# Testing a Clojure App: Fixtures, Helpers, and Coverage


---

You have a web app. It loads config, connects to Datomic, defines routes, renders pages. It works when you try it in the browser. But "works when I try it" is not a testing strategy. The moment you refactor a handler or change a route, you need something that tells you -- in seconds -- whether you broke anything.

This post covers the testing infrastructure we put in place early: a test helpers module with fixtures for fresh in-memory databases and deterministic config, a set of initial tests for configuration and routing, a coverage tool with a minimum threshold, and a shell script that ties it all together. None of this is exotic. That is the point. Setting up boring, reliable test infrastructure early pays dividends for every feature that follows.

## The Testing Philosophy: Fresh State Per Test

The core principle is simple: every test gets a fresh in-memory Datomic database. No shared mutable state between tests. No "run tests in this order." No cleanup logic that can silently fail.

Datomic makes this easy. Its in-memory mode (`datomic:mem://`) creates a real database with the full query engine, but it lives entirely in the JVM. Create it, transact your schema, run your test, delete it. Each test is isolated by construction, not by discipline.

This matters more than it sounds. When tests share a database, you get two failure modes that waste enormous amounts of time: tests that pass individually but fail together (ordering dependency), and tests that fail individually but pass together (one test's side effects are another test's setup). Both are awful to debug. Fresh state eliminates both.

## The Test Helpers Module

All shared test infrastructure lives in a single file: `test/myapp/test_helpers.clj`. It provides three things -- database fixtures, deterministic config, and a request builder.

```clojure
(ns myapp.test-helpers
  "Shared test fixtures and utilities.
  Provides a fresh in-memory Datomic DB per test, deterministic config values,
  and a Ring request builder."
  (:require
    [datomic.api :as d]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.db.schema :as schema]
    ;; only needed for the optional analytics fixture below;
    ;; this namespace arrives in the admin dashboard chapter. Drop this line if your app has no analytics DB.
    [myapp.analytics.db :as analytics]))
```

### The Database Fixture

The centerpiece is `with-test-db`, a Clojure test fixture that creates a throwaway Datomic database for each test:

```clojure
(def ^:dynamic *conn*
  "Bound to a fresh Datomic connection per test by the with-test-db fixture."
  nil)

(defn with-test-db
  "Fixture: creates a fresh in-memory Datomic DB per test.
   Binds *conn* and stubs db/get-connection."
  [f]
  (let [uri (str "datomic:mem://myapp-test-" (System/nanoTime))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn schema/schema)
      (binding [*conn* conn]
        (with-redefs [db/get-connection (fn [] *conn*)]
          (f)))
      (d/delete-database uri))))
```

Here is what happens step by step:

1. **Unique URI per test.** `System/nanoTime` ensures no two tests collide, even when running in parallel.
2. **Create and connect.** A fresh in-memory database, indistinguishable from a real one for query purposes.
3. **Transact the schema.** The test database has the same schema as production. You are testing against the real data model.
4. **Bind and stub.** `*conn*` holds the connection for tests that need direct access. `with-redefs` makes `db/get-connection` return this test connection, so application code that calls `(db/get-connection)` transparently gets the test database.
5. **Clean up.** After the test function `f` runs, the database is deleted. No residue.

The same pattern applies to the analytics database, if your app has one. It uses the `analytics` alias from the `:require` above (`myapp.analytics.db`, which we build in [the admin dashboard chapter](18-admin-dashboard.md) -- omit both the require and this fixture until then):

```clojure
(def ^:dynamic *analytics-conn*
  "Bound to a fresh analytics Datomic connection per test."
  nil)

(defn with-test-analytics-db
  "Fixture: creates a fresh in-memory analytics DB per test.
   Binds *analytics-conn* and stubs analytics/get-connection and analytics/get-db."
  [f]
  (let [uri (str "datomic:mem://myapp-analytics-test-" (System/nanoTime))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn analytics/schema)
      (binding [*analytics-conn* conn]
        (with-redefs [analytics/get-connection (fn [] *analytics-conn*)
                      analytics/get-db (fn [] (d/db *analytics-conn*))]
          (f)))
      (d/delete-database uri))))
```

### Deterministic Config

Tests should not depend on your local `config.edn`. A test that passes on your machine but fails in CI because of a config difference is worse than useless -- it builds false confidence. The test helpers define a fixed config map:

```clojure
(def test-signing-key
  "Deterministic signing key for tests."
  (.getBytes "test-signing-key-32-bytes-long!!" "UTF-8"))

(def test-session-key
  "Deterministic 16-byte session key for tests."
  (.getBytes "0123456789abcdef" "UTF-8"))

(def test-config
  "Deterministic config for tests."
  {:server {:port 3000
            :host "0.0.0.0"}
   :base-url "https://test.myapp.lan"
   :session-key test-session-key
   :signing-key test-signing-key
   :admin-email "admin@test.myapp.lan"
   :smtp {:host "localhost"
          :port 1025
          :tls false
          :user nil
          :pass nil
          :from "test@myapp.lan"}})
```

And a fixture that installs it:

```clojure
(defn with-test-config
  "Fixture: stubs myapp.config/config with deterministic test values."
  [f]
  (with-redefs [config/config (delay test-config)]
    (f)))
```

This uses `with-redefs` to replace the `config/config` delay with one that resolves to the test map. Any code that calls `(config/get-config :server :port)` during the test will get `3000`, deterministically.

### The Request Builder

For testing Ring handlers, you need request maps. Writing them by hand every time is tedious and error-prone. A small helper takes care of the boilerplate:

```clojure
(defn request
  "Build a minimal Ring request map. Defaults locale to :nl."
  [method uri &
   {:keys [session params locale]
    :or {locale :nl}}]
  (cond-> {:request-method method
           :uri uri
           :locale locale}
    session (assoc :session session)
    params (assoc :params params)))
```

Usage in tests:

```clojure
;; Simple GET
(h/request :get "/dashboard")

;; POST with params
(h/request :post "/auth/request" :params {:email "user@example.com"})

;; Authenticated request
(h/request :get "/dashboard" :session {:user-email "user@example.com"})
```

The `cond->` threading macro keeps it clean -- optional keys are only added when provided.

## Example Test: Configuration

The config tests verify that the configuration system works correctly -- loading profiles, producing the right key types, and supporting nested path access:

```clojure
(ns myapp.config-test
  "Tests for config loading: expected keys, crypto key sizes, nested path access."
  (:require
    [clojure.test :refer [deftest is]]
    [myapp.config :as config]))

(deftest dev-profile-loads
  (let [cfg (config/load-config :dev)]
    (is (map? cfg))
    (is (contains? cfg :server))
    (is (contains? cfg :session-key))
    (is (contains? cfg :signing-key))
    (is (contains? cfg :smtp))
    (is (contains? cfg :base-url))))

(deftest session-key-is-16-bytes
  (let [cfg (config/load-config :dev)]
    (is (bytes? (:session-key cfg)))
    (is (= 16 (alength ^bytes (:session-key cfg))))))

(deftest signing-key-is-byte-array
  (let [cfg (config/load-config :dev)]
    (is (bytes? (:signing-key cfg)))))

(deftest get-config-nested-path
  (with-redefs [config/config (delay {:server {:port 3000}})]
    (is (= 3000 (config/get-config :server :port)))))

(deftest get-config-missing-key
  (with-redefs [config/config (delay {:server {:port 3000}})]
    (is (nil? (config/get-config :nonexistent)))))
```

These tests are straightforward, but they catch real problems:

- `dev-profile-loads` ensures all required config keys exist. If someone removes `:smtp` from `config.edn`, this fails immediately.
- `session-key-is-16-bytes` verifies the crypto key size constraint. Ring session encryption requires exactly 16 bytes. A 15-byte key would cause a cryptic runtime error.
- `get-config-nested-path` and `get-config-missing-key` verify the `get-config` accessor works for both present and absent keys. Note how these tests use `with-redefs` directly to set up minimal config -- they do not need the full test fixtures.

## Example Test: Routes

This section builds one self-contained example to teach a *technique* -- data-driven route testing -- rather than a file you will find verbatim in the companion repo. There, the web layer is covered by `test/myapp/web/handler_smoke_test.clj` (each route returns a sane status) and `test/myapp/web/security_test.clj` (the CSP, auth gates, and escaping). The `routes-test` namespace below is the simplest place to show the pattern; adapt its route list to whatever your app actually serves.

The routes tests verify that the routing table is correct and that the app handles edge cases properly:

```clojure
(ns myapp.web.routes-test
  "Tests for route resolution: all paths resolve, unknown paths 404, wrong methods 405."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [myapp.test-helpers :as h]
    [myapp.web.routes :as routes]
    [reitit.core :as r]
    [reitit.ring :as ring]))

(use-fixtures :once h/with-test-config)
```

The `:once` fixture applies `with-test-config` once for the entire namespace, rather than per test. Config is read-only during tests, so sharing it is safe and faster.

### Route Resolution

Rather than testing each route individually, a data-driven approach lists all expected routes and verifies them in a loop:

```clojure
(def ^:private router
  "The application router, built once for path-resolution tests."
  (ring/router routes/routes))

(defn- match
  "Resolve a path against the app router (method is checked by the test).
   Returns the reitit Match or nil."
  [_method path]
  (r/match-by-path router path))

(def ^:private expected-routes
  "All application routes that should resolve."
  [["/" :get]
   ["/auth/request" :post]
   ["/auth/sent" :get]
   ["/auth/verify" :get]
   ["/auth/logout" :post]
   ["/terms/welcome" :get]
   ["/terms/accept" :post]
   ["/recipes" :get]
   ["/recipes/new" :get]
   ["/recipes/:id" :get]
   ["/dashboard" :get]
   ["/admin" :get]])

(deftest all-routes-resolve
  (doseq [[path method] expected-routes]
    (let [m (match method path)]
      (is (some? m) (str "Route " method " " path " should resolve"))
      (is
        (some? (get-in m [:data method]))
        (str "Route " method " " path " should have a handler")))))
```

The `match` helper resolves a path against the same router the app uses (`reitit.core/match-by-path`), and the test then checks that the resolved match has a handler for the expected method.

This is one of those tests that seems almost too simple to be useful. It is not. Here is what it catches:

- A route accidentally removed during refactoring.
- A route defined with the wrong HTTP method (`:get` instead of `:post`).
- A route that resolves to a path but has no handler attached for the expected method.

Adding a new route to the app means adding one line to `expected-routes`. The test grows with the application, and it takes near-zero effort to maintain.

### Edge Cases: 404 and 405

Two more tests verify that the app handles non-happy paths correctly:

```clojure
(deftest unknown-route-returns-404
  (let [resp (routes/app
               {:request-method :get
                :uri "/nonexistent"})]
    (is (= 404 (:status resp)))))

(deftest wrong-method-returns-405
  (let [resp (routes/app
               {:request-method :get
                :uri "/auth/request"})]
    (is (= 405 (:status resp)))))
```

The 404 test confirms the default handler returns a proper status code for unknown paths. The 405 test is subtler: `/auth/request` exists, but only for POST. A GET to that path should return 405 (Method Not Allowed), not 404 (Not Found). This distinction matters for API correctness and for clients that use status codes to make decisions.

### Cache Control for Authenticated Responses

One more test verifies security-relevant behavior -- that authenticated responses include `Cache-Control: no-store`:

```clojure
(deftest authenticated-responses-have-no-store-header
  (let [handler (routes/wrap-no-cache-authenticated
                  (constantly
                    {:status 200
                     :headers {"Content-Type" "text/html"}
                     :body "ok"}))]
    (is
      (=
        "no-store"
        (get-in (handler {:session {:user-email "user@test.com"}})
                [:headers "Cache-Control"]))
      "Authenticated responses should have Cache-Control: no-store")
    (is
      (nil? (get-in (handler {:session {}}) [:headers "Cache-Control"]))
      "Unauthenticated responses should not have Cache-Control")))
```

This tests a middleware function in isolation by wrapping a dummy handler. It verifies both the positive case (authenticated requests get the header) and the negative case (unauthenticated requests do not). Testing middleware this way -- by composing it with a trivial handler -- is clean and fast.

## Coverage with Cloverage

Tests without coverage measurement leave you guessing about what you have not tested. [Cloverage](https://github.com/cloverage/cloverage) instruments your Clojure code and reports line and form coverage.

The coverage configuration lives in the `:coverage` alias in `deps.edn`:

```clojure
:coverage {:extra-paths ["test"]
           :extra-deps {cloverage/cloverage {:mvn/version "1.2.4"}
                        ;; in-memory SMTP for the email-flow tests (ch. 16)
                        com.icegreen/greenmail {:mvn/version "2.1.8"}}
           :main-opts ["-m" "cloverage.coverage"
                       "--src-ns-path" "src"
                       "--test-ns-path" "test"
                       "--text" "--summary"
                       "--fail-threshold" "50"]}
```

The key flags:

- `--src-ns-path "src"` and `--test-ns-path "test"` tell Cloverage where to find source and test code.
- `--text --summary` outputs a human-readable summary to the terminal.
- `--fail-threshold 50` makes the process exit with a non-zero status if overall coverage drops below 50%. This is the enforcement mechanism.

Why 50% and not 80% or 100%? At this stage of the project, the app has config, routes, handlers, database code, and email sending. Some of that (email, database transactions) is harder to unit test and will be covered by integration and end-to-end tests. Setting the bar at 50% ensures meaningful coverage without creating pressure to write bad tests just to hit a number. The threshold should go up as the test suite matures.

## Running the Tests

Two commands cover the two modes, both run from the project root:

```bash
clojure -X:test      # fast: run the suite, no coverage
clojure -M:coverage  # run under Cloverage, enforce the coverage threshold
```

`clojure -X:test` is the quick feedback loop during development; `clojure -M:coverage` is what the commit gate and CI run, because it both runs the tests and fails if coverage drops below the threshold. The companion repo ships convenience scripts only for the two checks you run constantly by hand -- `./reformat` and `./lint` -- and calls the test commands directly in CI; if you like, wrapping `clojure -M:coverage` in a one-line `./unittest` script (the same `cd "$(dirname "$0")"` pattern as the others) is harmless, but it is not required and the repo does not ship one.

If tests pass and coverage is above the threshold, exit code 0; otherwise non-zero. Because CI runs the very same command, local and CI behavior are identical.

## The `deps.edn` Test Alias

For running tests without coverage (faster feedback during development), there is also the `:test` alias:

```clojure
:test {:extra-paths ["test"]
       :extra-deps {io.github.cognitect-labs/test-runner
                    {:git/tag "v0.5.1" :git/sha "dfb30dd"}
                    ;; in-memory SMTP for the email-flow tests (ch. 16)
                    com.icegreen/greenmail {:mvn/version "2.1.8"}}
       :main-opts ["-m" "cognitect.test-runner"]
       :exec-fn cognitect.test-runner.api/test}
```

This uses Cognitect's test runner, which discovers and runs all `_test.clj` files under `test/`. You can run it with:

```bash
clojure -M:test
```

The difference: `:test` runs fast without instrumentation. `:coverage` instruments every form for coverage tracking, which is slower. Use `clojure -X:test` during development, `clojure -M:coverage` before committing.

## In-File Tests: Co-Locating Quick Tests with Source

Everything above lives in `test/`. That is the right home for most tests -- the database fixtures, the route table, the middleware checks. But there is a second place a test can live: directly underneath the function it tests, in the source file itself. The two approaches are complements, not substitutes. This project uses both, and the only real question is which job goes where.

The division of labor is worth stating plainly:

- **In-file (co-located) tests** suit the light cases: example or doc-style tests, short assertions, and -- the one that earns its keep -- assertions on **private** functions. Inside the function's own namespace you call a `defn-` directly. No exposing it, no `(var myapp.ns/private-fn)` indirection. The test sits next to the code and reads as living documentation.
- **Separate `test/` files** -- the approach built earlier in this post -- carry the heavy lifting: larger standalone tests, anything with substantial setup, and integration tests like the DB-fixture style above. Those do not belong in-file.

The signal is the dependency. The only test-only dependency a light in-file test needs is `clojure.test` itself (`deftest`/`is`/`testing`), plus maybe a small helper or data generator. The moment a test reaches for something heavy -- a JDBC driver, testcontainers, anything not on the production classpath -- that is the cue to move it to a `test/` file. The macro below would technically strip it, but a test that needs that machinery is not an example anymore.

### The Problem: Test Dependencies in Production Code

The catch is the `require`. A co-located test needs `clojure.test` (and maybe a small helper), but the obvious ways to pull it in all fail:

1. **In the `ns` form.** The require loads into the *production* namespace too. For `clojure.test` that is merely unwanted; for a heavier test-only dep that is not even on the prod classpath, the build breaks outright.
2. **Inside the `deftest` body.** That is a runtime call inside the test fn -- it does not make `deftest`/`is` resolvable for the rest of the file at compile time.
3. **Guarded with `(when clojure.test/*load-tests* (require ...))`.** This is a *runtime* branch -- the form is still compiled into the artifact, so it does not reliably keep the dependency out.

### The Macro: Compile-Time Exclusion

The fix leans on a built-in: `clojure.test/*load-tests*` is a dynamic var, default `true`, and when it is `false`, `deftest` expands to nothing. We extend that to arbitrary forms -- including the test-only `require` -- with one macro:

```clojure
(defmacro tests
  "Include body only when clojure.test/*load-tests* is true."
  [& body]
  (if clojure.test/*load-tests*
    `(do ~@body)
    `(comment ~@body)))
```

The `if` runs at *macroexpansion* (compile) time. With `*load-tests*` true, `(tests (require '[clojure.test ...]) (deftest ...))` expands to `(do (require ...) (deftest ...))` -- it loads and runs exactly as written. With it false, the same form expands to `(clojure.core/comment (require ...) (deftest ...))`, and `comment` evaluates to `nil` and never evaluates its body. Even a `(require '[does.not.exist])` inside it is inert. So when `*load-tests*` is false at compile time, the entire block -- test-only requires and all -- is gone from the compiled output.

Define (or require) `tests` before you use it. Here it is around a private helper:

```clojure
(ns myapp.config
  (:require [clojure.string :as str]))

(defn- parse-port
  "Parse a port string into an int, clamped to the valid TCP range."
  [s]
  (-> (Long/parseLong (str/trim s))
      (max 1)
      (min 65535)))

(tests
  (require '[clojure.test :refer [deftest is]])

  (deftest parse-port-clamps
    (is (= 8080 (parse-port " 8080 ")))
    (is (= 1 (parse-port "0")))            ; clamped up
    (is (= 65535 (parse-port "70000")))))  ; clamped down
```

`parse-port` is private, and the test calls it directly -- no var gymnastics -- documenting the clamping behavior right where a reader will look for it.

### Stripping for Production

In normal dev and test runs, `*load-tests*` stays `true`, so these blocks load and run as written. You only flip it false for the AOT build. The strict-compilation chapter's `compile-strict` already passes a `:bindings` map to `compile-clj` -- add one entry (leaving its `:err :capture` and warning scan exactly as they were):

```clojure
;; the :bindings map inside compile-strict's b/compile-clj call
;; (see the strict-compilation chapter):
:bindings {#'*warn-on-reflection* true
           #'*unchecked-math* :warn-on-boxed
           #'clojure.test/*load-tests* false}
```

(`compile-clj` has supported `:bindings` since tools.build 0.8.1.) With this binding the AOT compiler expands every `tests` block to `(comment ...)`, so the uberjar carries no test forms and none of the in-file test requires. (`clojure.test` itself ships with Clojure, so its presence in the namespace that *defines* the `tests` macro is harmless -- the win is keeping the test bodies and any heavier test-only deps out of the artifact.)

### The Real Cost: Discovery

There is an ergonomic price, and it is the honest tradeoff. The `:test` alias runs the cognitect test-runner, which by default scans `test/` for namespaces matching `.*-test$`. In-file tests live inside source namespaces like `myapp.config`, which do not end in `-test` -- so the default run never sees them. To include them, point the runner at `src` and widen selection:

```bash
clojure -X:test :dirs '["src" "test"]' :patterns '[".*"]'
```

But `:patterns '[".*"]'` now loads *every* namespace under `src` as a test namespace, not just the ones with tests -- fine for a small tree, slower (and a side-effect risk) for a large one. Once you have more than a couple, enumerate the in-file test namespaces explicitly instead. The exec-fn key is `:nses` (not `:namespaces`):

```bash
clojure -X:test :nses '[myapp.config myapp.web.routes]'
```

The same caveat applies to the commit gate. The coverage gate runs `clojure -M:coverage`, and cloverage finds tests via its own `--test-ns-path "test"` -- so in-file tests are skipped there too until you broaden that path (or its `--test-ns-regex`) as well. Either way, discovery stops being automatic and becomes something you maintain.

Co-location is convenient -- the test sits where you edit, and reads as documentation for the next person. That convenience is not free: it puts a test concern into a source file, and while the `tests` macro keeps the *dependency* out of the artifact, it cannot make discovery automatic. The trade is worth it for short, doc-style, and private-function checks. For anything heavier -- standalone or integration -- the separate `test/` file stays simpler, and discovery stays free. Keep it there.

## What You Have Now

At this point in the series, the test suite covers three areas:

1. **Configuration** -- the config system loads correctly, produces keys of the right type and size, and supports nested access.
2. **Routes** -- every defined route resolves, unknown routes return 404, wrong methods return 405.
3. **Middleware** -- security-relevant behavior (cache headers for authenticated users) is verified.

The infrastructure supports more:

- **Fresh in-memory databases per test**, ready for when you start testing data access.
- **Deterministic config**, so tests never depend on local environment.
- **A request builder**, for when you start testing handlers end-to-end.
- **Coverage enforcement**, so coverage can only go up as you add features.

The investment is small -- one helpers file, two test files, a three-line shell script, and a couple of aliases in `deps.edn`. But it establishes patterns that scale. Every new feature you add gets tested against this infrastructure, and you find out in seconds whether it works.

Next time you sit down to add a feature, you write the test first (or at least alongside), and you have everything you need to run it.
