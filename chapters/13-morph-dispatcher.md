# The Morph Dispatcher: In-Place Navigation Without a Framework

The [previous chapter](12-hiccup-views.md) ended on a deliberate omission. Every handler renders the complete page and never branches on how it was called; every page's content sits inside a single `<main data-layout>`. That leaves one question hanging: if the server always sends a whole page, what makes navigating between pages feel instant instead of like a full reload? This chapter is the answer, and it is one script.

That script is the *dispatcher*, loaded once by the base layout as an ES module. It turns ordinary links and forms into smooth, in-place updates with no per-element configuration and no change to how the server responds -- and if it never loads, every link still navigates and every form still posts. It is worth being precise about its scope, because it is narrow on purpose: morphing is the right tool for exactly one kind of update -- reconciling server-authored HTML -- and it is *not* the whole progressive-enhancement story. Menus, motion, and local interactions each have their own narrower correct mechanism, and choosing among them is the subject of [the progressive-enhancement chapter](18-progressive-enhancement.md), which treats this dispatcher as the top layer of a five-layer stack. Here we build that layer.

The whole strategy rests on one idea: **the server always renders complete pages; the client morphs the part that changed.** Specifically, the dispatcher intercepts same-origin navigation, fetches the destination, parses the returned HTML, pulls out its `<main>`, and *morphs* the live `<main>` to match using [idiomorph](https://github.com/bigskysoftware/idiomorph). Morphing diffs the existing DOM against the new DOM and applies the minimal set of mutations -- so focus, scroll position, form state, and ongoing CSS transitions inside unchanged subtrees survive the update.

## What it intercepts

The dispatcher attaches exactly three listeners at the document level:

```javascript
document.addEventListener('click', onClick, true);
document.addEventListener('submit', onSubmit);
window.addEventListener('popstate', onPopState);
```

For clicks, it looks for the nearest `<a href>` and decides whether to take over. It deliberately bows out -- letting the browser do its native thing -- for anything that is not a plain primary-button, same-origin navigation:

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

Forms are similar -- the dispatcher reads `action` and `method` straight off the `<form>` (honoring an `<input formaction>`/`formmethod` submitter when present), and skips anything cross-origin or marked `data-no-enhance`. There is no DSL: a normal `<form method="POST" action="/auth/request">` is enhanced as-is.

## The core primitive: fetchAndMorph

Both the click and submit handlers funnel into one exported function, `fetchAndMorph`. It fetches, picks the fragment to apply, morphs, and updates history. The skeleton below shows the shape; the full `static/js/dispatcher.js` in the companion repo fills in the robustness details called out beneath it (in-flight de-duplication via `AbortController`, `formaction`/`formmethod` discovery, re-executing `<script>` tags the morph carried in), which are mechanical once the shape is clear:

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

  morph(targetEl, fragmentEl, { morphStyle: 'innerHTML', ignoreActiveValue });
  updateTitle(parsedDoc);
  // ... history bookkeeping ...
}
```

A few details worth pulling out, because they are what make this robust rather than a toy:

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
- **In-flight requests are de-duplicated.** Each destination keys an `AbortController`; a newer click to the same target aborts the older fetch, so rapid navigation cannot land an out-of-order morph.
- **Failure degrades gracefully.** A network error on a GET falls back to `window.location.assign(url)` -- a real navigation -- so the user is never stranded on a dead click.

## A note on the accept header

You will see the fetch send `headers: { 'Accept': 'text/html' }`. **The server does not branch on it.** There is no content negotiation, no "partial vs full" response, no special header that flips the handler into a different mode. Every handler renders the complete page every time; the dispatcher is the only thing that decides to extract `<main>`. The `Accept` header is vestigial -- harmless, but do not build server logic around it. Keeping the server oblivious to *how* it is being called is exactly what lets the no-JavaScript path stay correct for free.

## History and accessibility

For link clicks and GET form submits, the dispatcher pushes a history entry to the fetched URL, so Back and Forward work. `popstate` re-fetches whatever URL the browser is now showing and morphs `<main>` again. After a navigation morph it moves focus to `<main>` (giving it `tabindex="-1"` if needed) so screen readers re-announce the new content, and it copies the new document's `<title>` over.

## Scripts inside a morph

One sharp edge of morphing (or any `innerHTML` swap): browsers do **not** execute `<script>` tags introduced that way. If a page embeds a per-page script inside `<main>`, a morph would insert it inert. The dispatcher handles this by re-materializing such scripts after the morph -- cloning each into a fresh `<script>` element (which the browser *does* run) and marking it `data-executed` so later morphs leave it alone:

```javascript
function executeScripts(root) {
  const scripts = root.querySelectorAll('script:not([data-executed])');
  scripts.forEach((old) => {
    const fresh = document.createElement('script');
    for (const attr of old.attributes) fresh.setAttribute(attr.name, attr.value);
    fresh.setAttribute('data-executed', 'true');
    fresh.text = old.textContent;
    old.replaceWith(fresh);
  });
}
```

In practice our pages avoid inline `<script>` inside `<main>` entirely (it would also fight our strict Content-Security-Policy -- see the [asset pipeline chapter](23-asset-pipeline.md)). Page-specific behavior is delivered as ES modules loaded once in `<head>` that *enhance* whatever DOM is present and re-run their setup on the `dispatcher:morphed` event the function fires at the end:

```javascript
document.dispatchEvent(new CustomEvent('dispatcher:morphed', {
  detail: { url: finalUrl, target, method },
}));
```

That event is the extension point. The `live-form`, `defer-details`, `server-preview`, and `admin-stats` modules each listen for it (or for native events) and idempotently wire up the elements they care about after every morph.

Concretely, a module loaded once in `<head>` looks like this -- it enhances whatever DOM is present on first load, and re-runs the same enhancement after every morph, guarding against double-wiring with a marker attribute:

```javascript
// static/js/live-form.js — loaded once; survives every morph.
function enhance(root = document) {
  for (const form of root.querySelectorAll('form[data-live]')) {
    if (form.dataset.enhanced) continue;   // idempotent: never wire twice
    form.dataset.enhanced = '1';
    form.addEventListener('input', validate);
  }
}

document.addEventListener('DOMContentLoaded', () => enhance());
document.addEventListener('dispatcher:morphed', () => enhance());
```

The shape is the whole point. The module never assumes it owns the lifecycle: it asks "which `form[data-live]` elements exist right now, and which have I not touched?" on both the initial load and after each morph. Idiomorph preserves the nodes it can and replaces the ones it must, so a morph may hand the module brand-new form elements (no `data-enhanced`, so they get wired) or the very same ones it saw before (already marked, so they are skipped). There is no teardown to write, because the element either survives the morph with its listener intact or is replaced wholesale and re-enhanced from scratch. That is why the contract is a single event and an idempotent `enhance` -- not a mount/unmount pair.

## Why this shape

This is progressive enhancement in the literal sense. The HTML is complete and functional on its own; the dispatcher is a strict speed-up layered on top. There is no client-side router to keep in sync with the server's routes, no template duplicated between server and client, no hydration step, and no handler that has to know whether it is talking to a browser navigation or a `fetch`. Delete `dispatcher.js` and the app still works -- every link navigates, every form posts. Keep it and navigation becomes a partial DOM morph that preserves UI state.

## Where this leaves us

One script, three listeners, and one exported function are the whole of it -- no router, no hydration, no second copy of the routing table on the client. The leverage comes from how little the rest of the system has to know about it: the server keeps rendering whole pages in blissful ignorance, and the only contract the client side exposes is a single `dispatcher:morphed` event and an idempotent `enhance`.

That narrow surface is also why `fetchAndMorph` turns out to be reused well beyond navigation. The dev hot-reload loop in [the morph-reload chapter](17-morph-reload.md) is, as that chapter puts it, just one more caller of this same function -- a view edit on the server becomes a morph in the browser through exactly this path, with no dev-only morphing code to maintain. And the whole island layer in [the progressive-enhancement chapter](18-progressive-enhancement.md) hangs off the one event this function fires. Build the dispatcher once, correctly, and two later chapters get to treat it as bedrock.
