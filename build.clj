(ns build
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [clojure.tools.build.api :as b]))

(def lib
  'com.myapp/myapp)
(def version
  "0.1.0")
(def class-dir
  "target/classes")
(def uber-file
  "target/myapp.jar")
(def basis
  (delay (b/create-basis {:project "deps.edn"})))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn- fail-on-warnings!
  "Scan stderr for reflection/boxed-math warnings from our code and throw."
  [s]
  (let [hits (->> (str/split-lines s)
                  (filter #(and (or (str/includes? % "Reflection warning,")
                                    (str/includes? % "Boxed math warning,"))
                                (str/includes? % "myapp/")))
                  (take 50)
                  (vec))]
    (when (seq hits)
      (throw (ex-info "Performance warnings detected — add type hints to fix." {:warnings hits})))))

(defn compile-strict
  "AOT-compile src with *warn-on-reflection* and *unchecked-math* :warn-on-boxed,
  then fail if any warnings from our code were emitted. compile-clj runs in a
  subprocess, so we capture stderr via :err :capture and scan it."
  [_]
  (let [{:keys [err]} (b/compile-clj
                        {:basis @basis
                         :src-dirs ["src"]
                         :class-dir class-dir
                         :err :capture
                         :bindings {#'*warn-on-reflection* true
                                    #'*unchecked-math* :warn-on-boxed}})]
    (when err
      (binding [*out* *err*]
        (print err)
        (flush))
      (fail-on-warnings! err))
    (println "compile-strict: OK")))

(defn uber
  [_]
  (clean nil)
  (b/copy-dir
    {:src-dirs ["src" "resources"]
     :target-dir class-dir})
  (compile-strict nil)
  (b/uber
    {:class-dir class-dir
     :uber-file uber-file
     :basis @basis
     :main 'myapp.core}))

;; ---------------------------------------------------------------------------
;; Static asset pipeline.
;;
;; SOURCES live in static/ (committed): the ESM modules under static/js/, the
;; vendored library source idiomorph-0.7.4.js, fonts, svgs, error/. The `assets`
;; task GENERATES the served, content-hashed, minified tree into myapp/static/
;; (gitignored) plus an asset-manifest.edn the running app reads to resolve each
;; logical name to its hashed URL. Dev does not run this — it serves static/
;; directly at identity URLs (Tailwind --watch writes static/styles.css). One
;; engine, two deliveries: the bytes prod ships are produced here from the same
;; sources dev develops against.
;; ---------------------------------------------------------------------------

(def ^:private asset-src "static")
(def ^:private asset-out "myapp/static")

(defn content-hash
  "First 8 hex chars of the SHA-256 of a file's bytes — the cache-bust fingerprint."
  [^java.io.File file]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.readAllBytes (io/input-stream file)))]
    (subs (format "%064x" (BigInteger. 1 bs)) 0 8)))

(defn- sri384
  "Subresource-Integrity token: base64 SHA-384 of a file's bytes, prefixed sha384-."
  [^java.io.File file]
  (let [bs (.digest (java.security.MessageDigest/getInstance "SHA-384")
             (.readAllBytes (io/input-stream file)))]
    (str "sha384-" (.encodeToString (java.util.Base64/getEncoder) bs))))

(defn- insert-hash
  "\"js/dispatcher.js\" + \"a1b2c3d4\" -> \"js/dispatcher.a1b2c3d4.js\"."
  [path hash]
  (let [dot (.lastIndexOf ^String path ".")]
    (str (subs path 0 dot) "." hash (subs path dot))))

(defn- sh!
  "Run a shell command from the project root; throw with captured output on failure."
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed (" exit "): " (str/join " " args))
               {:exit exit :out out :err err})))
    out))

(def ^:private esbuild
  "Standalone esbuild via npx (pinned). Same class of tool as the tailwindcss CLI."
  ["npx" "--yes" "esbuild@0.24.0"])

(defn assets
  "Build the production static-asset tree into myapp/static/ + asset-manifest.edn.
  Tailwind one-shot + content-hash; esbuild-minify each ESM module + content-hash;
  esbuild-minify the vendored lib WITH a sourcemap (version-pinned filename, no
  content hash); copy fonts/svgs/error through unchanged. Run: clojure -T:build assets"
  [_]
  (b/delete {:path asset-out})
  (.mkdirs (io/file asset-out))
  (let [assets* (atom {})
        sri (atom {})]
    ;; 1. passthrough: everything except ESM sources, vendored lib sources, generated css
    (doseq [^java.io.File f (file-seq (io/file asset-src))
            :when (.isFile f)
            :let [rel (subs (.getPath f) (inc (count asset-src)))]
            :when (and (not (str/starts-with? rel "js/"))
                       (not (str/starts-with? rel "idiomorph-"))
                       (not (re-matches #"styles\.css|styles\.[a-f0-9]{8}\.css" rel)))]
      (let [dest (io/file asset-out rel)]
        (.mkdirs (.getParentFile dest))
        (b/copy-file {:src (.getPath f) :target (.getPath dest)})))
    ;; 2. CSS: Tailwind (minified) -> content-hash
    (let [css (io/file asset-out "styles.css")]
      (sh! "tailwindcss" "-i" "input.css" "-o" (.getPath css) "--minify")
      (let [hn (str "styles." (content-hash css) ".css")]
        (.renameTo css (io/file asset-out hn))
        (swap! assets* assoc "styles.css" (str "/" hn))))
    ;; 3. app ESM: esbuild minify (no bundle, keep ESM + absolute imports) -> content-hash
    (let [jsout (io/file asset-out "js")]
      (.mkdirs jsout)
      (doseq [^java.io.File f (sort (.listFiles (io/file asset-src "js")))
              :when (str/ends-with? (.getName f) ".js")
              :let [nm (.getName f)
                    tmp (io/file jsout nm)]]
        (apply sh! (concat esbuild [(.getPath f) "--minify" "--format=esm"
                                    (str "--outfile=" (.getPath tmp))]))
        (let [out (io/file jsout (insert-hash nm (content-hash tmp)))
              url (str "/js/" (.getName out))]
          (.renameTo tmp out)
          (swap! assets* assoc (str "js/" nm) url)
          (swap! sri assoc url (sri384 out)))))
    ;; 4. vendored lib: our own minify + sourcemap (upstream ships no map); version in
    ;; filename (NOT content-hashed) so it survives app deploys; debuggable on rare occasions.
    (let [idsrc (io/file asset-src "idiomorph-0.7.4.js")
          idmin (io/file asset-out "idiomorph-0.7.4.min.js")]
      (apply sh! (concat esbuild [(.getPath idsrc) "--minify" "--sourcemap"
                                  (str "--outfile=" (.getPath idmin))]))
      (swap! assets* assoc "idiomorph" "/idiomorph-0.7.4.min.js")
      (swap! sri assoc "/idiomorph-0.7.4.min.js" (sri384 idmin)))
    ;; 5. manifest the running app reads: {:assets name->url :sri url->sri}
    (spit (io/file asset-out "asset-manifest.edn")
      (pr-str {:assets (into (sorted-map) @assets*) :sri (into (sorted-map) @sri)}))
    (println (str "assets: " (count @assets*) " entries (+SRI) -> " asset-out))
    @assets*))

(defn verify-assets
  "Integrity gate for the built asset tree. Asserts: a manifest exists; every
  manifest target file exists; and every content-hashed filename matches the
  SHA-256 of its own bytes (so a name can never lie about its contents).
  Run `clojure -T:build assets` first. Run: clojure -T:build verify-assets"
  [_]
  (let [mf (io/file asset-out "asset-manifest.edn")]
    (when-not (.exists mf)
      (println "FAIL: no asset-manifest.edn — run `clojure -T:build assets` first")
      (System/exit 1))
    (let [m (:assets (read-string (slurp mf)))
          problems
          (for [[name url] m
                :let [f (io/file asset-out (subs url 1))]    ; url is "/..."
                :when (or (not (.exists f))
                          (when-let [[_ h] (re-find #"\.([a-f0-9]{8})\.(?:css|js)$" url)]
                            (not= h (content-hash f))))]
            (str name " -> " url (if (.exists f) " (hash mismatch)" " (missing)")))]
      (if (seq problems)
        (do (println "FAIL: asset integrity problems:")
            (doseq [p problems] (println "  " p))
            (System/exit 1))
        (println (str "OK: " (count m) " assets verified"))))))
