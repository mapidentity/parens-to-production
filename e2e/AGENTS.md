# AGENTS.md — Playwright e2e specs driven against the real Clojure server

> Browser-level proof that the whole stack works end to end: a real magic-link login, the recipe versioning flow, uploads, and the client-error beacon — nothing mocked between the click and Datomic. The config lives at the **repo root** (`/workspace/playwright.config.js`), not here; this dir holds only the `*.spec.js` and `fixtures/`.

## Key files
- `auth.spec.js` — passwordless flow: register (email → magic link → terms → dashboard), returning-user skips terms, logout gates `/dashboard`.
- `recipes.spec.js` — create / edit (→ history + `.diff-add`) / fork (→ lineage), 422 re-render with preserved input + morph-in-place, server-side preview pane, search, "While you were away" activity.
- `uploads.spec.js` — real multipart photo upload → content-addressed `/img/xx/xx/<64hex>/hero.webp` derivative + removal; and a lying-`.png` refused. **Needs libvips** (see Gotchas).
- `observability.spec.js` — synthetic `ErrorEvent` must POST to `/client-error`.
- `fixtures/sample.png` — the real image `uploads.spec.js` feeds via `setInputFiles`.
- `../test/myapp/e2e_server.clj` — the server Playwright auto-starts (`myapp.e2e-server/start!`); read it before touching auth/config assumptions.
- `../playwright.config.js` — `testDir: ./e2e`, `baseURL :9876`, chromium-only (`--no-sandbox`), `webServer` auto-start, `trace: retain-on-failure`.

## Conventions / rules
- **Login goes through the real flow, never a shortcut.** The e2e server runs actual auth (no auto-auth); `email/send-magic-link!` is stubbed to capture into an atom served at `GET /test/emails?to=<addr>`. Get the link there; there is no back door.
- **Always `expect(...'Check your email'...).toBeVisible()` before `getMagicLink`.** That heading is the barrier proving the sign-in POST landed and the email was captured — `getMagicLink` does a single, retry-free fetch and relies on it. Drop the wait and you get a flaky empty-array read.
- **One unique email per test** (`uniqueEmail()` = timestamp+rand). Test isolation depends on it, and the per-email magic-link limit only stays safe because each test owns its address.
- Helpers (`getMagicLink`, `uniqueEmail`, `registerUser`, `createRecipe`) are **copy-pasted into each spec on purpose** — there is no shared module. Keep them identical if you edit one.
- Assert accessible roles/names (`getByRole`), not CSS selectors, except where structure is the claim (`.diff-add`, `#recipe-preview`).

## Gotchas
- **Sign-out is dispatcher-enhanced** (cross-layout response → real `location.assign`). After clicking "Sign out", `await page.waitForURL('/')` before any `page.goto`, or the next nav races and aborts.
- **`uploads.spec.js` requires libvips-tools on the box** (`apt install libvips-tools`). The server normalizes/resizes out-of-process via `vipsthumbnail`/`vipsheader` (`src/myapp/upload/vips.clj`); without it the upload test fails at derivative generation, not in the browser.
- **`reuseExistingServer` is on unless `CI`.** The rate-limit sliding window then spans consecutive local runs — which is exactly why the server raises `handler/ml-per-ip` to 10000. A stale reused server also keeps old captured emails and DB state; restart it if a run behaves oddly.
- **Real CSP + session + locale middleware run** (`wrap-csp` is hash-based, no nonce). Tests inject via `page.evaluate` (bypasses CSP), so that is fine — but any *inline* script you expect the page itself to run must have its hash in the asset manifest, same as prod.
- **Backed by `datomic:mem`** (`myapp-e2e` + `-analytics`), fresh per server boot; excision/backup are silent no-ops here. Uploads land under `target/e2e-uploads`.
- The 422 / preview tests assert **morph, not navigation**: they plant `window.__stillHere` and check it survives, and that focus moves to the first invalid field. Don't "simplify" these into URL-only assertions.

## Running / testing what's here
- From the **repo root**: `npx playwright test` (auto-starts the server; first run needs `npx playwright install chromium`).
- Single spec / test: `npx playwright test e2e/uploads.spec.js` · `... -g "records lineage"`.
- Debug a failure: `npx playwright show-trace` on the retained trace; screenshots are captured only on failure.
- Server startup can take minutes on cold CI (Datomic peer + source compile) — the `webServer.timeout` is 300s by design; don't shorten it.

## See also
- Book: **ch.27 "E2E Testing a Clojure Web App with Playwright"** (`chapters/27-e2e-testing.md`) — the design of this suite and server.
- Uploads pipeline it drives: **ch.49** + `src/myapp/upload/vips.clj`; the routes/middleware under test: `src/myapp/web/{routes,handler}.clj`; deterministic keys from `myapp.test-helpers`.
