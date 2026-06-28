(ns myapp.lighthouse
  "Lighthouse CI server entry point.
  Starts a clean app instance with auto-authentication for auditing
  both public and authenticated pages. Never shipped to production —
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

(defn- test-email
  "Email for the auto-authenticated Lighthouse test user.
  Read at runtime (not namespace-load time) so it always reflects the active
  config profile. Must match :admin-email so admin pages are accessible."
  []
  (config/get-config :admin-email))

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

(defn- wrap-auto-auth
  "Inject a session with the test user on every request.
  Lighthouse sees authenticated pages without needing cookies."
  [handler]
  (fn [request] (handler (assoc-in request [:session :user-email] (test-email)))))

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
                    [wrap-auto-auth] [routes/wrap-locale]
                    ;; Audit the page production actually ships, CSP header and
                    ;; all — the Lighthouse run would otherwise score a policy
                    ;; the real app never serves.
                    [routes/wrap-csp]]})))

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
