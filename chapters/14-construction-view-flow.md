# Flow and the Dossier: Projecting a Recording

[The previous chapter](13-construction-view.md) recorded a request and projected it into the *whole-page* construction tree — every frame, every Datomic read, served as JSON from `GET /dev/__trace/:id`. That answers *how was this page built?* in the large.

This chapter answers the targeted questions. **Flow mode** takes one rendered element and traces its value back to the query behind it. The **dossier** is a set of drill-downs for one frame: navigate a recorded value level by level, ask "why is this section empty?", find the read that produced an entity, see the markup a component became, and follow a value through every frame it touched. Every one of these is the same shape — a pure projection over the recording that `read-window` already reads — and each gets its own dev-gated `/dev/__*` endpoint. We keep building the `trace` namespace; the browser overlay that renders all of it is [the next chapter](15-construction-view-overlay.md).

---

## Flow: from one element to the query behind it

Alt+click any rendered thing and trace its value back. Two capabilities make this more than the inspector could do, and both live in `flow`.

**Per-instance resolution.** Eight recipe cards share one `data-myapp-name`; the inspector, keyed on source location, can only highlight all of them. But the *timeline* has order: the k-th card in the DOM is the k-th element-producing `recipe-card` frame in the recording — exactly the `instance-of` rank `build-spans` computed last chapter. So clicking the third card resolves to *that instance's* frame — its actual `recipe` argument, its return, its ancestor chain.

**Eid-matched source.** A recipe card renders data some *sibling* call fetched earlier — `all-recipes`' `d/q` and the per-recipe `pull`, not anything in the card's own subtree. Which read, of all of them, produced *this* card? We have the card's entity id (from its argument map, via the `:eid` `summarize` stamped) and every db-op's recorded result, so we ask Datomic identity directly:

```clojure
(defn- result-has-eid? [result eid]
  (try
    (let [hit? (fn [x] (or (= x eid) (and (map? x) (= eid (safe-eid x))) (and (sequential? x) (some #(= % eid) x))))]
      (cond (nil? eid) false
            (map? result) (= eid (safe-eid result))
            (set? result) (or (contains? result eid) (some hit? result))   ; relation [:find ?e …]
            (sequential? result) (boolean (some hit? result))               ; collection [:find [?e …]]
            :else false))
    (catch Throwable _ false)))

(defn- arg-eid [args] (some (fn [a] (when (map? a) (safe-eid a))) args))
```

`flow` ties them together — resolve the instance, walk its ancestor path, flag the reads that produced its entity, and read the clicked line's recorded value if a source line was passed. It reuses `read-window`, `folded?`, `collect-db-exprs`, and `->db-op` from the previous chapter:

```clojure
(defn flow [{:keys [tid start end]} comp-name idx src-line]
  (let [{:keys [calls exprs-by-call at] :as w} (read-window tid start end)
        named (->> (keys calls) sort
                   (filter #(= comp-name (frame-name (calls %))))
                   ;; element-producing AND not a folded self-delegation — so the
                   ;; instance count matches the DOM node count (see build-spans).
                   (filterv (fn [i] (and (not (folded? calls i))
                                         (inspector/element? (some-> (:ret-idx (calls i)) at :result))))))
        target (when (< (or idx 0) (count named)) (nth named idx))]
    (cond
      target
      (let [m (calls target)
            eid (arg-eid (:fn-args m))
            reads (mapv (fn [d] (assoc (->db-op calls d) :source (result-has-eid? (:result (:e d)) eid)))
                    (collect-db-exprs w))
            path (loop [k target acc []]
                   (if-let [mk (calls k)]
                     (recur (:parent-idx mk)
                       (if (noise? mk) acc (conj acc {:short (short-name (frame-name mk)) :name (frame-name mk)})))
                     (vec (reverse acc))))
            value (when src-line
                    (->> (get exprs-by-call target)
                         (keep (fn [e]
                                 (let [sf (try (ia/get-form-at-coord (:form-id m) (:coord e)) (catch Throwable _ nil))]
                                   (when (and sf (= src-line (some-> sf meta :line)))
                                     {:form (trunc (form->str sf) 120) :line src-line :value (summarize (:result e))}))))
                         last))]
        {:component comp-name
         :instance (.indexOf ^java.util.List named target)
         :instances (count named)
         :span {:short (short-name comp-name) :name comp-name
                :args (mapv summarize (:fn-args m))
                :ret (summarize (some-> (:ret-idx m) at :result))}
         :path path :reads reads :value value
         :pivot (when eid {:eid eid})})

      (seq named) {:component comp-name :instances (count named) :ambiguous true}
      :else nil)))
```

When an index can't be resolved — genuine render reordering (a `sort` between data order and DOM order), or a conditional that rendered nothing — `flow` returns `:ambiguous true` rather than guessing. The flow card surfaces that honestly.

The `:pivot` is the honest part of the whole feature. The trace explains how *this request* rendered the value; it does not explain why the *entity* holds it. The classic case is a fork showing a stale title: `fork!` copied the title onto a new entity days ago, and to Datomic the fork's current title is perfectly correct — the read-trace is green and self-consistent, and unhelpful, because the cause was a write in a different request. So flow mode does not pretend to be a root-cause oracle. It **narrows the suspects, shows the data path, and pivots to the write history** — handing you the entity id and pointing at `d/history`, which is where that question actually lives.

```clojure
(defn get-flow-json [id comp-name idx src-line]
  (when-let [d (get-desc id)]
    (try (some-> (flow d comp-name idx src-line) json/write-value-as-string)
         (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))
```

## The dossier: on-demand projections

The trace JSON keeps values shallow — a `summarize` per arg and return. When you actually want to *explore* a frame, the overlay asks for more, one frame and one level at a time. Each of these is a small projection over the same `read-window`, exposed as its own endpoint. The pattern is identical, so we build the value navigator in full and the rest in brief.

### Navigating a recorded value

The raw value lives in FlowStorm's recorder, so we can walk it a level at a time instead of shipping the blob. `slot-value` fetches a frame's argN or ret; `expand-value` enumerates one bounded level; `table-of` renders a homogeneous coll-of-maps as a table (the REBL/Portal idiom):

```clojure
(defn- slot-value [{:keys [calls at]} frame slot]
  (when-let [m (calls frame)]
    (cond
      (= slot "ret") (some-> (:ret-idx m) at :result)
      (str/starts-with? (str slot) "arg")
      (when-let [n (parse-long (subs (str slot) 3))] (nth (vec (:fn-args m)) n nil)))))

(defn- child-at [v i]   ; the i-th child, in the SAME order expand-value enumerates
  (cond
    (map? v) (some-> (nth (seq v) i nil) val)
    (or (vector? v) (set? v) (seq? v)) (nth (seq v) i nil)
    :else nil))

(defn- nav-path [v path] (reduce child-at v (or path [])))
(defn- expandable? [v]
  (boolean (and (some? v) (not (db? v))
                (or (map? v) (vector? v) (set? v) (and (seq? v) (seq v))))))

(defn- table-of [v]
  (when (and (sequential? v) (next (take 2 v)) (every? map? (take 25 v)))
    (let [rows (vec (take 25 v))
          cols (->> rows (mapcat keys) distinct (take 12) vec)]
      {:columns (mapv str cols)
       :rows (mapv (fn [m] (mapv #(summarize (get m %)) cols)) rows)})))

(defn- expand-value [v]
  (let [children (cond
                   (map? v) (vec (map-indexed (fn [i [k val]] {:i i :k (trunc (pr-str k) 48)
                                                               :val (summarize val) :expandable (expandable? val)})
                                   (take 50 (seq v))))
                   (or (vector? v) (set? v) (seq? v))
                   (vec (map-indexed (fn [i x] {:i i :k (str i) :val (summarize x) :expandable (expandable? x)})
                          (take 50 (seq v))))
                   :else nil)
        table (try (table-of v) (catch Throwable _ nil))]
    (cond-> {:ref (summarize v)}
      children (assoc :children children :truncated (boolean (and (counted? v) (> (count v) 50))))
      table (assoc :table table))))

(defn get-value-json [id frame slot path]
  (when-let [d (get-desc id)]
    (try (let [w (read-window (:tid d) (:start d) (:end d))]
           (json/write-value-as-string (expand-value (nav-path (slot-value w frame slot) path))))
         (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))
```

The path is a vector of child indices, so the overlay can lazily drill `arg0 → 2 → 1` and we navigate deterministically — never forcing more than the 50 children of one level.

### "Why is this section empty?"

A `when`-family conditional whose recorded result is nil means its body did not render. We recover those from the same recorded exprs (`if`/`cond` are excluded — a nil there is ambiguous about which branch ran):

```clojure
(def ^:private skip-conds
  #{"when" "when-not" "when-let" "when-some" "if-let" "if-some" "if-not"})

(defn get-branches-json [id frame]
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
                            (when (and (seq? sf) (symbol? (first sf)) (skip-conds (name (first sf))) (nil? (:result e)))
                              {:op (name (first sf)) :form (trunc (form->str sf) 100) :line (some-> sf meta :line)}))))
                  distinct vec))}))
      (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))
```

On an anonymous request this immediately shows the three auth-gated `when`s in the top nav that rendered nothing — the SSR equivalent of "the button isn't there because you're logged out."

### Where a frame's data came from

The eid-match from flow mode, but keyed by frame for the details pane — the producing reads for whatever entity a frame *received*:

```clojure
(defn get-source-json [id frame]
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
```

### The produced markup

A render component's recorded Hiccup return, rendered as the element tree it *becomes* — flattening `(for …)` seqs and dropping nil children exactly as Hiccup does, keeping the inspector's `data-myapp-*` breadcrumbs so a node lines up with the page:

```clojure
(defn- hiccup-tree [v depth]
  (cond
    (inspector/element? v)
    (let [head (name (first v))
          attrs (when (map? (second v)) (second v))
          body (if (map? (second v)) (drop 2 v) (drop 1 v))
          kids (when (< depth 8)
                 (->> body
                      (mapcat (fn [c] (if (and (seq? c) (not (vector? c))) c [c])))  ; inline (for …) seqs
                      (remove nil?) (take 40)
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

(defn get-hiccup-json [id frame slot]
  (when-let [d (get-desc id)]
    (try (let [w (read-window (:tid d) (:start d) (:end d))]
           (json/write-value-as-string {:tree (hiccup-tree (slot-value w frame (or slot "ret")) 0)}))
         (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))
```

### Value-identity threading

Every frame a given value flows through — by object identity (structural sharing threads the *same* map) or by `:db/id` for entities. This is what lets you pick a value and light up its whole path through the call tree:

```clojure
(defn- value-occurrences [{:keys [calls at]} target]
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

(defn get-threads-json [id frame slot]
  (when-let [d (get-desc id)]
    (try (let [w (read-window (:tid d) (:start d) (:end d))
               target (slot-value w frame slot)]
           (json/write-value-as-string {:ref (summarize target) :frames (vec (value-occurrences w target))}))
         (catch Throwable e (json/write-value-as-string {:error (str (.getMessage e))})))))
```

### The last error

And finally, the projection that lets a *successful* page surface a prior 500 — the descriptor `trace-request` stashed in `last-error` last chapter:

```clojure
(defn get-last-error-json []
  (let [le @last-error]
    (json/write-value-as-string (if (and le (get-desc (:id le))) le {}))))
```

> **Deliberately not built — timing/profiling.** Instrumentation can't measure wall-clock honestly (the recorder's own overhead dominates), so the `ms` we show is the request total from `trace-request`, not per-frame timing. For real profiling, reach for a sampling profiler. The construction view answers *structure and data*, not *time*.

## The endpoints

Every projection above is reached through a dev-gated route, and they all share the one shape from the previous chapter: resolve the handler through `requiring-resolve` (nil → 404 in prod), parse the query params, call the matching `trace/get-*-json`. The flow route, with its `?name`/`?idx`/`?src` parsing:

```clojure
;; Flow drill-down: ?name=<data-myapp-name>&idx=<instance>&src=<file:line:col>
["/dev/__flow/:id"
 {:get (fn [request]
         (or (try
               (when-let [gf (requiring-resolve 'trace/get-flow-json)]
                 (let [{:keys [name idx src]} (:params request)
                       line (some-> src (str/split #":") (nth 1 nil) (->> (re-find #"\d+")) parse-long)]
                   (when-let [j (gf (get-in request [:path-params :id])
                                    name (or (some-> idx parse-long) 0) line)]
                     {:status 200 :headers {"Content-Type" "application/json" "Cache-Control" "no-store"} :body j})))
               (catch Throwable _ nil))
             {:status 404 :headers {"Content-Type" "application/json"} :body "{}"}))}]
```

The dossier endpoints follow the same template, each parsing its own params (`frame`, `slot`, `path`) and calling its `get-*-json`:

```clojure
["/dev/__value/:id"   ,,, ]   ; ?frame=&slot=arg0|ret&path=0,2,1  → get-value-json
["/dev/__branches/:id" ,,, ]  ; ?frame=                           → get-branches-json
["/dev/__source/:id"  ,,, ]   ; ?frame=                           → get-source-json
["/dev/__hiccup/:id"  ,,, ]   ; ?frame=&slot=ret                  → get-hiccup-json
["/dev/__value-threads/:id" ,,, ] ; ?frame=&slot=                 → get-threads-json
["/dev/__last-error"  ,,, ]   ; (no params)                       → get-last-error-json
["/dev/__trace-clear" ,,, ]   ; (no params)                       → clear-recordings!
```

> **Trade-off — the endpoints are unauthenticated, and return real recorded values.** Anything that can reach `/dev/__flow` or `/dev/__value` gets the actual recording — argument and result previews that can include user emails and recipe content. That is acceptable for a dev-only, loopback service — the same posture as `/dev/ws` from the inspector chapter, and prod-absent through the same `requiring-resolve` gate — don't expose the dev server. The third chapter audits the full structural-absence story.

## What you have now

The server is complete. On top of last chapter's whole-page tree, the recording now answers targeted questions as data:

- **Flow** (`/dev/__flow`): resolve a clicked element to its exact instance, walk its data path, and flag the one query and the one pull that produced its entity — with a `d/history` pivot for the question a read-trace honestly can't answer.
- **The dossier** (`/dev/__value`, `/dev/__branches`, `/dev/__source`, `/dev/__hiccup`, `/dev/__value-threads`): navigate any recorded value level by level, see why a section is empty, find an entity's source read, render a component's produced markup, and thread a value through every frame it touched.
- **`/dev/__last-error`**: a successful page can surface the most recent request that 500'd.

It is all still JSON — you can `curl` any of it. [The final chapter](15-construction-view-overlay.md) builds the overlay that turns this into an in-page tool: the call tree you can see, hover, and Alt+click.
