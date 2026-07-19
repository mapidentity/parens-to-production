;; Failover probe: a peer that commits a tiny transaction every 500ms and
;; timestamps every result, so killing the active transactor makes the
;; write-unavailability window visible in the output.
;;
;; The peer connects to the STORAGE uri (not a transactor address): Datomic
;; resolves the active transactor's location through storage, which is exactly
;; what lets the SAME `conn` survive a failover — when the standby promotes and
;; writes its new location, the peer's reconnector follows it with no app code.
;;
;; Run (the default deps have com.datomic/peer + the postgres driver):
;;   clojure -M -e '(load-file "ops/lab/failover-probe.clj")'
;;
;; Then, from another shell, kill the active transactor and watch the OK lines
;; stop, ERR lines fill the gap, and OK lines resume through the standby. See
;; ops/lab/DRILL.md for the full procedure and a captured result.

(require '[datomic.api :as d])

(def uri "datomic:sql://myapp?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")
(def conn (d/connect uri))
(println (format "%d START basis-t=%d" (System/currentTimeMillis) (d/basis-t (d/db conn))))

(def deadline (+ (System/currentTimeMillis) 120000))
(loop [n 0]
  (when (< (System/currentTimeMillis) deadline)
    (let [t (System/currentTimeMillis)
          line (try
                 (let [r (deref (d/transact conn [{:db/doc (str "probe-" n)}]) 15000 ::timeout)]
                   (if (= r ::timeout)
                     (format "%d TXTIMEOUT tx=%d" t n)
                     (format "%d OK tx=%d basis-t=%d" t n (d/basis-t (:db-after r)))))
                 (catch Throwable e
                   (format "%d ERR tx=%d %s" t n (.getName (class e)))))]
      (println line)
      (flush)
      (Thread/sleep 500)
      (recur (inc n)))))

(println (format "%d DONE" (System/currentTimeMillis)))
(System/exit 0)
