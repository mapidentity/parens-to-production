# Live Reload: A File Watcher and WebSocket Browser Refresh

The REPL closes the feedback gap for return values: you change a function, evaluate it, and the result appears in your editor. But a web application has a second surface that the REPL does not touch -- the browser. "Seeing the result" of a markup change or a handler change means the *page* updating, not a value printing. So the question for a server-rendered app is: how do you close the gap between saving a file and seeing it reflected in the browser, without leaving your flow state?

There are three honest answers.

**(a) Refresh by hand.** Save, alt-tab to the browser, hit reload. It works, and it costs you a context switch on every single edit. Over a day of iterating on markup that tax is enormous, and worse, it pulls you out of the editor every few seconds. We can do better.

**(b) `clojure.tools.namespace` full refresh.** The standard reloaded workflow scans the source tree, computes the dependency graph, and reloads each changed namespace *and everything that depends on it*, in dependency order. The scan is the cheap part. The cost is the cascade: an edit near the root of the graph reloads everything downstream, and the teardown-and-rebuild can wipe `defonce` state you were relying on. But the cascade is a correctness guarantee. Reloading dependents is what keeps code compiled against your macros and protocols in step with their new definitions. Hold on to that, because it is what the next option gives up.

**(c) A targeted file watcher plus a WebSocket push.** Watch the source tree. When one file changes, load *exactly that file* and tell the browser to refresh. No dependency graph, no cascade, no `defonce` reset: just the one file you actually touched, and a message down a socket.

(c) is what this chapter builds, and the trade deserves to be stated, because what it gives up is what (b)'s cascade bought. `load-file` compiles one file in isolation; anything already compiled against the old definitions stays compiled against them. Edit a macro and its call sites keep running the old expansion until their own namespaces reload. Reload a file containing a `defprotocol` and every implementation compiled against the old protocol object is orphaned: calls fail with `No implementation of method` even though the code on disk is fine. Rename or delete a var and the old one stays interned, so stale references keep working in this JVM and break on the next one. And `load-file` knows nothing about dependency order. These failure modes are the reason `tools.namespace` exists.

For a server-rendered app the trade is still right, because the edits it mishandles are rare and the edit it optimizes is nearly every save you make. The overwhelming majority of development edits change function bodies and markup, and for those recompiling the one file is fully correct: tweak a handler, save, see the page -- a single `load-file` and a single socket message, both effectively instantaneous on Linux (a platform caveat the timing section returns to). Macro and protocol edits are the exception, so we keep option (b) available as a manual command instead of paying its cascade on every save; the next section says where it lives. That is the system this chapter builds: a Java NIO file watcher that detects the change and reloads the one file, and a WebSocket connection that tells the browser to reload itself.

## dev/ classpath separation

Before any of the watcher code, one structural decision underpins all of it: none of this infrastructure can be allowed to reach production. The classpath gives us that guarantee.

All of the dev tooling lives in a `dev/` directory that is on the classpath *only* in development, via a `deps.edn` alias:

```clojure
;; deps.edn (excerpt)
:aliases
{:dev {:extra-paths ["dev"]
       :extra-deps {org.clojure/tools.namespace {:mvn/version "1.5.1"}
                    org.clojure/tools.reader {:mvn/version "1.5.0"}
                    ring/ring-devel {:mvn/version "1.15.3"}
                    nrepl/nrepl {:mvn/version "1.5.1"}
                    cider/cider-nrepl {:mvn/version "0.58.0"}}}}
```

`tools.reader` is not used by the watcher; it feeds the source inspector's loader ([the inspector](16-inspector.md)), which reads view files form-by-form. `tools.namespace` is the full-refresh library from option (b) above. The reload loop does not use it, but it stays on the dev classpath for exactly the edits the per-file loop mishandles: a changed macro or protocol, a renamed or deleted var, or any moment running behavior stops matching the code on disk. In those cases, run its `refresh` from the REPL and let it reload the dependents the watcher skipped. `ring-devel` is Ring's own development bundle; its `wrap-reload` middleware is the conventional answer to this chapter's question -- reload changed namespaces when the *next request* arrives. That pull model closes only half the gap: the server catches up, but nothing tells the browser to ask. Nothing in this repo ever requires it; it stays in the alias as the standard alternative to measure this chapter against, and deleting the line breaks nothing. None of the three ships in production.

Because `dev/` is an extra path, the namespaces in it -- `hot-reload`, `dev-reload`, `user` -- exist on the classpath when you develop with the `:dev` alias and do not exist at all in a production build. This is a structural fact about what code is present, deeper than a convention or a runtime flag. We will lean on it twice: the WebSocket route and the browser-side script both check, at runtime, whether the dev namespace can be resolved, and both become inert when it cannot. A guarded `requiring-resolve` (resolve, and treat failure as absence) is the same guarantee viewed from the calling side.

## The file watcher

The core of the system is built on Java NIO's `WatchService`. It monitors the `src/` tree and reacts when a file is modified.

```clojure
(ns hot-reload
  "A namespace for hot code reloading during development."
  (:require
    [clojure.tools.logging :as log]
    [dev-reload :as dev-reload]
    [myapp.core :as core])
  (:import
    [java.nio.file FileSystems Files Path StandardWatchEventKinds WatchEvent]
    [java.nio.file.attribute BasicFileAttributes]
    [java.util.function BiPredicate]))

(set! *warn-on-reflection* true)

(defonce ^{:doc "Holds the active file watcher state, or nil."} file-watcher (atom nil))
```

The `file-watcher` atom holds the WatchService and the daemon thread that polls it. It is a `defonce` so that reloading this namespace does not restart the watcher out from under you -- you start and stop it explicitly via the functions below.

### Detecting and loading a changed file

For now there is one thing we care about: a changed `.clj` file. A small predicate identifies it:

```clojure
(defn- clj-file?
  "Returns true if the path has a .clj extension."
  [^java.nio.file.Path path]
  (.endsWith (str path) ".clj"))
```

When a `.clj` file changes, we load it and then tell the browser to do a full reload:

```clojure
(defn- load-changed-file
  "Loads a changed file."
  [{:keys [event-type path]}]
  (when (= event-type :modify)
    (when (clj-file? path)
      (let [file-path (str path)
            start-time (System/nanoTime)]
        (log/info "File changed" {:file-path file-path})
        (try
          (load-file file-path)
          (dev-reload/notify-reload!)
          (let [duration-seconds (/ (- (System/nanoTime) start-time) 1e9)]
            (log/info "Successfully reloaded file"
              {:file-path file-path
               :duration-seconds duration-seconds}))
          (catch Exception e
            (let [duration-seconds (/ (- (System/nanoTime) start-time) 1e9)]
              (log/error e "Error reloading file"
                {:file-path file-path
                 :duration-seconds duration-seconds}))))))))
```

We `load-file` the one path that changed -- not a tree refresh, just that file. We then call `dev-reload/notify-reload!`, which pushes a reload message to every connected browser. The whole thing is wrapped in a timing block: during development you want to know how long a reload takes, and if it ever creeps above a fraction of a second something is wrong and you want to catch it early.

One honest limit on that number: the timer starts when the watcher *receives* an event, so it measures load-and-notify, not save-to-detect. On Linux (the devcontainer's world, where inotify delivers events in milliseconds) the two are close and the figure means what it says. On a macOS *host* they can diverge: the JDK ships no native FSEvents `WatchService`, so it falls back to a polling watcher whose latency is measured in seconds, and events for host-edited, bind-mounted files can arrive late or not at all across the Docker boundary. Editing inside the container keeps reloads fast; editing on the host and syncing in is where a "fast" timing number can hide a slow save-to-screen, and the fix is a native-FSEvents watcher (`io.methvin/directory-watcher`) rather than the raw `WatchService`.

The failure path is the important detail. A broken file -- a syntax error, an unbalanced paren -- does not crash the watcher. The exception is caught and logged, the watcher keeps running, and your next save gets a fresh chance to succeed. A crash-on-error watcher that you have to restart every time you fat-finger a paren would defeat the point.

> This `load-changed-file` is deliberately the *basic* path: one branch, full reload. Later chapters grow the one branch into several and hang hooks around the load, but the detect-load-notify-catch shape survives unchanged.

### Registering directories and the watch loop

The `WatchService` works at the directory level: you register directories, not individual files, and you get events for the files inside them. So on startup we walk the `src/` tree and register every subdirectory.

```clojure
(defn start-file-watcher
  "Start file watcher using Java NIO WatchService."
  []
  (when @file-watcher (stop-file-watcher))
  (let [ws (.newWatchService (FileSystems/getDefault))
        kinds (into-array
                [StandardWatchEventKinds/ENTRY_MODIFY
                 StandardWatchEventKinds/ENTRY_CREATE])
        _ (doseq [r ["src"]
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
        thread (Thread.
                 (fn []
                   (try
                     (loop []
                       (when-let [watch-key (.take ws)]
                         (doseq [^WatchEvent event (.pollEvents watch-key)]
                           (let [^Path changed (.context event)
                                 ^Path dir (.watchable watch-key)
                                 full-path (.resolve dir changed)]
                             (when
                               (Files/isDirectory full-path (make-array java.nio.file.LinkOption 0))
                               (.register full-path ws kinds))
                             (load-changed-file
                               {:event-type :modify
                                :path full-path})))
                         (.reset watch-key)
                         (recur)))
                     (catch java.nio.file.ClosedWatchServiceException _)
                     (catch Exception e (log/error e "File watcher error")))))]
    (.setDaemon thread true)
    (.start thread)
    (reset! file-watcher
      {:watch-service ws
       :thread thread})
    (log/info "File watcher started" {:watch-path "src"})))
```

The details worth calling out -- the first three are the `java.nio.file` interop a reader new to it is most likely to stall on:

- **Registering the tree means walking it first.** A `WatchService` watches *directories*, not whole trees, so before the loop can run we enumerate every subdirectory under `src/` and `.register` each one. `Files/find` does the walk; its filter argument is a Java `BiPredicate` of `(path, attrs)`, which Clojure has no literal for, so we `reify` one and keep only entries whose `BasicFileAttributes` report `isDirectory`.
- **The empty `(make-array …)` calls pass "no varargs" across interop.** `Files/find` and `Files/isDirectory` both end in a Java varargs parameter (`FileVisitOption…`, `LinkOption…`). Clojure does not splat into Java varargs, so the way to pass *none* is to hand over a zero-length array of exactly that element type -- `(make-array java.nio.file.FileVisitOption 0)`. It reads oddly the first time; it is just the standard shim for an empty varargs tail.
- **An event carries a name, not a path.** A `WatchEvent`'s `.context` is the changed entry *relative to the directory the key was registered on*, so we recover the absolute path by taking the key's `.watchable` (that directory) and `.resolve`-ing the context against it. If the new entry is itself a directory, the loop `.register`s it on the spot -- which is what lets a brand-new namespace package be watched without a restart. There is an inherent race here: a file created inside that directory in the same instant, before the `.register` lands, is missed. In a dev file-watcher that is a non-issue: you save the new file a moment later and that event is caught. But it is the reason this pattern is not a basis for anything that must observe *every* change.
- **We register both `ENTRY_MODIFY` and `ENTRY_CREATE`.** Modify is the everyday save; create is what fires when a new file or directory appears, so the watcher reacts to additions, not just edits. Notice how the two listings meet, though: the loop tags *every* event `{:event-type :modify}`, creates included, because a brand-new `.clj` file wants the response an edited one gets -- load it, refresh the browser. The `(when (= event-type :modify))` guard in `load-changed-file` therefore never rejects anything; it records the shape of the event map and filters nothing.
- **A single save can fire several events, and we let it.** Editors often write a file in more than one step (truncate, write, rename, touch the mtime), so one save can surface as two or three `ENTRY_MODIFY` events, each triggering a reload. We deliberately don't debounce: a redundant `load-file` plus browser refresh costs a few milliseconds in dev, and the coalescing machinery would be more code than the duplication is worth. A busier watcher, or a more expensive per-change action, would want to collect events on a short timer and act once after the burst -- which is what the CSS hot-swap in [the morph-reload chapter](19-morph-reload.md) does for its own reason.
- **The poll thread is a daemon thread.** It dies when the JVM exits, so a forgotten watcher never keeps the process alive.
- **`.take` is blocking.** The thread sleeps until there is an event, consuming zero CPU while idle.
- **`ClosedWatchServiceException` is caught silently.** This is the normal shutdown path: when we close the WatchService, the blocking `.take` throws this exception, the loop exits, and the thread ends.

We watch `src/` here. [The morph-reload chapter](19-morph-reload.md) later adds the `static/` tree as a second root, so built assets trigger refreshes too -- but for code reload, `src/` is all we need.

Stopping the watcher is the mirror image:

```clojure
(defn stop-file-watcher
  "Stop the file watcher."
  []
  (when-let [{:keys [^java.nio.file.WatchService watch-service]} @file-watcher]
    (.close watch-service)
    (reset! file-watcher nil)
    (log/info "File watcher stopped")))
```

Closing the WatchService makes the daemon thread's blocking `.take` throw the `ClosedWatchServiceException` we catch, so the thread unwinds on its own.

## WebSocket browser refresh

The watcher knows *that* a file changed. The browser needs to be *told*. That is the job of the `dev-reload` namespace: it holds the set of connected browser sockets and broadcasts messages to them.

```clojure
(ns dev-reload
  (:require
    [clojure.tools.logging :as log]
    [jsonista.core :as json]
    [org.httpkit.server :as http-kit]))

(set! *warn-on-reflection* true)

;; defonce so reloading this ns keeps live connections instead of orphaning them
;; (the running channels would otherwise be lost to a fresh empty atom).
(defonce websocket-clients
  ;; All connected dev clients (browser tabs).
  (atom #{}))
```

That `jsonista.core` require is the one new dependency this namespace pulls in: jsonista is a fast JSON codec. The trivial health response in [the web-server chapter](05-web-server.md) used `clojure.data.json` to serialize a one-off status map; the dev tooling is the first place the project reaches for jsonista. Add `metosin/jsonista {:mvn/version "0.3.12"}` to the top-level `:deps` map in `deps.edn` -- top-level, not `:dev`-only, because the application leans on it later too. With that in place, `(require '[jsonista.core :as json])` resolves and the namespace compiles.

`websocket-clients` is a set of http-kit channels, one per connected browser tab. It is a `defonce` for the same reason the watcher atom is: reloading the `dev-reload` namespace must preserve your live connections; a fresh empty atom would orphan them and leave every open tab silently disconnected.

The reload notification is a single typed JSON message:

```clojure
(defn notify-reload!
  "Tell every connected browser to do a full reload."
  []
  (send-json! @websocket-clients {:type "reload"}))
```

The send itself has a sharp edge worth handling carefully. http-kit's `send!` returns `false` for a channel that has already closed -- *without throwing*. A naive broadcast that ignored the return value would accumulate dead channels in the set forever. So `send-json!` prunes a client on both a `false` return and a thrown exception:

```clojure
(defn- send-json!
  "Send `msg` (a map) to `channels`. http-kit's send! returns false for a closed
  channel WITHOUT throwing, so prune on both false and exceptions. Returns the
  number of channels the message was actually delivered to."
  [channels msg]
  (let [s (json/write-value-as-string msg)]
    (reduce (fn [n client]
              (if (try
                    (http-kit/send! client s)
                    (catch Exception e
                      (log/warn e "Failed to send dev message to client")
                      false))
                (inc n)
                (do (remove-client! client) n)))
            0
            channels)))

(defn remove-client!
  "Forget a disconnected client."
  [channel]
  (swap! websocket-clients disj channel))
```

It returns the count of channels actually reached, which is handy for logging and, later, for callers that want to know whether anything was listening. (These three are shown caller-first to follow the narrative; in the source file the helpers are defined before `notify-reload!`, so the namespace loads cleanly top to bottom.)

For driving a reload by hand from the REPL, there is a plain trigger:

```clojure
(defn reload!
  "Manual reload trigger for REPL usage."
  []
  (log/info "Manual reload triggered")
  (notify-reload!))
```

The socket endpoint itself accepts a connection, registers it, and forgets it on close:

```clojure
(defn add-client!
  "Register a newly connected client."
  [channel]
  (swap! websocket-clients conj channel))

(defn websocket-handler
  "Sets up a /dev/ws connection."
  [request]
  (http-kit/as-channel
    request
    {:on-open  (fn [channel]
                 (log/debug "Dev WS client connected")
                 (add-client! channel)
                 (http-kit/send! channel (json/write-value-as-string {:type "connected"})))
     :on-close (fn [channel status]
                 (log/debug "Dev WS client disconnected" {:status status})
                 (remove-client! channel))
     :on-error (fn [channel throwable]
                 (log/error throwable "Dev WS error")
                 (remove-client! channel))}))
```

### Wiring the route -- and keeping it out of production

The `/dev/ws` route resolves its handler at request time with `requiring-resolve`. One sharp edge matters here: for a namespace that is not on the classpath, `requiring-resolve` does not return nil (the `require` inside it throws), so the guard must catch, and read the failure as absence:

```clojure
;; In the route definitions
["/dev/ws"
 {:get (fn [request]
         (if-let [handler (try (requiring-resolve 'dev-reload/websocket-handler)
                               (catch Throwable _ nil))]
           (handler request)
           {:status 404}))}]
```

The route entry exists in the route table in every build, but in production the `dev-reload` namespace is absent, the resolve fails, the catch converts the failure to nil, and the endpoint returns 404. There is no dev WebSocket server in production because there is no handler to run.

The same guard governs whether the browser even loads the dev script. The base layout emits the `dev-reload.js` `<script>` only when the dev namespace resolves:

```clojure
;; In base-layout, at the end of <body>:
(when (try
        (requiring-resolve 'dev-reload/websocket-handler)
        (catch Exception _ nil))
  (dev-reload-script))
```

In production the guard yields nil and no dev `<script>` appears in the rendered HTML. The entire client side of this system is structurally absent from a production page. One asymmetry between the two guards is incidental rather than meaningful: the route catches `Throwable`, the layout `Exception`. A namespace missing from the classpath surfaces as a `java.io.FileNotFoundException`, which both spellings catch, and either reads the failure as absence; nothing hangs on the difference.

## The client side

The browser-side script (`src/myapp/web/dev-reload.js`) is small: open the WebSocket, and on a `reload` message reload the page. The one nicety is that it stashes your scroll position before reloading and restores it after, so a full reload keeps your place rather than snapping you to the top.

```javascript
const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/dev/ws');
ws.onmessage = function (event) {
  const data = JSON.parse(event.data);
  if (data.type === 'reload') {
    // Stash scroll so the reload doesn't lose your place.
    try { sessionStorage.setItem('myapp-dev-scroll', String(window.scrollY)); } catch (e) {}
    window.location.reload();
  }
};

// Restore scroll after a dev reload (stashed just before reloading).
try {
  var savedScroll = sessionStorage.getItem('myapp-dev-scroll');
  if (savedScroll !== null) {
    sessionStorage.removeItem('myapp-dev-scroll');
    window.addEventListener('load', function () { window.scrollTo(0, parseInt(savedScroll, 10) || 0); });
  }
} catch (e) {}

ws.onopen = function () { console.log('Dev reload WebSocket connected'); };
ws.onerror = function (error) { console.log('WebSocket error:', error); };
```

The scroll stash goes into `sessionStorage` -- it has to survive the page navigation that `reload()` causes, so an in-memory variable would not do. On the next page load we read it back, scroll to it, and clear it. The full reload is correct and total: the whole page comes back fresh, just where you left it vertically.

The script opens its socket exactly once: there is no `onclose` handler and no reconnect. The omission is deliberate, and its cost is real. Restart the server (a routine REPL event) and every open tab's socket dies silently; live reload in those tabs stays dead until you refresh them by hand. The retry timer itself would be a few lines, but a correct one is not. Naive infinite retries leave every tab from yesterday's session hammering a dead port, so you owe backoff and a cap. And a tab that reconnects has still missed whatever was pushed while it was down, so it may already be stale. Fixing *that* means reloading on every reconnect, which turns each server restart into every open tab reloading at a moment you did not choose. One manual refresh per restart reconnects the socket and freshens the page in the same gesture. That is cheaper than the machinery, and the final client in the repo makes the same call.

## The REPL entry point

The last piece ties it together into a single command. `user.clj` loads when you start a REPL and exposes a `start!`:

```clojure
(ns user
  "Development REPL helpers"
  (:require
    [hot-reload]))

(defn start! [] (hot-reload/start))
(defn reload! [] (hot-reload/reload!))
```

`hot-reload/reload!` is a one-line delegator to the `dev-reload/reload!` trigger shown earlier: `user` requires only `hot-reload`, and `hot-reload` already requires `dev-reload`, so the manual trigger rides that require chain rather than adding another.

This is the rewiring the [web-server chapter](05-web-server.md) promised: `start!` now goes through `hot-reload/start` instead of calling `core/start-server!` directly. That entry point calls a small new function in `myapp.core` -- `start-dev-server`, which sets the `myapp.dev` system property and then delegates to the same `start-server!` from the previous chapter (the dev-only affordances later chapters add gate on a classpath resource, not on this property):

```clojure
;; added to myapp.core
(defn start-dev-server
  "Start the server in development mode."
  []
  (System/setProperty "myapp.dev" "true")
  (start-server!))
```

And `hot-reload/start` brings up the server and the watcher:

```clojure
(defn start
  "Run this from the REPL to start developing."
  []
  (core/start-dev-server)
  (log/info "Development server started" {:url "http://localhost:3000"})
  (start-file-watcher)
  (log/info "Development environment ready"
    {:websocket-reload true
     :file-watcher true
     :watch-path "/src"}))
```

Open a REPL, type `(start!)`, and the whole loop is live: the server is up, the watcher is watching `src/`, and any connected browser tab will reload when you save. (The two log lines label the same tree differently, `"src"` from the watcher and `"/src"` in this summary map; the strings are display labels, and nothing reads them.)

## Where this goes next

What we have is correct and useful: save a file, the browser reloads, you keep your scroll position. But a full reload is a blunt instrument. It throws away *all* page state -- focus, in-progress form input, open `<details>` -- and rebuilds the page from scratch even when the edit was a one-line markup tweak. That is fine for now, when every edit is a `.clj` file and the only response we have is "reload the page."

Once we have server-rendered Hiccup views with a client-side morphing layer, a source inspector, and a real asset pipeline with Tailwind, this single full-reload becomes the *wrong* default for the common case. A later chapter upgrades this one branch into a per-edit *delivery matrix*: a view edit morphs the live DOM in place (keeping scroll, focus, and open `<details>`), a CSS rebuild hot-swaps the stylesheet without reloading at all, and only the edits that genuinely require it fall back to a full reload. The watcher and socket you built here are the foundation; the matrix is the refinement.
