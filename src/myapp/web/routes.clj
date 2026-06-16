(ns myapp.web.routes
  "Reitit route definitions and the Ring middleware stack.
  Maps URL paths to handlers and composes middleware (params, sessions, locale,
  auth, terms gate, admin gate)."
  (:require
    [clojure.string :as str]
    [datomic.api :as d]
    [myapp.auth.core :as auth]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.i18n :as i18n]
    [myapp.web.assets :as assets]
    [myapp.web.handler :as handler]
    [reitit.ring :as ring]
    [ring.middleware.keyword-params :as keyword-params]
    [ring.middleware.params :as params]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as cookie]
    [ring.util.response :as response]))

(defn wrap-locale
  "Determine locale from session, Accept-Language header, or default, and assoc
  it as `:locale` on the request."
  [handler]
  (fn [request]
    (let [locale (or
                   (get-in request [:session :locale])
                   (i18n/detect-locale (get-in request [:headers "accept-language"]))
                   i18n/default-locale)]
      (handler (assoc request :locale locale)))))

(defn wrap-no-cache-authenticated
  "Set Cache-Control: no-store on authenticated HTML responses, so the browser
  bfcache can't show a stale page after logout."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (get-in request [:session :user-email])
        (assoc-in response [:headers "Cache-Control"] "no-store")
        response))))

(defn wrap-current-user
  "Soft auth: if the session names an existing user, assoc `:user-email` and
  `:user-eid` onto the request; otherwise pass through unchanged. Never
  redirects.

  This is what lets the PUBLIC, auth-aware pages (the recipe reads) show a
  signed-in visitor their own controls — Edit/Delete on recipes they own, the
  authenticated Fork button — while anonymous visitors still get the page.
  `wrap-auth` below is the HARD gate for routes that require a user."
  [handler]
  (fn [request]
    (let [email (get-in request [:session :user-email])
          eid (when email (auth/find-user-by-email (d/db (db/get-connection)) email))]
      (if eid
        (handler (assoc request :user-email email :user-eid eid))
        (handler request)))))

(defn wrap-auth
  "Establish the authenticated user for protected routes.

  Reads `:user-email` from the session, resolves the user entity, and assocs
  `:user-email` and `:user-eid` onto the request. Unauthenticated requests are
  rejected — HTML gets a redirect to `/`, JSON-tagged routes get a 401. Stale
  sessions (cookie email but no such user) are treated as unauthenticated AND
  the session is cleared, to avoid a landing-page redirect loop."
  [handler]
  (fn [request]
    (let [user-email (get-in request [:session :user-email])
          user-eid (when user-email
                     (auth/find-user-by-email (d/db (db/get-connection)) user-email))
          json? (get-in request [:reitit.core/match :data :json?])]
      (cond
        user-eid (handler (assoc request :user-email user-email :user-eid user-eid))
        json? (handler/json-response {:error "unauthorized"} :status 401)
        :else (-> (response/redirect "/") (assoc :session nil))))))

(defn wrap-terms-accepted
  "Require `:user/terms-accepted-at` on the authenticated user, else redirect to
  `/terms/welcome`. Apply only below `wrap-auth`. The logout, welcome, and
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
  "Restrict access to admin routes (assumes wrap-auth ran first). Compares the
  session email (case-insensitive) to `:admin-email`. Non-admins get a redirect
  to /dashboard (HTML) or 403 (JSON-tagged routes)."
  [handler]
  (fn [request]
    (let [user-email (:user-email request)
          admin-email (config/get-config :admin-email)
          json? (get-in request [:reitit.core/match :data :json?])
          admin? (and user-email admin-email
                      (= (str/lower-case user-email) (str/lower-case admin-email)))]
      (if admin?
        (handler request)
        (if json?
          (handler/json-response {:error "forbidden"} :status 403)
          (response/redirect "/dashboard"))))))

(def routes
  "Reitit route tree. Handler references use #' (vars) so hot-reload picks up
  changes without rebuilding the router.

  Authentication is enforced by nesting: routes under `{:middleware [wrap-auth]}`
  run only for authenticated users; the recipe READ routes (browse, detail,
  history, point-in-time, diff) sit outside it and are public — browsing and
  forking other people's recipes is the whole point. Mutations are gated by
  wrap-auth + wrap-terms-accepted."
  [["" {:middleware [wrap-current-user]}
    ;; ---- Public (auth-aware via wrap-current-user) ----
    ["/" {:get #'handler/home}]
    ["/health" {:get (fn [_] (handler/json-response {:status "ok"}))}]
    ["/csp-report" {:post #'handler/csp-report}]
    ["/auth/request" {:post #'handler/request-magic-link}]
    ["/auth/sent" {:get #'handler/magic-link-sent}]
    ["/auth/verify" {:get #'handler/verify-magic-link}]
    ["/recipes" {:get #'handler/recipes-index}]

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
      ["/recipes/new" {:get #'handler/recipe-new-form
                       :post #'handler/recipe-create}]
      ["/recipes/:id/edit" {:get #'handler/recipe-edit-form
                            :post #'handler/recipe-update}]
      ["/recipes/:id/fork" {:post #'handler/recipe-fork}]
      ["/recipes/:id/delete" {:post #'handler/recipe-delete}]
      ["/admin" {:middleware [wrap-admin]}
       ["" {:get #'handler/admin-dashboard}]
       ["/stats" {:json? true
                  :get #'handler/admin-stats}]]]]

    ;; ---- Public recipe reads (Datomic-powered time travel) ----
    ;; Declared last so the static mutation paths above win over `:id`.
    ["/recipes/:id" {:get #'handler/recipe-show}]
    ["/recipes/:id/history" {:get #'handler/recipe-history}]
    ["/recipes/:id/at/:t" {:get #'handler/recipe-version}]
    ["/recipes/:id/diff" {:get #'handler/recipe-diff}]]
   ["/dev/ws"
    {:get (fn [request]
            (if-let [handler (requiring-resolve 'dev-reload/websocket-handler)]
              (handler request)
              {:status 404}))}]
   ;; Construction-view tracer (dev+storm only): the overlay fetches a page's
   ;; recorded trace by the id it reads from <html data-myapp-trace-id>.
   ;; requiring-resolve is nil without the :dev trace ns, so this 404s in prod.
   ["/dev/__trace/:id"
    {:get (fn [request]
            (or (try
                  (when-let [gj (requiring-resolve 'trace/get-trace-json)]
                    (when-let [j (gj (get-in request [:path-params :id]))]
                      {:status 200
                       :headers {"Content-Type" "application/json"
                                 "Cache-Control" "no-store"}
                       :body j}))
                  (catch Throwable _ nil))
                {:status 404
                 :headers {"Content-Type" "application/json"}
                 :body "{}"}))}]
   ;; Flow drill-down: ?name=<data-myapp-name>&idx=<instance>&src=<file:line:col>
   ["/dev/__flow/:id"
    {:get (fn [request]
            (or (try
                  (when-let [gf (requiring-resolve 'trace/get-flow-json)]
                    (let [{:keys [name idx src]} (:params request)
                          line (some-> src (str/split #":") (nth 1 nil) (->> (re-find #"\d+")) parse-long)]
                      (when-let [j (gf (get-in request [:path-params :id])
                                       name (or (some-> idx parse-long) 0) line)]
                        {:status 200
                         :headers {"Content-Type" "application/json" "Cache-Control" "no-store"}
                         :body j})))
                  (catch Throwable _ nil))
                {:status 404
                 :headers {"Content-Type" "application/json"}
                 :body "{}"}))}]
   ;; On-demand recorder clear: lets a long dev session reclaim memory.
   ;; requiring-resolve is nil in prod, so this is a harmless no-op there.
   ["/dev/__trace-clear"
    {:get (fn [_]
            (when-let [c (requiring-resolve 'trace/clear-recordings!)] (c))
            {:status 200 :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body "{\"cleared\":true}"})}]
   ;; The most recent uncaught-error trace (so a good page can surface a prior 500)
   ["/dev/__last-error"
    {:get (fn [_]
            (or (try
                  (when-let [le (requiring-resolve 'trace/get-last-error-json)]
                    {:status 200 :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body (le)})
                  (catch Throwable _ nil))
                {:status 404 :headers {"Content-Type" "application/json"} :body "{}"}))}]
   ;; Expand a recorded value one level: ?frame=<id>&slot=arg0|ret&path=0,2,1
   ["/dev/__value/:id"
    {:get (fn [request]
            (or (try
                  (when-let [gv (requiring-resolve 'trace/get-value-json)]
                    (let [{:keys [frame slot path]} (:params request)
                          p (if (seq path) (vec (keep parse-long (str/split path #","))) [])]
                      (when-let [j (gv (get-in request [:path-params :id]) (some-> frame parse-long) slot p)]
                        {:status 200 :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body j})))
                  (catch Throwable _ nil))
                {:status 404 :headers {"Content-Type" "application/json"} :body "{}"}))}]
   ;; Conditionals in a frame that didn't render (why a section is empty): ?frame=<id>
   ["/dev/__branches/:id"
    {:get (fn [request]
            (or (try
                  (when-let [gb (requiring-resolve 'trace/get-branches-json)]
                    (when-let [j (gb (get-in request [:path-params :id]) (some-> (get-in request [:params :frame]) parse-long))]
                      {:status 200 :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body j}))
                  (catch Throwable _ nil))
                {:status 404 :headers {"Content-Type" "application/json"} :body "{}"}))}]
   ;; Where a frame's entity came from (producing DB reads): ?frame=<id>
   ["/dev/__source/:id"
    {:get (fn [request]
            (or (try
                  (when-let [gs (requiring-resolve 'trace/get-source-json)]
                    (when-let [j (gs (get-in request [:path-params :id]) (some-> (get-in request [:params :frame]) parse-long))]
                      {:status 200 :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body j}))
                  (catch Throwable _ nil))
                {:status 404 :headers {"Content-Type" "application/json"} :body "{}"}))}]
   ;; The produced-markup (Hiccup) tree of a frame: ?frame=<id>&slot=ret
   ["/dev/__hiccup/:id"
    {:get (fn [request]
            (or (try
                  (when-let [gh (requiring-resolve 'trace/get-hiccup-json)]
                    (let [{:keys [frame slot]} (:params request)]
                      (when-let [j (gh (get-in request [:path-params :id]) (some-> frame parse-long) slot)]
                        {:status 200 :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body j})))
                  (catch Throwable _ nil))
                {:status 404 :headers {"Content-Type" "application/json"} :body "{}"}))}]
   ;; Every frame a value flows through: ?frame=<id>&slot=arg0|ret
   ["/dev/__value-threads/:id"
    {:get (fn [request]
            (or (try
                  (when-let [gt (requiring-resolve 'trace/get-threads-json)]
                    (let [{:keys [frame slot]} (:params request)]
                      (when-let [j (gt (get-in request [:path-params :id]) (some-> frame parse-long) slot)]
                        {:status 200 :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body j})))
                  (catch Throwable _ nil))
                {:status 404 :headers {"Content-Type" "application/json"} :body "{}"}))}]])

(defn wrap-dev-no-store
  "Dev only: Cache-Control: no-store on served .css/.js so a stable (unhashed) dev
  URL never serves stale bytes after Tailwind --watch / esbuild rewrites the file."
  [handler]
  (fn [request]
    (let [resp (handler request)]
      (if (and resp (re-find #"\.(?:css|js)$" (or (:uri request) "")))
        (assoc-in resp [:headers "Cache-Control"] "no-store")
        resp))))

(defn wrap-csp
  "Set the app's strict, no-nonce Content-Security-Policy on HTML responses; static
  assets (served by Caddy in prod) don't need it. See myapp.web.assets/csp-header."
  [handler]
  (fn [request]
    (let [resp (handler request)
          ct (get-in resp [:headers "Content-Type"])]
      (if (and ct (str/includes? ct "text/html"))
        (-> resp
            (assoc-in [:headers "Content-Security-Policy"] (assets/csp-header))
            (assoc-in [:headers "Reporting-Endpoints"] "csp=\"/csp-report\""))
        resp))))

(def ^:private app*
  "Compiled Ring handler, built lazily to avoid loading config at compile time."
  (delay
    (let [base-mw [[params/wrap-params] [keyword-params/wrap-keyword-params]
                   [session/wrap-session
                    {:store (cookie/cookie-store {:key (config/get-config :session-key)})
                     :cookie-name "session"
                     :cookie-attrs {:http-only true
                                    :secure true
                                    :same-site :lax
                                    :max-age (* 30 24 60 60)}}]
                   [wrap-locale]
                   [wrap-no-cache-authenticated]
                   [wrap-csp]]
          ;; DEV + STORM only: the construction-view tracer, OUTERMOST so its
          ;; per-request timeline window spans the whole synchronous handler
          ;; (incl. the myapp middleware below). Gated on the ClojureStorm system
          ;; property so plain :dev and prod never resolve (or load) the dev ns.
          ;; deref to the middleware FN — reitit's IntoMiddleware has no impl for a Var
          mw (if-let [wt (when (System/getProperty "clojure.storm.instrumentEnable")
                           (try (some-> (requiring-resolve 'trace/wrap-trace) deref)
                                (catch Throwable _ nil)))]
               (into [[wt]] base-mw)
               base-mw)]
      (ring/ring-handler
        ;; `:conflicts nil` tolerates the static `/recipes/new` vs dynamic
        ;; `/recipes/:id` overlap — reitit routes the static path first, so only
        ;; non-\"new\" ids fall through to `recipe-show`.
        (ring/router routes {:conflicts nil})
        ;; Serve static assets (CSS, JS, SVGs, fonts) from the static/ dir, then
        ;; fall back to the default 404 handler.
        (ring/routes
          (cond-> (ring/create-file-handler {:path "/" :root assets/static-root})
            assets/dev? wrap-dev-no-store)
          (ring/create-default-handler))
        {:middleware mw}))))

(defn app
  "Ring handler entry point. Delegates to the lazily-compiled handler."
  [request]
  (@app* request))
