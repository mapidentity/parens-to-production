(ns myapp.web.presence-test
  "The viewer-presence registry, driven by fake channels.
  Register on open, broadcast the count on every arrival/departure,
  deregister on close. The SSE wire itself is
  http-kit's as-channel — the same mechanism the dev-reload WebSocket
  already exercises — so here we drive the on-open/on-close callbacks with
  fake channels and assert the counts they broadcast."
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer [deftest is testing]]
    [myapp.web.presence :as presence]
    [org.httpkit.server :as hk]))

(set! *warn-on-reflection* true)

(defn- last-count
  "The count in the most recent data: frame sent to `ch` (from `sent`)."
  [sent ch]
  (->> @sent
       (filter (fn [[c data]] (and (= c ch) (string? data))))
       last
       second
       (re-find #"\{.*\}")
       (#(json/read-str % :key-fn keyword))
       :count))

(deftest presence-registry-broadcasts-counts
  (reset! presence/viewers {})
  (let [rid (random-uuid)
        sent (atom [])
        ;; Capture the callbacks as-channel would install, and record sends.
        open-cbs (atom [])
        close-cbs (atom [])]
    (with-redefs [hk/as-channel (fn [_req {:keys [on-open on-close]}]
                                  (swap! open-cbs conj on-open)
                                  (swap! close-cbs conj on-close)
                                  {:mock-channel true})
                  hk/send! (fn [ch data _close?]
                             (swap! sent conj [ch data])
                             true)]
      (let [chA (Object.)
            chB (Object.)]
        ;; Viewer A connects.
        (presence/stream rid {})
        ((last @open-cbs) chA)
        (testing "A alone is counted as 1" (is (= 1 (last-count sent chA))))
        ;; Viewer B connects — both should now see 2.
        (presence/stream rid {})
        ((last @open-cbs) chB)
        (testing "B's arrival broadcasts 2 to both A and B"
          (is (= 2 (last-count sent chA)) "A was notified of B")
          (is (= 2 (last-count sent chB))))
        (testing "the registry holds both" (is (= 2 (presence/count-for rid))))
        ;; Viewer A leaves — B should drop to 1.
        ((first @close-cbs) chA :normal)
        (testing "A's departure broadcasts 1 to the remaining B"
          (is (= 1 (last-count sent chB)))
          (is (= 1 (presence/count-for rid))))
        ;; B leaves — the recipe key is cleaned up.
        ((second @close-cbs) chB :normal)
        (testing "the last departure cleans the registry entry"
          (is (zero? (presence/count-for rid)))
          (is (not (contains? @presence/viewers rid))))))))

(deftest broadcast-prunes-a-dead-channel
  ;; http-kit reports a closed-but-not-yet-noticed connection by returning
  ;; false from send!. Such a channel must be pruned on the next broadcast,
  ;; so a lagging disconnect self-heals rather than inflating the count.
  (reset! presence/viewers {})
  (let [rid (random-uuid)
        open-cbs (atom [])
        ;; send! returns false only for channels marked dead.
        dead (atom #{})]
    (with-redefs [hk/as-channel (fn [_req {:keys [on-open]}]
                                  (swap! open-cbs conj on-open)
                                  {:mock-channel true})
                  hk/send! (fn [ch _data _close?] (not (contains? @dead ch)))]
      (let [chA (Object.)
            chB (Object.)
            chC (Object.)
            connect (fn [ch]
                      (presence/stream rid {})
                      ((last @open-cbs) ch))]
        (connect chA)
        (connect chB)
        (is (= 2 (presence/count-for rid)) "A and B are registered")
        ;; A's socket has quietly closed; http-kit will now report send! false.
        (swap! dead conj chA)
        ;; C connecting triggers a broadcast, which discovers and prunes A.
        (connect chC)
        (testing "the dead channel is pruned on the next broadcast"
          (is (= 2 (presence/count-for rid)) "B and C remain; A is dropped")
          (is (not (contains? (get @presence/viewers rid) chA)) "A is gone from the set")
          (is (contains? (get @presence/viewers rid) chC) "C is present"))))))

(deftest sweep-reaps-quiet-recipe-ghosts
  ;; The reactive prune only runs on a connect/disconnect. A client that
  ;; vanishes with no TCP FIN leaves a ghost on a recipe nobody else touches,
  ;; so no broadcast — and therefore no prune — ever fires for it. The
  ;; heartbeat sweep is the only thing that reaps it.
  (reset! presence/viewers {})
  (let [r1 (random-uuid)
        r2 (random-uuid)
        dead (atom #{})
        open-cbs (atom [])]
    (with-redefs [hk/as-channel (fn [_req {:keys [on-open]}]
                                  (swap! open-cbs conj on-open)
                                  {:mock-channel true})
                  hk/send! (fn [ch _data _close?] (not (contains? @dead ch)))]
      (let [ghost (Object.)
            live (Object.)
            connect (fn [rid ch]
                      (presence/stream rid {})
                      ((last @open-cbs) ch))]
        (connect r1 ghost)
        (connect r2 live)
        (is (= 1 (presence/count-for r1)) "r1 has its lone viewer")
        (is (= 1 (presence/count-for r2)) "r2 has its viewer")
        ;; r1's client vanishes with no FIN — its on-close never fires, and
        ;; nobody else ever touches r1 to trigger a reactive prune.
        (swap! dead conj ghost)
        (presence/sweep!)
        (testing "the sweep reaps the ghost and drops its emptied recipe key"
          (is (zero? (presence/count-for r1)))
          (is (not (contains? @presence/viewers r1))))
        (testing "a live viewer on an untouched recipe survives the sweep"
          (is (= 1 (presence/count-for r2))))))))

(deftest dead-on-arrival-channel-is-not-registered
  ;; If the peer is already gone by on-open, the header send! fails; enrolling
  ;; that channel would mint an entry that never gets an on-close.
  (reset! presence/viewers {})
  (let [rid (random-uuid)
        open-cbs (atom [])]
    (with-redefs [hk/as-channel (fn [_req {:keys [on-open]}]
                                  (swap! open-cbs conj on-open)
                                  {:mock-channel true})
                  hk/send! (fn [_ch _data _close?] false)]
      (presence/stream rid {})
      ((last @open-cbs) (Object.))
      (testing "a channel whose first send fails is never enrolled"
        (is (zero? (presence/count-for rid)))
        (is (not (contains? @presence/viewers rid)))))))

(deftest reaper-lifecycle-is-idempotent-and-clears-registry
  ;; The cure is itself stateful: start/stop must pair with the server,
  ;; starting twice must not stack a second scheduler, and stopping must reset
  ;; the defonce registry so a restart in the same JVM starts clean.
  (let [ratom @#'presence/reaper] ; the private atom holding the scheduler
    (presence/stop-reaper!)
    (is (nil? @ratom) "baseline: stopped, no scheduler")
    ;; A 1-hour period so the task never actually fires mid-test.
    (presence/start-reaper! 3600000)
    (let [ex1 @ratom]
      (is (some? ex1) "started: a scheduler exists")
      (presence/start-reaper! 3600000)
      (is (identical? ex1 @ratom) "starting again is a no-op, not a second scheduler"))
    (reset! presence/viewers {:leftover #{(Object.)}})
    (presence/stop-reaper!)
    (is (nil? @ratom) "stopped: scheduler shut down and cleared")
    (is (empty? @presence/viewers) "stop resets the defonce registry")))
