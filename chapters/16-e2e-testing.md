# E2E Testing a Clojure Web App with Playwright


Unit tests prove your functions work. E2E tests prove your application works. There is a gap between the two that no amount of unit coverage can bridge: the gap where middleware ordering matters, where sessions expire, where a redirect chain lands somewhere unexpected, where the browser does not behave the way your mental model predicted. This post covers how to set up proper end-to-end testing for a Clojure web app using Playwright, with a dedicated test server, stubbed services, and test-only endpoints for controlling state.

By the end, you will have a self-contained E2E test suite that spins up a fresh Clojure server with in-memory databases, captures emails instead of sending them, and exercises your auth flow through a real browser.

## The Architecture

The E2E setup has four pieces:

1. **`e2e_server.clj`** -- A dedicated server entry point that boots the app with in-memory databases and stubbed external services.
2. **Test-only HTTP endpoints** -- Routes that only exist in the E2E server, letting Playwright inspect internal state (like captured emails).
3. **`playwright.config.js`** -- Configuration that tells Playwright to start the Clojure server before running tests and shut it down after.
4. **Spec files** -- The actual browser tests, living in an `e2e/` directory.

This architecture keeps E2E concerns completely separate from your production code. The test server never ships. The test endpoints never exist outside of the test process. Your production server is not modified or compromised in any way.

## The E2E Server

The heart of the setup is a dedicated server entry point. It looks like your production server, but with deliberate differences: in-memory databases, stubbed email sending, and extra routes for test control.

```clojure
(ns myapp.e2e-server
  "E2E test server entry point.
  Starts a clean app instance with real auth flow (no auto-auth).
  Stubs email sending to capture magic links in an atom, exposed via
  test-only HTTP endpoints for Playwright to fetch."
  (:require
    [jsonista.core :as json]
    [myapp.analytics.db :as analytics]
    [myapp.auth.email :as email]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.test-helpers :as h]
    [myapp.web.routes :as routes]
    [org.httpkit.server :as http-kit]
    [reitit.ring :as ring]
    [ring.middleware.keyword-params :as keyword-params]
    [ring.middleware.params :as params]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as cookie]))
```

### Capturing Emails Instead of Sending Them

The first problem is email. Your auth flow sends magic links via SMTP. In tests, you do not have an SMTP server, and even if you did, you would not want tests depending on network I/O. The solution is simple: an atom that collects emails, and a stubbed function that writes to it instead of sending.

```clojure
(def sent-emails
  "Atom collecting emails captured from the stubbed send-magic-link! function."
  (atom []))
```

The stub replaces the real `send-magic-link!` function at server startup (more on that below). Each call appends a map with the recipient and the magic link URL to the atom. From Playwright's perspective, the auth flow works identically -- the user enters their email, the server "sends" a magic link -- except the link ends up in memory instead of an inbox.

### Test-Only Endpoints

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
     :body (json/write-value-as-string emails)}))

(defn- clear-emails-handler
  "Clear all captured emails."
  [_request]
  (reset! sent-emails [])
  {:status 200
   :headers {"content-type" "application/json"}
   :body "{\"cleared\":true}"})
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

The `/test/emails` endpoint supports a `?to=` query parameter for filtering by recipient. This matters when you have tests running concurrently or multiple emails being sent in a single test. The `DELETE` method clears the atom, which you call in `beforeEach` to ensure test isolation.

This pattern generalizes. Any external service your app depends on -- payment processing, SMS, webhooks -- can be stubbed the same way: replace the side-effecting function, capture the calls in an atom, expose them via a test endpoint.

### Deterministic Configuration

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

A few things to note:

- **Port 9876** is fixed. The Playwright config needs to know where to find the server.
- **In-memory Datomic** (`datomic:mem://`) means every test run starts fresh. No leftover data from previous runs, no database cleanup scripts.
- **Deterministic keys** for session signing and token verification. These come from your test helpers module and are fixed byte arrays, not random. This means sessions and tokens behave identically across test runs.
- **SMTP config points nowhere real.** The email function is stubbed, so these values are never used, but they are present to keep the config shape consistent.

### Building the Ring Handler

The app handler is assembled the same way as production, but using the extended route table:

```clojure
(defn- build-app
  "Build the Ring handler with real auth (no auto-auth).
  Includes test-only routes for email capture."
  []
  (let [session-store (cookie/cookie-store
                        {:key (config/get-config :session-key)})]
    (ring/ring-handler
      (ring/router e2e-routes)
      (ring/routes
        (ring/create-file-handler
          {:path "/"
           :root "static"})
        (ring/create-default-handler))
      {:middleware [[params/wrap-params]
                    [keyword-params/wrap-keyword-params]
                    [session/wrap-session
                     {:store session-store
                      :cookie-attrs {:http-only true
                                     :same-site :lax}}]
                    [routes/wrap-locale]
                    [routes/wrap-no-cache-authenticated]]})))
```

The middleware stack is real. The session handling is real. The cookie store is real (just with a deterministic key). This is important: the E2E server must exercise the same middleware chain as production, or you are not testing what you think you are testing. The only differences should be external integrations (email, databases) and the addition of test control endpoints.

We keep `:same-site :lax` to match production -- the magic-link flow is a cross-context GET, and `:strict` would block the cookie on that navigation (more on this in [the email login-flow chapter](15-auth-email-flow.md)). We do drop `:secure`, because the e2e server runs over plain HTTP on `localhost`; a `:secure` cookie would never be sent. Those are the only deliberate cookie differences.

### The Start Function

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
    ;; Start server
    (http-kit/run-server
      (build-app)
      {:port port
       :ip "127.0.0.1"})
    (println (str "E2E server ready on port " port))
    @(promise)))
```

The sequence matters:

1. **Install config first.** Everything downstream reads from this.
2. **Stub external services.** `alter-var-root` replaces the var's root binding, so all code that calls `email/send-magic-link!` gets the stub. No dependency injection framework needed -- just Clojure's var system.
3. **Create fresh databases.** In-memory Datomic databases are created empty, with schemas applied by `create-database!`.
4. **Start the HTTP server.** http-kit listens on the configured port.
5. **Block forever.** `@(promise)` keeps the process alive. Playwright manages the lifecycle -- it starts this process before tests and kills it after.

The `alter-var-root` approach deserves a note. It is a blunt instrument -- it globally replaces the function. For E2E testing, this is exactly what you want. The test server is a separate process. There is no risk of affecting production code. And it means the stubbing works everywhere the function is called, without needing to thread a mock through the call stack.

## Playwright Configuration

With the server in place, Playwright needs to know how to start it and where to find it:

```javascript
// playwright.config.js

module.exports = {
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:9876',
    locale: 'en-US',
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
    timeout: 120_000,
    reuseExistingServer: !process.env.CI,
  },
};
```

The `webServer` block is the key piece. Playwright will:

1. Run the `command` to start the Clojure server.
2. Poll the `url` until it returns a 200 response. This is your `/health` endpoint -- a simple route that returns `{"status": "ok"}`.
3. Wait up to `timeout` milliseconds (120 seconds here, because JVM startup plus Datomic schema transacting takes time).
4. Run the tests once the server is healthy.
5. Kill the server process when tests finish.

The `reuseExistingServer` flag is useful during development. When not in CI, if a server is already running on port 9876, Playwright will use it instead of starting a new one. This lets you start the E2E server manually in a terminal, make changes, and re-run tests without the JVM startup penalty each time.

The `clojure -X:test` invocation uses Clojure's exec-fn mechanism. The `:test` alias in `deps.edn` adds the `test/` directory to the classpath, making `myapp.e2e-server` available. The `-X` flag calls the `start!` function directly, passing any additional key-value pairs as a map argument.

The Chrome launch options (`--no-sandbox`, `--disable-dev-shm-usage`) are for CI environments where Chrome runs as root or in containers with limited shared memory.

## The Auth Flow Spec

Now for the actual tests. Here is the complete auth spec that exercises the passwordless magic link flow:

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
  return `e2e-${Date.now()}@test.myapp.nl`;
}
```

Two helper functions set the stage. `getMagicLink` calls our test-only `/test/emails` endpoint to retrieve the magic link that was "sent" to a given address. `uniqueEmail` generates a timestamp-based email so each test gets its own user, even when tests run in parallel.

### Shared Registration Flow

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

  await page.getByRole('button', { name: 'I agree to the terms' }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}
```

This walks through the entire registration: enter email, get the magic link from the test endpoint, visit it, accept terms, land on the dashboard. Any test that needs a logged-in user calls this first.

### Test Isolation

Each test starts with a clean email inbox:

```javascript
test.beforeEach(async ({ request }) => {
  await request.delete('/test/emails');
});
```

This calls our `DELETE /test/emails` endpoint to clear the atom. Combined with unique email addresses per test, this ensures complete isolation between tests.

### Test: New User Registration

```javascript
test('new user registration', async ({ page, request }) => {
  const email = uniqueEmail();

  // Enter email on home page
  await page.goto('/');
  await page.fill('input[name="email"]', email);
  await page.getByRole('button', { name: 'Sign in' }).click();

  // Should see "check your email" content
  await expect(
    page.getByRole('heading', { name: 'Check your email' })
  ).toBeVisible();

  // Get magic link from captured emails and visit it
  const magicLink = await getMagicLink(request, email);
  await page.goto(magicLink);

  // New user -> redirected to terms acceptance
  await expect(page).toHaveURL(/\/terms\/welcome/);

  // Accept terms
  await page.getByRole('button', { name: 'I agree to the terms' }).click();

  // Should reach dashboard
  await expect(page).toHaveURL(/\/dashboard/);
});
```

This test verifies the full new-user flow from landing page to dashboard. It exercises: form submission, server-side email "sending," token verification, terms acceptance, session creation, and the final redirect.

### Test: Returning User Skips Terms

```javascript
test('returning user login skips terms', async ({ page, request }) => {
  const email = uniqueEmail();

  // Register first (creates user with terms accepted)
  await registerUser(page, request, email);

  // Logout
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.locator('input[name="email"]')).toBeVisible();

  // Login again with same email
  await page.fill('input[name="email"]', email);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(
    page.getByRole('heading', { name: 'Check your email' })
  ).toBeVisible();

  const magicLink = await getMagicLink(request, email);
  await page.goto(magicLink);

  // Should go directly to dashboard (skip terms)
  await expect(page).toHaveURL(/\/dashboard/);
});
```

This tests a critical branching point: returning users who have already accepted terms should land directly on the dashboard. The test registers a user, logs out, logs back in, and verifies the terms page is skipped. This is the kind of stateful behavior that is nearly impossible to unit test meaningfully -- you need the full session lifecycle.

### Test: Logout Prevents Dashboard Access

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

## The Test Runner Script

The final piece is a shell script that ties it together:

```bash
#!/usr/bin/env bash
cd "$(dirname "$0")"
playwright test --config playwright.config.js
```

That is all. The `cd` ensures the script works regardless of where you invoke it from -- important when running from a CI pipeline or a project root. Playwright reads the config, starts the Clojure server, waits for it to be healthy, runs the specs, and tears everything down.

Run it:

```bash
./e2etest
```

Playwright handles the lifecycle. You do not need to start the server manually (though you can, during development, thanks to `reuseExistingServer`).

## Design Decisions Worth Noting

**Why a separate server process instead of starting the server in-test?** Playwright expects to manage a server lifecycle via `webServer`. This is cleaner than trying to boot a JVM from within a Node.js test runner. It also means the Clojure server is a real process with real resource management, not something awkwardly embedded.

**Why `alter-var-root` instead of dependency injection?** For a test server that runs as its own process, global var replacement is the simplest approach. You are not composing test and production code in the same process. The E2E server is a standalone entry point. There is nothing to accidentally leak.

**Why in-memory Datomic instead of a test database?** Speed and isolation. In-memory databases are created in milliseconds, start empty, and disappear when the process exits. No cleanup, no port conflicts, no leftover state between test runs.

**Why test-only HTTP endpoints instead of reading the atom directly?** The tests run in a Node.js process. The server runs in a JVM process. They communicate over HTTP. The test endpoints are the bridge between these two worlds.

## What You Now Have

After following this setup, you have:

- A **dedicated E2E server** that boots your full application stack with in-memory databases and stubbed external services
- **Test-only endpoints** for inspecting server-side state from your browser tests, without modifying production code
- A **Playwright configuration** that manages the server lifecycle automatically
- **Auth flow specs** that exercise registration, login, logout, and access control through a real browser
- A **one-command test runner** that handles everything end-to-end

The pattern extends naturally. Each new feature that needs E2E coverage gets a new spec file in `e2e/`. If the feature involves an external service, you stub it in the E2E server and add a test endpoint if needed. The infrastructure is in place -- you just write tests.

Unit tests tell you your functions are correct. E2E tests tell you your application works. You need both.
