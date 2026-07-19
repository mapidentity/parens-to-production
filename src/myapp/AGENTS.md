# AGENTS.md — the application root: lifecycle, config, singletons, subsystem map

> This is `myapp`, the whole app. The four top-level files are the shared spine (server lifecycle, config, clock, i18n); the sibling dirs are the subsystems. Everything a request touches is reachable from here. Read this before adding a feature that crosses more than one dir.

## Key files (this dir)
- `core.clj` — the server lifecycle. `start-server!`/`stop-server!` wire, in order, the DBs + libvips boot check + http-kit + presence reaper + mailer + REPL + job worker + upload GC. `-main` is the uberjar entry (gen-class, NOT clojure.main). Adding a background subsystem means starting AND stopping it here.
- `config.clj` — Aero + `#profile` load of `resources/config.edn`. `get-config` reads a path from the delayed `config` map. `:prod` fails closed on missing keys/vars (`prod-required`); `:dev` generates random crypto keys with a warning.
- `time.clj` — the single swappable clock. Every `now`/`today` in the app routes through here; direct `Instant/now`/`LocalDate/now`/`System/currentTimeMillis` are BANNED elsewhere and `./lint` greps for them. Tests pin time with `with-clock` + `fixed-clock`.
- `i18n.clj` — `t(locale, key)` translation lookup + `detect-locale` (Accept-Language, RFC 4647). Strings live in `i18n/en.clj` + `i18n/nl.clj` as plain defs, deref'd through vars so they hot-reload.

## Subsystem map (sibling dirs)
- `web/` — Ring/Reitit edge: `routes.clj` (route tree + middleware stack), `handler.clj` (handlers), `views.clj` (Hiccup), plus assets, CSP, presence (SSE), metrics, ratelimit, markdown, security-log, inspector.
- `db/` — Datomic access + schema. Owns `transact*`/`pull*`/`q*` (Instant↔Date bridge) and the tenant-isolation helpers (`entid-owned`, `pull-owned`, `assert-owned!`).
- `recipe/` — the domain: versions/diffs/forks from Datomic history (`core.clj`) + fork proposals / 3-way merge (`proposal.clj`).
- `upload/` — content-addressed image store (`core.clj`) shelling out to libvips in a child process (`vips.clj`).
- `auth/` — passwordless HMAC magic-link tokens (`core.clj`) + SMTP delivery on a bounded mailer (`email.clj`).
- `jobs/` — durable job queue whose storage IS Datomic; CAS-claimed poll worker.
- `admin/` — privileged dashboard (queries + views). English only.
- `analytics/` — a SEPARATE Datomic db (same transactor/PG) for events; also holds the single-use magic-link nonces.

## Layering rules (do not cross the arrows)
`Caddy → routes/middleware → handler → domain (recipe/auth/upload/jobs) → db → Datomic`; domain returns data, handler passes it to `views`.
- Handlers ORCHESTRATE only: read request, call auth + domain, pick a view. No `d/q`/`d/transact` in a handler — that belongs in the domain.
- The domain OWNS Datomic. It goes through `db/core`'s `transact*`/`pull*`/`q*` (never raw `datomic.api` for reads that return dates or writes that take Instants).
- Views are PURE render: Hiccup data in `web/views.clj` (+ `admin/views.clj`), no DB or side effects. `web/handler.clj` requires `web/views`, never the reverse.
- `config`, `time`, `i18n` are SINGLETONS. Read them anywhere; they require nothing from web/db/domain (keep it that way — they load before the server).

## Adding a feature end-to-end
1. Schema in `db/schema.clj` (installed by `db/core/create-database!` on boot; idempotent).
2. Domain fn in the owning dir (e.g. `recipe/core.clj`) — do reads/writes through `db/core`, gate any externally-supplied id through `entid-owned`/`pull-owned`.
3. Handler in `web/handler.clj` — orchestrate, return `views/...`.
4. View fn in `web/views.clj` — Hiccup, all user text via `(t locale :some/key)`; add the key to BOTH `i18n/en.clj` and `i18n/nl.clj`.
5. Route in `web/routes.clj` — put it under `wrap-auth` (+ `wrap-terms-accepted` for mutations) unless it is deliberately public. Use a `#'var` ref so hot-reload picks it up.
6. Background work? Enqueue a durable job (`jobs/core`), don't spawn a thread. New long-lived subsystem? Start AND stop it in `core.clj`.

## Gotchas
- `core.clj` start order is load-bearing: DBs → libvips check → http-kit → reaper/mailer/worker/GC. The libvips check fails boot LOUDLY so a host missing `libvips-tools` dies at deploy, not on a user's first upload. Mirror every `start-*!` with a `stop-*!`.
- Config is a `delay`; deref (any `get-config`) triggers the one-time load. `app*` is built lazily for the same reason — don't force config at compile time.
- The socket REPL is loopback-only unauthenticated RCE; never bind it off 127.0.0.1 (see `start-repl-server!`).
- `time/with-clock` is atom-based (visible on every thread, including http-kit workers and `future`s) — that is WHY it's not a thread-local `binding`. Don't "fix" it to `binding`.
- i18n `t` falls back locale → default-locale → `(name key)`, so a missing key renders as the bare keyword name, not an error. Add keys to both locale files.
- `analytics/` is disposable EXCEPT the magic-link nonces — recreating it resets single-use replay protection for links in flight.
- Strict AOT (`compile-strict`) fails on ANY reflection/boxed-math warning from myapp code — every ns here sets `*warn-on-reflection*`; type-hint interop and keep arithmetic primitive.

## Running / testing what's here
- Root gates (from repo root): `./reformat` → `./lint` (0 warnings) → `clojure -X:test` → `clojure -T:build compile-strict` → `mdbook build`; e2e via `npx playwright test`.
- Spine tests: `myapp.config-test`, `myapp.time-test`, `myapp.i18n-test`. Subsystem tests mirror the tree (`myapp.db.core-test`, `myapp.recipe.core-test`, `myapp.jobs.core-test`, …). All run on `datomic:mem`, where excision/backup are silent no-ops.
- Dev server: `(myapp.core/start-dev-server)` at a REPL (port 3000). `restart-server!` for a full cycle.

## See also
- Lifecycle/server: ch.5 "Your First Clojure Web Server: Ring, http-kit, and Reitit"; deploy ch.36 "Updates with Minimal Downtime".
- Clock: ch.7 "A Single Source of Time: A Swappable Clock". i18n: ch.12 "i18n in Clojure". Datomic bridge: ch.8.
- Domain: ch.9 "The Recipe Domain". Views/escaping: ch.14 "Server-Rendered HTML with Hiccup". Jobs: ch.47. Uploads: ch.49. Auth: chs.24–25.
- Depends on: `db/core` (all persistence), `web/routes` (the edge). The ROOT `AGENTS.md` owns the global theses/gates/landmines — this file only sharpens the local ones.
