# N People Are Looking: Real-Time Presence Without Leaving SSR

There is a belief this book has spent forty-four chapters implicitly arguing against, and it deserves one chapter arguing against it explicitly: that *server-rendered* means *not real-time*. That choosing the server, as [chapter 2](02-positioning.md) did, means giving up the live, pushed, stateful experiences the single-page world treats as its home turf. It does not. The whole-page render and the live feed are not opposites; they are layers, and this chapter builds the second one (a small, honest, real-time feature) on top of everything already here.

The feature is modest by design; the *pattern* is what matters here, not the ambition: a live viewer count on a public recipe. "👀 3 people looking now," updating the instant someone arrives or leaves. It is the kind of thing everyone assumes requires a client framework and a websocket mesh, and it turns out to be a server-sent-events island and an atom.

## Presence is not data

The first design decision is where the state lives, and it is the opposite of every other decision in this book. Recipes, versions, forks, proposals -- all of it is [durable, immutable, in Datomic](08-datomic.md), because it is *data*: facts that happened and stay true. Presence is not that. Who is looking at a recipe *right now* is a fact with a lifespan of seconds, true only in the present tense, meaningless the instant it changes and worthless after a restart. Putting it in Datomic would be a category error: you would be writing history for something that has none, and slamming [the single transactor](41-beyond-one-box.md) with a write every time a browser tab opened or closed.

So presence lives in an atom, in memory, which is the correct home for ephemeral state:

```clojure
(defonce viewers (atom {}))   ; recipe-id -> #{open SSE channel}
```

A restart resets every count to zero, and that is *fine*: the counts re-form within seconds as browsers reconnect, because the truth they represent is continuously re-asserted by reality. The scaling caveat is the one [the audit already drew](41-beyond-one-box.md) (on a second box each process would count only its own viewers), but for the single box this whole book targets, one atom is right, and reaching for Redis here would be [buying infrastructure the domain does not demand](25-auth-email-flow.md).

## The push: Server-Sent Events, not WebSockets

To tell a browser the count changed, the server has to *push*. The instinct is a WebSocket, but a WebSocket is a bidirectional channel, and this is a one-way trickle: the server talks, the browser only listens. **Server-Sent Events** are that shape -- a long-lived HTTP response the server streams `data:` lines down, with the browser's `EventSource` reconnecting on its own if it drops. Less machinery than a WebSocket, and it rides ordinary HTTP, so it passes through [the reverse proxy](35-going-live.md) and [the strict CSP](29-asset-pipeline.md) without special handling.

The server side is [the same `http-kit` async channel the dev-reload loop has used since chapter 6](06-live-reload.md), pointed at a different job:

```clojure
(defn stream
  [recipe-id request]
  (hk/as-channel request
    {:on-open (fn [ch]
                ;; The header send's return value is the earliest signal the
                ;; peer is already gone — only enrol a channel that took it.
                (when (hk/send! ch {:status 200
                                    :headers {"Content-Type" "text/event-stream" ,,,}}
                                false)
                  (swap! viewers update recipe-id (fnil conj #{}) ch)
                  (broadcast! recipe-id)))
     :on-close (fn [ch _status]
                 (drop-channels recipe-id [ch])   ; remove + clean the key in ONE swap
                 (broadcast! recipe-id))}))
```

The whole feature is in the two callbacks. On open: send the `text/event-stream` headers (the `false` on `send!` means *keep the channel open*), and (only if that send took, so a dead-on-arrival connection is never enrolled) add this channel to the recipe's set and broadcast the new count. On close: remove it and, in the *same* swap, drop the entry if it was the last one out (two swaps with a gap between them could discard a viewer who arrived in that gap), then broadcast the lower count. `broadcast!` walks the set and pushes one `data: {"count": N}` frame to each, and prunes any channel whose `send!` returns false, because http-kit reports a closed-but-not-yet-noticed connection that way, so a lagging disconnect self-heals on the next broadcast. That prune is [the same belt the dev-reload channel uses](06-live-reload.md); the pattern was already in the codebase, waiting. What it does *not* cover -- a viewer that dies with no close event at all -- is [its own section below](#state-handling-and-the-leaks-you-design-against).

## The island: enhancement, never dependency

The client side is one of [the progressive-enhancement islands](20-progressive-enhancement.md), and it obeys the same rule as all of them: it is pure garnish. The recipe page server-renders an *empty, hidden* element; without JavaScript it stays empty and the page is complete without it.

```javascript
document.querySelectorAll('[data-viewers-url]').forEach((el) => {
  const source = new EventSource(el.dataset.viewersUrl);
  source.onmessage = (e) => {
    const { count } = JSON.parse(e.data);
    const others = count - 1;                 // the count includes this browser
    el.textContent = others > 0 ? `👀 ${count} people looking now` : '';
    el.hidden = others <= 0;
  };
  window.addEventListener('pagehide', () => source.close());
  ,,,   // a try/catch around the parse and a no-op onerror; the shipped
        // static/js/viewers.js is a morph-aware controller, not this loop
});
```

Two small honesties in that handler. The count includes *you*, so it renders only when `others > 0`. A solo reader sees nothing, not the slightly sad "1 person looking." And it closes the stream on `pagehide`, so the server's `on-close` fires promptly when you navigate away and the count everyone else sees drops without waiting for a timeout.

And the piece that ties it back to the book's spine: this needed **no change to the Content-Security-Policy.** [The strict CSP](29-asset-pipeline.md) already carries `connect-src 'self'`, because `connect-src` is the directive that governs `EventSource`, `fetch`, and WebSockets alike -- and it was set to `'self'` from the start. The policy that was written to lock everything down had already, correctly, allowed the app to talk to itself. The real-time feature slid in under a security posture designed months of chapters ago, with nothing loosened.

## Proof

Driven against the running server, two `EventSource` clients on one recipe: the first sees the count, and when the second connects, the first's stream immediately pushes the higher number: the real-time path works end to end, through the whole middleware stack, `text/event-stream` and all. The registry's arithmetic -- increment on arrival, decrement on departure, clean up the last one out, prune a stale channel -- is pinned by a unit test driving the open/close callbacks with fake channels, because that logic is where a leak or a wrong count would hide, and it should not depend on a live socket to catch. The reaper gets the same treatment, split across two tests. One drives `sweep!` directly against a recipe whose lone channel always fails its `send!`, and asserts the sweep reaps the ghost and empties the key: the leak fix, proven to reap. The other starts a real scheduler and pins the lifecycle around it -- a second start stacks no second thread, and stop clears the `defonce` registry. What is reasoned rather than drilled is the timer firing on its own clock: the sweep logic runs under the direct call and the scheduler that repeats it under the lifecycle test (with a one-hour period so it never fires mid-test), but no test waits out a live tick.

## State handling, and the leaks you design against

Every other feature in this book hands its state to Datomic, which is *built* to hold it. Presence keeps its own state in a bare atom -- and the moment you own a mutable registry of live connections, you have signed up for a discipline that is easy to get wrong in ways that stay invisible until they are fatal. Three pitfalls, each one this feature actually hit and had to answer, and each one general to any in-memory registry you will ever keep.

**A registry that grows on one event and shrinks on another will leak -- unless the shrink is a signal you control.** The set grows when a browser *connects*, an event the server always sees. Naively it would shrink only when the browser *cleanly closes*, but that is an event the peer might never send. A laptop lid closes, a phone leaves Wi-Fi, a NAT silently forgets the flow: no `FIN` reaches the server, http-kit's selector never gets a read-ready event, and `on-close` never fires. The channel (and its socket file descriptor) would sit in the atom forever, because nothing ever writes to it to discover it is dead. On a recipe nobody else visits, no arrival or departure fires a `broadcast!` either, so even the reactive `send!`-returns-false prune never runs. The *count* self-heals on the next visitor; the *memory* does not.

The answer is to stop waiting on the client's good manners and put eviction on a clock you own -- a heartbeat:

```clojure
(defn sweep! []
  (doseq [recipe-id (keys @viewers)]
    (broadcast! recipe-id)))          ; a write to every channel, everywhere
```

Every twenty seconds the reaper writes a fresh count frame to every open channel. The write *is* the point: it forces http-kit to try the dead socket, fail, and run the `on-close` that reclaims the slot and the FD. It also refreshes any count that drifted and keeps idle streams warm through proxies that would otherwise time them out. But be honest about what it buys -- with `SO_KEEPALIVE` off (http-kit's `run-server` never sets it), a write to a black-holed socket does not fail *instantly*; it lands in the kernel send buffer and succeeds until TCP exhausts its retransmissions, on the order of minutes. The heartbeat turns *leaks forever* into *leaks for a few minutes*. That is the ceiling of a userspace fix, and here it is enough.

**`send!` returning true means "enqueued," not "delivered" -- it is neither backpressure nor a health check.** The self-heal rests on `send!` returning *false* for a dead channel, but false appears only once http-kit already knows the socket is closed. For a reader that is merely *slow* (a throttled tab, or an attacker who opens a stream to a busy recipe and then stops reading) `send!` returns *true* while http-kit buffers the undrained frames in memory without bound. Nothing looks dead, so nothing prunes. A heartbeat cannot fix this (writing *more* only buffers *more*); its correct home is a write-timeout and a per-IP connection cap at the reverse proxy, [where connection limits belong](35-going-live.md) -- but the shipped Caddyfile sets neither, because a blanket write-timeout would sever the legitimate long-lived SSE stream along with the abusive one; the undrained-consumer defense is named here, not built. Two disciplines follow for the code itself: treat every async `send!` as fire-and-forget, and derive one broadcast from *one* `@viewers` snapshot. Reading the count from one deref and the recipient set from another announces a number to a set it was never measured against.

**The cure is itself stateful -- so lifecycle-manage it, or it out-leaks the disease.** A background reaper is a thread, a schedule, and a `defonce` atom, and each is its own way to leak:

```clojure
(defn start-reaper!
  ([] (start-reaper! 20000))                   ; the default heartbeat period
  ([period-ms]
   ;; `locking`, not a side-effecting `swap!`: building the executor is a side
   ;; effect, and a `swap!` that RETRIES under contention would leak a second
   ;; scheduler. The lock makes check-then-create one atomic step, no retry.
   (locking reaper
     (when-not @reaper                         ; idempotent, never a second one
       (reset! reaper
         (doto
           ^ScheduledExecutorService (Executors/newSingleThreadScheduledExecutor (daemon-factory))
           (.scheduleWithFixedDelay
             ^Runnable (fn [] (try (sweep!) (catch Throwable _ nil)))  ; a throw must not escape
             period-ms period-ms TimeUnit/MILLISECONDS)))))
   nil))
```

Four things that code is carrying, none of them obvious. It is **idempotent** (`locking` + `when-not @reaper`), so a dev reload that re-evaluates the namespace cannot spawn a second scheduler racing the first against the same atom. Its thread is a **daemon**, so a background heartbeat never keeps the JVM alive at shutdown. Every tick is **wrapped in `try`**, because a `ScheduledExecutorService` *silently cancels a repeating task that throws*. One pathological channel would otherwise disable the whole safety net with no log line. And it is **tied to the server lifecycle**, started in `start-server!` and stopped in `stop-server!` -- where `stop` also does `(reset! viewers {})`, because the registry is `defonce` and would otherwise carry a dead instance's ghost channels into the next server in the same JVM. The operator's instinct that "restarting clears presence" is only true if `stop` actually clears it.

Getting this wrong fails in a nastier way than a slow memory climb. Leaked sockets exhaust the process's file-descriptor limit while the heap stays healthy and the liveness probe stays green -- so the orchestrator never restarts the box, and a leak in one public SSE endpoint quietly starves every *other* feature that needs to open a file or a socket. That is also why the endpoint refuses to stream for a recipe that does not exist: without that check a made-up UUID would mint a permanent registry key, an attacker-controlled leak of map cardinality orthogonal to the connection count. Owning your state means owning its failure modes too.

## Trade-offs & limitations, in one place

- **Single-process, like the rate limiter.** The atom counts one box's viewers. [The scaling audit](41-beyond-one-box.md) already priced the shared-state fix; on one box there is nothing to fix. A pair deploy briefly shows two independent counts, which for a viewer garnish is beneath notice.
- **A held connection per viewer.** SSE keeps a socket open per watcher; http-kit carries many cheaply, and the [heartbeat above](#state-handling-and-the-leaks-you-design-against) reaps the ones whose clients vanished -- but an unbounded public endpoint that holds connections is still a denial-of-service surface, and [connection limits belong at the proxy](35-going-live.md), not in this handler. Named, not defended.
- **Counts, not identities.** It says *how many*, never *who* -- deliberately, because "who is reading this recipe" is a privacy question a public counter must not answer. Presence-with-identity (for the *editor*, say) would be a different feature with a consent story attached.
- **Restart zeroes it.** Argued above as correct: ephemeral state should not survive the process, and the truth re-forms on reconnect. It is the one place in this book where losing state on restart is the feature.
- **No history, by construction.** There is no record that three people looked at a recipe on Tuesday -- presence leaves no trace, because it is not data. The day you *want* that trace, it becomes an [analytics event](28-admin-dashboard.md), which is a different thing living in a different place.

## Both axes, reachable from here

The single-page world's strongest claim is the live, stateful, real-time experience. This chapter met it with an atom, an http-kit channel the book has had since chapter 6, an island obeying the same enhancement rule as every other, and a CSP directive that was already correct. Server-rendering was never the opposite of real-time; it is a foundation real-time layers cleanly onto, exactly as [the asynchronous collaboration](44-three-way-merge.md) of the previous chapter layered onto immutable history. Both axes of collaboration -- the async merge and the live presence -- turned out to be *reachable from here*, without abandoning the server or contradicting a single decision the book made along the way. That is the wager paid on the axis everyone swore it could not reach: choose the server, own the whole thing, and the features you were told you would have to leave behind are waiting on the other side, smaller than you feared.
