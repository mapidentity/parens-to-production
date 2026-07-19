# Provenance: Annotating the Transaction Itself

[The previous chapter](09-recipe-domain.md) built most of a version-control system without noticing: commits from `d/history`, checkout from `d/as-of`, diffs from an LCS walk, forks from one ref. Put its history page next to a `git log`, though, and two columns are conspicuously blank. Git tells you **who** made each commit and lets them say **why**. Our timeline knows only *when* and *what*.

The reflex fix is more recipe attributes (`:recipe/edited-by`, `:recipe/edit-note`), and it is worth watching that reflex fail before doing it right, because the failure teaches where facts actually belong. An edit note stored *on the recipe* would be part of the recipe's state: the next edit overwrites it (so history must dig it back out of `d/history` per version), [the diff view](09-recipe-domain.md) would dutifully report "note changed" alongside every real change, and [the preview chapter's](22-live-preview.md) speculative renders would carry stale notes into hypotheticals. All of it is symptom of one category error: *who* and *why* are not facts about the recipe. The recipe does not have an author-of-its-latest-change any more than a file does. **The change has an author.** The note describes a *transition*, not a state.

Datomic has a place for facts about transitions, and it has been on every page of this book that showed a query result: the transaction. A transaction in Datomic is an entity like any other: it has an id (the `?tx` our queries keep binding), it already carries one attribute (`:db/txInstant`, which [the history page](09-recipe-domain.md) has been reading all along), and, crucially, **it can carry more**. Asserting facts on the current transaction takes nothing but the reserved tempid `"datomic.tx"` in your own tx-data. The schema addition:

```clojure
(def tx-schema
  "Annotations on the TRANSACTION entity itself.
  A Datomic transaction is an entity like any other; asserting facts about
  it (via the \"datomic.tx\" tempid) turns each write into a commit with an
  author and, when offered, a message. version-history reads these back —
  git-for-recipes gets its `git log` fields."
  [{:db/ident :tx/author
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The signed-in user whose action produced this transaction."}

   {:db/ident :tx/note
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Optional user-supplied note: what changed, and why."}])
```

Note what these attributes are *not*: present in `pull-pattern`, in the diff's field list, or anywhere a recipe read can see. Provenance rides beside the data, never inside it. Every failure mode the reflex fix had is structurally impossible here.

## One helper, every write

The stamping is a three-line fold over tx-data, and a rule: **every user-driven write names its author.**

```clojure
(defn- annotate
  "Append the transaction annotation to `tx-data`.
  Every user-driven write names its author, and carries the user's note
  when one was offered. The \"datomic.tx\" tempid resolves to the
  transaction entity itself."
  ([tx-data user-eid] (annotate tx-data user-eid nil))
  ([tx-data user-eid note]
   (conj (vec tx-data)
     (cond-> {:db/id "datomic.tx"
              :tx/author user-eid}
       (seq note) (assoc :tx/note note)))))
```

Each mutation wraps its tx-data on the way to [`transact*`](08-datomic.md) -- `create!`, `update!`, `delete!`, even `reorder!`:

```clojure
@(db/transact* conn (annotate [[:db/retractEntity eid]] user-eid))
```

`fork!` needs nothing: it creates through `create!`, so a fork's opening transaction is authored like any other. The uniformity is deliberate and worth defending at its weakest point, the reorder. A dashboard drag [is bookkeeping, not content](09-recipe-domain.md): it produces no version, so why author it? Because "which writes deserve provenance" is exactly the kind of judgment call that rots: every exception is a future write path someone forgets to annotate on purpose instead of by accident. The rule with no exceptions costs one map per transaction and can be audited with a one-line query (*is there any user-era transaction without an author?*). Rules with exceptions cost meetings.

The note rides in only where a human offered one, which today means one place. The edit form grows its final field:

```clojure
;; The commit message. Only offered on edits: a creation IS its own
;; note, and fork provenance is recorded structurally.
(when editing?
  [:div
   [:label ,,, (t locale :recipe/note-label)]     ; "What changed? (optional)"
   [:input#note ,,,]
   (field-error locale :note (:note errors))])
```

and the note walks the only door the write path has: [`conform`](21-forms-validation.md) picks up an optional `:note` (trimmed, blank-is-nil, ceiling of 500, `:error/note-too-long` in both languages), `recipe-update` hands it to `update!`'s new trailing arity, and `update!` passes it to `annotate`. No second validation path, no special casing -- the commit message is just one more field that conforms.

## Reading it back

The payoff lands in `version-history`, which has been holding the transaction id in its hand since chapter 9. One pull per version entry, against the transaction entity itself:

```clojure
(let [ann (db/pull* db
            [{:tx/author [:user/email :user/display-name]} :tx/note]
            tx)]
  {:tx tx
   :t (d/tx->t tx)
   :instant (db/as-instant inst)
   :author (:tx/author ann)
   :note (:tx/note ann)
   :recipe (db/pull* (d/as-of db tx) pull-pattern eid)})
```

and the history page completes its `git log` impression -- a byline per version, and the note, when someone left one, quoted under it:

```clojure
(when-let [author (:author v)]
  [:span.text-sm.text-text-secondary
   (t locale :recipe/by) " " [:span.font-medium (author-name author)]])
,,,
(when-let [note (:note v)]
  [:p.mt-1.text-sm.text-text-primary.italic "“" note "”"])
```

Both renders are `when-let`s, and that is the honest treatment of the seed data problem every provenance system has: history recorded *before* the recorder existed. Versions transacted by [the seed](09-recipe-domain.md) before this chapter (or by any code path in the wild predating the migration) simply have no `:tx/author` datom, and the page says nothing rather than guessing. Provenance begins when you start recording it; backfilling would mean *inventing* provenance, which is worse than none.

For a single-author history the byline reads as redundant -- of course Alice edited Alice's recipe. The attribute is not there for that case. [Forks cross users](09-recipe-domain.md), deletions have actors, an admin surface may someday act on a user's behalf, and *"every transaction knows its author"* is also a query primitive the rest of the system inherits: [the activity feed](26-activity.md) currently infers actors from entity ownership, and the moment an event's actor and its entity's owner diverge, `:tx/author` on the very transactions it already reads is the answer waiting in place.

## Proof

```clojure
(recipe/update! h/*conn* u id {:recipe/servings 3} "Feeds one more now")
(let [[v1 v2] (recipe/version-history (d/db h/*conn*) id)]
  (is (= "annalist@x.lan" (get-in v1 [:author :user/email])) "creation is authored")
  (is (nil? (:note v1)) "no note was offered at creation")
  (is (= "Feeds one more now" (:note v2)) "the commit message survives on the tx"))
```

plus `conform`'s new table rows (whitespace note is no note; 501 characters is an error), and one line in the e2e edit flow: fill the note, save, open history, read the note on the version that carries it. The whole feature's test surface is small because the feature is small -- which was the point of putting it where the database wanted it.

## Trade-offs & limitations, in one place

- **Notes are immutable.** There is no edit-your-note, because there is no editing a past transaction -- and this is a feature wearing a limitation's clothes: provenance that can be revised later is testimony, not evidence. The cost is real (typos are forever, like a pushed git history without rebase) and the mitigation is the field's ceiling and its optionality.
- **`:tx/author` is a ref into mutable user-space.** The transaction is immutable; the *user it points to* is not (display names change, [accounts deactivate](28-admin-dashboard.md)). History bylines therefore render the author *as they are now* -- standard, and worth knowing. Rendering them as-of the transaction would be one `d/as-of` away if a product ever cared.
- **Deletion provenance is recorded but not yet surfaced.** A delete's transaction knows who did it; no current page shows deleted recipes, so the fact waits. It is the material an admin audit view would be built from -- [the log was the audit table all along](26-activity.md).
- **The annotation is per-transaction, not per-datom.** A transaction touching several entities (a reorder repositions many recipes) gets one author -- correct, since it was one act. If two acts ever share a transaction, that is a tx-batching design smell upstream, not a provenance limitation.

## The general lesson

Chapter 9's claim was that Datomic's log gives you version control's *mechanics* for free. This chapter's addendum: the log also has a place for version control's *sociology* (who and why), and it costs one reserved tempid. The broader habit to take away is the question this chapter started with, asked of every new fact: *is this about the state, or about the change?* State goes on entities. Change goes on transactions. The database has kept the two separate since its first datom; the schema just needed two attributes to say so out loud.
