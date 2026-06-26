# 100% Lighthouse Scores: Automated Performance Audits in CI


---

Lighthouse is Google's open-source tool for auditing web page quality across four categories: performance, accessibility, best practices, and SEO. Most teams run it manually, glance at the scores, and move on. The scores slowly degrade. Nobody notices until a customer complains about load times or a screen reader user cannot navigate the app.

There is a better approach. Wire Lighthouse into your CI pipeline with hard score thresholds. Every commit gets audited. If any category drops below 100, the build fails. You fix the regression immediately, while the change is fresh, instead of hunting through weeks of commits later.

The challenge for a SaaS app is that most pages live behind authentication. Lighthouse cannot log in. You need a test server that automatically authenticates every request, seeds realistic data, and serves the same pages your real users see. This post shows how to build that in Clojure, configure Lighthouse CI, and hit perfect scores across the board.

There is one idea under everything that follows: a 100 is not an achievement, it is a *contract*. The point of the threshold is not the round number -- it is that the number turns every quality property Lighthouse can measure (a `lang` attribute, a meta description, sufficient contrast, a fast first paint) into a regression-guarded invariant instead of a thing someone has to remember. The individual fixes below each look small; what makes them matter is that, once passing, the gate keeps them passing. So read the list not as tips but as the set of audits we are choosing to pin, and the test server as the apparatus that lets us pin the authenticated ones too.

## The Test Server

The Lighthouse test server lives on the test classpath -- it is never compiled into the production jar. Its job is simple: start a real instance of the app with a middleware that auto-authenticates every request as a test user.

```clojure
(ns myapp.lighthouse
  "Lighthouse CI server entry point.
  Starts a clean app instance with auto-authentication for auditing
  both public and authenticated pages. Never shipped to production --
  lives on the test classpath only."
  (:require
    [myapp.analytics.db :as analytics]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.time :as time]
    [myapp.web.assets :as assets]
    [myapp.web.routes :as routes]
    [org.httpkit.server :as http-kit]
    [reitit.ring :as ring]
    [ring.middleware.keyword-params :as keyword-params]
    [ring.middleware.params :as params]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as cookie])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)
```

The namespace requires the same modules as the real app: routes, database, assets, Ring middleware. It is the full application stack, not a mock.

### The Auto-Auth Test User

Lighthouse needs to see authenticated pages, but it has no way to perform a login flow. The solution is a middleware that injects a session on every request:

```clojure
(defn- test-email
  "Email for the auto-authenticated Lighthouse test user.
  Read at runtime (not namespace-load time) so it always reflects the
  active config. Must match :admin-email so admin pages are accessible."
  []
  (config/get-config :admin-email))

(defn- wrap-auto-auth
  "Inject a session with the test user on every request.
  Lighthouse sees authenticated pages without needing cookies."
  [handler]
  (fn [request] (handler (assoc-in request [:session :user-email] (test-email)))))
```

Two things to note here. First, the test email comes from the admin email in config, resolved at request time -- a function, not a top-level `def`, so it tracks whatever profile the server starts under rather than freezing the value when the namespace loads (the same runtime-config discipline as [the web-server chapter](05-web-server.md)'s `delay` and [the email login-flow chapter](19-auth-email-flow.md)'s SMTP config). This means the auto-authenticated user has admin privileges, so Lighthouse can audit admin pages too. Second, `wrap-auto-auth` is trivial -- one line of middleware that sets `:user-email` in the session map. The rest of the application sees a normal authenticated request.

This is convenient and dangerous in equal measure, so state the boundary plainly: **this server is an unauthenticated admin bypass by construction.** Anyone who can reach it is the admin -- no token, no login. That is fine for what it is (a throwaway instance the CI job starts, audits, and kills on loopback), but it must *never* bind a non-loopback interface or outlive the audit. It lives on the test classpath precisely so it cannot be reached from the production jar; keep it that way -- do not add it to a deployable alias, and bind it to `127.0.0.1`, never `0.0.0.0`.

### Seeding Test Data

An empty database produces empty pages. Empty pages get perfect performance scores but do not reflect what real users see. The test server seeds a user with accepted terms so the dashboard and other authenticated pages render with their full UI:

```clojure
(defn- seed-test-user!
  "Create a test user with terms accepted so dashboard renders fully."
  [conn]
  (let [now (time/now)]
    @(db/transact* conn
       [{:user/id (UUID/randomUUID)
         :user/email (test-email)
         :user/created-at now
         :user/active? true
         :user/terms-accepted-at now}])))
```

The `:user/terms-accepted-at` field is important. Without it, the app redirects to the terms acceptance page. With it, the dashboard renders normally. Seed the minimum data needed for pages to render their real content.

### Building the App

The test server reconstructs the Ring handler from the same route table the production app uses. The only difference is the middleware stack, which inserts `wrap-auto-auth` between session handling and the route handlers:

```clojure
(defn- build-app
  "Build the Ring handler with auto-auth in the middleware stack.
  Reconstructs the app from route data so auto-auth sits between
  session middleware (which reads cookies) and the handlers."
  []
  (let [session-store (cookie/cookie-store {:key (config/get-config :session-key)})]
    (ring/ring-handler
      ;; `:conflicts nil` mirrors prod — tolerates static-vs-dynamic
      ;; route overlaps (static path wins, dynamic ids fall through).
      (ring/router routes/routes {:conflicts nil})
      (ring/routes
        (ring/create-file-handler
          {:path "/"
           :root assets/static-root})
        (ring/create-default-handler))
      {:middleware [[params/wrap-params] [keyword-params/wrap-keyword-params]
                    [session/wrap-session
                     {:store session-store
                      :cookie-attrs {:http-only true
                                     :same-site :lax}}]
                    [wrap-auto-auth] [routes/wrap-locale]]})))
```

The middleware ordering matters. `wrap-params` and `wrap-keyword-params` run first to parse the request. `wrap-session` sets up cookie-based sessions. Then `wrap-auto-auth` injects the test user identity. Finally `wrap-locale` determines the locale for i18n. The handlers see a request that looks identical to a real authenticated request. Like the e2e server in [the e2e-testing chapter](20-e2e-testing.md), this one omits `:secure` (it runs over plain HTTP on `localhost`); the session cookie is `:same-site :lax`, which is all the auto-auth flow needs.

### The Entry Point

The `start!` function ties everything together. It creates fresh databases, loads the asset manifest (so `(assets/asset ...)` resolves the hashed URLs the views ask for), seeds the test user, and starts an HTTP server. It defaults to port 9876 -- the same port the e2e server in [the e2e-testing chapter](20-e2e-testing.md) uses; that is safe because the two run as separate, sequential CI steps and never bind the port at the same time:

```clojure
(defn start!
  "Start a Lighthouse audit server.
  Initializes a fresh database, seeds a test user, and starts http-kit.
  Prints a ready message that lhci startServerReadyPattern can match."
  [{:keys [port]
    :or {port 9876}}]
  (let [port (if (string? port) (parse-long port) port)]
    (db/create-database!)
    (analytics/create-database!)
    (assets/load-manifest!)
    (seed-test-user! (db/get-connection))
    (http-kit/run-server
      (build-app)
      {:port port
       :ip "127.0.0.1"})
    (println (str "Lighthouse server ready on port " port))
    @(promise)))
```

The final line -- `@(promise)` -- blocks the main thread indefinitely. Without it, the JVM would exit after starting the server. The `println` message is not decorative; Lighthouse CI uses it to know when the server is ready to accept requests, as we will see in the configuration below.

## Lighthouse CI Configuration

With the test server in place, the Lighthouse CI configuration tells `lhci` how to start it, which URLs to audit, and what scores to enforce. Create a `lighthouserc.js` at the project root:

```javascript
module.exports = {
  ci: {
    collect: {
      startServerCommand: 'clojure -X:test myapp.lighthouse/start!',
      startServerReadyPattern: 'Lighthouse server ready on port',
      startServerReadyTimeout: 60000,
      url: [
        'http://localhost:9876/',
        'http://localhost:9876/recipes',
        'http://localhost:9876/terms/welcome',
        'http://localhost:9876/dashboard',
        'http://localhost:9876/admin',
      ],
      numberOfRuns: 1,
      settings: {
        chromeFlags: '--no-sandbox --headless --disable-dev-shm-usage --disable-gpu',
      },
    },
    assert: {
      assertions: {
        'categories:performance': ['error', {minScore: 0.95}],
        'categories:accessibility': ['error', {minScore: 1}],
        'categories:best-practices': ['error', {minScore: 1}],
        'categories:seo': ['warn', {minScore: 1}],
      },
    },
  },
};
```

Let us walk through each section.

### `collect`

**`startServerCommand`** launches the Lighthouse test server using Clojure's `-X` (exec) flag. The `:test` alias adds the `test/` directory to the classpath, making `myapp.lighthouse` available. The function `start!` takes a map argument (the `-X` convention), so it receives `{}` by default and falls back to port 9876.

**`startServerReadyPattern`** is a string that `lhci` watches for in the server's stdout. When it sees `"Lighthouse server ready on port"`, it knows the server is accepting connections and begins the audit. This is why the `println` in `start!` matters -- it is a protocol between your server and the test runner.

**`startServerReadyTimeout`** gives the JVM 60 seconds to start. Clojure's startup is not instant, especially with AOT compilation disabled on the test classpath. Sixty seconds is generous but avoids flaky failures in CI where CPU is constrained.

**`url`** lists every page to audit. This covers the full range: the public landing page (`/`), the recipe browse page (`/recipes`), the terms-acceptance flow (`/terms/welcome`), the authenticated dashboard, and the admin panel. Because `wrap-auto-auth` is active, Lighthouse accesses all of them without authentication ceremony. (Audit the routes your app actually serves -- pointing Lighthouse at a path with no route loads a 404 page, which will tank the very scores you are trying to enforce.)

**`numberOfRuns`** is set to 1. Lighthouse defaults to multiple runs and takes the median, which is useful for catching performance variance. For CI, a single run keeps the feedback loop fast. If you have flaky scores, increase this.

**`chromeFlags`** configures headless Chrome for a CI environment. `--no-sandbox` is required in most Docker/CI environments where Chrome cannot create its sandbox. `--disable-dev-shm-usage` avoids shared memory issues in containers with limited `/dev/shm`.

### `assert`

The assertion block is where you set your standards. Each category maps to an assertion level and a minimum score:

- **Accessibility and Best Practices** are set to `error` with `minScore: 1` (100%). Any score below 100 fails the build. For a server-rendered app these are genuinely achievable, so we hold the line at perfect.
- **Performance** is set to `error` with `minScore: 0.95`. Performance scores carry a little run-to-run variance even on identical pages (CPU contention in CI, lab-throttling jitter), so a 0.95 floor fails real regressions without flaking on a single unlucky run that lands at 0.99. The page should still score 100 in practice; the threshold just leaves headroom for the measurement noise.
- **SEO** is set to `warn` with `minScore: 1`. It warns rather than errors because some SEO checks (like canonical URLs or structured data) may not apply to authenticated pages. The warning keeps it visible without blocking deploys.

## Running It

There is no wrapper script to write -- the whole audit is one command, run from the project root (where `lighthouserc.js` lives):

```bash
lhci autorun
```

`lhci autorun` handles everything: it starts your server (using `startServerCommand`), waits for the ready pattern, runs Lighthouse against each URL, collects the reports, and checks the assertions. If any assertion fails, it exits non-zero, which fails your CI step. This is exactly the command [the CI/CD chapter](24-ci-cd.md) wires into the pipeline -- no bespoke script in between. (If you prefer a short alias, a one-line `lhci autorun` wrapper is fine, but the companion repo deliberately keeps the canonical command in the workflow rather than hiding it behind a script.)

## Hitting 100%

Setting the thresholds is easy. Actually achieving them requires attention to several areas that Lighthouse checks. Here are the fixes that matter for a server-rendered Clojure app.

### Meta Tags

Every page needs viewport and description meta tags. Without them, Lighthouse docks you on SEO and best practices. In Hiccup, the base layout handles this:

```clojure
(defn- base-layout
  [locale & body]
  (h/html
    {:mode :html}
    (h/raw "<!DOCTYPE html>")
    [:html {:lang (name locale)}
     [:head [:meta {:charset "UTF-8"}]
      [:meta
       {:name "viewport"
        :content "width=device-width, initial-scale=1.0"}]
      [:meta
       {:name "description"
        :content (t locale :meta/description)}]
      ;; ...
      ]]))
```

This is the same escaping `h/html` layout from [the Hiccup views chapter](11-hiccup-views.md) -- not `hiccup.page/html5`, which [the asset-pipeline chapter](22-asset-pipeline.md) deliberately dropped because it does not escape string content. The `{:lang (name locale)}` attribute on the `<html>` element is easy to miss but Lighthouse checks for it. It tells screen readers and search engines what language the page is in. The viewport meta tag ensures mobile rendering works correctly. The description meta tag satisfies SEO audits.

Because these live in the shared base layout, every page gets them automatically. You set them once and never worry about a new page missing them.

### Font Display

Custom fonts are a common performance pitfall. If the browser waits for a font to download before rendering text, users see a blank page (or flash of invisible text). The fix is `font-display: swap` in your `@font-face` declaration:

```css
@font-face {
  font-family: "Geist";
  src: url("/fonts/GeistVF.woff2") format("woff2");
  font-weight: 100 900;
  font-style: normal;
  font-display: swap;
}
```

With `swap`, the browser immediately renders text in a fallback font, then swaps in the custom font when it finishes loading. Users see content instantly. Lighthouse gives you full marks on the "Ensure text remains visible during webfont load" audit.

Using a variable font (the `VF` in `GeistVF.woff2`) also helps performance. Instead of loading separate files for each weight (regular, bold, semibold), a single variable font file covers the entire `100 900` weight range. Fewer network requests, smaller total download.

### Semantic HTML

Lighthouse's accessibility audits check for proper HTML semantics. A `<div>` soup application will fail. The key elements:

- Use `<main>` for the primary content area. Lighthouse checks that exactly one `<main>` element exists.
- Use `<nav>` for navigation sections. This helps screen readers identify and skip navigation.
- Use proper heading hierarchy (`<h1>`, `<h2>`, etc.) without skipping levels.
- Use `<label>` elements associated with form inputs via the `for` attribute.

In the app layout, this looks like:

```clojure
(defn app-layout
  [locale user-email active-tab opts & body]
  (let [admin? (:admin? opts)]
    (base-layout
      locale
      [:div.min-h-screen.flex.flex-col.bg-surface-subtle
       (top-nav locale user-email active-tab admin?)      ;; renders <nav>
       [:main.flex-1.pb-16.md:pb-0                        ;; semantic <main>
        [:div.mx-auto.max-w-7xl.px-4.py-6.sm:px-6.lg:px-8
         body]]
       (bottom-tabs locale active-tab admin?)])))          ;; renders <nav>
```

And forms use explicit label associations:

```clojure
[:label.block.text-sm.font-medium.text-text-primary {:for "email"}
 (t locale :home/email-label)]
[:input {:type "email" :id "email" :name "email" :required true
         :placeholder (t locale :home/email-placeholder)}]
```

These are not difficult changes, but they are easy to forget. Lighthouse in CI catches the omission before it ships.

### Color Contrast

Lighthouse's accessibility audit checks that text has sufficient contrast against its background, following the WCAG 2.1 guidelines. This means your color palette needs to be designed with contrast ratios in mind from the start.

In the CSS theme definition:

```css
@theme {
  --color-text-primary: #0f172a;    /* near-black on white: ~15.4:1 ratio */
  --color-text-secondary: #64748b;  /* slate on white: ~4.6:1 ratio */
  --color-surface: #ffffff;
  --color-surface-subtle: #f8fafc;
}
```

The minimum ratio for normal text is 4.5:1 (AA standard). For large text it is 3:1. The `text-secondary` color at `#64748b` against a white background gives roughly a 4.6:1 ratio -- just above the threshold. If you had picked a lighter gray, Lighthouse would catch it.

The lesson: pick your grays carefully, and let Lighthouse verify the math. Eyeballing contrast is unreliable.

## How It Fits in CI

The Lighthouse audit runs alongside the other verification scripts:

| Command | What it checks |
|---|---|
| `./reformat` | Code formatting (zprint) |
| `./lint` | Static analysis (clj-kondo) + the "read time only through `myapp.time`" guard |
| `clojure -X:test` | Unit + integration tests (in-memory Datomic) |
| `npx playwright test` | Playwright end-to-end tests (auto-starts the e2e server) |
| `lhci autorun` | Lighthouse performance/accessibility/SEO audit |

`reformat` and `lint` are the two committed convenience scripts; the rest are plain one-liners the workflow calls directly. All five must pass before merging to main. The CI pipeline runs them in sequence. If Lighthouse fails, you know the exact commit that introduced the regression, and the Lighthouse report tells you exactly which audit failed and why.

## What You Have Now

After this setup, you have:

- **A Lighthouse test server** (`lighthouse.clj`) that starts your real application with auto-authentication, so Lighthouse can audit both public and authenticated pages without a login flow.
- **Test data seeding** that creates a user with accepted terms, ensuring pages render their full UI rather than empty shells.
- **A `lighthouserc.js` configuration** that audits five real URLs across your application and demands perfect accessibility and best-practices scores (and a 0.95 performance floor that leaves headroom for measurement noise).
- **A single `lhci autorun` command** that integrates with your existing CI pipeline -- no bespoke runner script in between.
- **Fixes for common Lighthouse failures**: viewport and description meta tags in the base layout, `font-display: swap` for custom fonts, semantic HTML elements (`<main>`, `<nav>`, `<label>`), and a color palette with sufficient contrast ratios.

The total cost is one Clojure file on the test classpath and one JavaScript configuration file -- no wrapper script, since `lhci autorun` is the whole command. The ongoing cost is zero -- Lighthouse runs automatically on every commit. The value is that performance, accessibility, and SEO regressions are caught at the moment they are introduced, not weeks later when someone happens to run an audit manually.

Start with 100. Stay at 100. The CI pipeline enforces it.
