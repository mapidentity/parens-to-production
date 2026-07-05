(ns hot-reload
  "A namespace for hot code reloading during development."
  (:require
    [clojure.tools.logging :as log]
    [dev-reload :as dev-reload]
    [inspector-load :as inspector-load]
    [myapp.core :as core]
    [seed])
  (:import
    [java.nio.file FileSystems Files Path StandardWatchEventKinds WatchEvent]
    [java.nio.file.attribute BasicFileAttributes]
    [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture ThreadFactory TimeUnit]
    [java.util.function BiPredicate]))

(set! *warn-on-reflection* true)

(defonce ^{:doc "Holds the active file watcher state, or nil."} file-watcher (atom nil))

(defn- clj-file?
  "Returns true if the path has a .clj extension."
  [^java.nio.file.Path path]
  (.endsWith (str path) ".clj"))

(defn- asset-file?
  "Returns true if the path has a .js or .css extension."
  [^java.nio.file.Path path]
  (let [s (str path)]
    (or (.endsWith s ".js") (.endsWith s ".css"))))

(defn before-refresh
  "This hook runs before refreshing a changed file."
  []
  (log/info "Code refresh starting..."))

(defn after-refresh
  "Runs after a changed .clj file reloads: notify the browser.
  A view-ns edit is
  morphable (state-preserving <body> morph — view namespaces own the chrome as
  well as <main>); other .clj edits force a full reload.
  CSS is rebuilt out-of-band by the Tailwind --watch process, not here."
  [morphable?]
  (log/info "Code refresh completed, notifying browser..." {:morphable morphable?})
  (dev-reload/notify-reload! morphable?))

;; Tailwind's --watch rewrites static/styles.css asynchronously and can emit
;; several filesystem events per rebuild. Debounce them into ONE browser refresh,
;; fired only AFTER the writes settle — so the browser never reloads against a
;; stylesheet that is mid-rebuild (the markup-before-CSS race the decoupled
;; watcher would otherwise introduce). One daemon scheduler; the pending task is
;; cancelled and rescheduled on each event.
(defonce ^:private css-debounce-pool
  (Executors/newSingleThreadScheduledExecutor
    (reify
      ThreadFactory
        (newThread [_ r] (doto (Thread. ^Runnable r "css-reload-debounce") (.setDaemon true))))))

(defonce ^:private css-debounce-task (atom nil))

(defn- debounced-css-reload!
  "Coalesce a burst of styles.css writes into a single browser refresh ~150ms after the last write, guaranteeing the new CSS is fully on disk first."
  []
  (when-let [^ScheduledFuture t @css-debounce-task]
    (.cancel t false))
  (reset! css-debounce-task
    (.schedule
      ^ScheduledExecutorService css-debounce-pool
      ^Runnable (fn [] (dev-reload/notify-css!))
      150
      TimeUnit/MILLISECONDS)))

(defn- load-changed-file
  "Loads a changed file."
  [{:keys [event-type path]}]
  (when (= event-type :modify)
    (cond
      ;; Tailwind output: debounced, CSS-ready refresh (fires after the write).
      (.endsWith (str path) "styles.css")
      (debounced-css-reload!)

      (clj-file? path)
      (let [file-path (str path)
            start-time (System/nanoTime)]
        (log/info "File changed" {:file-path file-path})
        (try
          (before-refresh)
          ;; View namespaces go through the inspector's tools.reader load (for
          ;; element-level source metadata); everything else is a normal load. A
          ;; view-ns edit is morphable; other .clj edits force a full reload.
          (let [view? (inspector-load/reload-changed! file-path)]
            (when-not view? (load-file file-path))
            (after-refresh (boolean view?)))
          (let [duration-seconds (/ (- (System/nanoTime) start-time) 1e9)]
            (log/info "Successfully reloaded file"
              {:file-path file-path
               :duration-seconds duration-seconds}))
          (catch Exception e
            ;; Reload failed (syntax error, etc.) — the edit didn't take and we
            ;; did NOT reload the browser, so its page is now potentially stale.
            ;; Warn the browser (soft: we don't know it uses the broken file).
            (dev-reload/notify-reload-error!
              file-path
              (some-> e
                      .getMessage))
            (let [duration-seconds (/ (- (System/nanoTime) start-time) 1e9)]
              (log/error e "Error reloading file"
                {:file-path file-path
                 :duration-seconds duration-seconds})))))

      (asset-file? path)
      (do
        (log/info "Asset file changed, reloading browser" {:file-path (str path)})
        (dev-reload/reload!)))))

(defonce ^{:doc "The long-lived Tailwind --watch Process, or nil."} tailwind-watcher (atom nil))

(defn stop-tailwind-watch!
  "Stop the Tailwind --watch process, WAITING for it to actually exit before returning — so a restart can never leave two tailwinds writing styles.css at once."
  []
  (when-let [^Process p @tailwind-watcher]
    (.destroy p)
    (when-not (.waitFor p 2 TimeUnit/SECONDS) (.destroyForcibly p))
    (reset! tailwind-watcher nil)
    (log/info "Tailwind --watch stopped")))

(defonce ^:private tailwind-shutdown-hook
  ;; Destroy the external Tailwind process on JVM exit so a killed REPL never
  ;; orphans it (an orphan would keep writing styles.css behind our back).
  (delay
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (stop-tailwind-watch!)) "tailwind-shutdown"))))

(defn start-tailwind-watch!
  "Start ONE long-lived `tailwindcss --watch=always` writing static/styles.css for dev (served unhashed, no-store).
  --watch=always (not plain --watch) is required:
  plain --watch exits as soon as stdin closes, which a script/REPL launch triggers.
  Tailwind v4 also watches the @source globs (./src), so a .clj edit that adds a
  utility class rebuilds the stylesheet on its own — decoupled from code reload."
  []
  (stop-tailwind-watch!)  ; fully terminate any prior process first — no double writers
  @tailwind-shutdown-hook ; install the JVM-exit cleanup once
  (let [^"[Ljava.lang.String;" cmd (into-array
                                     String
                                     ["tailwindcss" "-i" "input.css" "-o" "static/styles.css"
                                      "--minify" "--watch=always"])
        pb (doto (ProcessBuilder. cmd) (.inheritIO))
        p (.start pb)]
    (reset! tailwind-watcher p)
    (log/info "Tailwind --watch started" {:out "static/styles.css"})
    p))

(defn stop-file-watcher
  "Stop the file watcher."
  []
  (when-let [{:keys [^java.nio.file.WatchService watch-service]} @file-watcher]
    (.close watch-service)
    (reset! file-watcher nil)
    (log/info "File watcher stopped")))

(defn start-file-watcher
  "Start file watcher using Java NIO WatchService."
  []
  (when @file-watcher (stop-file-watcher))
  (let [ws (.newWatchService (FileSystems/getDefault))
        kinds (into-array
                [StandardWatchEventKinds/ENTRY_MODIFY
                 StandardWatchEventKinds/ENTRY_CREATE])
        ;; Watch src/ (code) AND static/ (so the Tailwind output styles.css and the
        ;; source ESM under static/js trigger the right refresh).
        _ (doseq [r ["src" "static"]
                  :when (.exists (java.io.File. ^String r))
                  ^Path dir (->> (Files/find (.toPath (java.io.File. ^String r))
                                             Integer/MAX_VALUE
                                             (reify
                                               BiPredicate
                                                 (test [_ _path attrs]
                                                   (.isDirectory ^BasicFileAttributes attrs)))
                                             (make-array java.nio.file.FileVisitOption 0))
                                 .iterator
                                 iterator-seq)]
            (.register dir ws kinds))
        thread (Thread.
                 (fn []
                   (try
                     (loop []
                       (when-let [watch-key (.take ws)]
                         (doseq [^WatchEvent event (.pollEvents watch-key)]
                           (let [^Path changed (.context event)
                                 ^Path dir (.watchable watch-key)
                                 full-path (.resolve dir changed)]
                             (when
                               (Files/isDirectory full-path (make-array java.nio.file.LinkOption 0))
                               (.register full-path ws kinds))
                             (load-changed-file
                               {:event-type :modify
                                :path full-path})))
                         (.reset watch-key)
                         (recur)))
                     (catch java.nio.file.ClosedWatchServiceException _)
                     (catch Exception e (log/error e "File watcher error")))))]
    (.setDaemon thread true)
    (.start thread)
    (reset! file-watcher
      {:watch-service ws
       :thread thread})
    (log/info "File watcher started" {:watch-path "src + static"})))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn start
  "Run this from the REPL to start developing."
  []
  (core/start-dev-server)
  (log/info "Development server started" {:url "http://localhost:3000"})
  ;; Populate a fresh dev database with the demo recipe graph. No-op once seeded
  ;; (so restarts don't pile up duplicates); dev-only, since this path only runs
  ;; under the :dev REPL's (start!).
  (seed/seed-if-empty!)
  ;; One long-lived Tailwind --watch writes static/styles.css (served unhashed in
  ;; dev); CSS is no longer rebuilt per .clj save.
  (start-tailwind-watch!)
  ;; Re-load view namespaces through tools.reader so Hiccup carries element-level
  ;; source metadata from boot (the inspector overlay reads it). Dev-only.
  (inspector-load/load-all-views!)
  ;; Under the :storm alias (ClojureStorm compiler), enable the construction-view
  ;; recorder. Guarded on the storm system property so a plain :dev REPL never
  ;; tries to load flow-storm.
  (when (System/getProperty "clojure.storm.instrumentEnable")
    (try
      (when-let [setup (requiring-resolve 'trace/setup!)]
        (setup))
      (log/info "Construction-view tracer enabled (ClojureStorm)")
      (catch Throwable e (log/warn e "Trace setup failed"))))
  (start-file-watcher)
  (log/info "Development environment ready"
    {:websocket-reload true
     :file-watcher true
     :watch-path "src + static"
     :database "Datomic"}))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn reload!
  "Invoke this to send a page reload request to connected browsers."
  []
  (dev-reload/reload!))
