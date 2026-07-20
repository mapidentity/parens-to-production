# AGENTS.md — content-addressed image store + out-of-process libvips interop

> Photos without a bucket: normalize each upload into a WebP *source* keyed by its own SHA-256, store metadata in Datomic, serve lazy `card`/`hero` derivatives, and reap orphans on a clock. All decode/resize runs in a libvips child process, never on the app heap.

## Key files
- `core.clj` (`myapp.upload.core`) — `store!` (validate→normalize→hash→dedup→transact), `ensure-derivative!` (lazy variant gen), `url-for`/`derivative-url`/`variant-dimensions`, orphan GC (`gc-orphans!`, `start-gc!`/`stop-gc!`), boot checks (`check-image-processor!`, `check-uploads-root!`).
- `vips.clj` (`myapp.upload.vips`) — the interop exemplar: `run` (ProcessBuilder), `probe` (`vipsheader`), `thumbnail!` (`vipsthumbnail`), `check!`, `busy?`.

## Conventions / rules
- The content hash is the SHA-256 of the **normalized WebP source bytes**, NOT the uploaded `tmp` bytes. Hash only after `vips/thumbnail!`. This is what makes dedup, immutability, and the integrity check line up.
- Sources are always WebP (`mime->ext`/`ext->mime` only map `webp`); `:upload/content-type` is always `"image/webp"`. Don't add formats without teaching both maps and the loader whitelist.
- Trust nothing from the client: format is decided by `probe`'s libvips loader (`allowed-loaders` = jpeg/png/gif/webp), never Content-Type or filename; paths derive from the hash alone (never the filename — no traversal). SVG/PDF loaders are deliberately excluded (SVG carries script). Keep them out.
- Derivatives are a **whitelist** (`specs`: `card`/`hero`). Never honor client `?w=`/`?h=` — that's a disk-fill DoS.
- `run` argv MUST stay a vector/`java.util.List` handed to `ProcessBuilder`; never join into a shell string. Drain stdout+stderr on their own threads (the `future`s) BEFORE/while `waitFor` — a filled pipe buffer deadlocks otherwise. Release the semaphore in `finally`.
- Pass **absolute** paths to `vipsthumbnail` on both sides: a relative `-o` resolves relative to the input's dir and silently doubles the path (see comment in `thumbnail!`).
- Strict AOT: `*warn-on-reflection*` is set here. Keep the interop hints (`^File`, `^Semaphore`, `^java.util.List`, `^Runnable`, `^ScheduledExecutorService`) and the `(long …)` coercions around size/pixel arithmetic, or the build fails on reflection/boxed-math.

## Gotchas
- Orphan GC is **deferred**, not delete-on-unlink: a shared blob may still be referenced by another recipe (same hash). `gc-orphans!` re-checks `referenced?` against the CURRENT db right before each delete — a re-upload upserts to the same `:upload/hash` entity and re-points at these very bytes. Do NOT drop that second check.
- Saturation is load-shed, not a queue: no `processes` permit within `acquire-timeout-ms` → `run` throws `::busy` → `store!`/`ensure-derivative!` surface `:busy`, which the handler turns into 503 + Retry-After. Preserve that path (`busy?` reads `ex-data`); don't swallow it.
- `max-pixels` (25M) is checked from the header via `probe` BEFORE any raster is expanded — that's the decompression-bomb defense. Keep the check before `thumbnail!`, not after.
- Re-encoding strips EXIF (incl. GPS) and applies orientation via the `[…,strip]` save option + vips autorotation. Don't remove `strip`.
- `check-image-processor!`/`vips/check!` AND `check-uploads-root!` run at server boot (`myapp.core`): a host missing `libvips-tools` — or an uploads root the systemd sandbox didn't grant (`ReadWritePaths=` in the units) — fails the deploy loudly, not a user's first upload. `start-gc!` is likewise wired at boot.

## Running / testing what's here
- Tests: `clojure -X:test` — namespace `myapp.upload.core-test` (drives real ImageIO-written bytes end to end: normalize, dedup, hostile-input rejection, lazy derivative caching, `:busy` shedding, orphan reap).
- REQUIRES `vips`/`vipsthumbnail`/`vipsheader` on PATH (`apt install libvips-tools`) — this suite shells out to the real binary; it will fail without it. datomic:mem is fine (no excision/backup here), but FS deletes/writes are real.
- After edits: `./reformat && ./lint` (0 warnings), then `clojure -T:build compile-strict` (reflection/boxed-math fails the build).

## See also
- Book: ch.49 "Photos Without a Bucket: A Content-Addressed File Store on One Box" (`chapters/49-file-storage.md`).
- Callers: `myapp.web.handler` (`recipe-image-upload` POST with per-user/per-IP `ratelimit/allow?`; `GET /img/:a/:b/:hash/:variant` → `ensure-derivative!`, 503 on `:busy`). Schema: `myapp.db.schema/upload-schema` + `:recipe/image`. Config key: `:uploads-root` (`resources/config.edn`). Caddy serves `/uploads` + `/img` from disk; only a MISS reaches the app.
