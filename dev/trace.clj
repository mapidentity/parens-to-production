(ns trace
  "DEV-ONLY: the runtime half of the inspector — a per-request *construction view*
  plus a *flow* drill-down, read from a ClojureStorm execution recording.

  ClojureStorm (the :storm alias) records every myapp.* expression as it runs.
  This namespace does NOT instrument anything; it READS FlowStorm's recordings
  and projects them into the JSON the browser overlay consumes.

  Two key design choices:
  - LAZY projection. `wrap-trace` stores only a tiny descriptor {tid start end ..}
    per request; the (potentially expensive) tree/flow projection runs on demand
    when the overlay fetches /dev/__trace or /dev/__flow. So the request hot path
    pays almost nothing, the heavy values stay in FlowStorm's recorder (not copied
    into our store), and form-level analysis becomes affordable.
  - DB ops come from EXPR traces, not wrapper names. A `(d/q '[...] db)` — whether
    the raw bypass in recipe.core or the `d/pull` inside db.core/pull* — is an
    `:expr` whose sub-form `get-form-at-coord` hands back as data. So we read the
    real datalog + basis-t and cover the raw call sites the wrapper allowlist
    couldn't see.

  Structurally absent in prod: dev/ path only, active only under the :storm
  compiler, reached from prod code solely via requiring-resolve (nil without :dev)."
  (:require
    [clojure.string :as str]
    [clojure.set :as set]
    [datomic.api :as d]
    [flow-storm.runtime.indexes.api :as ia]
    [flow-storm.runtime.debuggers-api :as dbg]
    [myapp.web.inspector :as inspector]
    [jsonista.core :as json]))

(def ^:const flow-id 0)

;; ---------------------------------------------------------------------------
;; Recording control + thread/timeline helpers
;; ---------------------------------------------------------------------------

(defonce ^:private epoch (atom 0))

;; Bounded descriptor store (id -> {tid start end ...}); newest-wins ring buffer.
;; Declared here (ahead of put!/get-desc) so clear-recordings! can reset it.
(defonce ^:private store (atom {:order clojure.lang.PersistentQueue/EMPTY :by-id {}}))

;; the most recent request that threw uncaught — so a later (successful) page can
;; offer "the last request errored — view its trace". Declared up here so
;; clear-recordings! can reset it.
(defonce ^:private last-error (atom nil))

(defn enable-recording! [] (try (dbg/set-recording true) (catch Throwable _ nil)))
(defn clear-recordings! []
  (try
    (swap! epoch inc)
    (dbg/clear-flows)
    (reset! store {:order clojure.lang.PersistentQueue/EMPTY :by-id {}})
    (reset! last-error nil)
    (catch Throwable _ nil)))
(defn setup! [] (enable-recording!))

(defn- recording? [] (try (when-let [f (requiring-resolve 'flow-storm.tracer/recording?)] (boolean (f))) (catch Throwable _ nil)))

(defn- thread-id ^long [] (.threadId (Thread/currentThread)))
(defn- timeline-len [tid] (if-let [tl (ia/get-timeline tid)] (count tl) 0))

;; ---------------------------------------------------------------------------
;; ValueRef summaries — lazy-safe, never embed a db/blob, never force a seq
;; ---------------------------------------------------------------------------

(defn- tname ^String [v] (.getName (class v)))
(defn- trunc [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))
(defn- bounded-pr [v]
  (binding [*print-level* 3 *print-length* 8] (try (pr-str v) (catch Throwable _ "?"))))

(defn- form->str
  "pr-str a source form but render (quote x) as 'x so datalog reads cleanly."
  [x]
  (try
    (cond
      (and (seq? x) (= 'quote (first x))) (str "'" (form->str (second x)))
      (seq? x) (str "(" (str/join " " (map form->str x)) ")")
      (vector? x) (str "[" (str/join " " (map form->str x)) "]")
      :else (pr-str x))
    (catch Throwable _ (pr-str x))))
(defn- db? [v] (try (instance? datomic.Database v) (catch Throwable _ false)))
(defn- safe-eid [v] (try (:db/id v) (catch Throwable _ nil)))  ; sorted-map compares can throw

(defn summarize [v]
  (try
    (cond
      (nil? v) {:kind "nil"}
      (db? v) {:kind "db" :type "datomic.Database" :basis-t (try (d/basis-t v) (catch Throwable _ nil))}
      (string? v) {:kind "scalar" :type "String" :preview (trunc v 80)}
      (number? v) {:kind "scalar" :type (tname v) :preview (str v)}
      (boolean? v) {:kind "scalar" :type "Boolean" :preview (str v)}
      (keyword? v) {:kind "scalar" :type "Keyword" :preview (str v)}
      (instance? java.util.UUID v) {:kind "scalar" :type "UUID" :preview (str v)}
      ;; a Hiccup element (vector with an HTML-tag head) — label it <tag>, not [n=N]
      ;; (strip Hiccup's .class/#id shorthand: :a.card#x -> "a")
      (inspector/element? v) {:kind "hiccup" :tag (first (str/split (name (first v)) #"[.#]")) :n (count v)}
      (map? v) (let [eid (safe-eid v)]
                 (cond-> {:kind "map" :type (tname v) :n (count v)
                          :keys (mapv str (take 12 (keys v)))
                          :preview (trunc (bounded-pr v) 120)}
                   eid (assoc :eid eid)))
      (counted? v) {:kind "coll" :type (tname v) :n (count v) :preview (trunc (bounded-pr v) 120)}
      (seq? v) {:kind "seq" :type (tname v)}
      :else {:kind "opaque" :type (tname v) :preview (trunc (bounded-pr v) 80)})
    (catch Throwable _ {:kind "opaque" :type (try (tname v) (catch Throwable _ "?"))})))

;; ---------------------------------------------------------------------------
;; Frame naming + noise filtering
;; ---------------------------------------------------------------------------

(defn- short-name [^String nm]
  (let [segs (str/split nm #"/") lst (peek segs)]
    (if (and (> (count segs) 1) (re-find #"^(fn|iter|eval)[-_]" lst))
      (str (nth segs (- (count segs) 2)) "/ƒ")
      lst)))

(def ^:private plumbing-fns #{"convert-dates" "convert-instants" "as-instant"})

(defn- noise? [m]
  (let [ns-str (:fn-ns m) nm (str (:fn-name m))]
    (or (= ns-str "myapp.web.inspector")
        (str/includes? nm "iter--")
        (contains? plumbing-fns nm)
        (some #(str/starts-with? nm (str % "/")) plumbing-fns))))

;; ---------------------------------------------------------------------------
;; Datomic call detection (from expr sub-forms)
;; ---------------------------------------------------------------------------

(def ^:private db-nss
  #{"myapp.recipe.core" "myapp.db.core" "myapp.auth.core" "myapp.admin.core"
    "myapp.analytics.db" "myapp.web.handler"})

(def ^:private datomic-ops
  #{"q" "pull" "pull-many" "entid" "as-of" "history" "transact" "datoms" "entity"})

(defn- datomic-call? [sf]
  (and (seq? sf) (symbol? (first sf))
       (#{"d" "datomic.api"} (namespace (first sf)))
       (contains? datomic-ops (name (first sf)))))

;; ---------------------------------------------------------------------------
;; Read one timeline window into an indexable shape
;; ---------------------------------------------------------------------------

(defn- read-window [tid start end]
  (let [tl (ia/get-timeline tid)
        total (if tl (count tl) 0)
        end (min end total)
        slice (if (and tl (< start end))
                (into [] (comp (drop start) (take (- end start)) (map ia/as-immutable)) tl)
                [])
        calls (persistent!
                (reduce (fn [acc [j m]] (if (= :fn-call (:type m)) (assoc! acc (+ start j) m) acc))
                  (transient {}) (map-indexed vector slice)))
        ;; exprs keep their absolute timeline idx so call-site recovery can pick the
        ;; parent expr that fires right after a (possibly wrapped) child returns.
        exprs-by-call (->> slice
                           (map-indexed (fn [j m] (when (= :expr (:type m)) (assoc m :idx (+ start j)))))
                           (remove nil?)
                           (group-by :fn-call-idx))
        ;; thrown frames: :fn-unwind {:coord :throwable :fn-call-idx}, tagged with idx
        ;; so the innermost (origin) is the lowest-idx unwind.
        unwinds (->> slice
                     (map-indexed (fn [j m] (when (= :fn-unwind (:type m)) (assoc m :idx (+ start j)))))
                     (remove nil?))]
    {:calls calls
     :exprs-by-call exprs-by-call
     :unwinds unwinds
     :unwinds-by-call (group-by :fn-call-idx unwinds)
     :at (fn [abs] (let [j (- abs start)] (when (and (>= j 0) (< j (count slice))) (nth slice j))))}))

(defn- db-t
  "The meaningful t of a db value: its as-of POINT when it's a time-travel db
  (d/as-of-t — note d/basis-t of an as-of db is still the LIVE basis-t, since
  as-of filters but doesn't lower basis), else its basis-t."
  [db]
  (or (try (d/as-of-t db) (catch Throwable _ nil))
      (try (d/basis-t db) (catch Throwable _ nil))))

(defn- nearest-db-basis
  "The t of the nearest in-scope db ARG: this fn-call's db arg, else an
  ancestor's. Uses db-t, so a frame handed an as-of db (e.g. version-history's
  pulls) is labelled with the historical point, not the live basis-t."
  [calls i]
  (loop [k i]
    (when-let [m (calls k)]
      (if-let [db (first (filter db? (:fn-args m)))]
        (db-t db)
        (recur (:parent-idx m))))))

(defn- collect-db-exprs
  "All Datomic-call exprs in the window (under db-relevant fns), as
  {:call-idx i :sf <sub-form> :e <expr-entry>}."
  [{:keys [calls exprs-by-call]}]
  (->> (for [[i es] exprs-by-call
             :let [m (calls i)]
             :when (and m (contains? db-nss (:fn-ns m)))
             e es
             :let [sf (try (ia/get-form-at-coord (:form-id m) (:coord e)) (catch Throwable _ nil))]
             :when (datomic-call? sf)]
         {:call-idx i :sf sf :e e})
       (sort-by :call-idx)))   ; timeline-ish order (stable within a fn)

(defn- ->db-op [calls {:keys [call-idx sf e]}]
  (let [op (name (first sf))
        bt (if (and (#{"as-of" "history"} op) (db? (:result e)))
             (db-t (:result e))                    ; the time-travel db's own point
             (nearest-db-basis calls call-idx))]
    {:op op
     :form (trunc (form->str sf) 600)   ; full enough to read the whole datalog in the detail pane
     :line (some-> sf meta :line)
     :basis-t bt
     :result (summarize (:result e))}))

;; ---------------------------------------------------------------------------
;; Construction tree (the "what / how")
;; ---------------------------------------------------------------------------

(defn- primary-data-arg
  "The 'subject' a fn transforms: its first map/coll/db argument (locale,
  flags, ids are passed as scalars and skipped)."
  [args]
  (first (filter (fn [v] (or (map? v) (coll? v) (db? v))) args)))

(defn- value-morph
  "How a fn reshaped its primary data arg into its return — {from to} ValueRefs
  (a type morph like db→map or map→hiccup reads at a glance), plus a key :diff
  (added/removed/changed) when both are maps. This is the 'what did this fn DO'
  signal, computed from the recorded arg+ret."
  [args ret]
  (when-let [a (primary-data-arg args)]
    (cond-> {:from (summarize a) :to (summarize ret)}
      (and (map? a) (map? ret))
      (assoc :diff (try
                     (let [ak (set (keys a)) rk (set (keys ret))]
                       {:added   (mapv str (take 8 (set/difference rk ak)))
                        :removed (mapv str (take 8 (set/difference ak rk)))
                        :changed (mapv str (take 8 (for [k (set/intersection ak rk)
                                                         :when (not= (get a k) (get ret k))] k)))})
                     (catch Throwable _ nil))))))

(defn- defn-src
  "The fn's definition site as a \"file:line:col\" string (the inspector's
  data-myapp-src format), from FlowStorm's form registry — so even frames with no
  DOM node (handlers, domain, db) are navigable to their source."
  [form-id]
  (let [f (try (ia/get-form form-id) (catch Throwable _ nil))]
    (when (and f (:form/file f) (:form/line f))
      (str (:form/file f) ":" (:form/line f) ":1"))))

(defn- call-parent
  "The frame that LEXICALLY called `i`: nearest ancestor that isn't the inspector's
  instrument-var wrapper / tag-tree machinery. Unlike build-spans' eff-parent this
  does NOT skip `iter--` (for/lazy-seq body) frames — a value rendered from a `(for
  …)` is realized during HTML serialization (under tag-tree), so its lexical caller
  IS that for-body frame, not the layout fn that eff-parent would climb to."
  [calls i]
  (loop [p (:parent-idx (calls i))]
    (cond (nil? p) nil
          (not (calls p)) nil
          (= "myapp.web.inspector" (:fn-ns (calls p))) (recur (:parent-idx (calls p)))
          :else p)))

(defn- call-site
  "Where frame `i` was actually INVOKED — {:src \"file:line:col\" :form <str>}. FlowStorm
  records no call coordinate on a :fn-call, so we recover it: the call expression's
  value is logged in the caller's frame right after the (inspector-wrapped) child
  returns, and that expr's :coord locates the `(callee …)` literal in the caller's
  source. The caller is `call-parent` (the real lexical caller, incl. for-bodies).
  nil for roots."
  [{:keys [calls exprs-by-call]} i]
  (when-let [a (call-parent calls i)]
    (let [;; w = the frame directly under a on the path to i (the wrapper, or i itself)
          w (loop [k i] (let [p (:parent-idx (calls k))]
                          (cond (nil? p) k (= p a) k :else (recur p))))
          wret (:ret-idx (calls w))
          site (when wret
                 (reduce (fn [best e]
                           (if (and (> (long (:idx e)) (long wret))
                                    (or (nil? best) (< (long (:idx e)) (long (:idx best)))))
                             e best))
                   nil (get exprs-by-call a)))
          aform (:form-id (calls a))
          sf (when site (try (ia/get-form-at-coord aform (:coord site)) (catch Throwable _ nil)))
          af (try (ia/get-form aform) (catch Throwable _ nil))
          line (some-> sf meta :line)]
      (when (and af (:form/file af) line)
        {:src (str (:form/file af) ":" line ":" (or (some-> sf meta :column) 1))
         :form (trunc (form->str sf) 90)}))))

(defn- frame-name [m] (str (:fn-ns m) "/" (:fn-name m)))

(defn- nn-ancestor
  "Nearest NON-noise ancestor frame index of i (skips the inspector's wrapper
  frames and loop machinery), or nil."
  [calls i]
  (loop [p (:parent-idx (calls i))]
    (cond (nil? p) nil
          (not (calls p)) nil
          (noise? (calls p)) (recur (:parent-idx (calls p)))
          :else p)))

(defn- folded?
  "A DIRECT same-name self-delegation — a multi-arity arity-default
  ((f a b) -> (f a b nil)) or tail self-recursion — whose nearest non-noise
  ancestor is the same fn. It's a dispatch artifact, not distinct structure: the
  inner and outer frames return the same element, so showing both duplicates the
  node in the tree AND double-counts the per-instance index (breaking the
  DOM-node mapping). We fold it into its caller."
  [calls i]
  (let [a (nn-ancestor calls i)]
    (boolean (and a (= (frame-name (calls i)) (frame-name (calls a)))))))

(defn- build-spans [{:keys [calls at] :as w}]
  (let [kept? (fn [i] (when-let [m (calls i)] (and (not (noise? m)) (not (folded? calls i)))))
        eff-parent (fn ep [i]
                     (let [p (:parent-idx (calls i))]
                       (cond (nil? p) nil
                             (not (calls p)) nil
                             (kept? p) p
                             :else (ep p))))
        kept-idxs (sort (filter kept? (keys calls)))
        nm-of (fn [i] (frame-name (calls i)))
        el? (fn [i] (inspector/element? (some-> (:ret-idx (calls i)) at :result)))
        ;; rank among same-named element-producing KEPT frames (timeline order),
        ;; so a tree node highlights the SPECIFIC rendered instance (k-th DOM node).
        ;; Folding above means this count equals the DOM-node count.
        instance-of (->> (filter el? kept-idxs)
                         (group-by nm-of)
                         (mapcat (fn [[_ idxs]] (map-indexed (fn [k i] [i k]) (sort idxs))))
                         (into {}))
        kept-anc (fn [i] (if (kept? i) i (eff-parent i)))   ; nearest kept frame (self or ancestor)
        ;; ---- lexical re-parenting for lazily-realized frames ----
        ;; A `(for …)` body is a lazy seq realized during HTML serialization (under
        ;; inspector/tag-tree), so a frame's RUNTIME parent is the renderer, not the
        ;; fn that wrote it. If the path up to the runtime parent crosses an `iter--`
        ;; (for-body) frame, attribute the frame to that for-body's OWNING fn instead,
        ;; so e.g. fmt-instant nests under users-table (where it's written) — matching
        ;; the call site — rather than under base-layout (where it ran).
        nm->kept (reduce (fn [acc i] (update acc (nm-of i) (fnil conj []) i)) {} kept-idxs)
        iter-owner (fn [i]
                     (let [ep (eff-parent i)]
                       (loop [p (:parent-idx (calls i))]
                         (cond (nil? p) nil
                               (= p ep) nil
                               (not (calls p)) nil
                               (re-find #"/iter--" (str (:fn-name (calls p))))
                               (str (:fn-ns (calls p)) "/" (first (str/split (str (:fn-name (calls p))) #"/")))
                               :else (recur (:parent-idx (calls p)))))))
        runtime-anc? (fn ra? [anc i] (let [p (eff-parent i)] (cond (nil? p) false (= p anc) true :else (ra? anc p))))
        lex-parent (fn [i]
                     (when-let [owner (iter-owner i)]
                       (let [cands (get nm->kept owner)]
                         (when (= 1 (count cands))
                           (let [c (first cands)]
                             (when (and (not= c i) (not (runtime-anc? i c))) c))))))   ; unique owner, no cycle
        tree-parent (fn [i] (or (lex-parent i) (eff-parent i)))
        ops-by-call (->> (collect-db-exprs w)
                         (keep (fn [d] (when-let [k (kept-anc (:call-idx d))]   ; re-attribute folded ops upward
                                         (assoc d :call-idx k :op (->db-op calls d)))))
                         (group-by :call-idx))
        ;; the throw origin: innermost unwound frame (lowest-idx :fn-unwind). Its
        ;; :coord pins the throwing expression in that frame's form.
        origin-uw (first (sort-by :idx (:unwinds w)))
        throw-idx (when (and origin-uw (calls (:fn-call-idx origin-uw))) (kept-anc (:fn-call-idx origin-uw)))
        throw-info (when throw-idx
                     (let [fnidx (:fn-call-idx origin-uw) t (:throwable origin-uw)
                           sf (try (ia/get-form-at-coord (:form-id (calls fnidx)) (:coord origin-uw)) (catch Throwable _ nil))
                           af (try (ia/get-form (:form-id (calls fnidx))) (catch Throwable _ nil))]
                       (cond-> {:type (try (.getName (class t)) (catch Throwable _ "?"))
                                :msg (try (trunc (.getMessage ^Throwable t) 200) (catch Throwable _ nil))}
                         sf (assoc :form (trunc (form->str sf) 110)
                                   :at (str (:form/file af) ":" (or (some-> sf meta :line) (:form/line af)) ":" (or (some-> sf meta :column) 1))))))
        ->span (fn [i]
                 (let [m (calls i) nm (nm-of i)
                       ret (some-> (:ret-idx m) at :result)
                       ops (mapv :op (get ops-by-call i))
                       src (defn-src (:form-id m))          ; implementation (the defn)
                       cs (call-site w i)                   ; call site (the caller's (f …))
                       inst (instance-of i)
                       lz (lex-parent i)                    ; non-nil ⇒ realized lazily (re-parented)
                       rb (when lz (eff-parent i))          ; the frame it actually ran under (render phase)
                       mr (value-morph (:fn-args m) ret)]
                   (cond-> {:id (str i) :name nm :ns (:fn-ns m) :fn (:fn-name m)
                            :short (short-name nm)
                            :args (mapv summarize (:fn-args m))
                            :ret (summarize ret)}
                     src (assoc :src src)
                     (:src cs) (assoc :call-src (:src cs) :call-form (:form cs))
                     inst (assoc :instance inst)     ; 0 is truthy in Clojure — fine
                     lz (assoc :lazy true)
                     rb (assoc :realized-by (short-name (frame-name (calls rb))) :realized-by-id (str rb))
                     (= i throw-idx) (assoc :threw throw-info)
                     mr (assoc :morph mr)
                     (seq ops) (assoc :db-ops ops))))
        children (reduce (fn [acc i] (if-let [p (tree-parent i)] (update acc p (fnil conj []) i) acc))
                   {} kept-idxs)                                   ; lexical (re-parented)
        children-rt (reduce (fn [acc i] (if-let [p (eff-parent i)] (update acc p (fnil conj []) i) acc))
                      {} kept-idxs)                                ; temporal (runtime, where it actually ran)
        spans (reduce (fn [acc i] (assoc acc (str i) (assoc (->span i)
                                                       :children (mapv str (get children i []))
                                                       :children-rt (mapv str (get children-rt i [])))))
                {} kept-idxs)]
    {:roots (mapv str (remove tree-parent kept-idxs))
     :roots-rt (mapv str (remove eff-parent kept-idxs))
     :spans spans}))

(defn- detect-repeats
  "Cross-frame N+1 signal: the same query FORM run ≥2× in one request (a query in a
  `for`, or redundant lookups across middleware). Returns [{form count op where}] —
  `where` = the spans that ran it, so the UI can jump to each."
  [spans]
  (->> (reduce (fn [acc [sid s]]
                 (reduce (fn [a op] (update a (:form op) (fnil conj []) {:span sid :short (:short s) :op (:op op)}))
                   acc (:db-ops s)))
         {} spans)
       (filter (fn [[_ occ]] (>= (count occ) 2)))
       (mapv (fn [[form occ]] {:form form :count (count occ) :op (:op (first occ))
                               :where (mapv #(select-keys % [:span :short]) occ)}))
       (sort-by (comp - :count))
       vec))

(defn project
  "Build the full Trace JSON from a stored descriptor (lazy, on fetch)."
  [{:keys [id tid start end uri method status ms]}]
  (let [w (read-window tid start end)
        {:keys [roots roots-rt spans]} (build-spans w)]
    {:id id
     :request {:method (some-> method name) :uri uri :status status
               :ms (when ms (Math/round (double (* ms 100)))) :ms-scale 100}
     :recording? (recording?)
     :span-count (count spans)
     :repeats (detect-repeats spans)
     :roots roots :roots-rt roots-rt :spans spans}))

;; ---------------------------------------------------------------------------
;; Flow drill-down (the "where did THIS element's data come from")
;; ---------------------------------------------------------------------------

(defn- result-has-eid? [result eid]
  (try
    (let [hit? (fn [x] (or (= x eid) (and (map? x) (= eid (safe-eid x))) (and (sequential? x) (some #(= % eid) x))))]
      (cond (nil? eid) false
            (map? result) (= eid (safe-eid result))
            (set? result) (or (contains? result eid) (some hit? result))
            (sequential? result) (boolean (some hit? result))
            :else false))
    (catch Throwable _ false)))

(defn- arg-eid [args] (some (fn [a] (when (map? a) (safe-eid a))) args))

(defn flow
  "Resolve a clicked element to its render-span instance and trace its data path.
  `comp-name` = data-myapp-name, `idx` = which same-named instance (DOM order ==
  timeline call order), `src-line` = the clicked element literal's line (or nil)."
  [{:keys [tid start end]} comp-name idx src-line]
  (let [{:keys [calls exprs-by-call at] :as w} (read-window tid start end)
        named (->> (keys calls) sort
                   (filter #(= comp-name (str (:fn-ns (calls %)) "/" (:fn-name (calls %)))))
                   ;; element-producing AND not a folded self-delegation — so the
                   ;; instance count matches the DOM node count (see build-spans).
                   (filterv (fn [i] (and (not (folded? calls i))
                                         (inspector/element? (some-> (:ret-idx (calls i)) at :result))))))
        target (when (< (or idx 0) (count named)) (nth named idx))]
    (cond
      target
      (let [m (calls target)
            eid (arg-eid (:fn-args m))
            db-exprs (collect-db-exprs w)
            reads (mapv (fn [d]
                          (assoc (->db-op calls d)
                            :source (result-has-eid? (:result (:e d)) eid)))
                    db-exprs)
            path (loop [k target acc []]
                   (if-let [mk (calls k)]
                     (recur (:parent-idx mk)
                       (if (noise? mk)
                         acc                       ; skip instrumentation/loop frames
                         (conj acc {:short (short-name (str (:fn-ns mk) "/" (:fn-name mk)))
                                    :name (str (:fn-ns mk) "/" (:fn-name mk))})))
                     (vec (reverse acc))))
            value (when src-line
                    (->> (get exprs-by-call target)
                         (keep (fn [e]
                                 (let [sf (try (ia/get-form-at-coord (:form-id m) (:coord e)) (catch Throwable _ nil))]
                                   (when (and sf (= src-line (some-> sf meta :line)))
                                     {:form (trunc (form->str sf) 120) :line src-line
                                      :value (summarize (:result e))}))))
                         last))]
        {:component comp-name
         :instance (.indexOf ^java.util.List named target)
         :instances (count named)
         :span {:short (short-name comp-name) :name comp-name
                :args (mapv summarize (:fn-args m))
                :ret (summarize (some-> (:ret-idx m) at :result))}
         :path path
         :reads reads
         :value value
         :pivot (when eid {:eid eid})})

      (seq named)
      {:component comp-name :instances (count named) :ambiguous true}

      :else nil)))

;; ---------------------------------------------------------------------------
;; Bounded descriptor store (id -> {tid start end ...}); newest-wins ring buffer
;; ---------------------------------------------------------------------------

(def ^:private cap 256)

(defn- put! [id desc]
  (swap! store
    (fn [{:keys [order by-id]}]
      (let [order (conj order id) by-id (assoc by-id id desc)]
        (if (> (count order) cap)
          {:order (pop order) :by-id (dissoc by-id (peek order))}
          {:order order :by-id by-id})))))

(defn- get-desc [id] (let [d (get-in @store [:by-id id])] (when (and d (= (:epoch d) @epoch)) d)))

(defn get-trace-json
  "Build + serialize the Trace for /dev/__trace/:id (nil if id unknown/expired)."
  [id]
  (when-let [d (get-desc id)]
    (try (json/write-value-as-string (project d))
         (catch Throwable e (json/write-value-as-string {:id id :error (str (.getMessage e))})))))

(defn get-flow-json
  "Build + serialize a FlowChain for /dev/__flow."
  [id comp-name idx src-line]
  (when-let [d (get-desc id)]
    (try (some-> (flow d comp-name idx src-line) json/write-value-as-string)
         (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))

(def ^:private skip-conds
  ;; `when`-family forms whose nil result means the guarded body DIDN'T render —
  ;; the SSR-idiomatic "(when admin? [:section …])". (`if`/`cond` are excluded:
  ;; a nil result there is ambiguous about which branch ran.)
  #{"when" "when-not" "when-let" "when-some" "if-let" "if-some" "if-not"})

(defn get-branches-json
  "Conditionals in a FRAME that evaluated falsy → their body was NOT rendered
  ('why is this section empty?'). {:skipped [{op form line}]}. For /dev/__branches."
  [id frame]
  (when-let [d (get-desc id)]
    (try
      (let [{:keys [calls exprs-by-call]} (read-window (:tid d) (:start d) (:end d))
            m (calls frame)]
        (json/write-value-as-string
          {:skipped
           (if-not m []
             (->> (get exprs-by-call frame)
                  (keep (fn [e]
                          (let [sf (try (ia/get-form-at-coord (:form-id m) (:coord e)) (catch Throwable _ nil))]
                            (when (and (seq? sf) (symbol? (first sf))
                                       (skip-conds (name (first sf)))
                                       (nil? (:result e)))
                              {:op (name (first sf)) :form (trunc (form->str sf) 100) :line (some-> sf meta :line)}))))
                  distinct vec))}))
      (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))

(defn get-source-json
  "The DB read(s) that produced the entity a FRAME received — 'where did this
  frame's data come from' — keyed by frame idx (for the details-pane dossier).
  {:eid e :reads [{op form basis-t result …}]} or {:eid nil} when the frame holds
  no entity. For /dev/__source."
  [id frame]
  (when-let [d (get-desc id)]
    (try
      (let [{:keys [calls] :as w} (read-window (:tid d) (:start d) (:end d))
            m (calls frame)
            eid (when m (arg-eid (:fn-args m)))]
        (json/write-value-as-string
          (if-not eid
            {:eid nil}
            {:eid eid
             :reads (->> (collect-db-exprs w)
                         (map (fn [dx] (assoc (->db-op calls dx) :source (result-has-eid? (:result (:e dx)) eid))))
                         (filter :source) vec)})))
      (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))

;; ---------------------------------------------------------------------------
;; On-demand value navigation (datafy/nav-style) + value-identity threading.
;; The RECORDED value (a fn's :fn-args / :result) lives in FlowStorm's recorder,
;; so we can walk a level at a time on demand instead of shipping the whole blob.
;; ---------------------------------------------------------------------------

(defn- slot-value
  "The raw recorded value of a frame's argN or ret."
  [{:keys [calls at]} frame slot]
  (when-let [m (calls frame)]
    (cond
      (= slot "ret") (some-> (:ret-idx m) at :result)
      (str/starts-with? (str slot) "arg")
      (when-let [n (parse-long (subs (str slot) 3))] (nth (vec (:fn-args m)) n nil)))))

(defn- child-at
  "The i-th child of a collection, in the SAME seq order expand-value enumerates
  (so a path of indices navigates deterministically)."
  [v i]
  (cond
    (map? v) (some-> (nth (seq v) i nil) val)
    (or (vector? v) (set? v) (seq? v)) (nth (seq v) i nil)
    :else nil))

(defn- nav-path [v path] (reduce child-at v (or path [])))

(defn- expandable? [v]
  (boolean (and (some? v) (not (db? v))
                (or (map? v) (vector? v) (set? v) (and (seq? v) (seq v))))))

(defn- table-of
  "A homogeneous coll-of-maps rendered as a {columns rows} table (REBL/Portal idiom)."
  [v]
  (when (and (sequential? v) (next (take 2 v)) (every? map? (take 25 v)))
    (let [rows (vec (take 25 v))
          cols (->> rows (mapcat keys) distinct (take 12) vec)]
      {:columns (mapv str cols)
       :rows (mapv (fn [m] (mapv #(summarize (get m %)) cols)) rows)})))

(defn- expand-value
  "One bounded level of a value: its children (key/index -> ValueRef, expandable?)
  and, for a coll-of-maps, a table."
  [v]
  (let [children (cond
                   (map? v) (vec (map-indexed (fn [i [k val]] {:i i :k (trunc (pr-str k) 48)
                                                               :val (summarize val) :expandable (expandable? val)})
                                   (take 50 (seq v))))
                   (or (vector? v) (set? v) (seq? v))
                   (vec (map-indexed (fn [i x] {:i i :k (str i) :val (summarize x) :expandable (expandable? x)})
                          (take 50 (seq v))))
                   :else nil)]
    (cond-> {:ref (summarize v)}
      children (assoc :children children
                      :truncated (boolean (and (counted? v) (> (count v) 50))))
      (try (table-of v) (catch Throwable _ nil)) (assoc :table (table-of v)))))

(defn get-value-json
  "Expand the recorded value at frame/slot navigated by `path` (a vector of
  child indices). For /dev/__value."
  [id frame slot path]
  (when-let [d (get-desc id)]
    (try (let [w (read-window (:tid d) (:start d) (:end d))]
           (json/write-value-as-string (expand-value (nav-path (slot-value w frame slot) path))))
         (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))

;; ---------------------------------------------------------------------------
;; Produced-markup tree — render a recorded Hiccup return as the element tree it
;; becomes, so a render component lines up with what the inspector shows on the
;; page (the <dt>/<dd> labels inside a stat-card, etc.).
;; ---------------------------------------------------------------------------

(defn- hiccup-tree
  "A recorded Hiccup value as a compact element tree. Flattens seq bodies
  (`(for …)`/`(map …)` children) and drops nil (conditional) children exactly as
  Hiccup does; keeps the inspector's data-myapp-* breadcrumbs so a node can open
  its source and highlight its DOM. Bounded in depth and breadth."
  [v depth]
  (cond
    (inspector/element? v)
    (let [head (name (first v))
          attrs (when (map? (second v)) (second v))
          body (if (map? (second v)) (drop 2 v) (drop 1 v))
          kids (when (< depth 8)
                 (->> body
                      (mapcat (fn [c] (if (and (seq? c) (not (vector? c))) c [c])))  ; inline (for …) seqs
                      (remove nil?)
                      (take 40)
                      (mapv #(hiccup-tree % (inc depth)))))]
      (cond-> {:kind "el" :tag (first (str/split head #"[.#]")) :sel head}
        (:class attrs) (assoc :class (trunc (str (:class attrs)) 70))
        (:data-myapp-name attrs) (assoc :comp (:data-myapp-name attrs))
        (:data-myapp-src attrs) (assoc :src (:data-myapp-src attrs))
        (:data-value attrs) (assoc :val (str (:data-value attrs)))
        (seq kids) (assoc :children kids)))
    (string? v) {:kind "text" :text (trunc v 90)}
    (or (number? v) (keyword? v)) {:kind "text" :text (str v)}
    (nil? v) nil
    (and (seq? v) (not (vector? v)))
    {:kind "seq" :children (when (< depth 8) (->> v (remove nil?) (take 40) (mapv #(hiccup-tree % (inc depth)))))}
    :else {:kind "other" :preview (trunc (bounded-pr v) 60)}))

(defn get-hiccup-json
  "The produced-markup tree for a frame's recorded Hiccup slot (default ret).
  For /dev/__hiccup."
  [id frame slot]
  (when-let [d (get-desc id)]
    (try (let [w (read-window (:tid d) (:start d) (:end d))
               v (slot-value w frame (or slot "ret"))]
           (json/write-value-as-string {:tree (hiccup-tree v 0)}))
         (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))

(defn- value-occurrences
  "Frames where `target` flows in as an arg or out as the ret — by object
  identity (structural sharing threads the SAME map) or by :db/id for entities."
  [{:keys [calls at]} target]
  (let [teid (when (map? target) (safe-eid target))
        same? (fn [v] (try (or (identical? v target) (and teid (map? v) (= teid (safe-eid v))))
                           (catch Throwable _ false)))]
    (when (some? target)
      (for [i (sort (keys calls))
            :let [m (calls i)
                  ret (some-> (:ret-idx m) at :result)
                  slots (vec (concat (keep-indexed (fn [j a] (when (same? a) (str "arg" j))) (:fn-args m))
                                     (when (same? ret) ["ret"])))]
            :when (seq slots)]
        {:frame (str i) :slots slots}))))

(defn get-threads-json
  "Every frame the value at frame/slot flows through. For /dev/__value-threads."
  [id frame slot]
  (when-let [d (get-desc id)]
    (try (let [w (read-window (:tid d) (:start d) (:end d))
               target (slot-value w frame slot)]
           (json/write-value-as-string {:ref (summarize target) :frames (vec (value-occurrences w target))}))
         (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))

;; ---------------------------------------------------------------------------
;; Middleware — near-zero hot-path cost: just record the slice boundaries
;; ---------------------------------------------------------------------------

(defn- html? [resp]
  (some-> (get-in resp [:headers "Content-Type"]) (str/includes? "text/html")))

(defn- stamp-html [body id]
  (if (string? body)
    (str/replace-first body "<html" (str "<html data-myapp-trace-id=\"" id "\""))
    body))

(defn- trace-request [handler req]
  (let [tid (thread-id) start (timeline-len tid) t0 (System/nanoTime)]
    (try
      (let [resp (handler req) ms (/ (- (System/nanoTime) t0) 1e6)]
        (if (html? resp)
          (let [id (str (java.util.UUID/randomUUID))]
            (put! id {:id id :tid tid :start start :end (timeline-len tid) :epoch @epoch
                      :uri (:uri req) :method (:request-method req) :status (:status resp) :ms ms})
            (-> resp (assoc-in [:headers "X-Myapp-Trace"] id) (update :body stamp-html id)))
          resp))
      (catch Throwable e
        ;; the handler threw — store the (errored) window so it's inspectable, then
        ;; re-throw so the app's own error handling is unchanged.
        (let [id (str (java.util.UUID/randomUUID)) ms (/ (- (System/nanoTime) t0) 1e6)]
          (put! id {:id id :tid tid :start start :end (timeline-len tid) :epoch @epoch
                    :uri (:uri req) :method (:request-method req) :status 500 :ms ms :errored true})
          (reset! last-error {:id id :uri (:uri req) :ex (.getName (class e))})
          (throw e))))))

(defn get-last-error-json
  "The most recent uncaught-error trace (id + uri + exception), or null. For
  /dev/__last-error — lets a successful page surface the prior failed request."
  []
  (let [le @last-error]
    (json/write-value-as-string (if (and le (get-desc (:id le))) le {}))))

(defn wrap-trace [handler]
  (fn [req]
    (if (str/starts-with? (str (:uri req)) "/dev/")
      (handler req)
      (trace-request handler req))))
