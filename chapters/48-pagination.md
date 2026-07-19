# Paging the Catalog: Keyset Pagination on an Ascending Index

There is a line in the browse handler that was fine for forty-seven chapters and is a bug in production:

```clojure
(recipe/all-recipes db)   ; every recipe, pulled and sorted, on every request
```

With a demo's worth of recipes it is instant. With a real catalog it is the same unbounded read the [resilience pass](46-watching-the-watchers.md) kept finding and bounding — the diff that grew with its input, the mailer queue that grew with the outage. A list that loads *everything* to show *twelve* gets slower every day it succeeds, and on one shared heap a big enough catalog is a self-inflicted outage. So the catalog needs pages. This chapter builds them. Pagination is standard; the interesting part is what doing it *well* on Datomic turns out to require.

## Keyset, not offset

The pagination most people reach for is `OFFSET`: page three is "skip 24, take 12." It has two problems, and they are the reasons keyset pagination is the modern default. First, `OFFSET n` makes the database *walk past* n rows to discard them, so deep pages get linearly slower — page 500 pays for 6,000 rows it throws away. Second, it is unstable: insert a recipe while a reader is on page two, and every later page shifts by one, duplicating a row across the boundary or skipping one.

**Keyset pagination** fixes both by paging on a *value*, not a *position*. The cursor is the last row you saw — here, the last recipe's title. The next page is "the rows just after that title," which the database answers by *seeking* the sorted index straight to the cursor and reading forward. No walking past discarded rows (so every page costs the same, O(page)), and inserts elsewhere in the catalog cannot shift a cursor that names a value rather than a count.

## The index the schema already carries — almost

Keyset pagination needs the sort column to be indexed, so the seek is a seek and not a scan. And [as with search](23-search.md), the instinct to reach for new infrastructure is met by an attribute that is *nearly* ready: `:recipe/title` has carried `:db/fulltext true` since [the schema's first version](08-datomic.md). But fulltext is a Lucene index — great for "recipes matching *carbonara*," useless for "recipes in title order," because Lucene answers relevance, not range. Range order lives in Datomic's own covering index, the AVET index, and an attribute joins it with one more flag:

```clojure
{:db/ident :recipe/title
 :db/valueType :db.type/string
 :db/fulltext true
 :db/index true              ; <- the AVET btree, distinct from the Lucene index
 :db/doc "Recipe title"}
```

Now a page is a seek into that index — `d/index-range` opens the `:recipe/title` index at the cursor value and hands back datoms in order, lazily, so taking one page's worth reads one page's worth:

```clojure
(->> (d/index-range db :recipe/title (:recipe/title after) nil)  ; seek to the cursor title
     (drop-while ,,,)                                            ; skip rows up to and incl. the cursor
     (take (inc limit)))                                         ; one page (+1 to know if more follow)
```

Only these datoms are realized, and only this page's entities are `pull`ed. The browse read no longer grows with the catalog; it grows with the *page size*, which is a constant. That is the whole game.

## Two things Datomic makes you get right

**The order is ascending, so the catalog is alphabetical.** Datomic's covering indexes are ascending-only; there is no descending index and no reverse seek. `index-range` reads low-to-high, and taking a page from a seek point is O(page) *only in that direction*. So the browse is A→Z by title, which is a fine way to browse a catalog you are scanning for a name. "Most recent first" (the other natural order) is *not* a cheap index primitive here (it would mean reading to the far end of the index), and it is already answered elsewhere: the [dashboard](21-forms-validation.md) and the [activity feed](26-activity.md) are where recency lives. Pick the sort key the index can serve, and let the feature that needs the other order own it. That is not a Datomic limitation so much as a Datomic *fact* that pagination has to respect; pretending otherwise is how you ship an O(n) "next page."

**The tie-breaker must be the index's own order, not yours.** Titles are not unique (two cooks can both write "Pesto"), so the cursor needs a tie-breaker to place a boundary *between* two equal titles. The obvious choice is the recipe's public `:recipe/id` (a UUID). It is also wrong, and wrong in the nastiest way: it works almost always and fails at random. The AVET index orders equal titles by *entity id*, the datom's own internal `e`, not by the recipe's UUID. A cursor that tie-breaks on the UUID disagrees with the index whenever a same-title recipe's UUID happens to sort before its predecessor's — and then `drop-while` skips it, dropping a recipe from the catalog entirely. A first draft did just this; its test passed in isolation and failed in the full suite, because the recipe UUIDs are random and the bug is a coin flip. The fix is to key on what the index actually orders by:

```clojure
(compare [(:v d) (:e d)] [(:recipe/title after) (:eid after)])   ; (title, eid) — the index's own order
```

The lesson generalizes past Datomic: a keyset cursor's ordering must be *byte-for-byte* the order the index yields, or the boundary between pages leaks. When the two can disagree, they eventually will, on a Tuesday, for one user, invisibly.

## The cursor is a token, not a URL

The page carries its cursor in the query string, and the temptation is to make it readable — `?title=Pesto&eid=17592186044443`. Don't. A cursor is an *implementation detail of how this page was reached*, not a promised, documented URL shape; the moment it looks like an API, someone builds against it, and you can never change the pagination scheme. So it is opaque: the `(title, eid)` keyset, base64url-encoded into one token:

```clojure
;; encode:  eid \t title  ->  base64url          decode: the reverse, and a
;; "?after=Mg9yZWNpcGU..."                        malformed token just starts at page one
```

Opaque also means *safe*: a garbled or hostile `?after=` value decodes to nil and serves the first page, never an error and never an eval — the decode is a plain string split, not a `read-string` (which would hand an attacker code execution through the URL). And it hides the internal entity id behind a token the client is not invited to interpret.

## It's just a link

The client story is the shortest in the chapter, because it is [the progressive-enhancement rule](20-progressive-enhancement.md) paying off again: the "Next" control is an ordinary anchor.

```clojure
[:a {:href (str "/recipes?after=" next-token)} (t locale :recipe/next-page) " →"]
```

Without JavaScript it is a full-page navigation to the next page — pagination works with the network and nothing else. With JavaScript, [the morph dispatcher](15-morph-dispatcher.md) already intercepts same-origin links and swaps the new page's content in place, animated by the View Transition, with no per-feature code. The server sends a link; the enhancement layer makes it feel like an app. There was never a paging *component* to build.

## Trade-offs & limitations, in one place

- **Forward-only.** Keyset pagination naturally offers "next"; a "previous" link needs a cursor that seeks the *other* way, which Datomic's ascending index does not give cheaply. Browsers have a Back button, and for an A→Z catalog forward paging plus [search](23-search.md) covers the real navigation; a true bidirectional pager is a named seam, not a shipped feature.
- **No page numbers, no total count.** Keyset pagination has no notion of "page 7 of 42," because it never counts — that is the point. A total count is a `count` over the whole catalog, the exact O(catalog) read this chapter exists to avoid. If you need "42 pages," you are asking for a number that costs what the old browse cost; price it deliberately.
- **A→Z, by design.** Covered above: recency is the dashboard's and the activity feed's job, because the index this page seeks is ascending. Not a gap — a division of labor dictated by what the index can serve in O(page).
- **The cursor pins to `eid`.** Stable and correct, but it means a cursor is only meaningful against the database that issued it; it is not a durable bookmark to a recipe (that is the `:recipe/id` URL). Opaque tokens exist to *resume a scan*; linking is the `:recipe/id` URL's job.

## The wager, once more

Pagination is the most standard requirement a web application has, and that is exactly why it is worth doing as carefully as anything exotic: the standard features are the ones every user touches. The catalog now reads a page instead of the world; the read is bounded by a constant, not the catalog's growth; the cursor is a seek into an index the schema all but already carried; and the one genuinely subtle part, that a keyset must order the way its index orders, was caught by a test before it could drop a stranger's recipe from the list on a random afternoon. Standard, done right, on the stack you already run.
