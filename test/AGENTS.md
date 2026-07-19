# AGENTS.md — the JVM test suite (unit + integration + e2e/lighthouse entrypoints)

> Every `clojure.test` ns for the app, plus the two real-server entrypoints Playwright/Lighthouse boot. Namespaces mirror `../src/myapp` with a `-test` suffix; run with `clojure -X:test`. Fixtures give each test a fresh `datomic:mem` DB and deterministic config.

## Key files
- `myapp/test_helpers.clj` — the shared kit. Fixtures `with-test-db`, `with-test-analytics-db`, `with-test-config`; dynamic `*conn*` / `*analytics-conn*`; Ring builders `request` / `auth-request`; `test-config`, `test-signing-key`, `test-session-key`.
- `myapp/web/handler_smoke_test.clj` — the largest suite (27 tests): handlers called as plain fns, asserting `:status` / `:headers` / `str/includes?` on `:body`.
- `myapp/web/security_test.clj` — escaping, markdown sanitize, strict CSP, and `capture-security` (redefs `clojure.tools.logging/log*`) that asserts the `SECURITY event=… ip=… reason=…` line shape fail2ban depends on.
- `myapp/upload/core_test.clj` — writes REAL images (ImageIO) and processes them through libvips; content-addressing, derivatives, GC.
- `myapp/auth/email_test.clj` — embedded GreenMail SMTP (`with-greenmail`), asserts subject/body/i18n.
- `myapp/jobs/core_test.clj` — test-only `defmethod jobs/run-job :test/echo` / `:test/boom` drive the queue.
- `myapp/e2e_server.clj` — real-auth e2e server (`start!`), NOT a test ns: stubs `send-magic-link!` into the `sent-emails` atom, exposes `/test/emails`, raises `handler/ml-per-ip`. Booted by Playwright's `webServer`.
- `myapp/lighthouse.clj` — Lighthouse CI server entry (auto-auth); test classpath only.

## Conventions / rules
- Top of every ns: `(set! *warn-on-reflection* true)` and type-hint interop (`^File`, `^bytes`, `^Date`) — match it.
- `(use-fixtures :each …)` in dependency order: `h/with-test-db h/with-test-analytics-db h/with-test-config`. Compose only what you need (db/jobs/upload use just `with-test-db`).
- Get the connection via `h/*conn*` only — never a global. It is a fresh in-mem DB per test, torn down after.
- Build requests with `h/request` / `h/auth-request` (the latter resolves `:user-eid` from the DB, mirroring `wrap-auth`). For mutation handlers that need the eid before auth would run, also `(assoc … :user-eid u)` from the `create!` return.
- Reach privates through the var: `#'config/resolve-keys`, `#'assets/build-csp-header`, `#'analytics/db/convert-instant`.
- Adding a security event? Assert its line in `security_test` via `capture-security` (the fail2ban contract).
- Determinism: use `time/with-clock` + `time/fixed-clock` for window/expiry tests — never `Thread/sleep`. Tests that mutate shared process state (`assets/manifest` atom, the rate limiter) save-and-`reset!` in `try/finally` or `clear!`.

## Gotchas
- **Rate limiter is process-global.** Any test that touches an upload/auth throttle must call `(myapp.web.ratelimit/clear!)` first (see `ratelimit_test`'s clear!/f/clear! fixture), or budget bleeds across tests and you get flaky failures.
- **Silent-nil bugs pass happy-path tests.** The `mrg`-written-as-`merge` class (a local shadowing a core fn → nil) survives every green render. Guard with a hard-branch/conflict-path test — see `handler_smoke_test/proposal-conflict-accept-through-handlers`, the literal regression for that bug.
- **`datomic:mem` cannot rehearse excision, backup/restore, or failover** — they are silent no-ops on mem storage. Do NOT try to test them here; they are drilled against real SQL storage / the ops lab (ch.39). This suite covers everything *else*.
- **libvips required.** `upload/core_test` and the photo paths in `handler_smoke_test` throw at `check-image-processor!` without `vips`/`vipsthumbnail`/`vipsheader` on PATH (`apt install libvips-tools`).
- **Peer returns `java.util.Date`.** Write `java.time.Instant`, read it back through the `db.core` wrappers; assert the round-trip (`db/core_test`, `analytics/convert-instant`), never a raw `Date` off a bare `d/q`.
- **e2e config mirrors prod on purpose:** `:conflicts nil` (route declaration-order resolution) and `:same-site :lax` (magic-link GET keeps its session). Don't "tighten" either. The email stub's `/test/emails` clear-all (no `?to=`) is only safe in serial runs.

## Running / testing what's here
- Whole suite: `clojure -X:test` (in-mem Datomic, from repo root).
- Narrow: `clojure -X:test :nses '[myapp.web.security-test myapp.upload.core-test]'` (also `:vars`, `:patterns` — cognitect test-runner).
- Coverage gate: `clojure -M:coverage` (Cloverage, ≥50%).
- E2E: `npx playwright test` — auto-starts `clojure -X:test myapp.e2e-server/start!` on :9876; specs live in `../e2e/*.spec.js`.

## See also
- Ch.11 *Testing a Clojure App: Fixtures, Helpers, and Coverage* (`chapters/11-unit-testing.md`) — this directory's charter.
- Ch.27 *E2E Testing a Clojure Web App with Playwright* (`chapters/27-e2e-testing.md`) — `e2e_server.clj` + `../e2e`.
- Ch.39 *Backup, Restore, and the Drill* (`chapters/39-backup-restore.md`) — what mem can't rehearse.
- Depends on `../src/myapp` (code under test) and the root gates (`./reformat`, `./lint`, `compile-strict`).
