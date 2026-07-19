# AGENTS.md — the HTTP layer (Ring/reitit): routes, middleware, handlers, views, security

> Every request enters here. `myapp.web.routes/app` is the Ring entry point; the middleware stack, the route tree, the handlers, the Hiccup views, the CSP/asset pipeline, rate limiting, metrics, and live presence all live in this dir. Most-edited area in the repo — read the local rules before touching it.

## Key files
- `routes.clj` — reitit route tree + the whole middleware stack + auth gates + conditional-GET/ETag. `app*` (a `delay`) composes `base-mw`; `routes` is the tree. Nesting-based auth: `wrap-current-user` (soft, resolves user ONCE → `:user-eid`/`:user-email`/`:admin?`) → `wrap-auth` (hard gate) → `wrap-terms-accepted` → `wrap-admin`.
- `handler.clj` — Ring handlers (one per route). `json-response`, `html`/`html-immutable`, `tenancy-check!`, the magic-link flow, recipe CRUD, proposals, sitemap/robots, admin. Owns the response shapes (200/302/404/409/422/503).
- `views.clj` — Hiccup views. `base-layout` sets `<head>`, the import map, module script order, inline `defn-asset` scripts, and the speculation-rules tag. Rendered with escaping `h/html`.
- `assets.clj` — asset manifest (dev vs prod), `importmap-json`, the hash-based CSP (`csp-header`), and the `defn-asset` macro + `register-inline-script!`.
- `security.clj` — `event!`: one structured line per security event to the `myapp.security` logger. The line format is a fail2ban CONTRACT.
- `ratelimit.clj` — in-process sliding-window-log `allow?`. Single-instance only.
- `metrics.clj` — Prometheus text `render`; `record-request!` (folded in by `wrap-metrics`), `record-email!`, `datomic-callback!`.
- `presence.clj` — live viewer count over SSE (http-kit) + the `defonce` reaper heartbeat.
- `markdown.clj` — CommonMark render with `escapeHtml`+`sanitizeUrls`. THE ONLY sanitized-raw path into a page.
- `toast.js`, `dev-reload.js`, `inspector.js`, `trace-overlay.js` — INLINE scripts emitted from the classpath via `defn-asset` (their bytes are CSP-hashed). NOT the same as the island modules in `/workspace/static/js/` (`dispatcher.js`, `controllers.js`, `viewers.js`, …), which are served as `type=module` and authorized by `'self'`+SRI.

## Conventions / rules
- Route handlers are referenced as vars (`#'handler/foo`) so hot-reload picks up edits without rebuilding the router.
- Route ORDER is load-bearing: the router is built `{:conflicts nil}`, so reitit matches in declaration order. Static paths (`/recipes/new`, `/recipes/reorder`) MUST stay declared before the dynamic `/recipes/:id` reads or they get shadowed. New routes: place accordingly.
- Auth is by NESTING, not per-handler checks: reads are public (outside `wrap-auth`); mutations sit under `wrap-auth` + `wrap-terms-accepted`. A new mutation goes in the authed block. Read `:user-eid`/`:admin?` off the request — do not re-query the user.
- Owner-gated handlers: check `recipe/owned-by?` and, on refusal, call `tenancy-check!` then return the SAME `not-found` a real miss returns. Never leak existence (identical 404 whether the recipe is missing or just not yours).
- Tag a route `:json? true` in its data so `wrap-auth`/`wrap-admin` answer 401/403 JSON instead of an HTML redirect.
- Escaping: views render through escaping `h/html`; string content is auto-escaped (the primary XSS defense). Use `h/raw` ONLY for `markdown/render` output and inline scripts/styles. Never `h/raw` an untrusted string.
- Every ns sets `*warn-on-reflection*`; strict AOT fails on ANY reflection/boxed-math warning. Type-hint interop (`^StringBuilder`, `^java.io.File`, `^UnixOperatingSystemMXBean`, …) and use `^long`/`^double` on arithmetic — match the existing hints when adding interop.
- Security identity is `:client-ip` (set by `wrap-client-ip` from the proxy's `X-Client-IP`, trusted only from loopback), NOT `:remote-addr`. Always pass `:ip` to `security/event!`.

## Gotchas
- **`:mrg`, not `:merge`.** `handler/proposal-parties` returns the merge under key `:mrg` on purpose — a local named `merge` shadows `clojure.core/merge` and reads as a silent nil that lint can't catch. Keep the `mrg` spelling; cover conflict/merge branches with a hard-branch test, not a happy-path one.
- **CSP is hash-based with NO nonce.** A new inline `<script>` is blocked unless its exact bytes are registered: define it with `defn-asset` (which calls `register-inline-script!` and emits via `h/raw` so emitted bytes == hashed bytes), or it's the import map / speculation-rules (already hashed). Adding an inline script any other way, or mutating one's bytes at render time, breaks the page. A new island MODULE instead goes in `/workspace/static/js/` + a `script-tag` in `base-layout` (authorized by `'self'`).
- **ETag validator must be build-stable.** `wrap-conditional-get` keys on `basis-t`+locale+`build-token` (the baked `build-id` resource, per-process UUID in dev). Two instances behind one proxy must share it or ETags flap. Do NOT derive it from the app clock (`myapp.time` is allowed to lie in tests). Conditional GET applies only to anonymous GET HTML; authed pages get `no-store` (bfcache-after-logout); `immutable-cache` is only for point-in-time/diff pages (pure function of basis-t).
- **Metrics:** duration uses `System/nanoTime` (not `myapp.time`); no per-path labels (cardinality budget — URLs carry ids); `render`'s DB read is wrapped in try (a scrape must never 500). `/metrics` has a loopback belt that reads `:remote-addr` (the real peer), but the actual wall is Caddy answering 404 in prod.
- **Presence:** `viewers` is `defonce`; only stream for a recipe that EXISTS (a made-up UUID would mint a permanent registry key — a cardinality leak). The reaper is a `defonce` heartbeat whose tick is try-wrapped (a `ScheduledExecutorService` silently cancels a throwing repeat). Single-process — each box counts its own viewers.
- **Dev-only surface 404s in prod by design:** `/dev/ws` and `/dev/__*` resolve their impl lazily with `requiring-resolve`; the resolve THROWS in prod and the catch turns it into a 404/no-op. The dev inline scripts (`dev-reload`/`inspector`/`trace-overlay`) are only emitted when `hot_reload.clj` is on the classpath. Don't assume any of it exists at runtime.
- `assets/dev?` is detected by resource presence (`hot_reload.clj`), NOT `requiring-resolve` — requiring the hot-reload ns here can deadlock a circular load and silently disable the feature.
- Multipart is scoped to the one upload route (`wrap-multipart`), streams to a temp FILE, and the handler `.delete`s the tmp in a `finally`. Don't move it up to the whole stack.
- `wrap-errors` (styled 500) is the INNERMOST middleware so its page still flows out through CSP/cache; `wrap-panic` is the OUTERMOST belt for failures in the stack itself (plain text, no views).

## Running / testing what's here
From repo ROOT (never cd): `./reformat && ./lint` (0 warnings) → `clojure -X:test` → `clojure -T:build compile-strict` (strict AOT) → e2e `npx playwright test`.
- Unit ns for this dir: `myapp.web.conditional-get-test`, `myapp.web.handler-smoke-test`, `myapp.web.markdown-test`, `myapp.web.presence-test`, `myapp.web.ratelimit-test`, `myapp.web.security-test` (under `test/myapp/web/`). Run one: `clojure -X:test :nses '[myapp.web.security-test]'`.
- These run on `datomic:mem` — excision/backup are silent no-ops there; don't assert on them from a unit test.
- E2E specs that exercise this layer: `e2e/auth.spec.js`, `e2e/recipes.spec.js`, `e2e/uploads.spec.js`, `e2e/observability.spec.js` (see `e2e/AGENTS.md`).

## See also
- Book: ch.5 (Ring/http-kit/reitit), ch.14 (Hiccup views + escaping), ch.15 (the morph dispatcher), ch.20 (progressive enhancement/islands), ch.21 (forms + error re-render), ch.29 (asset pipeline: hashing/SRI/import maps/CSP), ch.31 (conditional GET from basis-t), ch.37 (metrics/access logs/client errors), ch.42 (security events + fail2ban), ch.45 (live presence).
- Depends on siblings: `myapp.recipe.*` (domain + proposals/merge), `myapp.auth.*` (tokens/email), `myapp.db.core`, `myapp.upload.core`, `myapp.i18n`, `myapp.time`, `myapp.admin.*`. Islands + `dispatcher.js` live in `/workspace/static/js/`; the reverse proxy that owns `/metrics`, `/uploads`, `/img`, and static assets in prod is `ops/Caddyfile`.
