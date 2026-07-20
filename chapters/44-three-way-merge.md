# Proposing Changes Back: The Three-Way Merge

We return to the application one last time, now that the whole system is in view -- and the feature we build is the one the app has been asking for since [chapter 9](09-recipe-domain.md). "Git for recipes" gave us forks, diffs, lineage, and history. It has every piece of a version-control system except the one that makes version control *collaborative*: a way to offer a fork's changes back. Git calls it a pull request; the merge underneath it is a three-way merge. That is this chapter.

The reframe worth stating first, because it explains why this, rather than live co-editing, is the right collaboration feature here. There are two axes of collaboration. The *synchronous* one (two cursors in one document, Google Docs) is a genuinely hard problem, stack-independent, and [the positioning chapter](02-positioning.md) islanded it on purpose. The *asynchronous* one -- branch, change, propose, merge -- is how most collaboration on content actually happens, and it is the shape this immutable-history stack is built for. A recipe site whose headline feature is forking is begging for the second axis, not the first.

## The three-way merge, and why the base is the whole game

Merging two divergent versions of anything runs into one question immediately: when they differ on a field, *who changed it?* A two-way comparison cannot tell you. If the fork says "pecorino" and the original says "parmesan," did the fork change it (apply the fork's value) or did the original change it (keep the original's) or did both change it (a conflict the human must resolve)? The two current values look identical in all three cases. What breaks the tie is a third input: the **base**, the common ancestor both sides started from. That is what makes a merge *three*-way, and reconstructing the base is the part that is hard everywhere else.

Everywhere except here. In an immutable-history store, the base is a *read*. When a fork is created it copies the parent's current content into its own first version -- so **the fork's own first version IS the common ancestor**, sitting in its history, one query away:

```clojure
(defn merge-for
  ,,,
  [db proposal]
  (let [source-id (get-in proposal [:proposal/source :recipe/id])
        target-id (get-in proposal [:proposal/target :recipe/id])
        base (:recipe (first (recipe/version-history db source-id)))
        ours (recipe/recipe-by-id db target-id)
        theirs (recipe/recipe-by-id db source-id)]
    ,,,))
```

Three reads: `base` is the fork's first version, `ours` is the target now, `theirs` is the fork now. [The version history](09-recipe-domain.md) that fell out of Datomic for free in chapter 9 turns out to have been holding the merge's hardest input all along. This is the payoff the whole book has been compounding toward -- *the base is not computed, it is remembered.*

## The merge itself: four cases, field by field

With the three inputs in hand, the merge is small. Per content field, the base decides which of four things happened:

```clojure
  [base ours theirs]
  (let [decide (fn [f]
                 (let [b (get base f) o (get ours f) t (get theirs f)]
                   (cond
                     (= t b) {:status :unchanged :value o}        ; fork left it alone → keep ours
                     (= o b) {:status :applied :value t ,,,}       ; only fork changed → apply theirs
                     (= o t) {:status :unchanged :value o}         ; both converged → keep ours
                     :else   {:status :conflict ,,,})))            ; both changed differently → CONFLICT
        ,,,))
```

Read the four `cond` clauses as the four possibilities, and notice that *every one of them consults `b`*. Take the base away and the first two clauses collapse into "they differ, so it's a conflict" -- which would flag every field the fork touched as a conflict, making the merge useless. The base is what lets clean changes apply silently and reserves the human's attention for genuine conflicts. Fields that merge cleanly go into a `:clean` map ready to write; conflicting fields carry their `base`, `ours`, and `theirs` values for the reviewer to choose between.

This is a *field*-level three-way merge, not a line-level one (Git's `diff3`), and that is a deliberate right-sizing. The teaching point is the *base*, and the base is equally free at any granularity; a line-level merge would add a complex algorithm (conflict hunks, markers) that is beside that point. For a recipe (where "the ingredient list" is one coherent block a cook rewrites as a unit), "you both rewrote the ingredients, pick one" is also the saner interaction than interleaving two people's ingredient lines into something neither wrote. When a conflict does surface on a text field, the review UI still *shows* [the line diff](09-recipe-domain.md) of what each side did, so the choice is informed.

## The lifecycle: propose, review, merge

A proposal stores almost nothing -- `source` (the fork), `target` (its parent), a status, a timestamp -- because `merge-for` derives everything else from the two recipes' histories. It is intent plus resolution, not a copy of content.

- **Propose.** The fork's owner, on their fork's page, offers its changes back (`create-proposal!`, guarded so only the fork's owner may propose and only a real fork with a live parent can be a source).
- **Review.** Anyone party to it opens the proposal and sees the merge: what applies cleanly, what conflicts, laid out field by field. The target's owner additionally gets the accept/decline controls; the recipe page surfaces "pending suggestions" to that owner so proposals are not a channel nobody checks.
- **Merge or decline.** The target owner accepts -- choosing a side for each conflict -- and the merged content is written as a new version of the target, authored by the merger with [a note recording the merge](10-provenance.md). Or they decline, and the proposal closes.

## Accept composes with optimistic concurrency

Here is where the chapters compound. Between opening a proposal and accepting it, the target recipe might change -- the owner edits it in another tab, or accepts a *different* proposal first. The merge the reviewer looked at is now against a stale target. This is [the conflict the forms chapter already solved](21-forms-validation.md): `accept!` recomputes the merge against the *current* target and writes it through `recipe/update!` carrying the target's version token, so the write goes through the same compare-and-swap. If the target moved, the accept returns `:conflict`, the proposal stays open, and the owner re-reviews against the new state. The pull-request merge inherits the recipe editor's concurrency safety at no cost, because both write through the one door.

## Proof

The merge is the crux, so the tests hammer it directly: only-the-fork-changed applies, only-the-target-changed is kept, both-converged is clean, and both-changed-differently conflicts -- each asserted against the base. Then the lifecycle end to end: a clean proposal merges into the original; a conflicting one is refused until resolved and then merges the chosen value; a stranger can neither propose nor accept; and -- the composition that matters -- an accept against a target that moved during review returns `:conflict` and the proposal survives for a re-review, exactly as the forms chapter promised. The web layer gets its own pass: propose redirects to the proposal page, the page renders the proposed change to the target owner, and accepting lands the merge on the original.

## Trade-offs & limitations, in one place

- **Field-level, not line-level.** A conflict is declared per field; two edits to *different lines* of the same ingredient list still conflict rather than auto-merging. Argued above as the right granularity for recipes; the seam to a line-level `diff3` is real, and the base -- the hard part -- is already free for it.
- **Propose to the parent only.** A proposal targets the recipe the fork descends from, and only that recipe. Generalizing (propose across the lineage, or to a sibling) is a schema that already stores `target` explicitly for that future; the authorization and merge are unchanged.
- **Notification is a push email; the in-app view is pull.** Opening a proposal enqueues a "you have a proposed change" email in the *same transaction* that writes the proposal, handed to [the durable job queue](47-job-queue.md) whose worker sends it with retries, so a crash can never leave the target owner un-notified and the push needs no live connection. What stays pull is the *in-app* surface: the owner sees pending suggestions on the recipe page, and lifting those onto the dashboard or [the activity feed](26-activity.md) is a read away, since the proposals are queryable. A *live* nudge -- a proposal landing on an already-open page -- is not built here; [the presence chapter](45-live-presence.md) proves the server can push to an open page, but what it builds there is a viewer count.
- **Last-writer-acknowledged on the merge, too.** Accepting resolves conflicts by *choosing a side*, not by blending -- the same honest limitation as the [editor's conflict handling](21-forms-validation.md). Blending two prose edits is the synchronous-collaboration hard problem the book keeps declining, correctly.
- **No auto-merge of stacked proposals.** Two open proposals against one recipe are reviewed independently; accepting one may turn the other into a conflict on re-review. That is correct -- the second proposer should see the new base -- but it means a busy recipe's proposals are serialized through the owner's attention, which is a feature at recipe scale and a bottleneck at wiki scale.

## The other axis

This is asynchronous collaboration, and it is where an immutable-history, server-rendered stack is at its strongest: the merge's hard input was a read, the concurrency safety was inherited, and not one line of real-time machinery was required. The *other* axis -- knowing, live, that someone else is here right now -- is the one everyone assumes server-rendering cannot do. The last chapter shows that it can, with a single honest island.
