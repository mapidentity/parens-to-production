# Tightening the Reload Loop: DOM Morphing and CSS Hot-Swap

[The live-reload chapter](06-live-reload.md) gave us a working feedback loop: a file watcher loads the changed `.clj` file, a WebSocket tells the browser, and the browser does a full reload with the scroll position restored. That is correct. It is also a blunt instrument.

A full reload throws away *all* page state. It loses your scroll position (we patched that with the scroll stash), but it also blurs the field you were typing in, collapses every open `<details>`, and discards any in-progress form input. For the most common edit during day-to-day work -- a one-line tweak to a view function -- that is wildly disproportionate. You changed some markup; the browser tore down and rebuilt the entire page.

And reload is not even the right answer for *every* kind of save. A CSS rebuild should not reload the page at all -- a stylesheet is declarative, so swapping it in place restyles the page with no flash and no lost state. Meanwhile a `.js` module edit *must* reload, for reasons rooted in how ES modules work. Different edits have different correctness constraints.

The insight that drives this chapter is that **a save is not one thing.** A view edit, a non-view code edit, a JavaScript edit, and a CSS rebuild each have a *narrowest correct response*, and matching the response to the edit is what makes the loop feel instant instead of janky. We call the mapping the per-edit *delivery matrix*:

| You edit... | Response |
| --- | --- |
| a view namespace (`*views.clj`) | morph `<body>` in place -- keeps scroll, focus, open `<details>` |
| any other `.clj` | full reload, scroll restored |
| a served `.js` module | full reload, scroll restored |
| (Tailwind rebuilds `styles.css`) | hot-swap the stylesheet `<link>` -- no reload |

This chapter rebuilds the watcher's dispatch around that matrix. It assumes the basic file watcher and WebSocket from [the live-reload chapter](06-live-reload.md), the server-rendered Hiccup views from [the Hiccup views chapter](14-hiccup-views.md), the client dispatcher's `fetchAndMorph` (idiomorph) from [the morph-dispatcher chapter](15-morph-dispatcher.md), the source inspector and its `inspector-load` loader from [the source inspector chapter](16-inspector.md), and the Tailwind setup (`input.css`, the `@source` scan, the CLI) from [the Tailwind chapter](13-tailwind-styling.md).

> **One artifact, two deliveries.** Dev and production build from the same committed sources through one pipeline; they differ in the HTTP envelope (URL shape, cache headers) and the build cadence (watch vs. one-shot), not in a second asset tree to reconcile. For CSS the bytes are literally identical: the dev watch runs the same minified Tailwind build production does. For JavaScript the module graph is identical (same files, same import specifiers), but production esbuild-minifies each module, so dev serves the readable source and prod ships the compressed bytes. The dev story below is the "watch" cadence of that pipeline; [the asset pipeline chapter](29-asset-pipeline.md) covers the production cadence -- content hashing, minification, an import map, and immutable caching. Nothing here changes *what* ships between dev and prod; it only changes *when* and *how* it is delivered.

## Revisiting `load-changed-file`

In [the live-reload chapter](06-live-reload.md), `load-changed-file` had a single branch: a `.clj` file changed, so load it and trigger a full reload. The matrix needs three branches, checked in order. Here is the real thing:

```clojure
(defn- load-changed-file
  "Loads a changed file."
  [{:keys [event-type path]}]
  (when (= event-type :modify)
    (cond
      ;; Tailwind output: debounced, CSS-ready refresh (fires after the write).
      (.endsWith (str path) "styles.css")
      (debounced-css-reload!)

      (clj-file? path)
      (let [file-path (str path)
            start-time (System/nanoTime)]
        (log/info "File changed" {:file-path file-path})
        (try
          (before-refresh)
          ;; View namespaces go through the inspector's tools.reader load (for
          ;; element-level source metadata); everything else is a normal load. A
          ;; view-ns edit is morphable; other .clj edits force a full reload.
          (let [view? (inspector-load/reload-changed! file-path)]
            (when-not view? (load-file file-path))
            (after-refresh (boolean view?)))
          (let [duration-seconds (/ (- (System/nanoTime) start-time) 1e9)]
            (log/info "Successfully reloaded file"
              {:file-path file-path
               :duration-seconds duration-seconds}))
          (catch Exception e
            ;; Reload failed (syntax error, etc.) -- the edit didn't take and we
            ;; did NOT reload the browser, so its page is now potentially stale.
            (dev-reload/notify-reload-error! file-path (some-> e .getMessage))
            (let [duration-seconds (/ (- (System/nanoTime) start-time) 1e9)]
              (log/error e "Error reloading file"
                {:file-path file-path
                 :duration-seconds duration-seconds})))))

      (asset-file? path)
      (do
        (log/info "Asset file changed, reloading browser" {:file-path (str path)})
        (dev-reload/reload!)))))
```

Read top to bottom, this *is* the matrix:

- **`styles.css` changed.** This is Tailwind's output, generated rather than hand-edited, so it gets a *debounced* CSS swap (`debounced-css-reload!`, covered below). No code loads; no page reloads.
- **A `.clj` file changed.** This is the interesting branch. Instead of an unconditional `load-file`, we first ask `inspector-load/reload-changed!`, which returns truthy if the file was a *view* namespace. That truthiness becomes the `morphable?` flag passed to `after-refresh`. A view edit is morphable (the browser can morph `<body>`); any other `.clj` edit is not (full reload).
- **A `.js` (or other `.css`) asset changed.** A plain full reload via `dev-reload/reload!`.

The cheap predicates are unchanged from before, with one addition for assets:

```clojure
(defn- clj-file?
  "Returns true if the path has a .clj extension."
  [^java.nio.file.Path path]
  (.endsWith (str path) ".clj"))

(defn- asset-file?
  "Returns true if the path has a .js or .css extension."
  [^java.nio.file.Path path]
  (let [s (str path)]
    (or (.endsWith s ".js") (.endsWith s ".css"))))
```

The failure path and the view/non-view split both need a closer look.

The **failure path** is new. A broken file -- a syntax error -- no longer just logs and moves on. It also sends a `reload-error` message to the browser via `notify-reload-error!`. The edit didn't take, so the browser was never refreshed, which means the page in front of you no longer matches the code you just saved. The `reload-error` message raises a soft "this page may be stale" banner so you know *why* the page and the code disagree. Fix the error, save again, and the next successful reload clears the banner on its own.

### Why view namespaces take a different load path

The `.clj` branch does not call `load-file` directly. It first asks `inspector-load/reload-changed!`, and only falls back to `load-file` when that returns falsey. For files whose name ends in `views.clj`, `reload-changed!` loads them through `clojure.tools.reader` instead of the normal loader, and returns truthy.

The reason belongs to [the source inspector chapter](16-inspector.md): the default Clojure reader attaches no line metadata to nested vector literals, but `tools.reader` does, and that metadata is what lets the inspector tag every rendered element with the source location that produced it. A view edit must take the `tools.reader` path so the inspector's source map stays current; everything else takes the ordinary `load-file`.

For the matrix, the relevant output is just the boolean: a `views.clj` edit is *morphable*, a non-view `.clj` edit is not. That boolean is the entire reason this branch exists in the shape it does.

### Watching `static/` too

The basic watcher only registered the `src/` tree. The matrix needs more: the `styles.css` branch and the `.js` asset branch only fire if something is watching where those files live, and they live under `static/` -- Tailwind writes its output to `static/styles.css`, and the served ESM modules live under `static/js/`. So `start-file-watcher` now walks *both* roots:

```clojure
;; Watch src/ (code) AND static/ (so the Tailwind output styles.css and the
;; source ESM under static/js trigger the right refresh).
_ (doseq [r ["src" "static"]
          :when (.exists (java.io.File. ^String r))
          ^Path dir (->> (Files/find (.toPath (java.io.File. ^String r))
                                     Integer/MAX_VALUE
                                     (reify
                                       BiPredicate
                                         (test [_ _path attrs]
                                           (.isDirectory ^BasicFileAttributes attrs)))
                                     (make-array java.nio.file.FileVisitOption 0))
                         .iterator
                         iterator-seq)]
    (.register dir ws kinds))
```

If we only watched `src/`, JS edits and CSS rebuilds would fire nothing. The second root closes that gap. Everything else about the watch loop -- daemon thread, `ENTRY_MODIFY`/`ENTRY_CREATE`, the silent `ClosedWatchServiceException` on shutdown -- is exactly as [the live-reload chapter](06-live-reload.md) built it.

## CSS: one long-lived Tailwind watcher

The matrix line for CSS is conspicuous: CSS is absent from the "save triggers a rebuild" path entirely. The watcher only *reacts* to `styles.css` being rewritten; it never rewrites it. That is deliberate, and it is the biggest structural change from a naive design.

The obvious approach is to rebuild Tailwind *on every `.clj` save* -- a `.clj` file might introduce a new utility class, so regenerate the stylesheet, then refresh. That is wrong in two ways. It couples CSS rebuilds to code reloads, so a Clojure edit that touches no markup still pays for a full Tailwind run. And it puts the rebuild on the critical path of every single save.

The better design decouples them. Tailwind v4 has its own watch mode: point it at your input and output and it watches the `@source` globs (which include `./src`), rebuilding incrementally whenever a class appears or disappears. So we start **one long-lived Tailwind process** at dev startup and let it own the stylesheet entirely:

```clojure
(defonce ^{:doc "The long-lived Tailwind --watch Process, or nil."} tailwind-watcher (atom nil))

(defn start-tailwind-watch!
  "Start ONE long-lived `tailwindcss --watch=always` writing static/styles.css for
  dev (served unhashed, no-store). --watch=always (not plain --watch) is required:
  plain --watch exits as soon as stdin closes, which a script/REPL launch triggers.
  Tailwind v4 also watches the @source globs (./src), so a .clj edit that adds a
  utility class rebuilds the stylesheet on its own -- decoupled from code reload."
  []
  (stop-tailwind-watch!)        ; fully terminate any prior process first -- no double writers
  @tailwind-shutdown-hook       ; install the JVM-exit cleanup once
  (let [^"[Ljava.lang.String;" cmd (into-array String
                                     ["tailwindcss" "-i" "input.css" "-o" "static/styles.css" "--minify" "--watch=always"])
        pb (doto (ProcessBuilder. cmd) (.inheritIO))
        p (.start pb)]
    (reset! tailwind-watcher p)
    (log/info "Tailwind --watch started" {:out "static/styles.css"})
    p))
```

The two flags at the end of that command are the non-obvious part.

**Why `--watch=always` and not plain `--watch`?** Plain `--watch` exits as soon as its stdin closes. That is fine when you run it by hand in a terminal, but a process you launch from a script or the REPL doesn't keep a TTY attached to its stdin, so plain `--watch` would exit almost immediately. `--watch=always` tells Tailwind to keep watching regardless of stdin. This is the kind of thing you only discover by hitting it, and the flag is essential.

**Why keep `--minify` in dev?** Because of the "one artifact, two deliveries" rule. Production minifies the CSS, so dev minifies it too -- the dev stylesheet is byte-for-byte what production builds from the same `input.css`. If dev served unminified CSS and prod served minified, you would have two artifacts and a class of "works in dev, breaks in prod" surprises. The minification cost is invisible (Tailwind is fast and incremental), so there is no reason to introduce the drift.

### Lifecycle hardening

Because it is an external OS process, we have to manage its lifecycle by hand. Sloppiness here has a specific, nasty failure mode: two Tailwind processes writing the same `styles.css` at once, racing each other. Three pieces of hardening prevent it:

```clojure
(defn stop-tailwind-watch!
  "Stop the Tailwind --watch process, WAITING for it to actually exit before
  returning -- so a restart can never leave two tailwinds writing styles.css at once."
  []
  (when-let [^Process p @tailwind-watcher]
    (.destroy p)
    (when-not (.waitFor p 2 TimeUnit/SECONDS) (.destroyForcibly p))
    (reset! tailwind-watcher nil)
    (log/info "Tailwind --watch stopped")))

(defonce ^:private tailwind-shutdown-hook
  ;; Destroy the external Tailwind process on JVM exit so a killed REPL never
  ;; orphans it (an orphan would keep writing styles.css behind our back).
  (delay (.addShutdownHook (Runtime/getRuntime)
           (Thread. ^Runnable (fn [] (stop-tailwind-watch!)) "tailwind-shutdown"))))
```

1. **`stop-tailwind-watch!` waits for the process to actually die** (`.waitFor` with a timeout, escalating to `.destroyForcibly`) before returning. So when `start-tailwind-watch!` calls `stop-tailwind-watch!` first, the old process is *gone* before the new one starts -- never two writers.
2. **A JVM shutdown hook** destroys the Tailwind process when the REPL exits. Kill your REPL ungracefully and you would otherwise orphan a Tailwind process that keeps rewriting `styles.css` behind the back of your next session.
3. **`start-tailwind-watch!` is idempotent**: it stops any prior process before starting a new one, so re-running it (or `start`) can never accumulate watchers.

When this process rewrites `static/styles.css`, the *file watcher* sees the write -- that is the `styles.css` branch of `load-changed-file`. Which brings us to the race.

### Debouncing the CSS-ready refresh

A single Tailwind rebuild can emit several filesystem events (it may truncate, then write, then flush). If we fired a browser update on the first event, the browser could re-fetch a stylesheet that is *mid-rebuild* -- empty or half-written. And because Tailwind runs asynchronously, a code edit that adds a class produces a sequence the browser must not observe out of order: the new markup arrives via the code reload, but the matching CSS is still being written.

The fix is to debounce the CSS notification and fire it only *after* the writes settle:

```clojure
(defonce ^:private css-debounce-pool
  (Executors/newSingleThreadScheduledExecutor
    (reify ThreadFactory
      (newThread [_ r] (doto (Thread. ^Runnable r "css-reload-debounce") (.setDaemon true))))))

(defonce ^:private css-debounce-task (atom nil))

(defn- debounced-css-reload!
  "Coalesce a burst of styles.css writes into a single browser refresh ~150ms
  after the last write, guaranteeing the new CSS is fully on disk first."
  []
  (when-let [^ScheduledFuture t @css-debounce-task] (.cancel t false))
  (reset! css-debounce-task
    (.schedule ^ScheduledExecutorService css-debounce-pool
      ^Runnable (fn [] (dev-reload/notify-css!)) 150 TimeUnit/MILLISECONDS)))
```

Each `styles.css` event cancels the pending task and reschedules it ~150ms out. A burst of writes collapses into a single `notify-css!`, fired only once the file has been quiet for 150ms -- by which point the rebuilt CSS is, in practice, fully on disk. (A quiet window is a heuristic, not a proof: the filesystem offers no "rebuild finished" event, so silence after the last write is the strongest done-signal available.) One daemon-thread scheduler does all of it. This is the kind of small race that makes a decoupled watcher feel *unreliable* if you skip it, and rock-solid once it is in place.

### Stable dev URLs with `no-store`

The dev stylesheet is served at a stable, unhashed URL (`/styles.css`) -- there is no content hash in dev, because the URL never changes. That stability is why the next problem exists: the browser would happily cache the stable URL and serve stale bytes after a rebuild. We solve that on the *response* side, not by mangling URLs. Dev marks served `.css`/`.js` as `no-store`:

```clojure
;; myapp.web.routes
(defn wrap-dev-no-store
  "Dev only: Cache-Control: no-store on served .css/.js so a stable (unhashed) dev
  URL never serves stale bytes after Tailwind --watch / esbuild rewrites the file."
  [handler]
  (fn [request]
    (let [resp (handler request)]
      (if (and resp (re-find #"\.(?:css|js)$" (or (:uri request) "")))
        (assoc-in resp [:headers "Cache-Control"] "no-store")
        resp))))
```

This middleware wraps the static-file handler, and only in dev:

```clojure
(ring/routes
  (cond-> (ring/create-file-handler {:path "/" :root assets/static-root})
    assets/dev? wrap-dev-no-store)
  (ring/create-default-handler))
```

The `static-root` itself is the same idea -- one mechanism with a dev/prod switch:

```clojure
;; myapp.web.assets
(def static-root
  "Dir the Ring file handler serves from: source static/ in dev, the built
  myapp/static/ tree in prod (also what Caddy mounts)."
  (if dev? "static" "myapp/static"))
```

In dev the app serves the *source* `static/` tree directly at stable URLs with `no-store`. In production, Caddy serves the *built* `myapp/static/` tree with content-hashed filenames and `immutable` caching ([the asset pipeline chapter](29-asset-pipeline.md)). Same files, two envelopes.

## WebSocket: from one message to four

The live-reload chapter had exactly one outbound message: `reload`. The matrix needs more. The reload loop now spans three message types, and the `reload` message carries a `morphable` flag, so the browser has four behaviors to dispatch on. (The `/dev/ws` socket is shared infrastructure by this point in the book: [the source inspector chapter](16-inspector.md) already relays `connected`, `highlight`, and `open-result` over it. What follows is the reload loop's slice of the traffic.)

```clojure
(defn notify-reload!
  "Tell every browser client to reload. `morphable?` (default false) lets the browser
  take the state-preserving morph fast path (a view-ns edit) instead of a full
  reload (non-view .clj, .js, or a manual trigger)."
  ([] (notify-reload! false))
  ([morphable?]
   (send-json! (clients-of :browser) {:type "reload" :morphable (boolean morphable?)})))

(defn notify-css!
  "Tell browsers to hot-swap the stylesheet <link> (no reload). The dev CSS URL is
  stable, so the browser cache-busts it to refetch the rebuilt file."
  []
  (send-json! (clients-of :browser) {:type "css"}))

(defn notify-reload-error!
  "Tell browsers a source file failed to (re)load (a syntax error or similar), so
  the page can warn it MAY be stale."
  [file error]
  (send-json! (clients-of :browser) {:type "reload-error" :file file :error error}))
```

So the reload loop emits exactly three browser messages: `reload` (with a `morphable` flag), `css`, and `reload-error`. The `before-refresh`/`after-refresh` hooks in `hot-reload` are the thin shims that turn the load result into the first of those:

```clojure
(defn before-refresh
  "This hook runs before refreshing a changed file."
  []
  (log/info "Code refresh starting..."))

(defn after-refresh
  "Runs after a changed .clj file reloads: notify the browser. A view-ns edit is
  morphable (state-preserving <body> morph -- view namespaces own the chrome as
  well as <main>); other .clj edits force a full reload. CSS is rebuilt
  out-of-band by the Tailwind --watch process, not here."
  [morphable?]
  (log/info "Code refresh completed, notifying browser..." {:morphable morphable?})
  (dev-reload/notify-reload! morphable?))
```

Note what is *not* in `after-refresh` anymore: no Tailwind shell-out, no CSS hashing. That responsibility moved entirely to the long-lived watcher. `after-refresh` does one thing -- notify the browser, with the right `morphable?` flag.

A manual `(reload!)` from the REPL is intentionally *not* morphable -- it defaults to a full reload, the safe choice when you trigger it by hand. The `send-json!` pruning of dead channels and the `requiring-resolve`-guarded `/dev/ws` route are exactly as [the live-reload chapter](06-live-reload.md) built them.

## The client side: one socket, four responses

On the browser side, the dev script dispatches on message type. It is the client end of the delivery matrix:

```javascript
const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/dev/ws');
ws.onmessage = function (event) {
  const data = JSON.parse(event.data);
  if (data.type === 'reload') {
    if (data.morphable) { morphReload(); } else { hardReload(); }
  } else if (data.type === 'css') {
    swapStylesheet();                    // CSS rebuilt -- swap the <link>, no reload
  } else if (data.type === 'reload-error') {
    showStaleWarning(data.file, data.error);
  }
};
```

Three message types, four behaviors -- because `reload` forks on `morphable`. (One drift note for anyone reading `dev-reload.js` alongside this: the repo wraps this connection in a `connectDevReload()` function and defers the call while `document.prerendering` is true, because a prerendered page is not allowed to hold a WebSocket. The rule, and the speculative prerendering that makes it matter, belong to [the progressive-enhancement chapter](20-progressive-enhancement.md); the dispatch itself is as shown.)

<svg viewBox="0 0 760 250" role="img" aria-label="The delivery matrix: each kind of edit becomes one message and one browser response" style="width:100%;height:auto;font-family:monospace">
  <defs>
    <marker id="dm-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
      <path d="M 0 0 L 8 4 L 0 8 z" fill="currentColor" opacity="0.45"/>
    </marker>
  </defs>
  <g font-size="12" fill="currentColor" opacity="0.6">
    <text x="20" y="26">the edit</text>
    <text x="270" y="26">the message (/dev/ws)</text>
    <text x="520" y="26">the browser's answer</text>
  </g>
  <g font-size="12.5" fill="currentColor">
    <text x="20" y="62">a view .clj</text>
    <text x="20" y="100">any other .clj</text>
    <text x="20" y="138">a served .js</text>
    <text x="20" y="176">styles.css rebuilt</text>
    <text x="20" y="214">a load that threw</text>
  </g>
  <g stroke="currentColor" stroke-opacity="0.35" marker-end="url(#dm-arrow)">
    <path d="M 175 58 H 258"/><path d="M 175 96 H 258"/><path d="M 175 134 H 258"/>
    <path d="M 175 172 H 258"/><path d="M 175 210 H 258"/>
  </g>
  <g font-size="12.5" fill="currentColor">
    <text x="270" y="62">reload · morphable: true</text>
    <text x="270" y="100">reload · morphable: false</text>
    <text x="270" y="138">reload · morphable: false</text>
    <text x="270" y="176">css</text>
    <text x="270" y="214">reload-error</text>
  </g>
  <g stroke="currentColor" stroke-opacity="0.35" marker-end="url(#dm-arrow)">
    <path d="M 465 58 H 508"/><path d="M 465 96 H 508"/><path d="M 465 134 H 508"/>
    <path d="M 465 172 H 508"/><path d="M 465 210 H 508"/>
  </g>
  <g font-size="12.5">
    <text x="520" y="62" fill="#2a9d8f">morph &lt;body&gt; -- page state kept</text>
    <text x="520" y="100" fill="#e07840">full reload -- scroll restored</text>
    <text x="520" y="138" fill="#e07840">full reload -- scroll restored</text>
    <text x="520" y="176" fill="#4a7fb5">hot-swap the &lt;link&gt; -- no reload</text>
    <text x="520" y="214" fill="#c65353">stale-page warning stays up</text>
  </g>
</svg>

*The matrix as it runs: one watcher event becomes one of three message types, and the browser answers with the narrowest correct response -- morph, reload, stylesheet swap, or an honest warning that what you see may be stale.*

### A view edit: morph `<body>` in place

The most common edit during day-to-day work is to a view function -- tweak markup, adjust a class, restructure a fragment. For that case a full reload is overkill and actively annoying: it collapses open `<details>` and blurs the field you were typing in (scroll it would restore, via the stash from earlier -- but the rest it cannot). So a `morphable` reload morphs the freshly rendered page into the live DOM instead, reusing the *production* navigation machinery:

```javascript
// A view-ns edit: morph the whole <body> in place via the dispatcher
// (state-preserving -- keeps scroll, focus, open <details>), sparing the
// client-injected dev UI marked data-myapp-overlay (see below). Falls back to a
// full reload on any failure, and clears a prior stale banner since the morph
// won't navigate it away.
function morphReload() {
  var bar = document.getElementById('myapp-stale-warning'); if (bar) bar.remove();
  import('/js/dispatcher.js')
    .then(function (m) {
      return m.fetchAndMorph(location.pathname + location.search,
        { target: 'body', replaceUrl: true, focus: false, ignoreActiveValue: true,
          morphCallbacks: {
            beforeNodeRemoved: function (node) {
              return !(node.nodeType === 1 && node.hasAttribute('data-myapp-overlay'));
            },
            // A user-opened <details> reflects `open` onto the live node, but the
            // server re-render has it closed -- idiomorph would strip `open` and
            // collapse the panel. Veto that one attribute removal so an expanded
            // <details> survives a view-edit morph.
            beforeAttributeUpdated: function (attr, node, mutationType) {
              return !(mutationType === 'remove' && attr === 'open' && node.tagName === 'DETAILS');
            },
          } });
    })
    .catch(function () { window.location.reload(); });
}
```

The key point is that this is *not* a new mechanism. `fetchAndMorph` is the app's production interaction layer ([the morph-dispatcher chapter](15-morph-dispatcher.md)): it fetches the current URL, parses the response, and uses idiomorph to morph the requested target in place, preserving form values, focus, and scroll. Dev hot-reload is just one more caller of it. A few options matter:

- `target: 'body'` -- morph the whole page body, not just `<main>`. Why the widest target is the narrowest *correct* one is the decision below.
- `morphCallbacks` with two vetoes -- `beforeNodeRemoved` refuses to remove any element carrying `data-myapp-overlay`, the mark every client-injected dev node wears; `beforeAttributeUpdated` refuses the one attribute change that would strip `open` off a user-expanded `<details>` and collapse it. Both are part of the decision below, and the second is what makes the "keeps open `<details>`" claim above true rather than aspirational.
- `ignoreActiveValue: true` -- this one is mandatory. Without it, idiomorph would clobber the value of the field you are currently typing in. With it, your in-progress input survives the morph.
- `focus: false` -- a hot reload should not steal focus.
- The `.catch(...)` falls back to a full `window.location.reload()` if anything goes wrong, so a morph failure degrades to the safe behavior rather than leaving a half-updated page.

It also clears any leftover stale-warning banner first, because a morph (unlike a reload) does not navigate, so the banner would otherwise persist.

> **Decision -- morph `<body>`, and teach the morph to spare the overlays.** An earlier cut of this function morphed `<main>`, the same region production navigation targets, and it looked like the narrowest correct response. It is not, because of what "view namespace" means in this app: `views.clj` owns the page chrome (`base-layout`, `top-nav`, the layout wrappers) as well as the content inside `<main>`. Edit the nav markup under a `<main>`-only morph and everything reports success (the server reloads the namespace, sends `morphable: true`, the morph completes) while the visible chrome stays stale, with no banner to say so. That is precisely the silent staleness this chapter builds `reload-error` to prevent, manufactured by the morph itself. So the target is the whole `<body>`; the `<head>` stays untouched, and the dispatcher copies the new `<title>` over as it does for every navigation. That leaves one residue the box's own logic should name: a view edit to the rest of `base-layout`'s `<head>` (a meta description, an `og:` tag, a preload `<link>`) is neither morphed nor title-synced, so it goes stale under a view save just as `<main>`-only chrome would, until the next full reload picks it up. Head edits are rare and never visible on the page, so the body morph accepts that residue rather than widening again to re-render `<head>`. The widening has a cost the options list pays. The server's freshly rendered HTML never contained the dev tooling's client-injected nodes (the inspector's badge and highlight boxes, the construction-view panel), so to idiomorph they are cruft to delete. Every such node therefore carries `data-myapp-overlay`, and the `beforeNodeRemoved` callback vetoes their removal. The attribute is a contract: a future dev overlay that forgets to mark itself will be eaten by the next view save.

After the morph, `fetchAndMorph` fires a `dispatcher:morphed` event. The source inspector listens for it to re-attach its highlight to the freshly morphed DOM -- [the source inspector chapter](16-inspector.md), which precedes this one, owns that behavior. Here it is enough to know the morph announces itself.

### A non-view `.clj` or `.js` edit: full reload, scroll restored

When the edit is *not* a view -- a handler, a route table, a `.js` module -- the server sends a non-morphable `reload`, and the client does a full page reload. It stashes scroll first and restores it after, just as [the live-reload chapter](06-live-reload.md)'s reload did:

```javascript
// A non-view .clj or a .js edit: a module is a re-executing singleton, so a full
// reload is required. Stash scroll so the reload doesn't lose your place.
function hardReload() {
  try { sessionStorage.setItem('myapp-dev-scroll', String(window.scrollY)); } catch (e) {}
  window.location.reload();
}

// Restore scroll after a dev hard reload (stashed by hardReload before reloading).
try {
  var savedScroll = sessionStorage.getItem('myapp-dev-scroll');
  if (savedScroll !== null) {
    sessionStorage.removeItem('myapp-dev-scroll');
    window.addEventListener('load', function () { window.scrollTo(0, parseInt(savedScroll, 10) || 0); });
  }
} catch (e) {}
```

This raises the obvious question: **why does a `.js` edit force a full reload at all, when a view edit can morph?** The answer is fundamental to ES modules, and it is worth being precise about, because it is the reason the matrix has this shape.

> **An ES module is a URL-cached singleton.** The browser loads a module *once per URL* and caches the resulting module instance forever. Importing the same URL again returns the same already-evaluated instance -- the module body never re-runs. So when you edit `dispatcher.js`, there is no in-place way to make the browser re-evaluate it: the old instance, with its already-registered event listeners and timers, is still live. The only honest ways to pick up the new code are (a) `eval` the new source, (b) re-import the module under a *different* URL (a cache-bust query) and somehow tear down the old instance, or (c) reload the page.
>
> We reject (a) and (b). `eval` requires `'unsafe-eval'` in the Content-Security-Policy, and our CSP forbids it on purpose ([the asset pipeline chapter](29-asset-pipeline.md)) -- adding `unsafe-eval` for a dev convenience would weaken the very policy the book is teaching. Re-importing under a fresh URL is CSP-legal but *unsound*: the old module's event listeners and `setInterval`s keep running with nothing to dispose them, so you accumulate zombie handlers on every edit. A real module-replacement system (HMR with accept/dispose hooks) is bundler-grade machinery, wildly disproportionate for the thin, mostly stateless client JS here. So (c), a full reload, is the *correct* behavior, not a fallback -- and the scroll stash makes it nearly seamless.

The same singleton argument is why the dev scripts do not try anything clever for non-view `.clj` edits either: those can change server behavior in ways a body morph can't safely reflect (a changed route, a changed handler), so a full reload is the conservative choice.

### A CSS rebuild: swap the `<link>`, no reload

When Tailwind rebuilds the stylesheet, the server sends `css` (after the debounce settles), and the client swaps the stylesheet's `href` with a cache-busting query. No reload, no flash -- the page just restyles:

```javascript
// A .css rebuild (Tailwind --watch): swap the stylesheet href with a cache-bust so
// the browser refetches the rebuilt file -- no reload, no flash.
function swapStylesheet() {
  var link = document.querySelector('link[rel="stylesheet"]');
  if (!link) return;
  var base = (link.getAttribute('href') || '').split('?')[0];
  link.setAttribute('href', base + '?v=' + Date.now());
}
```

CSS is the easy case precisely because it has no execution model and no registry: a stylesheet is declarative, so re-fetching it has no side effects to clean up. (Compare the module problem above.) The dev URL is stable, so a `?v=<timestamp>` query is enough to defeat the cache and pull the rebuilt bytes.

### A reload that failed: a soft staleness banner

The fourth message, `reload-error`, is the response to a save that *didn't* take -- a syntax error on the server side. The browser was never refreshed, so the page you are looking at may not reflect your latest edit. The client raises a dismissible banner saying so:

```javascript
function showStaleWarning(file, error) {
  // ... builds a fixed-position banner: "<file> failed to reload --
  //     this page may be stale. Fix the error and save." ...
}
```

The wording is soft ("may be stale") because the server can't know whether the page actually depends on the broken file -- it might be an unrelated reload. The banner does not need to be cleared by hand: the next *successful* reload (or morph) clears it automatically, because a morph removes it explicitly and a reload navigates the page away from it.

## Updating `start`

The matrix adds two steps to the `hot-reload/start` [the live-reload chapter](06-live-reload.md) introduced: launch the long-lived Tailwind watcher, and preload the view namespaces through `tools.reader` so the inspector has source metadata from boot rather than only after the first edit.

```clojure
(defn start
  "Run this from the REPL to start developing."
  []
  (core/start-dev-server)
  (log/info "Development server started" {:url "http://localhost:3000"})
  ;; One long-lived Tailwind --watch writes static/styles.css (served unhashed in
  ;; dev); CSS is no longer rebuilt per .clj save.
  (start-tailwind-watch!)
  ;; Re-load view namespaces through tools.reader so Hiccup carries element-level
  ;; source metadata from boot (the inspector overlay reads it). Dev-only.
  (inspector-load/load-all-views!)
  (start-file-watcher)
  (log/info "Development environment ready"
    {:websocket-reload true
     :file-watcher true
     :watch-path "src + static"
     :database "Datomic"}))
```

`(start!)` from the REPL still brings up the whole system in one call -- now with the Tailwind process and the view preload folded in.

> **What the repo's `start` adds.** The listing is trimmed to this chapter's two additions. The repo's `start` also seeds a fresh dev database with the demo recipe graph (`seed/seed-if-empty!`, a no-op once recipes exist, so restarts never pile up duplicates) and, under the `:storm` alias only, enables the construction-view tracer -- the guarded block [the construction-view chapter](17-construction-view.md) added to this same function. Neither belongs to the reload loop, which is why they are elided here; `dev/hot_reload.clj` has the full body.

## The complete flow, per edit type

To see how the pieces fit, here is the end-to-end story for each kind of edit:

**You edit a view (`*views.clj`):**
1. The WatchService wakes on `ENTRY_MODIFY` under `src/`.
2. `load-changed-file` sees a `.clj` file; `inspector-load/reload-changed!` recognizes it as a view, loads it via `tools.reader`, and returns truthy.
3. `after-refresh` is called with `morphable? = true`, so `notify-reload!` sends `{:type "reload" :morphable true}`.
4. The browser's `morphReload` imports `dispatcher.js` and morphs the new `<body>` into place, sparing the marked dev overlays -- scroll, focus, and open `<details>` preserved. (If a class changed, Tailwind's watcher rebuilds CSS in parallel and a `css` message swaps the stylesheet a beat later.)

**You edit a handler or other non-view `.clj`:**
1. Same wake-up; `reload-changed!` returns nil, so `load-file` runs.
2. `after-refresh` is called with `morphable? = false`; `notify-reload!` sends `{:type "reload" :morphable false}`.
3. The browser stashes scroll and does a full `window.location.reload()`, then restores scroll on load.

**You edit a served `.js` module under `static/js/`:**
1. The WatchService wakes on the `static/` root.
2. `load-changed-file` hits the `asset-file?` branch -> `dev-reload/reload!` -> a non-morphable `reload`.
3. Full reload with scroll restore -- required, because an ES module is a URL-cached singleton (see above).

**Tailwind rebuilds `static/styles.css`:**
1. The long-lived Tailwind process rewrites the file (because a class appeared in your markup).
2. The WatchService sees the write; `load-changed-file` hits the `styles.css` branch -> `debounced-css-reload!`.
3. ~150ms after the writes settle, `notify-css!` sends `{:type "css"}`.
4. The browser swaps the stylesheet `<link>` with a cache-bust -- no reload, no flash.

The whole cycle -- from saving a file to seeing the updated page -- typically completes in a fraction of a second, and for the common view-edit case it does so *without losing any page state at all*.

## Design decisions worth noting

**Why decouple CSS from code reloads?** The argument is made in full above; what deserves stating here is the price. Decoupling means the stylesheet rebuild runs asynchronously to the code reload, and that asynchrony is what created the markup-before-CSS race the debounce exists to close. A 150ms settle window against a Tailwind run on every save is a good trade, but it is a trade all the same.

**Why reuse `fetchAndMorph` for the morph path?** Because a second, dev-only morph would be a divergent code path with its own edge cases. Riding the production dispatcher has its own cost, and it surfaced exactly once: the body morph needed a way to spare the client-injected overlays, so `fetchAndMorph` grew the `morphCallbacks` pass-through -- a generic option with, so far, a single caller. One extension point against a parallel implementation is the right side of that trade.

**Why `requiring-resolve` for dev/prod separation?** The pattern -- a guarded `requiring-resolve` of `'dev-reload/websocket-handler` -- is a runtime classpath check: the resolve throws when the namespace is absent, and the catch reads that failure as "not present". It is structurally impossible to accidentally enable dev reload in production -- the code simply is not there. The base layout uses the same check to decide whether to emit the dev scripts at all, so none of this advanced machinery leaks to a production page.

## Where this leaves the loop

The matrix is the argument: a save is not one thing, so each edit gets the lightest correct response. A view edit morphs the whole `<body>` in place, preserving scroll, focus, and open `<details>` while sparing the client-injected dev overlays; a `.js` module or a non-view `.clj` triggers the full reload an ES-module singleton actually requires; a Tailwind rebuild swaps the stylesheet `<link>` and nothing else, fired only once the rebuild settles. A failed reload raises a soft staleness banner rather than a silent stale page. The CSS watcher and the JVM share explicit lifecycle hardening, and the whole apparatus stays out of production by classpath separation -- so a loop this tight is paid for only in development and never shipped.
