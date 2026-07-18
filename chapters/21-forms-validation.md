# Forms That Hold: Server-Side Validation and the Error Re-Render

The positioning chapter made a promise it has been quietly sitting on for nineteen chapters: *"validation, authorization, and rendering all live in one place."* [Authorization got its chapters](20-progressive-enhancement.md). Rendering got a movement. This chapter pays the third debt -- and it starts with a confession, because until this chapter the promise was not merely unpaid; the application was in mild breach of it.

Here is what the write path did before today. The recipe form's handler extracted params like this:

```clojure
{:title (str/trim (or (get-in request [:params :title]) ""))
 ,,,
 :servings (or (parse-long (str (get-in request [:params :servings]))) 1)}
```

and the domain's `create!` finished the job:

```clojure
:recipe/title (or title "Untitled recipe")
:recipe/servings (long (or servings 1))
```

Read those `or`s as what they are: the server never says no. Submit a blank title and the application *invents one*. Submit `servings=banana` and it quietly becomes `1`. No error, no 4xx, no signal to the user that what they meant is not what was stored -- and stored is the operative word, because on Datomic that coined `"Untitled recipe"` is not a value you overwrite later; it is a fact in the log, forever, [in every version history the domain chapter taught us to read](09-recipe-domain.md). The only validation the user ever met was the HTML `required` attribute -- a client-side courtesy the server never backed. One `curl` and the courtesy evaporates.

This is the least glamorous page in any application and the most load-bearing: every SaaS is, at the bottom, forms. So this chapter builds the write path properly -- validation as data in the domain, one refusal boundary, and the error experience rendered the way everything else in this book is rendered: by the server. Along the way it collects a payoff that has been waiting since [the morph dispatcher](15-morph-dispatcher.md): the SPA's signature inline-error form -- errors appearing under fields, typed input intact, focus moving to the problem -- turns out to be *just another server render*.

## Errors are data, and the domain owns them

The first decision is where the rules live. Not in the handler (the handler translates HTTP; it has no opinion about what a recipe *is*), and not in the view (the view renders outcomes; letting it decide them is how rules drift apart per page). The rules live in the domain namespace that owns recipes, as a pure function from raw input to a verdict:

```clojure
(def ^:private limits
  "Field size ceilings for `conform`. The content ceilings are deliberately
  generous — the goal is refusing the absurd (a pasted novel, an overflowing
  integer), not policing prose."
  {:title 200
   :description 20000
   :ingredients 20000
   :steps 20000})

(defn conform
  "Validate and coerce raw recipe form fields (strings, straight off the
  wire) into the content values `create!`/`update!` accept.

  Returns `{:values {…}}` when everything conforms, else
  `{:errors {field [code …]}}` — e.g. `{:title [:blank]}`. Error codes are
  data: the domain decides *what* is wrong, and rendering translates codes
  into words (i18n) at the boundary — so the rules live in exactly one
  place for every caller, the form handler today, an API tomorrow."
  [{:keys [title description servings ingredients steps]}]
  (let [title (str/trim (or title ""))
        description (or description "")
        ingredients (or ingredients "")
        steps (or steps "")
        servings-n (parse-long (str/trim (str (or servings ""))))
        errors (cond-> {}
                 (str/blank? title) (assoc :title [:blank])
                 (> (count title) (:title limits)) (assoc :title [:too-long])
                 (nil? servings-n) (assoc :servings [:not-a-number])
                 (and servings-n (not (<= 1 servings-n 100)))
                 (assoc :servings [:out-of-range])
                 ,,,)]                       ; the three text ceilings, same shape
    (if (seq errors)
      {:errors errors}
      {:values {:title title
                :description description
                :servings servings-n
                :ingredients ingredients
                :steps steps}})))
```

Three properties of this function do the chapter's work.

**It parses and validates in one motion.** The input is what the wire actually carries -- strings, or nil -- and the output on success is what the domain actually wants: a trimmed title, a `long` for servings. There is no separate "coercion layer" that runs before validation and has opinions of its own; the old bug lived precisely in that gap, where `parse-long`'s nil met an `(or … 1)` that papered over it. `conform` either produces the typed values or tells you why it cannot. (The functional-programming literature calls this *parse, don't validate*; the name matters less than the property: nothing downstream of `conform` ever re-checks.)

**Its verdicts are data, not prose.** `{:servings [:out-of-range]}` names the field and the problem in vocabulary the domain owns. What an out-of-range servings *says to a human* -- "Servings must be between 1 and 100", "Porties moet tussen 1 en 100 liggen" -- is a rendering concern, and [the i18n chapter](12-i18n.md) already built the machinery for exactly this: the view will look up `:error/servings-out-of-range` in the locale's dictionary. The domain stays language-free, and a future caller that is not a form -- an API handler returning JSON -- gets the same codes and chooses its own words.

**It returns errors per field, as vectors.** The map is keyed by field because that is how the form will render it -- each error under its input. The values are vectors because a field can fail several rules, though the UI will choose to show one at a time: one problem per field reads as guidance; a stack reads as a scolding.

> **Trade-off -- hand-rolled vs. malli vs. spec.** This is the decision the chapter cannot dodge, because the ecosystem has real answers. [malli](https://github.com/metosin/malli) gives you schemas as data, coercion, and human-error rendering; `clojure.spec` gives you generative testing hooks and `s/conform`. Both earn their keep -- *at a certain scale*. What they cost here: a dependency in the trust boundary of every write, a second vocabulary for expressing rules the domain already speaks natively, and (for the error path) a translation layer from their error shapes into our field-keyed, i18n-coded one -- which is to say, most of the code above would survive, with a library underneath it. Our entire validation surface today is five fields and seven rules; `conform` is thirty lines with no closures over anything. The line we draw: **when the third substantive form arrives** -- when rules start repeating across domains, or an API needs schemas as documentation -- revisit malli, and the revisit is cheap precisely because every caller already speaks `{:errors {field [code]}}`; swapping the *producer* of that shape touches one function. Until then, the smallest thing that is honestly the whole thing.

## Refuse, don't repair

`conform` existing does not by itself stop a careless caller from skipping it. So the mutation functions grow a spine:

```clojure
(defn- assert-content!
  "Defense in depth: refuse content `conform` would reject instead of
  repairing it. The old behavior — coining \"Untitled recipe\" for a blank
  title — put a lie in the database and kept it forever; a throw here means
  a handler that skipped `conform` fails loudly in development, not quietly
  in the data."
  [{:keys [title servings]}]
  (when (or (str/blank? title) (not (int? servings)) (not (<= 1 servings 100)))
    (throw
      (ex-info
        "unconformed recipe content — call conform first"
        {:title title
         :servings servings}))))
```

`create!` calls it and drops every repairing `or`; `update!` gets a sibling (`assert-changes!`) that holds *the keys present* to the same rules, because `update!` legitimately takes subsets -- [the seed data](09-recipe-domain.md) edits one field at a time to build version history. The guard is deliberately a *subset* of `conform` (the invariants whose violation would poison data), not a re-run of it: `conform` speaks wire-strings, the mutations speak typed values, and duplicating the ceilings here would just be a second place for them to rot.

The choice to throw -- rather than return an error value like everything else in this book's error handling -- is worth a sentence, because it looks like an inconsistency and is actually the point. `conform` returning errors-as-values is the *expected* path: user input is wrong all day long, and wrongness flows to the UI as data. A mutation receiving unconformed content is a *programmer* error: some future handler skipped the boundary. That is not a state to render; it is a bug to surface, in development, with a stack trace -- the same reasoning that made [strict compilation](04-build-hardening.md) turn reflection into a build failure rather than a log line. The two error styles are not competing conventions; they are aimed at different audiences.

There is one deletion hiding in this section that deserves its own beat: **`"Untitled recipe"` is gone.** Not renamed, not translated -- gone. A default written by the server is a statement the user never made, and in an immutable database it is a statement they can never unmake. If product design someday wants draft recipes with placeholder titles, that is a *visibility* feature with its own chapter's worth of honest work; it is not something a validation layer should back into by being polite.

## The handler: one boundary, two exits

With the domain holding the rules, the handler collapses into a shape you can read at a glance -- parse, conform, branch:

```clojure
(defn- recipe-params
  "The recipe form fields exactly as submitted — raw strings, no trimming,
  no defaults. Coercion and validation are `recipe/conform`'s job; keeping
  the raw map intact is what lets an invalid submission re-render the form
  with precisely what the user typed."
  [request]
  {:title (get-in request [:params :title])
   ,,,})

(defn recipe-create
  "POST /recipes/new — create a recipe owned by the current user.
  Invalid input re-renders the form at 422; only conformed values reach the
  domain."
  [request]
  (let [raw (recipe-params request)
        {:keys [values errors]} (recipe/conform raw)]
    (if errors
      (invalid-form request nil raw errors)
      (let [id (recipe/create! (db/get-connection) (:user-eid request) values)]
        (response/redirect (str "/recipes/" id))))))
```

Notice what `recipe-params` *stopped* doing. The old version trimmed and defaulted at extraction, which felt tidy and was exactly wrong: the moment you normalize input, you have destroyed the evidence. When the re-render below needs to show the user their own typing -- the title that was all spaces, the `banana` in the servings box -- the raw map is the only honest source. Extraction extracts; judgment happens once, at `conform`.

The failure exit:

```clojure
(defn- invalid-form
  "422 re-render of the recipe form: field errors + the submitted values.
  The dispatcher morphs this over the live form (typed input and focus
  survive — dispatcher.js's 4xx-with-HTML rule); without JavaScript it is
  the same page as a full response. The 422 keeps the contract honest for
  machines: this POST changed nothing."
  [request recipe raw errors]
  (-> (html
        (views/recipe-form (:locale request) (:user-email request)
          (:admin? request) recipe
          {:errors errors :submitted raw}))
      (assoc :status 422)))
```

Two decisions live in that little function. First, **the response body is the same view that rendered the form** -- not an error page, not a flash-message redirect. The Post/Redirect/Get pattern the app uses everywhere else exists to make *success* refresh-safe; redirecting on failure would mean smuggling the errors and half a form's worth of typed input through a session flash, to rebuild on the other side of a GET a page we were already holding. When the answer is "same page, more information," the answer is a render.

Second, **the status is 422**, not 200-with-red-text. Browsers do not care -- they render the body either way -- but the contract does. A 200 says "this POST did what POSTs do"; it did not. Monitoring that counts error rates, an [e2e test](27-e2e-testing.md) asserting the unhappy path, the dispatcher deciding how to treat the response, a future API client: all of them read the status line before the body, and `422 Unprocessable Content` is the precise term of art for "well-formed request, unacceptable content." The cheapest place to be honest with machines is the status code.

`recipe-update` gets the same two exits, with one wrinkle: its error re-render passes the *stored* recipe alongside the submitted values, so the page's heading and the form's `action` URL come from truth while every field comes from the attempt. And its ownership check runs *before* the invalid re-render -- a non-owner probing another user's edit URL gets the same `404` whether their probe was well-formed or not, because [the isolation layer's rule](20-progressive-enhancement.md) is that authorization questions are answered first and identically.

## The form renders its own failure

The view's job barely grows. `recipe-form` gains an optional trailing map -- `{:errors …, :submitted …}` -- and two small helpers:

```clojure
(defn- field-error
  "Inline error line under a form field, or nil.
  `codes` is the vector of error-code keywords `recipe/conform` produced for
  this field; we show the first — one problem at a time reads better than a
  stack. The element id is what the input's `aria-describedby` points at."
  [locale field codes]
  (when-let [code (first codes)]
    [:p.mt-1.text-sm.text-negative
     {:id (str (name field) "-error")}
     (t locale (keyword "error" (str (name field) "-" (name code))))]))

(defn- field-aria
  "Accessibility attributes for a field in error.
  Screen readers announce the state and read the error line as the field's
  description."
  [field codes]
  (when (seq codes)
    {:aria-invalid "true"
     :aria-describedby (str (name field) "-error")}))
```

and each field follows one pattern -- attributes merged, error line beneath:

```clojure
[:input#title.mt-1.block.w-full ,,,
 (merge
   {:type "text"
    :name "title"
    :required true
    :value (fv :title :recipe/title)}
   (field-aria :title (:title errors)))]
(field-error locale :title (:title errors))
```

`fv` is the value-precedence rule as a two-line function: *if there was a submission, show the submission; otherwise show the stored recipe.* After a 422 the form must reflect the attempt -- mistakes and all -- because the user's next move is to *fix* their input, not to re-type it. Note that this works only because `field-error` looks up `(keyword "error" "title-blank")` in the same dictionaries as every other string on the page: the Dutch user who submits a blank title reads *"Geef het recept een titel."* under the field, and nobody wrote an if-statement about locales to make that happen.

The `aria-*` pair is not decoration, and it is about to earn its keep in the JavaScript section. `aria-invalid` marks the field as failed *in the accessibility tree*, not just in red; `aria-describedby` links it to the error line so that a screen reader, on focusing the field, reads the label, the state, and the reason as one utterance. Server-rendered error UX has a reputation for being the accessible-but-clunky option. Half of that reputation is about to fall; the other half never had to be true.

## The payoff: the dispatcher already knew

Here is the part of the chapter where nineteen chapters of architecture stop being setup. [The morph dispatcher](15-morph-dispatcher.md) -- built long before any form could fail -- contains this, verbatim:

```javascript
// 4xx/5xx with HTML body — morph in place so server-rendered errors
// appear. The server is the source of truth; client doesn't interpret.
```

The dispatcher intercepts the form's submit, POSTs via `fetch`, and when the response comes back `422` with an HTML body, it does *exactly what it does with every HTML body*: parses it, picks the fragment, and morphs it over the live page. It does not inspect the status and route to some error-handling branch; there is no error-handling branch. The server rendered what the page should now look like; the client's whole job is to make the page look like that, gently.

And "gently" is where the SPA-parity thesis of the tooling chapters cashes out on the humblest page in the app. Because the update is [an idiomorph morph](19-morph-reload.md), not a navigation:

- **the typed input survives** -- partly because the server echoed it into `value` attributes (the no-JS story depends on that), and partly because the morph matches the existing DOM nodes rather than replacing them, so even in-flight state the server does not know about (an IME composition, a scroll position, text selection) is left alone;
- **the page's JavaScript world survives** -- no re-parse, no re-execution, no flash;
- **the URL does not move** -- there is nothing to redirect to; you are still, truthfully, at `/recipes/new`, mid-attempt.

One question the infrastructure had not answered: **who owns focus after an error?** Doing nothing leaves focus on the submit button -- technically preserved, practically useless, and for a screen-reader user, silent: the page changed and nothing announced it. The right answer is a rule old enough to be in accessibility guidelines: move focus to the first failed control. The dispatcher grows one more small piece of policy:

```javascript
// A non-GET morph that surfaced server-side field errors: move focus to
// the first invalid control. Its aria-describedby points at the error
// line, so screen readers announce the problem, and keyboard users land
// exactly where the fix starts. GETs are exempt — stealing focus on
// ordinary navigation would be worse than the disease.
if (method !== 'GET') {
  const firstInvalid = targetEl.querySelector('[aria-invalid="true"]');
  if (firstInvalid) firstInvalid.focus();
}
```

Read the wiring loop this closes: the *domain* said `{:title [:blank]}`; the *view* turned that into `aria-invalid` and an `aria-describedby` pointing at a translated sentence; the *dispatcher* -- knowing nothing about recipes, titles, or Dutch -- finds the marked control and focuses it, at which point the browser's own accessibility machinery reads the field's label, its invalid state, and the error sentence aloud. Three layers, each speaking only its own vocabulary, composing into behavior none of them owns alone. The GET exemption matters as much as the rule: a search results page can legitimately contain `aria-invalid` markup, and yanking focus on every navigation would convert an affordance into an assault.

## The layers, drawn honestly

The HTML attributes are still on the form -- `required` on the title, `min="1"` on servings -- and it is worth being precise about what they are now that the server has opinions, because "client-side validation vs. server-side validation" is a false rivalry the web has argued about for twenty years.

The attributes are **layer 0 of [the progressive-enhancement stack](20-progressive-enhancement.md): a courtesy**. They catch the common case at zero latency -- an empty title never leaves the browser -- and they cost one attribute each. They are also, by design and by spec, a sieve. A title of three spaces satisfies `required` (the value is non-empty). A 300-character title sails through (we chose not to add `maxlength`; silently *truncating* typing is its own small dishonesty). Anything at all satisfies a client that is `curl`. The server's `conform` is **the contract**: the only layer that is always present, and therefore the only one allowed to be load-bearing.

That whitespace-only title is not a corner case to apologize for; it is the *demonstration*. It is precisely the input that walks through the courtesy layer and meets the contract, and it is how the e2e test drives the whole loop through a real browser:

```javascript
// A whitespace-only title slips through the HTML `required` courtesy layer;
// the server is the validator of record.
await page.fill('input[name="title"]', '   ');
,,,
// Plant a marker a real navigation would wipe — the morph must not.
await page.evaluate(() => { window.__stillHere = true; });
await page.getByRole('button', { name: 'Create recipe' }).click();

await expect(page.getByText('Give the recipe a title.')).toBeVisible();
await expect(page).toHaveURL(/\/recipes\/new$/);
await expect(page.locator('textarea[name="ingredients"]')).toHaveValue('typed and kept');
// In place, not a navigation: the page's JS world survived…
expect(await page.evaluate(() => window.__stillHere)).toBe(true);
// …and focus moved to the first invalid field, whose aria-describedby
// announces the error.
await expect(page.locator('input[name="title"]')).toBeFocused();
```

The `window.__stillHere` marker is the test's sharpest assertion, small as it looks. Every other observable -- the error text, the preserved value -- would also be true of a full page load of the 422 body, because the server renders the preserved values into the HTML itself. What a navigation *cannot* fake is the survival of the page's JavaScript heap. One boolean distinguishes "the SPA-grade path worked" from "the fallback worked;" both are correct pages, but the test pins which one happened. (And the fallback is not hypothetical: turn JavaScript off and the same POST returns the same 422 page as a full document -- slower, plainer, and exactly as informative. Layer 0 works because the server never learned there were other layers.)

The unit tests read like the specification the chapter has been arguing for, which is the point of errors-as-data -- the sad paths are just values to assert on:

```clojure
(is
  (= {:title [:blank] :servings [:not-a-number]}
    (:errors (recipe/conform {:title "   " :servings "many"})))
  "codes are data, keyed by field — no prose in the domain")
,,,
(is
  (thrown? clojure.lang.ExceptionInfo
    (recipe/create! h/*conn* u {:title "   " :servings 2}))
  "create! throws instead of coining a title — no more \"Untitled recipe\"")
```

## Trade-offs & limitations, in one place

- **One error per field, first-wins.** `conform` can carry multiple codes per field; the UI shows the head of the vector. For five fields this is guidance; for a forty-field enterprise form you would want error summaries at the top (with anchor links) *in addition* -- the data shape already supports it, the view just does not render it.
- **No cross-field rules yet.** Every current rule inspects one field. The day a rule spans fields ("a fork must change at least one field"), it goes in `conform` -- the errors map gains a key like `:form` -- not in the handler. The boundary stays singular; that is the promise being kept.
- **The ceilings are policy, stated in one place.** 200 and 20,000 are judgment calls, and the honest defense of a judgment call is legibility, not correctness: `limits` is a namespaced map you will change, not archaeology you will fear. (Transport-level request-size limits -- http-kit's `:max-body` -- are the web server's separate job, and one this build has not yet tightened; `conform`'s ceilings bound what we *store*, not what we *read*.)
- **The auth flow deliberately breaks this chapter's rules.** [The magic-link form](25-auth-email-flow.md) validates its email and then tells the user *nothing specific* -- same confirmation page for known addresses, unknown addresses, and rate-limited requests. That is not an inconsistency; it is the same boundary applying a different policy, because there the "user" being informed might be an attacker enumerating accounts. Field-level candor is for authenticated users editing their own data. When the reader meets a form that seems to violate this chapter, the first question is *who is allowed to learn from this error?*
- **`update!` trusts its callers slightly more than `create!` does.** The subset guard checks the keys present; a caller passing a partial map is asserting the rest is unchanged truth. That is the correct contract for the seed and the handler both -- and it means a *new* caller of `update!` must conform first, which the guard will teach them the first time they forget.

## Where this leaves the write path

`conform` is now the only door. The form handler walks through it and re-renders refusals; the domain functions bolt it shut behind them; the view knows how to paint a refusal under the exact field that earned it, in the user's language, announced to their screen reader; and the dispatcher makes the correction loop feel like the application never went anywhere -- because it didn't. The positioning chapter's sentence -- validation, authorization, and rendering, one place -- is no longer a claim about architecture diagrams. It is a `grep`: every rule about what a recipe may be lives in `myapp.recipe.core`, between `limits` and `assert-changes!`.

And one more door just quietly opened. `conform` produces, from any form submission, the exact typed values the domain would store -- *without storing them*. An input that can be judged without being committed is an input that can be **rendered** without being committed. The next chapter takes that sentence literally: the same view functions that render a saved recipe, fed a database value that contains your unsaved edits, make live preview -- the SPA's home-turf feature -- into one more server render. The database that doesn't exist yet is a `d/with` away.
