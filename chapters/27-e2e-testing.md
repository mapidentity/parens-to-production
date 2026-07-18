# E2E Testing a Clojure Web App with Playwright

Unit tests prove your functions work. E2E tests prove your application works. There is a gap between the two that no amount of unit coverage can bridge: the gap where middleware ordering matters, where sessions expire, where a redirect chain lands somewhere unexpected, where the browser does not behave the way your mental model predicted. Closing that gap is what this chapter is for, and closing it honestly means running the real thing: a dedicated Clojure test server, its external services stubbed, driven through an actual browser by Playwright.

The shape we are after is a self-contained suite that spins up a fresh server backed by in-memory databases, captures emails instead of sending them, and exercises the auth flow end to end -- the same path a user walks, with nothing mocked between the click and the database.

## The architecture

The E2E setup has four pieces:

1. **`e2e_server.clj`** -- A dedicated server entry point that boots the app with in-memory databases and stubbed external services.
2. **Test-only HTTP endpoints** -- Routes that only exist in the E2E server, letting Playwright inspect internal state (like captured emails).
3. **`playwright.config.js`** -- Configuration that tells Playwright to start the Clojure server before running tests and shut it down after.
4. **Spec files** -- The actual browser tests, living in an `e2e/` directory.

This architecture keeps E2E concerns completely separate from your production code. The test server never ships. The test endpoints never exist outside of the test process. Your production server is not modified or compromised in any way.

## The E2E server

The heart of the setup is a dedicated server entry point. It looks like your production server, but with three differences: in-memory databases, stubbed email sending, and extra routes for test control.

```clojure
(ns myapp.e2e-server
  "E2E test server entry point.
  Starts a clean app instance with real auth flow (no auto-auth).
  Stubs email sending to capture magic links in an atom, exposed via
  test-only HTTP endpoints for Playwright to fetch."
  (:require
    [clojure.data.json :as json]
    [myapp.analytics.db :as analytics]
    [myapp.auth.email :as email]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.test-helpers :as h]
    [myapp.web.assets :as assets]
    [myapp.web.routes :as routes]
    [org.httpkit.server :as http-kit]
    [reitit.ring :as ring]
    [ring.middleware.keyword-params :as keyword-params]
    [ring.middleware.params :as params]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as cookie]))
```

One require points forward. `myapp.analytics.db` is defined in [the admin dashboard chapter](28-admin-dashboard.md), not here; if you are building strictly in order, create it now from there (or take it from the repo), because the e2e server boots the full stack -- analytics database included -- and will not start without it.

### Capturing emails instead of sending them

The first problem is email. Your auth flow sends magic links via SMTP. In tests, you do not have an SMTP server, and even if you did, you would not want tests depending on network I/O. The solution is simple: an atom that collects emails, and a stubbed function that writes to it instead of sending.

```clojure
(def sent-emails
  "Atom collecting emails captured from the stubbed send-magic-link! function."
  (atom []))
```

The stub replaces the real `send-magic-link!` function at server startup (more on that below). Each call appends a map with the recipient and the magic link URL to the atom. From Playwright's perspective, the auth flow works identically -- the user enters their email, the server "sends" a magic link -- except the link ends up in memory instead of an inbox.

### Test-only endpoints

Now Playwright needs a way to retrieve those captured emails. We add two endpoints that only exist in the E2E server:

```clojure
(defn- get-emails-handler
  "Return captured emails as JSON, optionally filtered by ?to= query param."
  [request]
  (let [to (get-in request [:params :to])
        emails (cond->> @sent-emails
                 to (filter #(= (:to %) to)))]
    {:status 200
     :headers {"content-type" "application/json"}
     :body (json/write-str emails)}))

(defn- clear-emails-handler
  "Clear captured emails — optionally scoped via ?to=<addr>.
  With ?to= it drops only that recipient's emails so parallel workers
  don't trample each other's state. Without it, clears all (only safe
  in serial runs)."
  [request]
  (let [to (get-in request [:params :to])]
    (if to
      (swap! sent-emails (fn [es] (vec (remove #(= (:to %) to) es))))
      (reset! sent-emails []))
    {:status 200
     :headers {"content-type" "application/json"}
     :body "{\"cleared\":true}"}))
```

These get mounted alongside the app's real routes:

```clojure
(def ^:private e2e-routes
  "App routes plus test-only email capture endpoints."
  (conj
    routes/routes
    ["/test/emails"
     {:get get-emails-handler
      :delete clear-emails-handler}]))
```

The `/test/emails` endpoint supports a `?to=` query parameter for filtering by recipient. This matters when you have tests running concurrently or multiple emails being sent in a single test. The `DELETE` method can clear the atom -- but the specs never call it, for reasons the isolation section below makes concrete.

This pattern generalizes. Any external service your app depends on -- payment processing, SMS, webhooks -- can be stubbed the same way: replace the side-effecting function, capture the calls in an atom, expose them via a test endpoint.

### Deterministic configuration

The E2E server uses hardcoded configuration instead of reading from environment variables:

```clojure
(def ^:private e2e-config
  "Deterministic config for e2e tests."
  {:server {:port 9876
            :host "127.0.0.1"}
   :database-uri "datomic:mem://myapp-e2e"
   :analytics-database-uri "datomic:mem://myapp-e2e-analytics"
   :base-url "http://localhost:9876"
   :session-key h/test-session-key
   :signing-key h/test-signing-key
   :smtp {:host "localhost"
          :port 1025
          :tls false
          :user nil
          :pass nil
          :from "test@myapp.lan"}})
```

Four choices in that block are worth spelling out:

- **Port 9876** is fixed. The Playwright config needs to know where to find the server.
- **In-memory Datomic** (`datomic:mem://`) means every test run starts fresh. No leftover data from previous runs, no database cleanup scripts.
- **Deterministic keys** for session signing and token verification. These come from your test helpers module and are fixed byte arrays, not random. This means sessions and tokens behave identically across test runs.
- **SMTP config points nowhere real.** The email function is stubbed, so these values are never used, but they are present to keep the config shape consistent.

### Building the Ring handler

The app handler is assembled the same way as production, but using the extended route table:

```clojure
(defn- build-app
  "Build the Ring handler with real auth (no auto-auth).
  Includes test-only routes for email capture."
  []
  (let [session-store (cookie/cookie-store
                        {:key (config/get-config :session-key)})]
    (ring/ring-handler
      ;; `:conflicts nil` mirrors prod — tolerates the static-vs-dynamic
      ;; overlap, matching conflicting routes in declaration order (so
      ;; `/recipes/new`, declared first, wins and other ids fall through).
      (ring/router e2e-routes {:conflicts nil})
      (ring/routes
        (ring/create-file-handler
          {:path "/"
           :root assets/static-root})
        (ring/create-default-handler))
      {:middleware [[params/wrap-params]
                    [keyword-params/wrap-keyword-params]
                    [session/wrap-session
                     {:store session-store
                      :cookie-attrs {:http-only true
                                     :same-site :lax}}]
                    [routes/wrap-locale]
                    [routes/wrap-no-cache-authenticated]
                    ;; The strict CSP is part of what production serves, so the
                    ;; e2e stack must exercise it too — otherwise the tests pass
                    ;; under a policy real users never get.
                    [routes/wrap-csp]]})))
```

The middleware stack is real. The session handling is real. The cookie store is real (just with a deterministic key). This is important: the E2E server must exercise essentially the same middleware chain as production, or you are not testing what you think you are testing. The differences are external integrations (email, databases) and the test-control endpoints -- plus one middleware layer: production's innermost `wrap-errors` is absent, so these tests cover every layer except the styled-500 path (a raw exception in a failing test is what you want anyway).

We keep `:same-site :lax` to match production -- the magic-link flow is a cross-context GET, and `:strict` would block the cookie on that navigation (more on this in [the email login-flow chapter](25-auth-email-flow.md)). Note too that `:secure` is *absent* from those attrs -- a simplification, not the necessity it looks like. Chromium runs these tests over `http://localhost`, and [the web-server chapter](05-web-server.md) is careful that it *would* send a `:secure` cookie there, treating `http://localhost` as a potentially-trustworthy origin; dropping `:secure` just keeps the test cookie from leaning on that browser-specific carve-out. That, plus the omitted explicit `:cookie-name` and 30-day `:max-age`, is where the e2e cookie departs from production.

### The start function

Everything comes together in `start!`:

```clojure
(defn start!
  "Start the e2e test server.
  Sets deterministic config, stubs email sending, initializes fresh DB,
  and starts http-kit. Blocks indefinitely (for Playwright webServer)."
  [{:keys [port]
    :or {port 9876}}]
  (let [port (if (string? port) (parse-long port) port)]
    ;; Install deterministic config
    (alter-var-root #'config/config
      (constantly (delay e2e-config)))
    ;; Stub email sending -- capture to atom instead of SMTP
    (alter-var-root
      #'email/send-magic-link!
      (constantly
        (fn [_locale email token base-url]
          (swap! sent-emails conj
            {:to email
             :magic-link (str base-url "/auth/verify?token=" token)})
          {:error :SUCCESS})))
    ;; Initialize fresh in-memory databases
    (db/create-database!)
    (analytics/create-database!)
    ;; Load the asset manifest so (assets/asset ...) resolves in views.
    (assets/load-manifest!)
    ;; Start server
    (http-kit/run-server
      (build-app)
      {:port port
       :ip "127.0.0.1"})
    (println (str "E2E server ready on port " port))
    @(promise)))
```

The stub's return value looks odd on purpose. `{:error :SUCCESS}` is the exact shape the real `send-magic-link!` returns on a successful send: an `:error` key whose value is `:SUCCESS` (failures return `{:error :FAIL}`). Mirroring it keeps the stub a faithful stand-in rather than an approximation.

The sequence matters:

1. **Install config first.** Everything downstream reads from this.
2. **Stub external services.** `alter-var-root` replaces the var's root binding, so all code that calls `email/send-magic-link!` gets the stub. No dependency injection framework needed -- just Clojure's var system.
3. **Create fresh databases.** In-memory Datomic databases are created empty, with schemas applied by `create-database!`.
4. **Start the HTTP server.** http-kit listens on the configured port.
5. **Block forever.** `@(promise)` keeps the process alive. Playwright manages the lifecycle -- it starts this process before tests and kills it after.

The `alter-var-root` approach deserves a note. It is a blunt instrument -- it globally replaces the function. For E2E testing, this is exactly what you want. The test server is a separate process. There is no risk of affecting production code. And it means the stubbing works everywhere the function is called, without needing to thread a mock through the call stack.

## Playwright configuration

With the server in place, Playwright needs to know how to start it and where to find it:

```javascript
// playwright.config.js

module.exports = {
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:9876',
    locale: 'en-US',
    // Diagnostics on failure only (free on green runs): a trace records the
    // whole run -- DOM snapshots, network, console -- replayable with
    // `npx playwright show-trace`; the screenshot captures the final state.
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        browserName: 'chromium',
        launchOptions: {
          args: ['--no-sandbox', '--disable-dev-shm-usage'],
        },
      },
    },
  ],
  webServer: {
    command: 'clojure -X:test myapp.e2e-server/start!',
    url: 'http://localhost:9876/health',
    timeout: 300_000,
    reuseExistingServer: !process.env.CI,
    // Pipe the server's stdout/stderr to the Playwright reporter so a
    // startup failure shows up in CI logs next to the timeout message.
    stdout: 'pipe',
    stderr: 'pipe',
  },
};
```

The `webServer` block is the key piece: Playwright owns the server's whole lifecycle. It runs `command` to start the Clojure server, polls `url` -- the `/health` endpoint -- until it answers 200, runs the tests, and kills the process when they finish. The generous `timeout` (300 seconds) is deliberate: JVM startup, which loads the Datomic peer and compiles every required namespace from source, can be slow on a cold, CPU-constrained CI runner, so a high ceiling keeps a legitimately slow boot from being read as a failure. The `stdout`/`stderr: 'pipe'` settings are separate insurance: when the server does fail to start, they surface its error in the logs instead of leaving you only the timeout.

The `reuseExistingServer` flag is useful during development. When not in CI, if a server is already running on port 9876, Playwright will use it instead of starting a new one. This lets you start the E2E server manually in a terminal, make changes, and re-run tests without the JVM startup penalty each time.

The `clojure -X:test` invocation uses Clojure's exec-fn mechanism. The `:test` alias in `deps.edn` adds the `test/` directory to the classpath, making `myapp.e2e-server` available. The `-X` flag calls the `start!` function directly, passing any additional key-value pairs as a map argument.

The Chrome launch options (`--no-sandbox`, `--disable-dev-shm-usage`) are for CI environments where Chrome runs as root or in containers with limited shared memory.

## The auth flow spec

With the harness in place, the tests themselves are almost anticlimactic -- which is exactly the sign the harness is right. Here is the complete auth spec exercising the passwordless magic-link flow:

```javascript
// e2e/auth.spec.js

const { test, expect } = require('@playwright/test');

/** Fetch the most recent magic link sent to the given email address. */
async function getMagicLink(request, email) {
  const res = await request.get(
    `/test/emails?to=${encodeURIComponent(email)}`
  );
  const emails = await res.json();
  expect(emails.length).toBeGreaterThan(0);
  return emails[emails.length - 1]['magic-link'];
}

/** Generate a unique email for test isolation. */
function uniqueEmail() {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@test.myapp.lan`;
}
```

Two helper functions set the stage. `getMagicLink` calls our test-only `/test/emails` endpoint to retrieve the magic link that was "sent" to a given address. `uniqueEmail` generates a fresh address per test -- a timestamp plus a random suffix, so two tests that begin in the same millisecond still get distinct users when the suite runs in parallel.

Notice what `getMagicLink` does *not* do: it fires a single `request.get` with no retry loop, yet it never races the server. Two things make that safe. First, Playwright's assertions auto-wait. `expect(...).toBeVisible()` polls the live page until the element appears or the timeout fires, so the preceding assertion that "Check your email" is visible has already blocked until the server finished handling the sign-in POST. That is why the specs carry no manual `sleep`s or polling anywhere. Second, that POST captures the email synchronously: the `request-magic-link` handler calls `send-magic-link!` inline, before it issues its redirect (see [the auth chapter](25-auth-email-flow.md)), so the stub has appended to the atom by the time the confirmation page renders. The email is guaranteed present the moment the browser sees the heading. That second guarantee is a real coupling worth naming: move sending onto a background thread or a queue and the ordering breaks, at which point `getMagicLink` would need a retry of its own.

### Shared registration flow

Since multiple tests need a registered user, the registration flow is extracted into a helper:

```javascript
/** Register a new user through the full flow. */
async function registerUser(page, request, email) {
  await page.goto('/');
  await page.fill('input[name="email"]', email);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(
    page.getByRole('heading', { name: 'Check your email' })
  ).toBeVisible();

  const magicLink = await getMagicLink(request, email);
  await page.goto(magicLink);
  await expect(page).toHaveURL(/\/terms\/welcome/);

  await page.getByRole('button', { name: 'Agree and start cooking' }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}
```

This walks through the entire registration: enter email, get the magic link from the test endpoint, visit it, accept terms, land on the dashboard. Any test that needs a logged-in user calls this first.

### Test isolation

The tempting move is a `beforeEach` that wipes the inbox with an unscoped `DELETE /test/emails` before every test. The handler's own docstring warns you off: without `?to=`, a clear is only safe in serial runs -- and this suite is not serial. The config sets neither `workers` nor `fullyParallel`, so Playwright's defaults apply: tests within a file run in order, but separate spec files land in separate workers and run concurrently. One worker's blanket clear can land between another worker's form submission and its `getMagicLink` call, wiping the email it was about to read -- the kind of race that fails one run in twenty and never on your machine.

So the specs clear nothing. Isolation comes from never sharing state in the first place: every test generates a unique address, reads back only that address through the `?to=` filter, and takes the most recent match. The atom only ever grows, and that is fine -- entries from other tests, other workers, or (under `reuseExistingServer`) earlier runs are invisible behind the filter. No test can read *mail* another test wrote, so interleaved workers never cross wires in the inbox. (One caveat keeps this honest: it is not literally shared-nothing. The per-IP magic-link rate limiter -- ten sends per IP per fifteen minutes -- is a single process-global bucket every worker shares, since they all sign in from the same loopback address. It is the one place a large enough parallel suite could interfere with itself, and that limit is the number to raise for the e2e profile if it ever does.) The `DELETE` endpoint stays available for a suite that does accumulate enough state to care, and its `?to=` scoping keeps even that operation parallel-safe.

### Test: new user registration

The first test spells the whole path out inline rather than calling the helper. That is deliberate: it is the one place the entire flow is visible end to end as executable documentation -- enter email, retrieve the magic link, visit it, get redirected to `/terms/welcome` as a new account, accept, and land on `/dashboard` -- with a closing assertion that the dashboard's own heading actually rendered, not just that the URL changed:

```javascript
test('new user registration', async ({ page, request }) => {
  const email = uniqueEmail();

  await page.goto('/');
  await page.fill('input[name="email"]', email);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(
    page.getByRole('heading', { name: 'Check your email' })
  ).toBeVisible();

  const magicLink = await getMagicLink(request, email);
  await page.goto(magicLink);

  // New user → redirected to terms acceptance
  await expect(page).toHaveURL(/\/terms\/welcome/);
  await page.getByRole('button', { name: 'Agree and start cooking' }).click();

  // Should reach the dashboard
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(
    page.getByRole('heading', { name: 'Your recipes' })
  ).toBeVisible();
});
```

That single test exercises form submission, server-side email "sending," token verification, terms acceptance, session creation, and the final redirect. The other tests in this file do not re-walk it: they call `registerUser`, whose inline `expect`s carry the same assertions, so within `auth.spec.js` the full flow is written out exactly once, here. The recipe specs in the next file carry their own lighter `registerUser`, one that drives straight to the dashboard without re-asserting the confirmation page or the terms redirect. That is deliberate rather than sloppy reuse: their subject is what happens *after* login, so the auth assertions stay here, where verifying the login is the point.

### Test: returning user skips terms

```javascript
test('returning user login skips terms', async ({ page, request }) => {
  const email = uniqueEmail();
  await registerUser(page, request, email);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.locator('input[name="email"]')).toBeVisible();

  await page.fill('input[name="email"]', email);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(
    page.getByRole('heading', { name: 'Check your email' })
  ).toBeVisible();

  const magicLink = await getMagicLink(request, email);
  await page.goto(magicLink);

  // Should go directly to the dashboard (terms already accepted)
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(
    page.getByRole('heading', { name: 'Your recipes' })
  ).toBeVisible();
});
```

This tests a critical branching point: returning users who have already accepted terms should land directly on the dashboard. The test registers a user, logs out, logs back in, and verifies the terms page is skipped and the dashboard itself renders. This is the kind of stateful behavior that is nearly impossible to unit test meaningfully -- you need the full session lifecycle.

### Test: logout prevents dashboard access

```javascript
test('logout prevents dashboard access', async ({ page, request }) => {
  const email = uniqueEmail();

  // Register and login
  await registerUser(page, request, email);

  // Sign out
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.locator('input[name="email"]')).toBeVisible();

  // Try to access dashboard directly -- should redirect to home
  await page.goto('/dashboard');
  await expect(page.locator('input[name="email"]')).toBeVisible();
});
```

A security test: after logout, navigating directly to `/dashboard` should redirect to the home page (the login form). This verifies that session destruction actually works, not just that the UI hides the button.

## Running the suite

There is no wrapper script to write -- running the suite is one command from the project root, where `playwright.config.js` lives:

```bash
npx playwright test
```

Playwright reads the config, starts the Clojure server (via the `webServer` block), waits for `/health` to return 200, runs every spec in `e2e/`, and tears everything down. You do not need to start the server manually (though you can, during development, thanks to `reuseExistingServer`). [The CI/CD chapter](34-ci-cd.md) runs the same suite in the pipeline, there through the container's own `playwright` binary rather than `npx`, but against the identical config, server, and specs.

The companion repo ships two spec files: `e2e/auth.spec.js` (the magic-link login, logout, and route-protection flows built above) and `e2e/recipes.spec.js` (recipe creation, edit history, diffs, and fork lineage). `npx playwright test` runs both.

The recipe specs are where E2E earns the most in this application, because they check UI derived from Datomic's history -- the book's central subject -- through a real browser. One excerpt, from the edit test, after the recipe has been changed and saved:

```javascript
// History shows two versions
await page.getByRole('link', { name: /Version history/ }).click();
await expect(page.getByText('Version 1')).toBeVisible();
await expect(page.getByText('Version 2')).toBeVisible();

// Diff from previous shows the added line
await page.getByRole('link', { name: 'Changes from previous' }).click();
await expect(page.locator('.diff-add', { hasText: 'vanilla' })).toBeVisible();
```

No unit test reaches that far. The assertion is that a real edit, recorded as a new Datomic version, surfaces in the rendered history and produces a diff with the added ingredient line marked `.diff-add`. The fork test makes the same kind of check for lineage: after forking, the child page reads "Descends from 1 ancestor" and links back to its parent.

## When a test fails

A green suite tells you little; a red one is where the harness has to prove it was worth building. Playwright is configured so a failure leaves evidence rather than a bare stack trace, and both artifacts cost nothing on a passing run:

- **The trace** (`trace: 'retain-on-failure'`) is a full recording of the failed run: a DOM snapshot, the network log, and the console at every step. Open it with `npx playwright show-trace`, scrub to the step that failed, and read the page's actual state at that instant instead of inferring it from an error message.
- **The screenshot** (`screenshot: 'only-on-failure'`) captures the final rendered page, often enough on its own to show that a selector matched the wrong element or a redirect never happened.

To reproduce a failure interactively, three flags open the run up. `--headed` runs the browser visibly instead of headless. `--ui` opens Playwright's watch-mode interface, where you step through actions and re-run a single test in isolation. `--debug` launches the inspector with the run paused, so you can advance action by action and try selectors against the live page.

What the config does not set is retries.

> **Decision -- why retries stay at 0.** Playwright can re-run a failed test several times and pass the suite if any attempt succeeds. We leave that off. A flaky E2E test is a defect, not weather: a bug in the test or in the app, of exactly the kind the isolation section dissected, a cross-worker race, an ordering assumption, a missing wait. Retrying does not fix the defect; it hides it, letting a real bug reach users because the build stayed green. The cost of refusing retries is honest and worth stating: a genuinely environmental flake, a CI runner that stalls or a port slow to free, fails the whole build instead of passing on the second try. We take that cost, because a suite that retries its way to green is a suite that lies about how reliable the application is. When a test does flake, the trace tells you which kind you are looking at.

None of this makes E2E free to keep. A browser test breaks when the interface it drives changes: a renamed button, a moved heading, a new redirect on a path it walks. It breaks in ways a unit test never would, because it is coupled to rendered text and roles rather than to a function signature. That coupling is the price of testing the thing users actually touch, and there is no version of E2E that avoids it. The harness holds the cost down, since isolation is structural, failures leave traces, and one command runs everything, but each spec is a maintained asset, not a write-once one.

## Why the harness is shaped this way

Five choices in the setup are worth defending directly, because for each the tempting alternative deserves a named answer.

**Why Playwright at all, in a Clojure codebase?** It brings a second language and runtime into the repo, which is a real cost to weigh. Three things pay for it. Its assertions are web-first: `expect(...).toBeVisible()` retries against the live page until it passes or times out, so the specs need no manual waits and do not flake on timing. Its `webServer` block owns the server lifecycle, the spine of this whole harness, so nothing has to hand-roll start, health-poll, and teardown. And when a test fails, its trace viewer replays the run step by step, tooling the WebDriver protocol behind Selenium and Etaoin does not match. Etaoin, the Clojure-native option, would keep the tests in one language but hands back the auto-waiting and the lifecycle management this design leans on. Node, meanwhile, is not a new dependency here: Tailwind and Lighthouse CI already run through it, so Playwright is one more use of a runtime the project already carries.

**Why a separate server process instead of starting the server in-test?** Two runtimes, kept cleanly apart. The tests run on Node and the server on the JVM, and booting and supervising a JVM from inside a Node test runner is awkward at best. Keeping them separate processes buys more than tidiness: the process boundary is the seam that makes stubbing safe, since nothing test-only shares memory with anything that could ship, and the heavy JVM startup happens once, which is what `reuseExistingServer` exploits to skip it during development. Playwright's `webServer` already knows how to drive a real external process; embedding would trade all of that for a single-language illusion.

**Why `alter-var-root` instead of dependency injection?** Dependency injection exists to swap implementations without a global mutation, but its payoff is isolation inside a shared process, and this server has no shared process to isolate within. It is a standalone entry point in its own JVM, so (as the note above spells out) the global replacement costs nothing it would otherwise protect, while stubbing the function everywhere it is called without threading a seam through the code. DI here would be machinery bought to solve a problem the process boundary already solved.

**Why in-memory Datomic instead of a test database?** Speed and isolation. In-memory databases are created in milliseconds, start empty, and disappear when the process exits. No cleanup, no port conflicts, no leftover state between test runs.

**Why capture emails in an atom behind a test-only endpoint, rather than a real mail-catcher?** The honest alternatives are a Mailpit or MailHog container the stub delivers to and the tests poll, or letting the test side query Datomic directly. Both add a moving part this design does not need. A mail-catcher is a second service to start, health-check, and tear down, the very lifecycle weight in-memory Datomic was chosen to avoid, and it puts real SMTP I/O back in the loop the stub exists to remove. Querying the JVM's state from Node is not really on offer: the atom lives in one process and the tests in another. An HTTP endpoint the e2e server already serves is the smallest bridge across that boundary, and it reads captured state the same way the browser reads everything else, over HTTP against the running server.

## Where this leaves us

The harness, not the specs, is the deliverable. A dedicated E2E server boots the full stack against in-memory databases with its external services stubbed; test-only endpoints let the browser inspect server-side state without a line of that machinery reaching production; a single `webServer` block hands Playwright the whole lifecycle; and one command runs it all. The specs that ride on top -- registration, login, logout, route protection -- are the cheap part once that scaffolding exists.

And it extends cheaply, if never quite for free. A new feature needing coverage gets a new spec file; a new external dependency gets stubbed, and if a test needs to observe its effects, a test endpoint. The infrastructure is the one-time investment, already paid for; the specs riding on it are the standing cost, the same bargain unit testing offers one layer down, which is why a serious application wants both rather than choosing between them.
