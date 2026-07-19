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
