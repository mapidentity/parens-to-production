(ns myapp.core
  "Application entry point.
  Manages the HTTP server lifecycle (start, stop, restart) and initializes
  the database on startup."
  (:require
    [clojure.core.server :as repl-server]
    [myapp.analytics.db :as analytics]
    [myapp.auth.email :as email]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.jobs.core :as jobs]
    [myapp.upload.core :as upload]
    [myapp.web.assets :as assets]
    [myapp.web.presence :as presence]
    [myapp.web.routes :as routes]
    [org.httpkit.server :as http-kit])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce ^{:doc "Atom holding the HTTP-Kit stop function, or nil when stopped."} server (atom nil))

(def ^:private repl-server-name
  "myapp-repl")

(defn- start-repl-server!
  "Start a loopback-only socket REPL when MYAPP_REPL_PORT is set.

  This is the lever the runbook's live actions depend on — ban a user, inspect
  the presence registry, read config — WITHOUT a redeploy that would destroy
  the very incident state you are trying to see. The uberjar's entry point is
  this gen-class `-main`, not `clojure.main`, so the `-Dclojure.server.repl`
  property is never processed; the socket server has to be started here.

  Bound to 127.0.0.1 ONLY: a connection is unauthenticated RCE, so it must be
  unreachable from the network. An operator reaches it by SSH-tunnelling to the
  box (`ssh -L 5555:127.0.0.1:5555 box`), which already gates on SSH auth. Off
  entirely unless the port is set. See ops/RUNBOOK.md and ch.43."
  []
  (when-let [port (some-> (System/getenv "MYAPP_REPL_PORT")
                          parse-long)]
    (repl-server/start-server
      {:name repl-server-name
       :port port
       :address "127.0.0.1"
       :accept 'clojure.core.server/repl})
    (println (str "Loopback socket REPL on 127.0.0.1:" port " (SSH-tunnel to reach it)"))))

(defn- stop-repl-server!
  "Stop the loopback socket REPL if it was started (a no-op otherwise)."
  []
  (repl-server/stop-server repl-server-name))

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
    ;; The live-presence heartbeat: a background sweep that reaps SSE viewers
    ;; whose clients vanished without a clean close. Tied to the server so a
    ;; dev restart can't stack a second one (see presence/start-reaper!).
    (presence/start-reaper!)
    ;; The bounded mailer: keeps the blocking SMTP send off the request thread
    ;; so a slow relay can't starve the worker pool (see email/deliver-magic-link!).
    (email/start-mailer!)
    ;; The live-diagnosis lever: a loopback socket REPL, only if MYAPP_REPL_PORT
    ;; is set. Started BEFORE announcing ready so it is up for the first incident.
    (start-repl-server!)
    ;; The durable job worker: polls Datomic for due background jobs and runs
    ;; them, CAS-claimed so the pair deploy can't double-run one (see jobs.core).
    (jobs/start-worker!)
    ;; The upload orphan sweep: content-addressed blobs no recipe references are
    ;; collected on a daily clock (they can't be deleted on unlink — see upload.core).
    (upload/start-gc!)
    (println (str "Server running at http://" host ":" port))
    @server))

(defn stop-server!
  "Stop the web server, giving in-flight requests `drain-ms` to finish.
  http-kit stops accepting new connections immediately, then waits up to
  `drain-ms` for requests already being handled before closing their
  connections. The no-arg default suits dev restarts, where nothing of
  value is in flight."
  ([] (stop-server! 100))
  ([drain-ms]
   (when-let [stop-fn @server]
     (println "Stopping server...")
     ;; Stop the heartbeat first and clear the (defonce) presence registry, so
     ;; a restart in this same JVM doesn't inherit the dead instance's viewers.
     (presence/stop-reaper!)
     (email/stop-mailer!)
     (jobs/stop-worker!)
     (upload/stop-gc!)
     (stop-repl-server!)
     (stop-fn :timeout drain-ms)
     (reset! server nil)
     (println "Server stopped"))))

(defn- install-shutdown-hook!
  "Register a JVM shutdown hook that drains the server before exit.
  `systemctl stop` (like every container runtime's stop) delivers
  SIGTERM, which the JVM turns into an orderly shutdown-hook run. The
  one-second drain budget is generous next to the app's millisecond
  renders (see dev/bench.clj). Installed only by -main: in the REPL and
  the dev loop the server lifecycle is driven interactively, and a hook
  per reload would pile up."
  []
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread. ^Runnable (fn [] (stop-server! 1000)) "myapp-drain")))

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
  (install-shutdown-hook!)
  (start-server!))

(comment
  ;; REPL helpers
  (start-server!)
  (stop-server!)
  (restart-server!))
