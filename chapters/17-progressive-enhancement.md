# Progressive Enhancement: A Layered Architecture from SSR to Islands

The morph chapter left us with a powerful primitive. `fetchAndMorph` takes a server-rendered response and reconciles it into the live DOM without a full reload, preserving scroll, focus, and open `<details>`. The positioning chapter argued *why* we render on the server; morphing is what makes a server-rendered app feel like it isn't reloading the world on every click.

But there is a trap hiding in how good that primitive is. Once you have a hammer that turns "the server has new HTML" into "the page updated smoothly," it is tempting to reach for it for *everything* -- every toggle, every menu, every animation, every bit of local interactivity. And morph will dutifully oblige, by round-tripping to the server for things the server has no opinion about, and by mutating the DOM in ways that make motion and transient state harder than they need to be.

The insight that drives this chapter is that **a rich interaction is not one thing.** Showing a menu, animating a card into its new slot, dragging to reorder a list, and applying a server-authored update are four different jobs with four different *narrowest correct mechanisms* -- and three of those four are not morph. Matching the mechanism to the job is what lets a server-rendered app feel as alive as a single-page app without becoming one.

We call the mapping the *progressive-enhancement stack*, and it has five layers, each degrading cleanly to the one below it:

| Layer | Mechanism | Job |
| --- | --- | --- |
| 0 | Server-rendered HTML | Works with no JS at all -- SEO, the no-JS baseline, the source of truth |
| 1 | CSS / HTML platform primitives | Ephemeral UI state: menus, modals, disclosure, enter/leave motion |
| 2 | View Transitions | Animate any DOM change -- cross-fade, shared-element FLIP |
| 3 | JS islands (the controller registry) | Local behavior CSS can't express -- pointer dragging, polling |
| 4 | Morph | Reconcile server-authored updates non-destructively |

The crucial property is the ordering. Every feature is built first at Layer 0, and each higher layer is *added on top* as an enhancement that a capable browser opportunistically takes. Disable JavaScript and Layer 0 still works. Disable the View Transitions API and the same update applies instantly. The layers are not alternatives you pick between; they are sediment.

This chapter assumes the server-rendered Hiccup views from [the Hiccup views chapter](11-hiccup-views.md), the client dispatcher's `fetchAndMorph` from [the morph-dispatcher chapter](11b-morph-dispatcher.md), the strict no-nonce CSP from [the asset pipeline chapter](22-asset-pipeline.md), and the Datomic model from [the Datomic chapter](07-datomic.md). The running example is a feature we add along the way: a drag-to-reorder dashboard for a user's own recipes.

## Two kinds of state

Before the layers make sense, separate the two kinds of state an app holds, because the whole architecture turns on keeping them apart:

- **Canonical state** is the data: which recipes exist, who owns them, what order the user chose for their dashboard. It lives in the database, behind the server, and the browser only ever *renders* it. This is the state the positioning chapter put on the server.
- **Ephemeral state** is the interaction: is this menu open, is a drag in progress, is this dialog showing, is an element mid-animation. It is born in the browser, lives for seconds, and the server neither knows nor cares.

Morphing is the right tool for canonical state -- the server re-renders, morph applies it. It is the *wrong* tool for ephemeral state, and most of the "morph feels heavy" instinct comes from using it there: round-tripping to toggle a menu, or losing a half-typed input because a morph blew it away. The layers below are mostly about giving ephemeral state a home that is *not* the server.

## Layer 0: the baseline that always works

Everything starts as plain server-rendered HTML with real links and real forms. The dashboard's reorder feature is no exception. Before any JavaScript, the recipe list is an ordered server render, and reordering is two submit buttons per row:

```clojure
(defn- reorder-controls
  "No-JS / keyboard reorder path (Layer 0).
  Up & down submit buttons POST a single-step move to /recipes/reorder. The
  dispatcher enhances the submit into an animated morph; without JS it is an
  ordinary form post + redirect."
  [locale id]
  [:form.flex.flex-col.items-center.leading-none {:method "POST" :action "/recipes/reorder"}
   [:input {:type "hidden" :name "id" :value (str id)}]
   [:button {:type "submit" :name "dir" :value "up" :aria-label (t locale :dashboard/move-up)} "▲"]
   [:button {:type "submit" :name "dir" :value "down" :aria-label (t locale :dashboard/move-down)} "▼"]])
```

With JavaScript off, clicking ▲ posts `id` and `dir=up`, the server moves the recipe one slot and redirects back to the dashboard (the Post/Redirect/Get pattern), and the page reloads in the new order. It is not glamorous, but it *works* -- it is keyboard-accessible, it is screen-reader-labeled, and it needs nothing from the client. Everything we add above this layer is gravy on a meal that is already complete.

The canonical state behind it is a single new attribute and two pure mutations. Order is a `:recipe/position` long, assigned on create and rewritten on reorder:

```clojure
(defn reorder!
  "Set explicit :recipe/position on `ids` (UUIDs) in the given order, for recipes
  owned by `user-eid`. Ids not owned by the user are silently skipped (tenant
  isolation). Does NOT bump :recipe/updated-at -- a reorder is not a content edit
  and must not create a version. Returns true."
  [conn user-eid ids]
  (let [db (d/db conn)
        tx (->> ids
                (map-indexed
                  (fn [i id]
                    (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
                      {:db/id eid :recipe/position (long i)})))
                (remove nil?)
                vec)]
    (when (seq tx) @(db/transact* conn tx))
    true))
```

> **Reordering is not a version.** Because every edit in this app is a Datomic transaction, the recipe's history comes for free -- but that means a position change *would* show up as a version with an empty diff if we let it. `version-history` is restricted to the content attributes, so a reorder transaction never pollutes the timeline. The bookkeeping attribute and the content attributes live in the same entity but in different histories. This is the kind of thing that is invisible until you reorder a recipe and find a phantom "version" with nothing in it.

## Layer 1: the platform does more than you think

The last few years quietly moved a large class of interactions out of JavaScript and into HTML and CSS. Things that used to *require* a framework -- positioned menus, modal dialogs, enter/leave animation, "style the parent based on a child" -- are now declarative platform features. Using them means less JavaScript to ship, less state to manage, and no round trip.

The recipe detail page's owner actions are a declarative **popover**. No JavaScript opens it; the `popovertarget` attribute wires the button to the menu, and CSS anchor positioning places it:

```clojure
(defn- owner-actions-menu
  "Owner-only Edit/Delete as a declarative Popover menu (Layer 1)."
  [locale recipe]
  (let [id (:recipe/id recipe)
        pop-id (str "actions-" id)
        anchor (str "--" pop-id)]
    [:div.relative.inline-block
     [:button.actions-trigger
      {:type "button" :popovertarget pop-id
       :style (str "anchor-name:" anchor)
       :aria-label (t locale :recipe/actions)} "⋯"]
     [:div.actions-menu {:id pop-id :popover "auto" :style (str "position-anchor:" anchor)}
      [:a {:href (str "/recipes/" id "/edit")} (t locale :recipe/edit)]
      ...]]))
```

The popover's open/close animation -- including the transition *out of* `display: none`, which used to be impossible in pure CSS -- is `@starting-style` plus `transition-behavior: allow-discrete`:

```css
.actions-menu {
  transition:
    opacity 120ms ease-out,
    transform 120ms ease-out,
    overlay 120ms allow-discrete,
    display 120ms allow-discrete;
}
.actions-menu:not(:popover-open) { opacity: 0; transform: translateY(-4px); }
@starting-style {
  .actions-menu:popover-open { opacity: 0; transform: translateY(-4px); }
}
@supports (anchor-name: --x) {
  .actions-menu { position: absolute; top: calc(anchor(bottom) + 4px); right: anchor(right); }
}
```

Destructive actions get a native `<dialog>` confirm. The dialog element is Layer 1; the small amount of glue that shows it before a submit is the first taste of Layer 3 -- a *controller*, which the next section formalizes. Notice it still degrades: with JavaScript off, the delete form simply submits, which is the Layer 0 behavior.

`:has()` earns its place too. The dashboard's drag-reorder feel -- siblings gliding aside, the dragged row lifting and tilting, the rest receding -- is expressed almost entirely in CSS, with JavaScript reaching in only to set a single custom property:

```css
[data-controller~="sortable"] > li { transition: transform 180ms cubic-bezier(0.2, 0.8, 0.2, 1); }
[data-controller~="sortable"] > li.dragging {
  transition: none;                                              /* tracks the pointer, no lag */
  transform: translateY(var(--dy, 0)) scale(1.03) rotate(-0.6deg);
}
[data-controller~="sortable"]:has(.dragging) > li:not(.dragging) .card { opacity: 0.85; }
```

The controller adds a `.dragging` class and updates one variable, `--dy`, as the pointer moves; CSS composes that into the lift transform, animates the siblings via the `transition`, and -- through `:has()` -- dims everything else while a drag is live, with no class plumbing on the container. JavaScript writes a number; CSS does the motion.

One more HTML/CSS technique pulls its weight in the recipe cards: the **stretched link**. A card should be clickable all over, but the obvious implementation -- wrapping the whole card in an `<a>` -- is a trap, because the card also holds a *second* link (the "forked from" badge), and an anchor nested in an anchor is invalid HTML that the browser silently splits, hoisting the inner link out and leaving an empty stub where the card used to be. Instead the card is a `<div>`, the title is the link, and one rule stretches its hit area over the whole card:

```css
.card-link::after { content: ""; position: absolute; inset: 0; }
```

The badge, a sibling link, lifts above that overlay with `position: relative; z-index`. The result is a fully clickable card *and* an independent secondary link, with zero nested anchors and no JavaScript.

Every one of these features is progressive: where a browser lacks `@starting-style` the menu still opens, just without the fade; where it lacks anchor positioning the popover still appears in the top layer.

## Layer 2: view transitions, where motion lives

Animation is the job morph is worst at, because morph mutates the DOM in place and gives you no before/after to interpolate. The View Transitions API inverts that: you hand the browser a function that mutates the DOM, and it snapshots the page before and after and animates the difference.

There are two halves. The cross-document half is pure CSS and was already enabled in [the Tailwind chapter](10-tailwind-styling.md) -- `@view-transition { navigation: auto }` makes the browser animate *full page navigations*, giving an ordinary multi-page app the page-to-page transitions people associate with SPAs, for zero JavaScript.

The same-document half is one change at the single chokepoint every in-page update already flows through. In `fetchAndMorph`, the morph is wrapped in `document.startViewTransition`:

```javascript
const applyMorph = () => {
  morph(targetEl, fragmentEl, { morphStyle: 'innerHTML', ignoreActiveValue });
  if (traceId) targetEl.setAttribute('data-myapp-trace-id', traceId);
};
const animate = !!document.startViewTransition
  && !ignoreActiveValue
  && !(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
if (animate) {
  await document.startViewTransition(applyMorph).updateCallbackDone;
} else {
  applyMorph();
}
```

Two guards keep it tasteful. `ignoreActiveValue` is set by the live-preview enhancers that morph on every keystroke -- animating those would be visual noise, so they opt out. And `prefers-reduced-motion` is honoured, both here and in CSS, so a user who asked for less motion gets the instant mutation. Everything else -- a navigation, a reorder, a server-reconciled update -- now animates.

The shared-element trick is what makes a reorder *read* as a reorder rather than a flicker. Each dashboard row carries a `view-transition-name` keyed by its id:

```clojure
[:li {:data-id (str id) :style (str "view-transition-name:recipe-" id)} ...]
```

When the list re-renders in a new order, the browser matches each old name to its new one and tweens the position -- the cards visibly slide to their new slots. We wrote no animation code; we only gave the moving things stable names.

## Layer 3: islands, and the registry that runs them

Some behavior genuinely needs JavaScript: pointer math for dragging, a polling timer, a dialog shown before a submit. The mistake is to let each of these grow its own ad-hoc lifecycle -- find my elements on load, find them *again* after every morph, guard against double-wiring, tear down on the way out. That logic is identical for every behavior, and re-implementing it per module is how you get four subtly different versions of the same bug.

So Layer 3 is a single small **controller registry** that owns the lifecycle, and behaviors that declare only `connect`/`disconnect`:

```javascript
export function register(name, controller) {
  registry.set(name, {
    selector: controller.selector || '[data-controller~="' + name + '"]',
    connect: controller.connect,
    disconnect: controller.disconnect,
  });
  if (document.readyState !== 'loading') scan();
}

function scan() {
  for (const [name, ctrl] of registry) {
    document.querySelectorAll(ctrl.selector).forEach((el) => connectEl(el, name));
  }
  for (const [el, set] of elState) {
    for (const name of [...set]) {
      const ctrl = registry.get(name);
      if (!document.contains(el) || !ctrl || !el.matches(ctrl.selector)) disconnectEl(el, name);
    }
  }
}

document.addEventListener('DOMContentLoaded', scan);
document.addEventListener('dispatcher:morphed', scan);
```

`scan` runs on first load and on the `dispatcher:morphed` event the morph layer already fires. It connects elements that match and have not been connected, and disconnects those that no longer match -- whether because they left the DOM or because their marker was removed. The whole island layer hangs on that one event, and every behavior becomes a few lines.

> **The unified thing is the lifecycle, not the attribute.** A controller defaults to matching `data-controller="name"`, but it can override `selector` to keep an established marker of its own. The four enhancers in this codebase -- live-preview forms, deferred `<details>`, out-of-form previews, and the admin stat poller -- were each migrated onto the registry without changing the HTML they match. What was duplicated was never the attribute; it was the find-on-load-and-after-morph dance, and that now lives in exactly one place.

The flagship controller is `sortable`, and it is where the chapter's thesis gets its sharpest test. Dragging *needs* JavaScript -- there is no CSS that reads a pointer, and a survey of the state of the art confirms that a genuinely zero-JS drag-reorder does not exist. But the **motion** does not need JavaScript, and that is the part worth obsessing over. The modern technique (Adam Argyle's drag demos, the Frontend Masters "kick-flip" reorder) is to stop hand-writing FLIP and let the View Transitions API do the choreography; the controller's whole job shrinks to translating "the pointer is here" into things CSS can act on.

It uses **Pointer Events**, not the older HTML5 drag-and-drop API, for one decisive reason: native drag is mouse-only, and a reorder that doesn't work on a phone is not a showcase. Pointer Events unify mouse, touch, and pen, and `touch-action: none` on the handle keeps a touch-drag from scrolling the page instead.

As the pointer moves, the controller sets `--dy` on the lifted row and nudges the siblings out of the way -- nothing more:

```javascript
state.item.style.setProperty('--dy', dy + 'px');     // CSS composes this into the lift transform
state.rows.forEach((el, i) => {
  if (el === state.item) return;
  let shift = 0;
  if (state.from < to && i > state.from && i <= to) shift = -state.step;
  else if (state.from > to && i >= to && i < state.from) shift = state.step;
  el.style.transform = shift ? `translateY(${shift}px)` : '';   // the CSS transition animates the glide
});
```

That is the entire "animation" code: it writes a pixel value, and the `transition: transform` rule turns each change into a glide. The dragged row has `transition: none`, so it tracks the finger with no lag while its `scale`/`rotate` lift comes from the static `.dragging` rule. The target index is computed against the rows' *original* midpoints, captured once at the start, so the calculation never feeds back on its own displacements.

The drop is the one place a View Transition does the work. On release, the controller wraps the final reorder -- clear the transforms, move the row to its new index -- in `startViewTransition`. Because every row carries a `view-transition-name`, the browser FLIPs each from where the drag left it to its settled slot, and CSS owns the result:

```javascript
if (moved && document.startViewTransition && !reducedMotion()) {
  document.startViewTransition(() => settle(s));
} else {
  settle(s);
}
```

Then it persists -- with a deliberately plain, fire-and-forget POST, *not* a morph:

```javascript
const ids = s.rows.map((el) => el.getAttribute('data-id'));
ids.splice(s.to, 0, ids.splice(s.from, 1)[0]);   // final order from captured state, not the live DOM
fetch(container.getAttribute('data-sortable-url'), {
  method: 'POST',
  body: new URLSearchParams({ ids: ids.join(',') }),
  credentials: 'same-origin',
  redirect: 'manual',
}).catch(() => {});
```

Two decisions are worth dwelling on. First, the order is computed from the *captured drag state*, not by reading the live DOM -- because `settle` runs inside the asynchronous View Transition callback, which has not reordered the DOM yet at this point. Second, and more importantly, **this path does not morph.** The optimistic reorder is authoritative *by construction*: the server stores exactly the order we send and renders it back identically, so there is nothing to reconcile. And morphing here would actively harm -- it would mutate the `view-transition-name`d rows *while the drop transition is still animating*, which leaves a ghost snapshot on screen. (That is a real bug we hit and backed out: never mutate a transitioning element mid-flight.) The keyboard and no-JS button paths *do* flow through the dispatcher's morph, because there the server is the one computing the new order; the drag path already knows it.

This is where the layers meet in a single gesture: Pointer Events track the drag (Layer 3); CSS animates the lift and the sibling glide (Layer 1 mechanics); a View Transition animates the drop (Layer 2); a background POST persists it; the up/down buttons reorder through a server round-trip and a reconciling morph (Layers 0 and 4); and underneath all of it, the same list still renders in its saved order with no JavaScript at all. The island never holds the truth -- it proposes an order the server has already agreed to.

## Layer 4: morph, demoted to what it is good at

With motion handed to Layer 2 and ephemeral state handed to Layers 1 and 3, morph stops being asked to do things it is bad at, and quietly becomes excellent at the one thing it is for: applying a server-authored update without destroying the page around it. In the reorder flow it is the reconciler -- the island makes an optimistic move, the server is the authority, and the morph settles the DOM onto the authoritative order. If the two agree (the common case) the morph is a no-op; if they disagree, the View Transition animates the difference into place. Morph did not get worse by being demoted; it got *legible*, because it now has one job.

## Making the multi-page app feel instant

The remaining gap between a server-rendered app and an SPA is the network latency of a navigation. Speculation Rules close it without any of the SPA machinery: the browser prerenders likely-next pages in the background, so a click activates an already-built page. We emit an inline ruleset that prerenders same-origin pages on `moderate` eagerness -- hover or pointerdown -- excluding the stateful corners:

```clojure
(def speculation-rules-json
  (json/write-value-as-string
    {"prerender"
     [{"where" {"and" [{"href_matches" "/*"}
                       {"not" {"href_matches" "/auth/*"}}
                       {"not" {"href_matches" "/admin/*"}}
                       {"not" {"selector_matches" "[data-no-prerender]"}}]}
       "eagerness" "moderate"}]}))
```

Paired with the cross-document View Transitions from Layer 2, the result is a multi-page app that navigates like a single-page one: hover a recipe, click it, and the already-rendered page transitions in. Because the inline script is gated by the strict CSP, its content hash is registered the same way every other inline script's is -- the policy authorises exactly this ruleset and nothing else.

> **Prerendering has rules, and the dev tooling has to respect them.** A prerendered document is not allowed to hold a WebSocket; if it opens one, the browser discards the prerender. The dev live-reload script opens a `/dev/ws` socket, so it now defers that connection until the page is activated (`if (document.prerendering) document.addEventListener('prerenderingchange', connect)`). This is a dev-only concern -- production ships no such socket -- but it is a good illustration of the contract: prerendered code runs *speculatively*, and anything with side effects should wait until it knows it is real.

Prerendering hides the latency of the *next* navigation; HTTP caching removes it entirely for a page the browser has already seen. Here the server-authority model pays off once more, because Datomic makes some pages *immutable by construction*. A point-in-time view (`/recipes/:id/at/:t`) and a diff (`/recipes/:id/diff?from=…&to=…`) are pure functions of a recipe id and one or two Datomic basis-points -- their bytes can never change. So the handler serves them with a year-long `immutable` cache header, and the browser never refetches them: the URL *plus the basis-t* is the version, which is the addressability thesis from the positioning chapter made literal. The header is `private`, not `public`, for one careful reason -- the rendered page embeds the signed-in visitor's chrome (their email, their nav), so it must never land in a shared cache. Authenticated responses are forced to `no-store` anyway (so a logged-out user can't see a cached signed-in page through the back/forward cache), which leaves the immutable caching to do its work exactly where it is safe: for anonymous viewers, who are also the ones a prerender helps most.

Caching has a prerequisite the layering quietly enforces: a page is cacheable only if it is *deterministic*. The landing page nearly broke that -- it chose a random tagline on every render, which would make every response a unique document and defeat both caching and a stable prerender. The fix has the same shape as everything else in this chapter: the page server-renders one fixed default tagline (so the document is stable), and a tiny Layer-3 island swaps in a random one after load by fetching a `/partials/tagline` fragment. With no JavaScript the default simply stays. The randomness moved to a `no-store` partial -- itself excluded from the prerender rules, since it is fetched by an island and never navigated to -- while the page that wraps it stayed deterministic, and therefore cacheable.

Some latency, finally, cannot be hidden -- a form POST, a cold cache, a genuinely slow network -- and for that the honest answer is feedback, not cleverness. Because every navigation and reconciling update flows through `fetchAndMorph`, there is exactly one chokepoint to instrument: it toggles an `is-navigating` class on the document around the fetch-and-morph, and a single CSS rule paints a thin progress bar at the top of the viewport while it is set. This is pure Layer 1 -- no JavaScript animation, gone the moment the work completes, and absent entirely with JS off (a full navigation shows the browser's own progress UI). A counter rather than a boolean tracks overlapping updates, and a watchdog clears the bar if a navigation somehow never settles, so the indicator can never wedge itself on screen.

## Where the layering bottoms out

Honesty is part of the design. Not every feature reaches all the way down to Layer 0, and pretending otherwise is how you ship something that quietly breaks for someone.

- **Drag reordering is an enhancement, not a baseline.** Pointer math cannot be expressed in HTML, so without JavaScript there is no dragging -- and that is a genuine limit, not a gap to paper over. What survives is the thing that matters: the list still renders in its saved order for everyone, and the up/down buttons still reorder it. Where JavaScript *is* present, choosing Pointer Events (over the mouse-only native drag API) is what lets the enhancement reach touch and pen too. The *capability* degrades; the *data* does not.
- **The newest platform features have uneven support.** As of writing, same-document View Transitions, anchor positioning, and `@starting-style` are ahead in Chromium and arriving at different paces elsewhere. Because every one is layered as an enhancement, partial support is not a bug -- some users get the animation and the anchored menu, the rest get an instant, correctly-placed result. That is the contract working as intended, not failing.
- **Motion is opt-out.** Every animation in the stack checks `prefers-reduced-motion`, so the layering never forces movement on someone who asked for stillness.

## What we deliberately did not build

It is worth naming the roads not taken, because they are the obvious ones.

We did not reach for a single-page framework. The layered model gets the SPA's best properties -- smooth transitions, shared-element animation, instant navigation, local interactivity -- without moving rendering or canonical state into the browser, and so without the hydration tax, the client/server state duplication, and the build pipeline the positioning chapter spent its length arguing against.

We also did not reach for server-driven UI over a socket (the LiveView/Livewire shape). That would pull ephemeral state *back* to the server and turn the browser into a dumb terminal -- the exact inversion of the principle this chapter is built on. Keeping canonical state on the server and ephemeral state in the browser, joined by a morph that reconciles one into the other, is what lets each layer stay simple and each degrade on its own.

The thesis, then, is the same one the positioning chapter opened with, now made concrete: the server is the authority, the browser is a rendering surface, and progressive enhancement is how a rendering surface becomes a *rich* one -- by adding capability in layers, each of which a browser takes only if it can, and none of which the experience depends on.
