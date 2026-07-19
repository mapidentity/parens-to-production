(ns myapp.upload.core
  "Content-addressed image storage — the case that a photo store does not need S3.

  A user upload is NORMALIZED on ingest into a *source* image: decoded, downscaled
  to a bounded long edge, auto-oriented, and re-encoded to WebP. The source — not
  the arbitrary bytes the client sent — is the source of truth, stored on the
  filesystem keyed by its own SHA-256; Datomic holds only the metadata (see
  `myapp.db.schema/upload-schema`). Re-encoding through libvips strips EXIF (the
  GPS tracker in a phone photo) and applies its orientation on the way.

  The same content hash that cache-busts the asset pipeline (ch.29) does three
  jobs: it DEDUPLICATES (one source, one file), makes the blob IMMUTABLE (the name
  is the checksum, so Caddy caches it forever and serves it from disk), and makes
  the filename its own INTEGRITY check.

  Display sizes are DERIVATIVES: named variants (`card`, `hero`) computed from the
  source, cached under `img/…`, and generated lazily the first time each is
  requested — a new size later is a new URL, not a migration. The variant set is a
  WHITELIST; client-supplied dimensions are refused, since `?w=1..10000` is a
  disk-fill DoS.

  Uploads are hostile input, so nothing here trusts the client: the path is
  derived from the hash alone (never the filename — no traversal), the type is
  decided by DECODING (never the declared Content-Type), and a decompression bomb
  is caught by a pixel ceiling read from the header before any raster is expanded.
  The actual decoding and resizing happen out-of-process in libvips
  (`myapp.upload.vips`), so the heavy, hostile-byte work never runs on the app
  heap and a decoder crash cannot take the box with it."
  (:require
    [clojure.java.io :as io]
    [datomic.api :as d]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.time :as time]
    [myapp.upload.vips :as vips])
  (:import
    [java.io File]
    [java.nio.file Files StandardCopyOption]
    [java.security MessageDigest]
    [java.util Date]
    [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(set! *warn-on-reflection* true)

(def max-bytes
  "Upload size ceiling — a belt to Caddy's request-body cap (ch.46)."
  (* 5 1024 1024))

(def max-pixels
  "Decompression-bomb ceiling: a 100KB file can claim to be gigapixels.
  Read from the header BEFORE any raster is expanded, so this bounds the decode."
  (* 25 1000 1000))

(def max-edge
  "Longest-edge ceiling for the stored source.
  A photo larger than this is downscaled on ingest; we never keep the arbitrary
  resolution a client sent."
  2048)

(def ^:private source-quality
  "WebP quality for the stored source — high, because derivatives re-encode from it."
  90)

(def ^:private derivative-quality
  "WebP quality for display derivatives."
  80)

(def ^:private allowed-loaders
  "Accepted libvips loaders — raster formats only.
  The loader is libvips' verdict on the real bytes; the client's Content-Type and
  extension are never consulted. SVG and PDF loaders are deliberately absent (an
  SVG can carry script), as is anything that is not a plain image."
  #{"jpegload" "pngload" "gifload" "webpload"})

(def ^:private mime->ext
  {"image/webp" "webp"})

(def ^:private ext->mime
  {"webp" "image/webp"})

(def specs
  "The whitelist of derivative variants.
  Client-supplied dimensions are NOT honored (that is a disk-fill DoS); only these
  named variants exist. `:cover` fills and attention-crops to an exact box
  (uniform browse tiles); `:fit` shrinks to fit inside a box, aspect preserved
  (the detail view)."
  {"card" {:mode :cover
           :width 400
           :height 300}
   "hero" {:mode :fit
           :width 1200
           :height 1200}})

(def upload-pull
  "Pull pattern for a stored upload."
  [:upload/hash :upload/content-type :upload/size :upload/width :upload/height])

(defn uploads-root
  "The directory blobs live under (from config)."
  ^File []
  (io/file (config/get-config :uploads-root)))

(defn check-image-processor!
  "Prove the libvips image processor is present at startup, or throw.
  Wired into the server boot (see `myapp.core`) so a host missing libvips fails
  the deploy loudly, rather than serving until a user's first upload 500s.
  Returns the libvips version string."
  []
  (vips/check!))

(defn- sha256
  "Lowercase-hex SHA-256 of a byte array — the content address."
  [^bytes b]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md b)
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn stored-path
  "Filesystem path for a source's content hash and extension.
  `root/ab/cd/<hex>.<ext>` — the two-level fan-out keeps any one directory from
  holding millions of files."
  ^File [hex ext]
  (io/file (uploads-root) (subs hex 0 2) (subs hex 2 4) (str hex "." ext)))

(defn derived-path
  "Filesystem path for a named variant of a source.
  `root/img/ab/cd/<hex>/<spec>.<ext>` — the URL path mirrors this exactly, so
  Caddy serves a cached derivative straight from disk and only a MISS reaches
  the app."
  ^File [hex spec-name ext]
  (io/file (uploads-root) "img" (subs hex 0 2) (subs hex 2 4) hex (str spec-name "." ext)))

(defn- move-atomic!
  "Atomically move `src` onto `dest` (same directory), replacing any existing file."
  [^File src ^File dest]
  (Files/move
    (.toPath src)
    (.toPath dest)
    (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE])))

(defn- write-blob!
  "Atomically write `data` to `dest`, unless it is already there.
  Writes to a sibling temp then renames; by content-addressing an existing file
  is the same bytes, so a present `dest` is left as a dedup."
  [^File dest ^bytes data]
  (when-not (.exists dest)
    (.mkdirs (.getParentFile dest))
    (let [part (File. (.getParentFile dest) (str (.getName dest) "." (System/nanoTime) ".part"))]
      (with-open [o (io/output-stream part)]
        (.write o data))
      (move-atomic! part dest))))

(defn url-for
  "The /uploads URL Caddy serves a pulled `:upload` map's SOURCE blob at.
  Rarely linked directly — display goes through derivatives — but it is the
  full-size image for a download link."
  [upload]
  (when-let [h (:upload/hash upload)]
    (when-let [ext (mime->ext (:upload/content-type upload))]
      (str "/uploads/" (subs h 0 2) "/" (subs h 2 4) "/" h "." ext))))

(defn derivative-url
  "The /img URL for a named variant of a pulled `:upload` map (nil if no image)."
  [upload spec-name]
  (when-let [h (:upload/hash upload)]
    (when-let [ext (mime->ext (:upload/content-type upload))]
      (str "/img/" (subs h 0 2) "/" (subs h 2 4) "/" h "/" spec-name "." ext))))

(defn variant-dimensions
  "The [w h] a named variant renders at, from the source's stored dimensions.
  Lets the view set <img width/height> so the layout never shifts, without
  storing a row per derivative."
  [upload spec-name]
  (when-let [{:keys [mode width height]} (get specs spec-name)]
    (let [sw (:upload/width upload)
          sh (:upload/height upload)]
      (when (and sw sh)
        (case mode
          :cover [width height]
          :fit (let [ratio (min 1.0 (/ (double width) (long sw)) (/ (double height) (long sh)))]
                 [(max 1 (int (Math/round (* ratio (double sw)))))
                  (max 1 (int (Math/round (* ratio (double sh)))))]))))))

(defn- geometry
  "The libvips size string for a variant spec: shrink-only fit, or an exact box."
  [{:keys [mode width height]}]
  (str width "x" height (when (= mode :fit) ">")))

(defn store!
  "Normalize and store an uploaded file as a content-addressed source image.

  `tmp` is the `File` the multipart middleware wrote the part to. The bytes are
  validated (size, real-image-by-decode, pixel ceiling), then decoded, downscaled
  to `max-edge`, auto-oriented, and re-encoded to WebP by libvips — that source is
  stored and its metadata transacted. Returns {:upload <pulled map>} on success,
  or {:error <keyword>} on rejection (:empty, :too-large, :not-an-image,
  :unsupported-type, :too-many-pixels, or :busy when the image processor is
  saturated). Idempotent by content: a source that hashes the same reuses one
  file and one entity."
  [conn ^File tmp]
  (try
    (let [size (.length tmp)]
      (cond
        (zero? size) {:error :empty}
        (> size (long max-bytes)) {:error :too-large}
        :else
        (if-let [{:keys [width height loader]} (vips/probe tmp)]
          (if-not (contains? allowed-loaders loader)
            {:error :unsupported-type}
            (if (> (* (long width) (long height)) (long max-pixels))
              {:error :too-many-pixels}
              (let [source-tmp (File/createTempFile "source" ".webp")]
                (try
                  (vips/thumbnail!
                    tmp
                    source-tmp
                    (str max-edge "x" max-edge ">")
                    {:quality source-quality})
                  (let [data (Files/readAllBytes (.toPath source-tmp))
                        hex (sha256 data)
                        {sw :width
                         sh :height}
                        (vips/probe source-tmp)]
                    (write-blob! (stored-path hex "webp") data)
                    @(db/transact* conn
                       [{:upload/hash hex
                         :upload/content-type "image/webp"
                         :upload/size (alength data)
                         :upload/width sw
                         :upload/height sh
                         :upload/created-at (time/now)}])
                    {:upload (db/pull* (d/db conn) upload-pull [:upload/hash hex])})
                  (finally
                    (.delete source-tmp))))))
          {:error :not-an-image})))
    (catch clojure.lang.ExceptionInfo e (if (vips/busy? e) {:error :busy} (throw e)))))

(defn ensure-derivative!
  "Serve a named variant of the source `hex`, generating it on first request.
  Returns {:file <File> :content-type <mime>} in format `ext`, caching the bytes
  to disk; nil for an unknown variant, an unsupported ext, or a missing source;
  the keyword :busy when the image processor is saturated (the caller sheds 503).

  This is the app's role in the on-the-fly cache: in production Caddy serves an
  existing derivative from disk and only a MISS reaches here; in dev (no Caddy)
  every request lands here. The derivative is rendered by libvips to a temp file
  and atomically moved into place, so a concurrent reader never sees half a file
  and a concurrent double-generate is harmless."
  [hex spec-name ext]
  (try
    (let [spec (get specs spec-name)
          mime (get ext->mime ext)]
      (when (and spec mime)
        (let [^File source (stored-path hex ext)]
          (when (.exists source)
            (let [dp (derived-path hex spec-name ext)]
              (when-not (.exists dp)
                (.mkdirs (.getParentFile dp))
                (let [part
                      (File. (.getParentFile dp) (str spec-name "." (System/nanoTime) "." ext))]
                  (try
                    (vips/thumbnail!
                      source
                      part
                      (geometry spec)
                      {:smartcrop? (= (:mode spec) :cover)
                       :quality derivative-quality})
                    (when-not (.exists dp) (move-atomic! part dp))
                    (finally (when (.exists part) (.delete part))))))
              (when (.exists dp)
                {:file dp
                 :content-type mime}))))))
    (catch clojure.lang.ExceptionInfo e (if (vips/busy? e) :busy (throw e)))))

;; ---------------------------------------------------------------------------
;; Orphan collection — DEFERRED, because a blob may be shared
;; ---------------------------------------------------------------------------

(defn- orphans
  "Uploads no recipe references, older than `cutoff` — [[hash content-type] …].
  You cannot delete a blob when one recipe drops it, because content-addressing
  means another recipe may point at the same hash. So orphans are collected on a
  clock: an upload with no incoming `:recipe/image` ref, past a grace period."
  [db ^Date cutoff]
  (d/q
    '[:find ?h ?ct
      :in $ ?cutoff
      :where
      [?e :upload/hash ?h]
      [?e :upload/content-type ?ct]
      [?e :upload/created-at ?ca]
      [(< ?ca ?cutoff)]
      (not [_ :recipe/image ?e])]
    db
    cutoff))

(defn- referenced?
  "True when some recipe currently points `:recipe/image` at the upload `h`."
  [db h]
  (some? (d/q '[:find ?r . :in $ ?h :where [?e :upload/hash ?h] [?r :recipe/image ?e]] db h)))

(defn- delete-tree!
  "Recursively delete a directory (a source's derivative subtree)."
  [^File dir]
  (when (.exists dir)
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f))))

(defn gc-orphans!
  "Reap unreferenced uploads older than `grace-ms`.
  Deletes the source file, its whole derivative subtree, and retracts the entity;
  returns the number actually reaped. Safe to run repeatedly."
  [conn grace-ms]
  (let [cutoff (Date/from (.minusMillis (time/now) (long grace-ms)))
        found (orphans (d/db conn) cutoff)
        reaped (atom 0)]
    (doseq [[h ct] found]
      ;; Re-check against the CURRENT db right before deleting: a re-upload
      ;; between the snapshot above and here upserts to this same entity and may
      ;; have re-referenced it, and content-addressing means the new reference
      ;; points at these very bytes. Skip anything that gained a referrer.
      (when-not (referenced? (d/db conn) h)
        (when-let [ext (mime->ext ct)]
          (io/delete-file (stored-path h ext) true))
        (delete-tree! (io/file (uploads-root) "img" (subs h 0 2) (subs h 2 4) h))
        @(db/transact* conn [[:db/retractEntity [:upload/hash h]]])
        (swap! reaped inc)))
    @reaped))

(defonce
  ^{:private true
    :doc "The orphan-GC scheduler, or nil when stopped."}
  gc
  (atom nil))

(def ^:private gc-grace-ms
  "How long an unreferenced blob is kept before collection (a day)."
  (* 24 60 60 1000))

(defn start-gc!
  "Start the daily orphan sweep if not already running (idempotent).
  The same lifecycle-managed, try-wrapped, daemon sweep as the presence reaper."
  ([] (start-gc! (* 24 60 60 1000)))
  ([period-ms]
   (swap! gc
     (fn [ex]
       (or
         ex
         (doto
           ^ScheduledExecutorService
           (Executors/newSingleThreadScheduledExecutor
             (reify
               ThreadFactory
                 (newThread [_ r] (doto (Thread. r "upload-gc") (.setDaemon true)))))
           (.scheduleWithFixedDelay
             ^Runnable
             (fn []
               (try
                 (gc-orphans! (db/get-connection) gc-grace-ms)
                 (catch Throwable _ nil)))
             period-ms
             period-ms
             TimeUnit/MILLISECONDS)))))
   nil))

(defn stop-gc!
  "Stop the orphan sweep (paired with start-gc!)."
  []
  (swap! gc
    (fn [ex]
      (when ex (.shutdownNow ^ScheduledExecutorService ex))
      nil))
  nil)
