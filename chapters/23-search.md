# Search: The Index the Schema Already Carries

There is a line in [the recipe schema](09-recipe-domain.md) that has been quietly costing money since the day it was transacted:

```clojure
{:db/ident :recipe/title
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/fulltext true
 ,,,}
```

`:db/fulltext true` tells Datomic to maintain a Lucene index over every asserted value of the attribute. The transactor has been doing it faithfully: every title of every recipe and every version of every title, analyzed, tokenized, and indexed -- on each of the transactions this book has made since the Datomic chapter. And in nineteen chapters, no query has ever read it. The application has been paying for search since before it had users, and offering a browse page and a scrollbar instead.

This chapter collects. It is the shortest feature chapter in the book, because the hard parts -- the index, the layered form handling, the card rendering -- were all built by someone else: the schema, [the progressive-enhancement stack](20-progressive-enhancement.md), and [the browse page](14-hiccup-views.md), respectively. That brevity is itself the argument, third in a row: [chapter 21](21-forms-validation.md) cashed a rule the dispatcher had carried since its birth, [chapter 22](22-live-preview.md) subscribed to an island that was waiting for a subscriber, and now search is mostly a *withdrawal* from an index that never stopped accruing.

## A search is a GET

Before the query function, the architectural decision -- small, and easy to get wrong by copying the wrong neighborhood. In the SPA tradition, search is an *interaction*: a controlled input, a state atom, a fetch on keystroke, results materializing in a portal. None of that survives a refresh, lands in a bookmark, or travels in a chat message.

Here, search is a **page**:

```
GET /search?q=carbonara
```

The query lives in the URL, so every property the [positioning chapter](02-positioning.md) called "addressable by construction" applies to search results the moment the route exists: shareable, bookmarkable, back-button-able, prerenderable -- and, [one movement from now](31-conditional-get.md), cacheable by validator like any other pure read. The form is layer 0 in its entirety:

```clojure
(defn- search-form
  "The search box — a plain GET form, so layer 0 needs nothing else.
  Submitting navigates to /search?q=…; the dispatcher upgrades it to a
  morph like any other GET."
  [locale q]
  [:form.flex.gap-2.mb-6
   {:method "GET"
    :action "/search"
    :role "search"}
   [:label.sr-only {:for "q"} (t locale :search/label)]
   [:input#q ,,,
    {:type "search"
     :name "q"
     :value q
     :placeholder (t locale :search/placeholder)}]
   ,,,])
```

There is no search JavaScript in this chapter -- not even borrowed. A GET form serializes its fields into the URL natively; [the dispatcher](15-morph-dispatcher.md) already intercepts GET-form submits and morphs the result in like any navigation. The `role="search"` landmark and the visually-hidden label are the accessibility contract: screen-reader users get a named search region, everyone else gets the placeholder.

The same form sits on the browse page (where searching begins) and on the results page (where refining does). It renders with the current `q` on the results page for the oldest UX reason there is: the query you are iterating on should be in front of you, editable, not vanished into the page you came from.

## The query: fulltext is a datalog function

Datomic exposes the Lucene index inside datalog, as a built-in function that binds a relation:

```clojure
(defn search
  "Recipes whose title matches `q`, best match first.
  Runs on the fulltext index `:recipe/title` has carried since the
  schema's first version; returns pulled maps (the same shape the browse
  list renders), at most `search-limit`. Blank or operator-only input
  returns []."
  [db q]
  (let [q (fulltext-escape q)]
    (if (str/blank? q)
      []
      (->> (d/q '[:find ?e ?score
                  :in $ ?q
                  :where [(fulltext $ :recipe/title ?q) [[?e _ _ ?score]]]]
             db
             q)
           (sort-by second #(compare %2 %1))
           (take search-limit)
           (mapv (fn [[e _]] (db/pull* db pull-pattern e)))))))
```

The shape deserves a careful read, because `fulltext` is unlike the datalog clauses [the Datomic chapter](08-datomic.md) taught. It is not a pattern over datoms; it is a function call whose result destructures into a relation -- `[[?e ?value ?tx ?score]]` -- one row per matching *assertion*. We bind the entity and the relevance score, ignore the matched value and transaction, rank by score, and then do what every read in this application does: pull through `pull-pattern`, producing the maps `recipe-card` renders. The browse page and the results page differ by one `:where` clause and an ordering; everything downstream is shared, which is why the results page cost eleven lines of view.

Two narrowings, both worth defending out loud:

**Title-only, for now.** Fulltext indexes are per-attribute, and the schema indexes only `:recipe/title`. Should ingredients be searchable? Plausibly -- "what can I make with leeks" is a real question -- and the extension is mechanical: `:db/fulltext true` on the attribute, an `or-join` across two `fulltext` clauses, done. We stop at titles because that is where recipe *identity* lives (the thing you half-remember and want back), while ingredient search is a different product feature (discovery) wearing the same clothes, and [chapter 9's](09-recipe-domain.md) newline-separated ingredient text would index as one blob per recipe (workable, but worth designing on purpose rather than inheriting from a search box). The seam is labeled; the cost of walking through it later is one schema assertion and one clause.

**Current titles only.** The query runs against `db`, not `(d/history db)` -- you find recipes by what they are called *now*, not by every name they have ever had. The index actually contains the history (each assertion was indexed); the db value filters it. That default is right for a search box and easy to revisit if "find the recipe formerly known as…" ever becomes a requirement, which is a sentence that has never been said.

## The part where user input meets Lucene

`q` goes into a Lucene `QueryParser`, and Lucene has a query *language*: `*` wildcards, `~` fuzziness, `AND`/`OR`/`NOT`, field prefixes, brackets -- and inputs that are simply syntax errors. Type `carbonara AND (` into a naive implementation and the datalog query throws. Type `title:*` and a user is speaking to the index in ways no search box promised.

The policy question is the interesting part: *is the search box a query-language REPL or a place to type words?* For a recipe box there is one defensible answer, and then the code is dull on purpose:

```clojure
(defn- fulltext-escape
  "Neutralize Lucene query syntax in user input.
  The fulltext datalog fn hands `q` to a Lucene QueryParser, where `*`,
  `~`, `AND`… are operators (and some inputs throw outright). We search
  literal words, so operators become spaces and the analyzer does the rest."
  [q]
  (-> (or q "")
      (str/replace #"[+\-!(){}\[\]^\"~*?:\\/]" " ")
      (str/replace #"(?i)\b(AND|OR|NOT)\b" " ")
      str/trim))
```

This is [the same boundary discipline as the write path](21-forms-validation.md) wearing read-path clothes: raw wire input is *conformed* -- here, into the "literal words" dialect -- before anything with power sees it. Operators become spaces; the analyzer lowercases and tokenizes what remains; an input that was *only* operators conforms to nothing and searches nothing. The test states the contract more plainly than prose can:

```clojure
(is
  (= [] (recipe/search db "AND OR * ~ ("))
  "Lucene operator soup is neutralized, not thrown")
```

> **Trade-off -- the words we took away.** Neutralizing syntax costs real expressiveness: no phrase quotes, no exclusions, no prefix search. Power users notice. But an exposed query language is a *contract* -- once users learn `-cream`, you support `-cream` forever, across index rebuilds and any future engine swap -- and it is an error surface (every malformed query is now your bug, reachable by URL by anyone, unauthenticated). Literal words is the contract this product means to keep. If richer syntax ever justifies itself, it should arrive as *our* parsed grammar compiled onto Lucene, not as Lucene's grammar handed to strangers.

## Bounded, and why this bound is different

`search` truncates at fifty results. Attentive readers will raise an eyebrow: [chapter 9 defended](09-recipe-domain.md) the *unbounded* browse read as the right shape for this catalog, and the afterword lists real pagination among the seams. Is a `take 50` here a contradiction?

No: the two reads answer different questions, and the difference is what bounds are *for*. Browse is an inventory: its job is "everything, most recent first," its size is the catalog's size, and truncating it silently would be lying about the inventory. Search is a *ranked* read: its contract is "the best matches for these words," and rank decays -- match forty-one is not the answer to anyone's question. Truncating an inventory loses information; truncating a ranking sheds noise. So browse stays honest-and-unbounded until the catalog forces the pagination conversation, while search ships with a stated ceiling (`search-limit`, a named var) from day one -- because "bounded" is part of what *ranked* means. When pagination does arrive for browse, search will not need it; a user who does not find it in fifty results needs different words, not page two.

## Two kinds of nothing

The handler passes the view either `nil` or a vector, and the distinction is UX, not pedantry:

```clojure
(let [q (str (or (get-in request [:params :q]) ""))
      results (when-not (str/blank? q)
                (recipe/search (d/db (db/get-connection)) q))]
  ,,,)
```

`nil` means *no question was asked* -- `/search` bare -- and renders as just the form, an invitation. `[]` means *asked and answered: nothing* -- and renders as the localized no-results line, an answer. Collapsing them (the classic "showing 0 results for ''" page) makes the software look like it wasn't listening. Three lines of `cond` in the view are what listening costs.

## Proof

The unit tests pin the semantics that matter -- words not substrings, case-blindness from the analyzer, the empty-vector contracts, and the operator-soup guarantee:

```clojure
(is
  (= #{"Classic Carbonara" "Carbonara, but vegan"}
    (set (map :recipe/title (recipe/search db "carbonara"))))
  "matching is on words, case-insensitively")
(is (= ["Gazpacho"] (map :recipe/title (recipe/search db "GAZPACHO"))))
(is (= [] (recipe/search db "tiramisu")) "no hits is an empty vector")
```

The e2e test does the only thing an end-to-end test should here -- proves the *address* works:

```javascript
// A plain GET with the query in the URL — addressable by construction.
await page.goto('/search?q=sesame');
await expect(page.getByRole('link', { name: new RegExp(title) })).toBeVisible();
```

Note what it navigates to: not the form. The URL *is* the feature; the form is one way to construct it.

## Trade-offs & limitations, in one place

- **The fulltext index is eventually consistent by design.** Datomic builds fulltext segments during background indexing, not transaction commit. A freshly created recipe can be findable by browse (which reads datoms) moments before it is findable by search (which reads Lucene). Against `datomic:mem` -- dev, tests, this book's whole lab -- indexing is effectively immediate, which means *the development environment cannot show you this gap*; it is the one behavior in this chapter you must know about rather than observe. For a recipe box the lag is irrelevant. For "search must see the write it just made" products, fulltext is the wrong tool and the honest alternatives (query datoms, or an external index you own the freshness of) cost accordingly.
- **Relevance is Lucene's default scoring over titles.** No boosts, no recency weighting, no typo tolerance (we disarmed `~` along with the rest of the syntax). Each is a deliberate absence: every relevance knob added is a behavior to explain and maintain, and a three-word title corpus gives scoring very little to be wrong about.
- **The pull-per-result read is O(results), bounded by the limit.** Fifty pulls against a warm peer cache is not a cost worth engineering around today; [the measurement chapter](32-server-path-measured.md) is where that claim stops being a shrug and becomes a number.
- **`:db/fulltext` is forever-ish.** The flag cannot simply be toggled off an attribute later; it is one of the schema decisions [chapter 8](08-datomic.md) said to make deliberately. This application made it early and, for nineteen chapters, looked profligate. Chapters like this one are why the book keeps insisting schema is where foresight is cheapest.
- **This rests on Datomic Pro's Lucene fulltext specifically.** `:db/fulltext` is a Pro feature, backed by the transactor's embedded Lucene; Datomic Cloud does not offer it, so a reader on that product would build search a different way. It is worth naming because the whole feature stands on it -- one more place [the Datomic choice](08-datomic.md) carries weight rather than sitting incidental, and a seam to know before a migration rather than after.
- **One function stands between user input and Lucene's query parser.** `fulltext-escape` rewrites the QueryParser's operator vocabulary to spaces -- the wildcards and booleans, and the `&&`/`||` spellings that otherwise *throw* out of the parser and 500 a public endpoint. It is deny-by-rewrite, not a real parse: right for "search these literal words," and the thing to re-read against the Lucene syntax reference the day the query grammar grows past that.

## The shape of the movement

Three features now stand in a row, and they share an anatomy: the platform work was done early and *slightly in excess of immediate need*: a dispatcher that morphs any HTML answer, an island that POSTs any marked region, an index on the one attribute that names things. Each looked, briefly, like over-building. Then the feature arrived and was measured in attributes and clauses instead of subsystems. That is the compounding this book has been wagering on: the platform is not the tax you pay before features; built right, it is where the features already live, waiting to be asked for. The next two chapters ask harder questions of that platform -- who you are, and how to prove it -- and the answer will, one more time, be built from parts already on the table: [an HMAC, a clock](07-time-clock.md), and [an email](25-auth-email-flow.md).
