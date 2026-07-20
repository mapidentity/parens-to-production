# Your First Clojure Web Server: Ring, http-kit, and Reitit

You have Clojure installed and a project skeleton. Now you need to serve HTTP requests. In most ecosystems this means picking a framework -- Rails, Django, Express -- and letting it dictate your project structure. Clojure takes a different approach. You compose small, focused libraries into your own stack. This gives you just what you need and nothing you don't.

The web server needs three things: routing, configuration, and a clean development workflow. The result is a `curl`-able health endpoint and a REPL you can use to start, stop, and inspect the server without leaving your editor.

## The stack

Here is what we are using and why:

- **Ring** -- The HTTP abstraction. Requests and responses are plain Clojure maps. Middleware are functions that wrap handlers. This is the foundation that nearly every Clojure web library builds on.
- **http-kit** -- A fast, lightweight HTTP server with WebSocket support. The usual alternatives are Jetty (via `ring-jetty-adapter`), Undertow (via Luminus's `ring-undertow-adapter`), and Netty (via Aleph); all are excellent and battle-tested. We pick http-kit for two concrete reasons: it is a single, self-contained dependency with no servlet container to configure, and its WebSocket support is built in rather than bolted on -- which matters immediately, because the very next chapter pushes live-reload events to the browser over a WebSocket on this same server. It implements the Ring spec, starts in milliseconds, and stays out of your way.
- **Reitit** -- Data-driven routing from Metosin. The long-standing alternative is Compojure, which expresses routes as macros (`(GET "/foo" [] ...)`); Reitit expresses them as plain vectors instead. That difference is the reason we choose it: a route tree that is *data* can be inspected, transformed, and tested without being executed. [The testing chapter](11-unit-testing.md) resolves every path the application should serve against the compiled router without invoking a single handler -- a check a macro-based router can only perform by executing the route.
- **Aero** -- Configuration as EDN with profile support (`:dev`, `:prod`). The common alternatives are environ and cprop, which assemble configuration from environment variables and system properties. We pick Aero because the whole configuration is one EDN file you can read, diff, and test as a value: every key is visible in one place, `#profile` makes each dev/prod split explicit, and `#env` marks exactly which values the deployment must supply. With env-var libraries, that inventory lives in the deployment docs or in your head.

## deps.edn: your project file

Clojure projects use `deps.edn` to declare dependencies and paths. Here is the relevant subset for our web server:

```clojure
{:paths ["src" "resources"]

 :deps {org.clojure/clojure {:mvn/version "1.12.4"}

        ;; Web server
        ring/ring-core {:mvn/version "1.15.3"}
        http-kit/http-kit {:mvn/version "2.8.0"}
        metosin/reitit-ring {:mvn/version "0.10.0"}

        ;; JSON
        org.clojure/data.json {:mvn/version "2.5.2"}

        ;; Secure random (dev key generation, tokens)
        crypto-random/crypto-random {:mvn/version "1.2.1"}

        ;; Logging
        org.clojure/tools.logging {:mvn/version "1.3.0"}
        ch.qos.logback/logback-classic {:mvn/version "1.5.16"}

        ;; Configuration
        aero/aero {:mvn/version "1.1.6"}}

 :aliases
 {:dev {:extra-paths ["dev"]
        :extra-deps {org.clojure/tools.namespace {:mvn/version "1.5.1"}
                     ring/ring-devel {:mvn/version "1.15.3"}
                     nrepl/nrepl {:mvn/version "1.5.1"}
                     cider/cider-nrepl {:mvn/version "0.58.0"}}}

  :repl {:main-opts ["-m" "nrepl.cmdline" "--port" "7888" "--interactive"
                     "--middleware" "[cider.nrepl/cider-middleware]"]}}}
```

`:paths` tells Clojure where to find source code (`src`) and resources like config files (`resources`). `:deps` lists runtime dependencies at explicit Maven versions: no lock files, no version ranges, you pin the versions you want. And `:aliases` define optional configurations layered on top: the `:dev` alias puts the `dev` directory on the classpath and pulls in development-only dependencies like nREPL and `tools.namespace`, while `:repl` starts nREPL on port 7888 with CIDER middleware for editor integration.

To start a REPL with both aliases active:

```bash
clj -M:dev:repl
```

This gives you a running nREPL server that your editor (Emacs/CIDER, VS Code/Calva, IntelliJ/Cursive) can connect to.

## Ring: requests and responses as maps

Ring is not a framework. It is a specification. An HTTP request is a Clojure map:

```clojure
{:request-method :get
 :uri "/health"
 :headers {"accept" "application/json"}
 :query-string nil}
```

A handler is a function that takes a request map and returns a response map:

```clojure
{:status 200
 :headers {"Content-Type" "application/json"}
 :body "{\"status\":\"ok\"}"}
```

That is the entire abstraction. No magic, no inheritance, no annotations. A request comes in as data, a response goes out as data. You can construct and test these maps in the REPL without starting a server.

### Middleware

Middleware is a function that takes a handler and returns a new handler, typically adding behavior before or after the original handler runs. Here is the shape:

```clojure
(defn wrap-something [handler]
  (fn [request]
    ;; Do something before, often by modifying the request
    (let [response (handler request)]
      ;; Do something after, often by modifying the response
      response)))
```

Ring ships with middleware for common needs: parsing query parameters, managing sessions, handling cookies. You compose them into a stack, and each request flows through the layers.

## Configuration with Aero

Before we write routes, we need configuration. Aero reads an EDN file and supports profile-based values. Create `resources/config.edn`:

```clojure
{:server {:port 3000
          :host #profile {:dev "0.0.0.0"
                          :prod "127.0.0.1"}}

 :base-url #profile {:dev "https://myapp.lan"
                     :prod #env "BASE_URL"}

 :smtp {:host #profile {:dev "mailpit" :prod #env "SMTP_HOST"}
        :port #profile {:dev 1025 :prod 587}
        :from #profile {:dev "no-reply@myapp.lan" :prod #env "SMTP_FROM"}}

 :session-key #env "SESSION_KEY"
 :signing-key #env "SIGNING_KEY"}
```

The `#profile` reader tag selects a value based on the active profile. In dev, the server binds to `0.0.0.0` (all interfaces) for a concrete reason: TLS terminates in the separate Caddy container from [the devcontainer chapter](03-devcontainer.md), and its proxied requests reach this container over the compose network, which a loopback-only bind would refuse. In prod it binds to `127.0.0.1` (localhost only, behind a reverse proxy on the same host). The `#env` tag reads from environment variables, and the division of labor between the two tags is the configuration policy in miniature: dev values are literals, so a fresh checkout runs with zero setup, while anything that varies per deployment comes from the environment the production process manager supplies. That means `#env` under `:prod` for the base URL and the SMTP host, and bare `#env` for the two crypto keys. The session key's dev fallback is `resolve-keys`' job below; the signing key has no consumer yet, so its `nil` dev value is harmless until [the auth chapter](24-auth-tokens.md) starts signing tokens and threads it through the same fallback.

`:base-url` and `:smtp` are placeholders for now -- we use them later for magic-link emails ([the email login-flow chapter](25-auth-email-flow.md)). They live in config from the start so every environment has them, and so the config test we write in [the testing chapter](11-unit-testing.md) can assert their presence. (The repository's `:smtp` map eventually grows `:user` and `:pass` entries -- same pattern, `#env` under `:prod` -- and a `:tls` flag that simply flips to true in production, once the email chapter starts sending.)

Now the configuration namespace:

```clojure
(ns myapp.config
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]
    [crypto.random :as random]))

(defn generate-session-key
  "Generate a secure random 16-byte key for session encryption."
  []
  (random/bytes 16))

(defn- active-profile
  "Returns the active config profile, defaulting to :dev."
  []
  (keyword (or (System/getenv "MYAPP_PROFILE") "dev")))

(defn- require-prod-key!
  "Refuse to start when `var-name` is unset in :prod.

  A random fallback would invalidate sessions on every restart and
  diverge across instances of a load-balanced deployment."
  [profile var-name]
  (when (= profile :prod)
    (throw
      (ex-info
        (str var-name " env var is required in :prod — refusing to start with a random key. "
             "Random keys break multi-instance deployments and silently log out users on restart.")
        {:profile profile
         :var var-name}))))

(defn- require-session-key-length!
  "Refuse a session key that is not exactly 16 bytes.

  Ring's cookie session store uses AES-128, whose key is exactly 16 bytes. Ring
  itself asserts this when the cookie store is built, throwing a bare
  `AssertionError: the secret key must be exactly 16 bytes`. We check earlier,
  during config resolution, so a misconfigured `SESSION_KEY` fails with a
  domain-specific message before the middleware stack is even assembled."
  ^bytes [^bytes k]
  (when (not= (alength k) 16)
    (throw
      (ex-info
        (str "Session key must be exactly 16 bytes (got " (alength k)
             "). Ring's cookie store uses AES-128.")
        {:length (alength k)})))
  k)

(defn- parse-session-key
  "The two spellings of SESSION_KEY, disambiguated by length.

  32 hex characters decode to the 16 AES-128 key bytes — what
  `openssl rand -hex 16` prints, and the spelling to prefer: the full 128
  bits of entropy survive the trip through a text env file. A raw
  16-character string is used byte-for-byte (ISO-8859-1) — it satisfies
  Ring's length assertion but carries only as much entropy as its
  characters do (16 hex chars: 64 bits — half the strength the cipher was
  chosen for). The lengths cannot collide: raw is 16 chars, hex is 32."
  ^bytes [^String k]
  (if (re-matches #"[0-9a-fA-F]{32}" k)
    (let [out (byte-array 16)]
      (dotimes [i 16]
        (aset out i (unchecked-byte (Integer/parseInt (subs k (* 2 i) (+ 2 (* 2 i))) 16))))
      out)
    (.getBytes k "ISO-8859-1")))

(defn- resolve-keys
  "Convert string keys to bytes, with profile-aware fallback policy.

  In :dev, generate random keys when env vars are unset. In :prod, fail
  closed — refusing to start beats silently drifting apart across
  instances or invalidating sessions on each restart."
  [config profile]
  (-> config
      (update :session-key
              (fn [^String k]
                (require-session-key-length!
                  (or (when k (parse-session-key k))
                      (do (require-prod-key! profile "SESSION_KEY")
                          (println "⚠️  Generating random session key (dev mode)")
                          (println "⚠️  Sessions will not survive server restart")
                          (generate-session-key))))))))

(defn load-config
  "Load and resolve config.edn for the given profile."
  ([] (load-config (active-profile)))
  ([profile]
   (-> (io/resource "config.edn")
       (aero/read-config {:profile profile})
       (resolve-keys profile))))

(def config
  "Delayed config map. Deref triggers a one-time load."
  (delay (load-config)))

(defn get-config
  "Get configuration value by path."
  [& path]
  (get-in @config path))
```

Key design decisions here:

- **`config` is a `delay`**, not eagerly loaded at compile time. This matters because you don't want configuration loading to happen when the namespace is compiled -- only when it's first needed at runtime.
- **`get-config` takes a variable path**, so `(get-config :server :port)` drills into nested maps naturally.
- **Dev generates a random key; prod refuses to start without one.** Both policies live in one function: `resolve-keys` threads the active profile, and `require-prod-key!` throws in `:prod` rather than fall back: a random key would silently log every user out on restart and diverge across the instances of a load-balanced deployment. So you can start a dev REPL without configuring anything, and you cannot forget the key in production. (The repository's `resolve-keys` later threads the auth signing key through the same policy, once [the auth chapter](24-auth-tokens.md) adds it -- and by [Going Live](35-going-live.md), every `#env` under `:prod` earns the same boot refusal.)
- **The session key must be exactly 16 bytes.** Ring's cookie store encrypts with AES-128, whose key is 16 bytes, so `require-session-key-length!` checks the resolved key and refuses to start otherwise. The dev fallback is 16 bytes by construction; the guard matters for the production `SESSION_KEY`, where a wrong-length string would otherwise trip Ring's own 16-byte assertion when the cookie store is built. Our check just catches it earlier, during config resolution, with a domain-specific message instead of Ring's bare `AssertionError` -- the same fail-at-boot-not-in-production discipline `require-prod-key!` applies to the key's *absence*, applied to its *shape*.

## Routing with Reitit

Reitit represents routes as data -- nested vectors that describe your URL tree. Here is the routes namespace:

```clojure
(ns myapp.web.routes
  (:require
    [myapp.config :as config]
    [myapp.web.handler :as handler]
    [reitit.ring :as ring]
    [ring.middleware.keyword-params :as keyword-params]
    [ring.middleware.params :as params]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as cookie]))
```

This requires `myapp.web.handler`, which we create in the next section.

### The route tree

```clojure
(def routes
  [[""
    ["/" {:get #'handler/home}]
    ["/health"
     {:get (fn [_] (handler/json-response {:status "ok"}))}]]])
```

Each route is a vector of `[path data]`. The data map associates HTTP methods with handler functions. Routes nest naturally -- you can group related paths under a common prefix. One character in there does the real work: the handler is referenced as a var, `#'handler/home`, not as a bare function. The router is built once and holds whatever it was given; handed the function value, it would keep tonight's definition forever, and re-evaluating `home` at the REPL would change nothing until the router was rebuilt. Handed the var, every request looks up the current definition, so a redefinition -- yours at the REPL, or [the live-reload chapter](06-live-reload.md)'s on every save -- is live on the next request without touching the router.

We start with just two routes, because they are the two we can actually serve today: a home page and a health check. The real application grows this tree substantially -- auth endpoints, a dashboard, the recipe pages, an admin area -- but each of those needs machinery we have not built yet (sessions that mean something, a database, the view layer), so we add them in the chapters that introduce that machinery. Just as important, the *shape* of this tree changes too: the flat list here becomes a set of nested groups, each wrapped in its own middleware (current-user, auth, terms-accepted, admin) once those exist. So treat this as the seed, not the final form.

The `/health` endpoint is defined inline since it's trivial: return `{"status":"ok"}` with a 200. This is the endpoint your load balancer or monitoring tool will poll. The other route points at `myapp.web.handler`, which we create next.

### The handler namespace

A *handler* is a plain function from a Ring request map to a Ring response map. We keep them in their own namespace, `myapp.web.handler`, so routing stays a pure description of the URL tree and the request-handling logic lives apart from it. At this point the namespace is tiny -- it grows into the orchestration layer for auth, the recipe domain, and the views as those arrive:

```clojure
(ns myapp.web.handler
  (:require
    [clojure.data.json :as json]))

(defn json-response
  "Ring response with JSON content-type, no-store caching, and serialized body."
  [data & {:keys [status] :or {status 200}}]
  {:status status
   :headers {"Content-Type" "application/json"
             "Cache-Control" "no-store"}
   :body (if (string? data) data (json/write-str data))})

(defn home
  "Placeholder home page. Becomes a real Hiccup view in the views chapter."
  [_request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=UTF-8"}
   :body "<!doctype html><h1>MyApp</h1>"})
```

`json-response` builds a plain Ring response map: set the content type, serialize the data to JSON, done. The `json` alias is `clojure.data.json` (the `org.clojure/data.json` dependency from our `deps.edn`); `write-str` turns a Clojure value into a JSON string, and we pass a string body straight through unchanged. (The repository's `json-response` later grows a `:headers` option, merged over these defaults, so a caller can add or override a header without giving up the shared shape.) `home` returns a stub HTML page for now -- the [Hiccup views chapter](14-hiccup-views.md) replaces its body with a real server-rendered view, and the [auth](24-auth-tokens.md) and dashboard chapters add the handlers the fuller route tree calls.

### The middleware stack

Middleware wraps around the entire application. Order matters -- the first middleware listed is the outermost layer:

```clojure
(def ^:private app*
  (delay
    (ring/ring-handler
      (ring/router routes)
      (ring/create-default-handler)
      {:middleware [[params/wrap-params]
                    [keyword-params/wrap-keyword-params]
                    [session/wrap-session
                     {:store (cookie/cookie-store
                               {:key (config/get-config :session-key)})
                      :cookie-name "session"
                      :cookie-attrs {:http-only true
                                     :secure true
                                     :same-site :lax
                                     :max-age (* 30 24 60 60)}}]]})))

(defn app
  "Ring handler entry point."
  [request]
  (@app* request))
```

The middleware stack, layer by layer:

1. **`wrap-params`** -- Parses query string and form body parameters into a `:params` map on the request.
2. **`wrap-keyword-params`** -- Converts string parameter keys to keywords, so you get `(:email params)` instead of `(get params "email")`.
3. **`wrap-session`** -- Manages sessions using encrypted cookies; the session key comes from our config. Every cookie attribute is a security decision. `http-only` prevents JavaScript access, `secure` restricts the cookie to secure contexts, and `max-age` sets a 30-day expiry. `same-site :lax` is a partial mitigation of cross-site request forgery (CSRF), where another site causes your browser to submit a state-changing request that arrives carrying your ambient cookies. `:lax` withholds the cookie on cross-site subrequests but still sends it on top-level GET navigations, so it is a browser-enforced, defense-in-depth layer; [the email-flow chapter](25-auth-email-flow.md) weighs exactly what it covers and what it leaves on the table.

One nuance about `secure` matters here, because it is easy to get wrong: the attribute limits the cookie to *secure contexts*, and Chrome, Edge, and Firefox treat `http://localhost` as a potentially-trustworthy origin, so a `secure` cookie set over `http://localhost:3000` is stored and sent back normally *there*, and a login reached that way sticks. This is a per-browser choice, not a guarantee (RFC 6265bis leaves what counts as a secure protocol to the user agent): Safari does *not* do it and drops the cookie even on `http://localhost`. On any *non-localhost* plain-HTTP origin every browser drops it. Dev still runs through the Caddy `.lan` hostname (`https://myapp.lan`) rather than `http://localhost:3000` for a broader reason than the cookie: it is the full TLS-and-proxy front door the app's own URLs -- magic links included -- are built around. (The plain-`localhost` `curl` we run in a moment hits `/health`, which carries no session, so none of this touches it.) We keep `secure` on unconditionally rather than relaxing it in dev, because the dev environment is already HTTPS by design.

How the stack is built matters as much as what is in it:

- **`app*` is a `delay`**, just like our config. This prevents the middleware stack from being built at compile time (which would try to read config, which might not be available yet). It's built once on the first request.
- **`app` is a plain function** that derefs the delay. The server receives `#'routes/app` (a var reference), which means you can redefine `app` at the REPL and the server picks up changes without restarting.

Later chapters insert more middleware here: locale negotiation ([i18n](12-i18n.md)), a no-cache guard for authenticated pages, and the current-user/auth/terms/admin gates ([auth](24-auth-tokens.md), [admin](28-admin-dashboard.md)). A catch-all error boundary that turns an uncaught exception into a logged, generic 500 arrives with [the email-flow chapter](25-auth-email-flow.md), and a strict Content-Security-Policy with [the asset pipeline](29-asset-pipeline.md). Alongside the stack, `myapp.config` grows more keys: a `:database-uri` and an `:admin-email`. The `:signing-key` already in the listing above stays inert until [the auth chapter](24-auth-tokens.md) gives it a consumer and resolves it with the same dev-fallback, prod-fail-closed policy as `:session-key`.

Putting the pieces together, here is the whole path a request takes through the parts this chapter built. Each middleware is a layer the request passes *inward* through on the way to a handler, and the response passes back *outward* through on the way to the browser -- which is why order matters, and why the outermost layer is listed first:

```
   HTTP request (http-kit)
          │
          ▼
 ┌─────────────────────────────────────────────┐
 │ wrap-params          parse query/form        │  ← outermost
 │ ┌─────────────────────────────────────────┐ │
 │ │ wrap-keyword-params   "email" → :email   │ │
 │ │ ┌─────────────────────────────────────┐ │ │
 │ │ │ wrap-session        cookie ⇄ session │ │ │
 │ │ │ ┌─────────────────────────────────┐ │ │ │
 │ │ │ │ reitit router                   │ │ │ │
 │ │ │ │   matches the path → a handler  │ │ │ │
 │ │ │ │   (or the default 404/405)      │ │ │ │
 │ │ │ │        handler returns          │ │ │ │
 │ │ │ │        a Ring response map      │ │ │ │
 │ │ │ └─────────────────────────────────┘ │ │ │
 │ │ └─────────────────────────────────────┘ │ │
 │ └─────────────────────────────────────────┘ │
 └─────────────────────────────────────────────┘
          │
          ▼
   HTTP response (http-kit)
```

The request is a plain map going down; the response is a plain map coming back up; every layer is just a function that can look at or alter either one. That is the entire Ring model, and the rest of the book only ever adds layers to this onion -- it never changes its shape.

## The entry point

The `myapp.core` namespace ties everything together:

```clojure
(ns myapp.core
  (:require
    [myapp.config :as config]
    [myapp.web.routes :as routes]
    [org.httpkit.server :as http-kit])
  (:gen-class))

(defonce server (atom nil))

(defn start-server!
  "Start the web server."
  []
  (let [port (config/get-config :server :port)
        host (config/get-config :server :host)]
    (println (str "Starting server on " host ":" port "..."))
    (reset! server
      (http-kit/run-server
        #'routes/app
        {:port port
         :ip host}))
    (println (str "Server running at http://" host ":" port))
    @server))

(defn stop-server!
  "Stop the web server."
  []
  (when-let [stop-fn @server]
    (println "Stopping server...")
    (stop-fn :timeout 100)
    (reset! server nil)
    (println "Server stopped")))

(defn restart-server!
  "Restart the web server."
  []
  (stop-server!)
  (start-server!))

(defn -main
  "Application entry point."
  [& _args]
  (start-server!))
```

Several important patterns here:

**`defonce` for the server atom.** Using `defonce` instead of `def` means reloading this namespace at the REPL won't reset the atom. If you have a running server, the stop function stays accessible. Without `defonce`, reloading the namespace would create a new atom (set to `nil`), and you'd lose the ability to stop the old server -- it would keep running on the port with no way to shut it down cleanly.

**Var reference with `#'routes/app`.** We pass `#'routes/app` (the var itself) to http-kit, not `routes/app` (the current value). This is the key to REPL-driven development with http-kit. When you redefine `app` or any function it calls, the server automatically uses the new definition because it dereferences the var on each request. Without the `#'`, you'd have to restart the server after every code change.

**http-kit's stop function.** `http-kit/run-server` returns a stop function that takes an optional `:timeout` (milliseconds). Calling it stops the server. We store this function in the atom and call it in `stop-server!` as `(stop-fn :timeout 100)`, giving existing connections 100ms to complete before being closed.

**`(:gen-class)`.** This tells the Clojure compiler to generate a Java class with a static `main` method, which is what `java -jar` expects when running the uberjar in production.

**`start-server!` will gain startup steps.** Right now it only boots http-kit. As later chapters add a database and a content-hashed asset pipeline, this function grows a few lines at the front -- creating the Datomic database ([Datomic chapter](08-datomic.md)) and loading the asset manifest ([asset pipeline](29-asset-pipeline.md)) before the server accepts traffic. Likewise, `dev/user.clj`'s `start!` is rewired to go through the hot-reload entry point once [live reload](06-live-reload.md) exists.

### Why not a lifecycle library

By the end of the book, `start-server!` wires eight subsystems in a fixed order -- databases, the libvips check, http-kit, the presence reaper, the mailer, the socket REPL, the job worker, the upload GC -- and `stop-server!` unwinds them. That ordering matters (the databases exist before the worker polls them), and a reader who has met the Clojure ecosystem will recognize the shape of the problem: it is the one Component, Integrant, and mount each exist to formalize. A word on why this book hand-rolls it, because the [positioning chapter's](02-positioning.md) "no framework you cannot see into" is a stance the book owes you consistently, including here.

Component and Integrant turn the start order into *data*: a dependency graph of named components, topologically sorted, started and stopped for you, with a running system value passed into your handlers instead of reached through vars. (mount takes a lighter path -- state stays in global `defstate` vars, much like the `defonce`s already in this file, with start/stop order inferred from namespace requires rather than declared -- but the sales pitch is the same: stop hand-maintaining the lifecycle.) That is a real improvement at a real scale -- a dozen-plus components whose wiring changes often, several developers who must not have to hold the order in their heads, a test suite that spins subsystems up and down in permutations. It buys the most where the graph is large enough to get wrong by hand and volatile enough that a person keeps re-deriving it.

This system's graph is eight nodes in a nearly linear chain, defined in one `defn` a screenful long, changed a handful of times across the whole book. At that size the topological sort is *reading the function top to bottom*, and the dependency the sort would enforce -- start the databases first -- is one comment. The cost the libraries remove is a cost this system does not yet pay; the cost they add -- a dependency, a system-map threaded where plain vars were reaching singletons, a second mental model layered on `defn` -- is one it would pay in full. So the wager here is the same as everywhere else in the book: hold the seam open and hand-rolled while it stays legible, and name the exact condition that flips the trade. That condition is not subtle -- when the start order stops fitting in your head, when two people are editing it, when a component genuinely needs another component's *value* rather than a var it can resolve, reach for Integrant (its data-driven graph and its REPL-reset story fit this codebase's `defonce`-and-restart habits most naturally) and do not look back. Until then, `start-server!` is a function you can read, which is the property this book will not trade.

## REPL-driven development

The `dev/user.clj` file is automatically loaded when you start a REPL with the `:dev` alias (because `dev` is on the classpath, and Clojure looks for a `user` namespace on startup). This is where you put development shortcuts:

```clojure
(ns user
  "Development REPL helpers"
  (:require
    [myapp.core :as core]))

(println "Loading development environment...")
(println "Available commands:")
(println "  (start!)   - Start the server")
(println "  (stop!)    - Stop the server")
(println "  (restart!) - Restart the server")

(defn start!
  "Start the dev server"
  []
  (core/start-server!))

(defn stop!
  "Stop the server"
  []
  (core/stop-server!))

(defn restart!
  "Restart the server"
  []
  (core/restart-server!))
```

The loop this enables is the payoff. Start the REPL once with `clj -M:dev:repl`, call `(start!)`, and the server comes up on port 3000; from then on you edit a file, evaluate the changed namespace from your editor, and the change is live on the very next request -- no restart, because the server holds a var reference rather than a captured value.

This is profoundly different from the edit-compile-restart cycle. You keep the server running, keep your application state, and see changes in milliseconds. Need to test a handler? Call it directly in the REPL with a fake request map:

```clojure
(require '[myapp.web.routes :as routes])

(routes/app {:request-method :get
             :uri "/health"
             :headers {}})
;; => {:status 200,
;;     :headers {"Content-Type" "application/json", "Cache-Control" "no-store"},
;;     :body "{\"status\":\"ok\"}"}
```

No browser, no curl, no HTTP overhead. Just call the function with data and inspect the result.

## Try it

Start the REPL and server:

```bash
clj -M:dev:repl
```

```clojure
(start!)
;; ⚠️  Generating random session key (dev mode)
;; ⚠️  Sessions will not survive server restart
;; Starting server on 0.0.0.0:3000...
;; Server running at http://0.0.0.0:3000
```

The two warnings are `resolve-keys` doing its dev-mode job: nothing in the devcontainer sets `SESSION_KEY`, so the key is freshly random and sessions will not survive a restart. They print before the startup lines because the first `get-config` call is what forces the config delay.

Hit the health endpoint:

```bash
curl http://localhost:3000/health
# {"status":"ok"}
```

That `curl` runs from inside the devcontainer, where the app listens on `0.0.0.0:3000` and the `.lan` names resolve. From the host they do not, and nothing publishes Caddy's port, so a host-side `curl https://myapp.lan/health` has nothing to reach; [the devcontainer chapter](03-devcontainer.md) explains why and how you reach the app from your own machine instead -- the editor's forwarded `http://localhost:3000` for a quick look, or the in-container browser at `https://myapp.lan` for the real HTTPS front door.

Stop it:

```clojure
(stop!)
;; Stopping server...
;; Server stopped
```

## Where this leaves us

The pieces are independent and composable -- the through-line of the whole chapter -- so the stack grows by addition rather than rework: Ring's request/response maps, http-kit serving them, Reitit routing on path and method, and Aero loading one EDN config per profile, behind a `myapp.core` lifecycle you drive from the REPL. Nothing here forces a structure you would later have to undo, which is what lets the next chapter sharpen the loop with live reload, and the testing chapter cover this foundation, without first tearing it apart.
