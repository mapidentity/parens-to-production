# Progressive Enhancement: A Layered Architecture from SSR to Islands

[The morph-dispatcher chapter](15-morph-dispatcher.md) left us with a powerful primitive. `fetchAndMorph` takes a server-rendered response and reconciles it into the live DOM without a full reload, preserving scroll, focus, and open `<details>`. The positioning chapter argued *why* we render on the server; morphing is what makes a server-rendered app feel like it isn't reloading the world on every click.

But there is a trap hiding in how good that primitive is. Once you have a hammer that turns "the server has new HTML" into "the page updated smoothly," it is tempting to reach for it for *everything* -- every toggle, every menu, every animation, every bit of local interactivity. And morph will dutifully oblige, by round-tripping to the server for things the server has no opinion about, and by mutating the DOM in ways that make motion and transient state harder than they need to be.

The insight that drives this chapter is that **a rich interaction is not one thing.** Showing a menu, animating a card into its new slot, dragging to reorder a list, and applying a server-authored update are four different jobs with four different *narrowest correct mechanisms*, and three of those four are not morph. Matching the mechanism to the job is what lets a server-rendered app feel as alive as a single-page app without becoming one. It is the same lens [the previous chapter](19-morph-reload.md) turned on saves, where *a save is not one thing* either. Naming the narrowest correct mechanism for a job, and refusing to let one powerful tool annex the rest, is a move this book keeps making.

We call the mapping the *progressive-enhancement stack*, and it has five layers, each degrading cleanly to the one below it:

| Layer | Mechanism | Job |
| --- | --- | --- |
| 0 | Server-rendered HTML | Works with no JS at all -- SEO, the no-JS baseline, the source of truth |
| 1 | CSS / HTML platform primitives | Ephemeral UI state: menus, modals, disclosure, enter/leave motion |
| 2 | View Transitions | Animate any DOM change -- cross-fade, shared-element FLIP (First-Last-Invert-Play: measure an element before and after a change, then animate the inversion) |
| 3 | JS islands (the controller registry) | Local behavior CSS can't express -- pointer dragging, polling |
| 4 | Morph | Reconcile server-authored updates non-destructively |

The crucial property is the ordering. Every feature is built first at Layer 0, and each higher layer is *added on top* as an enhancement that a capable browser opportunistically takes. Disable JavaScript and Layer 0 still works. Disable the View Transitions API and the same update applies instantly. The layers are not alternatives you pick between; they are sediment.

This chapter assumes the server-rendered Hiccup views from [the Hiccup views chapter](14-hiccup-views.md), the client dispatcher's `fetchAndMorph` from [the morph-dispatcher chapter](15-morph-dispatcher.md), and the Datomic model from [the Datomic chapter](08-datomic.md). It also leans on the strict, no-nonce Content-Security-Policy that has gated our pages since the Hiccup views chapter's middleware: the policy authorises an inline script by its content hash, not by a per-response nonce, and [the asset pipeline chapter](29-asset-pipeline.md) is where that hashing gets built.

The running example is a feature we add along the way: a drag-to-reorder dashboard for a user's *own* recipes. That word *own* carries weight, because this is also where the book settles its multi-tenancy story. Layer 0's ownership section is the definitive treatment of tenant isolation, the enforcement the [primer](01-primer.md) and the [recipe-domain chapter](09-recipe-domain.md) both point forward to. The dashboard is scoped to the signed-in user through a session-derived `user-eid`; the magic-link login that produces that session arrives in the [authentication chapters](24-auth-tokens.md), and here we build the feature it plugs into.

One caution before we start: this chapter moves fast across a lot of newish platform surface -- popovers and anchor positioning, `@starting-style`, View Transitions, Pointer Events, Speculation Rules -- and each piece gets only a sentence or two. None is hard on its own, but expect to keep a reference tab open.

## Two kinds of state

Before the layers make sense, separate the two kinds of state an app holds, because the whole architecture turns on keeping them apart:

- **Canonical state** is the data: which recipes exist, who owns them, what order the user chose for their dashboard. It lives in the database, behind the server, and the browser only ever *renders* it. This is the state the positioning chapter put on the server.
- **Ephemeral state** is the interaction: is this menu open, is a drag in progress, is this dialog showing, is an element mid-animation. It is born in the browser, lives for seconds, and the server neither knows nor cares.

Morphing is the right tool for canonical state: the server re-renders, morph applies it. It is the *wrong* tool for ephemeral state, and most of the "morph feels heavy" instinct comes from using it there: round-tripping to toggle a menu, or losing a half-typed input because a morph blew it away. The layers below are mostly about giving ephemeral state a home that is *not* the server.

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

With JavaScript off, clicking ▲ posts `id` and `dir=up`, the server moves the recipe one slot and redirects back to the dashboard (the Post/Redirect/Get pattern), and the page reloads in the new order. It is not glamorous, but it *works*: it is keyboard-accessible, it is screen-reader-labeled, and it needs nothing from the client. Everything we add above this layer is gravy on a meal that is already complete.

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

> **Reordering is not a version.** Because every edit in this app is a Datomic transaction, the recipe's history comes for free. But that means a position change *would* show up as a version with an empty diff if we let it. `version-history` is restricted to the content attributes ([the recipe-domain chapter](09-recipe-domain.md) defines that set as `versioned-attrs`), so a reorder transaction never pollutes the timeline. The bookkeeping attribute and the content attributes live in the same entity but in different histories. This is the kind of thing that is invisible until you reorder a recipe and find a phantom "version" with nothing in it.

### Where ownership is enforced

`reorder!` quietly answers a question the book has so far only pointed at: the primer promised a *multi-tenant* SaaS, and this is where the isolation actually lives. The model is the one the primer named: the tenant is the user. One schema, one database, every recipe carrying a `:recipe/user` ref; isolation is the guarantee that an id arriving from outside the session -- a path param, a query param, a form field like `reorder!`'s `ids` -- can never be turned into another tenant's entity.

That guarantee has to live somewhere, and *where* is a real decision with three candidates. In middleware: a `wrap-owner` that inspects route params and rejects foreign ids before the handler runs, centralized but enforcing at a distance; it must know which param names an id and which attribute names the owner, and it silently protects nothing that arrives in a request body or in a route added after it was written. In each handler: an explicit check before every mutation, readable but multiplied by every route, and repeated discipline is exactly the kind that erodes. Or in the data layer, at the single point where an external id becomes a database entity. This app chooses the data layer, because every read and write -- from any handler, present or future -- already has to pass through that conversion. Enforce at the narrowest waist and there is nothing to forget.

The waist is a small family in `myapp.db.core`. Its workhorse:

```clojure
(defn entid-owned
  "Resolve a lookup-ref to an entity id, only if owned by `user-eid`.

  Returns nil for both 'no such entity' and 'entity exists but is owned
  by another user' — those are indistinguishable to the caller, so a
  handler can 404 in either case without leaking existence.

  `user-attr` defaults to the conventional `:<ns>/user` derived from the
  lookup-ref's attribute (e.g. [:recipe/id …] → :recipe/user). Pass an
  explicit `user-attr` only for entities that don't follow the convention.

  Use everywhere a non-session-derived id is converted to an eid."
  ([db user-eid lookup-ref] (entid-owned db user-eid lookup-ref (infer-user-attr lookup-ref)))
  ([db user-eid lookup-ref user-attr]
   (when-let [eid (d/entid db lookup-ref)]
     (when (= user-eid (entity-owner-eid db eid user-attr)) eid))))
```

The design's safety comes from three properties. The owner attribute is *inferred by convention* -- `[:recipe/id …]` implies `:recipe/user` -- so a call site cannot name the wrong attribute by accident, and an entity that breaks the convention must say so explicitly. A foreign entity and a missing entity are *indistinguishable*: both come back nil, the handler 404s either way, and a visitor probing other tenants' ids learns nothing -- not even that an id exists. And the family rounds out an API for each shape a foreign id can take: `pull-owned` for reads that render or prefill from an external id, `eid-owned?` for a raw eid, and `assert-owned!` to throw a tagged `:tenancy/forbidden` from inside a mutation, should a future refactor ever pass an eid through unchecked.

The rule that keeps the waist narrow is stated in the namespace itself: if a request handler ever writes `(d/entid db [:recipe/id …])` or a raw `d/pull` on an external id, it is bypassing tenant isolation: switch to the `*-owned` form. Half of that rule is even linted: the `:discouraged-var` entry from [the build-hardening chapter](04-build-hardening.md) flags raw `datomic.api/pull` wherever it appears. `reorder!` above shows the idiom under fire: it maps `entid-owned` over a *list* of user-supplied ids and drops the nils, so a crafted POST that slips someone else's recipe id into the order neither fails nor leaks: the foreign id simply is not part of the transaction. The same gate is what let [the recipe-domain chapter](09-recipe-domain.md)'s `update!` and `delete!` refuse a non-owner in one line. And the public reads -- browsing, the version history, forking itself -- deliberately gate nothing on ownership, because reading and forking other people's recipes is the product. Isolation guards *authority*, not *visibility*, and the fork is exactly where one tenant's data legitimately crosses into another's hands.

One of this section's invariants can even be *watched* rather than trusted: reorder a couple of cards under the `:storm` alias, then open the recipe's history page: the [construction view](17-construction-view.md)'s recording of that render shows `version-history` running its Datalog with `versioned-attrs` bound, and the reorder transaction appearing nowhere in what it returns. The tooling the book built early is not a separate story from the features; it is how a feature's claims get checked on the day they are written.

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

Destructive actions get a native `<dialog>` confirm. The dialog element is Layer 1; the small amount of glue that shows it before a submit is the first taste of Layer 3: a *controller*, which the next section formalizes. Notice it still degrades: with JavaScript off, the delete form simply submits, which is the Layer 0 behavior.

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

There are two halves. The cross-document half is pure CSS and was already enabled in [the Tailwind chapter](13-tailwind-styling.md): `@view-transition { navigation: auto }` makes the browser animate *full page navigations*, giving an ordinary multi-page app the page-to-page transitions people associate with SPAs, for zero JavaScript.

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

Two guards keep it tasteful. `ignoreActiveValue` is set by the live-preview enhancers that morph on every keystroke; animating those would be visual noise, so they opt out. And `prefers-reduced-motion` is honoured, both here and in CSS, so a user who asked for less motion gets the instant mutation. Everything else -- a navigation, a reorder, a server-reconciled update -- now animates.

The shared-element trick is what makes a reorder *read* as a reorder rather than a flicker. Each dashboard row carries a `view-transition-name` keyed by its id:

```clojure
[:li {:data-id (str id) :style (str "view-transition-name:recipe-" id)} ...]
```

When the list re-renders in a new order, the browser matches each old name to its new one and tweens the position: the cards visibly slide to their new slots. We wrote no animation code; we only gave the moving things stable names.

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

`scan` runs on first load and on the `dispatcher:morphed` event the morph layer already fires. It connects elements that match and have not been connected, and disconnects those that no longer match, whether because they left the DOM or because their marker was removed. The whole island layer hangs on that one event, and every behavior becomes a few lines.

> **The unified thing is the lifecycle, not the attribute.** A controller defaults to matching `data-controller="name"`, but it can override `selector` to keep an established marker of its own. The four enhancers in this codebase -- live-preview forms, deferred `<details>`, out-of-form previews, and the admin stat poller -- were each migrated onto the registry without changing the HTML they match. What was duplicated was never the attribute; it was the find-on-load-and-after-morph dance, and that now lives in exactly one place.

The flagship controller is `sortable`, and it is where the chapter's thesis gets its sharpest test. Dragging *needs* JavaScript: there is no CSS that reads a pointer, so a genuinely zero-JS drag-reorder does not exist. But the **motion** does not need JavaScript, and that is the part worth obsessing over. The modern technique is to stop hand-writing FLIP and let the View Transitions API do the choreography; the controller's whole job shrinks to translating "the pointer is here" into things CSS can act on.

It uses **Pointer Events**, not the older HTML5 drag-and-drop API, for one decisive reason: native drag-and-drop is unsupported or unreliable on touch, and a reorder that doesn't work on a phone is not a showcase. Pointer Events unify mouse, touch, and pen, and `touch-action: none` on the handle keeps a touch-drag from scrolling the page instead.

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

Then it persists, with a deliberately plain, fire-and-forget POST, *not* a morph:

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

The order is computed from the *captured drag state*, not from the live DOM, because `settle` runs inside the asynchronous View Transition callback, which has not reordered the DOM yet at this point. The second choice matters more: **this path does not morph.** The optimistic reorder is authoritative *by construction*: the server stores exactly the order we send and renders it back identically, so there is nothing to reconcile. And morphing here would actively harm: it would mutate the `view-transition-name`d rows *while the drop transition is still animating*, which leaves a ghost snapshot on screen. (That is a real bug we hit and backed out: never mutate a transitioning element mid-flight.) The keyboard and no-JS button paths *do* flow through the dispatcher's morph, because there the server is the one computing the new order; the drag path already knows it.

Both shapes reach one endpoint, and the server sorts out which it got. `recipe-reorder` dispatches on the payload: a comma-list of `ids` is a full explicit order (the drag island); an `id`+`dir` pair is a single-step move (the no-JS buttons), which `move!` expands into the same explicit order before writing it.

```clojure
(defn recipe-reorder
  "POST /recipes/reorder — persist the owner's dashboard order, then PRG-redirect."
  [request]
  (let [conn (db/get-connection)
        user-eid (:user-eid request)
        {:keys [ids id dir]} (:params request)
        move-id (parse-uuid* id)
        dir (some-> dir keyword)]
    (cond
      (seq ids)                          ; drag island → full explicit order
      (recipe/reorder! conn user-eid (keep parse-uuid* (str/split ids #",")))

      (and move-id (#{:up :down} dir))   ; no-JS buttons → single-step move
      (recipe/move! conn user-eid move-id dir))
    (response/redirect "/dashboard")))
```

That split is even watchable: under the `:storm` alias, Alt+click a card after an up/down reorder and the [construction view](17-construction-view.md) shows the `/dashboard` render behind the morph; do the same after a drag and there is no fresh render to show, because the island moved the DOM itself and told the server after the fact.

This is where the layers meet in a single gesture: Pointer Events track the drag (Layer 3); CSS animates the lift and the sibling glide (Layer 1 mechanics); a View Transition animates the drop (Layer 2); a background POST persists it; the up/down buttons reorder through a server round-trip and a reconciling morph (Layers 0 and 4); and underneath all of it, the same list still renders in its saved order with no JavaScript at all. The island never holds the truth -- it proposes an order the server has already agreed to.

## Layer 4: morph, demoted to what it is good at

With motion handed to Layer 2 and ephemeral state handed to Layers 1 and 3, morph stops being asked to do things it is bad at, and quietly becomes excellent at the one thing it is for: applying a server-authored update without destroying the page around it. In the reorder flow it is the reconciler: the island makes an optimistic move, the server is the authority, and the morph settles the DOM onto the authoritative order. If the two agree (the common case) the morph is a no-op; if they disagree, the View Transition animates the difference into place. Morph did not get worse by being demoted; it got *legible*, because it now has one job.

## Making the multi-page app feel instant

The remaining gap between a server-rendered app and an SPA is the network latency of a navigation. Speculation Rules close it without any of the SPA machinery: the browser prerenders likely-next pages in the background, so a click activates an already-built page. We emit an inline ruleset that prerenders same-origin pages on `moderate` eagerness -- hover or pointerdown -- excluding the stateful corners:

```clojure
(def speculation-rules-json
  (json/write-value-as-string
    {"prerender"
     [{"where" {"and" [{"href_matches" "/*"}
                       {"not" {"href_matches" "/auth/*"}}
                       {"not" {"href_matches" "/terms/*"}}
                       {"not" {"href_matches" "/admin/*"}}
                       ;; partials are fetched by islands, never navigated to
                       {"not" {"href_matches" "/partials/*"}}
                       {"not" {"selector_matches" "[data-no-prerender]"}}]}
       "eagerness" "moderate"}]}))
```

Paired with the cross-document View Transitions from Layer 2, the result is a multi-page app that navigates like a single-page one: hover a recipe, click it, and the already-rendered page transitions in. Because the inline script is gated by the strict CSP, its content hash is registered the same way every other inline script's is: the policy authorises exactly this ruleset and nothing else.

> **Prerendering has rules, and the dev tooling has to respect them.** A prerendered document is not allowed to hold a WebSocket; if it opens one, the browser discards the prerender. The dev live-reload script opens a `/dev/ws` socket, so it now defers that connection until the page is activated (`if (document.prerendering) document.addEventListener('prerenderingchange', connect)`). This is a dev-only concern -- production ships no such socket -- but it is a good illustration of the contract: prerendered code runs *speculatively*, and anything with side effects should wait until it knows it is real.

Prerendering hides the latency of the *next* navigation; HTTP caching removes it entirely for a page the browser has already seen. Here the server-authority model pays off once more, because Datomic makes some pages *immutable by construction*. A point-in-time view (`/recipes/:id/at/:t`) and a diff (`/recipes/:id/diff?from=…&to=…`) are pure functions of a recipe id and one or two Datomic basis-points, served straight from the [recipe domain](09-recipe-domain.md)'s `version-as-of`, so their bytes can never change. So the handler serves them with a year-long `immutable` cache header, and the browser never refetches them: the URL *plus the basis-t* is the version, which is the addressability thesis from the positioning chapter made literal. The header is `private`, not `public`, and the reasoning is worth being precise about. These HTML responses can embed the signed-in visitor's chrome -- their email, their nav -- which must never reach another visitor from a shared cache, so `private` is the uniform default. `wrap-no-cache-authenticated` then forces `no-store` on authenticated requests outright (so a logged-out user can't pull a cached signed-in page out of the back/forward cache), which means the `immutable` header only ever survives for anonymous viewers. Keeping it `private` rather than `public` even there costs nothing and keeps a signed-in page out of a shared proxy if that authenticated guard is ever bypassed. And anonymous viewers are exactly the ones a prerender, a revisit, or the back/forward cache helps most.

Caching has a prerequisite the layering quietly enforces: a page is cacheable only if it is *deterministic*. The landing page nearly broke that: it chose a random tagline on every render, which would make every response a unique document and defeat both caching and a stable prerender. The fix has the same shape as everything else in this chapter: the page server-renders one fixed default tagline (so the document is stable), and a tiny Layer-3 island swaps in a random one after load by fetching a `/partials/tagline` fragment. With no JavaScript the default simply stays. The randomness moved to a `no-store` partial -- itself excluded from the prerender rules, since it is fetched by an island and never navigated to -- while the page that wraps it stayed deterministic, and therefore cacheable.

Some latency, finally, cannot be hidden -- a form POST, a cold cache, a genuinely slow network -- and for that the honest answer is feedback. Because every navigation and reconciling update flows through `fetchAndMorph`, there is exactly one chokepoint to instrument: it toggles an `is-navigating` class on the document around the fetch-and-morph, and a single CSS rule paints a thin progress bar at the top of the viewport while it is set. This is pure Layer 1: no JavaScript animation, gone the moment the work completes, and absent entirely with JS off (a full navigation shows the browser's own progress UI). A counter rather than a boolean tracks overlapping updates, and a watchdog clears the bar if a navigation somehow never settles, so the indicator can never wedge itself on screen.

## Where the layering bottoms out

Honesty is part of the design. Not every feature reaches all the way down to Layer 0, and pretending otherwise is how you ship something that quietly breaks for someone.

- **Drag reordering is an enhancement, not a baseline.** Pointer math cannot be expressed in HTML, so without JavaScript there is no dragging, and that is a genuine limit, not a gap to paper over. What survives is the thing that matters: the list still renders in its saved order for everyone, and the up/down buttons still reorder it. Where JavaScript *is* present, choosing Pointer Events (over native drag-and-drop, which touch support treats unevenly) is what lets the enhancement reach touch and pen too. The *capability* degrades; the *data* does not.
- **The newest platform features have uneven support.** As of writing, `@starting-style` and same-document View Transitions have reached Baseline across Chromium, Firefox, and Safari. CSS anchor positioning is newer and shakier: it now ships unflagged in all three engines too -- Chromium 125, then Safari 26, and finally Firefox 147 in early 2026 -- but it is only just *newly available* rather than widely so, and still carries cross-engine rough edges, which is why we guard it behind `@supports` rather than lean on it. The stragglers are the whole-navigation features -- *cross-document* View Transitions animate in Chromium and Safari but not yet Firefox, and prerender-based instant navigation (Speculation Rules) is still Chromium-only. Because every one is layered as an enhancement, partial support is not a bug: the users whose engine has shipped the newest of them get the animation and the prefetched-instant feel, and everyone else gets a plain, correctly-placed result that works just as well. That is the contract working as intended.
- **Motion is opt-out.** Every animation in the stack checks `prefers-reduced-motion`, so the layering never forces movement on someone who asked for stillness.

## What we deliberately did not build

It is worth naming the roads not taken, because they are the obvious ones.

We did not reach for a single-page framework. The layered model gets the SPA's best properties -- smooth transitions, shared-element animation, instant navigation where the engine supports it, local interactivity -- without moving rendering or canonical state into the browser, and so without the costs [the positioning chapter](02-positioning.md) spent its length arguing against.

We also did not reach for server-driven UI over a socket (the LiveView/Livewire shape). Its center of gravity is the inverse of ours: it keeps the canonical UI state on the server, behind a stateful socket and server memory per connected client, where we keep only domain state there and give ephemeral state a home in the browser by default. It is a serious sibling -- the positioning chapter weighed it in full -- and the narrower point here is that a morph reconciling browser-held ephemeral state into server-held canonical state is what lets each layer stay simple and degrade on its own.

The thesis, then, is the same one [the positioning chapter](02-positioning.md) opened with, now made concrete: progressive enhancement is how a rendering surface becomes a *rich* one -- by adding capability in layers, each of which a browser takes only if it can, and none of which the experience depends on.
