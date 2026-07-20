# AGENTS.md — Parens to Production (a Clojure/Datomic SSR SaaS)

This repo is **two things at once**: an **online book** (`chapters/`, built with mdBook) and the **single real application it builds** (`src/myapp/`) — a server-authoritative, server-rendered recipe-versioning SaaS on Clojure + Datomic, deployed to one box. Both are held to a high engineering bar. When you work here, match it.

New to the repo? Read this file, then the `AGENTS.md` in the directory you're editing — they're nested, one per subsystem, and they carry the local rules this file doesn't.

## The theses (do not violate these — they are the whole point of the book)

- **Own the whole stack.** No layer is taken on faith. Prefer understanding plus a small dependency over a big one you cannot see into.
- **The server is the authority.** SSR with progressive enhancement, not a SPA. HTML is rendered server-side (Hiccup); a morph dispatcher (ch.15) swaps it in place; stateful bits are opt-in *islands*. Do not reach for client-side framework state where server authority plus an island will do.
- **The database already knows.** Datomic's immutable history *is* the feature — versions, diffs, the audit trail, and the ETag all fall out of it. Do not hand-maintain what history already holds.
- **Best engineering over teachability.** Never simplify a design just to make it easier to explain. If the best build is harder, build it and explain it.
- **Drilled or not claimed.** Every behavioral claim — in a code comment, in the book, in a PR — is backed by something actually run, tested, or measured, or is explicitly named as *reasoned, not drilled*.

## The gates (run these before you claim anything is done)

From the repo root:

```
./reformat && ./lint               # zprint + clj-kondo — MUST end at 0 warnings
clojure -X:test                    # unit suite (datomic:mem); scope with :nses '[a.b-test]'
clojure -T:build compile-strict    # STRICT AOT: fails on ANY reflection OR boxed-math warning from myapp
mdbook build                       # the book still builds (if you touched chapters/)
npx playwright test                # e2e (auto-starts the e2e server on :9876; needs libvips)
```

`compile-strict` is the one people forget: if you add interop, **type-hint it** (no reflection); if you do arithmetic, **keep it primitive** (no boxed math) — or the uberjar will not build. A stale `target/` can mask this; `clojure -T:build clean` first when in doubt. As of the 2026-07-20 review-fix pass **`myapp` compiles clean from a fresh `target/`** — the boxed-math sites that used to be tolerated in `jobs/core.clj` and `recipe/core.clj` (the old LCS diff, since rewritten as Myers) are all hinted now, so ANY `myapp` line in `target/compile-warnings.txt` is yours to fix. (The entries that remain in that file are third-party libs compiled from source — ring, crypto.equality, core.async — which the gate does not fail on.)

## Where things are

- `src/myapp/` — the app. `core.clj` (server lifecycle: start/stop wires the presence reaper, mailer, job worker, upload GC, and the libvips + uploads-root boot checks), `config.clj` (aero + `#profile`), `time.clj` (the one swappable clock), then subsystems, each with its own `AGENTS.md`: `web/` (HTTP), `db/` (Datomic), `recipe/` (domain), `upload/` (files + libvips), `auth/`, `jobs/`, `admin/`, `analytics/`, `i18n/`.
- `test/` — unit + smoke tests (datomic:mem). `e2e/` — Playwright specs against a real server.
- `ops/` — the box: systemd units, Caddy, deploy scripts, fail2ban/nftables, the RUNBOOK, the failover lab (`ops/lab/`).
- `chapters/` — the book (mdBook; `SUMMARY.md` is the TOC). `docs/` — non-book planning artifacts (not in the build).
- `dev/` — REPL/dev-only tooling (never required by production namespaces).
- `resources/config.edn`, `deps.edn` (deps + aliases), `build.clj` (the build).

## Conventions true everywhere

- **Hiccup auto-escapes — that is the XSS defense.** The one raw field (recipe descriptions) goes through the markdown sanitizer. Never emit unescaped user input.
- **CSP is hash-based, with no nonce.** An inline `<script>` needs its content hash registered in the asset pipeline (ch.29), or the browser blocks it.
- **Tenant isolation gates every read/write on ownership and never leaks existence** — a cross-owner attempt returns the same 404 as a missing entity. Route new mutations through the same ownership check.
- **Datomic's Peer returns `java.util.Date`;** convert at the `db/` seam (the `java.time` bridge). Schema grows by accretion — add attributes, never repurpose them.
- **`i18n` is two files.** Every user-facing string gets a key in both `i18n/en.clj` and `i18n/nl.clj`.

## Landmines (hard-won; you will hit these)

- **Silent-nil shadow bug.** A local bound to a name that shadows a core fn (the classic: a local `mrg` typed as `merge`) reads as a silent `nil`. Lint cannot see it. Only a **conflict-path / hard-branch test** catches it — write one for any merge/resolve logic.
- **`datomic:mem` cannot rehearse everything.** Excision, backup/restore, and transactor failover are no-ops or absent on the in-memory storage the tests use. Those are drilled against real SQL storage and the `ops/lab/` rig, not the unit suite.
- **Transactor + peer locally:** set the transactor `host=127.0.0.1`, not `localhost` — on a dual-stack box `localhost` resolves to `::1` first and the peer hangs forever. See `ops/lab/`.
- **Launching JVMs from a shell:** backgrounding a JVM with `&` leaks `SIGURG` into the parent shell (spurious non-zero exits); use a real background mechanism and guard `pkill` with `|| true`.

## Adding a feature (the well-worn path)

1. **Schema** (`db/schema.clj`) if you need new attributes — additive only.
2. **Domain** (`recipe/` or a new subsystem): the reads/writes, owner-gated, Datomic-first.
3. **Handler** (`web/handler.clj`): orchestrate, return a Ring response, owner-check, rate-limit if it writes shared state.
4. **Route** (`web/routes.clj`): wire it into the middleware stack (auth / terms / admin gates as needed).
5. **View** (`web/views.clj`): Hiccup, auto-escaped, progressive-enhancement-first (works with no JS); add an island only if it needs client state.
6. **i18n**: keys in `en.clj` and `nl.clj`.
7. **Tests**: a handler-smoke test at minimum; a hard-branch test if there is a merge/conflict path; an e2e spec for a user flow.
8. **Run the gates**, then update the chapter that documents this area — honestly (see `chapters/AGENTS.md`).

## If you touch `chapters/`

Best-engineering-over-teachability; drilled-or-not-claimed; a "Trade-offs & limitations, in one place" section per feature chapter; keep the opinionated, declarative voice but avoid overusing the AI-tell tics ("load-bearing", "not X but Y", "exactly", intensifiers, em-dash density) — and never swap a precise word for a tic-avoiding synonym that changes the meaning. Details in `chapters/AGENTS.md`.

---

*Every `AGENTS.md` in this repo has a `CLAUDE.md` symlinked to it, so Claude Code and any AGENTS.md-aware tool read the same context. Edit the `AGENTS.md`; the symlink follows.*
