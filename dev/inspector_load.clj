(ns inspector-load
  "Loads view namespaces via clojure.tools.reader so Hiccup carries source lines.

  The default Clojure reader attaches no :line to nested vector literals, but
  clojure.tools.reader does — and the compiler preserves that metadata onto the
  runtime Hiccup values. We read each view ns with tools.reader, stamp every
  element literal with {:line .. :myapp/file ..}, and eval it; then
  myapp.web.inspector/tag-tree reads that metadata at the render boundary to
  inject element-level data-myapp-src. See the myapp.web.inspector docstring.

  This namespace lives under dev/ (never on the prod classpath). Failures fall
  back to a normal load, so a tools.reader edge case can never break the dev
  loop."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [clojure.tools.reader :as tr]
    [clojure.tools.reader.reader-types :as rt]
    [myapp.web.inspector :as inspector])
  (:import
    [java.io File]))

(set! *warn-on-reflection* true)

(defn- classpath-path
  "Normalize a source path to its classpath-relative form (matching `*file*`).
  An absolute `…/src/foo/bar.clj` or relative `src/foo/bar.clj` -> `foo/bar.clj`."
  [path]
  (-> path
      (str/replace #"^.*/src/" "")
      (str/replace #"^src/" "")))

(defn tr-load!
  "Read `path` with tools.reader, stamp element literals, and eval in its namespace.

  tools.reader puts line/col on every vector; `add-file-meta` adds :myapp/file
  to the element ones. Binds `*file*` to the classpath-relative path so var :file
  metadata matches a normal load. Returns the number of forms evaluated."
  [path]
  (let [file (classpath-path path)
        rdr (rt/indexing-push-back-reader (slurp path))
        eof (Object.)]
    (binding [*ns* *ns*
              *warn-on-reflection* *warn-on-reflection*
              *file* file]
      ;; Collect the read forms (they carry tools.reader's full position spans)
      ;; so we can both eval them and build the reverse-inspector index.
      (let [read1 #(tr/read
                     {:eof eof
                      :read-cond :allow
                      :features #{:clj}}
                     rdr)
            ns-form (read1)]
        (if (identical? ns-form eof)
          0
          ;; Eval the ns form FIRST so *ns* and its aliases are established
          ;; before we read the rest — otherwise auto-resolved keywords
          ;; (::alias/kw, ::local) fail or resolve in the wrong ns and the
          ;; whole file falls back to a plain load, losing its tags + index.
          (do
            (eval ns-form)
            (let [body (loop [acc (transient [])]
                         (let [form (read1)]
                           (if (identical? form eof) (persistent! acc) (recur (conj! acc form)))))
                  forms (into [ns-form] body)
                  ;; the file's fn names — the call heads we wrap for call-site tags
                  names (inspector/view-defn-names forms)]
              (doseq [form body]
                ;; wrap-callsites (call-site tags) then add-file-meta (element-level
                ;; line tags); both preserve reader metadata. Index below uses the
                ;; ORIGINAL (untransformed) forms.
                ;;
                ;; Per-form fail-safe: if an edit hits a construct the transforms
                ;; mishandle so the tagged form won't eval, load THAT form plain
                ;; instead of failing the whole file (which would drop every tag
                ;; in it). The fn is still defined and instrument-ns! still
                ;; root-tags it; only this form's element-level tags are missing,
                ;; and we log which one so the gap is visible, not silent.
                (try
                  (eval
                    (inspector/add-file-meta file (inspector/wrap-callsites names file form true)))
                  (catch Throwable e
                    (log/warn e
                      "Inspector: form failed to source-tag; loaded plain (no element tags)"
                      {:file file
                       :form (when (seq? form) (vec (take 2 form)))})
                    (eval form))))
              ;; Component layer: source-tag every fn the ns just defined.
              (inspector/instrument-ns! (ns-name *ns*))
              ;; Reverse layer: index defn/element/call spans from the original forms.
              (inspector/index-ns! file (ns-name *ns*) forms)
              (count forms))))))))

(defn view-ns-file?
  "True when the .clj at `path` is a view namespace.
  Detected by convention: the filename ends in `views.clj` (so both
  `web/views.clj` and `admin/views.clj` qualify). View namespaces are loaded
  through tools.reader for element-level line metadata and have their functions
  source-tagged."
  [path]
  (str/ends-with? path "views.clj"))

(defn- src-clj-files
  []
  (->> (file-seq (File. "src"))
       (filter #(.isFile ^File %))
       (map #(.getPath ^File %))
       (filter #(str/ends-with? % ".clj"))))

(defn reload-changed!
  "Watcher hook: tools.reader-load a changed view ns, else return nil.

  Returns a truthy keyword when it handled `path` (so the caller skips
  `load-file`), or nil when `path` is not a view ns. Falls back to `load-file`
  on any tools.reader error — the dev loop must never break here."
  [path]
  (when (view-ns-file? path)
    (try
      (tr-load! path)
      :tr-load
      (catch Throwable e
        (log/warn e "Inspector tools.reader-load failed; using normal load" {:path path})
        (load-file path)
        :fallback))))

(defn load-all-views!
  "Tools.reader-load every view namespace at dev startup.

  So element-level metadata is present from boot, not only after the first save.
  Each file is independent; a failure logs and falls back to a normal load so the
  app still boots. Returns the number of view namespaces loaded."
  []
  (let [files (filter view-ns-file? (src-clj-files))]
    (doseq [path files]
      (try
        (tr-load! path)
        (catch Throwable e
          (log/warn e "Inspector tools.reader-load failed; using normal load" {:path path})
          (try
            (load-file path)
            (catch Throwable _ nil)))))
    (log/info "Inspector: element-level metadata loaded" {:view-namespaces (count files)})
    (count files)))
