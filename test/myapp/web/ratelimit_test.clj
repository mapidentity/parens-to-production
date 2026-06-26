(ns myapp.web.ratelimit-test
  "Tests for the in-process fixed-window rate limiter."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [myapp.time :as time]
    [myapp.web.ratelimit :as rl]))

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
