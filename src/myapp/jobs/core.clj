(ns myapp.jobs.core
  "A durable background-job queue whose storage IS Datomic — no broker.

  The book's spine, one more time: the database already knows. Analytics did
  not need a second store, search did not need Elasticsearch, and a job queue
  does not need Redis or SQS. A job is an ordinary durable entity; enqueuing it
  is a transaction (often the SAME transaction as the domain event that spawned
  it — see `enqueue-tx`); a background worker polls for due jobs and CLAIMS each
  with a compare-and-swap on `:job/status`, the very primitive the recipe editor
  uses for optimistic concurrency. That CAS is what makes the queue correct
  under a pair deploy: two worker processes cannot double-run one job, because
  the claim is a durable transaction, not a per-process atom — the split-brain
  that bites the in-memory rate limiter simply cannot arise here.

  Semantics are honest at-least-once: a worker can send an email and then die
  before recording success, so a reclaimed job may run twice. Handlers should
  tolerate a rare duplicate; exactly-once is a fiction this queue does not sell.

  What Datomic does NOT make free is throughput: this is a poll queue on one
  transactor, right for domain notifications, not for thousands of jobs a
  second. That is the boundary at which a real broker finally earns its place —
  named here, priced the way the scaling audit prices the second box."
  (:require
    [clojure.edn :as edn]
    [clojure.tools.logging :as log]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.time :as time])
  (:import
    [java.time Instant]
    [java.util Date]
    [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private max-attempts
  "After this many failed attempts a job is marked :failed and left for a human."
  5)

(def ^:private batch-size
  "Most jobs to claim-and-run per poll, so one busy tick can't hog the worker."
  10)

(def ^:private visibility-timeout-s
  "A :running job whose worker died is reclaimed after this long (seconds)."
  120)

(defn- now-date
  "The active clock's `now` as a `java.util.Date`.
  That is the type Datomic stores `:db.type/instant` values as, so a query can
  compare `:job/run-after` against it directly."
  ^Date []
  (Date/from (time/now)))

(defn- backoff-seconds
  "Exponential backoff for retry `n`: 60s, 120s, 240s … capped at 30 min."
  [n]
  (long (min 1800 (* 60 (long (Math/pow 2 (dec (long n))))))))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn enqueue-tx
  "Transaction data for one job — a map to drop into any `transact`.
  Return this from a domain write and put it in the SAME tx vector as the event
  it belongs to, so the event and its job commit atomically or not at all."
  ([kind payload] (enqueue-tx kind payload (time/now)))
  ([kind payload ^Instant run-after]
   {:job/id (random-uuid)
    :job/kind kind
    :job/status :pending
    :job/payload (pr-str payload)
    :job/attempts 0
    :job/run-after run-after
    :job/created-at (time/now)}))

(defn enqueue!
  "Enqueue a standalone job (its own transaction). Returns the job id."
  [conn kind payload]
  (let [tx (enqueue-tx kind payload)]
    @(db/transact* conn [tx])
    (:job/id tx)))

;; ---------------------------------------------------------------------------
;; The handler multimethod — one method per :job/kind, defined near its domain
;; ---------------------------------------------------------------------------

(defmulti run-job
  "Execute a job's side effect. Dispatches on `kind`.
  A method returns normally on success and THROWS on a retryable failure: the
  worker turns a throw into a backed-off retry and a normal return into :done."
  (fn [_conn _payload kind] kind))

(defmethod run-job :default
  [_conn _payload kind]
  (throw (ex-info "no handler registered for job kind" {:kind kind})))

;; ---------------------------------------------------------------------------
;; The worker
;; ---------------------------------------------------------------------------

(defn- cas-status!
  "CAS a job's `:job/status` from `from` to `to`, in one transaction with `extra`.
  Returns true, or false if the CAS lost (a concurrent worker or the reclaimer
  moved the status first)."
  [conn eid from to extra]
  (try
    @(db/transact* conn (into [[:db.fn/cas eid :job/status from to]] extra))
    true
    (catch Throwable e (if (db/cas-failed? e) false (throw e)))))

(defn- due-eids
  "Ids of :pending jobs whose run-after has arrived, oldest first, capped."
  [db limit]
  (->> (d/q '[:find ?e ?ra
              :in $ ?now
              :where
              [?e :job/status :pending]
              [?e :job/run-after ?ra]
              [(<= ?ra ?now)]]
            db
            (now-date))
       (sort-by second)
       (map first)
       (take limit)))

(defn- stuck-eids
  "Ids of :running jobs claimed longer ago than the visibility timeout."
  [db]
  (let [cutoff (Date/from (.minusSeconds (time/now) visibility-timeout-s))]
    (map
      first
      (d/q
        '[:find ?e
          :in $ ?cutoff
          :where
          [?e :job/status :running]
          [?e :job/claimed-at ?ca]
          [(< ?ca ?cutoff)]]
        db
        cutoff))))

(defn- fail!
  "Record a failed attempt: retry with backoff, or give up as :failed at the cap."
  [conn eid attempts ^Throwable e]
  (let [n (inc (long (or attempts 0)))
        msg (str (.getMessage e))]
    (if (>= n (long max-attempts))
      (do
        (log/error e "job failed permanently"
          {:eid eid
           :attempts n})
        (cas-status!
          conn
          eid
          :running
          :failed
          [{:db/id eid
            :job/attempts n
            :job/last-error msg}]))
      (cas-status!
        conn
        eid
        :running
        :pending
        [{:db/id eid
          :job/attempts n
          :job/run-after (.plusSeconds (time/now) (backoff-seconds n))
          :job/last-error msg}]))))

(defn process-one!
  "Claim job `eid` and run it, recording the outcome.
  Claims via CAS :pending→:running; on success marks :done; on a throw, retries
  with backoff or fails at the cap. A lost claim is a no-op (another worker got
  it). Returns :done, :retry, :failed, or nil (claim lost)."
  [conn eid]
  (let [{kind :job/kind
         payload :job/payload
         attempts :job/attempts}
        (db/pull* (d/db conn) [:job/kind :job/payload :job/attempts] eid)]
    (when
      (cas-status!
        conn
        eid
        :pending
        :running
        [{:db/id eid
          :job/claimed-at (time/now)}])
      (try
        (run-job conn (edn/read-string payload) kind)
        (cas-status! conn eid :running :done [])
        :done
        (catch Throwable e
          (fail! conn eid attempts e)
          (if (>= (inc (long (or attempts 0))) (long max-attempts)) :failed :retry))))))

(defn poll-once!
  "One worker tick: reclaim stuck jobs, then claim-and-run a batch of due ones."
  [conn]
  (doseq [eid (stuck-eids (d/db conn))]
    (cas-status! conn eid :running :pending []))
  (doseq [eid (due-eids (d/db conn) batch-size)]
    (process-one! conn eid)))

(defonce
  ^{:private true
    :doc "The worker scheduler, or nil when stopped."}
  worker
  (atom nil))

(defn start-worker!
  "Start the background job worker if not already running (idempotent).
  Tied to the server lifecycle (core.clj), like the presence reaper and mailer.
  Each tick is try-wrapped so one bad job can never cancel the schedule."
  ([] (start-worker! 2000))
  ([period-ms]
   ;; `locking`, not a side-effecting `swap!`: `swap!` may RETRY its fn
   ;; under contention, and creating the executor inside it would leak a
   ;; scheduler thread per retry. The lock makes check-then-create atomic.
   (locking worker
     (when-not @worker
       (reset! worker
         (doto
           ^ScheduledExecutorService
           (Executors/newSingleThreadScheduledExecutor
             (reify
               ThreadFactory
                 (newThread [_ r] (doto (Thread. r "job-worker") (.setDaemon true)))))
           (.scheduleWithFixedDelay
             ^Runnable
             (fn []
               (try
                 (poll-once! (db/get-connection))
                 (catch Throwable _ nil)))
             period-ms
             period-ms
             TimeUnit/MILLISECONDS)))))
   nil))

(defn stop-worker!
  "Stop the background job worker (paired with start-worker!)."
  []
  (locking worker
    (when-let [ex @worker]
      (.shutdownNow ^ScheduledExecutorService ex)
      (reset! worker nil)))
  nil)

(defn job-by-id
  "Pull a job by its `:job/id` (test/introspection)."
  [db id]
  (db/pull* db [:job/kind :job/status :job/attempts :job/last-error] [:job/id id]))

(defn stats
  "Operationally interesting job counts: pending, running, failed.
  A rising `failed` is the signal a job handler is broken — otherwise a
  background failure is invisible until someone notices the effect is missing."
  [db]
  (let [n (fn [s]
            (or
              (ffirst
                (d/q
                  '[:find (count ?e) :in $ ?s
                    :where [?e :job/status ?s]]
                  db
                  s))
              0))]
    {:pending (n :pending)
     :running (n :running)
     :failed (n :failed)}))
