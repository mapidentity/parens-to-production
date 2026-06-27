# The Construction View: Recording a Request with ClojureStorm

[The inspector chapter](12-inspector.md) answered one question precisely: *where did this element come from?* Hold a key, hover a badge, and your editor opens the exact `.clj` line that produced it. That is a map from a pixel to a **source location** -- a static fact, welded onto the Hiccup at read time.

This chapter and the two that follow answer a different question, the one you actually ask when something looks wrong: *how was this whole page built, and where did this value come from?* Not "what line is this `<td>`," but "which functions ran, which Datomic queries fired, at what basis-t, and which of them produced the stale title I'm staring at." That is not a static fact. It is a recording of one request's execution, tied back to the DOM the request produced.

The inspector is the *spatial* half -- element to code. This is the *temporal* half -- element to the code-and-data that ran. The two share exactly one thing, and it is the thing that makes the second half cheap: the welded coordinate. The inspector stamps `data-myapp-name="myapp.web.views/recipe-card"` on every component root. If our execution trace records frames keyed by the same `"ns/fn"` string, then a clicked element and the function call that rendered it are joined by a plain attribute selector -- no new index, no fuzzy matching. We reuse the *format*, not the mechanism.

It is worth being concrete about the payoff before the machinery, because the machinery is the bulk of this chapter. Picture the bug these three chapters exist to kill: a recipe card on the index shows a title you are sure is wrong. The browser shows you the wrong pixels and nothing else. With the construction view, you Alt+click that card and the tool answers in layers -- *this is the third `recipe-card` call, here is the exact `pull` that fed it, here is the `d/q` that listed it, here is the basis-t they read at, and here is the entity id to hand to `d/history` when the read trace comes back green and the real culprit was a write three days ago.* That is the end state. Everything below is how a recording becomes able to answer that, and it is genuinely involved -- so keep that one gesture in mind as the thing all of it is for.

![The construction-view overlay docked beside the recipe index: an icicle at the top, then the recorded call tree -- middleware narrowing to `recipes-index`, then `all-recipes`, its `d/q` and `pull*`, and the eight `recipe-card` frames -- with an N+1 warning in the header.](images/construction-view-overlay.png)

*The end state this chapter builds toward: one request's full execution -- 157 frames, every Datomic read, an N+1 signal -- recorded and projected into a navigable call tree.*

The feature is large enough to build in two movements. **This chapter** records a request and projects it into a call tree -- middleware to handler to domain to view, with every Datomic read -- served as JSON. [The next chapter](15-construction-view-overlay.md) adds the targeted projections over the same recording -- *flow* (trace one element's value back to the query behind it) and a *dossier* of drill-downs -- and builds the in-page overlay that renders all of it. Everything is dev-only and **structurally absent** from production, the same standard the rest of our dev infrastructure holds itself to; it reuses the inspector's welded coordinates, the `/dev/ws` editor relay from [the live-reload chapter](06-live-reload.md), and the `defn-asset` dev block from [the Hiccup views chapter](11-hiccup-views.md). The whole server lives in one dev-only namespace, `dev/trace.clj`, which we build from the bottom up here.

## Why not just extend the inspector?

The obvious move is to generalize what we already have. The inspector auto-instruments every view function by wrapping its var:

```clojure
;; myapp.web.inspector — the existing pattern
(alter-var-root v (constantly
  (with-meta (fn [& args] (tag-hiccup (apply orig args) src nm)) {::orig orig})))
```

Replace `tag-hiccup` with "open a span / run / close a span," thread a dynamic `*current-span*`, and you have a hand-rolled tracer. It works -- for a while. Then you notice what it *can't* see. `alter-var-root` wraps **vars**. Anonymous functions, the bodies of `map`/`reduce`/`for`, a tight numeric loop -- none of those are vars, so none of them are spans. You get an accurate tree of *named-function edges* and nothing inside them. For "which queries built this page," that happens to be enough, because the load-bearing edges are all named domain functions. For "trace this value back through the code," it is not: the value was shaped by a `(:user/display-name (:recipe/user recipe))` buried in a view, and a var-wrapper never sees that expression at all.

You could push harder -- instrument more, propagate context across threads, capture argument values without pinning them -- but at that point you are rebuilding an omniscient debugger by hand. Someone already built one, at the compiler level, and it sees *every* sub-expression.

> **Decision -- adopt the recording, build the correlation.** The right division of labor is: let **ClojureStorm** record the execution (it is unbeatable at that, and goes deeper than any var-wrapping we'd write); keep the *correlation to the rendered DOM* ours, because nobody else has our welded coordinate. We adopt the engine and build the part that is genuinely new. The seam between the two is a string -- `"ns/fn"` -- not a shared mechanism.

## ClojureStorm as a dev-only compiler

[FlowStorm](https://www.flow-storm.org/) is a time-travel debugger for Clojure. Its companion, **ClojureStorm**, is a drop-in build of the Clojure compiler that emits instrumentation into every namespace it compiles, recording each sub-expression's value as your program runs. You opt in by *swapping the compiler*, which sounds drastic until you realize it is exactly the dev/prod split we already use everywhere else -- a `:dev`-only dependency. Here is the whole alias in `deps.edn`:

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

`:classpath-overrides {org.clojure/clojure nil}` drops the normal Clojure jar so ClojureStorm's build provides `clojure.core`; `instrumentOnlyPrefixes=myapp.` keeps the recording to *our* functions (instrumenting `clojure.core` would bury the signal under millions of frames). Production never sees any of this: it resolves `org.clojure/clojure`, sets no storm properties, records nothing. The compiler swap is the heaviest dev affordance in the book -- it slows startup and makes the first hit to a page noticeably slower -- which is precisely why it lives behind an alias you choose to type.

Notice what is *not* in that list: `flowstorm.startRecording=true`. The obvious move is to record from boot and read whatever's there -- and it is the move we made first. It is also the move we backed out of, because it is a memory leak. ClojureStorm's per-thread timelines are append-only; with recording left on, every view-namespace load, every REPL eval, every background tick accretes entries that are never reclaimed, and over a days-long `:storm` session the recording grew to roughly **30 GB**. So recording is **off at boot**, and the tracer turns it on for exactly the span of one page render and off again -- the recorder only ever holds the page currently on screen. The `-Xmx2g` cap is the backstop: if that leak ever creeps back, it surfaces as a fast, loud `OutOfMemoryError` instead of silently eating host RAM for a day. (The per-render gating is the middleware below; the finer question of *which* visit to retain across in-page morphs is a refinement we add in [chapter 15](15-construction-view-overlay.md).)

When the dev system starts under `:storm`, `hot-reload` calls the tracer's `setup!` through the debugger API, guarded on the same storm property so a plain `:dev` REPL never tries to load FlowStorm:

```clojure
;; dev/hot_reload.clj — in the dev system startup
(when (System/getProperty "clojure.storm.instrumentEnable")
  (try
    (when-let [setup (requiring-resolve 'trace/setup!)] (setup))
    (log/info "Construction-view tracer enabled (ClojureStorm)")
    (catch Throwable e (log/warn e "Trace setup failed"))))
```

That resolves to `setup!` in `trace.clj`, which -- perhaps surprisingly -- turns recording *off*. Recording is gated per page, so the steady state at boot is "not recording"; the page-render path is the only thing that flips it on:

```clojure
(defn enable-recording!  [] (try (dbg/set-recording true)  (catch Throwable _ nil)))
(defn disable-recording! [] (try (dbg/set-recording false) (catch Throwable _ nil)))

(defn setup! []
  ;; Start NOT recording. A page request turns recording on for the duration of
  ;; its own render and off again (see record-page), so boot-time view loading,
  ;; DB seeding, REPL evals, and idle time accrete nothing.
  (disable-recording!))
```

> **Why a compiler and not a Java agent or OpenTelemetry?** A Java agent (Byte Buddy, AspectJ) is the same idea, less Clojure-aware -- dominated. OpenTelemetry is the industrial answer and the right tool when you want spans to *leave the process* into Jaeger or Tempo; but its spans are coarse (HTTP, JDBC, manual boundaries) and exported elsewhere, neither of which serves an *in-page* view at *expression* granularity. We are building a debugger affordance, not an observability pipeline. The two are answers to different questions, and conflating them gets you the worst of both.

The `trace` namespace itself instruments nothing -- its whole job is to *read* FlowStorm's recordings and project them into JSON:

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

1. **A `:fn-call`'s own index in the timeline is its identity.** Children point back to it via `:parent-idx` (other calls) and `:fn-call-idx` (the expressions inside it). So the call tree falls straight out of `:parent-idx` -- no separate tree to assemble.
2. **`:fn-ns` and `:fn-name` are strings**, so `(str fn-ns "/" fn-name)` is `"myapp.web.views/recipe-card"` -- **byte-identical** to the `data-myapp-name` the inspector already stamps on the DOM. That string is the join key, and it costs us nothing.
3. **`:fn-args` and `:result` carry the real recorded values.** A query frame's first argument *is* the Datomic db, so its basis-t is one `(d/basis-t …)` away. We never copy or pin these -- they live in FlowStorm's recorder; we only read them when asked.
4. **An expression's `:coord` plus the enclosing form's `:form-id`** resolve, via `get-form-at-coord`, to the *source sub-form* as data -- line metadata included. That fact is what makes the data-flow half possible.
5. **A `:fn-unwind`** marks a frame the stack unwound through because an exception propagated -- its `:coord` pins the throwing expression. That is how we surface a 500 later.

A couple of one-liners get us oriented on the current thread:

```clojure
(defn- thread-id ^long [] (.threadId (Thread/currentThread)))
(defn- timeline-len [tid] (if-let [tl (ia/get-timeline tid)] (count tl) 0))
```

Reading one request is reading a **slice** of one thread's timeline. We realize the slice once, aligned back to absolute indices, and index the fn-calls by their identity. This `read-window` is the workhorse every projection starts from -- in this chapter and the next:

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

The shape it returns is the vocabulary for everything that follows: `:calls` maps an absolute timeline index → the `:fn-call` map; `:exprs-by-call` groups every recorded expression under the frame it ran in; `:unwinds` is the (usually empty) list of thrown frames; and `:at` looks up any absolute index -- we use it to fetch a frame's `:fn-return` result by its `:ret-idx`.

## Per-request scoping, and the gift of a synchronous handler

Where do `start` and `end` come from? A Ring middleware, placed outermost, records the thread's timeline length before and after the handler. This is the one piece on the request hot path, so it does as little as possible -- it does **not** build the tree; it stores a four-field descriptor and returns:

```clojure
(defn- record-page [handler req]
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
      (record-page handler req))))
```

Two helpers it leans on -- recognize an HTML response, and weld the trace id onto its `<html>` so the browser can find it:

```clojure
(defn- html? [resp]
  (some-> (get-in resp [:headers "Content-Type"]) (str/includes? "text/html")))

(defn- stamp-html [body id]
  (if (string? body)
    (str/replace-first body "<html" (str "<html data-myapp-trace-id=\"" id "\""))
    body))
```

This is sound for one reason, and it is the same reason the inspector's `for`-row tagging was sound: **our request path is synchronous**. `http-kit` serves each request start-to-finish on one worker thread; every handler calls the domain layer and Datomic *inline*; `hiccup2`'s `html` macro forces its sequences eagerly before the response map is returned. So a request is *exactly* the slice `[start, end)` of one thread's timeline -- no `future`, no `core.async`, no context-propagation machinery to chase a span across threads. (An async handler would break this; the day one appears, the slice boundary is the thing to revisit.)

We also stamp `X-Myapp-Trace` on *every* HTML response, not just the full-page load. That header is what lets the overlay re-attach a trace to a region the dispatcher morphs in later -- the third chapter collects on it.

> **Decision -- project lazily, off the hot path.** An early version built the whole tree inside `wrap-trace`, which taxed every page and, worse, copied recorded values into our own store (pinning large pulled maps and skewing the very memory numbers the admin dashboard reports). Storing a `{:tid :start :end}` descriptor and projecting on fetch fixes both: the request pays almost nothing, the heavy values stay in FlowStorm's recorder where they belong, and the expensive form-level analysis below becomes affordable, because it only runs for traces a human actually opens.

The descriptors live in a small bounded ring buffer -- newest-wins, capped -- keyed by the request id. An `epoch` counter lets `clear-recordings!` invalidate everything at once:

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

> **What bounds memory -- two caps, on two different things.** FlowStorm's per-thread timeline is append-only: while recording is on, it only grows. The fix is not to cap the timeline but to *stop writing to it* -- recording is on only for the span of a render (the `enable`/`disable` pair above), so between requests the recorder is idle and accretes nothing. A full navigation additionally calls `clear-recordings!` to evict the previous visit, so the recorder holds at most the current page-visit (the page load plus any morphs layered onto it since -- the morph refinement is [chapter 15](15-construction-view-overlay.md)). That is the cap that matters, and it is what replaced an earlier "record from boot" build whose timeline grew to ~30 GB. Our descriptor ring buffer is a *second*, independent cap on a much smaller thing -- the four-field `{:tid :start :end}` descriptors, of which we keep the newest 256. The `clear-recordings!` handle and its `/dev/__trace-clear` endpoint still exist as an escape hatch, but they are no longer a discipline you have to remember: the per-render gating and the per-navigation clear keep memory flat on their own, and `-Xmx2g` is there only to make any regression fail loudly.

Everything else in `trace.clj` is a *projection*: a pure function from a stored descriptor to JSON. They all start the same way -- resolve the descriptor, `read-window` its slice, build something, serialize. The top-level one is `project`, which we assemble piece by piece over the next sections; here is the endpoint wrapper that serializes it:

```clojure
(defn get-trace-json
  "Build + serialize the Trace for /dev/__trace/:id (nil if id unknown/expired)."
  [id]
  (when-let [d (get-desc id)]
    (try (json/write-value-as-string (project d))
         (catch Throwable e (json/write-value-as-string {:id id :error (str (.getMessage e))})))))
```

## Summarizing values without forcing them

Every projection emits **ValueRefs**: small JSON maps describing a recorded value without shipping the value itself. This matters for three reasons -- the values can be huge (a pulled recipe map), lazy (an unrealized seq we must not force), or unprintable (a Datomic db). `summarize` is the one function that turns any recorded value into a safe, preattentive descriptor, and it is careful about all three:

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

Three details earn their keep. A db becomes `{:kind "db" :basis-t …}` -- never `(count)`ed or printed. A `counted?` collection reports its size, but a bare `seq?` is labeled `(lazy)` *without* realizing it (forcing a recorded lazy seq could run side effects or blow up). And `safe-eid` notices when a map is a Datomic entity (`:db/id` present), stamping `:eid` -- the hook the flow drill-down will match on next chapter. Everything is wrapped in `try` because we are summarizing *arbitrary recorded values*, some of which throw on `count`/`print`/`compare`.

The bounded printer and a Datalog-friendly form printer round out the helpers:

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

ClojureStorm records *everything* under `myapp.*` -- including the inspector's own wrapper frames, `for`-loop machinery (`iter--…`), and pure date-shuffling helpers. None of those belong in a *construction* view. Two predicates draw the line. `short-name` turns a raw `ns/fn` into a label, collapsing anonymous/iter/eval fns to `owner/ƒ`:

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

`noise?` is consulted everywhere a frame might appear, so the inspector's instrumentation never shows up *inside* the view of the page it instrumented -- a small but important bit of hygiene, since both features are live at once.

## DB ops from expressions, not from names

Here is the part where reading *expressions* -- not just function calls -- pays off, and where the naive design fails in an instructive way.

The tempting way to surface "what did this page query" is to flag the database wrapper functions: `pull*`, `q*`, `transact*` in `myapp.db.core`. List their names, badge any frame whose name is on the list. It is a five-line predicate, and it is wrong, because **half our reads never go through the wrappers.** `recipe/core` calls `d/q`, `d/history`, `d/as-of`, and `d/entid` directly -- and those are not even functions *we* compiled, so ClojureStorm records no `:fn-call` for them at all. A name-allowlist is structurally blind to exactly the time-travel reads a recipe-versioning app is built around.

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

This *unifies* the two cases instead of special-casing them. The `d/pull` inside `pull*` and the raw `d/q` in `all-recipes` are both just `d/*` expressions; each becomes a `db-op` attached to the function it ran in. And because we have the sub-form, we have **the real Datalog as data** -- not "a query happened" but the query itself.

The basis-t is the other half of a useful db-op. We want the t the read actually saw -- and for a time-travel read that is *not* the live basis-t. `db-t` extracts the meaningful point of a db value, and `nearest-db-basis` walks up the call tree to find the db a frame was handed:

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

So a `version-history` frame handed an `as-of` db passes that db down to its `pull`, and `nearest-db-basis` picks up the historical t automatically -- each query is labeled with the point it actually read at:

```
recipe-by-id     (d/entid db [:recipe/id id])                                         @t1031
forks            (d/q '[:find [?c ...] :in $ ?e :where [?c :recipe/forked-from ?e]] …) @t1031
version-history  (d/as-of db tx)  → db                                                @t1031
pull*            (d/pull db pattern eid)  → {n=10}                                     @t1031
```

> **Lesson -- instrument the boundary you can name, but *read* the expressions you can't.** The wrapper allowlist was the readable design; the expression scan is the correct one. Whenever a feature needs to see "every call to X" and X is a library function you didn't compile, a name list will quietly miss the call sites that don't go through your wrapper. The recording saw them; ask it. One honest limit remains: the scan is still scoped by a hand-maintained namespace allowlist (`db-nss`), so a *new* DB-touching namespace must be added to that set or its reads won't be surfaced.

## Building the construction tree

Everything so far reads the recording one fact at a time. `build-spans` is where those facts become the structure the overlay actually renders: it takes a `read-window` result and returns a map of span-id → span plus the root ids -- the middleware-to-view call tree, each node carrying its name, summarized args and return, db-ops, and the flags the UI keys off. It is the densest function in `trace.clj`, and rather than walk every local here I'll lay out its shape and the three tricks that make it more than a `:parent-idx` reduce. The full defn lives in `dev/trace.clj` (look for `build-spans`, around line 462); it reads best with the whole function in front of you, so open it there as you follow along.

Think of it as **two passes layered into one `let`**. The first pass is the plain tree: a frame is *kept* if it is neither noise nor a folded duplicate, and `eff-parent` re-parents each survivor onto its nearest kept ancestor so dropping a noise frame never severs the tree. That alone gives a correct call tree -- `:roots-rt` and `:children-rt`, the *temporal* parenting, "where each frame actually ran." The second pass is the corrections that make the tree read the way a human expects, and it is where the interesting work is.

Three tricks earn their place:

- **Folding dispatch artifacts.** An arity-default in a multi-arity fn (`(f a b)` → `(f a b nil)`) or a tail self-call records as two frames with the *same name* returning the *same element*. Left in, they duplicate the node and double-count the per-instance index -- which is what makes "the third `recipe-card`" resolvable, so the bug is silent but fatal to the DOM join. `folded?` spots the pattern (nearest non-noise ancestor shares the name) and collapses the pair.

- **Call-site recovery.** FlowStorm records where a function is *defined*, but a `:fn-call` carries no coordinate for where it was *invoked* -- and the construction view wants the call site, not just the home. The recovery is structural: a call expression's value is logged in the *caller's* frame as an `:expr` that fires right after the (inspector-wrapped) callee returns, so the first expr in the caller with an index past the callee's `:ret-idx` is the `(callee …)` literal, and its `:coord` resolves to source. The definition site, by contrast, comes free from the form registry -- so even DOM-less frames (middleware, handlers, db fns) stay navigable.

- **Lexical re-parenting of lazy frames.** This is the subtlety that would otherwise make the tree lie. A `(for …)` body is a lazy seq, realized during HTML serialization under the inspector's `tag-tree` walk -- *not* where it was written. So `fmt-instant`, written inside `users-table`, runs under `base-layout`; the temporal tree is correct but useless. When the path to the runtime parent crosses an `iter--` frame *and* that for-body's owning fn appears exactly once (so the re-parenting is unambiguous), we move the frame back to where it was written. We keep both parentings -- lexical and temporal -- so the overlay can offer a toggle, and flag the moved frame `:lazy` with a link to where it ran.

The exception case rides along: the innermost `:fn-unwind` is the throw origin, and its `:coord` pins the throwing expression onto the nearest kept frame, surfaced as `:threw`. The body of the `let` then runs `->span` over every kept index -- attaching args, return, source coordinates, instance rank, db-ops, a `value-morph` (a from→to summary of the primary data arg, with a key-diff when both ends are maps), and the lazy/throw flags -- and assembles the two child maps and two root lists into the final `{:roots :roots-rt :spans}`.

## N+1 detection, for free

We already have every db-op keyed to its form. A query form that ran two or more times in one request is the classic N+1 signal -- a query inside a `for`, or a redundant lookup across the middleware chain. `detect-repeats` is a pure projection over the spans we just built:

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

`project` assembles the response the overlay first fetches -- request metadata, the recording flag, the span tree, and the repeats. (`recording?` just asks FlowStorm whether the recorder is on, so the overlay can warn if it isn't.)

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

The middleware mounting is the matching gate on the *write* side. `wrap-trace` is added to the stack only when the ClojureStorm system property is present, and even then only via `requiring-resolve` of the dev namespace -- so plain `:dev` (let alone prod) never adds it *or* loads `trace`. It goes outermost, so its timeline window spans the whole synchronous handler:

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

## What you now have

With one dev alias, the `trace` namespace, and one route, a request under `clojure -M:dev:storm:repl` now records itself, and `GET /dev/__trace/:id` returns the whole construction as JSON: the middleware-to-handler-to-domain-to-view call tree, each frame's recorded args and return summarized as ValueRefs, every Datomic read as real Datalog at its basis-t -- including the raw `d/as-of`/`d/history` time-travel reads a wrapper allowlist would miss -- plus an N+1 signal, both lexical and temporal parentings, and the throw site if the request 500'd.

```
wrap-locale › … › recipes-index
  recipe-show
    recipe-by-id        ⛁ (d/entid …) → 17592186045442   ⛁ pull* (d/pull …) → {n=10} @t1031
    forks               ⛁ (d/q '[:find [?c ...] …]) → [] @t1031
    version-history     ⛁ (d/history db) → db   ⛁ (d/as-of db tx) → db   ⛁ (d/pull …) @t1031
    recipe-detail
      author-name → "Bob"   text-block → 3   app-layout › base-layout › …
```

That JSON is the descriptive whole-page view. [The next chapter](15-construction-view-overlay.md) adds the *targeted* projections over the same recording -- flow mode and the dossier -- and renders all of it in the page.
