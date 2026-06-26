(ns myapp.web.ratelimit
  "Minimal in-process fixed-window rate limiter for abuse-prone endpoints.

  The counters live in a local atom, so this is SINGLE-INSTANCE only: a
  load-balanced, multi-instance deployment needs a shared store (Redis, a
  Datomic/SQL table) keyed the same way. It is deliberately small — enough to
  blunt mail-bombing and credential-spraying from one source — not a general
  quota system. Fixed windows are slightly bursty at the boundary; that is an
  acceptable trade for a coarse abuse limit."
  (:require
    [myapp.time :as time]))

(set! *warn-on-reflection* true)

(defonce ^:private buckets (atom {}))

(defn allow?
  "Return true if `k` is under `limit` hits within the trailing `window-ms`,
  recording the hit when it is. Prunes timestamps outside the window on each
  call, so a key idle for a full window costs nothing to keep around."
  [k ^long limit ^long window-ms]
  (let [now (.toEpochMilli (time/now))
        cutoff (- now window-ms)
        next-state (swap! buckets
                     (fn [m]
                       (let [hits (filterv #(> (long %) cutoff) (get-in m [k :hits] []))
                             ok? (< (count hits) limit)]
                         (assoc m
                           k {:hits (if ok? (conj hits now) hits)
                              :ok? ok?}))))]
    (get-in next-state [k :ok?])))

(defn reset!
  "Clear all counters (test support)."
  []
  (clojure.core/reset! buckets {}))
