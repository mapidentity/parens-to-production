(ns myapp.web.inspector
  "Dev-only source inspector. Two layers, no source-level ceremony — plain
  `defn` views are instrumented automatically by the dev loader.

  COMPONENT level. In dev, the loader (`inspector-load`) re-defines every fn in
  a view namespace through `clojure.tools.reader` and then calls
  `instrument-var!` on it, which wraps the fn so its returned root Hiccup element
  carries the defining function's source location as `data-myapp-src`
  (\"file:line:col\") + `data-myapp-name` (\"ns/name\"). Because it reads the
  VAR's metadata, even dynamically-built roots get tagged. The browser overlay
  (inspector.js) reads those attributes: hover highlights the element, walking up
  the tagged DOM ancestors gives a component breadcrumb, and a click opens the
  source in the editor (via the dev WebSocket — see `dev-reload`). In PROD the
  loader never runs, so views are exactly their `defn` — no wrapping, no
  attributes, no overhead.

  ELEMENT level. The default Clojure reader attaches NO `:line` to nested vector
  literals, but `clojure.tools.reader` does — and the compiler preserves that
  metadata onto the runtime Hiccup values. So the same dev load gives every
  element vector its own `:line` + `:myapp/file`. `tag-root` wraps each render
  boundary so `tag-tree` injects those as `data-myapp-src` just before
  stringification — in PROD `tag-root` expands to the tree unchanged, preserving
  hiccup's compile-time precompilation and adding zero overhead.

  The two layers partition cleanly: a fn root (rebuilt by `tag-hiccup`, so its
  element metadata is gone) keeps its component-level tag, while every inner
  literal keeps its exact line."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private dev?
  "True in the dev environment (the dev/ source dir is on the classpath).

  Detected by resource presence — NOT `requiring-resolve` — because loading
  `hot-reload` here would close a require cycle (hot-reload -> myapp.core -> …
  -> this ns) during cold boot, throw, and silently freeze dev? to false (then
  `tag-root` would expand to its prod no-op in dev). `io/resource`
  loads nothing, so the value is correct regardless of load order. `hot_reload.clj`
  lives under dev/ (a :dev :extra-path), absent from prod builds."
  (some? (io/resource "hot_reload.clj")))

(def ^:private html-tags
  "Base names of Hiccup keywords we treat as real HTML/SVG elements.

  Used to tag ONLY genuine element roots — excludes namespaced keywords (Datomic
  pull patterns `[:db/id ...]`) and tagged-tuple data like `[:expense ...]`, so
  tagging a function's output can never corrupt a non-Hiccup return value."
  #{"a" "abbr" "address" "area" "article" "aside" "audio" "b" "base" "bdi" "bdo" "blockquote" "body"
    "br" "button" "canvas" "caption" "cite" "code" "col" "colgroup" "data" "datalist" "dd" "del"
    "details" "dfn" "dialog" "div" "dl" "dt" "em" "embed" "fieldset" "figcaption" "figure" "footer"
    "form" "h1" "h2" "h3" "h4" "h5" "h6" "head" "header" "hgroup" "hr" "html" "i" "iframe" "img"
    "input" "ins" "kbd" "label" "legend" "li" "link" "main" "map" "mark" "menu" "meta" "meter" "nav"
    "noscript" "object" "ol" "optgroup" "option" "output" "p" "param" "picture" "pre" "progress" "q"
    "rp" "rt" "ruby" "s" "samp" "script" "section" "select" "slot" "small" "source" "span" "strong"
    "style" "sub" "summary" "sup" "table" "tbody" "td" "template" "textarea" "tfoot" "th" "thead"
    "time" "title" "tr" "track" "u" "ul" "var" "video" "wbr"
    ;; SVG (views may render inline-SVG Hiccup)
    "svg" "g" "path" "circle" "rect" "line" "polyline" "polygon" "text" "ellipse" "defs"
    "linearGradient" "radialGradient" "stop" "clipPath" "tspan" "use" "symbol" "marker"})

(defn element?
  "True when `x` is a Hiccup element vector with an HTML/SVG tag keyword head.
  Unnamespaced; `.class`/`#id` suffixes are allowed."
  [x]
  (and
    (vector? x)
    (keyword? (first x))
    (nil? (namespace (first x)))
    (contains? html-tags (first (str/split (name (first x)) #"[.#]")))))

(defn tag-hiccup
  "Inject source attributes into a Hiccup element vector, else return unchanged.

  If `h` is an HTML element vector, add `data-myapp-src`/`data-myapp-name` to its
  attribute map (creating one if absent); otherwise return `h` unchanged. An
  already-tagged element is left alone so the innermost component's location wins."
  [h src nm]
  (if (and (element? h)
           (not (and (map? (second h)) (contains? (second h) :data-myapp-src))))
    (let [has-attrs? (map? (second h))
          attrs (if has-attrs? (second h) {})
          children (subvec h (if has-attrs? 2 1))]
      (into [(first h) (assoc attrs :data-myapp-src src :data-myapp-name nm)] children))
    h))

(defn add-file-meta
  "Stamp `:myapp/file file` onto every Hiccup element literal's metadata in `form`.

  Used by the dev tools.reader load (`inspector-load`): tools.reader puts `:line`
  on every vector, this adds the file so `tag-tree` can emit \"file:line\".
  Preserves structure and ALL existing metadata on every form; only element
  vectors are touched, and only by adding one key — so non-element vectors
  (arg/binding vectors, pull patterns, tuples) are walked but never tagged.
  Recursion depth is bounded by Hiccup/code nesting (tens of levels in practice);
  a pathological depth would StackOverflow, but the dev load is fail-safe (it
  falls back to a normal `load-file` — see `inspector-load/reload-changed!`)."
  [file form]
  (cond
    (vector? form)
    (let [walked (mapv #(add-file-meta file %) form)
          m (meta form)]
      (with-meta
        walked
        (if (and m (:line m) (element? form))
          (assoc m
            :myapp/file file)
          m)))

    (map? form)
    (with-meta
      (into (empty form) (map (fn [[k v]] [(add-file-meta file k) (add-file-meta file v)])) form)
      (meta form))

    (set? form)
    (with-meta (into (empty form) (map #(add-file-meta file %)) form) (meta form))

    (seq? form)
    (with-meta (apply list (map #(add-file-meta file %) form)) (meta form))

    :else form))

(defn tag-tree
  "DEV: walk an assembled Hiccup tree, injecting element-level source tags.

  Every element vector carrying `:line` + `:myapp/file` metadata (attached by
  the dev tools.reader load — see `inspector-load`) gets `data-myapp-src`
  \"file:line\" and `data-myapp-name` (the bare tag) added to its attributes.
  Elements without that metadata — instrumented fn roots (rebuilt by `tag-hiccup`, so
  the metadata is gone) or anything loaded the normal way — pass through, so the
  component-level and element-level layers compose. Pure data->data; non-element
  nodes (strings, numbers, nil, attr maps) are untouched. Seqs (e.g. `for`
  results) are walked element-wise."
  [node]
  (cond
    (vector? node)
    (let [m (meta node)
          children (mapv tag-tree node)]
      (if (and (:line m) (:myapp/file m) (element? node))
        (let [has-attrs? (map? (second children))
              attrs (if has-attrs? (second children) {})
              body (subvec children (if has-attrs? 2 1))]
          (into
            [(first children)
             (assoc attrs
               :data-myapp-src (str (:myapp/file m) ":" (:line m) ":" (or (:column m) 1))
               :data-myapp-name (first (str/split (name (first node)) #"[.#]")))]
            body))
        children))

    (seq? node)
    (doall (map tag-tree node))

    :else node))

(defmacro tag-root
  "Wrap a render-boundary Hiccup tree for element-level source tagging.

  In DEV expands to `(tag-tree tree)`, injecting `data-myapp-src` just before
  stringification. In PROD expands to `tree` unchanged, so hiccup's compile-time
  literal precompilation is preserved and there is zero overhead."
  [tree]
  (if dev? `(tag-tree ~tree) tree))

(defn instrument-var!
  "DEV: wrap a view var's fn so its returned root element is source-tagged.

  Reads the location from `v`'s metadata. Idempotent — re-running after a
  hot-reload re-wraps the freshly-def'd fn and never double-wraps (it unwraps to
  the original first via the `::orig` marker)."
  [v]
  (let [cur @v]
    (when (fn? cur)
      (let [orig (or (::orig (meta cur)) cur)
            m (meta v)
            src (str (:file m) ":" (:line m) ":" (or (:column m) 1))
            nm (str
                 (some-> ^clojure.lang.Namespace (:ns m)
                         ns-name)
                 "/"
                 (:name m))
            wrapped (with-meta (fn [& args] (tag-hiccup (apply orig args) src nm)) {::orig orig})]
        (alter-var-root v (constantly wrapped))))))

(defn instrument-ns!
  "DEV: source-tag every function var in namespace `ns-sym` (a view namespace).

  Called by the dev loader after a view ns is (re)loaded. Wrapping a fn that
  returns non-Hiccup is safe — `tag-hiccup` passes through anything that isn't
  an HTML element vector — so this can blanket-instrument an entire view ns
  without picking functions individually."
  [ns-sym]
  (doseq [[_ v] (ns-interns ns-sym)
          :when (and (var? v) (fn? @v))]
    (instrument-var! v)))

;; ---------------------------------------------------------------------------
;; Reverse inspector: editor cursor -> on-screen element.
;;
;; The dev loader (inspector-load) populates `view-index` from the SAME
;; tools.reader pass that powers the forward tags. `resolve-cursor` maps an
;; editor cursor to {:component \"ns/fn\" :element \"file:line:col\"} by span
;; containment, using the SAME strings stamped onto the DOM (data-myapp-name /
;; data-myapp-src) so the browser match is exact. Empty in prod (the loader
;; never runs), so resolve-cursor returns nil and the feature is inert.
;; ---------------------------------------------------------------------------

(defonce ^{:doc "Atom: classpath-relative file -> {:defns [{:name \"ns/fn\" :span [l c el ec]} …]
  :elements [{:key \"file:line:col\" :span [l c el ec]} …]}. Spans are inclusive
  start / EXCLUSIVE end (tools.reader's :end-column is one past the last char).
  defonce so hot-reloading this ns (a non-view .clj edit triggers a plain
  load-file) keeps the reverse index instead of wiping it mid-session."}
  view-index
  (atom {}))

(defn- form-span
  "[line column end-line end-column] from a form's reader metadata, or nil when
  the indexing reader didn't record a full span."
  [form]
  (let [m (meta form)]
    (when (and (:line m) (:column m) (:end-line m) (:end-column m))
      [(:line m) (:column m) (:end-line m) (:end-column m)])))

(defn- src-key
  "The data-myapp-src string for a position — identical to what tag-tree emits."
  [file line column]
  (str file ":" line ":" (or column 1)))

(defn tag-callsite
  "DEV: stamp `data-myapp-callsite` (the INVOCATION site \"file:line:col\") onto
  `x` if it's an element vector, else return `x` unchanged.

  The dev loader wraps calls to view fns with this, so each rendered instance
  carries the call site that produced it — which is what lets a cursor on one of
  several `(stat-card …)` calls light up only that card, while a single call
  inside a `for` (one source site → N renders) correctly lights up all of them.
  A no-op on non-elements, so wrapping a non-component call is harmless."
  [loc x]
  (if (element? x)
    (let [has-attrs? (map? (second x))
          attrs (if has-attrs? (second x) {})
          children (subvec x (if has-attrs? 2 1))]
      (into [(first x) (assoc attrs :data-myapp-callsite loc)] children))
    x))

(defn- collect-elements
  "Depth-first collect {:key :span} for every element-vector literal in `form`
  that carries a reader span."
  [file form]
  (cond
    (vector? form)
    (concat
      (when (and (element? form) (form-span form))
        (let [[l c] (form-span form)]
          [{:key (src-key file l c) :span (form-span form)}]))
      (mapcat #(collect-elements file %) form))
    (map? form) (mapcat (fn [[k v]] (concat (collect-elements file k) (collect-elements file v))) form)
    (set? form) (mapcat #(collect-elements file %) form)
    (seq? form) (mapcat #(collect-elements file %) form)
    :else nil))

(defn- defn-form?
  "True for (defn …) / (defn- …) top-level forms (any ns-qualification)."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? #{"defn" "defn-"} (name (first form)))
       (symbol? (second form))))

(def ^:private reserved-call-heads
  "Names never treated as view fns — special forms / core whose calls would break
  if rewritten out of position (e.g. a fn shadowing `recur`)."
  #{"recur" "fn" "fn*" "let" "loop" "def" "do" "if" "quote" "var" "throw"
    "try" "catch" "finally" "new" "set!" "monitor-enter" "monitor-exit"})

(defn view-defn-names
  "Set of (string) names of the fns defined in `forms` — the call heads we treat
  as view components for call-site tagging and indexing. Excludes reserved names."
  [forms]
  (into #{} (comp (filter defn-form?) (map #(name (second %))) (remove reserved-call-heads)) forms))

(defn- call-head
  "The bare name of `form`'s head if it's an UNqualified symbol naming a view fn,
  else nil. Qualified heads (other-ns/foo) are never treated as local calls."
  [names form]
  (let [h (and (seq? form) (first form))]
    (when (and (symbol? h) (nil? (namespace h)) (contains? names (name h)))
      (name h))))

(defn- collect-calls
  "Depth-first collect {:key :span} for every call `(name …)` whose head is an
  unqualified symbol in `names`. Records ALL such call sites — the browser's
  data-myapp-callsite existence check reconciles ones that didn't get wrapped."
  [names file form]
  (cond
    (seq? form)
    (concat
      (when (and (call-head names form) (form-span form))
        (let [[l c] (form-span form)]
          [{:key (src-key file l c) :span (form-span form)}]))
      (mapcat #(collect-calls names file %) form))
    (vector? form) (mapcat #(collect-calls names file %) form)
    (map? form) (mapcat (fn [[k v]] (concat (collect-calls names file k) (collect-calls names file v))) form)
    (set? form) (mapcat #(collect-calls names file %) form)
    :else nil))

(def ^:private no-wrap-heads
  "Macro heads whose subforms must not be call-site-wrapped: threading/doto/..
  rewrite their argument forms, so wrapping a threaded call would change its
  meaning. We conservatively disable wrapping for their whole subtree."
  #{"->" "->>" "some->" "some->>" "cond->" "cond->>" "as->" "doto" ".."
    ;; never rewrite inside a quoted list — its contents are data, not calls.
    "quote"})

(defn wrap-callsites
  "Rewrite `form` so each call to a view fn (head ∈ `names`) becomes
  `(tag-callsite \"file:l:c\" <call>)`, stamping the invocation site on the
  rendered result. Preserves all reader metadata so element-level tagging and
  the index keep working. `wrap?` starts true; it's forced false inside
  threading/doto forms (see `no-wrap-heads`) where wrapping would be unsafe."
  [names file form wrap?]
  (cond
    (seq? form)
    (let [head-name (when (symbol? (first form)) (name (first form)))
          child-wrap? (if (contains? no-wrap-heads head-name) false wrap?)
          walked (with-meta (apply list (map #(wrap-callsites names file % child-wrap?) form)) (meta form))]
      (if (and wrap? (call-head names form) (form-span form))
        (let [[l c] (form-span form)]
          (with-meta (list `tag-callsite (src-key file l c) walked) (meta form)))
        walked))
    (vector? form)
    (with-meta (mapv #(wrap-callsites names file % wrap?) form) (meta form))
    (map? form)
    (with-meta
      (into (empty form) (map (fn [[k v]] [(wrap-callsites names file k wrap?) (wrap-callsites names file v wrap?)])) form)
      (meta form))
    (set? form)
    (with-meta (into (empty form) (map #(wrap-callsites names file % wrap?)) form) (meta form))
    :else form))

(defn index-ns!
  "Build the `view-index` entry for `file` (classpath-relative) from the read
  top-level `forms` of view namespace `ns-sym`. Replaces any prior entry, so a
  hot-reload re-indexes cleanly. Returns a small summary."
  [file ns-sym forms]
  (let [defn-forms (filter defn-form? forms)
        names (view-defn-names forms)
        defns (keep (fn [form]
                      (when-let [span (form-span form)]
                        {:name (str ns-sym "/" (second form)) :span span}))
                    defn-forms)
        ;; Only index elements/calls that live inside a defn — a cursor only
        ;; resolves when it's inside a defn anyway, and this skips phantom
        ;; element literals in (comment …) blocks or top-level data defs.
        elements (vec (mapcat #(collect-elements file %) defn-forms))
        calls (vec (mapcat #(collect-calls names file %) defn-forms))]
    (swap! view-index assoc file {:defns (vec defns) :elements elements :calls calls})
    {:file file :defns (count defns) :elements (count elements) :calls (count calls)}))

(defn- contains-pos?
  "True when [line col] (1-based) lies in `span` (inclusive start, exclusive end)."
  [[l c el ec] line col]
  (let [l (long l) c (long c) el (long el) ec (long ec) line (long line) col (long col)]
    (and (or (< l line) (and (= l line) (<= c col)))
         (or (< line el) (and (= line el) (< col ec))))))

(defn- span-extent
  "[lines columns] extent of a span — for ordering innermost (smallest) first."
  [[l c el ec]]
  [(- (long el) (long l)) (- (long ec) (long c))])

(defn- span-smaller?
  "Order spans by extent (fewer lines, then fewer columns) = innermost first."
  [a b]
  (neg? (compare (span-extent a) (span-extent b))))

(defn- innermost-containing
  "The smallest-span item from `items` whose span contains [line col], or nil."
  [items line col]
  (reduce (fn [best it]
            (if (and (contains-pos? (:span it) line col)
                     (or (nil? best) (span-smaller? (:span it) (:span best))))
              it
              best))
          nil
          items))

(defn resolve-cursor
  "Map an editor cursor (classpath-relative `file`, 1-based `line`/`col`) to
  {:component \"ns/fn\" :file <file> :defn-lines [start end]
   :element \"file:line:col\" :callsite \"file:line:col\"}.

  - `:component`/`:defn-lines` — the enclosing defn (for the soft component frame;
    span-membership handles components with no single DOM root, e.g. layouts).
  - `:callsite` — innermost call to a view fn under the cursor. The browser
    prefers it (precise per call site; all instances for a single looped call).
  - `:element` — innermost element literal under the cursor (fallback target).

  The browser uses DOM-as-truth precedence: callsite → element → component root.
  Returns nil when the cursor isn't inside any indexed defn."
  [file line col]
  (when-let [{:keys [defns elements calls]} (get @view-index file)]
    (when-let [d (innermost-containing defns line col)]
      {:component (:name d)
       :file file
       :defn-lines [(nth (:span d) 0) (nth (:span d) 2)]
       :element (:key (innermost-containing elements line col))
       :callsite (:key (innermost-containing calls line col))})))
