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
    [myapp.web.handler :as handler]
    [myapp.web.routes :as routes]
    [org.httpkit.server :as http-kit]
    [reitit.ring :as ring]
    [ring.middleware.keyword-params :as keyword-params]
    [ring.middleware.params :as params]
    [ring.middleware.session :as session]
    [ring.middleware.session.cookie :as cookie]))

(def sent-emails
  "Atom collecting emails captured from the stubbed send-magic-link! function."
  (atom []))

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
    (if to (swap! sent-emails (fn [es] (vec (remove #(= (:to %) to) es)))) (reset! sent-emails []))
    {:status 200
     :headers {"content-type" "application/json"}
     :body "{\"cleared\":true}"}))

(def ^:private e2e-routes
  "App routes plus test-only email capture endpoints."
  (conj
    routes/routes
    ["/test/emails"
     {:get get-emails-handler
      :delete clear-emails-handler}]))

(def ^:private e2e-config
  "Deterministic config for e2e tests."
  {:server {:port 9876
            :host "127.0.0.1"}
   :database-uri "datomic:mem://myapp-e2e"
   :analytics-database-uri "datomic:mem://myapp-e2e-analytics"
   :base-url "http://localhost:9876"
   :uploads-root "target/e2e-uploads"
   :session-key h/test-session-key
   :signing-key h/test-signing-key
   :smtp {:host "localhost"
          :port 1025
          :tls false
          :user nil
          :pass nil
          :from "test@myapp.lan"}})

(defn- build-app
  "Build the Ring handler with real auth (no auto-auth).
  Includes test-only routes for email capture."
  []
  (let [session-store (cookie/cookie-store {:key (config/get-config :session-key)})]
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
      {:middleware [[params/wrap-params] [keyword-params/wrap-keyword-params]
                    [session/wrap-session
                     {:store session-store
                      ;; mirror production (:same-site :lax); :strict would drop
                      ;; the session cookie on the cross-context magic-link GET.
                      :cookie-attrs {:http-only true
                                     :same-site :lax}}]
                    [routes/wrap-locale]
                    [routes/wrap-no-cache-authenticated]
                    ;; The strict CSP is part of what production serves, so the
                    ;; e2e stack must exercise it too — otherwise the tests pass
                    ;; under a policy real users never get.
                    [routes/wrap-csp]]})))

(defn start!
  "Start the e2e test server.
  Sets deterministic config, stubs email sending, initializes fresh DB,
  and starts http-kit. Blocks indefinitely (for Playwright webServer)."
  [{:keys [port]
    :or {port 9876}}]
  (let [port (if (string? port) (parse-long port) port)]
    ;; Install deterministic config
    (alter-var-root #'config/config (constantly (delay e2e-config)))
    ;; Stub email sending — capture to atom instead of SMTP
    (alter-var-root
      #'email/send-magic-link!
      (constantly
        (fn [_locale email token base-url]
          (swap! sent-emails conj
            {:to email
             :magic-link (str base-url "/auth/verify?token=" token)})
          {:error :SUCCESS})))
    ;; Raise the per-IP magic-link budget: every Playwright worker signs in
    ;; from loopback, and with reuseExistingServer the sliding window also
    ;; spans consecutive local runs — ten sends per 15 minutes starves the
    ;; suite. The per-email limit stays: each test owns a unique address.
    (alter-var-root #'handler/ml-per-ip (constantly 10000))
    ;; Initialize fresh in-memory databases
    (db/create-database!)
    (analytics/create-database!)
    ;; Load the asset manifest so (asset ...) resolves (incl. idiomorph)
    (assets/load-manifest!)
    ;; Start server
    (http-kit/run-server
      (build-app)
      {:port port
       :ip "127.0.0.1"})
    (println (str "E2E server ready on port " port))
    @(promise)))
