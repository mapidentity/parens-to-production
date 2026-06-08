# A Bidirectional Source Inspector for Server-Rendered Hiccup: From Element to Code and Back


In [the live-reload chapter](06-live-reload.md) we closed the gap between saving a file and seeing it in the browser. This chapter closes two more, in both directions:

- **Element → code.** You spot a misaligned badge in the admin dashboard; hold a key, hover it, and your editor opens the exact `.clj` line that produced it.
- **Code → element.** You put your cursor on a view function (or a call to one) in the editor, and the matching element lights up in the browser — even telling apart the component's *definition* from this particular *call site*.

Front-end frameworks have had the first half for years (React/Vue/Svelte inspectors). The second half — editor-cursor-drives-the-browser — is rarer even there. We render HTML on the server from Hiccup, plain Clojure data with no source information attached, so we have to manufacture all of it. This post is the full build: the why, the how, and the trade-offs.

It reuses two things from [the live-reload chapter](06-live-reload.md) — the file watcher and the `dev-reload` WebSocket — and hooks into the `base-layout` from [the Hiccup views chapter](11-hiccup-views.md). Everything here is dev-only and **structurally absent** from production builds, the same as the rest of our dev infrastructure. If you read strictly in order, the Hiccup views chapter's layout sections are the relevant background.

---

## Part I — Element → code

### Why this is hard for Hiccup specifically

Svelte and JSX get element-level source locations for free because a *compiler* owns the template. It parses the template into an AST where every node knows its character offset, and in dev mode it emits that position on the element. The framework hands itself the answer.

Hiccup has no compiler step that sees your source text. A view is an ordinary function returning vectors:

```clojure
(defn user-row [u]
  [:tr
   [:td (:user/email u)]
   [:td (:user/created-at u)]])
```

By the time `[:tr …]` exists it is a runtime value with no idea what line it came from. Worse, the default Clojure reader attaches **no** line metadata to vector literals:

```clojure
(meta (read-string "[:div [:span \"hi\"]]"))
;;=> nil
```

So the obvious approaches are dead ends. We cannot ask a Hiccup value where it lives, and the reader will not tell us. The whole feature hinges on getting around exactly this.

### Three coordinates of "where did this come from?"

A rendered element actually has *three* distinct source locations, and we will produce all three because the reverse direction needs them:

| Coordinate | Attribute | Means |
| --- | --- | --- |
| **Element** | `data-myapp-src` | the exact `[:td …]` literal's `file:line:col` |
| **Component** | `data-myapp-name` | the view fn that produced this subtree (`ns/fn`) |
| **Call site** | `data-myapp-callsite` | where *this instance* was invoked from |

The element coordinate is the hard, interesting one — it did not exist for Hiccup. The other two fall out of the same plumbing. The distinction between *component* and *call site* matters more than it looks; we will return to it when a component is rendered from several places.

### The insight: `tools.reader` keeps your line numbers, and so does the compiler

Two facts, stacked, make element-level mapping possible.

**Fact one:** `clojure.tools.reader` — a pure-Clojure reader maintained by core — *does* attach `:line`/`:column`/`:end-line`/`:end-column` to every nested form, vectors included, at every depth:

```clojure
(require '[clojure.tools.reader :as tr]
         '[clojure.tools.reader.reader-types :as rt])

(let [form (tr/read (rt/indexing-push-back-reader "[:div [:span \"hi\"]]"))]
  {:outer (meta form) :inner (meta (nth form 1))})
;;=> {:outer {:line 1 :column 1 :end-line 1 :end-column 21}
;;    :inner {:line 1 :column 7 :end-line 1 :end-column 19}}
```

The **end** positions matter later (the reverse direction does span-containment), and they are present too. Where the default reader gave `nil`, tools.reader gives every `[…]` its own coordinates.

**Fact two — the one that makes it all work:** the Clojure compiler *preserves* a vector literal's metadata onto the runtime value, and does so even for vectors built in a loop:

```clojure
(def render (eval '(fn [xs] (mapv (fn [x] ^{:line 9} [:li x]) xs))))
(map meta (render [1 2 3]))
;;=> ({:line 9} {:line 9} {:line 9})
```

Three `<li>`s, each tagged with the *one* source line of their template. That is exactly the semantics we want: clicking a `for`-generated row should land on the `for`'s `[:tr …]` line, not on three different places.

Put the facts together:

> Read our view namespaces with `tools.reader` instead of the default reader, then `eval` the forms. Now every Hiccup element our views produce carries its own `:line`/`:column` in its metadata — welded onto the value, riding along through every `for`, `if`, and helper call, all the way to the rendered page.

There is no separate index to keep in sync and no fragile matching of DOM nodes back to source. The position *is* part of the value.

### Recognising an element, and stamping the file onto it

tools.reader gives `:line`/`:column` but not which *file* a form came from, and a page mixes elements from many namespaces. So as we load each file we stamp the file path onto every element literal.

First, recognise an element literal — not every vector is Hiccup (Datomic pull patterns `[:db/id …]`, `let` bindings, tagged tuples). We tag only vectors whose head is an unnamespaced HTML/SVG tag keyword:

```clojure
(ns myapp.web.inspector
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(def ^:private dev?
  ;; Detect dev by a classpath resource, NOT requiring-resolve — see the design
  ;; note at the end; requiring the hot-reload ns here can deadlock on a
  ;; circular load and silently turn the whole feature off.
  (some? (io/resource "hot_reload.clj")))

(def ^:private html-tags
  #{"a" "div" "span" "p" "ul" "ol" "li" "table" "tbody" "tr" "td" "th" "thead"
    "form" "input" "button" "label" "section" "nav" "header" "footer" "h1" "h2"
    "h3" "img" "svg" "g" "path" "circle" "rect" "text" ,,, })   ; full set in the repo

(defn element?
  "True when x is a Hiccup element vector with an unnamespaced HTML/SVG tag head."
  [x]
  (and (vector? x)
       (keyword? (first x))
       (nil? (namespace (first x)))
       (contains? html-tags (first (str/split (name (first x)) #"[.#]")))))
```

The `str/split` on `#"[.#]"` strips Hiccup's `.class`/`#id` shorthand, so `:div.card#main` matches `"div"`. This `element?` gate is what makes everything that follows **safe to apply blindly** across every view: a non-Hiccup vector can never be touched.

Now the walk that adds the file, preserving all existing metadata:

```clojure
(defn add-file-meta
  "Stamp :myapp/file onto every Hiccup element literal in `form`. Preserves
   structure and all existing metadata; only element vectors are touched."
  [file form]
  (cond
    (vector? form)
    (let [walked (mapv #(add-file-meta file %) form) m (meta form)]
      (with-meta walked
        (if (and m (:line m) (element? form)) (assoc m :myapp/file file) m)))
    (map? form)
    (with-meta (into (empty form)
                     (map (fn [[k v]] [(add-file-meta file k) (add-file-meta file v)])) form)
               (meta form))
    (set? form) (with-meta (into (empty form) (map #(add-file-meta file %)) form) (meta form))
    (seq? form) (with-meta (apply list (map #(add-file-meta file %) form)) (meta form))
    :else form))
```

The care to re-attach `(meta form)` on lists/maps/sets matters: `clojure.walk/postwalk` would *drop* metadata as it rebuilds collections, throwing away the very line numbers we are keeping. We rebuild by hand so nothing is lost.

### The loader (and the component layer)

The *component* coordinate — which view function produced a given root element — can't come from a literal's source position; it needs the enclosing `defn`. The loader supplies it invisibly by instrumenting every function a view namespace defines, so views stay plain `defn` with no annotation.

The loader, under `dev/` (never on the prod classpath), reads each file with tools.reader, and then does three things to it: stamps file metadata (element layer), **auto-instruments every function the namespace defined** (component layer), and **indexes** it (for the reverse direction, Part II).

```clojure
(ns inspector-load
  (:require [clojure.string :as str]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]
            [myapp.web.inspector :as inspector]))

(defn tr-load!
  "Read a .clj view file with tools.reader; eval each form with element + call-
   site tags; then instrument its fns (component layer) and index it (reverse)."
  [path]
  (let [file  (str/replace path #"^.*/src/" "")           ;; classpath-relative
        rdr   (rt/indexing-push-back-reader (slurp path))
        eof   (Object.)]
    (binding [*ns* *ns*, *file* file]
      (let [read1 #(tr/read {:eof eof :read-cond :allow} rdr)
            ns-form (read1)]
        ;; Eval the (ns …) form FIRST so the namespace and its aliases exist
        ;; before we read the rest — see the note below.
        (eval ns-form)
        (let [body  (loop [acc (transient [])]
                      (let [form (read1)]
                        (if (identical? form eof) (persistent! acc) (recur (conj! acc form)))))
              forms (into [ns-form] body)
              names (inspector/view-defn-names forms)]    ;; fn names → call heads to wrap
          (doseq [form body]
            (eval (inspector/add-file-meta file (inspector/wrap-callsites names file form true))))
          (inspector/instrument-ns! (ns-name *ns*))           ;; component layer
          (inspector/index-ns! file (ns-name *ns*) forms))))))  ;; reverse-direction index
```

Two ordering subtleties hide in that little loop. We need the file's function names *before* evaluating any body form (so call-site wrapping knows which calls are components), which pushes us toward reading everything up front. But we must **evaluate the `ns` form before reading the rest**: `tools.reader` resolves auto-namespaced keywords like `::alias/kw` against the current namespace's aliases *at read time*, so the namespace has to exist first — read the whole file before the `ns` form runs and such a keyword throws, the file falls back to a plain `load`, and you silently lose its tags. So: read the `ns` form, eval it, *then* read the body. Binding `*file*` to the classpath-relative path also means the *vars* get a correct `:file` — exactly what the component layer reads:

```clojure
(defn instrument-var!
  "DEV: wrap a view fn so its returned root element is tagged with the var's
   location (data-myapp-src = the defn site) and name (data-myapp-name = ns/fn).
   Idempotent — unwraps to the original before re-wrapping on a reload."
  [v]
  (let [cur @v]
    (when (fn? cur)
      (let [orig (or (::orig (meta cur)) cur), m (meta v)
            src  (str (:file m) ":" (:line m) ":" (or (:column m) 1))
            nm   (str (ns-name (:ns m)) "/" (:name m))
            wrapped (with-meta (fn [& args] (tag-hiccup (apply orig args) src nm)) {::orig orig})]
        (alter-var-root v (constantly wrapped))))))

(defn instrument-ns!
  "Tag every fn the namespace defined. Wrapping a fn that returns non-Hiccup is
   safe (tag-hiccup is a no-op on non-elements), so we don't pick functions."
  [ns-sym]
  (doseq [[_ v] (ns-interns ns-sym) :when (and (var? v) (fn? @v))]
    (instrument-var! v)))
```

`tag-hiccup` is the one-element version of the tree walk below: if its argument is an element vector, it adds `data-myapp-src`/`data-myapp-name`; otherwise it returns it unchanged. Because `instrument-var!` reads the *var's* metadata (always present after a `defn`), the component tag works even for a function whose root is built dynamically — and clicking that root opens the function definition.

**How the loader hooks the live-reload watcher.** We `tr-load!` view namespaces instead of `load-file`, both at startup and on change — but only views, to avoid the tools.reader cost on files with no Hiccup. Views carry no inspector-specific marker, so we detect them by a **naming convention**:

```clojure
(defn- view-ns-file? [path]
  (str/ends-with? (str path) "views.clj"))   ;; web/views.clj, admin/views.clj, …
```

Wrap `tr-load!` in a `try` that falls back to `load-file` — a reader edge case must never break your reload loop.

> **Trade-off — convention over opt-in.** A naming convention means a view placed in a non-`views.clj` file silently won't be instrumented. We accept that: it is a dev affordance with a harmless failure mode, and the convention is one the project already follows. The alternative (a per-namespace marker like `^{:myapp/views true}`) is more explicit but adds ceremony; pick whichever your team prefers.

### Keeping the tags alive

Here is a subtlety worth calling out, because it produces a baffling symptom. **The source tags exist only because the loader applied them** — they live on the runtime functions (the `instrument-var!` wrappers) and on the element metadata that `tr-load!`'s tools.reader pass attached. So *any* re-definition of a view namespace that goes around the loader — a plain `load-file`, or evaluating the namespace from your editor/REPL (a "Load File", an eval of a single `defn`, or an editor's eval-on-save) — re-defs those functions with the *default* reader and no `instrument-ns!`, silently stripping every tag in that file until the next loader pass. The visible effect: "I edited a view, saved, and after the reload a bunch of elements lost their inspection border." It's easy to misattribute to the edit; the real cause is a second, untagged load behind the watcher.

The fix is a principle, not a mechanism: **`tr-load!` is the single source of truth for tagged view code.** Make it the *only* path that re-loads views and the problem can't occur. Concretely, let the file-watcher own reloads and turn off any editor "evaluate/load on save" for view namespaces — `tr-load!` already `eval`s into the running REPL, so an editor re-load of the same file is pure redundancy that happens to strip the tags.

We did briefly build the obvious "fix" — a var watch on each view fn that re-instruments whenever it's re-defined out of band — and then deleted it. It's the wrong direction: reactive instead of preventive, and not even airtight, because the re-tag is asynchronous, so a page fetch can land in the window between the strip and the heal (you see a partial page, and a manual refresh "fixes" it). Healing the symptom is strictly worse than removing the cause. If you genuinely need out-of-band re-defs to stay tagged — a REPL-eval-heavy flow where you hover the page before saving — the airtight shape is a dev request middleware that re-tags any dirtied file *before* serving (correct at fetch time, whatever the timing), but that's only worth its machinery for that narrow case.

What the loader *should* do is degrade gracefully on its own:

**Degrade per form, not per file.** `tr-load!` evals each tagged form inside a `try`. If a transformed form won't compile — an edit hit a construct the call-site/element rewrite mishandles — it loads *that one form* plain and logs which one, rather than letting the whole file fall back to an untagged load. The function is still defined and still root-tagged by `instrument-ns!`; only its element-level tags are missing, and the gap is logged, not silent. (`reload-changed!` keeps the outer `try`/`load-file` from the previous section as a last resort for a read error that kills the entire file.)

> **Lesson.** When a feature works by *decorating* runtime vars — instrumentation, tracing, `clojure.spec` instrumentation — a re-`def` behind your back silently undoes the decoration. The temptation is to re-apply it reactively with a var watch; resist it. Keep one authoritative path that applies the decoration, route all reloads through it, and there's no symptom to chase.

### Call-site tagging: telling instances apart

Here is the subtlety the three-coordinate table hinted at. Consider a dashboard that calls one component eight times:

```clojure
[:dl
 (stat-card "Total Users" total-users)
 (stat-card "Links Sent"  links-sent)
 ,,, ]                                  ; six more
```

All eight rendered cards carry the same `data-myapp-name` (`…/stat-card`) and the same `data-myapp-src` (the defn site). Nothing records *which call* produced *which card*. So if your cursor is on the "Total Users" call and you want only that card to light up, there is no way to know — the instance identity isn't in the DOM.

The fix is to tag each rendered instance with its **invocation site**. During the loader's read pass we wrap calls to view fns:

```clojure
(stat-card "Total Users" n)
;; becomes, at load time:
(myapp.web.inspector/tag-callsite "myapp/admin/views.clj:123:9" (stat-card "Total Users" n))
```

`tag-callsite` adds `data-myapp-callsite` to the result if it is an element, and is otherwise a no-op. The rewrite is done by `wrap-callsites`, which walks the form preserving reader metadata and only wraps calls whose head is an **unqualified** symbol naming a fn the file defined:

```clojure
(def ^:private no-wrap-heads
  ;; threading/doto rewrite their arg forms, and a quoted list is data — never
  ;; wrap a call inside these, or you change its meaning.
  #{"->" "->>" "some->" "some->>" "cond->" "cond->>" "as->" "doto" ".." "quote"})

(defn wrap-callsites [names file form wrap?]
  (cond
    (seq? form)
    (let [head        (when (symbol? (first form)) (name (first form)))
          child-wrap? (if (contains? no-wrap-heads head) false wrap?)
          walked      (with-meta (apply list (map #(wrap-callsites names file % child-wrap?) form))
                                 (meta form))]
      (if (and wrap? (call-head names form) (form-span form))
        (let [[l c] (form-span form)]
          (with-meta (list `tag-callsite (src-key file l c) walked) (meta form)))
        walked))
    (vector? form) (with-meta (mapv #(wrap-callsites names file % wrap?) form) (meta form))
    ,,, ))   ; map/set/else preserve metadata the same way
```

Now the two cases resolve correctly, and — this is the nice part — they are *the same mechanism*:

- **Distinct call sites** (the eight `stat-card`s): each gets a different `data-myapp-callsite`, so a cursor on one call lights up exactly one card.
- **A single call in a loop** (`(for [r recipes] (recipe-card r))`): one source site, so all its instances share one `data-myapp-callsite` — and lighting up all of them is *correct*. One place in the code, many renders.

> **Trade-offs — call-site tagging.**
> - **Per-instance precision has a floor.** A looped call can't distinguish iteration 3 from iteration 5 — they share a call site. That is the honest limit of "one source location → N renders," and the behaviour (highlight the whole family) is the right answer, not a bug.
> - **Rewriting source is delicate.** We only wrap unqualified calls to the file's own fns, and we refuse to descend into threading/`quote` forms (where wrapping would change semantics). Reserved names (`recur`, `let`, …) are excluded so we can never move a call out of tail position. The conservative guard loses call-site precision for components composed *through* a threading macro — which view code essentially never does — in exchange for never corrupting code.
> - **It is metadata-preserving by construction.** Every rebuilt collection re-attaches `(meta form)`; the wrapper inherits the call's reader span. If you get this wrong you silently break the element layer, so test that `data-myapp-src` still appears after wrapping.

### From metadata to attributes, at the render boundary

The metadata rides on the runtime Hiccup, but a browser can't read Clojure metadata. We translate it to attributes by walking the assembled tree just before stringification:

```clojure
(defn tag-tree
  "Add data-myapp-src to every element carrying :line + :myapp/file metadata."
  [node]
  (cond
    (vector? node)
    (let [m (meta node) children (mapv tag-tree node)]
      (if (and (:line m) (:myapp/file m) (element? node))
        (let [has-attrs? (map? (second children))
              attrs      (if has-attrs? (second children) {})
              body       (subvec children (if has-attrs? 2 1))]
          (into [(first children)
                 (assoc attrs :data-myapp-src
                        (str (:myapp/file m) ":" (:line m) ":" (or (:column m) 1)))]
                body))
        children))
    (seq? node) (doall (map tag-tree node))
    :else node))
```

We never call this directly — that would defeat Hiccup's compile-time precompilation in prod. We gate it behind a macro that vanishes in prod, and call it once, in the layout every page passes through:

```clojure
(defmacro tag-root [tree] (if dev? `(tag-tree ~tree) tree))

(defn base-layout [& body]
  (page/html5
    [:head ,,, ]
    [:body
     (tag-root body)                       ;; <- dev: tagged; prod: the bare tree
     ,,, ]))
```

The two layers compose cleanly. `tag-tree` only tags elements that still carry reader metadata; a component's *root* was rebuilt by `tag-hiccup`/`tag-callsite` (so its reader metadata is gone) and keeps its component/call-site tags, while every inner literal keeps its own `:line`. Roots resolve to their function; inner literals to their exact line.

### The browser overlay

The front end is a self-contained script, inlined with the `defn-asset` macro from the Hiccup views chapter so it is only ever served in dev. It opens its own connection to the `/dev/ws` endpoint, draws a highlight box on hover, shows a breadcrumb of tagged ancestors, and opens source on click.

Two pieces beyond the basics are worth showing.

**The breadcrumb folds component + call site.** As you hover, we walk the `data-myapp-src` ancestors into breadcrumb *steps*. A component instance carries two source locations on one node — its definition and its call site — so instead of two crumbs with the same name we fold them into one: the name once, then two tiny selectable glyphs, **λ** (the definition) and **()** (the call site):

```
… ▸ dl ▸ stat-card () λ ▸ dd
```

```javascript
// each tagged ancestor becomes one or two STEPS:
function chain(node) {
  var out = [], cur = node;
  while (cur) {
    if (cur.getAttribute && cur.getAttribute('data-myapp-src')) {
      var name = shortName(cur), call = cur.getAttribute('data-myapp-callsite');
      out.push({ node: cur, src: cur.getAttribute('data-myapp-src'),
                 name: name, kind: call ? 'defn' : 'element' });
      if (call) out.push({ node: cur, src: call, name: name, kind: 'callsite' });
    }
    cur = cur.parentElement;
  }
  return out;
}
```

**Alt+wheel walks the chain without moving the mouse.** Hover selects the most-nested element (`e.target.closest('[data-myapp-src]')`); holding Alt and scrolling then walks *outward* (λ → () → parent element → …) and back in, so you can select an outer component or its call site without nudging the pointer. While Alt is held we freeze the selection (a mouse jitter during scrolling must not reset it), and a click opens whatever step is currently selected — the λ, the `()`, or an element — not whatever is physically under the cursor.

### The editor bridge

A browser can't open your editor; the server can. In the live-reload chapter the `dev-reload` WebSocket handler only did open/close/error. We make it a small **relay hub** between two kinds of peer — browsers and the editor — and teach it to push an "open" command to the editor:

```clojure
(defn- handle-open!
  "Browser → open file. Pushes the open to a connected editor (vscode API via
   Joyride). With no editor connected the open fails — no shell-out fallback."
  [channel src line column]
  (let [reply (fn [m] (send! channel (assoc m :type "open-result")))]
    (if-let [f (resolve-source-file src)]
      (if (pos? (push-open! (.getPath f) line column))           ;; editor connected?
        (reply {:ok true :src src :line line :column column})
        (reply {:ok false :src src :error "no editor connected"})) ;; Joyride not running
      (reply {:ok false :error (str "unresolved source: " (pr-str src))}))))
```

`resolve-source-file` is the trust boundary — a browser can send any string, so we canonicalize, reject `..`, require a `.clj`/`.cljc` extension, and confirm the result is **inside** `src/` using NIO `Path.startsWith` (a plain string-prefix check would let a sibling `src-other/` slip through):

```clojure
(defn- resolve-source-file ^File [src]
  (when (and (string? src) (not (str/includes? src "..")))
    (let [^File root @src-root
          cf (.getCanonicalFile (if (.isAbsolute (File. src)) (File. src) (File. root ^String src)))]
      (when (and (.exists cf) (re-find #"\.cljc?$" (.getName cf))
                 (.startsWith (.toPath cf) (.toPath root)))
        cf))))
```

How the editor actually opens the file is Part II. `push-open!` writes an `{type "open" ...}` message to every connected editor over the same `/dev/ws` socket and returns the delivery count; `handle-open!` uses that count to report success or "no editor connected" back to the browser. There is no shell-out — opening always goes through a live editor agent.

---

## Part II — Code → element

The forward direction tags the DOM with source coordinates. The reverse direction is the dual: take an editor cursor and find the DOM. It needs three pieces — an **index** (so the server can map a cursor to coordinates), an **editor agent** (so the editor can report the cursor), and a **highlighter** in the browser.

### The index: reuse the same read pass

We already read every view with tools.reader. While we're there, we build a per-file span index — top-level `defn` spans (→ component), every element literal's span (→ element), and every call-to-a-view-fn span (→ call site):

```clojure
(def view-index (atom {}))   ;; file -> {:defns [...] :elements [...] :calls [...]}

(defn index-ns! [file ns-sym forms]
  (let [defn-forms (filter defn-form? forms)
        names      (view-defn-names forms)]
    (swap! view-index assoc file
      {:defns    (keep (fn [f] (when-let [s (form-span f)]
                                 {:name (str ns-sym "/" (second f)) :span s})) defn-forms)
       :elements (vec (mapcat #(collect-elements file %) defn-forms))
       :calls    (vec (mapcat #(collect-calls names file %) defn-forms))})))
```

The keys it produces (`file:line:col` for elements/calls, `ns/fn` for components) are **byte-identical** to what the forward direction stamps onto the DOM — so resolving a cursor yields strings the browser can match with a plain attribute selector. No fuzzy matching.

`resolve-cursor` then maps a cursor to all three coordinates by span containment (inclusive start, exclusive end — tools.reader's `:end-column` is one past the last char), picking the **innermost** containing span for the element and call:

```clojure
(defn resolve-cursor [file line col]
  (when-let [{:keys [defns elements calls]} (get @view-index file)]
    (when-let [d (innermost-containing defns line col)]
      {:component  (:name d)
       :file       file
       :defn-lines [(first (:span d)) (nth (:span d) 2)]
       :element    (:key (innermost-containing elements line col))
       :callsite   (:key (innermost-containing calls line col))})))
```

Because the index lives in an atom the dev loader refreshes on every reload, it always reflects the *currently loaded* source — which is what the browser was rendered from. It is empty in production (the loader never runs), so `resolve-cursor` returns `nil` and the whole reverse path is inert.

### The editor agent: piggyback on Joyride

Capturing cursor movement requires running code in the editor's extension host — there is no config-only or LSP route (the language-server protocol has no cursor-moved notification, and Calva exposes no such hook). So we need an extension. But we don't have to *write and package* one: **Joyride** (Calva's sibling) runs ClojureScript in VS Code's extension host with full access to the `vscode` API, so the editor agent is a script that lives in the repo under `.joyride/scripts/`, installed by adding `betterthantomorrow.joyride` to the devcontainer's extension list. No TypeScript, no build, no `.vsix`.

> **Why Joyride and not a custom extension?** A TypeScript extension is fully decoupled and works for non-Joyride users, but you must build and auto-install a `.vsix` from a lifecycle hook (the declarative `customizations.vscode.extensions` only takes marketplace IDs). For a Clojure team in a devcontainer, a Joyride script is genuinely project code — a `.cljs` in the repo — with none of that overhead. The cost is coupling navigation to Joyride being installed. We *did* once keep a `code -g` shell-out as a fallback, but landing it in the right window required sniffing the newest `VSCODE_IPC_HOOK_CLI` socket — fragile enough that we dropped it. With Joyride already supplying the reverse direction, requiring it for the forward direction too is a fair trade for deleting that workaround.

The script holds one WebSocket to the dev server. It **sends** the cursor (debounced) and **receives** open commands (opening the file via the vscode API — exact window, exact range, no shell-out):

```clojure
(ns workspace-activate
  (:require ["vscode" :as vscode]))

(defn- on-selection [event]            ;; report the cursor (debounced)
  (let [doc  (.-document (.-textEditor event))
        sel  (aget (.-selections event) 0)
        file (rel-path (.. doc -uri -fsPath))]   ;; …/src/foo/views.clj -> foo/views.clj
    (when file
      (debounce 80
        #(ws-send! {:type "cursor" :file file
                    :line (inc (.. sel -active -line)) :col (inc (.. sel -active -character))})))))

(defn- open-file! [file line col]      ;; act on an open command from the browser
  (let [uri (.file vscode/Uri file)
        pos (vscode/Position. (max 0 (dec line)) (max 0 (dec col)))]
    (-> (.openTextDocument vscode/workspace uri)
        (.then #(.showTextDocument vscode/window % #js {:selection (vscode/Selection. pos pos)}))
        (.then #(.revealRange % (vscode/Range. pos pos) (.. vscode -TextEditorRevealType -InCenter))))))
```

A node-22 extension host has a global `WebSocket` (undici), so the script opens `ws://localhost:3000/dev/ws` directly. (On older hosts that lack it, an HTTP `fetch` POST works the same — we benchmarked both on loopback at well under a millisecond; transport latency is never the bottleneck for a debounced cursor.) Everything is **event-driven**: on the socket's `open` event the script sends `{:type "hello" :role "editor"}` to register up front — so a browser click can be routed to the editor *before* you've even moved the cursor — and on `close`/`error` it reconnects. The socket lives in a `defonce` atom so a re-eval disposes and reconnects cleanly. Getting that reconnect right across a REPL restart turned out to be its own saga — the next section.

### The relay, and a lesson about ordering

On the server, the same `/dev/ws` handler now tags each client's role (browser by default; editor on its `hello`/`cursor`) and routes:

```clojure
(defn- handle-cursor! [file line column]
  (when (and (number? line) (number? column))
    (when-let [f (resolve-source-file file)]                 ;; same trust boundary
      (when-let [resolved (inspector/resolve-cursor (classpath-relative f) line column)]
        (when (:component resolved)
          (notify-highlight! resolved))))))                  ;; broadcast to browsers
```

We learned one thing here the hard way, worth passing on. An early version stamped each highlight with a sequence number and had the browser drop any with `seq <= lastSeq`, to guard against out-of-order delivery. But highlights travel over a single **ordered** WebSocket — they *can't* arrive out of order — so the guard bought nothing and introduced a footgun: when the server's counter reset on a hot-reload (a plain `def (atom 0)` re-evaluates to 0), the browser's `lastSeq` was still high and silently dropped every subsequent highlight. We removed the guard entirely.

> **Trade-off / lesson.** Don't add ordering machinery for a channel that already guarantees order. We dropped the sequence number from both ends entirely — an ordered socket needs no de-duplication, and the only thing a counter added was a way to stall after a reload reset it.

### Reconnecting through the extension host

Making the editor **survive a REPL/server restart on its own** took longer than the rest of the inspector combined, and every wrong turn traced back to the same root: the VS Code extension host is a Node runtime with a few non-obvious WebSocket behaviours. Three lessons, because they'll bite anyone running a duplex socket from Joyride.

**1. Async logs go to the DevTools console, not the Joyride output channel.** Joyride binds `*out*` to its output channel only during a script's *synchronous* top-level evaluation. Anything that runs later — a `setTimeout`, a vscode event, a WebSocket handler — runs outside that binding, so its `println` lands in the Extension Host DevTools console (Help → Toggle Developer Tools), which you won't see unless it's open. We spent a long time concluding "the events never fire" purely because we were watching the wrong console — they fired the whole time. **Debug the socket in DevTools**, or have handlers report back over the socket itself (which the server *can* log).

**2. undici fires `close` for a dropped link but only `error` for a failed connect.** Node's WebSocket distinguishes a connection that *was* established and then dropped (the server died — `close`, code 1006) from one that *never* connected (the server is still booting after a restart — `error`, no `close`). Reconnect logic that listens to only one of them stalls on the other: listen to `close` alone and you never retry while the server is restarting. So both events schedule a reconnect, deduped so the rare both-fire case still yields a single retry:

```clojure
(defn- connect! []
  ;; Tear down a prior socket; only close one that's OPEN/CONNECTING (see lesson 3).
  (when-let [ws (:ws @!state)] (when (< (.-readyState ws) 2) (try (.close ws) (catch :default _ nil))))
  (let [ws (js/WebSocket. ws-url)]
    (swap! !state assoc :ws ws)
    (.addEventListener ws "open"    (fn [_] (ws-send! {:type "hello" :role "editor"})
                                            (swap! !state assoc :attempt 0)))
    (.addEventListener ws "message" on-message)
    (.addEventListener ws "close"   (fn [_] (schedule-reconnect! ws)))      ;; dropped link (1006)
    (.addEventListener ws "error"   (fn [_] (schedule-reconnect! ws)))))    ;; failed connect

(defn- schedule-reconnect! [ws]   ;; only the *current* socket retries, exactly once
  (when (= ws (:ws @!state))
    (let [n (inc (:attempt @!state 0))]
      (swap! !state assoc :ws nil :attempt n
        :reconnect (js/setTimeout (fn [] (connect!)) (backoff n))))))
```

**3. Never call `.close()` from an `error`/`close` handler.** In a browser this is harmless; in undici, `.close()` on a failing socket runs the close path *synchronously*, re-firing the same event into the same handler — unbounded recursion, `Maximum call stack size exceeded`, and worst of all it silently wedges the whole Joyride extension host. An early `error` handler did exactly this — `(.close ws)` "to force a close so the reconnect fires" — which was both unnecessary (undici fires `close` right after `error` anyway) and the actual cause of every "editor won't reconnect" report: the reconnect attempt was crashing on the recursion, and lesson 1 hid the crash. The fix is to never close from a handler — `schedule-reconnect!` only ever schedules — and to skip closing an already-closing socket in the teardown.

> **The browser side needs none of this.** Chromium fires `close`/`error` reliably and `.close()` is async there, so the overlay reconnects on `close`/`error` with no heartbeat and tracks the connection in its badge. We *did* briefly add a ping/pong liveness heartbeat to the browser while debugging blind — but once the events proved reliable it was pure asymmetry (the editor has none) for a case a page reload already covers, so we removed it along with the server's `pong`. Both ends now use the same event-driven strategy, each matched to its runtime's reality.

### The browser highlighter

The browser handles `{type: "highlight", component, file, defn-lines, element, callsite}` with **DOM-as-truth precedence**: the server proposes coordinates, but the browser highlights based on what actually rendered.

```javascript
function handleHighlight(m) {
  clearReverse();
  if (!m.component) return;
  var compNodes = bySel('data-myapp-name', m.component);
  // element target (strong): callsite → element literal → component root
  var elNodes = bySel('data-myapp-callsite', m.callsite);
  if (!elNodes.length) elNodes = bySel('data-myapp-src', m.element);
  if (!elNodes.length) elNodes = compNodes;
  // component frame (soft): per-instance roots if present, else a bounding box
  // over the defn's source-span members (for components with no DOM root).
  var frame = compNodes.length ? compNodes : spanNodes(m.file, m['defn-lines']);
  drawFrame(frame); drawElement(elNodes);   // soft frame + strong box + a pulse
}
```

The precedence resolves the cursor's intent automatically:

- Cursor **on a call** `(stat-card …)` → `callsite` matches → that one card lights up.
- Cursor **inside a component's body** → no callsite; `element` matches → that literal's nodes (or the component root if the cursor is on the root literal, which carries the defn-site key).
- Cursor on a call to a *non-component* helper → `callsite`/`element` find nothing on the page → it harmlessly falls back. The DOM, not the resolver, decides what exists.

The **component frame** has one wrinkle worth its own note. A component that returns a single element vector has a `data-myapp-name` root we can frame per instance. But a *layout* like `app-layout` returns a string (it calls `html5`), so it has **no** root node. For those we fall back to a bounding box over every on-screen node whose `data-myapp-src` line falls within the defn's span — so even a root-less layout gets a meaningful boundary.

> **Trade-off — root-less components.** Keying the component frame on a `data-myapp-name` root is precise per instance but misses string-returning layouts and fragments. Span-membership covers them at the cost of a single bounding box instead of crisp per-instance frames. We use the root when present and the span otherwise; the two together cover every component shape.

---

## Surfacing a failed reload

One more dev affordance, reusing the same relay. When you save a file with a syntax error, the hot-reload hook can't load it: the edit doesn't take, and — crucially — the browser is *not* told to reload, so it keeps showing the old page. That's a quiet trap. The page looks fine, so you assume your change applied when it didn't; the only sign is a stack trace in the server terminal.

So on a reload exception the hook pushes one message, and the dev-reload script turns it into a dismissible banner:

```clojure
;; hot-reload hook, in the catch around the file load:
(dev-reload/notify-reload-error! file-path (some-> e .getMessage))
;; → broadcasts {:type "reload-error" :file … :error …} to browser clients
```

```javascript
// dev-reload.js
else if (data.type === 'reload-error') showStaleWarning(data.file, data.error);
```

> ⚠ `myapp/web/views.clj` failed to reload — this page may be stale. Fix the error and save.

The wording is deliberately soft. We know a reload *failed*; we don't know the current page actually renders the broken file — it could be an unrelated source reload — so "may be" is the honest claim. There's no explicit clear path: the next *successful* reload navigates the page (a full `location.reload()`), which removes the banner along with everything else, and the `×` dismisses it in the meantime. Dev-only, like the rest — the message originates only from the dev file-watcher, and the script ships only in the dev-gated asset block.

---

## Keeping production clean

Trace every piece and confirm it disappears in prod:

- `tag-root` expands to the bare literal, so Hiccup precompiles your markup exactly as before — no `tag-tree`, no `data-*` attributes in the output.
- `inspector.js` is emitted by `defn-asset` only inside the dev-gated block (next to the dev-reload script), so it never reaches a production page.
- `inspector-load` and the editor/relay code live under `dev/`, on the classpath only via the `:dev` alias.
- `tr-load!` — and therefore all instrumentation, call-site wrapping, and indexing — is dev-only. Production loads views the normal way: no tools.reader, no metadata, no wrapping, an empty `view-index`.
- The editor agent is a Joyride script in `.joyride/`; it does nothing without the Joyride extension and a running dev server.

The feature is structurally absent, not merely disabled.

---

## Trade-offs & limitations, in one place

- **Per-instance precision has a floor.** Distinct call sites are distinguishable; instances of a *single* looped call are not (they share a call site) — and highlighting all of them is the correct semantics, not a defect.
- **Definition vs. call site is an intent, resolved by position.** A cursor in a component's body means "show me my output" (component/element); a cursor on a call means "show me what this produces" (call site). The breadcrumb's λ/() makes the same choice clickable in the forward direction. Navigating to *the specific call site that produced one clicked instance* is the one thing that needs the call-site tag we added — and it now works.
- **Source rewriting is conservative.** Call-site wrapping refuses threading/`quote` contexts and reserved names; it loses precision in component-via-threading composition (rare in views) to guarantee it never corrupts code.
- **Editor coupling.** Navigation pushes to a connected Joyride agent (exact window, exact range, works in remote containers); with no agent connected, the browser reports "no editor connected" rather than guessing. Both directions need the agent — an earlier `code -g` fallback for the forward direction was dropped because landing it in the right window required a fragile newest-IPC-socket sniff.
- **The dev WebSocket is unauthenticated.** Anything that can reach `localhost:3000/dev/ws` can ask to open a (src-confined) file or push a cursor. That is acceptable for a dev-only, loopback service; don't expose the dev server.
- **A small dev startup cost.** Reading views through tools.reader, wrapping calls, instrumenting vars, and indexing is more work than a plain `load` — paid once per view file at startup/reload, and never in production.

## Design decisions worth noting

- **The metadata rides on the value — there is no index to keep in sync (forward).** The location travels welded to the Hiccup vector from read time, through `eval`, through every `for` and helper, onto the page. A `for`-row maps to its template line; an `if`-branch maps to the branch taken.
- **The loader instruments; the views stay plain.** Auto-instrumenting every fn in a `views.clj` (safe because `tag-hiccup` no-ops on non-elements) gets the component layer with zero source ceremony: views need no per-function annotation.
- **Only the markup is touched.** `element?` gates every mutation, so a blanket walk can never corrupt a non-Hiccup value. That is what makes it safe to apply across every view without auditing each one.
- **DOM-as-truth precedence (reverse).** The server proposes coordinates; the browser highlights whatever actually rendered, so conditionals, loops, and not-taken branches all behave correctly without the server predicting anything.
- **A resource check for `dev?`.** Detecting dev with `requiring-resolve` of the hot-reload namespace can close a circular load during the app's own startup, throw, get swallowed, and silently freeze `dev?` to false. `(io/resource "hot_reload.clj")` answers the same question without loading anything.

## What you now have

Building on the live-reload chapter's watcher and dev WebSocket, with one namespace (`myapp.web.inspector`), one dev loader (`inspector-load`), one inlined script (`inspector.js`), one Joyride script (`workspace_activate.cljs`), and a relay in `dev-reload`, you get a **bidirectional** inspector:

- Hold `Alt+Shift+I`, hover any element, walk its ancestor chain with Alt+wheel (component λ / call site ()), and click to open the exact source.
- Move your cursor in the editor and the matching element lights up — the right card among eight, or every row of a `for`, framed within its component.
- `for`-rows resolve to their template; conditionals to the branch taken; clicks to the selection, not the pointer.
- Zero production footprint — no attributes, no script, no server code, all structurally excluded.

The whole thing rests on one trick that the front-end world never needed: Hiccup is plain data with no source information, so you manufacture it — and `clojure.tools.reader` plus the Clojure compiler will, between them, carry a line number from your file to a running vector if you simply stop throwing it away. Everything else — call sites, the reverse direction, the editor bridge — is built on that single welded coordinate.
