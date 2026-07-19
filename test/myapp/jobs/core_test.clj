(ns myapp.jobs.core-test
  "The durable job queue, end to end.
  Enqueue, CAS-claim, retry-with-backoff, the failure cap, stuck-job reclaim,
  and worker lifecycle. Two test-only job kinds drive it — one that records its
  runs, one that always throws."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.jobs.core :as jobs]
    [myapp.test-helpers :as h])
  (:import
    [java.util Date]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-db)

(def ^:private runs
  "Payloads the :test/echo handler has been run with (reset per test)."
  (atom []))

(defmethod jobs/run-job :test/echo [_conn payload _kind] (swap! runs conj payload))
(defmethod jobs/run-job :test/boom [_conn _payload _kind] (throw (ex-info "boom" {})))

(defn- status
  "The current :job/status of the job with `:job/id` `id`."
  [id]
  (:job/status (jobs/job-by-id (d/db h/*conn*) id)))

(defn- eid
  "The entity id of the job with `:job/id` `id`."
  [id]
  (d/entid (d/db h/*conn*) [:job/id id]))

(deftest enqueue-runs-and-marks-done
  (reset! runs [])
  (let [id (jobs/enqueue! h/*conn* :test/echo {:n 1})]
    (is (= :pending (status id)) "enqueued jobs start pending")
    (jobs/poll-once! h/*conn*)
    (is (= :done (status id)) "a successful run marks it done")
    (is (= [{:n 1}] @runs) "the handler ran exactly once, with the payload")))

(deftest cas-claim-prevents-double-run
  (reset! runs [])
  (let [id (jobs/enqueue! h/*conn* :test/echo {:n 9})
        e (eid id)]
    (is (= :done (jobs/process-one! h/*conn* e)) "the first claim runs it")
    (is (nil? (jobs/process-one! h/*conn* e)) "a second claim is lost — the CAS refuses")
    (is (= 1 (count @runs)) "so the handler ran exactly once, never twice")))

(deftest failing-job-retries-with-backoff
  (let [id (jobs/enqueue! h/*conn* :test/boom {})]
    (jobs/poll-once! h/*conn*)
    (testing "a throw sends it back to pending with an incremented, deferred retry"
      (let [j (jobs/job-by-id (d/db h/*conn*) id)]
        (is (= :pending (:job/status j)))
        (is (= 1 (:job/attempts j)))
        (is (string? (:job/last-error j)))))
    (testing "the retry is deferred (run-after in the future) — a poll now is a no-op"
      (jobs/poll-once! h/*conn*)
      (is (= 1 (:job/attempts (jobs/job-by-id (d/db h/*conn*) id))) "backoff held it back"))))

(deftest reaches-failed-after-the-attempt-cap
  (let [id (jobs/enqueue! h/*conn* :test/boom {})]
    ;; Jump to the last allowed attempt directly (the cap is a private 5), so
    ;; this one failure crosses it — no need to time-travel the whole backoff.
    @(db/transact* h/*conn*
       [{:db/id (eid id)
         :job/attempts 4}])
    (jobs/poll-once! h/*conn*)
    (let [j (jobs/job-by-id (d/db h/*conn*) id)]
      (is (= :failed (:job/status j)) "past the cap it gives up as :failed")
      (is (string? (:job/last-error j)) "and keeps the last error for a human"))))

(deftest reclaims-a-stuck-running-job
  (reset! runs [])
  (let [id (jobs/enqueue! h/*conn* :test/echo {:n 7})]
    ;; Simulate a worker that claimed the job and then died: :running with a
    ;; claimed-at far in the past (the visibility timeout has long since passed).
    @(db/transact* h/*conn*
       [{:db/id (eid id)
         :job/status :running
         :job/claimed-at (Date. 0)}])
    (jobs/poll-once! h/*conn*)
    (is (= :done (status id)) "the stuck job was reclaimed and run")
    (is (= [{:n 7}] @runs))))

(deftest worker-lifecycle-is-idempotent
  (jobs/stop-worker!)
  (let [ratom @#'jobs/worker]
    (is (nil? @ratom) "baseline: stopped")
    (jobs/start-worker! 3600000) ; 1h period — never fires mid-test
    (let [ex1 @ratom]
      (is (some? ex1) "started")
      (jobs/start-worker! 3600000)
      (is (identical? ex1 @ratom) "starting again does not spawn a second worker"))
    (jobs/stop-worker!)
    (is (nil? @ratom) "stopped: cleared")))
