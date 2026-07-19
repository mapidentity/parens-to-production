# N People Are Looking: Real-Time Presence Without Leaving SSR

There is a belief this book has spent forty-four chapters implicitly arguing against, and it deserves one chapter arguing against it explicitly: that *server-rendered* means *not real-time*. That choosing the server, as [chapter 2](02-positioning.md) did, means giving up the live, pushed, stateful experiences the single-page world treats as its home turf. It does not. The whole-page render and the live feed are not opposites; they are layers, and this chapter builds the second one — a small, honest, genuinely real-time feature — on top of everything already here.

The feature is deliberately modest, because the point is the *pattern*, not the ambition: a live viewer count on a public recipe. "👀 3 people looking now," updating the instant someone arrives or leaves. It is the kind of thing everyone assumes requires a client framework and a websocket mesh, and it turns out to be a server-sent-events island and an atom.

## Presence is not data

The first design decision is where the state lives, and it is the opposite of every other decision in this book. Recipes, versions, forks, proposals — all of it is [durable, immutable, in Datomic](08-datomic.md), because it is *data*: facts that happened and stay true. Presence is not that. Who is looking at a recipe *right now* is a fact with a lifespan of seconds, true only in the present tense, meaningless the instant it changes and worthless after a restart. Putting it in Datomic would be a category error — you would be writing history for something that has none, and slamming [the single transactor](41-beyond-one-box.md) with a write every time a browser tab opened or closed.

So presence lives in an atom, in memory, and that is not a shortcut — it is the correct home for ephemeral state:

```clojure
(defonce viewers (atom {}))   ; recipe-id -> #{open SSE channel}
```

A restart resets every count to zero, and that is *fine*: the counts re-form within seconds as browsers reconnect, because the truth they represent is continuously re-asserted by reality. The scaling caveat is the honest one [the audit already drew](41-beyond-one-box.md) — on a second box each process would count only its own viewers — but for the single box this whole book targets, one atom is exactly right, and reaching for Redis here would be [buying infrastructure the domain does not demand](25-auth-email-flow.md).

## The push: Server-Sent Events, not WebSockets

To tell a browser the count changed, the server has to *push*. The instinct is a WebSocket — but a WebSocket is a bidirectional channel, and this is a one-way trickle: the server talks, the browser only listens. **Server-Sent Events** are exactly that shape — a long-lived HTTP response the server streams `data:` lines down, with the browser's `EventSource` reconnecting on its own if it drops. Less machinery than a WebSocket, and it rides ordinary HTTP, so it passes through [the reverse proxy](35-going-live.md) and [the strict CSP](29-asset-pipeline.md) without special handling.

The server side is [the same `http-kit` async channel the dev-reload loop has used since chapter 6](06-live-reload.md), pointed at a different job:

```clojure
(defn stream
  [recipe-id request]
  (hk/as-channel request
    {:on-open (fn [ch]
                (hk/send! ch {:status 200
                              :headers {"Content-Type" "text/event-stream" ,,,}}
                          false)
                (swap! viewers update recipe-id (fnil conj #{}) ch)
                (broadcast! recipe-id))
     :on-close (fn [ch _status]
                 (swap! viewers update recipe-id disj ch)
                 (when (empty? (get @viewers recipe-id))
                   (swap! viewers dissoc recipe-id))
                 (broadcast! recipe-id))}))
```

The whole feature is in the two callbacks. On open: send the `text/event-stream` headers (the `false` on `send!` means *keep the channel open*), add this connection to the recipe's set, and broadcast the new count to everyone watching. On close: remove it, clean up an emptied entry, and broadcast the lower count. `broadcast!` walks the set and pushes one `data: {"count": N}` frame to each — and prunes any channel whose `send!` returns false, because http-kit reports a closed-but-not-yet-noticed connection that way, so a lagging disconnect self-heals on the next broadcast rather than inflating the count. That prune is [the same belt the dev-reload channel uses](06-live-reload.md); the pattern was already in the codebase, waiting.

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
});
```

Two small honesties in that handler. The count includes *you*, so it renders only when `others > 0` — a solo reader sees nothing, not the slightly sad "1 person looking." And it closes the stream on `pagehide`, so the server's `on-close` fires promptly when you navigate away and the count everyone else sees drops without waiting for a timeout.

And the piece that ties it back to the book's spine: this needed **no change to the Content-Security-Policy.** [The strict CSP](29-asset-pipeline.md) already carries `connect-src 'self'`, because `connect-src` is the directive that governs `EventSource`, `fetch`, and WebSockets alike — and it was set to `'self'` from the start. The policy that was written to lock everything down had already, correctly, allowed the app to talk to itself. The real-time feature slid in under a security posture designed months of chapters ago, with nothing loosened.

## Proof

Driven against the running server, two `EventSource` clients on one recipe: the first sees the count, and when the second connects, the first's stream immediately pushes the higher number — the real-time path works end to end, through the whole middleware stack, `text/event-stream` and all. The registry's arithmetic — increment on arrival, decrement on departure, clean up the last one out, prune a stale channel — is pinned by a unit test driving the open/close callbacks with fake channels, because that logic is where a leak or a wrong count would hide, and it should not depend on a live socket to catch.

## Trade-offs & limitations, in one place

- **Single-process, like the rate limiter.** The atom counts one box's viewers. [The scaling audit](41-beyond-one-box.md) already priced the shared-state fix; on one box there is nothing to fix. A pair deploy briefly shows two independent counts, which for a viewer garnish is beneath notice.
- **A held connection per viewer.** SSE keeps a socket open per watcher; http-kit carries many cheaply, but an unbounded public endpoint that holds connections is a denial-of-service surface, and [connection limits belong at the proxy](35-going-live.md), not in this handler. Named, not defended.
- **Counts, not identities.** It says *how many*, never *who* — deliberately, because "who is reading this recipe" is a privacy question a public counter must not answer. Presence-with-identity (for the *editor*, say) would be a different feature with a consent story attached.
- **Restart zeroes it.** Argued above as correct: ephemeral state should not survive the process, and the truth re-forms on reconnect. It is the one place in this book where losing state on restart is the feature.
- **No history, by construction.** There is no record that three people looked at a recipe on Tuesday — presence leaves no trace, because it is not data. The day you *want* that trace, it becomes an [analytics event](28-admin-dashboard.md), which is a different thing living in a different place.

## The wager, once more

The single-page world's strongest claim is the live, stateful, real-time experience — and this chapter met it with an atom, an http-kit channel the book has had since chapter 6, an island obeying the same enhancement rule as every other, and a CSP directive that was already correct. Server-rendering was never the opposite of real-time; it is a foundation real-time layers cleanly onto, exactly as [the asynchronous collaboration](44-three-way-merge.md) of the previous chapter layered onto immutable history. Both axes of collaboration — the async merge and the live presence — turned out to be *reachable from here*, without abandoning the server or contradicting a single decision the book made along the way. That is the wager, paid one final time: choose the server, own the whole thing, and the features everyone said you would have to leave behind are waiting for you on the other side, smaller than you feared.
