(ns myapp.web.presence
  "Live viewer presence — one stateful, real-time part of an SSR app.
  The exception that proves server-rendering is not the opposite of real-time.

  An in-memory registry of who is watching which recipe, pushed to browsers
  over Server-Sent Events. It is deliberately ephemeral and single-process:
  presence is not data, it is a fleeting fact about *right now*, so it lives
  in an atom, not Datomic. A restart resets every count, which is correct —
  the counts re-form in seconds as browsers reconnect. On a second box each
  process would count its own viewers, the shared-state boundary the scaling
  audit named."
  (:require
    [clojure.data.json :as json]
    [org.httpkit.server :as hk]))

(set! *warn-on-reflection* true)

(defonce ^{:doc "A map of recipe-id (uuid) to the set of open SSE channels."} viewers (atom {}))

(defn- frame
  "One SSE `data:` line carrying the current viewer count for a recipe."
  [recipe-id]
  (str "data: " (json/write-str {:count (count (get @viewers recipe-id))}) "\n\n"))

(defn- drop-channels
  "Atomically remove `chans` from `recipe-id`'s set, cleaning an emptied entry.
  One swap, not two: removing the last channel and dropping the now-empty key
  must be a single transition, or a viewer that arrives in the gap between a
  separate remove-then-dissoc would be silently discarded with the key."
  [recipe-id chans]
  (swap! viewers
    (fn [m]
      (let [remaining (reduce disj (get m recipe-id) chans)]
        (if (empty? remaining)
          (dissoc m recipe-id)
          (assoc m
            recipe-id remaining))))))

(defn- prune!
  "Drop `dead` channels from `recipe-id`'s set, cleaning an emptied entry."
  [recipe-id dead]
  (when (seq dead) (drop-channels recipe-id dead)))

(defn- broadcast!
  "Push the current count to every open channel watching `recipe-id`.
  http-kit's `send!` returns false for a channel that has closed but whose
  on-close has not yet fired; those are pruned here, so a lagging
  disconnect self-heals on the next broadcast rather than inflating the
  count. (The same belt the dev-reload WebSocket uses.)"
  [recipe-id]
  (let [f (frame recipe-id)
        dead (reduce
               (fn [acc ch] (if (hk/send! ch f false) acc (conj acc ch)))
               []
               (get @viewers recipe-id))]
    (prune! recipe-id dead)))

(defn stream
  "SSE handler that streams the live viewer count for `recipe-id`.
  Registers this viewer, pushes the count on every arrival and departure,
  and deregisters when the connection closes.
  `send!` with `close? false` keeps the stream open; the browser's
  EventSource reconnects on its own if it drops."
  [recipe-id request]
  (hk/as-channel
    request
    {:on-open (fn [ch]
                ;; The SSE response headers ride the first send!; the channel
                ;; then streams `data:` frames until it closes.
                (hk/send!
                  ch
                  {:status 200
                   :headers {"Content-Type" "text/event-stream"
                             "Cache-Control" "no-store"
                             "X-Accel-Buffering" "no"}}
                  false)
                (swap! viewers update recipe-id (fnil conj #{}) ch)
                (broadcast! recipe-id))
     :on-close (fn [ch _status]
                 (drop-channels recipe-id [ch])
                 (broadcast! recipe-id))}))

(defn count-for
  "Current viewer count for a recipe (test/introspection)."
  [recipe-id]
  (count (get @viewers recipe-id)))
