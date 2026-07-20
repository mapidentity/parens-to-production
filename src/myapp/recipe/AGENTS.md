# AGENTS.md — the recipe-versioning core domain

> "Git for recipes." Versions, diffs, lineage and proposals are thin READS over Datomic history, not tables you maintain. This dir owns the domain logic between the web handlers and `myapp.db.core`.

## Key files
- `core.clj` — recipes: reads (`recipe-by-id`, `browse-page`, `search`, `activity`), history (`version-history`, `version-as-of`), `line-diff`/`diff`, lineage (`lineage`/`forks`), mutations (`create!`/`update!`/`delete!`/`fork!`/`reorder!`/`move!`), `preview`, input validation (`conform`).
- `proposal.clj` — pull-request-style proposals: `three-way-merge`, `merge-for`, `create-proposal!`, `accept!`/`decline!`, and the `:proposal-notification` `jobs/run-job` defmethod.

## Conventions / rules
- `versioned-attrs` (core.clj) is the single source of truth for "what counts as a version." `version-history`, `activity`'s upstream-edit query, and proposal `merge-fields` all key off it. `:recipe/position` is deliberately excluded so a dashboard reorder never creates a version or a false conflict. Add a content attr → add it here.
- Reads are public (anyone browses/forks any recipe). Every mutation resolves its target with `db/entid-owned` (owner-checked, returns nil for both missing and foreign — 404 without leaking existence). Never call `d/entid`/`d/pull` on an outside-supplied id in a write path.
- All reads pull through `db/pull*` (Date→Instant bridge); writes through `db/transact*`/`db/with*` (Instant→Date). Never raw `d/pull`/`d/transact` here — Peer returns `java.util.Date`.
- `create!`/`update!`/`preview` refuse unconformed input: `assert-content!`/`assert-changes!` THROW rather than repair (no coining "Untitled"). Call `recipe/conform` (in the handler) first. `conform` returns error *codes* as data (`{:title [:blank]}`); i18n happens at the boundary, not here.
- Every write is annotated via `annotate` (the `"datomic.tx"` tempid) with `:tx/author` and optional `:tx/note`.
- Strict AOT (`compile-strict`) forbids reflection + boxed math, and this ns compiles clean. The Myers diff runs over a primitive `long`-array frontier with `(long …)` coercions throughout, `cas-failed?` lives in `db/core`, and `browse-page`'s `limit` is `^long` — keep arithmetic unboxed and interop type-hinted (`^Throwable`).

## Gotchas
- Optimistic concurrency: `update!` with `expected` (the `:recipe/updated-at` the editor loaded) is a `:db.fn/cas` on that per-recipe attr; conflict returns `:conflict` (nothing written), detected via `cas-failed?`. The token is the recipe's content clock, NOT global basis-t — that's why reorders must NOT bump `updated-at`.
- `version-history` sorts by basis-t (`d/tx->t`), never `:db/txInstant` — two edits in the same ms tie on the instant and make "latest" nondeterministic.
- `browse-page` keyset MUST be `(title, eid)`, not the uuid — that is the AVET index order; tie-breaking on uuid skips rows. Keep the cursor opaque to callers.
- `line-diff` is Myers' O(ND) diff (git's algorithm): cost tracks the EDIT DISTANCE, so a small edit to a big recipe is near-linear — no line-count cap needed. The one bound is `max-edit-distance` (4000): a pair sharing almost nothing degrades to `coarse-diff` (all-del/all-add) so the trace can't OOM a public cacheable endpoint. Output orders a replaced line as del-then-add (git hunk order). Keep the primitive `long`-array frontier.
- `search` escapes Lucene operators (`fulltext-escape`); blank/operator-only input returns `[]`. `recipe-by-id` resolves the eid first so a missing recipe returns nil, not `{:db/id nil}`. `lineage` guards against a hand-crafted fork cycle.
- Proposal merge: `base` = the fork's FIRST version (`first-version`, a min-tx query + one as-of pull — NOT `(first (version-history …))`, which reconstructs every version); `ours` = parent now; `theirs` = fork now. `accept!` recomputes the merge against the CURRENT target and writes the merge + the `:accepted` status flip in ONE `update!` transaction (via its `extra-tx` arg), so a target that moved during review yields `:conflict`, leaves the proposal open, and never half-commits. Unresolved conflict fields → `:incomplete`.
- `create-proposal!` commits the proposal AND its notification job in ONE transaction (crash cannot desync them), is IDEMPOTENT per (source, target) (a duplicate open proposal is not re-created — it would re-spam the target owner), and the propose route is rate-limited. All proposal writes go through `recipe/annotate` like every other domain write.
- Merge/conflict logic is exactly where a `mrg`-written-as-`merge` shadow bug (silent nil) hides — cover the CONFLICT branch, not just the happy path (see MEMORY: merge/mrg shadow bug).

## Running / testing what's here
- Unit suite (datomic:mem): `clojure -X:test` — namespaces `myapp.recipe.core-test`, `myapp.recipe.proposal-test`. `history`/`as-of`/`since` all work in-mem.
- Before committing (from repo root): `./reformat` then `./lint` (0 warnings), then `clojure -T:build compile-strict`.

## See also
- Book: ch. 09 "The Recipe Domain: Versions, Diffs, and Forks from Datomic's History" (`chapters/09-recipe-domain.md`) — also defines `recipe-schema`.
- Depends on `myapp.db.core` (`pull*`, `entid-owned`, `with*`, `transact*`, `as-instant`), `myapp.time` (`now` + java.time bridge), `myapp.jobs.core` (`enqueue-tx`/`run-job`), and for proposal notifications `myapp.auth.email` + `myapp.i18n`. Callers of `conform`/mutations live in `myapp.web.handler` / `myapp.web.views`.
