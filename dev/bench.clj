(ns bench
  "Measure the server render path — the code this book most owns.

  Lighthouse (ch.33) audits everything the BROWSER does with our output;
  nothing there measures the path from Ring request to HTML string. This
  harness does, at handler level against the seeded in-memory database, so
  the numbers include the real work: Datomic reads, pulls, markdown,
  Hiccup stringification — and exclude the accidents (network, TLS, a
  cold JVM).

  Usage (composes the :dev classpath for config + seed):
    clojure -M:dev:measure -m bench          criterium benchmarks
    clojure -M:dev:measure -m bench flame    flamegraph -> /tmp/clj-async-profiler/results/

  Uses the in-memory storage protocol, like the tests: measured latencies
  are a FLOOR (no transactor round trips on reads anyway — the peer reads
  from its own cache either way, which is the point of measuring here)."
  (:require
    [clj-async-profiler.core :as prof]
    [clojure.java.io :as io]
    [criterium.core :as crit]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.web.handler :as handler]
    [seed]))

(defn- setup!
  "Fresh in-memory db + demo seed; returns ids the benchmarks render."
  []
  (db/create-database!)
  (seed/seed!)
  (let [dbv (d/db (db/get-connection))
        recipes (recipe/all-recipes dbv)
        ;; The most expensive recipe page: the deepest fork lineage.
        deepest (apply max-key #(count (recipe/lineage dbv (:recipe/id %))) recipes)]
    {:recipe-id (:recipe/id deepest)}))

(defn- show-request
  [id]
  {:request-method :get
   :uri (str "/recipes/" id)
   :locale :en
   :path-params {:id (str id)}})

(defn- index-request
  []
  {:request-method :get
   :uri "/recipes"
   :locale :en})

(defn -main
  [& [mode]]
  (let [{:keys [recipe-id]} (setup!)]
    (case mode
      "flame"
      (do
        (println "Profiling 2000 recipe-show renders…")
        ;; :itimer (SIGPROF sampling) rather than the default :cpu —
        ;; perf_events is typically fenced off inside containers, and for
        ;; a single-process render profile the two answer alike.
        (prof/profile
          {:event :itimer}
          (dotimes [_ 2000]
            (handler/recipe-show (show-request recipe-id))))
        (println "flamegraphs:" (mapv str (file-seq (io/file "/tmp/clj-async-profiler/results")))))
      ;; default: benchmark the two hot public renders
      (do
        (println "== recipe-show (deepest lineage) ==")
        (crit/quick-bench (handler/recipe-show (show-request recipe-id)))
        (println "\n== recipes-index (full catalog) ==")
        (crit/quick-bench (handler/recipes-index (index-request)))))
    (shutdown-agents)))
