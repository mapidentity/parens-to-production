(ns myapp.web.ratelimit
  "Minimal in-process sliding-window-log rate limiter for abuse-prone endpoints.

  The counters live in a local atom, so this is SINGLE-INSTANCE only: a
  load-balanced, multi-instance deployment needs a shared store (Redis, a
  Datomic/SQL table) keyed the same way. It is deliberately small — enough to
  blunt mail-bombing and credential-spraying from one source — not a general
  quota system. Each key keeps the timestamps of its recent hits and prunes
  those older than the trailing window on every call, so the cap holds exactly
  for any window-length span — no fixed-window boundary burst — at the cost of
  O(hits) memory per active key.

  The KEY SET is bounded too, and that is not optional: several keys embed
  attacker-chosen input (the magic-link limiter keys per target email), so an
  attacker can mint unlimited distinct keys that will never be hit again —
  and a map that only ever assocs is a slow memory leak an abuser can drive.
  Every entry carries its own expiry (`:until`, last hit + window); when the
  map outgrows `sweep-threshold`, the next call drops every expired entry —
  at most once per `sweep-interval-ms`, so a genuinely large live set does
  not turn each request into a full scan."
  (:require
    [myapp.time :as time]))

(set! *warn-on-reflection* true)

(defonce ^:private buckets (atom {}))

(def ^:private sweep-threshold
  "Key count above which a call may sweep expired entries."
  1024)

(def ^:private sweep-interval-ms
  "Floor between sweeps — a big LIVE key set must not scan per request."
  60000)

(defn- sweep
  "Drop every entry whose window has fully elapsed (its `:until` passed)."
  [m ^long now]
  (persistent!
    (reduce-kv
      (fn [acc k v] (if (and (map? v) (< (long (:until v 0)) now)) acc (assoc! acc k v)))
      (transient {})
      m)))

(defn allow?
  "Return true (and record the hit) when `k` is under its window budget.

  `k` is allowed if it has had fewer than `limit` hits in the trailing
  `window-ms`. Timestamps outside the window are pruned on each call, and a
  key idle past its window is dropped entirely by the threshold sweep — so an
  idle key really does cost nothing to keep around, even one that is never
  queried again."
  [k ^long limit ^long window-ms]
  (let [now (.toEpochMilli (time/now))
        cutoff (- now window-ms)
        next-state
        (swap! buckets
          (fn [m]
            (let [m (if
                      (and
                        (> (count m) (long sweep-threshold))
                        (> (- now (long (::swept m 0))) (long sweep-interval-ms)))
                      (assoc (sweep m now)
                        ::swept now)
                      m)
                  hits (filterv #(> (long %) cutoff) (get-in m [k :hits] []))
                  ok? (< (count hits) limit)]
              (assoc m
                k {:hits (if ok? (conj hits now) hits)
                   :ok? ok?
                   :until (+ now window-ms)}))))]
    (get-in next-state [k :ok?])))

(defn clear!
  "Clear all counters (test support)."
  []
  (reset! buckets {}))
