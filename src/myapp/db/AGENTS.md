# AGENTS.md — Datomic access layer + schema + the tenant-isolation seam

> The only sanctioned door to Datomic. Two files: `core.clj` wraps the Peer API with the
> java.time bridge and the owner-gated read/write primitives; `schema.clj` holds every
> attribute install. Everything domain-side (recipe, auth, jobs, upload) goes through here.

## Key files
- `core.clj` — connection (`get-connection`, `get-db`, `create-database!`); the wrappers
  (`transact*`, `pull*`, `pull-many*`, `q*`, `with*`) that run the Date↔Instant bridge;
  the java.time coercers (`as-instant`, `convert-instants`, `convert-dates`); and the tenant
  primitives (`entid-owned`, `pull-owned`, `eid-owned?`, `assert-owned!`, `infer-user-attr`).
- `schema.clj` — schema-by-accretion. Per-entity vars (`user-schema`, `recipe-schema`,
  `upload-schema`, `tx-schema`, `proposal-schema`, `job-schema`) concatenated into `schema`,
  transacted by `create-database!` on startup.

## Conventions / rules
- **Never call raw `d/pull`/`d/pull-many`/`d/q`/`d/transact`/`d/with` from outside this ns.**
  Use the `*`-suffixed wrappers so the Peer's `java.util.Date` becomes `java.time.Instant`
  (and Instants become Dates on write). The two in-file raw `d/pull` sites are intentional and
  carry `#_:clj-kondo/ignore` + a comment explaining why (conversion on the next line, or a
  `:db/id`-only ref pull with no Date leakage). Don't add a third without the same justification.
- **Any externally-supplied id → an eid MUST pass through `entid-owned`/`pull-owned`.** A raw
  `(d/entid db [:recipe/id …])` or `(d/pull db … [:recipe/id …])` in a handler is a tenant-isolation
  bypass. These helpers return `nil` for both "no such entity" AND "owned by another user" — the
  caller 404s identically, so existence never leaks. `assert-owned!` is the mutation-side belt.
- **Schema is accretion-only.** Add attributes; never repurpose or remove an installed `:db/ident`.
  Re-transacting an identical attr is a no-op, so appending to a `*-schema` var is picked up on next
  startup with no REPL step. New entity types get a new `*-schema` var added to `schema`'s concat.
- **Owner convention:** every domain entity carries `:<ns>/user`. `infer-user-attr` derives it from
  the lookup-ref (`[:recipe/id …]` → `:recipe/user`). If you add an entity that doesn't follow this,
  pass `user-attr` explicitly to the owned helpers.
- Type-hint interop for strict AOT: `.toInstant` on `Date`, primitive-friendly code — `core.clj`
  sets `*warn-on-reflection*` and the strict build fails on any reflection/boxed-math warning.

## Gotchas
- `as-instant` and the bridge THROW on an unexpected type rather than passing it through — a silent
  passthrough would let a `Date` reach `.atZone` far from the source. Keep that fail-loud behavior.
- The **`merge`/`mrg` silent-nil class**: a `let`-local shadowing a core fn (the classic is binding a
  local `mrg` but typing `merge`) reads as `nil`, and clj-kondo cannot catch it. It surfaces only on
  the branch that reads the local — e.g. a three-way-merge conflict path. A happy-path test passes
  while conflicts silently drop a side. Any change to merge/owner-resolution logic needs a
  **hard-branch / conflict-path test**, not a happy-path one.
- Owner checks that "look redundant" are not: `entid-owned` at resolution AND `assert-owned!` at
  mutation are deliberate defense-in-depth against a future refactor threading an eid unchecked.
- On `datomic:mem` (all unit tests), excision and backup are silent no-ops — they need real SQL
  storage. Don't assert excision effects in a mem test.
- `pull*` converts a whole result tree; a huge pull pattern converts a huge tree. Keep patterns tight.

## Running / testing what's here
- Round-trip + upsert: `clojure -X:test :nses '[myapp.db.core-test]'` (bridge round-trips,
  `:db.unique/identity` upsert). Fixture `myapp.test-helpers/with-test-db` binds a fresh
  `datomic:mem` conn per test as `h/*conn*`.
- Tenant-isolation regressions live in `myapp.web.security-test` (cross-owner update →
  opaque 404, no existence leak) — run when touching the owned helpers.
- Full gates from repo root: `./reformat` → `./lint` (0 warnings) → `clojure -X:test` →
  `clojure -T:build compile-strict` (strict AOT).

## See also
- Ch. 8 "Datomic for Your SaaS: Schema, Queries, and the java.time Bridge" — this directory.
- Ch. 7 "A Single Source of Time: A Swappable Clock" — where the Instants come from (`myapp.time`).
- Ch. 9 "The Recipe Domain: Versions, Diffs, and Forks from Datomic's History" — the biggest caller
  (`myapp.recipe.core`), `d/history`/`d/as-of` reads, and the three-way merge (conflict paths).
- Depends on `myapp.config` (`:database-uri`). Callers: `myapp.recipe.*`, `myapp.auth.core`,
  `myapp.web.*`, `myapp.jobs.core` (Ch. 47), `myapp.upload.core` (Ch. 49).
