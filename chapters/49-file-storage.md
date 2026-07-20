# Photos Without a Bucket: A Content-Addressed File Store on One Box

Forty-eight chapters in, the recipe app has versions, forks, diffs, three-way merges, and live presence, and not one photograph. It is the most conspicuous gap in a cooking site, and it is missing for a reason: user files are the canonical excuse to reach off the box. "You can't put uploads on a single server" is received wisdom, and the reflex is a bucket -- S3, or one of its clones. This chapter takes the other position and builds it: a photo store that lives on the disk the box already has, architected so that *correctly* is the operative word. Then it draws the honest line, the narrow band where a managed object store actually earns its bill, because the case for staying on the box is only worth making if you also say exactly where it ends.

A bucket, stripped of its branding, is a filesystem with a REST API and a monthly invoice. A single box already has a filesystem -- a faster one, on the same machine as the code, with no network hop and no egress meter. The question is not whether the disk can hold photos; it plainly can. The question is whether you can architect the store so that it deduplicates, survives a restart, serves without touching the heap, cleans up after itself, and grows past one disk when it must. Answer those and the bucket was solving a problem you don't have yet.

## The bytes go on disk; the metadata goes in Datomic

The first decision is the one people get backwards: what goes *in* the database. Not the bytes. A photo is a two-megabyte blob with no query surface -- you never ask Datomic "which recipes have a JPEG whose 400,000th byte is 0x7A." Putting blobs in the transactor makes every backup, every index, and every memory budget carry ballast the database can do nothing with. Blobs are to the transactor what [presence was to the request path](45-live-presence.md): real state, in the wrong home.

So the split is clean. The **bytes** live on the filesystem. The **metadata** -- hash, content type, dimensions, size, timestamp -- lives in Datomic, one small entity per image, joined to a recipe by a plain reference:

```clojure
{:db/ident :recipe/image, :db/valueType :db.type/ref, :db/cardinality :db.cardinality/one}
;; the upload entity, keyed by content hash:
{:db/ident :upload/hash, :db/valueType :db.type/string, :db/unique :db.unique/identity}
```

The database knows *about* every photo and holds *none* of them. That is the same division of labor the [conditional-GET chapter](31-conditional-get.md) leaned on: let the database keep the facts it can reason over, and let the cheaper tier hold the bulk.

## The name is the hash

Given bytes on a disk, the only real question is what to call the file. The answer is the one the [asset pipeline](29-asset-pipeline.md) already committed to for CSS and JS: **name it after its own SHA-256.** A photo becomes `uploads/ab/cd/<hash>.webp`, where `ab/cd` is a two-level fan-out so no single directory holds a million entries. That one choice pays for three properties at once:

- **Deduplication is free.** The same image uploaded twice hashes to the same name and is one file. A popular recipe forked five hundred times with its photo intact costs one blob, not five hundred.
- **The blob is immutable.** The name *is* the checksum, so the bytes at a given path can never change. That is exactly the precondition for `Cache-Control: immutable`, so the file caches forever in every browser and proxy between you and the reader.
- **The name is its own integrity check.** Re-hash the bytes and you have verified them; a corrupted or tampered blob announces itself. And the path is computed from the hash alone, never from anything the client sent, which is the first half of the answer to a hostile upload.

## Never trust the upload: validate by decoding

The second half is that everything about an upload is a claim by an attacker until proven otherwise. The declared `Content-Type` is a lie waiting to happen; the filename is a directory-traversal attempt (`../../etc/...`) waiting to happen. Neither is ever consulted. The path comes from the hash. The type comes from **reading the bytes** and asking the decoder what it actually found:

```clojure
;; libvips names the REAL format from the bytes; Content-Type and filename
;; are never consulted. vipsheader reads only the header — no full decode.
(vips/probe tmp)  ;; => {:width 4032 :height 3024 :loader "jpegload"}  (or nil)
```

That `:loader` is libvips' verdict, and it is checked against a whitelist of raster formats -- `jpegload`, `pngload`, `gifload`, `webpload`, and no others. `svgload` is deliberately absent (an SVG is a document that can carry script, not a picture), as is anything that is not a plain image.

The header read also returns the dimensions, and that closes a real hole: the **decompression bomb**. A hundred-kilobyte PNG can declare itself 50,000 × 50,000 pixels and, expanded, would try to become ten gigabytes of raster. Because `vipsheader` reads only the header, that pixel count is known *before* any raster is expanded, and a pixel ceiling rejects the bomb while it is still small on disk. A byte-size cap sits in front of that as a cheap belt, and it is the app's cap that is the real contract: [Caddy's request-body wall](35-going-live.md) stays at its site-wide 2 MB for every route *except* this one, which gets the 5 MB contract plus multipart-envelope headroom -- so an abusive body is refused at the door, while a legitimate 4 MB phone photo reaches the app and, when *over* the limit, gets the app's translated error instead of Caddy's bare 413. (The first cut of this chapter left the site-wide cap in front of the upload route; review caught photos between 2 and 5 MB dying at the edge. Two walls only compose if the outer one is wider.)

## Store a source, not the user's bytes

Here is where the design earns the word *correctly*, and where an early draft was wrong. The tempting move is to keep the file the user sent. Don't. Keep it and you have built your store on whatever a phone, a scanner, or a hostile client chose to emit: a 48-megapixel JPEG, a PNG dragged out of a screenshot tool, a photo with the GPS coordinates of someone's kitchen baked into its EXIF. You would be depending on input you do not control, forever.

So nothing the client sent is ever stored. On ingest we **normalize** into a *source* image: libvips decodes it, applies its EXIF orientation, caps the long edge (2,048 px is plenty for any view a recipe page has), and re-encodes to WebP. The orientation is baked into the pixels and everything else -- including the GPS tracker in a phone's EXIF -- is dropped on the way. *That* image, addressed by *its* own hash, is the source of truth: bounded, sanitized, predictable. The arbitrary original is discarded the moment it is normalized.

Which means the UI owes the user one honest sentence, set before they choose a file:

> JPEG, PNG, or GIF, up to 5 MB. We'll scale large photos down to 2048px and make the sizes we need.

No surprise, no silent mangling: you told them it would be scaled, and it is.

## Sizes are derivatives, generated on the fly

A recipe page wants a big hero image; the browse grid wants a small square tile. Shipping the 2,048-px source into a 400-px slot wastes bandwidth on every card. So the source is never displayed directly -- the views ask for **derivatives**: named variants computed from the source.

Two rules make this safe and cheap. First, the variants are a **whitelist** -- `card` (a 400×300 attention-crop, where libvips picks the interesting region rather than the middle) and `hero` (fit within 1,200 px, aspect kept) -- and *only* those. Honoring client-supplied dimensions (`?w=1..10000`) would be a disk-fill DoS: an attacker requests ten thousand sizes and you generate ten thousand files. A fixed set of named variants has no such surface.

Second, derivatives are generated **lazily** and cached as content on disk, and this is where a single box quietly does what teams pay a service for. The variant's URL is derived from the source hash, so an existing derivative is a plain file. [Caddy](35-going-live.md) serves it straight from disk if it is there, and only a *miss* falls through to the app, which renders the variant, writes it, and returns it:

```caddyfile
handle /img/* {
    root * /mnt/data/uploads
    @variant file
    handle @variant { header Cache-Control "...immutable"; file_server }   # a hit: disk, no app
    handle { import app_upstream }                                          # a miss: app renders once
}
```

The consequences are worth stating plainly. Every derivative is generated at most once and served from disk forever after. Adding a new size next year -- a `thumb`, a retina `hero@2x` -- is a new URL, not a migration and not a batch job: the first request for it materializes it, and the rest are disk hits. Purging is `rm -rf` on the derivative tree; it repopulates itself on demand. This is precisely what Cloudflare Images, imgproxy, and S3-plus-Lambda@Edge sell as a product, and here it is a Caddy fallback, a whitelist, and the filesystem.

## The tool for the job is not on the JVM

Which raises the question the whole feature turns on: *what* renders those derivatives? The path of least resistance is the JDK's own `ImageIO`, and it is the wrong choice, because image processing is a poor tenant for the request-serving JVM, for three concrete reasons:

- **Formats.** The JDK encodes JPEG, PNG, and GIF, and nothing modern -- no WebP, no AVIF. Serving JPEG-only in this decade leaves a real bandwidth win unclaimed.
- **Memory.** `ImageIO` decodes the *entire* raster to heap: a 25-megapixel photo is ~100 MB of `int[]`, transiently, on the one heap that serves every other request. A streaming library holds a few MB.
- **Correctness papercuts.** Phone photos store their orientation in EXIF; strip the metadata without first *applying* it and every portrait shows sideways. The JDK makes you hand-parse the tag out of hostile bytes; the right tool has a flag.

The right tool is **libvips** -- a streaming C library that resizes in a few megabytes, speaks WebP and AVIF, and auto-rotates with a switch. Reaching for it means reaching past the JVM for a native dependency, and this is the place to be plain about that: the book's *one jar* thesis is about the deploy artifact, not a vow of purity. The jar stays a jar; the box gains one `apt` package, exactly as it already gained Caddy and PostgreSQL. A native dependency is just a dependency -- one you opt into with strong reasons, and image processing is precisely the strong reason. The dogmatic reading of "no native libraries" would have us decode hostile gigapixels on the request heap to honor a rule the deployment never actually needed.

So we use libvips, and -- the deliberate part -- we use it **out of process**. Not an in-JVM binding sharing our address space, but a `vipsthumbnail` child whose raster lives and dies in its own memory, and whose *crash* dies with it: a decoder segfault on a malicious byte fails one request, not the JVM that is the whole application. The shipped render is the call the JDK could not make:

```bash
# shrink-to-fit, auto-orient, strip metadata, encode WebP — one child process
vipsthumbnail source.webp --size "1200x1200>" -o "hero.webp[Q=80,strip]"
```

### Interop, done deliberately

Shelling out is where careless code earns a CVE, so the wrapper around that call is the careful part, in four specific ways -- each a trap that bites in production, not in the demo:

- **The arguments are a vector, never a string.** `(ProcessBuilder. [bin in "--size" geo …])` hands the OS an `argv` directly; there is no shell to parse it, so a filename containing `; rm -rf ~` is a filename, not a command. The most common shell-out vulnerability simply cannot occur, because there is no shell.
- **stdout and stderr are drained concurrently.** A child that fills the pipe buffer blocks on write while a parent that only reads *after* `waitFor` blocks on wait -- a textbook deadlock. Two threads read the streams *while* we wait, so neither side can wedge the other.
- **Every call has a hard timeout.** Past it the child is `destroyForcibly`-killed and the call throws, so a pathological image that sends the decoder into a spin cannot pin a worker forever.
- **A semaphore bounds how many children run at once, and sheds load past it.** The memory and CPU are the box's to spend now, not one heap's, so the concurrency cap lives with the thing being capped. Acquiring a slot is a *bounded* wait, not an open-ended block: a short queue absorbs a transient burst, but once the box is saturated a call gives up its wait and refuses rather than piling request threads up behind it. That refusal surfaces honestly -- `/img` sheds a cache-miss as `503` with `Retry-After`, and an upload that can't get a slot comes back with a "we're busy, try again" message instead of a spinner that never resolves. Backpressure, not a silent stall.

That wrapper is one small namespace, and each of those four points is a line of code, not a paragraph of hope. Native interop is not scary; it is *specific*. The architecture around it -- content-addressed source, lazy cached derivatives, Caddy-served -- never touches the JVM heap for the pixels at all; the heavy, hostile-byte work happens in a child that the OS can kill.

## Served by Caddy, gated only when it must be

Notice what never happened above: a photo request never reached the application. Both the sources (`/uploads/*`) and the derivatives (`/img/*`, on a hit) are served by Caddy straight from the state volume, with an immutable cache header, never proxied. The app's job is to *accept* an upload and to *render* a derivative on the first miss; serving bytes is the web server's job, and it does it without waking a single thread of the JVM.

That is safe here because recipe photos are public -- the recipes are public reads. When uploads are *private*, the same architecture holds with one addition: Caddy's `forward_auth` asks the app for a yes/no before serving the file, so access is gated while the bytes still never touch the heap. This app does not need it, so it does not have it, but the seam is one directive wide.

## Deletion is deferred, because a blob can be shared

Removing a photo cannot delete its file, and the reason is the deduplication that content-addressing bought us. If two recipes point at the same hash and one drops its photo, deleting the blob would blank the other. So an unlink retracts only the *reference*; the bytes stay. Collecting them is a separate, deferred job -- the same lifecycle-managed, daemon-threaded, idempotent sweep as [the presence reaper and the mailer](46-watching-the-watchers.md): once a day, find every upload with no incoming `:recipe/image` reference that is older than a grace period, and delete the source, its derivative subtree, and the metadata entity.

```clojure
;; an orphan: referenced by nothing, past the grace window
(d/q '[:find ?h :in $ ?cutoff :where
       [?e :upload/hash ?h] [?e :upload/created-at ?ca] [(< ?ca ?cutoff)]
       (not [_ :recipe/image ?e])] db cutoff)
```

The grace period matters for more than caution: it is what makes backup consistent without a lock. Because a blob is always written *before* the transaction that references it, and is deleted only long *after* it goes unreferenced, a backup that copies the blob tree at or after the database snapshot always contains a superset of what the database points at. Restore the DB and the files, and every reference resolves. So [the nightly backup](39-backup-restore.md) grew a few lines: an `rsync` of the uploads tree into the backup directory, *after* `backup-db` runs, which the content-addressed names make near-free -- an immutable blob never needs re-copying, so only new photos move. The mirror deliberately never deletes: a blob the GC reaped may still be referenced by an *older* database backup, and a restore from that backup wants it -- prune the mirror the day you prune old database backups. And when the backup target is off-box (`BACKUP_URI=s3://…`), the tree rides the same channel as the database: `backup-db` writes s3 natively, and the script `aws s3 sync`s the uploads and config alongside it, so the copy that survives the box is the *whole* box -- or the script fails loudly, because a backup that quietly protects the database but not the photos is exactly the "we think we have backups" trap [chapter 39](39-backup-restore.md) opens on. (The restore drill of chapter 39 proves the database comes back; the photo tree's restore is a file copy, reasoned here, not separately drilled.)

The grace window has a sharp edge worth naming: it means an orphaned source lingers on the *shared* state volume -- the same disk Datomic and the backups live on -- for as long as the grace lasts. Left unbounded, that is a disk-exhaustion DoS: an authenticated user loops the upload with a byte of noise appended each time, so every image is a distinct hash that dedup cannot collapse, and the volume fills faster than the daily sweep drains it. When the disk is full the transactor cannot write, and a failed *photo* becomes a failed *application*. So the write path is bounded like every other unbounded resource in this book -- the upload handler is rate-limited per user and per IP, the same posture as [the magic-link sender](25-auth-email-flow.md) and the report sinks. Uploads are rare and legitimate ones stay far under the ceiling; a flood hits the wall long before it touches the disk.

## How far this actually scales, in one place

Everything above runs on one disk. The honest question -- the one [the scaling audit](41-beyond-one-box.md) insists on -- is what happens when one disk is not enough, and the honest answer is that "one disk" is a much higher ceiling than the reflex assumes, with several rungs between it and a managed bucket:

1. **One disk.** A modern volume is measured in terabytes; at a couple of megabytes a photo, that is a million-plus images before you have thought about it. Most applications that were told they needed S3 never leave this rung.
2. **A bigger disk.** Storage is the one resource you can grow by an order of magnitude with a downtime window and a `resize2fs`. Boring, and it buys years.
3. **A shared network filesystem.** Mount NFS or CephFS at `/mnt/data/uploads` and every app instance sees the same tree. Nothing in this chapter changes: the paths are identical, and immutable content-addressed names mean there is no cache to invalidate across the mount.
4. **One writer, many readers.** Ingest is rare; reads are not. Let one node own writes and replicate the tree read-only to the servers that only serve `/img`. Immutable names make replication trivially correct -- a file that exists is final, so "eventually replicated" is as good as "replicated."
5. **Shard across boxes by hash.** The name is a hash, so the first byte already partitions the space: `0–7` to one box, `8–f` to another. A content-addressed store is a distributed hash table you can grow one box at a time, and the routing is a prefix, not a lookup table.
6. **Self-hosted S3-compatible.** When you want a bucket's *API* without a bucket's *bill* -- presigned URLs, lifecycle rules, an object interface other tools expect -- MinIO, SeaweedFS, or Garage give you exactly that on your own hardware. The content-addressed key becomes the object key unchanged.
7. **Managed S3.** The top rung, and a genuinely good one -- for a genuinely narrow case: you need multi-region durability or a compliance boundary you would rather rent than run, or you are already all-in on a cloud and the egress math favors it, or your scale has made running rungs 3–6 a team's full-time job. That is a real place to be. It is a *last* rung, not a *second* one.

The thing that makes rungs 3 through 6 clean -- the reason this ladder is climbable at all -- is the choice made at the very top of the chapter. **Immutable, content-addressed names have no cache-invalidation problem, because the name is the version.** Replication, sharding, and CDN caching are all hard exactly when a name can point at changing bytes; here it never can. The architecture that let one box serve photos well is the same architecture that lets many boxes serve them, which is why "start on the box" is not a corner you code yourself into.

## Trade-offs & limitations, in one place

- **libvips is a box dependency.** The price of the native tool, paid on purpose: the box, CI, and the dev environment all need `libvips-tools` on `PATH`, and the app will not process an upload without it. The deploy artifact is unchanged (still one jar), but "runs on a bare JVM host" is no longer true -- an honest cost, and one `apt install` line in each of the three places. The app *proves* the dependency at boot: startup shells `vipsthumbnail --version` and refuses to start without it, so a host that forgot the package fails its health check at deploy time (the [pair deploy](36-minimal-downtime.md) keeps the old instances serving) rather than 500-ing on a user's first photo. Fail-fast, where the book fails fast everywhere else.
- **The writable directory is a sandbox grant, not a given.** [The hardened units](43-harden-patch.md) run under `ProtectSystem=strict`, where every writable path is granted by literal name -- and the uploads root is exactly such a grant (`ReadWritePaths=`), one the first cut of this feature forgot: reviewed against the units as shipped, every upload would have died on a read-only filesystem. The fix is one line in each app unit plus a second boot check beside the libvips one: startup writes and deletes a probe file in the uploads root and refuses to start if it cannot, so a forgotten grant -- or an unprovisioned directory -- fails the deploy, not a user's photo. Reasoned and fixed at the unit level; the sandbox itself is still [verified as syntax, not fault-injected](43-harden-patch.md).
- **AVIF and content-negotiation are the next format step, not this one.** We ship WebP to everyone, which ~97% of browsers take and the rest tolerate via the `<img>` fallback path. AVIF is smaller still; serving it means adding an `avif` variant and a `Vary: Accept` negotiation so each client gets the best format it supports. libvips already encodes it -- this is a variant and a header, deliberately deferred, not a re-architecture.
- **HEIC and RAW inputs are refused.** The accept-whitelist is `jpeg/png/gif/webp`; a phone that uploads HEIC is rejected with a readable error rather than half-supported. Accepting it is one more loader in the whitelist *once the box's libvips is built with libheif* -- a provisioning decision, surfaced rather than assumed.
- **No virus scanning.** A public site that lets strangers upload files should run the bytes past ClamAV before they are served. It slots in exactly where validation already sits (probe, then scan, then normalize) and is left out here only because the demo's uploads are images bound for `<img>`, never downloads.
- **Dedup is content-exact, not perceptual.** Two visually identical photos that differ by one byte are two blobs. Perceptual dedup is a different, much harder feature, and not one a recipe site needs.
- **A CDN is still a config line, not a rewrite.** Immutable URLs are the ideal CDN origin; putting one in front is a DNS change and a cache rule, and nothing in the app changes. Staying on the box does not foreclose the edge -- it just declines to *require* it.

## The wager, once more

The recipe app has photos now, and it got them without a bucket, a broker, or a bill. The bytes are on the disk the box already had, named by their own hash so they deduplicate and cache forever; the database knows about every image and holds none; uploads are decoded before they are trusted and normalized before they are kept; display sizes are generated once and served by Caddy without waking the app; deletion is deferred because a shared blob cannot be unlinked in place; and the whole store climbs a ladder of six rungs before a managed object store is the honest answer rather than the reflexive one. The reflex says user files are where the single box finally breaks. They are, instead, one more thing the box you already run does well -- right up to a scale you can see coming from a long way off, and name before you get there.
