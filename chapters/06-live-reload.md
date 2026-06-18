# Live Reload: A File Watcher and WebSocket Browser Refresh

The REPL closes the feedback gap for return values: you change a function, evaluate it, and the result appears in your editor. But a web application has a second surface that the REPL does not touch -- the browser. "Seeing the result" of a markup change or a handler change means the *page* updating, not a value printing. So the question for a server-rendered app is: how do you close the gap between saving a file and seeing it reflected in the browser, without leaving your flow state?

There are three honest answers, and only one of them is good.

**(a) Refresh by hand.** Save, alt-tab to the browser, hit reload. It works, and it costs you a context switch on every single edit. Over a day of iterating on markup that tax is enormous, and worse, it pulls you out of the editor every few seconds. We can do better.

**(b) `clojure.tools.namespace` full refresh.** The standard reloaded workflow scans the whole source tree, computes the dependency graph, and reloads changed namespaces in dependency order. It is powerful, but it is also slow (it walks everything, every time) and occasionally surprising -- it tears down and rebuilds namespaces, which can wipe `defonce` state you were relying on. For a large app where you might edit one file, that is a lot of machinery and a lot of risk for one save.

**(c) A targeted file watcher plus a WebSocket push.** Watch the source tree. When one file changes, load *exactly that file* and tell the browser to refresh. No tree scan, no dependency graph, no `defonce` reset -- just the one file you actually touched, and a message down a socket.

For a server-rendered app where you edit one file at a time, (c) wins decisively. The common case -- tweak a handler, save, see the page -- is a single `load-file` and a single socket message, both effectively instantaneous. We do not pay for the rest of the tree because we never look at it. That is the system this chapter builds: a Java NIO file watcher that detects the change and reloads the one file, and a WebSocket connection that tells the browser to reload itself.

## dev/ classpath separation

Before any of the watcher code, one structural decision underpins all of it: none of this infrastructure can be allowed to reach production. We get that guarantee for free from the classpath.

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

Two of those deps look out of place this early. `tools.reader` is not used by the watcher at all -- it is here for the source inspector's loader a few chapters on ([the inspector](12-inspector.md)), which reads view files form-by-form; we list it now so the `:dev` alias does not churn later. `tools.namespace` is the full-refresh library from option (b) above: we do not build the reload loop on it, but it stays available for the occasional manual `refresh` at the REPL. Neither ships in production.

Because `dev/` is an extra path, the namespaces in it -- `hot-reload`, `dev-reload`, `user` -- exist on the classpath when you develop with the `:dev` alias and do not exist at all in a production build. This is not a convention or a runtime flag; it is a structural fact about what code is present. We will lean on it twice: the WebSocket route and the browser-side script both check, at runtime, whether the dev namespace can be resolved, and both become inert when it cannot. `requiring-resolve` returning nil in production is the same guarantee viewed from the calling side.

## The File Watcher

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

For now there is exactly one thing we care about: a changed `.clj` file. A cheap predicate identifies it:

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

A few things to note. We `load-file` the one path that changed -- not a tree refresh, just that file. We then call `dev-reload/notify-reload!`, which pushes a reload message to every connected browser. The whole thing is wrapped in a timing block: during development you want to know how long a reload takes, and if it ever creeps above a fraction of a second something is wrong and you want to catch it early.

The failure path is the important detail. A broken file -- a syntax error, an unbalanced paren -- does not crash the watcher. The exception is caught and logged, the watcher keeps running, and your next save gets a fresh chance to succeed. A crash-on-error watcher that you have to restart every time you fat-finger a paren would defeat the entire purpose.

> This `load-changed-file` is deliberately the *basic* path: one branch, full reload. Once we have real server-rendered views and an asset pipeline, a later chapter widens this `cond` so that different edits get different responses -- but the skeleton is exactly this.

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

The details worth calling out:

- **We register both `ENTRY_MODIFY` and `ENTRY_CREATE`.** When you create a new directory under `src/` -- a new namespace package -- the watch loop sees the create event, notices it is a directory, and registers it too. You never have to restart the watcher to pick up newly added namespaces.
- **The poll thread is a daemon thread.** It dies when the JVM exits, so a forgotten watcher never keeps the process alive.
- **`.take` is blocking.** The thread sleeps until there is an event, consuming zero CPU while idle.
- **`ClosedWatchServiceException` is caught silently.** This is the normal shutdown path: when we close the WatchService, the blocking `.take` throws this exception, the loop exits, and the thread ends.

We watch `src/` here. A later chapter, once the asset pipeline enters the picture, adds the `static/` tree as a second root so that built assets trigger refreshes too -- but for code reload, `src/` is all we need.

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

Closing the WatchService makes the daemon thread's blocking `.take` throw the `ClosedWatchServiceException` we catch, so the thread unwinds on its own. Clean and simple.

## WebSocket Browser Refresh

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

`websocket-clients` is a set of http-kit channels, one per connected browser tab. It is a `defonce` for the same reason the watcher atom is: reloading the `dev-reload` namespace must keep your live connections, not orphan them into a fresh empty atom and leave every open tab silently disconnected.

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

It returns the count of channels actually reached, which is handy for logging and, later, for callers that want to know whether anything was listening.

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

The `/dev/ws` route is registered with `requiring-resolve`, which returns nil when the `dev-reload` namespace is not on the classpath:

```clojure
;; In the route definitions
["/dev/ws"
 {:get (fn [request]
         (if-let [handler (requiring-resolve 'dev-reload/websocket-handler)]
           (handler request)
           {:status 404}))}]
```

The route entry exists in the route table in every build, but in production the `dev-reload` namespace is absent, `requiring-resolve` yields nil, and the endpoint returns 404. There is no dev WebSocket server in production because there is no handler to run.

The same guard governs whether the browser even loads the dev script. The base layout emits the `dev-reload.js` `<script>` only when the dev namespace resolves:

```clojure
;; In base-layout, at the end of <body>:
(when (try
        (requiring-resolve 'dev-reload/websocket-handler)
        (catch Exception _ nil))
  (dev-reload-script))
```

In production the guard yields nil and no dev `<script>` appears in the rendered HTML. The entire client side of this system is structurally absent from a production page.

## The Client Side

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

## The REPL Entry Point

The last piece ties it together into a single command. `user.clj` loads when you start a REPL and exposes a `start!`:

```clojure
(ns user
  "Development REPL helpers"
  (:require
    [myapp.core :as core]
    [hot-reload]))

(defn start! [] (hot-reload/start))
(defn reload! [] (hot-reload/reload!))
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

Open a REPL, type `(start!)`, and the whole loop is live: the server is up, the watcher is watching `src/`, and any connected browser tab will reload when you save. A later chapter adds two more steps to this same `start` -- a long-lived Tailwind watcher and a one-time view preload -- once those concerns exist.

## Design Decisions

**`load-file` instead of `tools.namespace`.** We load exactly the one file that changed rather than running `clojure.tools.namespace.repl/refresh`. The tools.namespace approach scans the whole source tree and reloads in dependency order, which is powerful but slow and occasionally surprising -- it can wipe `defonce` state. For a server-rendered app where you edit one handler or view at a time, loading just that file is faster and more predictable, and it never resets the state we deliberately hold in `defonce`.

**`requiring-resolve` for dev/prod separation.** `(requiring-resolve 'dev-reload/websocket-handler)` is a runtime classpath check that returns nil when the namespace is absent. This is more reliable than an environment variable or a config flag because it is *structurally impossible* to accidentally enable dev reload in production -- the code simply is not on the classpath. The route uses it to decide whether to serve the socket; the layout uses it to decide whether to emit the script. One mechanism, applied on both sides.

## Where This Goes Next

What we have is correct and useful: save a file, the browser reloads, you keep your scroll position. But a full reload is a blunt instrument. It throws away *all* page state -- focus, in-progress form input, open `<details>` -- and rebuilds the page from scratch even when the edit was a one-line markup tweak. That is fine for now, when every edit is a `.clj` file and the only response we have is "reload the page."

Once we have server-rendered Hiccup views with a client-side morphing layer, a source inspector, and a real asset pipeline with Tailwind, this single full-reload becomes the *wrong* default for the common case. A later chapter upgrades this one branch into a per-edit *delivery matrix*: a view edit morphs the live DOM in place (keeping scroll, focus, and open `<details>`), a CSS rebuild hot-swaps the stylesheet without reloading at all, and only the edits that genuinely require it fall back to a full reload. The watcher and socket you built here are the foundation; the matrix is the refinement.
