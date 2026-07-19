# AGENTS.md — passwordless magic-link auth + the off-thread mailer

> Issues/verifies HMAC-signed, single-use magic-link tokens, manages user accounts, and delivers login emails without letting a slow SMTP relay touch the request pool. No passwords are ever stored.

## Key files
- `core.clj` — token sign/verify (`sign-token`, `verify-token`), `create-magic-link-token`, user CRUD (`find-user-by-email`, `create-user!`, `get-or-create-user!`), and `set-active!` (the live ban lever). Pure/Datomic; no HTTP.
- `email.clj` — Jakarta Mail send (`send-magic-link!`, `deliver!`) plus the **bounded background mailer** (`start-mailer!`/`stop-mailer!`/`deliver-magic-link!`) that keeps blocking SMTP off the http-kit worker thread.

## Conventions / rules
- **The one-shot replay check does NOT live here.** `verify-token` only checks HMAC + expiry and *extracts* the `:nonce`; it embeds no replay defense. The single-use CAS (`consume-magic-link-nonce!`) runs in `web/handler.clj` against the **analytics** DB (`:magic-link/nonce` / `:magic-link/verified-at`), not the app DB. Don't "helpfully" add a replay check here or move it — you'd double-guard one path and leave the other bare.
- **`verify-token` takes a key OR a seq of keys** — the rotation grace window. The handler passes `[signing-key signing-key-previous]`. Keep this polymorphism (`bytes?` branch); it's what lets the signing key rotate without breaking in-flight 15-min links.
- **`set-active!` is a REPL/runbook lever, not a route.** `wrap-current-user` (in `web/routes.clj`) reads `:user/active?` and bans only on explicit `false?` (a missing flag never bans). If you change the ban semantics, change both sides together and preserve "missing ≠ banned" — a schema gap must not lock out the userbase.
- **Every send outcome must be counted** (`metrics/record-email!` via `send-and-record!`, and the `RejectedExecutionException` path in `deliver-magic-link!`). A silently dropped send is an invisible login outage while `/health` stays green (see ch.37).
- Both files run `(set! *warn-on-reflection* true)` and carry heavy interop hints (`^bytes`, `^String`, `^Mac`, `^Session`, `^ExecutorService`, `^java.util.Base64$Encoder`). Strict AOT fails on any reflection/boxed-math warning — keep the hints when you edit interop.

## Gotchas
- **Constant-time compare is load-bearing.** `verify-token-1` uses `MessageDigest/isEqual` on the raw signature bytes. Do NOT swap it for `=` on the base64 strings — that short-circuits and leaks timing. A malformed base64 signature throws in `base64-decode` and is caught as an invalid token; that's intentional.
- **The mailer is a `defonce` atom tied to server lifecycle** (started in `myapp.core`). `start-mailer!` is idempotent (`swap!` keeps the existing executor) so a dev reload can't stack a second pool. Threads are daemon; the queue is a 100-deep `ArrayBlockingQueue` with `AbortPolicy`.
- **No mailer running → inline send.** `deliver-magic-link!` falls back to a synchronous send when `@mailer` is nil (REPL, e2e/unit stubs) so behaviour is never a silent no-op. Tests that assert on delivery rely on this fallback.
- **Finite SMTP timeouts are the per-send deadline.** `smtp-session` sets connect 5s / read 10s / write 10s because Jakarta defaults them to *infinite*. Don't drop these — an infinite read timeout is how a stalled relay pins a thread forever.
- **Time bridge:** `create-user!` stamps `time/now` (an `Instant`); the handler's nonce CAS uses `time/now-date` (a `java.util.Date`, because Datomic's Peer returns Date). Use the right one for the sink you're writing to.

## Running / testing what's here
- Unit suite: `clojure -X:test` (datomic:mem). Namespaces: `myapp.auth.core-test`, `myapp.auth.email-test`.
- Focused run: `clojure -X:test :nses '[myapp.auth.core-test]'`.
- `core.clj` and `email.clj` each have a `(comment …)` REPL block for manual token/round-trip checks.
- Gate before commit (from repo root): `./reformat` → `./lint` (0 warnings) → `clojure -X:test` → `clojure -T:build compile-strict`.

## See also
- Book: ch.24 *Passwordless Auth Part 1: HMAC-Signed Magic Link Tokens* and ch.25 *Passwordless Auth Part 2: Magic Link Emails and the Full Login Flow*; ch.37 *Legible at Runtime* (the dropped-send-is-invisible thesis); ch.42 *Detect and Respond* (the ban-lever containment story).
- Depends on: `myapp.db.core` (`transact*`, `get-connection`), `myapp.time` (Instant/Date bridge), `myapp.config` (`:signing-key`, `:smtp`), `myapp.web.metrics` (`record-email!`), `myapp.i18n` (`t`). Consumed by: `web/handler.clj` (login flow) and `web/routes.clj` (`wrap-current-user` reads the ban flag).
