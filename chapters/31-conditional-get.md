# The Database Already Knows: Conditional GET from basis-t

[The asset-pipeline chapter](29-asset-pipeline.md) sorted this application's responses into caching tiers, and each tier got the strongest honest claim available. Hashed assets: immutable forever, because the URL changes when the bytes do. Authenticated pages: `no-store`, because [a browser cache is the wrong place for someone's signed-in session](29-asset-pipeline.md). Point-in-time and diff pages: immutable by construction, because they are addressed by basis-t and the past does not change.

One tier was left saying nothing: the live anonymous pages. The browse list, a recipe, [the search results](23-search.md) -- public, hot, and served with no cache story at all, which in HTTP means the browser re-downloads them every visit. The reason they were left out is the reason most dynamic pages everywhere are left out: they *change*. Not on a schedule, not per-URL-version -- they change when the data changes.

Which is, on this stack, a sentence with an unusual property: **the server can evaluate it in constant time.**

## What every other stack has to do

HTTP's tool for "it changes, but rarely" is the validator: the server labels a response (`ETag`), the browser presents the label next time (`If-None-Match`), and a match earns `304 Not Modified` -- no body, no re-download. The catch has always been *computing* the label. A strong ETag is a hash of the bytes, which means doing the whole render and then discovering it wasn't needed -- saving bandwidth, but none of the work. The usual dodge is `max-age`: don't ask for sixty seconds. That saves everything and costs honesty; every `max-age` on dynamic content is a decision about how stale you are willing to be seen.

Now inventory what one of our anonymous pages actually depends on. The database -- [a single immutable value](08-datomic.md), whose identity is its basis-t, one number the peer holds in memory. The locale, from the request. The rendering code, fixed per deploy. That is the complete list; [the views chapter](14-hiccup-views.md) made pages pure functions and nineteen chapters have kept them that way. So the validator for *any* anonymous page is:

```
(basis-t, locale, build)
```

-- knowable **before rendering anything**. Not a hash of the output; a name for the inputs. The database already knows whether the page changed, because the page can only change if *it* did.

## The middleware

```clojure
(defn wrap-conditional-get
  "Conditional GET for anonymous HTML pages, validated by basis-t.
  Request side: a matching If-None-Match answers 304 before the handler
  runs — no query, no render. Response side: 200 text/html responses that
  carry no Cache-Control of their own get the validator plus no-cache
  (always revalidate; revalidation is the cheap part). ,,,"
  [handler]
  (fn [request]
    (if-not (and (= :get (:request-method request))
                 (nil? (get-in request [:session :user-email])))
      (handler request)
      (let [validator (current-validator (:locale request))]
        (if (= validator (get-in request [:headers "if-none-match"]))
          {:status 304
           :headers {"ETag" validator
                     "Cache-Control" "no-cache"
                     "Vary" "Accept-Language"}}
          (let [response (handler request)]
            (if (and (= 200 (:status response))
                     (some-> (get-in response [:headers "Content-Type"])
                             (str/starts-with? "text/html"))
                     (nil? (get-in response [:headers "Cache-Control"])))
              (update response :headers merge {"ETag" validator ,,,})
              response)))))))
```

The request side is where the stack's advantage lives. On a match, the 304 is returned *before the handler runs*: no Datomic query, no pull, no Hiccup, no string. Most conditional-GET implementations save the transfer; this one saves the render, because the validator never needed the render. `current-validator` is three lookups:

```clojure
(str "W/\"" (d/basis-t (d/db (db/get-connection))) "-" (name locale) "-" boot-token "\"")
```

`d/basis-t` on a db value is reading a field -- [the peer already holds the value](08-datomic.md); there is no round trip in this middleware, ever.

Each piece of the header story is a small decision made out loud:

- **Weak (`W/`), on purpose.** A weak ETag promises semantic equivalence, not byte equality -- exactly our claim. Two renders at one basis-t are the same *page*; we have not promised the same *bytes*, and weak validators are what HTTP provides for saying precisely that.
- **`Cache-Control: no-cache`, not `max-age`.** `no-cache` means *store it, but ask every time*. Asking is a conditional request; answering is constant-time. When revalidation costs nearly nothing, staleness windows buy nearly nothing -- so we set the policy that is never wrong instead of the one that is usually fine. Freshness lifetimes stay where bytes genuinely cannot change: the hashed-asset tier.
- **`Vary: Accept-Language`**, because [the locale is part of the page's identity](12-i18n.md), and it is also *in the validator* -- a Dutch 304 must never be granted against an English copy.
- **The `boot-token`** folds "the rendering code" into the validator: a UUID minted per process, so every deploy or restart invalidates the world. Over-invalidation, never staleness. (It is a UUID and not a timestamp for a reason the repo's own lint enforced: [the clock in this application is swappable](07-time-clock.md) and must stay free to lie in tests -- a cache token is an *identity*, not a time, and reaching for `System/currentTimeMillis` here got the commit rejected by the time-as-global rule. The linter was right.)

The scope rules are the other half of the design. Authenticated requests pass through untouched -- [`wrap-no-cache-authenticated`](29-asset-pipeline.md) owns them, and a session-shaped page must never earn a shared validator. Responses that already state a policy keep it: the immutable point-in-time pages are *stronger* than revalidation and must not be demoted. Non-HTML is untouched, because assets live in the hashed-URL tier. Everything about the middleware is "add the honest default where nothing better was claimed."

## What it deliberately does not know

**The validator is whole-database.** Any transaction anywhere -- one user renames one recipe -- advances basis-t and invalidates every anonymous page for every browser. That is the trade this chapter makes with open eyes, and it is worth stating why the naive-looking choice is the engineered one. The alternative is dependency tracking: knowing *which* pages read *which* entities, which is a cache-key design problem that grows without bound and fails toward the one unforgivable direction -- serving stale content as fresh. Whole-db granularity fails only toward extra revalidations, each costing a constant-time 304 check. On a catalog where writes are humans editing recipes, basis-t advances a few times a minute at the very busiest; between any two writes, every returning anonymous visitor rides 304s. The win is not "we never re-render"; it is "we re-render at the rate the world changes instead of the rate it is looked at."

The race is worth naming too: the validator is computed before the handler, and a transaction can land mid-request, so a response can carry an ETag one tick older than the db that rendered it. Walk the consequence: the browser caches fresh-body-under-old-tag; its next conditional request presents the old tag; basis-t has moved, so the answer is a fresh 200, not a wrong 304. The design cannot serve stale -- it can only revalidate once more than strictly needed, which is the failure direction everything in this chapter is aimed at.

> **Trade-off -- browser caches, not shared ones.** These headers are written for the browser's cache. A CDN or shared proxy *could* honor them (`no-cache` + ETag revalidation works for shared caches), but doing that safely means auditing every anonymous page for accidental personalization and adding `public` deliberately -- an operations-shaped decision about infrastructure this book doesn't run, and exactly the kind of concern [the operations volume](34-ci-cd.md) exists for. One browser, one user, one cache is the claim we can make from here and defend completely.

One dev-mode honesty note: the live-reload loop hot-swaps view code without restarting the JVM, so in development the boot-token can survive a render-code change and a 304 can serve a page older than your edit. Development already disables caching in the places it bites (DevTools open, [dev asset no-store](29-asset-pipeline.md)); production deploys restart the process and mint the token. The gap exists only where the dev loop is already king.

## Proof

The test file reads as the chapter in miniature -- and note the third test's shape, because it is the whole feature in one motion:

```clojure
(deftest a-transaction-invalidates-every-page
  (let [before (get-in (routes/app (get-request "/recipes")) [:headers "ETag"])
        u (mk-user! "etag@x.lan")]
    (recipe/create! h/*conn* u {:title "Cache Buster" :servings 1})
    (let [resp (routes/app (get-request "/recipes" {"if-none-match" before}))]
      (is (= 200 (:status resp)) "the old validator no longer matches")
      (is (not= before (get-in resp [:headers "ETag"])))
      (is (str/includes? (:body resp) "Cache Buster") "…because the world changed"))))
```

Present the old label, get the new world. Its sibling asserts the other direction -- unchanged database, `If-None-Match`, 304 with no body -- and the scope tests pin the guardrails: signed-in requests never enter the path; the immutable pages keep their stronger claim; CSS passes untouched.

## Trade-offs & limitations, in one place

- **Whole-db invalidation** -- argued above; the honest granularity until measurement, not intuition, demands finer.
- **Anonymous traffic only.** Signed-in users -- the dashboard, the editor -- get nothing from this chapter, by design: their pages are `no-store` for [bfcache-after-logout reasons](29-asset-pipeline.md) that outrank bandwidth. The beneficiaries are exactly the visitors [the SEO chapter](30-machine-legibility.md) is courting: anonymous readers, crawlers, and link-unfurlers re-fetching public pages.
- **304s still traverse the middleware above this one.** Sessions are decoded, locale negotiated. The saved work is the expensive tail (queries, pulls, rendering), not the whole request. [The measurement chapter](32-server-path-measured.md) is where "expensive" stops being an adjective.
- **The validator says nothing about *what* changed** -- and consumes nothing that says so. A page-dependency map could sharpen it someday; the place such a map would come from is [the construction view's](17-construction-view.md) recording of which entities a render actually read. That is a research afternoon, deliberately not taken.

## The third dividend

This is now the third feature in the book paid for by the same early decision. Immutability gave [the domain its version history](09-recipe-domain.md) for free; the log gave [the activity feed its events](26-activity.md) for free; and here, the fact that a database is a *value with a name* gives HTTP caching its validator for free -- one that most dynamic websites structurally cannot have, because "did anything change?" is, for them, a question answered by doing the work and comparing. Ours answers it by reading a number. The next chapter puts numbers on the work itself: what a render of this application actually costs, measured instead of assumed.
