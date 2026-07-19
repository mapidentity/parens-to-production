# AGENTS.md — dev-only REPL tooling, live-reload, and the inspector/tracer

> Everything under `dev/` is the developer loop: the REPL entry points, the file-watcher + WebSocket live reload, the two halves of the source inspector (element-metadata load + ClojureStorm construction-view tracer), the render-path benchmark, and demo seed data. It is loaded via the `:dev` extra-path (`:storm`/`:measure` compose on top). **None of it is on the prod classpath.**

## Key files
- `user.clj` — REPL namespace; the commands you drive dev with: `(start!)` `(stop!)` `(restart!)` `(reload!)` `(reset-db!)` `(seed!)` `(fresh!)`.
- `hot_reload.clj` — `start` (the boot orchestrator) + a Java NIO `WatchService` watching `src/` and `static/`; owns the long-lived `tailwindcss --watch` process and routes each change to a browser refresh.
- `dev_reload.clj` — the `/dev/ws` WebSocket relay hub between the browser overlay and the editor (Joyride). Public: `notify-reload!` `notify-css!` `notify-highlight!` `push-open!`. Holds the src/ path trust boundary (`resolve-source-file`).
- `inspector_load.clj` — loads *view* namespaces through `clojure.tools.reader` so Hiccup literals carry `:line`/`:myapp/file` metadata (the reverse index + `data-myapp-src`). `load-all-views!` at boot, `reload-changed!` per save.
- `trace.clj` — the ClojureStorm construction-view tracer (~1200 lines). `wrap-trace` middleware + `record-page`; `get-*-json` back the `/dev/__trace|__flow|__value|__branches|__source|__hiccup|__last-error` endpoints. Reads FlowStorm recordings; instruments nothing itself.
- `bench.clj` — criterium quick-bench + async-profiler flamegraph of `handler/recipe-show` and `recipes-index` against the seeded in-mem DB (ch.32). Run via `:measure`.
- `seed.clj` — `seed!` builds the 4-deep carbonara fork lineage + standalones with real edit history; `seed-if-empty!` is the boot guard.

## Conventions / rules
- **Never `require` a `dev/` ns from `src/`.** The single prod touchpoint is guarded `requiring-resolve` of `trace/*` symbols in `src/myapp/web/routes.clj` — the resolve throws without `:dev`, and every caller catches it as "absent". Keep that shape; do not add a hard require.
- The browser client `trace-overlay.js` lives in `src/myapp/web/` (served by `views.clj`), **not here** — this dir is the server side only.
- A namespace counts as a "view" (gets tools.reader loading + source tags) purely by filename ending in `views.clj`. Name new view nses accordingly or the inspector silently won't tag them.
- Every ns here sets `*warn-on-reflection* true` and does heavy Java interop (NIO, `ProcessBuilder`, FlowStorm). Type-hint new interop — the repo's strict-compile bar is the house style even though `dev/` isn't AOT'd.
- Any browser/editor-supplied path MUST pass through `dev_reload/resolve-source-file` (rejects `..`, confines to `src/` component-wise, `.clj`/`.cljc` only). Never relay an unconfined path.

## Gotchas
- **Storm recording is OFF at boot** and toggled per page render by `record-page`. Do NOT set `flowstorm.startRecording=true` — recording-from-boot is what grew the timeline to ~30GB over a long session. The `-Xmx2g` cap in the `:storm` alias is deliberate (turns a leak regression into a fast OOM instead of eating host RAM).
- `full-navigation?` (`Sec-Fetch-Mode: navigate`, or a non-browser client with no such header) is the **only** eviction point; an in-page morph records but does NOT clear (its region's trace ids stay on screen).
- `trace.clj` only loads under `:storm` (which `classpath-overrides` swaps `org.clojure/clojure` for FlowStorm's compiler). A plain `:dev` REPL has no flow-storm dep, so `hot_reload/start` guards `trace/setup!` on the `clojure.storm.instrumentEnable` system property.
- Tailwind must be launched with `--watch=always` (plain `--watch` exits when stdin closes on a REPL launch). One long-lived process only + a JVM shutdown hook; two writers race `static/styles.css`, so `stop-tailwind-watch!` waits for exit before returning. CSS refresh is debounced ~150ms so the browser never reloads against a mid-rebuild stylesheet.
- `inspector_load/tr-load!` must eval the `ns` form first (to establish aliases) before reading the rest; it has a per-form fail-safe that loads a form plain if the source-tag transform can't eval it — a tags-missing gap is logged, never silent.
- `seed!` is **additive, not idempotent** — re-running piles up duplicate recipes. Use `(fresh!)` / `(reset-db!)` or `seed-if-empty!`. `bench`/`seed` run on `datomic:mem`, so bench latencies are a floor (no transactor round trips).

## Running / testing what's here
- Dev loop: `clojure -M:dev:repl` then `(start!)` (serves `http://localhost:3000`, seeds if empty, starts Tailwind + watcher).
- With the tracer: `clojure -M:dev:storm:repl` then `(start!)`.
- Benchmarks: `clojure -M:dev:measure -m bench` (criterium) / `clojure -M:dev:measure -m bench flame` (flamegraph → `/tmp/clj-async-profiler/results/`).
- No unit tests target `dev/` (dev-only, exercised by driving the loop). `./reformat` and `./lint` DO cover `dev/` — keep it zprint-clean and 0 clj-kondo warnings.

## See also
- Chapters: 06 *Live Reload*, 16 *A Bidirectional Source Inspector*, 17 *The Construction View: Recording a Request with ClojureStorm*, 19 *Tightening the Reload Loop (DOM Morphing + CSS Hot-Swap)*, 32 *The Server Path, Measured*.
- Sibling deps: `src/myapp/web/inspector.clj` (the render-boundary tagger this feeds), `src/myapp/web/routes.clj` (`/dev/__*` routes + `wrap-trace` wiring), `src/myapp/web/trace-overlay.js` + `dev-reload.js` (browser clients), `src/myapp/core.clj` (`start-dev-server`).
