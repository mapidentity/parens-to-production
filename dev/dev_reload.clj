(ns dev-reload
  "Development-only dev channel between the browser overlay, the editor (Joyride), and this server.

  One `/dev/ws` socket per peer; the server is a small relay hub:
    - browser  -> {type:\"open\" src line col}  -> open the file in the editor
    - editor   -> {type:\"cursor\" file line col} -> highlight the element in the browser
    - server   -> {type:\"reload\"}               -> live-reload the browser
  Clients self-identify: a browser is the default; an editor sends
  {type:\"hello\" role:\"editor\"} on connect (and any {type:\"cursor\"}).

  Opens are delivered to a connected editor via the vscode API (Joyride). With
  no editor connected, an open reports failure — there is no `code -g` fallback
  (it required a fragile VSCODE_IPC_HOOK_CLI / newest-socket workaround that the
  editor-push path replaced)."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [jsonista.core :as json]
    [myapp.web.inspector :as inspector]
    [org.httpkit.server :as http-kit])
  (:import
    [java.io File]))

(set! *warn-on-reflection* true)

;; defonce so reloading this ns keeps live connections instead of orphaning them
;; (the running channels would otherwise be lost to a fresh empty atom).
(defonce ^{:doc "All connected dev clients (browser tabs + the editor)."} websocket-clients
  (atom #{}))

(defonce
  ^:private
  ^{:doc "Channel -> role (:browser default, :editor). Lets the relay route messages."} client-roles
  (atom {}))

(defn add-client!
  "Register a newly connected client (defaults to the :browser role)."
  [channel]
  (swap! websocket-clients conj channel))

(defn remove-client!
  "Forget a disconnected client and its role."
  [channel]
  (swap! websocket-clients disj channel)
  (swap! client-roles dissoc channel))

(defn- set-role!
  "Record the role of a connected client (browser or editor)."
  [channel role]
  (swap! client-roles assoc channel role))

(defn- clients-of
  "Connected channels with `role` (channels with no recorded role count as :browser)."
  [role]
  (filter #(= role (get @client-roles % :browser)) @websocket-clients))

(defn- send-json!
  "Send `msg` (a map) to `channels`.
  http-kit's send! returns false for a closed
  channel WITHOUT throwing, so prune on both false and exceptions. Returns the
  number of channels the message was actually delivered to."
  [channels msg]
  (let [s (json/write-value-as-string msg)]
    (reduce
      (fn [n client]
        (if
          (try
            (http-kit/send! client s)
            (catch Exception e (log/warn e "Failed to send dev message to client") false))
          (inc n)
          (do (remove-client! client) n)))
      0
      channels)))

(defn notify-reload!
  "Tell every browser client to reload.
  `morphable?` (default false) lets the browser
  take the state-preserving morph fast path (a view-ns edit) instead of a full
  reload (non-view .clj, .js, or a manual trigger)."
  ([] (notify-reload! false))
  ([morphable?]
   (send-json!
     (clients-of :browser)
     {:type "reload"
      :morphable (boolean morphable?)})))

(defn notify-css!
  "Tell browsers to hot-swap the stylesheet <link> (no reload).
  The dev CSS URL is
  stable, so the browser cache-busts it to refetch the rebuilt file."
  []
  (send-json! (clients-of :browser) {:type "css"}))

(defn notify-reload-error!
  "Tell browsers a source file failed to (re)load (a syntax error or similar), so the page can warn it MAY be stale.
  We can't know the current page actually uses
  the broken file — it could be an unrelated reload — hence the soft 'may be'. A
  later successful reload navigates the page and clears the warning on its own."
  [file error]
  (send-json!
    (clients-of :browser)
    {:type "reload-error"
     :file file
     :error error}))

(defn notify-highlight!
  "Tell every browser to apply the `resolved` highlight (the map from inspector/resolve-cursor: :component :file :defn-lines :element :callsite).
  Highlights arrive in order over the socket, so the browser applies each
  unconditionally — no sequence number needed."
  [resolved]
  (send-json!
    (clients-of :browser)
    (assoc resolved
      :type "highlight")))

(defn push-open!
  "Push an open-file command to connected editors.
  Returns the number of editors
  it was actually DELIVERED to (a half-open channel counts as 0), so the caller
  can report failure when the push didn't land."
  [abs-path line column]
  (send-json!
    (clients-of :editor)
    {:type "open"
     :file abs-path
     :line line
     :col column}))

;; ---------------------------------------------------------------------------
;; Path trust boundary. resolve-source-file confines every browser/editor-
;; supplied path to src/ — for both the open-push and the cursor->element lookup.
;; Never relay an unconfined path.
;; ---------------------------------------------------------------------------

(def ^:private src-root
  "Canonical absolute path of the project's src/ directory."
  (delay (.getCanonicalFile (File. "src"))))

(defn- resolve-source-file
  "Resolve a peer-supplied source path to a safe File under src/, or nil.

  Accepts a path relative to src/ or an absolute one; the result must exist, be a
  .clj/.cljc file, and canonicalize to within src/. This is the trust boundary
  for both the editor-launch and the cursor->element lookup."
  ^File [src]
  (when (and (string? src) (not (str/includes? src "..")))
    (let [^File root @src-root
          ^File direct (File. ^String src)
          ^File cf (.getCanonicalFile (if (.isAbsolute direct) direct (File. root ^String src)))]
      (when
        (and
          (.exists cf)
          (re-find #"\.cljc?$" (.getName cf))
          ;; Path-based containment (component-wise) — a string prefix check
          ;; would let a sibling like src-other/… pass as if under src/.
          (.startsWith ^java.nio.file.Path (.toPath cf) (.toPath root)))
        cf))))

(defn- classpath-relative
  "Path of `f` relative to src/ (e.g. \"myapp/web/views.clj\") — the key the reverse index and data-myapp-src use.
  Normalized to forward slashes so the
  key matches data-myapp-src on any OS."
  [^File f]
  (-> (.toPath ^File @src-root)
      (.relativize (.toPath f))
      (.toString)
      (str/replace "\\" "/")))

;; ---------------------------------------------------------------------------
;; Relay handlers
;; ---------------------------------------------------------------------------

(defn- handle-open!
  "Browser -> open file.
  Pushes the open to a connected editor (vscode API via
  Joyride). With no editor connected the open fails — there is no `code -g`
  fallback. Replies an open-result to the browser for its toast."
  [channel src line column]
  (let [reply (fn [m]
                (http-kit/send!
                  channel
                  (json/write-value-as-string
                    (assoc m
                      :type "open-result"))))]
    (if-let [f (resolve-source-file src)]
      (if (pos? (push-open! (.getPath f) line column))
        (reply
          {:ok true
           :src src
           :line line
           :column column})
        (reply
          {:ok false
           :src src
           :error "no editor connected (open this workspace in VS Code with Joyride)"}))
      (reply
        {:ok false
         :error (str "unresolved source: " (pr-str src))}))))

(defn- handle-cursor!
  "Editor -> highlight.
  Confine the path, resolve the cursor to a component +
  element via the reverse index, and broadcast a highlight to the browsers."
  [file line column]
  (let [column (if (number? column) column 1)] ; tolerate a missing col like the open path
    (when (number? line)
      (when-let [f (resolve-source-file file)]
        (let [resolved (inspector/resolve-cursor (classpath-relative f) line column)]
          (when (:component resolved) (notify-highlight! resolved)))))))

(defn request-cursor-resend!
  "Ask connected editors to re-emit their current cursor, so the reverse highlight reappears after a browser (re)connect — e.g. a save-triggered page reload — without waiting for the user to move the cursor.
  No-op with no editor."
  []
  (send-json! (clients-of :editor) {:type "resend-cursor"}))

(defn websocket-handler
  "Sets up a /dev/ws connection and relays inspector messages between peers."
  [request]
  (http-kit/as-channel
    request
    {:on-open (fn [channel]
                (log/debug "Dev WS client connected")
                (add-client! channel)
                (http-kit/send! channel (json/write-value-as-string {:type "connected"}))
                ;; A (re)connecting browser starts with a blank reverse-highlight;
                ;; ask the editor to re-emit its current cursor so it comes back
                ;; on its own (no cursor move needed). Harmless if it was an editor.
                (request-cursor-resend!))
     :on-receive
     (fn [channel message]
       (try
         (let [msg (json/read-value message)]
           (case (get msg "type")
             "hello" (set-role! channel (keyword (get msg "role" "browser")))
             "cursor" (do
                        (set-role! channel :editor)
                        (handle-cursor! (get msg "file") (get msg "line") (get msg "col")))
             "open" (handle-open! channel (get msg "src") (get msg "line") (get msg "col"))
             ;; After a morph the browser asks for the editor's cursor to be re-sent,
             ;; so the reverse highlight reappears against the freshly-morphed DOM.
             "request-resend" (request-cursor-resend!)
             nil))
         (catch Exception e (log/warn e "Dev WS message failed"))))
     :on-close (fn [channel status]
                 (log/debug "Dev WS client disconnected" {:status status})
                 (remove-client! channel))
     :on-error (fn [channel throwable]
                 (log/error throwable "Dev WS error")
                 (remove-client! channel))}))

(defn reload!
  "Manual reload trigger for REPL usage."
  []
  (log/info "Manual reload triggered")
  (notify-reload!))
