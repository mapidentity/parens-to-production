(ns myapp.web.assets
  "Static-asset resolution, Subresource Integrity, the strict CSP header, and the `defn-asset` inline macro.

  SOURCES live in static/ (committed). The build (`clojure -T:build assets`)
  generates the served, content-hashed, minified tree into myapp/static/ plus an
  asset-manifest.edn of {:assets {logical-name served-url} :sri {served-url sri}}.
  PROD reads that; DEV derives identity URLs (vendored libs served UNMINIFIED, no
  SRI, since the source changes). One mechanism, two deliveries.

  CSP: the app emits its own strict, NO-NONCE Content-Security-Policy. Every inline
  <script> it can emit (registered via defn-asset) plus the import map are allowed
  by their sha256 content hash; same-origin modules by 'self' (+ per-module SRI for
  tamper evidence — defense-in-depth, since 'self' already authorizes them);
  style-src is pragmatically 'unsafe-inline'. Inline script content is emitted via
  hiccup raw so the EMITTED bytes equal the HASHED bytes."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [hiccup2.core :as h2]
    [jsonista.core :as json])
  (:import
    [java.security MessageDigest]
    [java.util Base64]))

(set! *warn-on-reflection* true)

(def dev?
  "True in the dev environment (the dev/ source dir is on the classpath).
  Detected by resource presence, NOT requiring-resolve — requiring the hot-reload
  ns here can deadlock on a circular load and silently turn the feature off."
  (some? (io/resource "hot_reload.clj")))

(def static-root
  "Dir the Ring file handler serves from: source static/ in dev, the built myapp/static/ tree in prod (also what Caddy mounts)."
  (if dev? "static" "myapp/static"))

(def ^:private asset-out
  "myapp/static")

(defonce ^:private manifest
  ;; {:assets {logical-name served-url} :sri {served-url sri-token}}
  (atom
    {:assets {}
     :sri {}}))

(defn- dev-manifest
  "Derive the dev manifest from the source tree.
  Identity URLs for css/js served straight from static/, and UNMINIFIED
  vendored libs (readable for debugging). No SRI in dev — the source files
  change as you edit them."
  []
  (let [js-dir (io/file "static/js")
        js (when (.isDirectory js-dir)
             (into
               {}
               (for [^java.io.File f (.listFiles js-dir)
                     :when (str/ends-with? (.getName f) ".js")]
                 [(str "js/" (.getName f)) (str "/js/" (.getName f))])))
        idiomorph (when (.exists (io/file "static/idiomorph-0.7.4.js"))
                    {"idiomorph" "/idiomorph-0.7.4.js"})]
    {:assets (merge {"styles.css" "/styles.css"} js idiomorph)
     :sri {}}))

(defn load-manifest!
  "Load the asset manifest once at startup.
  PROD reads myapp/static/asset-manifest.edn;
  DEV derives an identity/source manifest from static/."
  []
  (reset! manifest
    (if dev?
      (dev-manifest)
      (let [f (io/file asset-out "asset-manifest.edn")]
        (if (.exists f)
          (edn/read-string (slurp f))
          (do
            (println "Assets: WARNING no asset-manifest.edn — run `clojure -T:build assets`")
            {:assets {}
             :sri {}})))))
  (println
    (str "Assets: " (count (:assets @manifest)) (if dev? " dev" " prod") " manifest entries")))

(defn asset
  "Resolve a logical asset name (e.g. \"styles.css\", \"js/dispatcher.js\", \"idiomorph\") to its served URL.
  Falls back to an identity URL if unmapped."
  [asset-name]
  (or (get-in @manifest [:assets asset-name]) (str "/" asset-name)))

(defn asset-sri
  "SRI token for a served URL, or nil (e.g. always nil in dev)."
  [url]
  (get-in @manifest [:sri url]))

(defn importmap-json
  "JSON for a <script type=importmap> remapping each ESM module's identity URL to its served (hashed) URL, with an `integrity` block (per-module SRI) in prod so a hash-based CSP can authorize the resolved modules.
  Identity no-op in dev. Emit it BEFORE any module script."
  []
  (let [as (:assets @manifest)
        sri (:sri @manifest)
        imports (into
                  (sorted-map)
                  (for [[k v] as
                        :when (str/starts-with? k "js/")]
                    [(str "/" k) v]))
        integrity (into
                    (sorted-map)
                    (for [[_ v] imports
                          :when (sri v)]
                      [v (sri v)]))]
    (json/write-value-as-string
      (cond-> {"imports" imports}
        (seq integrity) (assoc "integrity"
                          integrity)))))

;; ---------------------------------------------------------------------------
;; Speculation Rules (prerender-on-hover) — inline, allowed by CSP content hash
;; ---------------------------------------------------------------------------

(def speculation-rules-json
  "The Speculation Rules document, emitted as an inline <script type=speculationrules>.
  Prerenders same-origin GET pages at `moderate`
  eagerness (hover / pointerdown), so a click activates an already-built page —
  SPA-feel navigation on a plain multi-page app, with cross-document View
  Transitions animating the swap. Excludes auth/terms/admin (stateful or heavy)
  and anything tagged [data-no-prerender]. Honoured only where supported; an
  inert tag elsewhere. We emit (and hash) this exact string."
  (json/write-value-as-string
    {"prerender" [{"where" {"and" [{"href_matches" "/*"}
                                   {"not" {"href_matches" "/auth/*"}}
                                   {"not" {"href_matches" "/terms/*"}}
                                   {"not" {"href_matches" "/admin/*"}}
                                   ;; partials are fetched by islands, never navigated to
                                   {"not" {"href_matches" "/partials/*"}}
                                   {"not" {"selector_matches" "[data-no-prerender]"}}]}
                   "eagerness" "moderate"}]}))

(defn speculation-rules-tag
  "Inline <script type=speculationrules> carrying `speculation-rules-json`.
  Its sha256 enters the CSP via `csp-script-hashes`."
  []
  [:script {:type "speculationrules"} (h2/raw speculation-rules-json)])

;; ---------------------------------------------------------------------------
;; Content-Security-Policy (static, no-nonce; hashes for inline, 'self' for modules)
;; ---------------------------------------------------------------------------

(defn- sha256-b64
  "CSP hash token: base64 SHA-256 of a string, prefixed sha256-."
  [^String s]
  (let [bs (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (str "sha256-" (.encodeToString (Base64/getEncoder) bs))))

(defonce ^:private inline-scripts
  ;; classpath resource paths of inline <script> assets defined via defn-asset;
  ;; their content hashes go into script-src so the strict CSP allows exactly them.
  (atom #{}))

(defn register-inline-script!
  "Record an inline <script> resource path so its hash enters the CSP.
  Called by defn-asset for script assets."
  [path]
  (swap! inline-scripts conj path))

(defn- csp-script-hashes
  "The sha256 CSP tokens for every inline <script> the app may emit (registered inline assets + the import map JSON).
  Recomputed each call (cheap); in dev the inline
  content hot-reloads, so the policy self-heals."
  []
  (-> (mapv (fn [p] (sha256-b64 (slurp (io/resource p)))) (sort @inline-scripts))
      (conj (sha256-b64 (importmap-json)))
      ;; The Speculation Rules inline script (same hash mechanism as defn-asset
      ;; inline scripts; it carries no executable JS but is still script-src).
      (conj (sha256-b64 speculation-rules-json))))

(def ^:private csp-rest
  "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")

(defn- build-csp-header
  "Build the Content-Security-Policy header string from the current manifest.
  Enumerates the inline-script hashes (so the policy needs no 'unsafe-inline')
  and adds ws:/wss: to connect-src in dev for live reload."
  []
  (str
    "default-src 'none'; "
    "script-src 'self' "
    (str/join " " (map #(str "'" % "'") (csp-script-hashes)))
    "; "
    "connect-src 'self'"
    (if dev? " ws: wss:" "")
    "; "
    csp-rest
    "; report-uri /csp-report; report-to csp"))

(def ^:private csp-cached
  (delay (build-csp-header)))

(defn csp-header
  "The strict CSP header value.
  Static in prod (computed once); recomputed in dev
  so it tracks hot-reloaded inline scripts."
  []
  (if dev? (build-csp-header) @csp-cached))

;; ---------------------------------------------------------------------------
;; Inline assets
;; ---------------------------------------------------------------------------

(defn- tag-for-ext
  "Hiccup tag for a resource extension. nil if unknown."
  [path]
  (cond
    (.endsWith ^String path ".css") :style
    (.endsWith ^String path ".js") :script))

(defmacro defn-asset
  "Defines a private zero-arity fn returning a hiccup element for a classpath resource (an INLINE <script>/<style>).
  Content is emitted RAW (unescaped) so the
  emitted bytes equal what the CSP hashes. In prod content is read once at load; in
  dev it is re-read every call so inline scripts hot-reload. Script assets register
  their path so their hash enters the CSP."
  [sym path]
  (let [tag (tag-for-ext path)
        wrap (if tag (fn [expr] [tag `(h2/raw ~expr)]) identity)
        reg (when (= tag :script) `(register-inline-script! ~path))]
    (if dev?
      `(do
         ~reg
         (defn- ~sym
           []
           ~(wrap `(slurp (io/resource ~path)))))
      (let [content (wrap (slurp (io/resource path)))]
        `(do
           ~reg
           (let [v# ~content]
             (defn- ~sym
               []
               v#)))))))
