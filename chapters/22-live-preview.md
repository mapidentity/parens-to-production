# Rendering the Future: Live Preview with d/with

Ask a room of web developers which features *need* a client-side application and the same short list always comes back. Live preview is usually first: the user types markdown on the left, the rendered result tracks on the right, and surely -- surely -- that means rendering logic in the browser. The standard architecture agrees. It ships a second markdown implementation to the client, then spends the rest of its life keeping two renderers honest with each other: same flavor, same sanitizer, same edge cases, one in Clojure and one in JavaScript, drifting.

The [previous chapter](21-forms-validation.md) ended on the observation that unlocks a different answer: `conform` produces, from raw form input, the values the domain would store -- *without storing them*. An input that can be judged without being committed can be **rendered** without being committed. What that takes is a database that contains your unsaved edit; and Datomic, because [a database here is an immutable value you hold in your hand](08-datomic.md), will manufacture one on request.

```clojure
(d/with db tx-data)
;; => {:db-after <a database in which tx-data happened>, ,,,}
```

`d/with` applies transaction data to a database *value* and returns the result as another database value. Nothing reaches the transactor. Nothing is written. No other thread, request, or peer can observe it. It is the whole architecture of this application -- pure functions from a db value to HTML -- pointed at a state of the world that does not exist yet. This chapter turns that into the SPA's home-turf feature in one domain function, one handler, and zero new lines of JavaScript.

## The domain function: the recipe as it would read

```clojure
(defn preview
  "The recipe as it WOULD read, rendered from a speculative database.
  Applies conformed content `values` — to the existing recipe `id` (owner
  only), or as a new recipe when `id` is nil. `d/with` returns a db that
  contains the change without transacting anything; we pull from it exactly
  as `recipe-by-id` would. Returns the pulled map, or nil when `id` isn't
  the caller's to preview.

  Because the speculative db is a real db value, everything downstream is
  the real read path: same pull pattern, same markdown, same formatting —
  and, for an edit, the recipe's real fork provenance and timestamps."
  [db user-eid id {:keys [title description servings ingredients steps]}]
  (assert-content! {:title title :servings servings})
  (let [now (time/now)
        content {:recipe/title title
                 ,,,}]
    (if id
      (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
        (let [{:keys [db-after]} (db/with* db
                                   [(assoc content
                                      :db/id eid
                                      :recipe/updated-at now)])]
          (db/pull* db-after pull-pattern eid)))
      (let [{:keys [db-after tempids]}
            (db/with* db
              [(assoc content
                 :db/id "preview"
                 :recipe/id (UUID/randomUUID)
                 :recipe/user user-eid
                 ,,,)])]
        (db/pull* db-after pull-pattern (d/resolve-tempid db-after tempids "preview"))))))
```

The function is short because it is made of parts that already existed, and each reuse is a small argument.

**The tx-data is `create!`/`update!`'s tx-data.** The edit branch builds the same entity map `update!` would transact; the new branch builds `create!`'s, temp-id and all, and resolves the temp-id out of `:tempids` to know what to pull. The preview is honest *by construction*: it cannot show you something different from what saving would produce, because it speaks the identical transaction dialect. (When [chapter 10](10-provenance.md)'s transaction annotations arrive on the write path, the preview needs nothing -- tx metadata doesn't change what the entity pulls.)

**The read is `recipe-by-id`'s read.** Same `pull-pattern`, same nested owner and fork provenance. An edit preview therefore renders with the recipe's *real* `created-at`, its *real* forked-from banner -- state the form knows nothing about, which a client-side preview could only fake with more shipped data.

**The boundaries hold.** `assert-content!` guards the door exactly as it does for real writes: the preview endpoint only ever passes conformed values, and a future caller that forgets learns loudly. And `entid-owned` -- [the tenant-isolation helper every mutation goes through](20-progressive-enhancement.md) -- answers the ownership question before any speculation happens. Previewing an edit to someone else's recipe is refused *like the edit itself would be*; a preview is a read, but it is a read of a write, and it inherits the write's rules.

> **Trade-off -- what `d/with` does and does not check.** The speculative db is not a lawless sandbox: `d/with` enforces schema types and uniqueness against the value you gave it, which is why the java.time bridge below was forced into the open. What it cannot give you is the transactor's serialized view of *now*. A uniqueness conflict with a transaction that lands between your preview and your save will only surface at the save. For a preview pane this is the right trade: perfect fidelity to the data semantics, zero coordination cost, and the real transaction remains the single authority on admission.

One wrinkle earned its way into `myapp.db.core` rather than staying buried in a test failure. The first run of the preview tests produced:

```
:db.error/wrong-type-for-attribute Value 2026-07-18T12:07:11Z
is not a valid :inst for attribute :recipe/updated-at
```

[The Datomic chapter](08-datomic.md) built a `java.time` bridge -- `transact*` converts `Instant` to `Date` on the way in, `pull*` converts back on the way out -- and every write in the application flows through it. `d/with` is a write that *doesn't*: it takes tx-data straight, so the bridge's blind spot became visible the first time tx-data bypassed `transact*`. The fix is the bridge's missing sibling, three lines that keep the rule "application code speaks `java.time`, period" true for databases that will never be transacted:

```clojure
(defn with*
  "Like d/with, with the java.time bridge applied to tx-data.
  Converts Instant values to Date exactly as `transact*` does — for a
  database value that will never be transacted (speculative writes)."
  [db tx-data]
  (d/with db (mapv convert-instants tx-data)))
```

## The view: the preview *is* the page

The temptation now is to write a `render-preview` view. Resist it -- it is the same trap as the second markdown renderer, one layer up. Instead, the recipe detail page's content -- the rendered markdown, the ingredients and steps columns -- moves into a fragment both callers share:

```clojure
(defn- recipe-body
  "The recipe's content: markdown description, ingredients, steps.
  Shared verbatim between the detail page and the live-preview pane: the
  preview is not a reimplementation of this view, it IS this
  view, fed a database value that was never transacted."
  [locale recipe]
  (list
    (when-not (str/blank? (:recipe/description recipe))
      [:div.legal-content.mt-4 (h/raw (markdown/render (:recipe/description recipe)))])
    [:div.mt-8.grid.gap-8.sm:grid-cols-2
     ,,,]))                               ; the ingredients/steps sections, unchanged
```

`recipe-detail` calls it where its body used to be inline; the preview pane calls it under a compact header. Every consequence a reader might worry about is settled by the sharing. Does the preview sanitize markdown the same way? It is [the same `markdown/render`](14-hiccup-views.md), behind the same escaping renderer. Will the preview drift from the page as the page evolves? It cannot; there is nothing to drift. The one piece of preview-only rendering is the waiting state:

```clojure
(defn- recipe-preview-content
  "Inside of the live-preview pane.
  A compact header (title, servings) over the shared `recipe-body`, or the
  waiting hint until the input conforms."
  [locale recipe]
  (if recipe
    (list ,,,)
    [:p.text-sm.text-text-secondary (t locale :recipe/preview-waiting)]))
```

That `nil` branch is worth a pause, because it is [the last chapter's](21-forms-validation.md) placeholder discipline paying rent. When the form's input does not yet conform -- no title typed, servings mid-edit -- the pane does not preview a coined `"Untitled recipe"`; the domain no longer *has* defaults to coin. It renders an honest waiting hint, in the user's language, owned entirely by the view layer. Placeholders belong to rendering; defaults never belonged to the domain.

## The endpoint: a preview is not a mutation attempt

```clojure
(defn recipe-preview
  "POST /recipes/new/preview and /recipes/:id/preview — the preview pane.
  Renders the shared recipe views against a d/with speculative db;
  transacts nothing. Always 200 — a preview is not a mutation attempt, and
  until the input conforms the pane simply shows its waiting state.
  no-store: every keystroke is a new hypothetical."
  [request]
  (let [id (path-uuid request)
        {:keys [values errors]} (recipe/conform (recipe-params request))
        pulled (when-not errors
                 (recipe/preview (d/db (db/get-connection)) (:user-eid request) id values))]
    (-> (html (views/recipe-preview-pane (:locale request) pulled))
        (assoc-in [:headers "Cache-Control"] "no-store"))))
```

Set this beside `recipe-create` and the pair teaches the chapter's grammar lesson. Same `recipe-params`, same `conform`, same door, but where invalid input made `recipe-create` answer **422**, here it renders the waiting state at **200**. That is not an inconsistency; it is the status codes meaning what they say. A failed create is a *request to change the world* that was refused. A preview of half-typed input is a *question* -- "what would this look like?" -- and "nothing yet, keep typing" is a perfectly successful answer to it. Errors-as-values flow to whatever the surface needs: the form renders them as field errors, the preview renders them as patience.

`no-store` closes the loop [the asset-pipeline chapter](29-asset-pipeline.md) opened about caching honesty: a response derived from a hypothetical that changes on every keystroke is the caching system's zero case, and saying so costs one header.

The routes slot in beside their write-path siblings -- `/recipes/new/preview` declared before `/recipes/:id/preview` for [the same static-before-dynamic reason as `/recipes/new` itself](05-web-server.md):

```clojure
["/recipes/new/preview"
 {:post #'handler/recipe-preview}]
["/recipes/:id/preview"
 {:post #'handler/recipe-preview}]
```

## The client: infrastructure this book already shipped

Here is the complete inventory of JavaScript written for this feature:

Nothing.

[The progressive-enhancement chapter](20-progressive-enhancement.md) built `server-preview.js` as a *pattern*: a registered island that watches a marker element, gathers named inputs on debounced keystrokes, POSTs them to an endpoint, and morphs the response into itself. It was built as declared behavior looking for a subscriber, the same way [the dispatcher's 4xx-morphs-in-place rule](21-forms-validation.md) sat waiting for the first form that failed. This chapter is the subscriber. The form page grows an `aside`:

```clojure
[:aside#recipe-preview.rounded-lg.border.border-border.bg-surface.p-6
 {:data-preview-action (if editing?
                         (str "/recipes/" (:recipe/id recipe) "/preview")
                         "/recipes/new/preview")
  :data-preview-from "#recipe-form input, #recipe-form textarea"}
 (recipe-preview-content locale recipe)]
```

and the machine starts. Two attributes are the entire client contract: *where to POST* and *which inputs to gather*. The island debounces at 300ms, aborts superseded requests by key, and morphs with `ignoreActiveValue` -- so the pane updating never disturbs the textarea you are mid-word in. The response fragment's root carries the same stable `recipe-preview` id as the pane, which is how [the dispatcher's fragment-picking](15-morph-dispatcher.md) knows what to swap: the server names the region, the client aligns it. No preview-specific JavaScript exists to maintain, because "POST some fields, morph the answer" was never specific to previews.

Note what the initial pane content does for the no-JS story. The `aside` is server-rendered *with content*: an edit page shows the stored recipe's body before a single keystroke -- and, with JavaScript absent entirely, continues to show it: a truthful, static rendering of what is saved, sitting beside the form that edits it. Layer 0 lacks the *live* in live preview; it gets a poorer version of the same page, not a broken one, which has been [the layering's promise](20-progressive-enhancement.md) since it was drawn.

## Proof

The unit tests state the two claims that matter -- the future renders, and the future *stays* future:

```clojure
(let [p (recipe/preview db u id
          {:title "Bolognese v2" :servings 6 :description "Now with **wine**."
           ,,,})]
  (is (= "Bolognese v2" (:recipe/title p)) "the preview carries the unsaved edit")
  ,,,
  (is
    (= "Bolognese" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id)))
    "…and the database never heard about it")
  (is
    (= 1 (count (recipe/version-history (d/db h/*conn*) id)))
    "no phantom version either"))
```

The phantom-version assertion is the one to keep. [The recipe domain's whole design](09-recipe-domain.md) reads version history *out of the transaction log*; the strongest way to say "preview writes nothing" is to ask the log. Ownership gets the same treatment as every other write path (`preview` for another user's recipe is `nil`), and the handler test drives markdown through the endpoint and finds `<strong>` in the response -- the real pipeline, not an imitation.

The e2e test types into a real browser and watches the pane:

```javascript
await page.fill('textarea[name="description"]', 'A **speculative** delight');
await expect(pane.locator('strong')).toHaveText('speculative');

// Leave without saving: the recipe is untouched.
await page.goto('/recipes');
await page.getByRole('link', { name: 'Preview Base' }).click();
await expect(page.getByText('speculative')).toHaveCount(0);
```

Type, see it rendered, walk away, confirm the world never changed. That is the feature's entire contract, in four lines a browser can check.

## Trade-offs & limitations, in one place

- **Every preview is a server render.** A keystroke (debounced) costs a round trip and a `d/with` + pull + Hiccup render. That is the trade against the client-side renderer, and it should be made with eyes open: the render is the same work as serving the page (cheap, and [measured properly two movements from now](32-server-path-measured.md)), the debounce bounds the rate, and the reply is a fragment. What you buy is a preview that cannot lie and a client that cannot drift. On a high-latency connection the pane lags the keyboard by the round trip; the *form* -- the thing being edited -- never does.
- **The preview waits for conformance.** Until title and servings conform, the pane shows its hint rather than a partial preview. We chose the crisp boundary over per-field leniency (previewing "everything except the broken field" means inventing values for the broken field -- the defaulting this book just finished deleting). The cost is real but small: the waiting state in practice appears for the first seconds of a new recipe and never on an edit.
- **A new-recipe preview coins a throwaway UUID and `:recipe/position 0` per request.** Both vanish with the db value; neither is observable. If that offends, note that the alternative -- threading "maybe there is no id yet" through the pull and views -- puts speculation-awareness into code whose entire virtue is not knowing it is speculating.
- **The speculative db cannot foresee contention.** `d/with` validates against *its* snapshot; the save can still lose a race it cannot. For recipes this is the owner racing themselves in two tabs -- accepted. For domains where it is not, the answer is [the real transaction's](08-datomic.md) `:db.fn/cas` and friends, at save time; the preview's job is fidelity, not prophecy.

## Where this leaves the argument

The positioning chapter conceded a list of features as the client's home turf, to be built as deliberate islands when wanted. This chapter took the first name on that list and found the island was *twelve lines of Hiccup attributes* -- because the expensive half, the rendering, never had to move. That is the pattern worth carrying out of the chapter, more than the feature: `d/with` means *any* server render in this application can be pointed at a hypothetical (what this recipe would look like rolled back to version 3, what the dashboard would show if this import ran) for the cost of building tx-data and losing nothing. The views don't know. They take a database value and tell the truth about it; we have merely widened what counts as a database value.

The next chapter widens the other end. The views render what the db value holds; [search](23-search.md) is about what the *user* can find in it -- with an index the schema has been quietly paying for since its first line.
