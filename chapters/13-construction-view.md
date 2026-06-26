# The Construction View: Recording a Request with ClojureStorm

[The inspector chapter](12-inspector.md) answered one question precisely: *where did this element come from?* Hold a key, hover a badge, and your editor opens the exact `.clj` line that produced it. That is a map from a pixel to a **source location** — a static fact, welded onto the Hiccup at read time.

This chapter and the two that follow answer a different question, the one you actually ask when something looks wrong: *how was this whole page built, and where did this value come from?* Not "what line is this `<td>`," but "which functions ran, which Datomic queries fired, at what basis-t, and which of them produced the stale title I'm staring at." That is not a static fact. It is a recording of one request's execution, tied back to the DOM the request produced.

The inspector is the *spatial* half — element to code. This is the *temporal* half — element to the code-and-data that ran. The two share exactly one thing, and it is the thing that makes the second half cheap: the welded coordinate. The inspector stamps `data-myapp-name="myapp.web.views/recipe-card"` on every component root. If our execution trace records frames keyed by the same `"ns/fn"` string, then a clicked element and the function call that rendered it are joined by a plain attribute selector — no new index, no fuzzy matching. We reuse the *format*, not the mechanism.

The feature is large enough to build in three movements. **This chapter** records a request and projects it into a call tree — middleware to handler to domain to view, with every Datomic read — served as JSON. [The next chapter](14-construction-view-flow.md) adds the targeted projections: *flow* (trace one element's value back to the query behind it) and a *dossier* of drill-downs over the same recording. [The third](15-construction-view-overlay.md) builds the in-page overlay that renders all of it. Everything is dev-only and **structurally absent** from production, the same standard the rest of our dev infrastructure holds itself to; it reuses the inspector's welded coordinates, the `/dev/ws` editor relay from [the live-reload chapter](06-live-reload.md), and the `defn-asset` dev block from [the Hiccup views chapter](11-hiccup-views.md). The whole server lives in one dev-only namespace, `dev/trace.clj`, which we build from the bottom up here.

---

## Why not just extend the inspector?

The obvious move is to generalize what we already have. The inspector auto-instruments every view function by wrapping its var:

```clojure
;; myapp.web.inspector — the existing pattern
(alter-var-root v (constantly
  (with-meta (fn [& args] (tag-hiccup (apply orig args) src nm)) {::orig orig})))
```

Replace `tag-hiccup` with "open a span / run / close a span," thread a dynamic `*current-span*`, and you have a hand-rolled tracer. It works — for a while. Then you notice what it *can't* see. `alter-var-root` wraps **vars**. Anonymous functions, the bodies of `map`/`reduce`/`for`, a tight numeric loop — none of those are vars, so none of them are spans. You get an accurate tree of *named-function edges* and nothing inside them. For "which queries built this page," that happens to be enough, because the load-bearing edges are all named domain functions. For "trace this value back through the code," it is not: the value was shaped by a `(:user/display-name (:recipe/user recipe))` buried in a view, and a var-wrapper never sees that expression at all.

You could push harder — instrument more, propagate context across threads, capture argument values without pinning them — but at that point you are rebuilding an omniscient debugger by hand. Someone already built one, at the compiler level, and it sees *every* sub-expression.

> **Decision — adopt the recording, build the correlation.** The right division of labour is: let **ClojureStorm** record the execution (it is unbeatable at that, and goes deeper than any var-wrapping we'd write); keep the *correlation to the rendered DOM* ours, because nobody else has our welded coordinate. We adopt the engine and build the part that is genuinely new. The seam between the two is a string — `"ns/fn"` — not a shared mechanism.

## ClojureStorm as a dev-only compiler

[FlowStorm](https://www.flow-storm.org/) is a time-travel debugger for Clojure. Its companion, **ClojureStorm**, is a drop-in build of the Clojure compiler that emits instrumentation into every namespace it compiles, recording each sub-expression's value as your program runs. You opt in by *swapping the compiler*, which sounds drastic until you realise it is exactly the dev/prod split we already use everywhere else — a `:dev`-only dependency. Here is the whole alias in `deps.edn`:

```clojure
;; ClojureStorm: a DEV-ONLY compiler swap that records execution for the
;; construction-view tracer. It replaces org.clojure/clojure with FlowStorm's
;; drop-in compiler build, which emits instrumentation into every myapp.*
;; namespace it compiles. Combine with the dev alias: clojure -M:dev:storm:repl
:storm {:classpath-overrides {org.clojure/clojure nil}        ; drop vanilla Clojure
        :extra-deps {com.github.flow-storm/clojure {:mvn/version "1.12.5-1"}
                     com.github.flow-storm/flow-storm-dbg {:mvn/version "4.6.0"}}
        :jvm-opts ["-Dclojure.storm.instrumentEnable=true"
                   "-Dclojure.storm.instrumentOnlyPrefixes=myapp."   ; our code only
                   "-Dclojure.storm.instrumentAutoPrefixes=false"
                   ;; NOTE: no flowstorm.startRecording=true — recording is OFF at
                   ;; boot and driven per-page by the tracer (see below).
                   "-Xmx2g"                                          ; loud OOM if the leak ever returns
                   "-Dmyapp.dev=true"]}
```

`:classpath-overrides {org.clojure/clojure nil}` drops the normal Clojure jar so ClojureStorm's build provides `clojure.core`; `instrumentOnlyPrefixes=myapp.` keeps the recording to *our* functions (instrumenting `clojure.core` would bury the signal under millions of frames). Production never sees any of this: it resolves `org.clojure/clojure`, sets no storm properties, records nothing. The compiler swap is the heaviest dev affordance in the book — it slows startup and makes the first hit to a page noticeably slower — which is precisely why it lives behind an alias you choose to type.

Notice what is *not* in that list: `flowstorm.startRecording=true`. The obvious move is to record from boot and read whatever's there — and it is the move we made first. It is also the move we backed out of, because it is a memory leak. ClojureStorm's per-thread timelines are append-only; with recording left on, every view-namespace load, every REPL eval, every background tick accretes entries that are never reclaimed, and over a days-long `:storm` session the recording grew to roughly **30 GB**. So recording is **off at boot**, and the tracer turns it on for exactly the span of one page render and off again — the recorder only ever holds the page currently on screen. The `-Xmx2g` cap is the backstop: if that leak ever creeps back, it surfaces as a fast, loud `OutOfMemoryError` instead of silently eating host RAM for a day. (The per-render gating is the middleware below; the finer question of *which* visit to retain across in-page morphs is a refinement we add in [chapter 15](15-construction-view-overlay.md).)

When the dev system starts under `:storm`, `hot-reload` calls the tracer's `setup!` through the debugger API, guarded on the same storm property so a plain `:dev` REPL never tries to load FlowStorm:

```clojure
;; dev/hot_reload.clj — in the dev system startup
(when (System/getProperty "clojure.storm.instrumentEnable")
  (try
    (when-let [setup (requiring-resolve 'trace/setup!)] (setup))
    (log/info "Construction-view tracer enabled (ClojureStorm)")
    (catch Throwable e (log/warn e "Trace setup failed"))))
```

That resolves to `setup!` in `trace.clj`, which — perhaps surprisingly — turns recording *off*. Recording is gated per page, so the steady state at boot is "not recording"; the page-render path is the only thing that flips it on:

```clojure
(defn enable-recording!  [] (try (dbg/set-recording true)  (catch Throwable _ nil)))
(defn disable-recording! [] (try (dbg/set-recording false) (catch Throwable _ nil)))

(defn setup! []
  ;; Start NOT recording. A page request turns recording on for the duration of
  ;; its own render and off again (see record-page), so boot-time view loading,
  ;; DB seeding, REPL evals, and idle time accrete nothing.
  (disable-recording!))
```

> **Why a compiler and not a Java agent or OpenTelemetry?** A Java agent (Byte Buddy, AspectJ) is the same idea, less Clojure-aware — dominated. OpenTelemetry is the industrial answer and the right tool when you want spans to *leave the process* into Jaeger or Tempo; but its spans are coarse (HTTP, JDBC, manual boundaries) and exported elsewhere, neither of which serves an *in-page* view at *expression* granularity. We are building a debugger affordance, not an observability pipeline. The two are answers to different questions, and conflating them gets you the worst of both.

The `trace` namespace itself instruments nothing — its whole job is to *read* FlowStorm's recordings and project them into JSON:

```clojure
(ns trace
  "DEV-ONLY: the runtime half of the inspector — a per-request construction view
  plus a flow drill-down, read from a ClojureStorm execution recording."
  (:require
    [clojure.string :as str]
    [clojure.set :as set]
    [datomic.api :as d]
    [flow-storm.runtime.indexes.api :as ia]
    [flow-storm.runtime.debuggers-api :as dbg]
    [myapp.web.inspector :as inspector]
    [jsonista.core :as json]))
```

The rest of the chapter builds this namespace from the bottom up.

## Reading the recording

ClojureStorm keeps one **timeline per JVM thread**: an append-only sequence of entries. We never call the debugger UI; we read the timeline directly through `flow-storm.runtime.indexes.api`. Four entry shapes matter, and `as-immutable` turns each into a plain map:

```clojure
{:type :fn-call   :fn-ns "myapp.recipe.core" :fn-name "recipe-by-id"
 :form-id 8829    :fn-args [<db> #uuid"…"] :parent-idx 41 :ret-idx 58}
{:type :expr      :coord [4 1] :result #{17592186045442 …} :fn-call-idx 42}
{:type :fn-return :result {…} :fn-call-idx 42}
{:type :fn-unwind :coord [3 2] :throwable <ex> :fn-call-idx 42}     ; a frame that threw
```

Five facts, stacked, are everything we need:

1. **A `:fn-call`'s own index in the timeline is its identity.** Children point back to it via `:parent-idx` (other calls) and `:fn-call-idx` (the expressions inside it). So the call tree falls straight out of `:parent-idx` — no separate tree to assemble.
2. **`:fn-ns` and `:fn-name` are strings**, so `(str fn-ns "/" fn-name)` is `"myapp.web.views/recipe-card"` — **byte-identical** to the `data-myapp-name` the inspector already stamps on the DOM. That string is the join key, and it costs us nothing.
3. **`:fn-args` and `:result` carry the real recorded values.** A query frame's first argument *is* the Datomic db, so its basis-t is one `(d/basis-t …)` away. We never copy or pin these — they live in FlowStorm's recorder; we only read them when asked.
4. **An expression's `:coord` plus the enclosing form's `:form-id`** resolve, via `get-form-at-coord`, to the *source sub-form* as data — line metadata included. That fact is what makes the data-flow half possible.
5. **A `:fn-unwind`** marks a frame the stack unwound through because an exception propagated — its `:coord` pins the throwing expression. That is how we surface a 500 later.

A couple of one-liners get us oriented on the current thread:

```clojure
(defn- thread-id ^long [] (.threadId (Thread/currentThread)))
(defn- timeline-len [tid] (if-let [tl (ia/get-timeline tid)] (count tl) 0))
```

Reading one request is reading a **slice** of one thread's timeline. We realise the slice once, aligned back to absolute indices, and index the fn-calls by their identity. This `read-window` is the workhorse every projection starts from — in this chapter and the next:

```clojure
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
        ;; thrown frames: tagged with idx so the innermost (origin) is the lowest-idx unwind.
        unwinds (->> slice
                     (map-indexed (fn [j m] (when (= :fn-unwind (:type m)) (assoc m :idx (+ start j)))))
                     (remove nil?))]
    {:calls calls
     :exprs-by-call exprs-by-call
     :unwinds unwinds
     :at (fn [abs] (let [j (- abs start)] (when (and (>= j 0) (< j (count slice))) (nth slice j))))}))
```

The shape it returns is the vocabulary for everything that follows: `:calls` maps an absolute timeline index → the `:fn-call` map; `:exprs-by-call` groups every recorded expression under the frame it ran in; `:unwinds` is the (usually empty) list of thrown frames; and `:at` looks up any absolute index — we use it to fetch a frame's `:fn-return` result by its `:ret-idx`.

## Per-request scoping, and the gift of a synchronous handler

Where do `start` and `end` come from? A Ring middleware, placed outermost, records the thread's timeline length before and after the handler. This is the one piece on the request hot path, so it does as little as possible — it does **not** build the tree; it stores a four-field descriptor and returns:

```clojure
(defn- trace-request [handler req]
  (let [tid (thread-id) start (timeline-len tid) t0 (System/nanoTime)]
    (enable-recording!)        ; record ONLY for this render...
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
          (throw e)))
      ;; ...and off again the moment it returns, so the recorder is idle between requests
      (finally (disable-recording!)))))

(defn wrap-trace [handler]
  (fn [req]
    (if (str/starts-with? (str (:uri req)) "/dev/")
      (handler req)
      (trace-request handler req))))
```

Two helpers it leans on — recognise an HTML response, and weld the trace id onto its `<html>` so the browser can find it:

```clojure
(defn- html? [resp]
  (some-> (get-in resp [:headers "Content-Type"]) (str/includes? "text/html")))

(defn- stamp-html [body id]
  (if (string? body)
    (str/replace-first body "<html" (str "<html data-myapp-trace-id=\"" id "\""))
    body))
```

This is sound for one reason, and it is the same reason the inspector's `for`-row tagging was sound: **our request path is synchronous**. `http-kit` serves each request start-to-finish on one worker thread; every handler calls the domain layer and Datomic *inline*; `hiccup2`'s `html` macro forces its sequences eagerly before the response map is returned. So a request is *exactly* the slice `[start, end)` of one thread's timeline — no `future`, no `core.async`, no context-propagation machinery to chase a span across threads. (An async handler would break this; the day one appears, the slice boundary is the thing to revisit.)

We also stamp `X-Myapp-Trace` on *every* HTML response, not just the full-page load. That header is what lets the overlay re-attach a trace to a region the dispatcher morphs in later — the third chapter collects on it.

> **Decision — project lazily, off the hot path.** An early version built the whole tree inside `wrap-trace`, which taxed every page and, worse, copied recorded values into our own store (pinning large pulled maps and skewing the very memory numbers the admin dashboard reports). Storing a `{:tid :start :end}` descriptor and projecting on fetch fixes both: the request pays almost nothing, the heavy values stay in FlowStorm's recorder where they belong, and the expensive form-level analysis below becomes affordable, because it only runs for traces a human actually opens.

The descriptors live in a small bounded ring buffer — newest-wins, capped — keyed by the request id. An `epoch` counter lets `clear-recordings!` invalidate everything at once:

```clojure
(defonce ^:private epoch (atom 0))
(defonce ^:private store (atom {:order clojure.lang.PersistentQueue/EMPTY :by-id {}}))
(defonce ^:private last-error (atom nil))    ; most recent uncaught-error trace

(def ^:private cap 256)

(defn- put! [id desc]
  (swap! store
    (fn [{:keys [order by-id]}]
      (let [order (conj order id) by-id (assoc by-id id desc)]
        (if (> (count order) cap)
          {:order (pop order) :by-id (dissoc by-id (peek order))}
          {:order order :by-id by-id})))))

;; a descriptor is valid only within its epoch — a clear bumps the epoch, so every
;; older id silently expires (its window may no longer exist in the recorder).
(defn- get-desc [id] (let [d (get-in @store [:by-id id])] (when (and d (= (:epoch d) @epoch)) d)))

(defn clear-recordings! []
  (try
    (swap! epoch inc)
    (dbg/clear-flows)
    (reset! store {:order clojure.lang.PersistentQueue/EMPTY :by-id {}})
    (reset! last-error nil)
    (catch Throwable _ nil)))
```

> **What bounds memory — two caps, on two different things.** FlowStorm's per-thread timeline is append-only: while recording is on, it only grows. The fix is not to cap the timeline but to *stop writing to it* — recording is on only for the span of a render (the `enable`/`disable` pair above), so between requests the recorder is idle and accretes nothing. A full navigation additionally calls `clear-recordings!` to evict the previous visit, so the recorder holds at most the current page-visit (the page load plus any morphs layered onto it since — the morph refinement is [chapter 15](15-construction-view-overlay.md)). That is the cap that matters, and it is what replaced an earlier "record from boot" build whose timeline grew to ~30 GB. Our descriptor ring buffer is a *second*, independent cap on a much smaller thing — the four-field `{:tid :start :end}` descriptors, of which we keep the newest 256. The `clear-recordings!` handle and its `/dev/__trace-clear` endpoint still exist as an escape hatch, but they are no longer a discipline you have to remember: the per-render gating and the per-navigation clear keep memory flat on their own, and `-Xmx2g` is there only to make any regression fail loudly.

Everything else in `trace.clj` is a *projection*: a pure function from a stored descriptor to JSON. They all start the same way — resolve the descriptor, `read-window` its slice, build something, serialize. The top-level one is `project`, which we assemble piece by piece over the next sections; here is the endpoint wrapper that serializes it:

```clojure
(defn get-trace-json
  "Build + serialize the Trace for /dev/__trace/:id (nil if id unknown/expired)."
  [id]
  (when-let [d (get-desc id)]
    (try (json/write-value-as-string (project d))
         (catch Throwable e (json/write-value-as-string {:id id :error (str (.getMessage e))})))))
```

## Summarizing values without forcing them

Every projection emits **ValueRefs**: small JSON maps describing a recorded value without shipping the value itself. This matters for three reasons — the values can be huge (a pulled recipe map), lazy (an unrealized seq we must not force), or unprintable (a Datomic db). `summarize` is the one function that turns any recorded value into a safe, preattentive descriptor, and it is careful about all three:

```clojure
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
      (inspector/element? v) {:kind "hiccup" :tag (first (str/split (name (first v)) #"[.#]")) :n (count v)}
      (map? v) (let [eid (safe-eid v)]
                 (cond-> {:kind "map" :type (tname v) :n (count v)
                          :keys (mapv str (take 12 (keys v)))
                          :preview (trunc (bounded-pr v) 120)}
                   eid (assoc :eid eid)))
      (counted? v) {:kind "coll" :type (tname v) :n (count v) :preview (trunc (bounded-pr v) 120)}
      (seq? v) {:kind "seq" :type (tname v)}        ; lazy / uncounted — NEVER (count) it
      :else {:kind "opaque" :type (tname v) :preview (trunc (bounded-pr v) 80)})
    (catch Throwable _ {:kind "opaque" :type (try (tname v) (catch Throwable _ "?"))})))
```

Three details earn their keep. A db becomes `{:kind "db" :basis-t …}` — never `(count)`ed or printed. A `counted?` collection reports its size, but a bare `seq?` is labelled `(lazy)` *without* realizing it (forcing a recorded lazy seq could run side effects or blow up). And `safe-eid` notices when a map is a Datomic entity (`:db/id` present), stamping `:eid` — the hook the flow drill-down will match on next chapter. Everything is wrapped in `try` because we are summarizing *arbitrary recorded values*, some of which throw on `count`/`print`/`compare`.

The bounded printer and a datalog-friendly form printer round out the helpers:

```clojure
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
```

`form->str` is a small thing that matters a lot for readability: it renders `(quote [:find ?e …])` as `'[:find ?e …]`, so the queries we surface read exactly as written in the source rather than as `(quote …)` noise.

## Frame naming and noise filtering

ClojureStorm records *everything* under `myapp.*` — including the inspector's own wrapper frames, `for`-loop machinery (`iter--…`), and pure date-shuffling helpers. None of those belong in a *construction* view. Two predicates draw the line. `short-name` turns a raw `ns/fn` into a label, collapsing anonymous/iter/eval fns to `owner/ƒ`:

```clojure
(defn- short-name [^String nm]
  (let [segs (str/split nm #"/") lst (peek segs)]
    (if (and (> (count segs) 1) (re-find #"^(fn|iter|eval)[-_]" lst))
      (str (nth segs (- (count segs) 2)) "/ƒ")
      lst)))

(def ^:private plumbing-fns #{"convert-dates" "convert-instants" "as-instant"})

(defn- noise? [m]
  (let [ns-str (:fn-ns m) nm (str (:fn-name m))]
    (or (= ns-str "myapp.web.inspector")          ; our own instrument-var wrappers
        (str/includes? nm "iter--")               ; for/lazy-seq body machinery
        (contains? plumbing-fns nm)               ; pure date plumbing — not "construction"
        (some #(str/starts-with? nm (str % "/")) plumbing-fns))))
```

`noise?` is consulted everywhere a frame might appear, so the inspector's instrumentation never shows up *inside* the view of the page it instrumented — a small but important bit of hygiene, since both features are live at once.

## DB ops from expressions, not from names

Here is the part where reading *expressions* — not just function calls — pays off, and where the naive design fails in an instructive way.

The tempting way to surface "what did this page query" is to flag the database wrapper functions: `pull*`, `q*`, `transact*` in `myapp.db.core`. List their names, badge any frame whose name is on the list. It is a five-line predicate, and it is wrong, because **half our reads never go through the wrappers.** `recipe/core` calls `d/q`, `d/history`, `d/as-of`, and `d/entid` directly — and those are not even functions *we* compiled, so ClojureStorm records no `:fn-call` for them at all. A name-allowlist is structurally blind to exactly the time-travel reads a recipe-versioning app is built around.

But a raw `(d/q '[...] db)` is still an **expression** inside an instrumented function, and ClojureStorm recorded it. Its `:coord` and the enclosing function's `:form-id` hand us the sub-form as data. So we detect a Datomic call by *shape*, scan the recorded expressions in db-relevant namespaces, and resolve each back to its source sub-form:

```clojure
(def ^:private db-nss
  #{"myapp.recipe.core" "myapp.db.core" "myapp.auth.core" "myapp.admin.core"
    "myapp.analytics.db" "myapp.web.handler"})

(def ^:private datomic-ops
  #{"q" "pull" "pull-many" "entid" "as-of" "history" "transact" "datoms" "entity"})

(defn- datomic-call? [sf]
  (and (seq? sf) (symbol? (first sf))
       (#{"d" "datomic.api"} (namespace (first sf)))
       (contains? datomic-ops (name (first sf)))))

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
       (sort-by :call-idx)))
```

This *unifies* the two cases instead of special-casing them. The `d/pull` inside `pull*` and the raw `d/q` in `all-recipes` are both just `d/*` expressions; each becomes a `db-op` attached to the function it ran in. And because we have the sub-form, we have **the real datalog as data** — not "a query happened" but the query itself.

The basis-t is the other half of a useful db-op. We want the t the read actually saw — and for a time-travel read that is *not* the live basis-t. `db-t` extracts the meaningful point of a db value, and `nearest-db-basis` walks up the call tree to find the db a frame was handed:

```clojure
(defn- db-t
  "The meaningful t of a db value: its as-of POINT when it's a time-travel db
  (d/as-of-t — note d/basis-t of an as-of db is still the LIVE basis-t, since
  as-of filters but doesn't lower basis), else its basis-t."
  [db]
  (or (try (d/as-of-t db) (catch Throwable _ nil))
      (try (d/basis-t db) (catch Throwable _ nil))))

(defn- nearest-db-basis
  "The t of the nearest in-scope db ARG: this fn-call's db arg, else an ancestor's."
  [calls i]
  (loop [k i]
    (when-let [m (calls k)]
      (if-let [db (first (filter db? (:fn-args m)))]
        (db-t db)
        (recur (:parent-idx m))))))

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
```

So a `version-history` frame handed an `as-of` db passes that db down to its `pull`, and `nearest-db-basis` picks up the historical t automatically — each query is labelled with the point it actually read at:

```
recipe-by-id     (d/entid db [:recipe/id id])                                         @t1031
forks            (d/q '[:find [?c ...] :in $ ?e :where [?c :recipe/forked-from ?e]] …) @t1031
version-history  (d/as-of db tx)  → db                                                @t1031
pull*            (d/pull db pattern eid)  → {n=10}                                     @t1031
```

> **Lesson — instrument the boundary you can name, but *read* the expressions you can't.** The wrapper allowlist was the readable design; the expression scan is the correct one. Whenever a feature needs to see "every call to X" and X is a library function you didn't compile, a name list will quietly miss the call sites that don't go through your wrapper. The recording saw them; ask it. One honest limit remains: the scan is still scoped by a hand-maintained namespace allowlist (`db-nss`), so a *new* DB-touching namespace must be added to that set or its reads won't be surfaced.

## Building the construction tree

`build-spans` is the heart of the server. It takes a `read-window` result and produces the call tree the overlay renders: a map of span-id → span, plus the root ids. It is the densest function in the file, so we build it as a sequence of local definitions, each solving one problem.

How to read what follows: there are two passes layered into one function. The *first* pass is the plain tree — "which frames survive, and who is each one's parent" (`kept?`, `eff-parent`, the per-instance rank). If you read only that, you have a working call tree and can skip ahead to the endpoints. The *second* pass is the corrections that make the tree match what a human expects under laziness and exceptions — re-parenting lazy frames to where they were *written* rather than where they were *forced*, and pinning a throw to its origin. Those are the genuinely hard parts; they are marked as we reach them, and they are worth a second reading rather than a first-pass stall.

**Which frames survive.** A kept frame is one that is neither noise nor a folded self-delegation (defined next). `eff-parent` re-parents every surviving node onto its nearest *kept* ancestor, so dropping a noise frame never disconnects the tree:

```clojure
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
      ,,, ]
  ,,, )
```

**Folding dispatch artifacts.** A multi-arity arity-default (`(f a b)` → `(f a b nil)`) or tail self-recursion records as two frames with the *same name* that return the *same element*. Showing both duplicates the node and — worse — double-counts the per-instance index, breaking the DOM mapping. `folded?` detects it (nearest non-noise ancestor has the same name) so we collapse it into its caller:

```clojure
(defn- nn-ancestor
  "Nearest NON-noise ancestor frame index of i, or nil."
  [calls i]
  (loop [p (:parent-idx (calls i))]
    (cond (nil? p) nil
          (not (calls p)) nil
          (noise? (calls p)) (recur (:parent-idx (calls p)))
          :else p)))

(defn- folded? [calls i]
  (let [a (nn-ancestor calls i)]
    (boolean (and a (= (frame-name (calls i)) (frame-name (calls a)))))))
```

**Per-instance rank.** This is the join that makes "the third recipe card" resolvable. Among same-named *element-producing* kept frames, in timeline order, each gets an index — and because folding already removed the dispatch duplicates, that index equals the DOM-node order:

```clojure
      instance-of (->> (filter el? kept-idxs)
                       (group-by nm-of)
                       (mapcat (fn [[_ idxs]] (map-indexed (fn [k i] [i k]) (sort idxs))))
                       (into {}))
```

**Recovering the call site.** FlowStorm records *where a function is defined* but no call coordinate on a `:fn-call`. Yet the construction view wants the *logical flow* — where `recipe-card` was *invoked from*, not just where it lives. We recover it from the recording's structure: a call expression's value is logged in the *caller's* frame as an `:expr`, firing right after the (inspector-wrapped) callee returns. So the first expr in the caller with `:idx` greater than the callee's `:ret-idx` is the `(callee …)` literal, and its `:coord` locates it in source. `call-parent` finds the real lexical caller (skipping only inspector frames, *keeping* `for`-body frames — a value rendered from a `(for …)` is genuinely called from that loop body):

```clojure
(defn- call-parent [calls i]
  (loop [p (:parent-idx (calls i))]
    (cond (nil? p) nil
          (not (calls p)) nil
          (= "myapp.web.inspector" (:fn-ns (calls p))) (recur (:parent-idx (calls p)))
          :else p)))

(defn- call-site
  "Where frame `i` was actually INVOKED — {:src \"file:line:col\" :form <str>}. nil for roots."
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
```

The definition site comes free from FlowStorm's form registry, so even frames with no DOM node (middleware, handlers, db functions) are navigable:

```clojure
(defn- defn-src [form-id]
  (let [f (try (ia/get-form form-id) (catch Throwable _ nil))]
    (when (and f (:form/file f) (:form/line f))
      (str (:form/file f) ":" (:form/line f) ":1"))))
```

**What a function did.** A type morph — db→map, map→hiccup — tells you a function's job at a glance. `value-morph` summarizes the primary data argument and the return, plus a key-diff when both are maps:

```clojure
(defn- primary-data-arg [args]   ; the 'subject' a fn transforms: first map/coll/db arg
  (first (filter (fn [v] (or (map? v) (coll? v) (db? v))) args)))

(defn- value-morph [args ret]
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
```

**Lexical re-parenting of lazy frames.** Here is a subtlety that would otherwise make the tree lie. A `(for …)` body is a *lazy seq* — it is realized during HTML serialization, under the inspector's `tag-tree` walk, not where it was written. So `fmt-instant`, written inside `users-table`, *ran* under `base-layout`. The runtime tree is technically correct but useless: it puts `fmt-instant` under the layout. We re-parent it back to where it was written, when we can do so unambiguously — if the path to the runtime parent crosses an `iter--` frame, and that for-body's owning fn appears exactly once in the tree:

```clojure
      nm->kept (reduce (fn [acc i] (update acc (nm-of i) (fnil conj []) i)) {} kept-idxs)
      iter-owner (fn [i]   ; the fn that owns the (for …) whose body realized i, or nil
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
                       (when (= 1 (count cands))                 ; unique owner — unambiguous
                         (let [c (first cands)]
                           (when (and (not= c i) (not (runtime-anc? i c))) c))))))   ; no cycle
      tree-parent (fn [i] (or (lex-parent i) (eff-parent i)))
```

We emit *both* parentings so the overlay can offer a lexical⇄temporal toggle: `tree-parent` (lexical, re-parented to where written) and `eff-parent` (temporal, where it actually ran). A re-parented frame is flagged `:lazy` so the UI can mark it and link to where it ran.

**The thrown frame.** The innermost unwind is the throw origin; its `:coord` pins the throwing expression:

```clojure
      kept-anc (fn [i] (if (kept? i) i (eff-parent i)))   ; nearest kept frame (self or ancestor)
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
```

**Assembling a span.** With all the locals in hand, `->span` projects one frame, and the closing `let` body wires the children maps and root lists:

```clojure
      ops-by-call (->> (collect-db-exprs w)
                       (keep (fn [d] (when-let [k (kept-anc (:call-idx d))]   ; re-attribute folded ops upward
                                       (assoc d :call-idx k :op (->db-op calls d)))))
                       (group-by :call-idx))
      ->span (fn [i]
               (let [m (calls i) nm (nm-of i)
                     ret (some-> (:ret-idx m) at :result)
                     ops (mapv :op (get ops-by-call i))
                     src (defn-src (:form-id m))          ; implementation (the defn)
                     cs (call-site w i)                   ; call site (the caller's (f …))
                     inst (instance-of i)
                     lz (lex-parent i)                    ; non-nil ⇒ realized lazily (re-parented)
                     rb (when lz (eff-parent i))          ; the frame it actually ran under
                     mr (value-morph (:fn-args m) ret)]
                 (cond-> {:id (str i) :name nm :ns (:fn-ns m) :fn (:fn-name m)
                          :short (short-name nm)
                          :args (mapv summarize (:fn-args m))
                          :ret (summarize ret)}
                   src (assoc :src src)
                   (:src cs) (assoc :call-src (:src cs) :call-form (:form cs))
                   inst (assoc :instance inst)            ; 0 is truthy in Clojure — fine
                   lz (assoc :lazy true)
                   rb (assoc :realized-by (short-name (frame-name (calls rb))) :realized-by-id (str rb))
                   (= i throw-idx) (assoc :threw throw-info)
                   mr (assoc :morph mr)
                   (seq ops) (assoc :db-ops ops))))
      children (reduce (fn [acc i] (if-let [p (tree-parent i)] (update acc p (fnil conj []) i) acc))
                 {} kept-idxs)                            ; lexical (re-parented)
      children-rt (reduce (fn [acc i] (if-let [p (eff-parent i)] (update acc p (fnil conj []) i) acc))
                    {} kept-idxs)                         ; temporal (where it actually ran)
      spans (reduce (fn [acc i] (assoc acc (str i) (assoc (->span i)
                                                     :children (mapv str (get children i []))
                                                     :children-rt (mapv str (get children-rt i []))))) 
              {} kept-idxs)]
  {:roots (mapv str (remove tree-parent kept-idxs))
   :roots-rt (mapv str (remove eff-parent kept-idxs))
   :spans spans})
```

That is the entire tree: each span carries its name, args, return, source coordinates, optional instance index, db-ops, morph, lazy/throw flags, and both child lists. (`frame-name` is the obvious one-liner — `(str (:fn-ns m) "/" (:fn-name m))`.)

## N+1 detection, for free

We already have every db-op keyed to its form. A query form that ran two or more times in one request is the classic N+1 signal — a query inside a `for`, or a redundant lookup across the middleware chain. `detect-repeats` is a pure projection over the spans we just built:

```clojure
(defn- detect-repeats [spans]
  (->> (reduce (fn [acc [sid s]]
                 (reduce (fn [a op] (update a (:form op) (fnil conj []) {:span sid :short (:short s) :op (:op op)}))
                   acc (:db-ops s)))
         {} spans)
       (filter (fn [[_ occ]] (>= (count occ) 2)))
       (mapv (fn [[form occ]] {:form form :count (count occ) :op (:op (first occ))
                               :where (mapv #(select-keys % [:span :short]) occ)}))
       (sort-by (comp - :count))
       vec))
```

`:where` lists the spans that ran the query, so the overlay can turn each into a clickable chip. In this app it immediately surfaces a real one: `/admin` and `/dashboard` run `find-user-by-email` twice, because the auth middleware chain looks the user up more than once.

## project: the whole trace

`project` assembles the response the overlay first fetches — request metadata, the recording flag, the span tree, and the repeats. (`recording?` just asks FlowStorm whether the recorder is on, so the overlay can warn if it isn't.)

```clojure
(defn project [{:keys [id tid start end uri method status ms]}]
  (let [w (read-window tid start end)
        {:keys [roots roots-rt spans]} (build-spans w)]
    {:id id
     :request {:method (some-> method name) :uri uri :status status
               :ms (when ms (Math/round (double (* ms 100)))) :ms-scale 100}
     :recording? (recording?)
     :span-count (count spans)
     :repeats (detect-repeats spans)
     :roots roots :roots-rt roots-rt :spans spans}))
```

## Serving it, and the middleware gate

The trace is reached through a dev-gated route in `routes.clj`. Its shape is the whole production-safety story for the server: **resolve the handler through `requiring-resolve`, which is `nil` without the `:dev` `trace` namespace, so the route 404s in production.**

```clojure
;; The recorded trace for a page, by the id the overlay reads from <html>.
["/dev/__trace/:id"
 {:get (fn [request]
         (or (try
               (when-let [gj (requiring-resolve 'trace/get-trace-json)]   ; nil in prod → 404
                 (when-let [j (gj (get-in request [:path-params :id]))]
                   {:status 200
                    :headers {"Content-Type" "application/json" "Cache-Control" "no-store"}
                    :body j}))
               (catch Throwable _ nil))
             {:status 404 :headers {"Content-Type" "application/json"} :body "{}"}))}]
```

The middleware mounting is the matching gate on the *write* side. `wrap-trace` is added to the stack only when the ClojureStorm system property is present, and even then only via `requiring-resolve` of the dev namespace — so plain `:dev` (let alone prod) never adds it *or* loads `trace`. It goes outermost, so its timeline window spans the whole synchronous handler:

```clojure
;; DEV + STORM only: the construction-view tracer, OUTERMOST. Gated on the
;; ClojureStorm property so plain :dev and prod never resolve (or load) the dev ns.
;; deref to the middleware FN — reitit's IntoMiddleware has no impl for a Var.
mw (if-let [wt (when (System/getProperty "clojure.storm.instrumentEnable")
                 (try (some-> (requiring-resolve 'trace/wrap-trace) deref)
                      (catch Throwable _ nil)))]
     (into [[wt]] base-mw)
     base-mw)
```

## What you have so far

With one dev alias, the `trace` namespace, and one route, a request under `clojure -M:dev:storm:repl` now records itself, and `GET /dev/__trace/:id` returns the whole construction as JSON: the middleware-to-handler-to-domain-to-view call tree, each frame's recorded args and return summarized as ValueRefs, every Datomic read as real datalog at its basis-t — including the raw `d/as-of`/`d/history` time-travel reads a wrapper allowlist would miss — plus an N+1 signal, both lexical and temporal parentings, and the throw site if the request 500'd.

```
wrap-locale › … › recipes-index
  recipe-show
    recipe-by-id        ⛁ (d/entid …) → 17592186045442   ⛁ pull* (d/pull …) → {n=10} @t1031
    forks               ⛁ (d/q '[:find [?c ...] …]) → [] @t1031
    version-history     ⛁ (d/history db) → db   ⛁ (d/as-of db tx) → db   ⛁ (d/pull …) @t1031
    recipe-detail
      author-name → "Bob"   text-block → 3   app-layout › base-layout › …
```

That JSON is the descriptive whole-page view. [The next chapter](14-construction-view-flow.md) adds the *targeted* projections over the same recording — flow mode and the dossier — and the chapter after renders all of it in the page.
