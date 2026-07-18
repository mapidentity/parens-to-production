# What Happened While You Were Away: Activity from the Log

Somewhere in every social product's architecture diagram there is a box labeled *notifications*, and it is never a small box. An events table or a message queue; fan-out writes on every action ("insert a notification row per follower"); a mark-as-read model; a background worker to do the fanning; retention policies for a table that only grows. The [afterword-fenced](34-ci-cd.md) infrastructure -- background jobs, outbound email -- sits inside that box for most stacks, which is why this book's application was going to ship without the feature at all.

Then the auth chapters gave users [a signed-in home](25-auth-email-flow.md), and the product grew the exact social edge the feature exists for: *other people can fork your recipe, and the person you forked from can keep editing the original.* Both facts a user cares about; both invisible unless they go looking. The classic move is to build the box.

This application does not need the box, and the reason is [the oldest claim in its data chapter](08-datomic.md): the database is a log that never forgets. "What happened since I last looked" is not something the write path must *record* -- every write already recorded it, with a transaction instant, for free. Notifications here are **a query with a cursor**, and this chapter is the third and final panel of the time-travel triptych [the domain chapter](09-recipe-domain.md) opened: `d/history` answered *what were the versions*; `d/as-of` answered *what was true then*; **`d/since`** answers *what is new*.

## The idiom: a window plus the world

`d/since` is the mirror of `d/as-of`: it returns a database value containing only datoms asserted *after* a basis point. The temptation is to treat it as "the recent database" and query it directly, and the first attempt at that teaches the API's one real lesson:

```clojure
(d/q '[:find ?f :where [?f :recipe/forked-from ?orig]
                       [?orig :recipe/user ?u] ,,,]
     (d/since db cursor) ,,,)
;; => #{}  — always, for any recent fork
```

Empty, always. The fork datom is in the window -- but `?orig`'s ownership was asserted *months ago*, and a since-db, by definition, does not contain it. A since-db is not a small database; it is a **window**, and almost nothing interesting is answerable inside a window alone. The idiom -- and it is the load-bearing line of this chapter -- is to pass *two* database values and let each clause say which world it reads:

```clojure
(d/q '[:find ?f ?inst
       :in $s $ ?u
       :where
       [$s ?f :recipe/forked-from ?orig ?tx]   ; NEW: the fork, in the window
       [$ ?orig :recipe/user ?u]               ; IDENTITY: whose recipe, full db
       (not [$ ?f :recipe/user ?u])            ; and not the user's own doing
       [$ ?tx :db/txInstant ?inst]]
  since-db
  db
  user-eid)
```

Datalog has carried multiple database sources since [chapter 8](08-datomic.md) introduced `$`; this is the pattern that makes the feature fall out of it: **the window supplies the news; the world supplies the meaning.** The `?tx` binding in the fourth datom position -- and its `:db/txInstant` -- gives every piece of news its timestamp without any schema for timestamps, because [transactions are entities](10-provenance.md) and always were.

The domain function asks its two questions and merges the answers:

```clojure
(defn activity
  "What happened around `user-eid`'s recipes since the cursor.
  `since` is an Instant (nil means ever). Two kinds of news: forks of
  their recipes by others, and content edits to originals they forked.
  Newest first.

  A read, not infrastructure: `d/since` narrows the database to datoms
  asserted after the cursor, and each query joins that window back to the
  full db for identity — a since-db alone knows nothing older than the
  cursor, so the two-source join is the idiom, not a workaround. ,,,"
  [db user-eid since]
  (let [since-db (if since (d/since db (Date/from since)) db)
        fork-rows ,,,      ; the query above
        upstream ,,,]      ; its sibling, below
    (->> (concat ,,,)
         (sort-by :at #(compare %2 %1))
         vec)))
```

The sibling query walks the fork edge the other direction -- *originals I forked, edited since* -- with two touches worth noticing. Its aggregation head, `(max ?inst)`, collapses five upstream edits into one entry per recipe ("the original changed, most recently at…"), because a feed is news, not an audit trail; [the history page](09-recipe-domain.md) is one click away for anyone who wants the trail. And its own `not`-clause keeps self-forks quiet -- both queries agree that *nobody is notified about themselves*, which sounds obvious until you have used software that does.

Note the small mirror of [the last chapter's bridge lesson](22-live-preview.md): `d/since` takes a `Date` or basis-t, so the Instant-speaking application converts at the boundary -- `(Date/from since)` -- the same seam `db/with*` covered for speculative writes. The bridge's rule holds: `java.time` everywhere, `java.util.Date` only in the last inch before a Datomic API.

## The cursor is one fact

The entire persistent state of this feature is a single attribute:

```clojure
{:db/ident :user/activity-seen-at
 :db/valueType :db.type/instant
 :db/cardinality :db.cardinality/one
 :db/doc "When the user last viewed their activity feed — the d/since cursor."}
```

No notifications table, no per-item read flags, no fan-out. The dashboard handler reads the cursor, computes the feed, and then -- only then -- advances it:

```clojure
(let [seen-at (:user/activity-seen-at (db/pull* db [:user/activity-seen-at] user-eid))
      items (recipe/activity db user-eid seen-at)]
  (auth/mark-activity-seen! conn user-eid)
  (html (views/dashboard ,,, recipes items)))
```

Order is the design. Compute against the *old* cursor, advance, render: the panel is "since your previous visit" *by construction* -- shown once, then folded into history. There is no mark-as-read button because there is nothing to mark; the visit itself is the acknowledgment, the way a stack of mail on the table stops being news once you have stood in front of it. (The `db` value the feed reads predates the cursor write by construction too -- [database-as-value](08-datomic.md) again, quietly preventing the feed from hiding its own contents.)

> **Trade-off -- a greeting, not an inbox.** Shown-once is a real choice with a real cost: glance at the dashboard on your phone and the news is consumed, findable afterward only by knowing to look at the recipe. That is the correct shape for *this* feature -- a low-stakes "while you were away" -- and the wrong shape for anything actionable. An inbox (per-item read state, dismissal, retention) is per-item *writes* -- state the log does not carry for us -- and the day this product needs one is the day notifications stop being a query. The line to hold: build the inbox when an item can require a *decision*, and not before. Forks require a smile at most.

The view is a panel that leads the dashboard when there is news and does not exist when there isn't; each entry names the actor, the deed, and links the recipe:

```clojure
[:span.font-medium (author-name (:recipe/user recipe))]
" "
(t locale (if (= entry-type :fork) :activity/forked-yours :activity/updated-upstream))
" "
[:a ,,, {:href (str "/recipes/" (:recipe/id recipe))} (:recipe/title recipe)]
```

For a `:fork` entry the recipe *is* the fork, so its author is the forker; for an `:upstream-edit` the recipe is the original, so its author is the editor. One rendering rule, and the actor is always `(:recipe/user recipe)` -- the kind of coincidence you get to keep when the feed's entries are pulled through [the same pattern as every other read](09-recipe-domain.md).

## Proof

The unit tests read as the feature's spec -- Alice hears about Bob, Bob hears about Alice, nobody hears about themselves, and the cursor narrows the window:

```clojure
(is (= 1 (count (recipe/activity (d/db h/*conn*) alice nil))) "the fork is visible")
(is
  (empty? (recipe/activity (d/db h/*conn*) alice (time/now)))
  "…and invisible past a cursor set after it")
```

The handler test pins the shown-once contract (two consecutive dashboard renders; the panel appears exactly once). And the e2e test plays the whole social scene through a real browser -- Alice creates, signs out; Bob forks; Alice returns:

```javascript
await page.goto(magicLink);
await expect(page.getByText('While you were away')).toBeVisible();
await expect(page.getByText('forked your recipe:')).toBeVisible();

// Refresh: the cursor advanced; the news is folded into history.
await page.reload();
await expect(page.getByText('While you were away')).toHaveCount(0);
```

Three actors in that test -- two users and the transaction log -- and the log is the only one doing notification work.

## Trade-offs & limitations, in one place

- **A nil cursor reads the whole log.** A user's first-ever dashboard visit (and every visit, for the pre-cursor seed users) computes activity across all history. Bounded today by catalog size; the honest fix at scale is initializing the cursor at signup ("news starts now"), one line in the registration transact.
- **Recipe-granular, latest-wins.** The upstream entry says *the original changed*, not what changed; the fork entry doesn't count how many. Both link to pages that answer precisely ([the diff view](09-recipe-domain.md) exists for exactly this). The feed's job is *that*, not *what*.
- **Poll-on-visit, not push.** News arrives when you show up, which is what "while you were away" means. Real-time delivery is a different feature wearing the same data: [an SSE island](20-progressive-enhancement.md) running the same `activity` query on a heartbeat would be the architecture-honest version, and [the positioning chapter](02-positioning.md) already priced that road. Email digests remain exactly where the afterword put them -- with the jobs infrastructure this book deliberately does not build.
- **The feed trusts the log's retention.** `d/since` reads what the log kept. The day this application confronts excision -- [the right-to-be-forgotten chapter](40-excision.md), where history is genuinely unsaid -- is the day "the log remembers everything" acquires an asterisk, and this feature inherits it.

## The box, unbuilt

Look back at the notifications box this chapter did not build: the events table was the transaction log, the timestamps were `:db/txInstant`, the fan-out was a join, the read-model was one instant per user, and the worker was nobody. That is not a trick that generalizes to every stack -- it is what falls out of *this* one's central purchase, made twenty chapters ago, when the database that never forgets looked like an extravagance. The extravagances keep maturing. Two movements from now, [the same immutability hands anonymous readers a free cache validator](31-conditional-get.md); first, the features movement closes with its proofs -- [end to end, in a real browser](27-e2e-testing.md).
