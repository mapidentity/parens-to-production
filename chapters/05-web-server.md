# Your First Clojure Web Server: Ring, http-kit, and Reitit


You have Clojure installed and a project skeleton. Now you need to serve HTTP requests. In most ecosystems this means picking a framework -- Rails, Django, Express -- and letting it dictate your project structure. Clojure takes a different approach. You compose small, focused libraries into your own stack. This gives you exactly what you need and nothing you don't.

In this post we will build a running web server with routing, configuration, and a clean development workflow. By the end you will have a `curl`-able health endpoint and a REPL you can use to start, stop, and inspect your server without leaving your editor.

## The Stack

Here is what we are using and why:

- **Ring** -- The HTTP abstraction. Requests and responses are plain Clojure maps. Middleware are functions that wrap handlers. This is the foundation that nearly every Clojure web library builds on.
- **http-kit** -- A fast, lightweight HTTP server with WebSocket support. The usual alternatives are Jetty (via `ring-jetty-adapter`) and Undertow (via Aleph or `luminus`); both are excellent and battle-tested. We pick http-kit for two concrete reasons: it is a single, self-contained dependency with no servlet container to configure, and its WebSocket support is built in rather than bolted on -- which matters immediately, because the very next chapter pushes live-reload events to the browser over a WebSocket on this same server. It implements the Ring spec, starts in milliseconds, and stays out of your way.
- **Reitit** -- Data-driven routing from Metosin. The long-standing alternative is Compojure, which expresses routes as macros (`(GET "/foo" [] ...)`); Reitit expresses them as plain vectors instead. That difference is the reason we choose it: a route tree that is *data* can be inspected, transformed, and tested without being executed -- the testing chapter walks the whole tree as a value to assert every route resolves, which a macro-based router cannot offer.
- **Aero** -- Configuration as EDN with profile support (`:dev`, `:prod`). Environment variables, defaults, and profile switching without a framework.

## deps.edn: Your Project File

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

A few things to notice:

- **`:paths`** tells Clojure where to find source code (`src`) and resources like config files (`resources`).
- **`:deps`** lists your runtime dependencies with explicit Maven versions. No lock files, no version ranges. You pin exactly what you want.
- **`:aliases`** define optional configurations. The `:dev` alias adds a `dev` directory to the classpath and pulls in development-only dependencies like nREPL and `tools.namespace`. The `:repl` alias configures nREPL to start on port 7888 with CIDER middleware for editor integration.

To start a REPL with both aliases active:

```bash
clj -M:dev:repl
```

This gives you a running nREPL server that your editor (Emacs/CIDER, VS Code/Calva, IntelliJ/Cursive) can connect to.

## Ring: Requests and Responses as Maps

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
    ;; Do something before
    (let [response (handler modified-request)]
      ;; Do something after
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
                     :prod "https://myapp.example.com"}

 :smtp {:host #profile {:dev "mailpit" :prod "localhost"}
        :port #profile {:dev 1025 :prod 25}
        :from "noreply@myapp.example.com"}

 :session-key #env "SESSION_KEY"
 :signing-key #env "SIGNING_KEY"}
```

The `#profile` reader tag selects a value based on the active profile. In dev, the server binds to `0.0.0.0` (all interfaces); in prod, to `127.0.0.1` (localhost only, behind a reverse proxy). The `#env` tag reads from environment variables.

`:base-url` and `:smtp` are placeholders for now -- we use them later for magic-link emails ([the email login-flow chapter](19-auth-email-flow.md)). They live in config from the start so every environment has them, and so the config test we write in [the testing chapter](08-unit-testing.md) can assert their presence.

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

(defn- resolve-keys
  "Convert string keys to bytes, generate dev keys when not configured."
  [config]
  (-> config
      (update :session-key
              (fn [^String k]
                (or (when k (.getBytes k "ISO-8859-1"))
                    (do (println "Warning: Generating random session key (dev mode)")
                        (println "Warning: Sessions will not survive server restart")
                        (generate-session-key)))))))

(defn load-config
  "Load and resolve config.edn for the given profile."
  ([] (load-config (active-profile)))
  ([profile]
   (-> (io/resource "config.edn")
       (aero/read-config {:profile profile})
       resolve-keys)))

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
- **Dev mode generates random keys** when environment variables are not set. This means you can start the REPL without configuring anything, but sessions won't survive a restart. Good enough for development, impossible to forget in production (it will fail loudly if `SESSION_KEY` is not set).

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

### The Route Tree

```clojure
(def routes
  [[""
    ["/" {:get handler/home}]
    ["/health"
     {:get (fn [_] (handler/json-response {:status "ok"}))}]]])
```

Each route is a vector of `[path data]`. The data map associates HTTP methods with handler functions. Routes nest naturally -- you can group related paths under a common prefix.

We start with just two routes, because they are the two we can actually serve today: a home page and a health check. The real application grows this tree substantially -- auth endpoints, a dashboard, the recipe pages, an admin area -- but each of those needs machinery we have not built yet (sessions that mean something, a database, the view layer), so we add them in the chapters that introduce that machinery. Just as important, the *shape* of this tree changes too: the flat list here becomes a set of nested groups, each wrapped in its own middleware (current-user, auth, terms-accepted, admin) once those exist. So treat this as the seed, not the final form.

The `/health` endpoint is defined inline since it's trivial: return `{"status":"ok"}` with a 200. This is the endpoint your load balancer or monitoring tool will poll. The other route points at `myapp.web.handler`, which we create next.

### The Handler Namespace

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

`json-response` builds a plain Ring response map: set the content type, serialize the data to JSON, done. The `json` alias is `clojure.data.json` (the `org.clojure/data.json` dependency from our `deps.edn`); `write-str` turns a Clojure value into a JSON string, and we pass a string body straight through unchanged. `home` returns a stub HTML page for now -- the [Hiccup views chapter](11-hiccup-views.md) replaces its body with a real server-rendered view, and the [auth](18-auth-tokens.md) and dashboard chapters add the handlers the fuller route tree calls.

### The Middleware Stack

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

Let's walk through the middleware stack:

1. **`wrap-params`** -- Parses query string and form body parameters into a `:params` map on the request.
2. **`wrap-keyword-params`** -- Converts string parameter keys to keywords, so you get `(:email params)` instead of `(get params "email")`.
3. **`wrap-session`** -- Manages sessions using encrypted cookies. The session key comes from our config. Cookie attributes are set for security: `http-only` prevents JavaScript access, `secure` requires HTTPS, `same-site :lax` provides CSRF protection, and `max-age` sets a 30-day expiry. One consequence of `secure` is worth stating now: the browser will only *send the cookie back* over HTTPS, so authenticated flows must be reached over TLS -- which in dev means the Caddy `.lan` hostname above (`https://myapp.lan`), not `http://localhost:3000`. The plain-`localhost` `curl` we run in a moment hits `/health`, which carries no session, so it is unaffected; but if you log in over plain HTTP and wonder why the session never sticks, this attribute is why. We keep `secure` on unconditionally rather than relaxing it in dev, because the dev environment is already HTTPS by design.

Two structural choices worth noting:

- **`app*` is a `delay`**, just like our config. This prevents the middleware stack from being built at compile time (which would try to read config, which might not be available yet). It's built once on the first request.
- **`app` is a plain function** that derefs the delay. The server receives `#'routes/app` (a var reference), which means you can redefine `app` at the REPL and the server picks up changes without restarting.

This three-entry stack is the foundation, not the whole. Later chapters insert more middleware here -- locale negotiation ([i18n](09-i18n.md)), a no-cache guard for authenticated pages, the current-user/auth/terms/admin gates ([auth](18-auth-tokens.md), [admin](21-admin-dashboard.md)), and a strict Content-Security-Policy ([asset pipeline](22-asset-pipeline.md)) -- and `myapp.config` learns to resolve more keys (a `:signing-key` for magic-link HMAC, `:database-uri`, an `:admin-email`) the same way it resolves `:session-key` here. The mechanism does not change; the stack and the config just get longer.

## The Entry Point

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

**http-kit's stop function.** `http-kit/run-server` returns a zero-argument function. Calling it stops the server. We store this function in the atom and call it in `stop-server!`. The `:timeout 100` gives existing connections 100ms to complete before being closed.

**`(:gen-class)`.** This tells the Clojure compiler to generate a Java class with a static `main` method, which is what `java -jar` expects when running the uberjar in production.

**`start-server!` will gain startup steps.** Right now it only boots http-kit. As later chapters add a database and a content-hashed asset pipeline, this function grows a few lines at the front -- creating the Datomic database ([Datomic chapter](07-datomic.md)) and loading the asset manifest ([asset pipeline](22-asset-pipeline.md)) before the server accepts traffic. Likewise, `dev/user.clj`'s `start!` is rewired to go through the hot-reload entry point once [live reload](06-live-reload.md) exists. The skeleton here is deliberately the minimum that runs.

## REPL-Driven Development

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

The workflow looks like this:

1. Start your REPL: `clj -M:dev:repl`
2. In the REPL (or from your editor): `(start!)`
3. The server starts on port 3000
4. Edit code in your editor, save the file
5. Evaluate the changed namespace (your editor sends it to the REPL)
6. Changes take effect immediately -- no restart needed, thanks to the var reference

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

## Try It

Start the REPL and server:

```bash
clj -M:dev:repl
```

```clojure
(start!)
;; Starting server on 0.0.0.0:3000...
;; Server running at http://localhost:3000
```

Hit the health endpoint:

```bash
curl http://localhost:3000/health
# {"status":"ok"}
```

That `curl` runs from inside the devcontainer, where the app listens on `0.0.0.0:3000`. From the host, go through Caddy at the `.lan` hostname set up in [the devcontainer chapter](03-devcontainer.md) -- `curl https://myapp.lan/health` -- which is also how a browser reaches the app.

Stop it:

```clojure
(stop!)
;; Stopping server...
;; Server stopped
```

## What You Have Now

You have a running Clojure web server with:

- **Ring** handling the HTTP abstraction -- requests and responses as plain maps
- **http-kit** serving requests on a configurable host and port
- **Reitit** routing requests to handler functions based on path and method
- **Aero** loading environment-specific configuration from a single EDN file
- **A clean entry point** in `myapp.core` with start/stop/restart lifecycle
- **A REPL workflow** where code changes take effect immediately without restarting

This is a foundation you can build on. The pieces are independent and composable. When you need authentication, you add middleware. When you need more routes, you extend the route vector. When you need a database, you add it to your startup sequence. Nothing forces you into a structure you don't want.

Next, we sharpen the development loop with live reload so changes show up in the browser the instant you save. Soon after, a test suite -- covering config loading, routing, and the middleware stack -- lets us keep refactoring this foundation without fear of breaking it.
