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
    [clojure.java.io :as io]
    [clojure.string :as str]
    [myapp.web.presence :as presence])
  (:import
    [com.sun.management UnixOperatingSystemMXBean]
    [java.lang.management GarbageCollectorMXBean ManagementFactory MemoryUsage]))

(set! *warn-on-reflection* true)

(def ^{:doc "Short git SHA of this build (from the build-id resource), or \"dev\"."} build-id
  (delay
    (or
      (some-> (io/resource "build-id")
              slurp
              str/trim
              not-empty)
      "dev")))

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

(defonce ^{:doc "Magic-link send outcomes: {:success n :fail n}."} emails
  (atom
    {:success 0
     :fail 0}))

(defn record-email!
  "Fold one magic-link send outcome (`:success` or `:fail`) into the counter.
  Login is email, so a rising `email_send_total{outcome=\"fail\"}` is the
  earliest signal of the invisible outage the alerting chapter is built around
  — a relay that went bad while every other health signal stayed green."
  [outcome]
  (swap! emails update outcome (fnil inc 0)))

(defn- open-fd-count
  "Open file descriptors for this process, or nil off Unix.
  The platform MXBean does not always expose it. Leaked sockets (the SSE
  reaper's failure mode) climb here while the heap stays flat — the one gauge
  that catches an FD leak before the process hits its limit and everything that
  needs a socket fails at once."
  []
  (let [os (ManagementFactory/getOperatingSystemMXBean)]
    (when (instance? UnixOperatingSystemMXBean os)
      (.getOpenFileDescriptorCount ^UnixOperatingSystemMXBean os))))

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
    (emit sb (str "build_info{build_id=\"" @build-id "\"}") 1.0)
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
    (let [{:keys [success fail]} @emails]
      (emit sb "email_send_total{outcome=\"success\"}" (double success))
      (emit sb "email_send_total{outcome=\"fail\"}" (double fail)))
    ;; Presence registry cardinality + open FDs: the SSE reaper's failure mode
    ;; (leaked sockets) shows here — channels/FDs climbing while the heap is
    ;; flat — long before it becomes an FD-exhaustion outage with a green probe.
    (let [{:keys [channels recipes]} (presence/stats)]
      (emit sb "presence_channels" (double channels))
      (emit sb "presence_recipes" (double recipes)))
    (when-let [fds (open-fd-count)]
      (emit sb "process_open_fds" (double fds)))
    (when-let [dm @datomic-metrics]
      (emit-datomic sb dm))
    (str sb)))
