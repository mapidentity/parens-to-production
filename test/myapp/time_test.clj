(ns myapp.time-test
  "Tests for the time wrapper.

  These tests are necessarily allowed to call `Instant/now` directly —
  they're verifying that the wrapper's wallclock default actually
  tracks wallclock. The grep in `./lint` exempts this file by name."
  (:require
    [clojure.test :refer [deftest is testing]]
    [myapp.time :as time])
  (:import
    [java.time Clock Duration Instant LocalDate ZoneId ZoneOffset]))

(set! *warn-on-reflection* true)

(deftest defaults-to-wallclock-test
  (testing "now returns an Instant close to wallclock when no clock is pinned"
    (let [t1 (time/now)
          wallclock (Instant/now)]
      (is (instance? Instant t1))
      (is
        (< (Math/abs (- (.toEpochMilli t1) (.toEpochMilli wallclock))) 1000)
        "Wrapper agrees with wallclock to within a second"))))

(deftest with-clock-pins-time-test
  (testing "with-clock pins now and today to the fixture"
    (let [pinned (Instant/parse "2026-04-15T10:30:00Z")]
      (time/with-clock
        (time/fixed-clock pinned)
        (is (= pinned (time/now)))
        ;; 2026-04-15 10:30 UTC is 12:30 in Amsterdam (CEST), still 2026-04-15.
        (is (= (LocalDate/of 2026 4 15) (time/today))))))
  (testing "with-clock crosses midnight correctly per Amsterdam zone"
    ;; 2026-12-31 23:30 UTC is 2027-01-01 00:30 in Amsterdam (CET).
    (let [pinned (Instant/parse "2026-12-31T23:30:00Z")]
      (time/with-clock
        (time/fixed-clock pinned)
        (is (= (LocalDate/of 2027 1 1) (time/today)))
        (is
          (= (LocalDate/of 2026 12 31) (time/today-in ZoneOffset/UTC))
          "today-in respects the requested zone")))))

(deftest with-clock-restores-on-exception-test
  (testing "with-clock restores the previous clock even if body throws"
    (let [before (time/current-clock)
          pinned (time/fixed-clock (Instant/parse "2026-04-15T10:30:00Z"))]
      (try
        (time/with-clock pinned (is (= pinned (time/current-clock))) (throw (ex-info "boom" {})))
        (catch clojure.lang.ExceptionInfo _ nil))
      (is (= before (time/current-clock)) "Clock restored after exception in body"))))

(deftest with-clock-nests-test
  (testing "with-clock nests and restores the immediate outer value"
    (let [outer (time/fixed-clock (Instant/parse "2025-01-01T00:00:00Z"))
          inner (time/fixed-clock (Instant/parse "2030-01-01T00:00:00Z"))]
      (time/with-clock
        outer
        (is (= outer (time/current-clock)))
        (time/with-clock inner (is (= inner (time/current-clock))))
        (is
          (= outer (time/current-clock))
          "Inner exit restored outer, not the wallclock default")))))

(deftest fixed-clock-at-midnight-test
  (testing "fixed-clock-at pins to midnight Amsterdam on the given date"
    (time/with-clock
      (time/fixed-clock-at (LocalDate/of 2026 5 3))
      (is (= (LocalDate/of 2026 5 3) (time/today)))
      ;; The Instant should be midnight Amsterdam. In May Amsterdam is
      ;; CEST (UTC+2), so midnight there is 22:00 UTC the previous day.
      (is (= (Instant/parse "2026-05-02T22:00:00Z") (time/now))))))

(deftest now-amsterdam-zone-test
  (testing "now-amsterdam returns ZonedDateTime in Europe/Amsterdam"
    (time/with-clock
      (time/fixed-clock (Instant/parse "2026-04-15T10:30:00Z"))
      (let [zdt (time/now-amsterdam)]
        (is (= (ZoneId/of "Europe/Amsterdam") (.getZone zdt)))
        (is (= 12 (.getHour zdt)) "10:30 UTC reads as 12:30 Amsterdam in CEST")))))

(deftest set-clock-mutates-test
  (testing "set-clock! changes the global clock until reset"
    (let [before (time/current-clock)
          fixture (time/fixed-clock (Instant/parse "2050-01-01T00:00:00Z"))]
      (try
        (time/set-clock! fixture)
        (is (= fixture (time/current-clock)))
        (is (= (Instant/parse "2050-01-01T00:00:00Z") (time/now)))
        (finally (time/set-clock! before))))))

(deftest now-uses-clock-not-wallclock-test
  (testing "now reads from current-clock, never from wallclock directly"
    ;; Pin to an instant in the year 2099 — if any call short-circuits
    ;; to wallclock, this test would fail (we are clearly not in 2099).
    (let [t (Instant/parse "2099-06-01T12:00:00Z")
          ticking (Clock/tick (time/fixed-clock t) (Duration/ofSeconds 1))]
      (time/with-clock ticking (is (= 2099 (.getYear (time/today))))))))
