(ns myapp.web.ratelimit-test
  "Tests for the in-process fixed-window rate limiter."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [myapp.time :as time]
    [myapp.web.ratelimit :as rl]))

(set! *warn-on-reflection* true)

(use-fixtures
  :each
  (fn [f]
    (rl/clear!)
    (f)
    (rl/clear!)))

(deftest allows-up-to-the-limit-then-blocks
  (testing "the first `limit` hits pass, the next is rejected"
    (is (true? (rl/allow? "k" 3 60000)))
    (is (true? (rl/allow? "k" 3 60000)))
    (is (true? (rl/allow? "k" 3 60000)))
    (is (false? (rl/allow? "k" 3 60000)) "fourth hit in the window is blocked")))

(deftest keys-are-independent
  (is (true? (rl/allow? "a" 1 60000)))
  (is (false? (rl/allow? "a" 1 60000)))
  (is (true? (rl/allow? "b" 1 60000)) "a different key has its own budget"))

(deftest window-slides
  (testing "hits outside the trailing window are pruned, freeing budget"
    ;; t0: fill the budget
    (time/with-clock
      (time/fixed-clock (java.time.Instant/ofEpochMilli 0))
      (is (true? (rl/allow? "k" 1 1000)))
      (is (false? (rl/allow? "k" 1 1000))))
    ;; t0 + 2s: the old hit is outside the 1s window, so budget is back
    (time/with-clock
      (time/fixed-clock (java.time.Instant/ofEpochMilli 2000))
      (is (true? (rl/allow? "k" 1 1000))))))

(deftest idle-keys-are-swept
  ;; Attacker-chosen keys (per-email limiters) can be minted without bound
  ;; and never queried again. Once the map outgrows the sweep threshold,
  ;; the next call must drop every entry whose window has fully elapsed —
  ;; the leak is bounded by (threshold + live keys), not by attacker zeal.
  (rl/clear!)
  (let [^java.time.Instant start (java.time.Instant/parse "2026-01-01T00:00:00Z")]
    (time/with-clock
      (time/fixed-clock start)
      (dotimes [i 1100]
        (rl/allow? (str "minted:" i) 3 60000)))
    ;; Two minutes later: every minted key's window has elapsed.
    (time/with-clock
      (time/fixed-clock (.plusSeconds start 120))
      (rl/allow? "fresh" 3 60000)
      (let [ks (keys @@#'rl/buckets)]
        (is
          (< (count ks) 10)
          "the swept map holds only the fresh key (plus bookkeeping), not 1100 minted ones")
        (is (some #{"fresh"} ks))))))
