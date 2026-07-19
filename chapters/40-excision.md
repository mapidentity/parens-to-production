# The Right to Be Forgotten: Excision, Run

This debt is the book's oldest. [Chapter 9](09-recipe-domain.md), having sold immutable history as the feature it is, turned the same sentence over and priced it: *"an application holding personal data on this schema ships with excision in its runbook, or it cannot honor an erasure request."* Then [the activity feed](26-activity.md) inherited the asterisk, and [the backup chapter](39-backup-restore.md) just made everything remember *twice*. A user who invokes the GDPR's right to erasure is asking about history. [`delete!`](09-recipe-domain.md) already handles the present tense; history is the thing this entire application is built never to lose. Datomic's answer is **excision**: the one write that unsays.

## The write that unsays

Mechanically, excision is a transaction like any other, naming the entity whose past must go:

```clojure
@(d/transact conn [{:db/excise eid}])
;; Excision is performed by the transactor's indexing job; sync-excise
;; blocks until it is visible.
@(d/sync-excise conn (d/basis-t (:db-after result)))
```

Two properties distinguish it from everything else in the book. It is the only write that touches *history* -- after it, `d/history`, `d/as-of`, every time-travel read this application is made of, behaves as if the entity's datoms never happened. And it is heavyweight by design: the actual removal runs inside the transactor's indexing machinery (hence `sync-excise`, the only "wait for it" in the book's Datomic usage), which is Datomic telling you this is a compliance procedure, not a delete button. Routine deletion remains [`delete!`](09-recipe-domain.md); excision is for the day someone has the *right* to be gone.

## The drill, and what it proved

Rehearsing the most destructive write in the system wants a database you can afford to destroy -- and [the previous chapter](39-backup-restore.md) built just that: the restored scratch copy, standing in separate storage, byte-for-datom equal to production. That is where the drill ran, against a recipe with a real edit history:

```
RESULT history-datoms before: 14
RESULT fulltext before: 1
RESULT history-datoms after: 0
RESULT fulltext after: 0
RESULT tombstone: 1 excision record(s) — the log remembers THAT, not WHAT
```

Three findings, one per line-pair. **Fourteen history datoms to zero**: every version, across all time, gone from `d/history`'s answers. This is the past itself going, where `delete!` retracts only the present. **The fulltext index forgets too**: [the search chapter's](23-search.md) index is rebuilt by the same indexing job that performs the excision, so the erased title stops matching without any extra step -- something to prove rather than assume, since a search index that remembered would undo the whole exercise. And **the tombstone**: the excision transaction itself remains in the log, recording *that* entity such-and-such was excised, and when, but not one datom of what it said. An auditor can verify the erasure happened; nobody can reconstruct what was erased. That is the shape a compliance write should have.

And one finding from the *negative* drill, kept because it is a genuine trap: **on `datomic:mem`, excision silently does nothing.** The transaction succeeds, `sync-excise` returns a database, no error is raised anywhere. The history is fully intact afterward. A test suite running on [the in-memory fixture every other test uses](11-unit-testing.md) would exercise your erasure code and green-light a procedure that erases nothing. The dev loop cannot rehearse this write even dishonestly; the drill *must* run on real storage, which is one more job for [the scratch-storage rig](39-backup-restore.md) -- and the sharpest instance yet of chapter 8's warning that some production behavior simply does not exist in dev.

## The runbook: erasing a person from this schema

An erasure request names a person, not an entity id, so the runbook is an inventory walk over everywhere this schema lets a person leave personal data:

1. **Present tense first.** `delete!` their recipes, deactivate the account -- the product's own machinery, so every live view is consistent before history is touched.
2. **Their entities.** `:db/excise` the user entity and each recipe they authored, `sync-excise` after the batch. Their *content* is theirs; it goes entire, versions and all.
3. **The analytics database too.** [The magic-link rows](25-auth-email-flow.md) carry their email; excise those entities in `myapp-analytics` -- a second database with the same procedure, [same transactor](28-admin-dashboard.md).
4. **The backup shelf.** [Yesterday's archives](39-backup-restore.md) still hold everything; excision changes the log going forward and leaves old copies untouched. Rotate to a fresh backup target after the excision and let the retention clock run out on the old ones -- and where a legal-hold obligation says *keep* while an erasure right says *destroy*, that collision is a lawyer's to resolve, not a script's; the runbook's job is to make the shelf's contents knowable.

What remains afterward, named plainly: **references to the erased, from data that is not theirs.** A fork of their recipe is the *forker's* entity -- content lawfully copied at fork time, a separate person's data ([chapter 9's](09-recipe-domain.md) fork model made the copy explicit, which now turns out to be a GDPR-relevant design property). And datoms like [`:tx/author`](10-provenance.md) on other people's transaction history have the erased user only as a *value*; excising the user leaves them pointing at an entity that no longer resolves to anything. An opaque number that dereferences to nothing is not personal data. And the rendering layer was accidentally ready for it: [chapter 10's](10-provenance.md) `when-let` bylines, written for history recorded *before* provenance existed, handle history whose author has been *unsaid* with the same silence. The seam that tolerated the past's absence tolerates the person's.

## Trade-offs & limitations, in one place

- **Irreversible is the feature and the risk.** There is no un-excise; the drill database exists so the first run of a new erasure script is never against production. The [restore path](39-backup-restore.md) is the emergency brake only until the backup rotation completes -- after that, gone is gone, which is what was promised.
- **The tombstone is metadata that persists.** *That* an entity was excised, and when, remains queryable forever. Defensible (auditability) and worth disclosing in a privacy policy's fine print.
- **Excision load is real.** Each excision triggers index work proportional to the data touched; batch a large erasure and run it like the maintenance it is, not in a request handler -- [chapter 9's](09-recipe-domain.md) refusal to wire this into the app was the right call and stays.
- **`:db/noHistory` is the preventive sibling.** For high-churn attributes nobody will time-travel to, opting out of history *up front* shrinks what erasure ever has to touch. This schema's content attributes earn their history; a future `:user/last-seen-at` would not.
- **The inventory is a discipline, not a query.** Nothing finds "all places a person's data lives" automatically; the runbook above is correct for *this* schema and must be revisited when the schema grows. The habit of asking [state or transition?](10-provenance.md) at every new attribute now gains a third question: *whose?*

## The ledger, closed

Chapter 9 made a wager it could not yet cover: that keeping everything would prove the right default *because* the exceptional case, unsaying, had a real mechanism when law demanded it. The mechanism is now drilled, its trap on dev storage is pinned, its residue is named, and its runbook composes with the backup shelf it must chase. The application remembers everything, can prove what it remembered, and can forget on demand. What is left is the question every one-box system eventually faces -- what happens when one box stops being enough. That is the last chapter's subject.
