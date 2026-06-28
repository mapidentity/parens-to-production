# The Recipe Domain: Versions, Diffs, and Forks from Datomic's History

The previous chapter ended on a promise. It chose Datomic over PostgreSQL on the strength of one claim -- that for an application whose subject is the history of its data, immutability is not a nicety but the correct model -- and it left that claim mostly unspent. The schema was the user account; the time-travel reads it kept mentioning never appeared. This chapter spends the claim. It builds the recipe domain, the part of the application the [primer](01-primer.md) described as "Git for recipes": browse a recipe, fork someone else's, edit your copy, and see the diff between any two versions, the lineage back to the original, and the recipe exactly as it stood at any past moment.

![A server-rendered recipe page -- title, author, ingredients, method, version history, and the forks section.](images/app-recipe-detail.png)

What makes this domain worth its place in the book is that none of those features is a feature you build. Versions are not a `recipe_versions` table you write to on every edit; they are the transactions that already happened. A diff is not a stored delta; it is two reads of the past compared. Lineage is not a closure table; it is one reference walked. The work of this chapter is mostly the work of *not* building things -- of recognizing that Datomic has already recorded what a relational schema would make you record by hand, and writing the thin reads that ask it back. That recognition is the whole argument for the database, made concrete.

## The schema

The schema chapter built `user-schema` and noted that the same file would grow a `recipe-schema` as the domain arrived. Here it is:

```clojure
(def recipe-schema
  "Schema for the recipe-versioning domain — \"Git for recipes\"."
  [{:db/ident :recipe/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique recipe ID (stable across edits; a fork gets a new one)"}
   {:db/ident :recipe/user
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning user. Only the owner may edit or delete (tenant isolation)."}
   {:db/ident :recipe/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/fulltext true
    :db/doc "Recipe title"}
   {:db/ident :recipe/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Short description / headnote, rendered as Markdown"}
   {:db/ident :recipe/servings
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Number of servings the quantities are written for"}
   {:db/ident :recipe/ingredients
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Ingredients, one per line (newline-separated so versions line-diff)"}
   {:db/ident :recipe/steps
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Method, one step per line (newline-separated so versions line-diff)"}
   {:db/ident :recipe/forked-from
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The recipe this one was forked from. Absent on originals."}
   {:db/ident :recipe/position
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Display order within the owner's dashboard. Deliberately excluded
             from the version timeline so reordering is not a content change."}
   {:db/ident :recipe/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When this recipe (this fork) was first created"}
   {:db/ident :recipe/updated-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When this recipe was last edited"}])

(def schema
  "The full schema, transacted on database creation."
  (vec (concat user-schema recipe-schema)))
```

Three of these decisions carry the whole chapter, so they are worth naming before the code that depends on them.

A recipe is **one entity**. There is no separate version record. When the owner edits the ingredients, that is an ordinary transaction asserting a new value of `:recipe/ingredients` on the same entity -- and because Datomic never overwrites, the prior value is still a fact, still queryable, tagged with the transaction that retired it. The version history is therefore not something the schema provides; it is something the schema *cannot avoid*. The same property that would be an audit burden in a mutable store -- "we keep everything" -- is here the feature, free.

`:recipe/forked-from` is a **self-reference**: a ref attribute on a recipe pointing at another recipe. A fork is a brand-new entity with a fresh `:recipe/id`, carrying a copy of the source's content plus this one ref back to where it came from. Following the ref upward, recipe to parent to grandparent, is the lineage. Querying for entities that point *at* a given recipe is the set of its direct forks. The fork graph is a single attribute; the traversals are two short queries.

`:recipe/ingredients` and `:recipe/steps` are **newline-separated text**, not a collection of ingredient entities. This looks like the lazy choice and is in fact the load-bearing one: storing them as line-delimited strings is what lets a version-to-version comparison be a *line diff*, the same algorithm and the same `+`/`-` display a programmer already reads fluently from `git diff`. Modeling each ingredient as its own entity would buy structured querying the application never needs and cost exactly the diff it depends on.

The odd attribute out is `:recipe/position`, the owner's manual dashboard ordering. It is on the entity but it is emphatically *not* part of the content: dragging a card to the top of your dashboard must not register as a new version with an empty diff. Keeping it off the timeline is a single decision made in the domain code, below, and the dashboard-reorder mechanism that writes it belongs to the [progressive-enhancement chapter](19-progressive-enhancement.md), where the no-JavaScript reorder UI is built. Here it is enough to know the attribute exists and that the version machinery is written to ignore it.

## Reads

Everything the domain reads goes through one pull pattern, so a recipe always arrives shaped the same way -- its scalar fields, its owner, and (if it is a fork) a thumbnail of its parent:

```clojure
(def pull-pattern
  [:db/id :recipe/id :recipe/title :recipe/description :recipe/servings
   :recipe/ingredients :recipe/steps :recipe/position
   :recipe/created-at :recipe/updated-at
   {:recipe/user [:db/id :user/email :user/display-name]}
   {:recipe/forked-from [:recipe/id :recipe/title {:recipe/user [:user/display-name]}]}])

(defn recipe-by-id
  "Pull the current state of the recipe with `:recipe/id` = `id` (a UUID), or nil."
  [db id]
  (when id
    (when-let [eid (d/entid db [:recipe/id id])]
      (db/pull* db pull-pattern eid))))
```

`recipe-by-id` resolves the entity id before pulling, rather than pulling on the lookup ref directly, for one specific reason: a pull on a lookup ref that matches nothing returns `{:db/id nil}`, a truthy map that has to be guarded against downstream. Resolving the id first means a missing or retracted recipe returns a plain `nil`, which `when-let` and every caller already handles. It is a small thing that prevents a `{:db/id nil}` from leaking into a view and rendering as a blank recipe page. Note the pull goes through `db/pull*`, the wrapper from the [Datomic chapter](08-datomic.md) that converts `java.util.Date` back to `java.time.Instant` on the way out -- the domain never sees a `Date`.

The browse and dashboard lists are the same pull mapped over a query, and differ only in their sort. `all-recipes` returns everything most-recently-updated first; `recipes-by-user` returns one owner's recipes in their chosen dashboard order. Neither is surprising, so the repository has them in full; the reads that *are* the point are the temporal ones.

## Versions are transactions

A version of a recipe is a transaction that changed its content. That sentence is the entire design, and the function that realizes it is the keystone of the chapter:

```clojure
(defn version-history
  "Every version of recipe `id`, oldest first, reconstructed from Datomic history.
  Each entry is `{:tx <tx-eid> :t <basis-t> :instant <Instant>
  :recipe <state as-of that tx>}`. Returns nil if the recipe doesn't exist."
  [db id]
  (when-let [eid (d/entid db [:recipe/id id])]
    (let [h (d/history db)
          txs (->> (d/q '[:find ?tx ?inst
                          :in $ ?e [?a ...]
                          :where
                          ;; Only transactions that asserted a CONTENT attribute
                          ;; count as a version — a position-only reorder does not.
                          [?e ?a _ ?tx true]
                          [?tx :db/txInstant ?inst]]
                        h
                        eid
                        versioned-attrs)
                   ;; Sort by basis-t, not :db/txInstant — two edits in the same
                   ;; millisecond tie on the instant, which would make version
                   ;; order (and "latest") non-deterministic. t is monotonic.
                   (sort-by (fn [[tx _]] (d/tx->t tx))))]
      (mapv
        (fn [[tx inst]]
          {:tx tx
           :t (d/tx->t tx)
           :instant (db/as-instant inst)
           :recipe (db/pull* (d/as-of db tx) pull-pattern eid)})
        txs))))
```

It queries against `(d/history db)`, a view of the database that, unlike the ordinary database value, contains *every* assertion and retraction ever made, not just the current ones. The `:where` clause reads almost as English once the datom shape from the Datomic chapter is in mind. `[?e ?a _ ?tx true]` matches a datom on our entity `?e`, for some attribute `?a`, with any value (`_`), asserted (the trailing `true`, as opposed to a retraction) by transaction `?tx`. The `?a` is bound from the outside by `[?a ...]`, the collection-binding form, to `versioned-attrs` -- so the query matches only transactions that touched a *content* attribute:

```clojure
(def ^:private versioned-attrs
  [:recipe/title :recipe/description :recipe/servings
   :recipe/ingredients :recipe/steps :recipe/forked-from])
```

This is where `:recipe/position` earns its exclusion. A dashboard reorder asserts only `:recipe/position`, which is not in the list, so its transaction never matches the pattern and never appears as a version. The timeline stays a record of content changes, and a reorder does not surface as a phantom version whose diff is empty. The list is small, explicit, and the single point of truth for the question "what counts as an edit."

Two details repay attention. The query asks for `:db/txInstant`, the wall-clock time of each transaction, but it does **not** sort by it -- it sorts by `(d/tx->t tx)`, the transaction's basis-t. The reason is that two edits made in the same millisecond carry the same instant, and sorting on a value with ties makes "which version is newest" non-deterministic from one query to the next. The basis-t is Datomic's monotonic transaction counter; it never ties, so it gives a total, stable order. Wall-clock time is what we *show* the user; basis-t is what we *sort* and *address* by. And the per-version state itself comes from `(d/as-of db tx)` -- the database as it stood as of that transaction -- pulled with the same pattern as a live read. `d/as-of` is the second half of the promise from the previous chapter, finally called: a point-in-time database value you query exactly like the present one.

Addressing a single past state directly is its own small function:

```clojure
(defn version-as-of
  "The state of recipe `id` as of basis point `t` (a basis-t, tx-eid, or Instant/Date)."
  [db id t]
  (when-let [eid (d/entid db [:recipe/id id])]
    (db/pull* (d/as-of db t) pull-pattern eid)))
```

That `t` is the same basis-t `version-history` exposed on each entry, which means a URL can carry it. The [progressive-enhancement chapter](19-progressive-enhancement.md) serves `/recipes/:id/at/:t` straight from this function and, because a past state is immutable by construction, caches it for a year -- the basis-t in the path *is* the version, an address that can never go stale. The addressability the [positioning chapter](02-positioning.md) argued for in the abstract is, here, a recipe id plus an integer.

## Diffs are two reads compared

With any two versions reconstructable, a diff between them is a pure function of two pulled maps. The scalar fields compare directly; the two list fields -- ingredients and steps -- get a line diff, which is where the newline-separated storage pays off:

```clojure
(defn line-diff
  "A git-style line diff of two newline-separated strings.

  Returns a vector of `{:op :ctx|:add|:del :text <line>}` in display order,
  computed from a longest-common-subsequence of the lines: shared lines are
  `:ctx`, lines only in `new-text` are `:add`, lines only in `old-text` are
  `:del`. Pure data → data."
  [old-text new-text]
  ...)
```

The body is a textbook longest-common-subsequence: a dynamic-programming table over the two line vectors, then a walk back through it emitting context, additions, and deletions in display order. It is the densest function in the namespace, and the repository has it in full; the shape above -- and the data it returns -- is what matters here. One implementation note does connect back to an earlier chapter: the DP table is filled with primitive-`long` index loops rather than a `reduce` over a range, because the [strict build](04-build-hardening.md) fails on boxed math, and the obvious idiomatic version boxes its indices. The constraint set in chapter 4 reaches all the way into a diff algorithm, which is the point of setting it on the first day rather than the three-hundredth.

`line-diff` returns plain data -- a vector of `{:op :add :text "sugar"}` maps -- so the view layer renders a diff by mapping over it, and a test asserts on it without parsing rendered HTML. The field-level `diff` wraps it together with the scalar comparisons into one map describing everything that changed between two recipe states, carrying a top-level `:changed?` the caller can branch on. It, too, is pure data in and pure data out, with no database handle anywhere in sight -- the temporal reads happened upstream, and the comparison is arithmetic.

## Lineage and forks

A fork is the content of a recipe copied onto a new entity that remembers its source:

```clojure
(defn fork!
  "Fork the recipe `source-id` (any owner's) into a new recipe owned by `user-eid`.
  Copies the current fields and records `:recipe/forked-from`."
  [conn user-eid source-id]
  (let [db (d/db conn)
        src (recipe-by-id db source-id)
        src-eid (d/entid db [:recipe/id source-id])]
    (when src
      (create! conn user-eid
               {:title (:recipe/title src)
                :description (:recipe/description src)
                :servings (:recipe/servings src)
                :ingredients (:recipe/ingredients src)
                :steps (:recipe/steps src)
                :forked-from-eid src-eid}))))
```

`source-id` is deliberately any recipe, not one the forking user owns -- forking someone else's recipe is the entire social premise, so `fork!` takes no ownership check. The new recipe is a normal `create!` with one extra field, the `:recipe/forked-from` ref. From that single ref, both directions of the fork graph fall out. Upward, `lineage` walks parent to parent until it reaches an original:

```clojure
(defn lineage
  "Ancestors of recipe `id`, immediate parent first, up to the root original.
  Empty vector for an original recipe. Spans owners."
  [db id]
  (loop [cur (recipe-by-id db id)
         acc []]
    (if-let [parent-id (get-in cur [:recipe/forked-from :recipe/id])]
      (let [parent (recipe-by-id db parent-id)]
        (if (or (nil? parent) (some #(= (:recipe/id parent) (:recipe/id %)) acc))
          acc
          (recur parent (conj acc parent))))
      acc)))
```

The cycle guard -- stop if the parent is already in the accumulator -- cannot trigger through the UI, since a fork always points at a recipe that existed before it. It is there because the lineage is reconstructed from a stored reference, and a hand-written transaction could in principle form a loop; a read that walks references should not be the thing that hangs the request if the data is ever malformed. Downward, `forks` is a one-line query for the entities whose `:recipe/forked-from` points at a given recipe. The fork graph is navigable in both directions and was never built as a graph -- it is one ref attribute, read two ways.

## Mutations, and where ownership lives

Creating a recipe is a plain transaction; the only subtlety is computing its initial dashboard position so a new recipe appends to the end of the owner's list. Editing and deleting are owner-gated:

```clojure
(defn update!
  "Apply `changes` (a subset of the `:recipe/*` content keys) to recipe `id`,
  if owned by `user-eid`. Bumps `:recipe/updated-at`."
  [conn user-eid id changes]
  (let [db (d/db conn)]
    (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
      @(db/transact* conn
         [(merge {:db/id eid :recipe/updated-at (time/now)}
                 (select-keys changes
                   [:recipe/title :recipe/description :recipe/servings
                    :recipe/ingredients :recipe/steps]))])
      true)))
```

The gate is `db/entid-owned`: resolve the recipe's entity id, but only if it belongs to `user-eid`, and otherwise return `nil` so the whole `update!` short-circuits to `nil` and changes nothing. This is the tenant-isolation layer the [Datomic chapter](08-datomic.md) flagged as a coming addition to `myapp.db.core`; it is built in full in the [progressive-enhancement chapter](19-progressive-enhancement.md), alongside the other `*-owned` helpers and the question of where in the stack ownership should be enforced. For this chapter it is enough that a non-owner's edit resolves no entity and is silently refused -- which the tests below pin down. `delete!` follows the same shape with a `:db/retractEntity`, and a deleted recipe's forks keep their copied content; their `:recipe/forked-from` ref simply dangles, which `lineage` already tolerates by treating a missing parent as the end of the line. Note also what `update!` does *not* touch: `:recipe/position`. Reordering goes through its own path, so an edit bumps `:recipe/updated-at` and creates a version, while a reorder does neither.

## The proof

None of this is asserted; it is tested, and the tests are the most direct statement of what the domain guarantees. They build a recipe from nothing, edit it, and read its history straight back:

```clojure
(deftest edits-create-versions
  (let [u (mk-user! "a@x.lan")
        id (recipe/create! h/*conn* u {:title "Soup" :ingredients "water"})]
    (recipe/update! h/*conn* u id {:recipe/ingredients "water\nsalt"})
    (recipe/update! h/*conn* u id {:recipe/ingredients "water\nsalt\npepper"})
    (let [versions (recipe/version-history (d/db h/*conn*) id)]
      (is (= 3 (count versions)) "create + 2 edits = 3 versions")
      (testing "oldest version reconstructs the original via d/as-of"
        (is (= "water" (:recipe/ingredients (:recipe (first versions))))))
      (testing "newest version has the latest content"
        (is (= "water\nsalt\npepper" (:recipe/ingredients (:recipe (last versions))))))
      (testing "version-as-of by basis-t returns that point's state"
        (let [t (:t (first versions))]
          (is (= "water" (:recipe/ingredients (recipe/version-as-of (d/db h/*conn*) id t)))))))))
```

Three transactions, three versions, and the first version still says `"water"` though the recipe now says `"water\nsalt\npepper"` -- a past state read out of `d/as-of`, intact, with no version table behind it. The diff test makes the same point about comparison: it edits a cake from eight servings to twelve and adds sugar, then asserts that `diff` reports the servings change with both old and new values and that `"sugar"` appears in the ingredients diff as an `:add`. The fork tests build a chain four deep and check that `lineage` returns the three ancestors parent-first to the root, that an original has no ancestors, and that a fork is owned by the forking user while recording its provenance back to a recipe it does not own. And the isolation tests confirm the gate: a non-owner's `update!` and `delete!` both return `nil` and leave the recipe untouched, while the owner's succeed. Every one of these runs against the in-memory database from the [Datomic chapter](08-datomic.md)'s fixture, in milliseconds, with no setup beyond transacting a user to own the recipes.

What the chapter built is small -- a schema, a pull pattern, a handful of reads, two mutations, a diff -- and most of it is reads rather than machinery. That is the shape of the argument. A recipe-versioning product in a mutable store is a version table, a trigger or an application-level write on every edit to populate it, a soft-delete column, a query language for "as of" that the database does not speak, and the standing risk that the audit trail and the live row disagree. Here the audit trail *is* the database, the "as of" is an API call, and the two cannot disagree because there is only one of them. That is what it means to treat immutability as a feature to exploit rather than a constraint to work around: the history you would otherwise build by hand is the history you were storing all along, and the work is learning to ask for it.
