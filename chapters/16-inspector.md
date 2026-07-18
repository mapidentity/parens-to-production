# A Bidirectional Source Inspector for Server-Rendered Hiccup: From Element to Code and Back

In [the live-reload chapter](06-live-reload.md) we closed the gap between saving a file and seeing it in the browser. This chapter closes two more, in both directions:

- **Element → code.** You spot a misaligned badge in the admin dashboard; hold a key, hover it, and your editor opens the exact `.clj` line that produced it.
- **Code → element.** You put your cursor on a view function (or a call to one) in the editor, and the matching element lights up in the browser, even telling apart the component's *definition* from this particular *call site*.

Front-end frameworks have had the first half for years (React/Vue/Svelte inspectors). The second half, where the editor cursor drives the browser, is rarer even there. We render HTML on the server from Hiccup, plain Clojure data carrying no source information, so we manufacture all of it ourselves. This chapter is the full build: the why, the how, and the trade-offs.

![Inspect mode on: hovering a recipe title boxes it and shows the breadcrumb of its tagged ancestors, ending in the exact source location `myapp/web/views.clj:361:6`.](images/inspector-hover.png)

*Element → code. With inspect mode on, hovering an element draws its box and a breadcrumb of tagged ancestors -- `recipe-card () λ` marks the component and its call site -- ending in the precise `file:line:col` a click will open.*

It reuses the file watcher and the `dev-reload` WebSocket from [the live-reload chapter](06-live-reload.md), and hooks into the `base-layout` from [the Hiccup views chapter](14-hiccup-views.md). Everything here is dev-only and **structurally absent** from production builds, the same as the rest of our dev infrastructure. If you read strictly in order, the Hiccup views chapter's layout sections are the relevant background.

> **Why build this on a server-rendered app?** Because it keeps a workflow Clojure programmers refuse to give up. The REPL habit is to stay in live contact with the running system: evaluate, inspect the value, navigate the data, redefine, look again. The browser is normally where that contact ends. The server ships HTML across the wire, and the rendered page is a dead artifact with no thread back to the process that built it. The inspector reopens that thread at the border, and it runs both ways. Element → code makes the page interrogable the way the REPL makes a namespace interrogable: hover a pixel and your editor opens the line that produced it. Code → element closes the other half. Put your cursor on a view function and the element it rendered lights up in the live page, folding the editor into the loop exactly as REPL evaluation already does. Editor, running process, and rendered page stop being three windows you alt-tab between and become one connected surface.
>
> A React or Vue developer takes element-to-source inspection for granted because it ships with the framework, and the instinct is that server-rendered Hiccup means doing without. It does not, *because we own the rendering path and can instrument it ourselves*. That is the lesson of this chapter and the construction-view chapters that follow: choosing SSR costs you neither the interactive, REPL-driven development Clojure is built on nor a toolbox an SPA would have. You extend that workflow across the browser boundary instead of surrendering it there.
>
> The tool pays off in proportion to how much view code you have and how often you hunt *which* function produced a given pixel. The idea behind it is bigger still, and it is the keystone of Part I: welding source locations onto plain Clojure data with `tools.reader`.

## Part I -- element → code

Part I is a pipeline of several pieces, and it helps to hold the whole shape before the parts arrive. After two sections of groundwork (*why* Hiccup makes this hard, and the `tools.reader` insight that makes it possible), we build, in order:

1. **a recognizer** -- spot a Hiccup element and stamp its source file onto it;
2. **a loader and component layer** -- read view namespaces so every form keeps its line numbers, and wrap each view function;
3. **call-site tagging** -- so two calls to the same component can be told apart;
4. **the render boundary** -- turn the accumulated metadata into real HTML attributes as the page serializes;
5. **the browser overlay** -- hover-to-box and click-to-open, in the page;
6. **the editor bridge** -- the other end of that click, opening the exact file and line.

Each gets its own section below; if a later one starts to feel unmoored, this is the map back.

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
| **Component** | `data-myapp-name` | the view fn that produced this subtree (`ns/fn`); plain elements reuse it for the bare tag name |
| **Call site** | `data-myapp-callsite` | where *this instance* was invoked from |

The element coordinate is the hard, interesting one. It did not exist for Hiccup. The other two fall out of the same plumbing. The distinction between *component* and *call site* matters more than it looks; we will return to it when a component is rendered from several places.

### The insight: `tools.reader` keeps your line numbers, and so does the compiler

The keystone is this: a Hiccup element's source position can ride on the runtime value itself -- no separate index, no matching DOM nodes back to source after the fact. That rests on two facts, stacked.

**Fact one:** `clojure.tools.reader` -- a pure-Clojure reader maintained by core -- *does* attach `:line`/`:column`/`:end-line`/`:end-column` to every nested form, vectors included, at every depth:

```clojure
(require '[clojure.tools.reader :as tr]
         '[clojure.tools.reader.reader-types :as rt])

(let [form (tr/read (rt/indexing-push-back-reader "[:div [:span \"hi\"]]"))]
  {:outer (meta form) :inner (meta (nth form 1))})
;;=> {:outer {:line 1 :column 1 :end-line 1 :end-column 20}
;;    :inner {:line 1 :column 7 :end-line 1 :end-column 19}}
```

The **end** positions matter later (the reverse direction does span-containment), and they are present too. Where the default reader gave `nil`, tools.reader gives every `[…]` its own coordinates.

**Fact two:** the Clojure compiler *preserves* a vector literal's metadata onto the runtime value, and does so even for vectors built in a loop:

```clojure
(def render (eval '(fn [xs] (mapv (fn [x] ^{:line 9} [:li x]) xs))))
(map meta (render [1 2 3]))
;;=> ({:line 9} {:line 9} {:line 9})
```

Three `<li>`s, each tagged with the *one* source line of their template. That is exactly the semantics we want: clicking a `for`-generated row should land on the `for`'s `[:tr …]` line, not on three different places.

Put the facts together:

> Read our view namespaces with `tools.reader` instead of the default reader, then `eval` the forms. Now every Hiccup element our views produce carries its own `:line`/`:column` in its metadata -- welded onto the value, riding along through every `for`, `if`, and helper call, all the way to the rendered page.

There is no separate index to keep in sync and no fragile matching of DOM nodes back to source. The position *is* part of the value.

### Recognizing an element, and stamping the file onto it

tools.reader gives `:line`/`:column` but not which *file* a form came from, and a page mixes elements from many namespaces. So as we load each file we stamp the file path onto every element literal.

First, recognize an element literal -- not every vector is Hiccup (Datomic pull patterns `[:db/id …]`, `let` bindings, tagged tuples). We tag only vectors whose head is an unnamespaced HTML/SVG tag keyword:

```clojure
(ns myapp.web.inspector
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.walk :as walk]))

(def ^:private dev?
  ;; Detect dev by a classpath resource, NOT requiring-resolve: requiring the
  ;; hot-reload ns here can deadlock on a circular load and silently turn the
  ;; whole feature off.
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

A word on the `,,,` inside that set, a notation the listings use from here on: commas are whitespace to the Clojure reader, so a bare `,,,` is legal Clojure. It marks lines elided from a listing; the full text is always in the repo.

The `str/split` on `#"[.#]"` strips Hiccup's `.class`/`#id` shorthand, so `:div.card#main` matches `"div"`. This `element?` gate is what makes everything that follows **safe to apply blindly** across every view: a non-Hiccup vector can never be touched.

Now the walk that adds the file:

```clojure
(defn add-file-meta
  "Stamp :myapp/file onto every Hiccup element literal's metadata in `form`.
   A plain postwalk: only element vectors are touched, and only by gaining one key."
  [file form]
  (walk/postwalk
    (fn [x]
      (if (and (vector? x) (element? x) (:line (meta x))) (vary-meta x assoc :myapp/file file) x))
    form))
```

That this can be a plain `clojure.walk/postwalk` rests on a guarantee that only recently became complete. The walk rebuilds every collection it traverses, so the question is whether the rebuilt copies keep their metadata; lose it here and we throw away the very line numbers we just captured. Vectors, maps, and sets always kept theirs: `walk` reconstructs them with `(into (empty form) …)`, and `empty` preserves metadata. So the element vectors carrying our `:line` numbers were never at risk. Lists and seqs, though, silently lost their metadata to `postwalk` until Clojure 1.12 re-attached `(meta form)` explicitly (CLJ-2568). That metadata is not disposable either: this walk runs *after* `wrap-callsites` (below) has rebuilt every call form carrying its reader span, and the compiler reads `:line` off list forms to emit line numbers into compiled code. Stripping it would degrade every stack trace through a view. On the Clojure this book pins (1.12.4) the guarantee is total and the plain walk preserves everything. On anything earlier, hand-roll it: rebuild each collection yourself and re-attach `(meta form)` to every rebuilt seq.

### The loader (and the component layer)

The *component* coordinate (which view function produced a given root element) can't come from a literal's source position. It needs the enclosing `defn`. The loader supplies it invisibly by instrumenting every function a view namespace defines, so views stay plain `defn` with no annotation.

The loader -- `dev/inspector_load.clj`, under `dev/` and so never on the prod classpath -- reads each file with tools.reader, and then does three things to it: stamps file metadata (element layer), **auto-instruments every function the namespace defined** (component layer), and **indexes** it (for the reverse direction, Part II).

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
            ;; per-form try + plain-eval fallback elided — see "Keeping the tags alive"
            (eval (inspector/add-file-meta file (inspector/wrap-callsites names file form true))))
          (inspector/instrument-ns! (ns-name *ns*))           ;; component layer
          (inspector/index-ns! file (ns-name *ns*) forms))))))  ;; reverse-direction index
```

Two ordering subtleties hide in that little loop. We need the file's function names *before* evaluating any body form (so call-site wrapping knows which calls are components), which pushes us toward reading everything up front. But we must **evaluate the `ns` form before reading the rest**. `tools.reader` resolves auto-namespaced keywords like `::alias/kw` against the current namespace's aliases *at read time*, so the namespace has to exist first. Read the whole file before the `ns` form runs and such a keyword throws: the file falls back to a plain `load`, and you silently lose its tags. So: read the `ns` form, eval it, *then* read the body. Binding `*file*` to the classpath-relative path also means the *vars* get a correct `:file` -- exactly what the component layer reads:

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

`tag-hiccup` is the one-element version of the tree walk below: if its argument is an element vector, it adds `data-myapp-src`/`data-myapp-name`; otherwise it returns it unchanged. Because `instrument-var!` reads the *var's* metadata (always present after a `defn`), the component tag works even for a function whose root is built dynamically, and clicking that root opens the function definition.

**How the loader hooks the live-reload watcher.** We `tr-load!` view namespaces instead of `load-file`, both at startup and on change -- but only views, to avoid the tools.reader cost on files with no Hiccup. Views carry no inspector-specific marker, so we detect them by a **naming convention**:

```clojure
(defn- view-ns-file? [path]
  (str/ends-with? (str path) "views.clj"))   ;; web/views.clj, admin/views.clj, …
```

Wrap `tr-load!` in a `try` that falls back to `load-file` -- a reader edge case must never break your reload loop.

> **Trade-off -- convention over opt-in.** A naming convention means a view placed in a non-`views.clj` file silently won't be instrumented. We accept that: it is a dev affordance with a harmless failure mode, and the convention is one the project already follows. The alternative (a per-namespace marker like `^{:myapp/views true}`) is more explicit but adds ceremony; pick whichever your team prefers.

### Keeping the tags alive

The source tags exist only because the loader applied them. So any reload that goes around the loader -- a plain `load-file`, or an editor's eval-on-save -- re-defs those functions with the default reader and no `instrument-ns!`, and silently strips every tag until the next loader pass. The symptom: elements lose their inspection border after a save. The fix is a principle, not a mechanism: **`tr-load!` is the single source of truth for tagged view code** -- make it the only path that reloads views (let the file-watcher own reloads, turn off editor eval-on-save for views) and the problem can't occur. Resist the temptation to heal it reactively with a var watch; that is asynchronous and racy. The loader does degrade per form rather than per file -- each tagged form evals inside a `try`, so a form the rewrite mishandles loads plain and logs, leaving the function defined and root-tagged with only its element-level tags missing.

### Call-site tagging: telling instances apart

This is the distinction the three-coordinate table flagged. Consider a dashboard that calls one component eight times:

```clojure
[:dl
 (stat-card "Total Users" total-users)
 (stat-card "Links Sent"  links-sent)
 ,,, ]                                  ; six more
```

All eight rendered cards carry the same `data-myapp-name` (`…/stat-card`) and the same `data-myapp-src` (the defn site). Nothing records *which call* produced *which card*. So if your cursor is on the "Total Users" call and you want only that card to light up, there is no way to know -- the instance identity isn't in the DOM.

The fix is to tag each rendered instance with its **invocation site**. During the loader's read pass we wrap calls to view fns:

```clojure
(stat-card "Total Users" n)
;; becomes, at load time:
(myapp.web.inspector/tag-callsite "myapp/admin/views.clj:123:9" (stat-card "Total Users" n))
```

`tag-callsite` adds `data-myapp-callsite` to the result if it is an element, and is otherwise a no-op. The rewrite is done by `wrap-callsites`, which walks the form preserving reader metadata and only wraps calls whose head is an **unqualified** symbol naming a fn the file defined. Unlike `add-file-meta`, this walk cannot be a plain `postwalk` (the `wrap?` flag must flow top-down into threading subtrees, and `postwalk` carries no context), so it rebuilds by hand and re-attaches `(meta form)` at every step:

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

Now the two cases resolve correctly, and they are *the same mechanism*:

- **Distinct call sites** (the eight `stat-card`s): each gets a different `data-myapp-callsite`, so a cursor on one call lights up exactly one card.
- **A single call in a loop** (`(for [r recipes] (recipe-card r))`): one source site, so all its instances share one `data-myapp-callsite`, and lighting up all of them is *correct*. One place in the code, many renders.

> **Trade-offs -- call-site tagging.**
> - **Per-instance precision has a floor.** A looped call can't distinguish iteration 3 from iteration 5; they share a call site. That is the honest limit of "one source location → N renders," and the behavior (highlight the whole family) is the right answer, not a bug.
> - **Rewriting source is delicate.** We only wrap unqualified calls to the file's own fns, and we refuse to descend into threading/`quote` forms (where wrapping would change semantics). Reserved names (`recur`, `let`, …) are excluded so we can never move a call out of tail position. The conservative guard loses call-site precision for components composed *through* a threading macro, something view code essentially never does. The trade is never corrupting code.
> - **It is metadata-preserving by construction.** Every rebuilt collection re-attaches `(meta form)`; the wrapper inherits the call's reader span. If you get this wrong you silently break the element layer, so test that `data-myapp-src` still appears after wrapping.

### From metadata to attributes, at the render boundary

The metadata rides on the runtime Hiccup, but a browser can't read Clojure metadata. We translate it to attributes by walking the assembled tree just before stringification:

```clojure
(defn tag-tree
  "Add data-myapp-src (file:line:col) and data-myapp-name (the bare tag) to
   every element carrying :line + :myapp/file metadata."
  [node]
  (cond
    (vector? node)
    (let [m (meta node) children (mapv tag-tree node)]
      (if (and (:line m) (:myapp/file m) (element? node))
        (let [has-attrs? (map? (second children))
              attrs      (if has-attrs? (second children) {})
              body       (subvec children (if has-attrs? 2 1))]
          (into [(first children)
                 (assoc attrs
                        :data-myapp-src  (str (:myapp/file m) ":" (:line m) ":" (or (:column m) 1))
                        :data-myapp-name (first (str/split (name (first node)) #"[.#]")))]
                body))
        children))
    (seq? node) (doall (map tag-tree node))
    :else node))
```

We never call this directly -- that would defeat Hiccup's compile-time precompilation in prod. We gate it behind a macro that vanishes in prod, and call it once, in the layout every page passes through:

```clojure
(defmacro tag-root [tree] (if dev? `(tag-tree ~tree) tree))

(defn base-layout [& body]
  (h/html {:mode :html}                     ;; the escaping renderer from the views chapter
    (h/raw "<!DOCTYPE html>")
    [:html
     [:head ,,, ]
     [:body
      (tag-root body)                       ;; <- dev: tagged; prod: the bare tree
      ,,, ]]))
```

The two layers compose cleanly. `tag-tree` only tags elements that still carry reader metadata. A component's *root* was rebuilt by `tag-hiccup`/`tag-callsite`, so its reader metadata is gone; it keeps its component/call-site tags, while every inner literal keeps its own `:line`. Roots resolve to their function; inner literals to their exact line. Note that `data-myapp-name` does double duty across the layers: a component root carries `ns/fn` (stamped by `tag-hiccup`), a plain element the bare tag name (stamped here). The overlay's breadcrumb reads that one attribute for every crumb's label.

### The browser overlay

The front end is a self-contained script, inlined with the `defn-asset` macro from the Hiccup views chapter so it is only ever served in dev. It opens its own connection to the `/dev/ws` endpoint, draws a highlight box on hover, shows a breadcrumb of tagged ancestors, and opens source on click.

Two pieces beyond the basics are worth showing.

**The breadcrumb folds component + call site.** As you hover, we walk the `data-myapp-src` ancestors into breadcrumb *steps*. A component instance carries two source locations on one node, its definition and its call site. Rather than two crumbs with the same name, we fold them into one: the name once, then two tiny selectable glyphs, **λ** (the definition) and **()** (the call site):

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

`shortName` reads the node's `data-myapp-name` and trims any namespace for display: `stat-card` from `myapp.admin.views/stat-card` on a component root, the bare `dl`/`dd` that `tag-tree` stamped on plain elements.

**Alt+wheel walks the chain without moving the mouse.** Hover selects the most-nested element (`e.target.closest('[data-myapp-src]')`); holding Alt and scrolling then walks *outward* (λ → () → parent element → …) and back in, so you can select an outer component or its call site without nudging the pointer. While Alt is held we freeze the selection, so a mouse jitter during scrolling can't reset it. A click then opens whatever step is currently selected -- the λ, the `()`, or an element -- not whatever is physically under the cursor.

### The editor bridge

A browser can't open your editor; the server can. In the live-reload chapter the `dev-reload` WebSocket handler only did open/close/error. We make it a small **relay hub** between two kinds of peer, browsers and the editor, and teach it to push an "open" command to the editor:

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

`resolve-source-file` is the trust boundary. A browser can send any string, so we canonicalize, reject `..`, require a `.clj`/`.cljc` extension, and confirm the result is **inside** `src/` using NIO `Path.startsWith` (a plain string-prefix check would let a sibling `src-other/` slip through):

```clojure
(defn- resolve-source-file ^File [src]
  (when (and (string? src) (not (str/includes? src "..")))
    (let [^File root @src-root
          cf (.getCanonicalFile (if (.isAbsolute (File. src)) (File. src) (File. root ^String src)))]
      (when (and (.exists cf) (re-find #"\.cljc?$" (.getName cf))
                 (.startsWith (.toPath cf) (.toPath root)))
        cf))))
```

How the editor actually opens the file is Part II. `push-open!` writes an `{type "open" ...}` message to every connected editor over the same `/dev/ws` socket and returns the delivery count; `handle-open!` uses that count to report success or "no editor connected" back to the browser. There is no shell-out -- opening always goes through a live editor agent.

## Part II -- code → element

The forward direction tags the DOM with source coordinates. The reverse direction is the dual: take an editor cursor and find the DOM. It needs three pieces -- an **index** (so the server can map a cursor to coordinates), an **editor agent** (so the editor can report the cursor), and a **highlighter** in the browser.

### The index: reuse the same read pass

We already read every view with tools.reader. While we're there, we build a per-file span index: top-level `defn` spans (→ component), and, within those defns, every element literal's span (→ element) and every call-to-a-view-fn span (→ call site). Only forms inside a `defn` are indexed; a cursor resolves only inside a defn anyway, and the restriction skips phantom element literals in `(comment …)` blocks and top-level data defs:

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

The keys it produces (`file:line:col` for elements/calls, `ns/fn` for components) are **byte-identical** to what the forward direction stamps onto the DOM, so resolving a cursor yields strings the browser can match with a plain attribute selector. No fuzzy matching.

`resolve-cursor` then maps a cursor to all three coordinates by span containment (inclusive start, exclusive end; tools.reader's `:end-column` is one past the last char), picking the **innermost** containing span for the element and call:

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

Because the index lives in an atom the dev loader refreshes on every reload, it always reflects the *currently loaded* source, which is what the browser was rendered from. It is empty in production (the loader never runs), so `resolve-cursor` returns `nil` and the whole reverse path is inert.

### The editor agent: piggyback on Joyride

Capturing cursor movement requires running code in the editor's extension host -- there is no config-only or LSP route (the language-server protocol has no cursor-moved notification, and Calva exposes no such hook). So we need an extension. But we don't have to *write and package* one: **Joyride** (Calva's sibling) runs ClojureScript in VS Code's extension host with full access to the `vscode` API. So the editor agent is a script that lives in the repo under `.joyride/scripts/`, installed by adding `betterthantomorrow.joyride` to the devcontainer's extension list. No TypeScript, no build, no `.vsix`.

> **Why Joyride and not a custom extension?** A TypeScript extension is fully decoupled and works for non-Joyride users, but you must build and auto-install a `.vsix` from a lifecycle hook (the declarative `customizations.vscode.extensions` only takes marketplace IDs). For a Clojure team in a devcontainer, a Joyride script is genuinely project code, a `.cljs` in the repo, with none of that overhead. The cost is coupling navigation to Joyride being installed. But since Joyride already supplies the reverse direction, requiring it for the forward direction too is a fair trade. It also lets us drop a fragile `code -g` shell-out fallback that had to sniff the newest `VSCODE_IPC_HOOK_CLI` socket to land in the right window.

The script holds one WebSocket to the dev server. It **sends** the cursor (debounced) and **receives** open commands (opening the file via the vscode API -- exact window, exact range, no shell-out):

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

A node-22 extension host has a global `WebSocket` (undici), so the script opens `ws://localhost:3000/dev/ws` directly. (On older hosts that lack it, an HTTP `fetch` POST works the same -- we benchmarked both on loopback at well under a millisecond; transport latency is never the bottleneck for a debounced cursor.) Everything is **event-driven**. On the socket's `open` event the script sends `{:type "hello" :role "editor"}` to register up front, so a browser click can be routed to the editor *before* you've even moved the cursor. On `close` or `error` it reconnects. The socket lives in a `defonce` atom so a re-eval disposes and reconnects cleanly.

### The relay, and reconnecting through the extension host

On the server, the same `/dev/ws` handler now tags each client's role (browser by default; editor on its `hello`/`cursor`) and routes a resolved cursor to the browsers:

```clojure
(defn- handle-cursor! [file line column]
  (let [column (if (number? column) column 1)]               ;; tolerate a missing col
    (when (number? line)
      (when-let [f (resolve-source-file file)]               ;; same trust boundary
        (let [resolved (inspector/resolve-cursor (classpath-relative f) line column)]
          (when (:component resolved)
            (notify-highlight! resolved)))))))                  ;; broadcast to browsers
```

The editor agent piggybacks on Joyride over this WebSocket relay, and keeping it alive across a REPL or server restart took real care. The VS Code extension host is a Node (undici) runtime with a few non-obvious socket behaviors. It fires `close` for a dropped link but only `error` for a failed connect, so both must schedule a reconnect. And calling `.close()` from inside an `error` or `close` handler re-fires the event synchronously and wedges the host. The reconnect logic that handles this (current-socket guarding, dedup, backoff) lives in the Joyride editor script (`.joyride/scripts/workspace_activate.cljs`) and, for the browser side, `src/myapp/web/inspector.js`; read the exact code there rather than reconstructing it from prose. The browser side needs none of it: Chromium fires the events reliably and `.close()` is async, so the overlay reconnects on `close`/`error` with no heartbeat. (Two things we tried and removed, in case you reach for them: a per-highlight sequence number is pointless on an already-ordered socket and stalls when a hot-reload resets the counter; and a browser-side ping/pong heartbeat is redundant once the events prove reliable.)

### The browser highlighter

The browser handles `{type: "highlight", component, file, defn-lines, element, callsite}` with **DOM-as-truth precedence**: the server proposes coordinates, but the browser highlights based on what actually rendered. The first line gates the whole reverse direction on the same `enabled` flag as the forward one -- the inspect badge is a single master switch for *both* directions, so a disengaged inspector leaves the page untouched. (Turning it off clears any live boxes; turning it on re-requests the cursor so the current position lights up at once.) Flipping that switch also broadcasts a `myapp:inspect` document event carrying `{enabled}`. Nothing in *this* chapter consumes it, but it is the hook other dev-only tools subscribe to, so they can turn themselves on and off in lockstep with the inspector. The construction-view overlay ([its chapter](18-construction-view-overlay.md)) is the first to use it.

```javascript
function handleHighlight(m) {
  if (!enabled) return;   // reverse highlight follows the inspect toggle
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

The **component frame** has one wrinkle worth its own note. A component that returns a single element vector has a `data-myapp-name` root we can frame per instance. But a *layout* like `app-layout` returns a string (it is built with the escaping `h/html` from [the Hiccup views chapter](14-hiccup-views.md), not a single Hiccup vector), so it has **no** root node. For those we fall back to a bounding box over every on-screen node whose `data-myapp-src` line falls within the defn's span, so even a root-less layout gets a meaningful boundary.

> **Trade-off -- root-less components.** Keying the component frame on a `data-myapp-name` root is precise per instance but misses string-returning layouts and fragments. Span-membership covers them at the cost of a single bounding box instead of crisp per-instance frames. We use the root when present and the span otherwise; the two together cover every component shape.

## Surfacing a failed reload

One more dev affordance, reusing the same relay. When you save a file with a syntax error, the hot-reload hook can't load it: the edit doesn't take, and, crucially, the browser is *not* told to reload, so it keeps showing the old page. That's a quiet trap. The page looks fine, so you assume your change applied when it didn't; the only sign is a stack trace in the server terminal.

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

> ⚠ `myapp/web/views.clj` failed to reload -- this page may be stale. Fix the error and save.

The wording is deliberately soft. We know a reload *failed*; we don't know the current page actually renders the broken file; it could be an unrelated source reload. So "may be" is the honest claim. There's no explicit clear path: the next *successful* reload navigates the page (a full `location.reload()`), which removes the banner along with everything else, and the `×` dismisses it in the meantime. Dev-only, like the rest -- the message originates only from the dev file-watcher, and the script ships only in the dev-gated asset block.

## Keeping production clean

Trace every piece and confirm it disappears in prod:

- `tag-root` expands to the bare literal, so Hiccup precompiles your markup exactly as before: no `tag-tree`, no `data-*` attributes in the output.
- `inspector.js` is emitted by `defn-asset` only inside the dev-gated block (next to the dev-reload script), so it never reaches a production page.
- `inspector-load` and the editor/relay code live under `dev/`, on the classpath only via the `:dev` alias.
- `tr-load!` -- and therefore all instrumentation, call-site wrapping, and indexing -- is dev-only. Production loads views the normal way: no tools.reader, no metadata, no wrapping, an empty `view-index`.
- The editor agent is a Joyride script in `.joyride/`; it does nothing without the Joyride extension and a running dev server.

The feature is structurally absent, not merely disabled.

## Trade-offs & limitations, in one place

- **Per-instance precision has a floor.** Distinct call sites are distinguishable; instances of a *single* looped call are not (they share a call site) -- and highlighting all of them is the correct semantics, not a defect.
- **Definition vs. call site is an intent, resolved by position.** A cursor in a component's body means "show me my output" (component/element); a cursor on a call means "show me what this produces" (call site). The breadcrumb's λ/() makes the same choice clickable in the forward direction. Navigating to *the specific call site that produced one clicked instance* is the one thing that needs the call-site tag we added, and it now works. Concretely, with the dashboard rendering its eight `stat-card`s, the cursor's position picks the intent in the reverse direction:

  ```clojure
  (defn stat-card [label n]        ; ← cursor here lights up ALL eight cards (the component, every instance)
    [:div.rounded-lg.p-4            ; ← cursor here lights up just this element, across all eight
     [:dt label]
     [:dd n]])

  ;; ...in the dashboard view:
  (stat-card "Total Users" total-users)  ; ← cursor here lights up only THIS card — one instance, via the call-site tag
  (stat-card "Links Sent"  links-sent)
  ,,,                                    ; six more
  ```

  Three positions, three answers: the definition (all instances), one element within it (that element in all instances), and one call (exactly one rendered card). The looped case is the floor noted above: a single `(for [r recipes] (recipe-card r))` is one call site, so a cursor on it lights up the whole family, not iteration 3.
- **Source rewriting is conservative.** Call-site wrapping refuses threading/`quote` contexts and reserved names; it loses precision in component-via-threading composition (rare in views) to guarantee it never corrupts code.
- **Editor coupling.** Navigation pushes to a connected Joyride agent (exact window, exact range, works in remote containers); with no agent connected, the browser reports "no editor connected" rather than guessing. Both directions need the agent. An earlier `code -g` fallback for the forward direction was dropped because landing it in the right window required a fragile newest-IPC-socket sniff.
- **The dev WebSocket is unauthenticated.** Anything that can reach `localhost:3000/dev/ws` can ask to open a (src-confined) file or push a cursor. That is acceptable for a dev-only, loopback service; don't expose the dev server.
- **A small dev startup cost.** Reading views through tools.reader, wrapping calls, instrumenting vars, and indexing is more work than a plain `load`. It is paid once per view file at startup or reload, and never in production.

## What it rests on

The whole thing rests on one move the front-end world never had to make: Hiccup is plain data with no source information, so you manufacture it -- and `clojure.tools.reader` plus the Clojure compiler will, between them, carry a line number from your file to a running vector if you simply stop throwing it away. Everything else -- call sites, the reverse direction, the editor bridge -- is built on that single welded coordinate.
