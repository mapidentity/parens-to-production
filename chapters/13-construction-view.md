# The Construction View: From a Rendered Element to the Query Behind It

[The inspector chapter](12-inspector.md) answered one question precisely: *where did this element come from?* Hold a key, hover a badge, and your editor opens the exact `.clj` line that produced it. That is a map from a pixel to a **source location** — a static fact, welded onto the Hiccup at read time.

This chapter answers a different question, the one you actually ask when something looks wrong: *how was this whole page built, and where did this value come from?* Not "what line is this `<td>`," but "which functions ran, which Datomic queries fired, at what basis-t, and which of them produced the stale title I'm staring at." That is not a static fact. It is a recording of one request's execution, tied back to the DOM the request produced.

The inspector is the *spatial* half — element to code. This is the *temporal* half — element to the code-and-data that ran. The two share exactly one thing, and it is the thing that makes the second half cheap: the welded coordinate. The inspector stamps `data-myapp-name="myapp.web.views/recipe-card"` on every component root. If our execution trace records frames keyed by the same `"ns/fn"` string, then a clicked element and the function call that rendered it are joined by a plain attribute selector — no new index, no fuzzy matching. We reuse the *format*, not the mechanism.

Everything here is dev-only and **structurally absent** from production, the same standard the rest of our dev infrastructure holds itself to. But this feature reaches that standard a different way than the inspector did, and the difference is the first decision worth explaining.

---

## Why not just extend the inspector?

The obvious move is to generalize what we already have. The inspector auto-instruments every view function by wrapping its var:

```clojure
;; myapp.web.inspector — the existing pattern
(alter-var-root v (constantly
  (with-meta (fn [& args] (tag-hiccup (apply orig args) src nm)) {::orig orig})))
```

Replace `tag-hiccup` with "open a span / run / close a span," thread a dynamic `*current-span*`, and you have a hand-rolled tracer. We built exactly that first, and it works — for a while. Then you notice what it *can't* see. `alter-var-root` wraps **vars**. Anonymous functions, the bodies of `map`/`reduce`/`for`, a tight numeric loop like `line-diff` — none of those are vars, so none of them are spans. You get an accurate tree of *named-function edges* and nothing inside them. For "which queries built this page," that happens to be enough, because the load-bearing edges are all named domain functions. For "trace this value back through the code," it is not: the value was shaped by a `(:user/display-name (:recipe/user recipe))` buried in a view, and a var-wrapper never sees that expression at all.

You could push harder — instrument more, propagate context across threads, capture argument values without pinning them — but at that point you are rebuilding an omniscient debugger by hand. Someone already built one, at the compiler level, and it sees *every* sub-expression.

> **Decision — adopt the recording, build the correlation.** The right division of labour is: let **ClojureStorm** record the execution (it is unbeatable at that, and goes deeper than any var-wrapping we'd write); keep the *correlation to the rendered DOM* ours, because nobody else has our welded coordinate. We adopt the engine and build the part that is genuinely new. The seam between the two is a string — `"ns/fn"` — not a shared mechanism.

## ClojureStorm as a dev-only compiler

[FlowStorm](https://www.flow-storm.org/) is a time-travel debugger for Clojure. Its companion, **ClojureStorm**, is a drop-in build of the Clojure compiler that emits instrumentation into every namespace it compiles, recording each sub-expression's value as your program runs. You opt in by *swapping the compiler*, which sounds drastic until you realise it is exactly the dev/prod split we already use everywhere else — a `:dev`-only dependency.

```clojure
;; deps.edn — a dev-only alias. Run with: clojure -M:dev:storm
:storm {:classpath-overrides {org.clojure/clojure nil}        ; remove vanilla Clojure
        :extra-deps {com.github.flow-storm/clojure {:mvn/version "1.12.5-1"}
                     com.github.flow-storm/flow-storm-dbg {:mvn/version "4.6.0"}}
        :jvm-opts ["-Dclojure.storm.instrumentEnable=true"
                   "-Dclojure.storm.instrumentOnlyPrefixes=myapp."   ; our code only
                   "-Dclojure.storm.instrumentAutoPrefixes=false"
                   "-Dflowstorm.startRecording=true"                 ; headless, no GUI
                   "-Dmyapp.dev=true"]}
```

`:classpath-overrides {org.clojure/clojure nil}` drops the normal Clojure jar so ClojureStorm's build provides `clojure.core`; `instrumentOnlyPrefixes=myapp.` keeps the recording to *our* functions (instrumenting `clojure.core` would bury the signal under millions of frames). Production never sees any of this: it resolves `org.clojure/clojure`, sets no storm properties, records nothing. The compiler swap is the heaviest dev affordance in the book — it slows startup and makes the first hit to a page noticeably slower — which is precisely why it lives behind an alias you choose to type.

> **Why a compiler and not a Java agent or OpenTelemetry?** A Java agent (Byte Buddy, AspectJ) is the same idea, less Clojure-aware — dominated. OpenTelemetry is the industrial answer and the right tool when you want spans to *leave the process* into Jaeger or Tempo; but its spans are coarse (HTTP, JDBC, manual boundaries) and exported elsewhere, neither of which serves an *in-page* view at *expression* granularity. We are building a debugger affordance, not an observability pipeline. The two are answers to different questions, and conflating them gets you the worst of both.

## Reading the recording

ClojureStorm keeps one **timeline per JVM thread**: an append-only sequence of entries. We never call the debugger UI; we read the timeline directly through `flow-storm.runtime.indexes.api`. Three entry shapes matter, and `as-immutable` turns each into a plain map:

```clojure
{:type :fn-call   :fn-ns "myapp.recipe.core" :fn-name "recipe-by-id"
 :form-id 8829    :fn-args [<db> #uuid"…"] :parent-idx 41 :ret-idx 58}
{:type :expr      :coord [4 1] :result #{17592186045442 …} :fn-call-idx 42}
{:type :fn-return :result {…} :fn-call-idx 42}
```

Four facts, stacked, are everything we need:

1. **A `:fn-call`'s own index in the timeline is its identity.** Children point back to it via `:parent-idx` (other calls) and `:fn-call-idx` (the expressions inside it). So the call tree falls straight out of `:parent-idx` — no separate tree to assemble.
2. **`:fn-ns` and `:fn-name` are strings**, so `(str fn-ns "/" fn-name)` is `"myapp.web.views/recipe-card"` — **byte-identical** to the `data-myapp-name` the inspector already stamps on the DOM. That string is the join key, and it costs us nothing.
3. **`:fn-args` and `:result` carry the real recorded values.** A `pull*` frame's first argument *is* the Datomic db, so its basis-t is one `(d/basis-t …)` away. We never need to copy or pin these — they live in FlowStorm's recorder; we only read them when asked.
4. **An expression's `:coord` plus the enclosing form's `:form-id`** resolve, via `get-form-at-coord`, to the *source sub-form* as data — line metadata included. That last fact is what makes the data-flow half possible; we return to it below.

Reading one request is reading a **slice** of one thread's timeline. We realise the slice once, aligned back to absolute indices, and index the fn-calls by their identity:

```clojure
(defn- read-window [tid start end]
  (let [tl    (ia/get-timeline tid)
        slice (into [] (comp (drop start) (take (- end start)) (map ia/as-immutable)) tl)
        calls (persistent!
                (reduce (fn [acc [j m]]
                          (if (= :fn-call (:type m)) (assoc! acc (+ start j) m) acc))
                  (transient {}) (map-indexed vector slice)))]
    {:calls calls
     :exprs-by-call (->> slice (filter #(= :expr (:type %))) (group-by :fn-call-idx))
     ,,,}))
```

## Per-request scoping, and the gift of a synchronous handler

Where do `start` and `end` come from? A Ring middleware, placed outermost, records the thread's timeline length before and after the handler:

```clojure
(defn- trace-request [handler req]
  (let [tid   (thread-id)
        id    (str (java.util.UUID/randomUUID))
        start (timeline-len tid)
        resp  (handler req)
        end   (timeline-len tid)]
    (put! id {:id id :tid tid :start start :end end
              :uri (:uri req) :method (:request-method req) :status (:status resp) :ms ,,,})
    (cond-> (assoc-in resp [:headers "X-Myapp-Trace"] id)
      (html? resp) (update :body stamp-html id))))   ; weld data-myapp-trace-id onto <html>
```

This is sound for one reason, and it is the same reason the inspector's `for`-row tagging was sound: **our request path is synchronous**. `http-kit` serves each request start-to-finish on one worker thread; every handler in `handler.clj` calls the domain layer and Datomic *inline*; `hiccup2`'s `html` macro forces its sequences eagerly before the response map is returned. So a request is *exactly* the slice `[start, end)` of one thread's timeline — no `future`, no `core.async`, no context-propagation machinery to chase a span across threads. (An async handler would break this; the day one appears, the slice boundary is the thing to revisit. It is worth a lint that fails the build if `future`/`core.async` shows up in a request namespace.)

Notice what `trace-request` does **not** do: build the tree. It stores a four-field *descriptor* and returns. The projection — walking the slice, resolving forms, summarising values — happens later, when the overlay fetches the trace.

> **Decision — project lazily, off the hot path.** An early version built the whole tree inside `wrap-trace`, which taxed every page and, worse, copied recorded values into our own store (pinning large pulled maps and skewing the very memory numbers the admin dashboard reports). Storing a `{:tid :start :end}` descriptor and projecting on fetch fixes both: the request pays almost nothing, the heavy values stay in FlowStorm's recorder where they belong, and the expensive form-level analysis below — which we could never afford per-request — becomes affordable, because it only runs for traces a human actually opens.

The descriptors live in a small bounded ring buffer; the endpoints build on demand:

```clojure
(defn get-trace-json [id] (some-> (get-desc id) project json/write-value-as-string))
(defn get-flow-json  [id name idx src] (some-> (get-desc id) (flow name idx src) json/write-value-as-string))
```

> **Trade-off — the ring buffer bounds *our* store, not the recorder.** Lazy projection keeps the heavy values out of *our* heap, but it does not make memory unbounded-safe. FlowStorm's per-thread timeline is **append-only and grows for the life of the dev `:storm` JVM**; our descriptor ring buffer caps only the descriptors we keep, not the recording behind them. There is no automatic ring-drop on the recorder side — `set-thread-trace-limit` *throws* once the cap is exceeded rather than evicting the oldest entries — so you reclaim memory deliberately: call `(trace/clear-recordings!)`, hit the `/dev/__trace-clear` endpoint, or restart the REPL. This is a dev-only JVM you drive yourself, so a periodic clear is the whole discipline; it is worth knowing the recorder never trims itself.

`project` turns a descriptor into the call tree. The only subtlety is filtering: ClojureStorm records the inspector's own wrapper frames, `for`-loop machinery, and pure date-shuffling helpers, none of which belong in a *construction* view. A `noise?` predicate drops them, and the tree re-parents each surviving node onto its nearest surviving ancestor, so the chain stays connected.

## DB ops from expressions, not from names

Here is the part where reading *expressions* — not just function calls — pays off, and where the naive design fails in an instructive way.

The tempting way to surface "what did this page query" is to flag the database wrapper functions: `pull*`, `q*`, `transact*` in `myapp.db.core`. List their names, badge any frame whose name is on the list. It is a five-line predicate, and it is wrong, because **half our reads never go through the wrappers.** `recipe/core` calls `d/q`, `d/history`, `d/as-of`, and `d/entid` directly — and those are not even functions *we* compiled, so ClojureStorm records no `:fn-call` for them at all. A name-allowlist is structurally blind to exactly the time-travel reads a recipe-versioning app is built around.

But a raw `(d/q '[...] db)` is still an **expression** inside an instrumented function, and ClojureStorm recorded it. Its `:coord` and the enclosing function's `:form-id` hand us the sub-form as data:

```clojure
(def ^:private datomic-ops
  #{"q" "pull" "pull-many" "entid" "as-of" "history" "transact" "datoms" "entity"})

(defn- datomic-call? [sf]
  (and (seq? sf) (symbol? (first sf))
       (#{"d" "datomic.api"} (namespace (first sf)))
       (contains? datomic-ops (name (first sf)))))

(def ^:private db-nss
  #{"myapp.recipe.core" "myapp.db.core" "myapp.auth.core" "myapp.admin.core"
    "myapp.analytics.db" "myapp.web.handler"})

(defn- collect-db-exprs [{:keys [calls exprs-by-call]}]
  (->> (for [[i es] exprs-by-call
             :let [m (calls i)] :when (and m (contains? db-nss (:fn-ns m)))
             e es
             :let [sf (ia/get-form-at-coord (:form-id m) (:coord e))] :when (datomic-call? sf)]
         {:call-idx i :sf sf :e e})
       (sort-by :call-idx)))
```

This *unifies* the two cases instead of special-casing them. The `d/pull` inside `pull*` and the raw `d/q` in `all-recipes` are both just `d/*` expressions; each becomes a `db-op` attached to the function it ran in. And because we have the sub-form, we have **the real datalog as data** — not "a query happened" but the query itself. The basis-t comes from the nearest in-scope db value (our domain functions take `db` first; an `as-of` read passes the time-travel db down, so the basis-t is automatically the historical one). One cosmetic touch — rendering `(quote x)` as `'x` — and the queries read exactly as written:

```
recipe-by-id     (d/entid db [:recipe/id id])                                         @t1031
forks            (d/q '[:find [?c ...] :in $ ?e :where [?c :recipe/forked-from ?e]] …) @t1031
version-history  (d/as-of db tx)  → db                                                @t1031
pull*            (d/pull db pattern eid)  → {n=10}                                     @t1031
```

> **Lesson — instrument the boundary you can name, but *read* the expressions you can't.** The wrapper allowlist was the readable design; the expression scan is the correct one. Whenever a feature needs to see "every call to X" and X is a library function you didn't compile, a name list will quietly miss the call sites that don't go through your wrapper. The recording saw them; ask it. One honest limit remains: the scan is still scoped by a hand-maintained namespace allowlist (`db-nss`), so a *new* DB-touching namespace must be added to that set or its reads won't be surfaced.

## Part I — the construction view

With the tree projected and DB ops attached, the browser half is the inspector's overlay, extended. The page already carries `data-myapp-trace-id` on `<html>` (welded by `wrap-trace`) and `data-myapp-name` on every component root (welded by the inspector). A self-contained script — shipped in the same dev-only asset block as `inspector.js`, inert without a trace id — fetches the trace and renders it.

Each node shows its function, its return, and its database ops; hovering a node highlights the DOM region that function produced, by selecting on the shared coordinate:

```javascript
function highlight(name) {
  var nodes = document.querySelectorAll('[data-myapp-name="' + cssEsc(name) + '"]');
  // …draw a bounding box over every rendered instance…
}
```

The tree reads like the page being built. For a recipe detail page:

```
wrap-locale › … › recipes-index
  recipe-show
    recipe-by-id        ⛁ (d/entid …) → 17592186045442   ⛁ pull* (d/pull …) → {n=10} @t1031
    lineage             → 0
    forks               ⛁ (d/q '[:find [?c ...] …]) → [] @t1031
    version-history     ⛁ (d/history db) → db   ⛁ (d/as-of db tx) → db   ⛁ (d/pull …) @t1031
    recipe-detail
      author-name → "Bob"   text-block → 3   app-layout → base-layout → …
```

You can *see* the handler fan out into four reads, the time-travel `as-of` feeding the version history, and the view tree assembling underneath — each query at its basis-t, each value flowing out. That is the descriptive half: what ran, and how.

## Part II — flow: from one element to the query behind it

The construction view describes the whole page. Flow mode answers about *one* element: **Alt+click** any rendered thing and trace its value back. Two capabilities make this more than the inspector could do.

**Per-instance resolution.** Eight recipe cards share one `data-myapp-name`; the inspector, keyed on source location, can only highlight all of them — the honest floor of "one call site, N renders." But the *timeline* has order: the k-th card in the DOM is the k-th `recipe-card` call in the recording. So clicking the third card resolves to *that instance's* frame — its actual `recipe` argument, its return, its ancestor chain. The overlay sends the component name, the DOM index among its siblings, and the clicked element's source line; the server picks the k-th matching frame.

**Eid-matched source.** A recipe card renders data that some *sibling* call fetched earlier — `all-recipes`' `d/q` and the per-recipe `pull`, not anything in the card's own subtree. Which pull, of the eight, produced *this* card? We have the card's entity id (from its argument map) and every db-op's recorded result, so we ask Datomic identity directly:

```clojure
(defn- result-has-eid? [result eid]
  (cond (map? result)        (= eid (:db/id result))
        (set? result)        (contains? result eid)              ; relation find-spec [:find ?e ...]
        (sequential? result) (some #(or (= % eid) (= eid (:db/id %))) result)  ; collection [:find [?e ...]]
        :else false))
```

The flow card then shows the path, the value, and — flagged out of all the candidate reads — the *one* query and the *one* pull that produced this entity:

```
↳ recipe-card #3/8
wrap-locale › … › recipes-index › app-layout › base-layout › recipe-card
produced by
  ⛁ (d/q '[:find [?e ...] :where [?e :recipe/id]] db)   → [n=8]            @t1031
  ⛁ (d/pull db pattern eid)                             → {#…442 n=11}    @t1031
entity #17592186045442 — to see why it holds this value, check its write history (d/history)
```

That last line is the honest part. The trace explains how *this request* rendered the value; it does not explain why the *entity* holds it. The classic case is a fork showing a stale title: `fork!` copied the title onto a new entity days ago, and to Datomic the fork's current title is perfectly correct — the read-trace is green and self-consistent, and unhelpful, because the cause was a write in a different request. So flow mode does not pretend to be a root-cause oracle. It **narrows the suspects, shows the data path, and pivots to the write history** — handing you the entity id and pointing at `d/history`, which is where that question actually lives.

> **Trade-offs — flow mode.**
> - **Per-instance resolution counts only element-producing frames.** The k-th match is taken over frames that actually emit an element (an `element?` filter), so `nil`/conditional components (`when`/`when-let` that render nothing) no longer skew the index. The one case still unhandled is genuine **render reordering** — a `sort`/`reverse` between data order and DOM order — where the k-th frame and the k-th DOM node legitimately disagree; when an index can't be resolved the overlay says "couldn't resolve which one" rather than guessing.
> - **basis-t is "nearest in-scope db," not a proof.** When several db values are live it is the one the function was handed — correct in this codebase, an approximation in principle.
> - **Eid-matching is identity, not lineage.** It tells you which query *returned* the entity, not which transform shaped the displayed string. Following a value through `str`/`subs`/i18n/markdown to the exact datom is true taint tracking — the research-hard frontier (the [Whyline](https://faculty.washington.edu/ajko/papers/Ko2004WhylineAlice.pdf) asked this question of UIs two decades ago and it is still not a shipping feature). We stop one honest step short of it, and say so.

## Keeping production clean

Trace every piece and confirm it vanishes:

- **The compiler.** `:storm` is a dev alias. Production resolves `org.clojure/clojure`, sets no storm properties, and records nothing. With no recording there is no timeline to read.
- **The middleware.** `wrap-trace` is added to the stack only when the `clojure.storm.instrumentEnable` system property is present, and only via `requiring-resolve` of a namespace that lives under `dev/`. Plain `:dev` — never mind prod — neither adds it nor loads the namespace.
- **The endpoints.** `/dev/__trace/:id` and `/dev/__flow/:id` resolve their handler through `requiring-resolve`, which is `nil` without the dev namespace, so they 404 in production.
- **The endpoints are unauthenticated, and return real recorded values.** Anything that can reach `/dev/__trace` or `/dev/__flow` gets the actual recording — argument and result previews that can include user emails and recipe content. That is acceptable for a dev-only, loopback service — the same posture as `/dev/ws` from the inspector chapter — don't expose the dev server.
- **The trace id and the overlay.** Two different gates, worth separating. The trace id is reached *via the storm property*: `data-myapp-trace-id` is stamped only by the middleware, which is itself only mounted when `clojure.storm.instrumentEnable` is set. The overlay is not gated on the storm property at all — it ships in the **dev asset block** (the same `requiring-resolve` gate as the inspector, so it's prod-absent), and at runtime it is simply *inert* without a storm-stamped `data-myapp-trace-id` to act on. So in production the script is absent; under plain `:dev` (no `:storm`) it may load but finds no trace id and does nothing.

The feature is structurally absent, not merely disabled — the same bar as the inspector, reached through the storm property and `requiring-resolve` rather than the inspector's `dev?` resource check.

## Design decisions worth noting

- **Adopt the engine, build the correlation.** ClojureStorm records far more, and far more reliably, than the var-wrapping we would write by hand. The original contribution is not the recording; it is welding a *runtime* span id onto the SSR'd DOM the way the inspector welds a *static* source location — and the unfair advantage is that the coordinate already exists.
- **Share the format, not the mechanism.** The inspector and the tracer meet at a string, `"ns/fn"`. The inspector stays a zero-runtime static artifact; the tracer is a runtime recording. Unifying them under one engine would make the inspector depend on a running recorder for no benefit. The right amount of coupling is data.
- **Lazy projection.** Storing a descriptor and projecting on fetch keeps the request hot path cheap and the expensive analysis affordable — and keeps every recorded value in FlowStorm's recorder instead of pinning it in ours.
- **Read expressions, not just calls.** Function-call frames give the skeleton; the expression traces give the datalog, the basis-t, the raw-bypass reads, and the eid-matched source. The depth that makes flow mode work is exactly the depth a var-wrapper could never reach — which is why we adopted the compiler in the first place.

## What you now have

Building on the inspector's welded coordinate, with one dev alias, one dev namespace (`trace`), two dev endpoints, and one inlined script, you get a **construction view**:

- Press `Alt+Shift+I` and the inspector still answers *where is this element from?* Press `Alt+Shift+C` and the construction view answers *how was this whole page built?* — the middleware-to-handler-to-domain-to-view call tree, each frame's return, and every Datomic read as real datalog at its basis-t, including the raw `d/as-of`/`d/history` time-travel reads.
- **Alt+click** any element and flow mode resolves the exact instance, walks its data path, and flags the one query and the one pull that produced its entity — then points at `d/history` for the question a read-trace honestly can't answer.
- Zero production footprint — no compiler swap, no middleware, no endpoints, no script; all structurally excluded.

The inspector rested on one trick: Hiccup is plain data, so you manufacture its source location and weld it to the value. This rests on the dual: execution is *also* data once a recording compiler is in the loop — so you read the trace, key it on the same coordinate the inspector already stamped, and the page tells you not just where each element is from, but everything the server did to put it there.
