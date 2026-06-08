(ns myapp.core
  "Application entry point.
  Manages the HTTP server lifecycle (start, stop, restart) and initializes
  the database on startup."
  (:require
    [myapp.analytics.db :as analytics]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.web.assets :as assets]
    [myapp.web.routes :as routes]
    [org.httpkit.server :as http-kit])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce ^{:doc "Atom holding the HTTP-Kit stop function, or nil when stopped."} server (atom nil))

(defn start-server!
  "Start the web server."
  []
  (let [port (config/get-config :server :port)
        host (config/get-config :server :host)]
    ;; Load the asset manifest (logical name -> served URL)
    (assets/load-manifest!)
    ;; Initialize databases
    (println "Initializing database...")
    (db/create-database!)
    (println "Initializing analytics database...")
    (analytics/create-database!)
    ;; Start HTTP server
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

(defn start-dev-server
  "Start the server in development mode.
   Sets the myapp.dev system property, inits DB, starts HTTP-Kit on port 3000."
  []
  (System/setProperty "myapp.dev" "true")
  (start-server!))

(defn -main
  "Application entry point."
  [& _args]
  (start-server!))

(comment
  ;; REPL helpers
  (start-server!)
  (stop-server!)
  (restart-server!))
