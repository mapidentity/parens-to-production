(ns myapp.web.routes
  "Reitit route definitions and the Ring middleware stack.
  Maps URL paths to handlers and composes middleware (params, sessions, locale,
  auth, terms gate, admin gate)."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [datomic.api :as d]
    [myapp.analytics.db :as analytics]
    [myapp.auth.core :as auth]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.i18n :as i18n]
    [myapp.web.assets :as assets]
    [myapp.web.handler :as handler]
    [myapp.web.metrics :as metrics]
    [myapp.web.security :as security]
    [myapp.web.views :as views]
    [reitit.ring :as ring]
    [ring.middleware.keyword-params :as keyword-params]
    [ring.middleware.params :as params]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as cookie]
    [ring.util.response :as response]))

(set! *warn-on-reflection* true)

(def ^:private loopback-peers
  ;; The addresses the reverse proxy connects from. In prod the app binds
  ;; 127.0.0.1, so the ONLY peer it ever sees is the on-box proxy — which is
  ;; exactly what makes the forwarded header trustworthy from these, and
  ;; only these, sources.
  #{"127.0.0.1" "::1" "0:0:0:0:0:0:0:1"})

(defn wrap-client-ip
  "Resolve the real client IP and assoc it as `:client-ip`.

  The app sits behind Caddy on loopback, so `:remote-addr` is ALWAYS the
  proxy (127.0.0.1) in production — which silently collapsed every per-IP
  control to one global bucket. Caddy sets `X-Client-IP` to the real peer
  it saw (`header_up X-Client-IP {remote_host}`, ops/Caddyfile),
  overwriting anything the client sent, so the header is authoritative —
  but ONLY when the request actually reached us through the proxy. We
  trust it solely when the TCP peer is loopback; a direct hit (no proxy)
  keeps its own peer address, so the header can never be spoofed by a
  client that bypasses Caddy. `:remote-addr` is left untouched for the
  code that genuinely wants the peer (the /metrics loopback belt)."
  [handler]
  (fn [request]
    (let [peer (:remote-addr request)
          fwd (when (contains? loopback-peers peer)
                (some-> (get-in request [:headers "x-client-ip"])
                        str/trim
                        not-empty))]
      (handler
        (assoc request
          :client-ip (or fwd peer "?"))))))

(defn wrap-locale
  "Determine locale from session, Accept-Language header, or default.
  Assoc it as `:locale` on the request."
  [handler]
  (fn [request]
    (let [locale (or
                   (get-in request [:session :locale])
                   (i18n/detect-locale (get-in request [:headers "accept-language"]))
                   i18n/default-locale)]
      (handler
        (assoc request
          :locale locale)))))

(defonce ^:private build-token
  ;; The rendering code's identity in the page validator. A production jar
  ;; carries a `build-id` resource baked at uberjar time (the git sha), so
  ;; every process of one build shares one token — two instances behind one
  ;; proxy must agree or ETags flap between them — while a deploy still
  ;; invalidates every cached page. Dev has no jar and no pair, so it falls
  ;; back to a per-process UUID, keeping restart-invalidates semantics.
  ;; An identity, not a timestamp, either way: the swappable clock
  ;; (myapp.time) must stay free to lie in tests.
  (or
    (some-> (io/resource "build-id")
            slurp
            str/trim
            not-empty)
    (str (random-uuid))))

(defn- current-validator
  "The ETag any anonymous HTML page has RIGHT NOW.
  Such a page is a pure function of exactly three things: the database
  basis-t, the locale, and this build of the rendering code (build-token).
  Computable without rendering anything — which is what lets a matching
  If-None-Match short-circuit the whole handler."
  [locale]
  (str "W/\"" (d/basis-t (d/db (db/get-connection))) "-" (name locale) "-" build-token "\""))

(defn wrap-conditional-get
  "Conditional GET for anonymous HTML pages, validated by basis-t.
  Request side: a matching If-None-Match answers 304 before the handler
  runs — no query, no render. Response side: 200 text/html responses that
  carry no Cache-Control of their own get the validator plus no-cache
  (always revalidate; revalidation is the cheap part). Authenticated
  requests pass through untouched (wrap-no-cache-authenticated owns them),
  as do responses that already state a cache policy (immutable
  point-in-time pages, no-store partials) and non-HTML (static assets have
  their own hashed-URL story)."
  [handler]
  (fn [request]
    (if-not (and (= :get (:request-method request)) (nil? (get-in request [:session :user-email])))
      (handler request)
      (let [validator (current-validator (:locale request))]
        (if (= validator (get-in request [:headers "if-none-match"]))
          {:status 304
           :headers {"ETag" validator
                     "Cache-Control" "no-cache"
                     "Vary" "Accept-Language"}}
          (let [response (handler request)]
            (if
              (and
                (= 200 (:status response))
                (some-> (get-in response [:headers "Content-Type"])
                        (str/starts-with? "text/html"))
                (nil? (get-in response [:headers "Cache-Control"])))
              (update
                response
                :headers
                merge
                {"ETag" validator
                 "Cache-Control" "no-cache"
                 "Vary" "Accept-Language"})
              response)))))))

(defn wrap-no-cache-authenticated
  "Set Cache-Control: no-store on authenticated HTML responses.
  This stops the browser bfcache showing a stale page after logout."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (get-in request [:session :user-email])
        (assoc-in response [:headers "Cache-Control"] "no-store")
        response))))

(defn admin-email?
  "True when `email` matches the configured admin email (case-insensitive).
  The single source of the admin predicate — resolved once per request in
  `wrap-current-user` and read back as `:admin?` by handlers and `wrap-admin`."
  [email]
  (let [admin-email (config/get-config :admin-email)]
    (boolean (and email admin-email (= (str/lower-case email) (str/lower-case admin-email))))))

(defn wrap-current-user
  "Soft auth that never redirects.
  If the session names an existing user, resolve the user entity ONCE and assoc
  `:user-email`, `:user-eid`, and `:admin?` onto the request; otherwise pass
  through unchanged.

  This is what lets the PUBLIC, auth-aware pages (the recipe reads) show a
  signed-in visitor their own controls — Edit/Delete on recipes they own, the
  authenticated Fork button — while anonymous visitors still get the page.
  `wrap-auth` below is the HARD gate for routes that require a user; it relies on
  this middleware having run first (it always nests inside it), so the user
  lookup happens exactly once per request rather than once per gate."
  [handler]
  (fn [request]
    (let [db (d/db (db/get-connection))
          email (get-in request [:session :user-email])
          eid (when email (auth/find-user-by-email db email))
          ;; The ban lever: `:user/active?` explicitly false deactivates the
          ;; session on its very next request — live, no redeploy, no key
          ;; rotation. `false?` (not `not`) is deliberate: a missing flag
          ;; never bans, so a schema gap can't lock the userbase out.
          banned? (and eid (false? (:user/active? (db/pull* db [:user/active?] eid))))]
      (cond
        (and eid (not banned?))
        (handler
          (assoc request
            :user-email email
            :user-eid eid
            :admin? (admin-email? email)))
        banned?
        ;; Treated as anonymous from here (wrap-auth bounces protected
        ;; routes to /), and logged: a banned session still trying is worth
        ;; the operator's eye.
        (do
          (security/event!
            :auth/banned-attempt
            {:ip (or (:client-ip request) "?")
             :email email})
          (handler request))
        :else (handler request)))))

(defn wrap-auth
  "Hard gate for protected routes — assumes `wrap-current-user` ran first.

  `wrap-current-user` (which always nests outside this) has already resolved the
  session user to `:user-eid`; we only check it here, so the lookup is not
  repeated. Unauthenticated requests are rejected — HTML gets a redirect to `/`,
  JSON-tagged routes get a 401. A stale session (cookie email but no such user)
  arrives here with no `:user-eid`, so it is treated as unauthenticated AND the
  session is cleared, to avoid a landing-page redirect loop."
  [handler]
  (fn [request]
    (cond
      (:user-eid request) (handler request)
      (get-in request [:reitit.core/match :data :json?])
      (handler/json-response {:error "unauthorized"} :status 401)
      :else (-> (response/redirect "/")
                (assoc :session nil)))))

(defn wrap-terms-accepted
  "Require `:user/terms-accepted-at` on the authenticated user, else redirect to `/terms/welcome`.
  Apply only below `wrap-auth`. The logout, welcome, and
  accept routes sit ABOVE this so onboarding stays reachable. Putting the gate
  in middleware means it can't be bypassed by direct POSTs."
  [handler]
  (fn [request]
    (let [user-eid (:user-eid request)
          accepted? (when user-eid
                      (-> (db/pull* (d/db (db/get-connection)) [:user/terms-accepted-at] user-eid)
                          :user/terms-accepted-at
                          some?))]
      (if accepted? (handler request) (response/redirect "/terms/welcome")))))

(defn wrap-admin
  "Restrict access to admin routes (assumes wrap-current-user + wrap-auth ran first).
  Reads the `:admin?` flag those middlewares already resolved. Non-admins get a
  redirect to /dashboard (HTML) or 403 (JSON-tagged routes)."
  [handler]
  (fn [request]
    (if (:admin? request)
      (do
        (security/event!
          :admin/access
          {:ip (or (:client-ip request) "?")
           :email (or (:user-email request) "?")
           :uri (:uri request)})
        (handler request))
      ;; A non-admin reaching an admin route is either a confused user or
      ;; someone probing the privileged surface. Either way the operator
      ;; wants it, with the identity that tried.
      (do
        (security/event!
          :admin/denied
          {:ip (or (:client-ip request) "?")
           :email (or (:user-email request) "anonymous")
           :uri (:uri request)})
        (if (get-in request [:reitit.core/match :data :json?])
          (handler/json-response {:error "forbidden"} :status 403)
          (response/redirect "/dashboard"))))))

(defn- dev-json-handler
  "Build a dev-only Ring handler for a construction-view tracer JSON endpoint.
  `sym` is the trace fn's qualified symbol, resolved lazily — in prod / non-storm
  the trace ns is absent, the resolve throws, and the try/catch below turns that
  into a 404. `extract` is called with the resolved fn and the
  request and returns a JSON string (or nil → 404). Centralizes the
  requiring-resolve + try/catch + 200/404 plumbing every /dev/__* route repeated."
  [sym extract]
  (fn [request]
    (or
      (try
        (when-let [f (requiring-resolve sym)]
          (when-let [j (extract f request)]
            {:status 200
             :headers {"Content-Type" "application/json"
                       "Cache-Control" "no-store"}
             :body j}))
        (catch Throwable _ nil))
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body "{}"})))

(def routes
  "Reitit route tree.
  Handler references use #' (vars) so hot-reload picks up
  changes without rebuilding the router.

  Authentication is enforced by nesting: routes under `{:middleware [wrap-auth]}`
  run only for authenticated users; the recipe READ routes (browse, detail,
  history, point-in-time, diff) sit outside it and are public — browsing and
  forking other people's recipes is the whole point. Mutations are gated by
  wrap-auth + wrap-terms-accepted."
  [["" {:middleware [wrap-current-user]}
    ;; ---- Public (auth-aware via wrap-current-user) ----
    ["/" {:get #'handler/home}]
    ["/health"
     {:get (fn [_]
             ;; Proves the process, its config, and both database connections.
             ;; d/db is a local read against the peer's cache, so this stays
             ;; cheap enough to poll. It deliberately does NOT prove the
             ;; transactor is accepting writes: there is no side-effect-free
             ;; probe for that, and a health check that transacts is worse
             ;; than one that under-promises.
             (handler/json-response
               {:status "ok"
                :basis-t (d/basis-t (d/db (db/get-connection)))
                :analytics-basis-t (d/basis-t (analytics/get-db))}))}]
    ["/csp-report" {:post #'handler/csp-report}]
    ["/client-error" {:post #'handler/client-error}]
    ["/metrics"
     {:get (fn [request]
             ;; Operator surface. The real wall is the proxy: ops/Caddyfile
             ;; answers 404 for /metrics and never forwards it, so from the
             ;; internet the route does not exist. This loopback check is
             ;; the dev-mode belt (dev binds 0.0.0.0 for the compose
             ;; network); in prod every request arrives via the proxy from
             ;; loopback anyway, which is why the proxy must be the wall.
             (if (contains? #{"127.0.0.1" "::1" "0:0:0:0:0:0:0:1"} (:remote-addr request))
               {:status 200
                :headers {"Content-Type" "text/plain; version=0.0.4"}
                :body (metrics/render)}
               {:status 404
                :headers {"Content-Type" "text/plain; charset=UTF-8"}
                :body "Not found."}))}]
    ["/auth/request" {:post #'handler/request-magic-link}]
    ["/auth/sent" {:get #'handler/magic-link-sent}]
    ["/auth/verify" {:get #'handler/verify-magic-link}]
    ["/sitemap.xml" {:get #'handler/sitemap}]
    ["/robots.txt" {:get #'handler/robots}]
    ["/search" {:get #'handler/search-page}]
    ["/recipes" {:get #'handler/recipes-index}]
    ;; Random landing tagline, fetched by the `tagline` island so the landing
    ;; page itself stays deterministic/cacheable. See handler/tagline.
    ["/partials/tagline" {:get #'handler/tagline}]

    ;; ---- Authenticated ----
    ["" {:middleware [wrap-auth]}
     ;; Reachable before terms acceptance.
     ["/auth/logout" {:post #'handler/logout}]
     ["/terms/welcome" {:get #'handler/terms-welcome}]
     ["/terms/accept" {:post #'handler/accept-terms}]

     ;; ---- Authenticated + terms accepted ----
     ["" {:middleware [wrap-terms-accepted]}
      ["/dashboard" {:get #'handler/dashboard}]
      ;; Recipe mutations. These are declared BEFORE the public dynamic
      ;; `/recipes/:id` reads below so the static `/recipes/new` is inserted
      ;; first and wins the match (reitit picks the first inserted route when
      ;; conflict resolution is disabled — see `:conflicts nil`).
      ["/recipes/new"
       {:get #'handler/recipe-new-form
        :post #'handler/recipe-create}]
      ;; Dashboard reorder (drag-drop full order, or no-JS up/down step). Static
      ;; path declared before the dynamic `/recipes/:id` reads so it wins.
      ["/recipes/reorder" {:post #'handler/recipe-reorder}]
      ["/recipes/new/preview"
       {:post #'handler/recipe-preview}]
      ["/recipes/:id/preview"
       {:post #'handler/recipe-preview}]
      ["/recipes/:id/edit"
       {:get #'handler/recipe-edit-form
        :post #'handler/recipe-update}]
      ["/recipes/:id/fork" {:post #'handler/recipe-fork}]
      ["/recipes/:id/delete" {:post #'handler/recipe-delete}]
      ["/admin" {:middleware [wrap-admin]}
       ["" {:get #'handler/admin-dashboard}]
       ["/stats"
        {:json? true
         :get #'handler/admin-stats}]]]]

    ;; ---- Public recipe reads (Datomic-powered time travel) ----
    ;; Declared last so the static mutation paths above win over `:id`.
    ["/recipes/:id" {:get #'handler/recipe-show}]
    ["/recipes/:id/history" {:get #'handler/recipe-history}]
    ["/recipes/:id/at/:t" {:get #'handler/recipe-version}]
    ["/recipes/:id/diff" {:get #'handler/recipe-diff}]]
   ["/dev/ws"
    {:get (fn [request]
            ;; dev-reload is absent in prod: the resolve THROWS there (it does
            ;; not return nil), and the catch is what turns that into a 404.
            (if-let [handler (try
                               (requiring-resolve 'dev-reload/websocket-handler)
                               (catch Throwable _ nil))]
              (handler request)
              {:status 404}))}]
   ;; Construction-view tracer (dev+storm only): the overlay fetches a page's
   ;; recorded data by the id it reads from <html data-myapp-trace-id>. Each
   ;; endpoint resolves its trace fn lazily via `dev-json-handler`, so all of
   ;; these 404 in prod (the dev trace ns isn't loaded); each route below supplies
   ;; only how to pull its args from the request.
   ["/dev/__trace/:id"
    {:get
     (dev-json-handler 'trace/get-trace-json (fn [f req] (f (get-in req [:path-params :id]))))}]
   ;; Flow drill-down: ?name=<data-myapp-name>&idx=<instance>&src=<file:line:col>
   ["/dev/__flow/:id"
    {:get (dev-json-handler
            'trace/get-flow-json
            (fn [f req]
              (let [{nm :name
                     :keys [idx src]}
                    (:params req)
                    line (some-> src
                                 (str/split #":")
                                 (nth 1 nil)
                                 (->> (re-find #"\d+"))
                                 parse-long)]
                (f
                  (get-in req [:path-params :id])
                  nm
                  (or
                    (some-> idx
                            parse-long)
                    0)
                  line))))}]
   ;; Expand a recorded value one level: ?frame=<id>&slot=arg0|ret&path=0,2,1
   ["/dev/__value/:id"
    {:get (dev-json-handler
            'trace/get-value-json
            (fn [f req]
              (let [{:keys [frame slot path]} (:params req)
                    p (if (seq path) (vec (keep parse-long (str/split path #","))) [])]
                (f
                  (get-in req [:path-params :id])
                  (some-> frame
                          parse-long)
                  slot
                  p))))}]
   ;; Conditionals in a frame that didn't render (why a section is empty): ?frame=<id>
   ["/dev/__branches/:id"
    {:get (dev-json-handler
            'trace/get-branches-json
            (fn [f req]
              (f
                (get-in req [:path-params :id])
                (some-> (get-in req [:params :frame])
                        parse-long))))}]
   ;; Where a frame's entity came from (producing DB reads): ?frame=<id>
   ["/dev/__source/:id"
    {:get (dev-json-handler
            'trace/get-source-json
            (fn [f req]
              (f
                (get-in req [:path-params :id])
                (some-> (get-in req [:params :frame])
                        parse-long))))}]
   ;; The produced-markup (Hiccup) tree of a frame: ?frame=<id>&slot=ret
   ["/dev/__hiccup/:id"
    {:get (dev-json-handler
            'trace/get-hiccup-json
            (fn [f req]
              (let [{:keys [frame slot]} (:params req)]
                (f
                  (get-in req [:path-params :id])
                  (some-> frame
                          parse-long)
                  slot))))}]
   ;; Every frame a value flows through: ?frame=<id>&slot=arg0|ret
   ["/dev/__value-threads/:id"
    {:get (dev-json-handler
            'trace/get-threads-json
            (fn [f req]
              (let [{:keys [frame slot]} (:params req)]
                (f
                  (get-in req [:path-params :id])
                  (some-> frame
                          parse-long)
                  slot))))}]
   ;; The most recent uncaught-error trace (so a good page can surface a prior 500)
   ["/dev/__last-error"
    {:get (dev-json-handler 'trace/get-last-error-json (fn [f _req] (f)))}]
   ;; On-demand recorder clear: lets a long dev session reclaim memory.
   ;; The trace ns is absent in prod / non-storm: the resolve throws there and
   ;; the catch makes this a harmless no-op.
   ["/dev/__trace-clear"
    {:get (fn [_]
            (when-let [c (try
                           (requiring-resolve 'trace/clear-recordings!)
                           (catch Throwable _ nil))]
              (c))
            {:status 200
             :headers {"Content-Type" "application/json"
                       "Cache-Control" "no-store"}
             :body "{\"cleared\":true}"})}]])

(defn wrap-dev-no-store
  "Dev only: Cache-Control: no-store on served .css/.js so a stable (unhashed) dev URL never serves stale bytes after Tailwind --watch / esbuild rewrites the file."
  [handler]
  (fn [request]
    (let [resp (handler request)]
      (if (and resp (re-find #"\.(?:css|js)$" (or (:uri request) "")))
        (assoc-in resp [:headers "Cache-Control"] "no-store")
        resp))))

(defn wrap-csp
  "Set the app's strict, no-nonce Content-Security-Policy on HTML responses.
  Static assets (served by Caddy in prod) don't need it. See myapp.web.assets/csp-header."
  [handler]
  (fn [request]
    (let [resp (handler request)
          ct (get-in resp [:headers "Content-Type"])]
      (if (and ct (str/includes? ct "text/html"))
        (-> resp
            (assoc-in [:headers "Content-Security-Policy"] (assets/csp-header))
            (assoc-in [:headers "Reporting-Endpoints"] "csp=\"/csp-report\""))
        resp))))

(defn wrap-errors
  "Catch-all: an uncaught exception becomes a logged, generic 500.

  Innermost of the base stack, so the error page still flows out through the
  CSP/cache layers like any other HTML response, and `wrap-locale` has already
  run on the way in. The page reveals nothing about the failure — not the
  class, not the message; the stack trace goes to the log, where it belongs."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (log/error t "Unhandled exception" (select-keys request [:request-method :uri]))
        (let [locale (:locale request :en)]
          {:status 500
           :headers {"Content-Type" "text/html; charset=UTF-8"}
           :body (str (views/error-page locale (i18n/t locale :error/server-error)))})))))

(defn wrap-panic
  "Last-resort catch around the entire middleware stack.

  `wrap-errors` (innermost) owns the styled 500; this belt exists for
  exceptions thrown by the stack itself — a session cookie that fails to
  decrypt, the conditional-GET database read — which would otherwise
  surface as http-kit's bare default. No locale, no views: those layers
  may be exactly what broke. Plain text out, the stack trace to the log."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (log/error t "Exception escaped the middleware stack"
          (select-keys request [:request-method :uri]))
        {:status 500
         :headers {"Content-Type" "text/plain; charset=UTF-8"}
         :body "Internal server error."}))))

(defn wrap-metrics
  "Fold every served response into the request counters.

  Status class and duration only — no per-path labels, because label
  cardinality is a budget and URLs with ids in them would spend it all.
  Monotonic nanoTime, not the app clock: a duration is an interval, and
  the swappable clock (myapp.time) must stay free to lie in tests
  without bending the metrics."
  [handler]
  (fn [request]
    (let [t0 (System/nanoTime)
          response (handler request)]
      (metrics/record-request! (:status response) (- (System/nanoTime) t0))
      response)))

(def ^:private app*
  "Compiled Ring handler, built lazily to avoid loading config at compile time."
  (delay
    (let [base-mw [[wrap-panic]
                   [wrap-client-ip]
                   [wrap-metrics]
                   [params/wrap-params] [keyword-params/wrap-keyword-params]
                   [session/wrap-session
                    {:store (cookie/cookie-store {:key (config/get-config :session-key)})
                     :cookie-name "session"
                     :cookie-attrs {:http-only true
                                    :secure true
                                    :same-site :lax
                                    :max-age (* 30 24 60 60)}}]
                   [wrap-locale]
                   [wrap-conditional-get]
                   [wrap-no-cache-authenticated]
                   [wrap-csp]
                   ;; Innermost: catches what routes/handlers throw; its 500
                   ;; flows back out through wrap-csp like any other page.
                   [wrap-errors]]
          ;; DEV + STORM only: the construction-view tracer, OUTERMOST so its
          ;; per-request timeline window spans the whole synchronous handler
          ;; (incl. the myapp middleware below). Gated on the ClojureStorm system
          ;; property so plain :dev and prod never resolve (or load) the dev ns.
          ;; deref to the middleware FN — reitit's IntoMiddleware has no impl for a Var
          mw (if-let [wt (when (System/getProperty "clojure.storm.instrumentEnable")
                           (try
                             (some-> (requiring-resolve 'trace/wrap-trace)
                                     deref)
                             (catch Throwable _ nil)))]
               (into [[wt]] base-mw)
               base-mw)]
      (ring/ring-handler
        ;; `:conflicts nil` tolerates the static `/recipes/new` vs dynamic
        ;; `/recipes/:id` overlap — reitit matches conflicting routes in
        ;; declaration order, so `/recipes/new` (declared first) wins and only
        ;; other ids fall through to `recipe-show`.
        (ring/router routes {:conflicts nil})
        ;; Serve static assets (CSS, JS, SVGs, fonts) from the static/ dir, then
        ;; fall back to a branded 404 — reitit's default is a bare text/plain
        ;; response wearing none of the app's chrome. The fallback runs inside
        ;; the middleware stack, so :locale is set and the page exits through
        ;; the CSP layer like any other.
        (ring/routes
          (cond-> (ring/create-file-handler {:path "/"
                                             :root assets/static-root})
            assets/dev? wrap-dev-no-store)
          (ring/create-default-handler
            {:not-found (fn [request]
                          (let [locale (:locale request :en)]
                            {:status 404
                             :headers {"Content-Type" "text/html; charset=UTF-8"}
                             :body
                             (str (views/error-page locale (i18n/t locale :error/not-found)))}))}))
        {:middleware mw}))))

(defn app
  "Ring handler entry point. Delegates to the lazily-compiled handler."
  [request]
  (@app* request))
