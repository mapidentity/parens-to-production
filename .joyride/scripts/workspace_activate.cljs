(ns workspace-activate
  "Joyride workspace-activation script — the editor side of the dev source
  inspector. Joyride runs this automatically when the workspace opens (it can
  also be run via the command palette: \"Joyride: Run Workspace Script\").

  Holds one WebSocket to the app's dev server (/dev/ws) and:
    - SENDS  {type:\"hello\" role:\"editor\"} on connect (registers as the editor)
             and {type:\"cursor\" file line col} on (debounced) cursor moves, so
             the browser overlay highlights the matching element.
    - RECEIVES {type:\"open\" file line col} and opens that file at the range in
             this window via the vscode API (browser click -> editor jump).

  Hard-won notes about the VS Code extension host (Node 22 / undici WebSocket):
    1. Logging: async output (event handlers, timers) goes to the Extension Host
       DevTools console (Help -> Toggle Developer Tools), NOT the Joyride output
       channel — only top-level `println` shows there. Debug the channel in
       DevTools.
    2. undici reconnect semantics differ from the browser: a *dropped* established
       link fires only 'close' (code 1006); a *failed* connect attempt fires only
       'error'. So we reconnect on BOTH events (deduped) — listening to just one
       leaves us stuck whenever the other fires.
    3. NEVER call (.close) from an 'error'/'close' handler: undici runs the close
       path synchronously, re-firing the event into the same handler -> unbounded
       recursion -> stack overflow that silently wedges Joyride.

  Dev-only; runs entirely in the VS Code extension host."
  (:require
    ["vscode" :as vscode]))

(def ^:private ws-url
  "Loopback to http-kit (same container as the editor)."
  "ws://localhost:3000/dev/ws")

(def ^:private debounce-ms 80)

;; defonce so a re-eval (manual re-run / Joyride reload) keeps the live handles
;; and we can dispose them at the top of this script before reconnecting.
(defonce ^:private !state (atom {:ws nil :disposable nil :timer nil :reconnect nil :attempt 0}))

(defn- ws-send! [obj]
  (let [ws (:ws @!state)]
    (when (and ws (= 1 (.-readyState ws)))
      (.send ws (js/JSON.stringify (clj->js obj))))))

(defn- rel-path
  "Absolute fsPath -> classpath-relative key, e.g. .../src/myapp/web/views.clj
  -> myapp/web/views.clj. nil when the file isn't under a src/ dir."
  [fs-path]
  (when fs-path
    (when-let [m (re-find #"/src/(.+)$" fs-path)]
      (second m))))

(defn- open-file!
  "Open `file` (absolute) at 1-based `line`/`col` in this window, selecting and
  centering the position."
  [file line col]
  (let [uri (.file vscode/Uri file)
        pos (vscode/Position. (max 0 (dec (or line 1))) (max 0 (dec (or col 1))))
        sel (vscode/Selection. pos pos)
        rng (vscode/Range. pos pos)
        reveal (.. vscode -TextEditorRevealType -InCenter)]
    (-> (.openTextDocument vscode/workspace uri)
        (.then (fn [doc]
                 (.showTextDocument vscode/window doc #js {:selection sel :preserveFocus false})))
        (.then (fn [editor] (.revealRange editor rng reveal)))
        (.then identity (fn [err] (js/console.log "myapp inspector: open failed:" err))))))

(defn- send-current-cursor!
  "Read the active editor's cursor and report it — used to re-emit the reverse
  highlight when a browser (re)connects after a reload, without a cursor move."
  []
  (when-let [ed (.-activeTextEditor vscode/window)]
    (let [doc (.-document ed)
          sel (.-selection ed)
          line (inc (.. sel -active -line))
          col (inc (.. sel -active -character))
          file (rel-path (.. doc -uri -fsPath))]
      (when file
        (ws-send! {:type "cursor" :file file :line line :col col})))))

(defn- on-message [event]
  (try
    (let [m (js->clj (js/JSON.parse (.-data event)) :keywordize-keys true)]
      (case (:type m)
        "open" (open-file! (:file m) (:line m) (:col m))
        "resend-cursor" (send-current-cursor!)
        nil))
    (catch :default _ nil)))

(defn- on-selection
  "Debounced cursor reporter."
  [event]
  (let [doc (.-document (.-textEditor event))
        sel (aget (.-selections event) 0)
        line (inc (.. sel -active -line))
        col (inc (.. sel -active -character))
        file (rel-path (.. doc -uri -fsPath))]
    (when file
      (when-let [t (:timer @!state)] (js/clearTimeout t))
      (swap! !state assoc :timer
        (js/setTimeout
          #(ws-send! {:type "cursor" :file file :line line :col col})
          debounce-ms)))))

(defn- reconnect-delay
  "Bounded backoff: 2s, 4s, then capped at 8s, so a permanently-down dev server
  doesn't reconnect-spin while still recovering quickly after a restart."
  [attempt]
  (min 8000 (* 1000 (bit-shift-left 1 (min attempt 3)))))

(declare connect!)

(defn- schedule-reconnect!
  "Schedule one reconnect for `ws` if it's still the current socket. undici fires
  ONLY 'close' when an established link drops, but ONLY 'error' when a connect
  attempt fails (server still booting) — so both events route here. The
  current-socket check plus nilling :ws dedupe to exactly one retry per socket.
  Never call (.close) from a handler — in undici that re-enters the failure path
  synchronously and stack-overflows."
  [ws]
  (when (= ws (:ws @!state))
    (let [attempt (inc (:attempt @!state 0))]
      (swap! !state assoc :ws nil :attempt attempt
        :reconnect (js/setTimeout (fn [] (connect!)) (reconnect-delay attempt))))))

(defn- connect! []
  ;; Tear down any prior socket / pending reconnect first so retries never stack.
  ;; Only close a socket that's OPEN/CONNECTING (readyState 0/1) — calling .close
  ;; on an already-closing/closed undici socket can re-enter its failure path.
  (let [{:keys [ws reconnect]} @!state]
    (when reconnect (js/clearTimeout reconnect))
    (when (and ws (< (.-readyState ws) 2)) (try (.close ws) (catch :default _ nil))))
  (js/console.log "myapp inspector: connecting to" ws-url)
  (try
    (let [ws (js/WebSocket. ws-url)]
      (swap! !state assoc :ws ws :reconnect nil)
      (.addEventListener ws "open"
        (fn [_]
          (ws-send! {:type "hello" :role "editor"})   ; register as editor up front
          (swap! !state assoc :attempt 0)
          (js/console.log "myapp inspector: connected (editor registered)")))
      (.addEventListener ws "message" on-message)
      (.addEventListener ws "close" (fn [_] (schedule-reconnect! ws)))
      (.addEventListener ws "error" (fn [_] (schedule-reconnect! ws))))
    (catch :default e
      (js/console.log "myapp inspector: connect error —" (.-message e))
      (let [attempt (inc (:attempt @!state 0))]
        (swap! !state assoc :ws nil :attempt attempt
          :reconnect (js/setTimeout (fn [] (connect!)) (reconnect-delay attempt)))))))

(defn- cleanup! []
  (let [{:keys [ws disposable timer reconnect]} @!state]
    (when disposable (.dispose disposable))
    (when timer (js/clearTimeout timer))
    (when reconnect (js/clearTimeout reconnect))
    (when ws (try (.close ws) (catch :default _ nil))))
  (reset! !state {:ws nil :disposable nil :timer nil :reconnect nil :attempt 0}))

;; --- activate (idempotent across re-evals) ---
(cleanup!)
(swap! !state assoc :disposable (.onDidChangeTextEditorSelection vscode/window on-selection))
(connect!)
(println "myapp inspector: editor↔dev-server channel active on" ws-url)
