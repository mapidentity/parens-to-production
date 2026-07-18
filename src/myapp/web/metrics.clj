(ns myapp.web.metrics
  "Runtime metrics: a hand-rolled Prometheus text endpoint.

  The exposition format is a few lines of text (`name{label=\"v\"} 1.0`);
  what deserves a dependency is a metrics *system*, and the operations
  chapters argue this box does not need one. Three sources feed the
  endpoint: the JVM's own MXBeans, the request counters folded in by
  `wrap-metrics`, and the Datomic peer's periodic metrics report, which
  the peer delivers to `datomic-callback!` when the production JVM flag
  `-Ddatomic.metricsCallback=myapp.web.metrics/datomic-callback!` names
  it (see ops/myapp.service) — a plain dev REPL never wires it."
  (:require
    [clojure.string :as str])
  (:import
    [java.lang.management GarbageCollectorMXBean ManagementFactory MemoryUsage]))

(set! *warn-on-reflection* true)

(defonce ^{:doc "Latest metrics map from the peer's ~1/min callback, or nil."} datomic-metrics
  (atom nil))

(defn datomic-callback!
  "Receive the peer's periodic metrics report (named in the prod JVM flags)."
  [m]
  (reset! datomic-metrics m))

(defonce ^{:doc "Per-status-class request counters: {\"2xx\" {:count n :nanos total}}."} requests
  (atom {}))

(defn record-request!
  "Fold one served request into the counters: status class, duration."
  [status ^long nanos]
  (let [status-class (str (quot (long (or status 0)) 100) "xx")]
    (swap! requests update
      status-class
      (fn [m]
        {:count (inc (long (:count m 0)))
         :nanos (+ (long (:nanos m 0)) nanos)}))))

(defn- sanitize
  "Metric-name-safe form of a camelCased metric key."
  [k]
  (-> (name k)
      (str/replace #"([a-z0-9])([A-Z])" "$1_$2")
      (str/lower-case)
      (str/replace #"[^a-z0-9_]" "_")))

(defn- emit
  "Append one exposition line: `metric-name value`."
  [^StringBuilder sb ^String metric-name ^double v]
  (.append sb metric-name)
  (.append sb " ")
  (.append sb (str v))
  (.append sb "\n"))

(defn- emit-datomic
  "Emit the peer's metrics report, generically.
  Numbers become gauges; the peer's {:lo :hi :sum :count} rollups become
  four samples each. Generic on purpose — the report's keys vary by
  activity, and an operator wants whatever the peer said, not our
  curation of it."
  [^StringBuilder sb m]
  (doseq [[k v] m
          :when (not= k :event)]
    (cond
      (number? v) (emit sb (str "datomic_" (sanitize k)) (double v))
      (map? v) (doseq [[stat sv] v
                       :when (number? sv)]
                 (emit sb (str "datomic_" (sanitize k) "_" (name stat)) (double sv))))))

(defn render
  "The Prometheus text exposition of everything the process knows about itself."
  []
  (let [heap ^MemoryUsage (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))
        sb (StringBuilder.)]
    (emit sb "jvm_memory_heap_used_bytes" (double (.getUsed heap)))
    (emit sb "jvm_memory_heap_max_bytes" (double (.getMax heap)))
    (emit sb "jvm_threads" (double (.getThreadCount (ManagementFactory/getThreadMXBean))))
    (emit
      sb
      "process_uptime_seconds"
      (/ (double (.getUptime (ManagementFactory/getRuntimeMXBean))) 1000.0))
    (doseq [^GarbageCollectorMXBean gc (ManagementFactory/getGarbageCollectorMXBeans)]
      (let [gc-label (str "{gc=\"" (sanitize (.getName gc)) "\"}")]
        (emit sb (str "jvm_gc_collections_total" gc-label) (double (.getCollectionCount gc)))
        (emit
          sb
          (str "jvm_gc_seconds_total" gc-label)
          (/ (double (.getCollectionTime gc)) 1000.0))))
    (doseq [[status-class
             {n :count
              nanos :nanos}]
            (sort-by key @requests)]
      (let [status-label (str "{class=\"" status-class "\"}")]
        (emit sb (str "http_requests_total" status-label) (double n))
        (emit sb (str "http_request_duration_seconds_sum" status-label) (/ (double nanos) 1e9))))
    (when-let [dm @datomic-metrics]
      (emit-datomic sb dm))
    (str sb)))
