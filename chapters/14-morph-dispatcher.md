# The Morph Dispatcher: In-Place Navigation Without a Framework

The [previous chapter](13-hiccup-views.md) ended on a deliberate omission. Every handler renders the complete page and never branches on how it was called; every page's content sits inside a single `<main data-layout>`. That leaves one question hanging: if the server always sends a whole page, what makes navigating between pages feel instant instead of like a full reload? This chapter is the answer, and it is one script.

That script is the *dispatcher*, loaded once by the base layout as an ES module. It turns ordinary links and forms into smooth, in-place updates with no per-element configuration and no change to how the server responds -- and if it never loads, every link still navigates and every form still posts. It is worth being precise about its scope, because it is narrow on purpose: morphing is the right tool for exactly one kind of update -- reconciling server-authored HTML -- and it is *not* the whole progressive-enhancement story. Menus, motion, and local interactions each have their own narrower correct mechanism, and choosing among them is the subject of [the progressive-enhancement chapter](19-progressive-enhancement.md), which treats this dispatcher as the top layer of a five-layer stack. Here we build that layer.

The strategy rests on one idea: **the server always renders complete pages; the client morphs the part that changed.** Specifically, the dispatcher intercepts same-origin navigation, fetches the destination, parses the returned HTML, pulls out its `<main>`, and *morphs* the live `<main>` to match using [idiomorph](https://github.com/bigskysoftware/idiomorph). Morphing diffs the existing DOM against the new DOM and applies the minimal set of mutations, so focus, scroll position, form state, and ongoing CSS transitions inside unchanged subtrees survive the update. We use idiomorph rather than a smaller differ like morphdom or nanomorph for how it matches nodes across a re-render: it indexes the set of ids within each subtree and treats two nodes as the same when those sets overlap, where morphdom and nanomorph look only at each node's own id, sibling by sibling. That is why an element which moved or was re-wrapped between renders is preserved rather than rebuilt -- and why that survival holds even when the new markup differs structurally, not just by an attribute.

## What it intercepts

The dispatcher attaches exactly three listeners, two on `document` and one on `window`:

```javascript
document.addEventListener('click', onClick, true);
document.addEventListener('submit', onSubmit);
window.addEventListener('popstate', onPopState);
```

The two phases differ on purpose. Click binds in the capture phase (the trailing `true`) so the dispatcher sees a navigation click even when a handler deeper in the tree stops propagation. Submit binds in the bubble phase so handlers on the form itself run first: the confirm controller of [the progressive-enhancement chapter](19-progressive-enhancement.md) holds a form's pre-confirm submit back exactly this way, with `preventDefault` plus `stopPropagation`. Both handlers also return immediately when `defaultPrevented` is already set, so anything that canceled the event upstream keeps the dispatcher out.

For clicks, it looks for the nearest `<a href>` and decides whether to take over. It bows out -- letting the browser do its native thing -- for anything that is not a plain primary-button, same-origin navigation:

```javascript
function shouldEnhanceLink(a, e) {
  if (a.hasAttribute('data-no-enhance')) return false;
  if (a.getAttribute('target') === '_blank') return false;
  if (a.hasAttribute('download')) return false;
  if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return false;
  if (e.button !== undefined && e.button !== 0) return false;
  const href = a.getAttribute('href');
  if (!href) return false;
  if (!sameOrigin(href)) return false;
  if (isFragmentOnly(a)) return false;
  return true;
}
```

So cmd-click to open in a new tab, downloads, external links, and in-page `#anchor` jumps all behave exactly as the browser intends. The only opt-out attribute is `data-no-enhance`; everything else is inferred from the link itself.

Forms are similar: the dispatcher reads `action` and `method` straight off the `<form>` (honoring an `<input formaction>`/`formmethod` submitter when present), and skips anything cross-origin or marked `data-no-enhance`. There is no DSL: a normal `<form method="POST" action="/auth/request">` is enhanced as-is.

## The core primitive: fetchAndMorph

Both the click and submit handlers funnel into one exported function, `fetchAndMorph`. It fetches, picks the fragment to apply, morphs, and updates history. The full `static/js/dispatcher.js` in the companion repo fills in the robustness details called out beneath the skeleton, mechanical once the shape is clear: in-flight de-duplication via `AbortController`, `formaction`/`formmethod` discovery, history and `popstate` handling, moving focus to the new `<main>` so screen readers re-announce it. Two things in that file are *not* mechanical and are not narrated here either: the navigation-pending progress bar and the View Transitions wrapper around the morph, two layers of the enhancement stack that [the progressive-enhancement chapter](19-progressive-enhancement.md) adds and explains. The skeleton shows the shape:

```javascript
export async function fetchAndMorph(url, opts = {}) {
  // ... fetch the URL (credentials: same-origin, redirect: follow) ...

  const finalUrl = res.url || url;       // reflects any 302 the server issued
  const html = await res.text();

  // Pull <main> (or an explicit data-target) out of the response HTML.
  const { fragmentEl, parsedDoc } = pickResponseFragment(html, target);
  const targetEl = document.querySelector(target);
  if (!targetEl) return;                 // page already morphed away; bail quietly

  // Cross-layout guard (see below).
  // ...

  morph(targetEl, fragmentEl,
    { morphStyle: 'innerHTML', ignoreActiveValue,
      ...(morphCallbacks ? { callbacks: morphCallbacks } : {}) });
  updateTitle(parsedDoc);
  // ... history bookkeeping ...
}
```

The robustness lives in the details:

- **The server is the source of truth for errors and redirects.** A 4xx or 5xx with an HTML body is morphed in place, so server-rendered error pages just appear. A POST that ends in a redirect (the Post-Redirect-Get pattern) is followed by `fetch`, and `finalUrl` carries the redirect target, which the dispatcher then writes into the address bar -- so a refresh does not re-POST.
- **Cross-layout navigation falls back to a full load.** If the response's `<main data-layout>` differs from the live one (e.g. going from `public-layout` to `app-layout`), an in-place morph would leave the wrong chrome around it. The dispatcher detects the mismatch and does an honest full navigation instead:

  ```javascript
  if (target === 'main') {
    const liveLayout = document.querySelector('main[data-layout]');
    const newLayout = parsedDoc.querySelector('main[data-layout]');
    if (liveLayout && newLayout
        && liveLayout.dataset.layout !== newLayout.dataset.layout) {
      window.location.assign(finalUrl);
      return;
    }
  }
  ```

  This is the payoff for putting `data-layout` on every `<main>`.
- **In-flight requests are de-duplicated, per destination.** Each target-plus-URL pair keys an `AbortController`, so re-issuing the same request (a double-click, a debounced live form firing again) aborts the older fetch instead of racing it. The dedupe claims no more than that: clicks to two *different* destinations still race, and the slower response lands last. Because each response writes its history entry in the same pass that morphs, even the losing order leaves the address bar and the page consistent with each other, and one press of Back restores the newer click.
- **Failure degrades gracefully, and differently by method.** A network error on a GET falls back to `window.location.assign(url)`, a real navigation, so the user is never stranded on a dead click. A failed POST does nothing: the submit was already prevented and no morph ran, so the form and everything typed into it are still on screen for a retry. Replaying it automatically would be wrong twice over: a navigation is a GET and cannot carry the POST body, and re-issuing the POST risks a duplicate write, because a network error can strike after the server has already processed the request.

Two options in the skeleton's `morph` call pass straight through to idiomorph. `ignoreActiveValue` tells it to leave the focused element's value alone; the live-preview enhancers of [the progressive-enhancement chapter](19-progressive-enhancement.md) set it so the field you are typing into survives its own round-trip. `morphCallbacks` forwards an idiomorph callbacks object and exists for exactly one caller, the dev hot-reload of [the morph-reload chapter](18-morph-reload.md), which uses it to shield client-injected dev-overlay nodes from removal.

## A note on the accept header

You will see the fetch send `headers: { 'Accept': 'text/html' }`. **The server does not branch on it.** There is no content negotiation, no "partial vs full" response, no special header that flips the handler into a different mode. Every handler renders the complete page every time; the dispatcher is the only thing that decides to extract `<main>`. The `Accept` header is vestigial -- harmless, but do not build server logic around it. Keeping the server oblivious to *how* it is being called is exactly what lets the no-JavaScript path stay correct for free.

## History and accessibility

For link clicks and GET form submits, the dispatcher pushes a history entry to the fetched URL, so Back and Forward work. `popstate` re-fetches whatever URL the browser is now showing and morphs `<main>` again. After a navigation morph it moves focus to `<main>` (giving it `tabindex="-1"` if needed) so screen readers re-announce the new content, and it copies the new document's `<title>` over.

## Scripts inside a morph

One sharp edge of morphing (or any `innerHTML` swap): browsers do **not** execute `<script>` tags introduced that way. The naive fix is to re-materialize them, cloning each inert `<script>` into a fresh element the browser will run. We do *not*, and a morphed fragment never carries behavior-bearing script, for two reasons that point the same way. The first is the strict, no-`eval` Content-Security-Policy (see [the asset pipeline chapter](24-asset-pipeline.md)): it authorizes exactly the inline scripts hashed at boot and nothing else, so an injected `<script>` would be refused anyway; re-executing fragment scripts is a door the policy keeps shut by construction. The second is that there is nothing to re-execute: all page behavior lives in ES modules loaded once in `<head>`, and the only thing a morph changes is the DOM those modules act on.

## The extension point: `dispatcher:morphed` and the controller registry

Because scripts do not travel in fragments, the dispatcher's contract with the behavior layer is not a script but an *event*. Once the new markup is in place it fires one:

```javascript
document.dispatchEvent(new CustomEvent('dispatcher:morphed', {
  detail: { url: finalUrl, target, method },
}));
```

(At the repo head the `detail` carries a fourth field, `traceId` -- dev-only plumbing that [the construction-view chapter](17-construction-view-overlay.md) adds so its overlay can trace a morphed region; in production it is `null`.)

That event is the single extension point, and its principal listener is a small controller registry. A "controller" is a behavior attached to elements matching a selector -- `live-form`, `defer-details`, `server-preview`, `live-stats`, and a handful more -- declared as a `connect`/`disconnect` pair. The registry owns the lifecycle each of them would otherwise re-implement by hand: find matching elements on first load *and* on every `dispatcher:morphed`, attach exactly once, and detach when an element leaves the DOM or stops matching. A behavior on the registry never touches `DOMContentLoaded`, the morph event, or a "have I wired this already" flag; it just says what to do when a matching element appears and what to undo when it goes:

```javascript
// static/js/live-form.js — registers a behavior; the registry runs its lifecycle.
register('live-form', {
  selector: 'form[data-live]',
  connect(form)    { /* attach input/change listeners, start the debounce */ },
  disconnect(form) { /* remove them */ },
});
```

The division is the whole point. Idiomorph preserves the nodes it can and replaces the ones it must, so after a morph some elements are the very ones a controller already connected (left alone) and some are brand new (connected now); anything the morph removed is disconnected, so its listeners and timers are torn down rather than leaked. Because the registry reconciles the live DOM against the set of connected elements on every morph, a controller is a pure `connect`/`disconnect` pair with no bookkeeping of its own -- which is precisely the island layer that [the progressive-enhancement chapter](19-progressive-enhancement.md) builds out in full. The fact that matters here is narrower: the dispatcher's one obligation to that layer is to fire one event once the new markup is in place.

One production script bypasses the registry: the inline toast helper from [the views chapter](13-hiccup-views.md) hangs on `dispatcher:morphed` (and `DOMContentLoaded`) directly. The reason is scope, not laziness. The helper's work is keyed to the navigation, not to any element. After each load or morph it checks the URL for a `?toast=` param (the shape that lets any redirect carry a notice) and starts the dismiss timer on any toast the server rendered into the page. The container it appends to sits outside `<main>`, untouched by every morph, so `connect`/`disconnect` would have nothing to key on. It is also an inline classic script, hashed into the CSP, with no `import` declaration available to pull in the module registry. So the rule of this section is scoped: element-level behavior belongs on the registry; page-level, per-navigation work subscribes to the event itself, which is what an extension point is for. (The dev-only overlays of the [inspector](15-inspector.md) and [construction-view](17-construction-view-overlay.md) chapters subscribe the same way; they are absent from a production page.)

## Why this shape

This is progressive enhancement in the literal sense. The HTML is complete and functional on its own; the dispatcher is a strict speed-up layered on top. There is no client-side router to keep in sync with the server's routes, no template duplicated between server and client, no hydration step, and no handler that has to know whether it is talking to a browser navigation or a `fetch`. Delete `dispatcher.js` and the app still works -- every link navigates, every form posts. Keep it and navigation becomes a partial DOM morph that preserves UI state.

> **Decision -- why not htmx?** The obvious alternative deserves a named answer, because **htmx** (or Turbo, or Unpoly) would do the navigation job well, and every 2026 reader has been told to reach for it first. Three things tipped this app to building instead.
>
> *Scope -- and it is architectural, not a line count:* htmx is *element-granular* hypermedia: any element can issue any request and swap any target. An app that uses it the way its ecosystem intends grows a server API shaped like fragments -- handlers returning partials aimed at swap targets, a second answer to what an endpoint is. This app's contract is *page-granular*: the server renders complete documents, and enhancement reaches the server at only two small endpoints -- a plain-text `/partials/tagline` endpoint (built in [the progressive-enhancement chapter](19-progressive-enhancement.md)) and the admin dashboard's JSON `/stats` poll -- neither of which is the fragment-shaped HTML API an htmx codebase grows toward, and the client behavior amounts to intercept-fetch-morph-`<main>` plus islands the controller registry already owns. Used at page granularity, htmx collapses to `hx-boost` plus a morph extension, at which point it is the couple hundred lines we would rather own outright.
>
> *The CSP:* our no-`eval`, hash-based policy is the strictest posture the asset pipeline can express, and htmx's attribute-expression features (event filters, `js:`-prefixed values) need the `eval`-like evaluation a no-`unsafe-eval` policy refuses. Those features can be configured off, but then the guarantee rests on configuration; ours rests on construction, a few pages of our own ES modules hashed like everything else. *Ownership:* the dispatcher's entire surface reads in one sitting, and [the morph-reload chapter](18-morph-reload.md) and [the progressive-enhancement chapter](19-progressive-enhancement.md) both extend it in ways that are only cheap because we own the seam. (We already vendor the one piece of that family we could not improve on: idiomorph is the htmx project's own morphing engine.)
>
> In the Clojure world the decision often arrives pre-made. **Biff** and **Kit** (the community defaults [the positioning chapter](02-positioning.md) named) both scaffold exactly this SSR + htmx shape, and **Datastar** -- newer, server-authoritative, SSE-streamed, with an official Clojure SDK -- makes the same bet from the signals end. They are good answers whose trade is the inverse of ours -- a maintained vocabulary in exchange for a seam you do not own -- and the architecture of this book survives the swap either way, because the server-side contract (render whole pages, one morph target) is identical.

## Where this leaves us

One script, three listeners, and one exported function are the whole of it: no router, no hydration, no second copy of the routing table on the client. The leverage comes from how little the rest of the system has to know about it: the server keeps rendering complete pages in blissful ignorance, and the only contract the client side exposes is a single `dispatcher:morphed` event, which the controller registry -- and the page-level toast helper -- listens for.

That narrow surface is also why `fetchAndMorph` turns out to be reused well beyond navigation. The dev hot-reload loop in [the morph-reload chapter](18-morph-reload.md) is, as that chapter puts it, just one more caller of this same function: a view edit on the server becomes a morph in the browser through exactly this path, with no dev-only morphing code to maintain. And the entire island layer in [the progressive-enhancement chapter](19-progressive-enhancement.md) hangs off the one event this function fires. Build the dispatcher once, correctly, and two later chapters get to treat it as bedrock.
