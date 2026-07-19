(ns myapp.web.presence
  "Live viewer presence — one stateful, real-time part of an SSR app.
  The exception that proves server-rendering is not the opposite of real-time.

  An in-memory registry of who is watching which recipe, pushed to browsers
  over Server-Sent Events. It is deliberately ephemeral and single-process:
  presence is not data, it is a fleeting fact about *right now*, so it lives
  in an atom, not Datomic. A restart resets every count, which is correct —
  the counts re-form in seconds as browsers reconnect. On a second box each
  process would count its own viewers, the shared-state boundary the scaling
  audit named.

  The registry GROWS on a detectable event (a connection opens) but would,
  naively, SHRINK only on another event the peer might never send — a clean
  close. A viewer that vanishes without a TCP FIN (laptop asleep, network
  yanked, a NAT quietly dropping the flow) leaves no trace to react to, so a
  `defonce` heartbeat (`start-reaper!`) writes to every channel on a fixed
  clock: the write is what lets http-kit discover a dead peer and fire
  on-close, so eviction is tied to a signal we control, not to the client's
  good behaviour. See the chapter's state-handling section for the full why."
  (:require
    [clojure.data.json :as json]
    [org.httpkit.server :as hk])
  (:import
    [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(set! *warn-on-reflection* true)

(defonce ^{:doc "A map of recipe-id (uuid) to the set of open SSE channels."} viewers (atom {}))

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

  The count and the recipients are read from ONE `@viewers` snapshot — deref
  twice and a concurrent connect could make the announced number disagree
  with the set it was sent to. http-kit's `send!` returns false for a channel
  that has closed but whose on-close has not yet fired; those are pruned here,
  so a lagging disconnect self-heals on the next broadcast rather than
  inflating the count. (The same belt the dev-reload WebSocket uses.)"
  [recipe-id]
  (let [chans (get @viewers recipe-id)
        frame (str "data: " (json/write-str {:count (count chans)}) "\n\n")
        dead (reduce (fn [acc ch] (if (hk/send! ch frame false) acc (conj acc ch))) [] chans)]
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
                ;; The SSE response headers ride the first send!; its return
                ;; value is the earliest signal the peer is already gone —
                ;; registering a dead-on-arrival channel would mint an entry
                ;; that never gets an on-close, so only enrol on a live send.
                (when
                  (hk/send!
                    ch
                    {:status 200
                     :headers {"Content-Type" "text/event-stream"
                               "Cache-Control" "no-store"
                               "X-Accel-Buffering" "no"}}
                    false)
                  (swap! viewers update recipe-id (fnil conj #{}) ch)
                  (broadcast! recipe-id)))
     :on-close (fn [ch _status]
                 (drop-channels recipe-id [ch])
                 (broadcast! recipe-id))}))

;; ---------------------------------------------------------------------------
;; The reaper — the heartbeat that makes eviction a signal we control
;; ---------------------------------------------------------------------------

(defn sweep!
  "Heartbeat every open channel with a fresh count frame, reaping the dead.

  The reactive prune in `broadcast!` only fires when a viewer arrives or
  leaves; a client that vanishes without a TCP FIN would otherwise sit in the
  registry — and hold its socket FD — until someone else happens to touch the
  same recipe, or forever on a recipe nobody revisits. This periodic write
  forces the failed send that lets http-kit notice the dead peer and run
  on-close, so a quiet recipe's ghosts are reclaimed on a fixed clock. The
  same frame also refreshes any count that drifted and keeps idle streams warm
  through proxies. It only NARROWS the window (to roughly the TCP retransmit
  timeout, since SO_KEEPALIVE is off) — it does not close it instantly."
  []
  (doseq [recipe-id (keys @viewers)]
    (broadcast! recipe-id)))

(defonce
  ^{:private true
    :doc "The single-thread scheduler, or nil when stopped."}
  reaper
  (atom nil))

(defn- daemon-factory
  "Make a single named daemon thread for the sweep.
  A background heartbeat must never keep the JVM alive on shutdown."
  []
  (reify
    ThreadFactory
      (newThread [_ r] (doto (Thread. r "presence-reaper") (.setDaemon true)))))

(defn start-reaper!
  "Start the presence heartbeat if it is not already running (idempotent).

  Tied to the server lifecycle rather than ns-load so a dev reload cannot
  stack a second scheduler against the same registry. Each tick is wrapped so
  a single throwing send can never escape — a `ScheduledExecutorService`
  silently cancels a repeating task that throws, which would disable the whole
  safety net without a trace."
  ([] (start-reaper! 20000))
  ([period-ms]
   (swap! reaper
     (fn [ex]
       (or
         ex
         (doto
           ^ScheduledExecutorService (Executors/newSingleThreadScheduledExecutor (daemon-factory))
           (.scheduleWithFixedDelay
             ^Runnable
             (fn []
               (try
                 (sweep!)
                 (catch Throwable _ nil)))
             period-ms
             period-ms
             TimeUnit/MILLISECONDS)))))
   nil))

(defn stop-reaper!
  "Stop the heartbeat and clear the registry.

  `viewers` is `defonce`, so it survives a `stop-server!`/`start-server!`
  cycle in the same JVM — the operator's instinct that a restart clears
  presence only holds if we reset it here. Otherwise the next server instance
  inherits the dead one's ghost channels and never prunes them (it never
  broadcasts to them)."
  []
  (swap! reaper
    (fn [ex]
      (when ex (.shutdownNow ^ScheduledExecutorService ex))
      nil))
  (reset! viewers {})
  nil)

(defn count-for
  "Current viewer count for a recipe (test/introspection)."
  [recipe-id]
  (count (get @viewers recipe-id)))

(defn stats
  "Registry cardinality for observability.
  Total open channels and the number of recipes being watched. The leak the
  reaper guards against is invisible until it is fatal (OOM or FD exhaustion,
  often with a still-green health check); a gauge makes it observable first."
  []
  (let [m @viewers]
    {:channels (reduce + 0 (map count (vals m)))
     :recipes (count m)}))
