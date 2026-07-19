(ns myapp.upload.core
  "Content-addressed image storage — the case that a photo store does not need S3.

  A user upload is NORMALIZED on ingest into a *master*: decoded, downscaled to a
  bounded long edge, and re-encoded to a canonical format. The master — not the
  arbitrary bytes the client sent — is the source of truth, stored on the
  filesystem keyed by its own SHA-256; Datomic holds only the metadata (see
  `myapp.db.schema/upload-schema`). Re-encoding from the decoded raster strips
  EXIF (the GPS tracker in a phone photo) for free.

  The same content hash that cache-busts the asset pipeline (ch.29) does three
  jobs: it DEDUPLICATES (one master, one file), makes the blob IMMUTABLE (the
  name is the checksum, so Caddy caches it forever and serves it from disk), and
  makes the filename its own INTEGRITY check.

  Display sizes are DERIVATIVES: named variants (`card`, `hero`) computed from the
  master, cached under `img/…`, and generated lazily the first time each is
  requested — a new size later is a new URL, not a migration. The variant set is a
  WHITELIST; client-supplied dimensions are refused, since `?w=1..10000` is a
  disk-fill DoS.

  Uploads are hostile input, so nothing here trusts the client: the path is
  derived from the hash alone (never the filename — no traversal), the type is
  decided by DECODING (never the declared Content-Type), and a decompression bomb
  is caught by a pixel ceiling read from the header before any raster is expanded.
  Raster work is heavy and memory-shaped nothing like request serving, so it is
  serialized through a small semaphore — concurrent decodes cannot stack on the
  one heap and OOM the box. That heaviness is also the argument (ch.49) for
  graduating this to an out-of-process libvips subprocess."
  (:require
    [clojure.java.io :as io]
    [datomic.api :as d]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.time :as time])
  (:import
    [java.awt Graphics2D RenderingHints]
    [java.awt.image BufferedImage]
    [java.io ByteArrayOutputStream File]
    [java.nio.file Files StandardCopyOption]
    [java.security MessageDigest]
    [java.util Date]
    [java.util.concurrent Executors ScheduledExecutorService Semaphore ThreadFactory TimeUnit]
    [javax.imageio IIOImage ImageIO ImageReader ImageWriteParam ImageWriter]))

(set! *warn-on-reflection* true)

(def max-bytes
  "Upload size ceiling — a belt to Caddy's request-body cap (ch.46)."
  (* 5 1024 1024))

(def max-pixels
  "Decompression-bomb ceiling: a 100KB file can claim to be gigapixels.
  Read from the header BEFORE any raster is expanded, so this bounds the decode's heap."
  (* 25 1000 1000))

(def max-edge
  "Longest-edge ceiling for the stored master.
  A photo larger than this is downscaled on ingest; we never keep the arbitrary
  resolution a client sent."
  2048)

(def ^:private jpeg-quality
  "JPEG re-encode quality for opaque masters and their derivatives."
  0.82)

(def ^:private allowed
  "Decoded formats we accept.
  The DECODER names the format; the client's Content-Type is never consulted.
  Masters are always re-encoded to jpeg/png."
  #{"jpeg" "png" "gif"})

(def ^:private mime->ext
  {"image/jpeg" "jpg"
   "image/png" "png"})

(def ^:private ext->mime
  {"jpg" "image/jpeg"
   "png" "image/png"})

(def specs
  "The whitelist of derivative variants.
  Client-supplied dimensions are NOT honored (that is a disk-fill DoS); only these
  named variants exist. `:cover` scales to fill and center-crops to an exact box
  (uniform browse tiles); `:fit` scales to fit inside a box, aspect preserved
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

;; ---------------------------------------------------------------------------
;; Bounded raster work — heavy, so serialized through a small permit pool
;; ---------------------------------------------------------------------------

(def ^:private ^Semaphore transform-permits
  "Serialize heavy raster work so it cannot OOM the box: one decode at a time.
  A full-image decode expands the whole raster to heap (a 25 MP source is ~100 MB
  of int[]), and the resize holds source and destination at once. Ingest is rare
  and derivatives generate once, so a single permit bounds the transient raster
  to one worst-case decode instead of letting concurrent uploads stack them."
  (Semaphore. 1))

(defn- with-permit
  "Run `thunk` holding a transform permit, bounding concurrent raster work."
  [thunk]
  (.acquire transform-permits)
  (try
    (thunk)
    (finally (.release transform-permits))))

(defn- has-alpha?
  "True when the image carries an alpha channel (so it must stay PNG, not JPEG)."
  [^BufferedImage img]
  (.hasAlpha (.getColorModel img)))

(defn- render-resized
  "Resize `src` to `nw`x`nh` with quality hints, preserving any alpha channel."
  ^BufferedImage [^BufferedImage src nw nh]
  (let [kind (if (has-alpha? src) BufferedImage/TYPE_INT_ARGB BufferedImage/TYPE_INT_RGB)
        dst (BufferedImage. (int nw) (int nh) kind)
        ^Graphics2D g (.createGraphics dst)]
    (doto g
      (.setRenderingHint
        RenderingHints/KEY_INTERPOLATION
        RenderingHints/VALUE_INTERPOLATION_BICUBIC)
      (.setRenderingHint RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
      (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.drawImage src 0 0 (int nw) (int nh) nil)
      (.dispose))
    dst))

(defn- clamp-round
  "Round `x` to the nearest int, floored at 1 — a primitive result, no boxing."
  ^long [^double x]
  (Math/max 1 (Math/round x)))

(defn- scale-to-max
  "Downscale `src` so its longest edge is at most `edge`; never upscale."
  ^BufferedImage [^BufferedImage src ^long edge]
  (let [w (.getWidth src)
        h (.getHeight src)
        longest (Math/max w h)]
    (if (<= longest edge)
      src
      (let [ratio (/ (double edge) longest)]
        (render-resized src (clamp-round (* ratio w)) (clamp-round (* ratio h)))))))

(defn- fit-within
  "Scale `src` to fit inside `bw`x`bh`, aspect preserved; never upscale."
  ^BufferedImage [^BufferedImage src ^long bw ^long bh]
  (let [w (.getWidth src)
        h (.getHeight src)
        ratio (Math/min 1.0 (Math/min (/ (double bw) w) (/ (double bh) h)))]
    (if (>= ratio 1.0)
      src
      (render-resized src (clamp-round (* ratio w)) (clamp-round (* ratio h))))))

(defn- cover
  "Scale `src` to fill `bw`x`bh` then center-crop to exactly that box."
  ^BufferedImage [^BufferedImage src ^long bw ^long bh]
  (let [w (.getWidth src)
        h (.getHeight src)
        ratio (Math/max (/ (double bw) w) (/ (double bh) h))
        sw (clamp-round (* ratio w))
        sh (clamp-round (* ratio h))
        scaled (render-resized src sw sh)
        cw (Math/min bw sw)
        ch (Math/min bh sh)]
    (.getSubimage scaled (int (quot (- sw cw) 2)) (int (quot (- sh ch) 2)) (int cw) (int ch))))

(defn- transform
  "Apply a named variant `spec` to a master image."
  ^BufferedImage [^BufferedImage src {:keys [mode width height]}]
  (case mode
    :fit (fit-within src width height)
    :cover (cover src width height)))

(defn- read-image
  "Fully decode an image file to a BufferedImage, or nil if it will not decode.
  Called only after `probe` has bounded the pixel count, so the heap is bounded."
  ^BufferedImage [^File f]
  (with-open [iis (ImageIO/createImageInputStream f)]
    (let [readers (ImageIO/getImageReaders iis)]
      (when (.hasNext readers)
        (let [^ImageReader r (.next readers)]
          (try
            (.setInput r iis true true)
            (.read r 0)
            (catch Exception _ nil)
            (finally (.dispose r))))))))

(defn- probe
  "Read format + dimensions from the image HEADER, without decoding the raster.
  Returns {:format :width :height}, or nil if the bytes are not a decodable
  image. Header-only, so a bomb is measured (by pixels) before it is expanded."
  [^File f]
  (with-open [iis (ImageIO/createImageInputStream f)]
    (let [readers (ImageIO/getImageReaders iis)]
      (when (.hasNext readers)
        (let [^ImageReader r (.next readers)]
          (try
            (.setInput r iis true true)
            {:format (.toLowerCase (.getFormatName r))
             :width (.getWidth r 0)
             :height (.getHeight r 0)}
            (catch Exception _ nil)
            (finally (.dispose r))))))))

(defn- write-jpeg
  "Encode a BufferedImage to JPEG bytes at `quality`."
  ^bytes [^BufferedImage img quality]
  (let [baos (ByteArrayOutputStream.)
        ^ImageWriter writer (.next (ImageIO/getImageWritersByFormatName "jpeg"))
        param (.getDefaultWriteParam writer)]
    (.setCompressionMode param ImageWriteParam/MODE_EXPLICIT)
    (.setCompressionQuality param (float quality))
    (with-open [ios (ImageIO/createImageOutputStream baos)]
      (.setOutput writer ios)
      (.write writer nil (IIOImage. img nil nil) param))
    (.dispose writer)
    (.toByteArray baos)))

(defn- write-png
  "Encode a BufferedImage to PNG bytes."
  ^bytes [^BufferedImage img]
  (let [baos (ByteArrayOutputStream.)]
    (ImageIO/write img "png" baos)
    (.toByteArray baos)))

(defn- encode
  "Encode a BufferedImage to bytes in the master's format (`ext` = jpg | png)."
  ^bytes [^BufferedImage img ext]
  (if (= ext "png") (write-png img) (write-jpeg img jpeg-quality)))

(defn- sha256-hex
  "Lowercase-hex SHA-256 of a byte array — the content address."
  [^bytes b]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md b)
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn stored-path
  "Filesystem path for a master's content hash and extension.
  `root/ab/cd/<hex>.<ext>` — the two-level fan-out keeps any one directory from
  holding millions of files."
  ^File [hex ext]
  (io/file (uploads-root) (subs hex 0 2) (subs hex 2 4) (str hex "." ext)))

(defn derived-path
  "Filesystem path for a named variant of a master.
  `root/img/ab/cd/<hex>/<spec>.<ext>` — the URL path mirrors this exactly, so
  Caddy serves a cached derivative straight from disk and only a MISS reaches
  the app."
  ^File [hex spec-name ext]
  (io/file (uploads-root) "img" (subs hex 0 2) (subs hex 2 4) hex (str spec-name "." ext)))

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
      (Files/move
        (.toPath part)
        (.toPath dest)
        (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE])))))

(defn url-for
  "The /uploads URL Caddy serves a pulled `:upload` map's MASTER blob at.
  Rarely linked directly — display goes through derivatives — but it is the
  full-size original for a download link."
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
  "The [w h] a named variant renders at, from the master's stored dimensions.
  Lets the view set <img width/height> so the layout never shifts, without
  storing a row per derivative."
  [upload spec-name]
  (when-let [{:keys [mode width height]} (get specs spec-name)]
    (let [mw (:upload/width upload)
          mh (:upload/height upload)]
      (when (and mw mh)
        (case mode
          :cover [width height]
          :fit (let [ratio (Math/min
                             1.0
                             (Math/min (/ (double width) (long mw)) (/ (double height) (long mh))))]
                 [(int (clamp-round (* ratio (double mw))))
                  (int (clamp-round (* ratio (double mh))))]))))))

(defn store!
  "Normalize and store an uploaded file as a content-addressed master.

  `tmp` is the `File` the multipart middleware wrote the part to. The bytes are
  validated (size, real-image-by-decode, pixel ceiling), then decoded, downscaled
  to `max-edge`, and re-encoded to a canonical format — that master is stored and
  its metadata transacted. Returns {:upload <pulled map>} on success, or
  {:error <keyword>} on rejection (:empty, :too-large, :not-an-image,
  :unsupported-type, :too-many-pixels). Idempotent by content: a master that
  hashes the same reuses one file and one entity."
  [conn ^File tmp]
  (let [size (.length tmp)]
    (cond
      (zero? size) {:error :empty}
      (> size (long max-bytes)) {:error :too-large}
      :else
      (if-let [{fmt :format
                :keys [width height]}
               (probe tmp)]
        (if-not (contains? allowed fmt)
          {:error :unsupported-type}
          (if (> (* (long width) (long height)) (long max-pixels))
            {:error :too-many-pixels}
            (with-permit
              (fn []
                (if-let [src (read-image tmp)]
                  (let [^BufferedImage master (scale-to-max src max-edge)
                        alpha? (has-alpha? master)
                        ext (if alpha? "png" "jpg")
                        mime (if alpha? "image/png" "image/jpeg")
                        data (encode master ext)
                        hex (sha256-hex data)]
                    (write-blob! (stored-path hex ext) data)
                    @(db/transact* conn
                       [{:upload/hash hex
                         :upload/content-type mime
                         :upload/size (alength data)
                         :upload/width (.getWidth master)
                         :upload/height (.getHeight master)
                         :upload/created-at (time/now)}])
                    {:upload (db/pull* (d/db conn) upload-pull [:upload/hash hex])})
                  {:error :not-an-image})))))
        {:error :not-an-image}))))

(defn ensure-derivative!
  "Serve a named variant of the master `hex`, generating it on first request.
  Returns {:file <File> :content-type <mime>} in format `ext`, caching the bytes
  to disk; nil for an unknown variant, an unsupported ext, or a missing master.

  This is the app's role in the on-the-fly cache: in production Caddy serves an
  existing derivative from disk and only a MISS reaches here; in dev (no Caddy)
  every request lands here. The generated bytes are content-derived and immutable,
  so a concurrent double-generate is harmless — same bytes, atomic move."
  [hex spec-name ext]
  (let [spec (get specs spec-name)
        mime (get ext->mime ext)]
    (when (and spec mime)
      (let [^File master (stored-path hex ext)]
        (when (.exists master)
          (let [dp (derived-path hex spec-name ext)]
            (when-not (.exists dp)
              (with-permit
                (fn []
                  (when-not (.exists dp) ; re-check: another request may have won the permit
                    (when-let [src (read-image master)]
                      (write-blob! dp (encode (transform src spec) ext)))))))
            (when (.exists dp)
              {:file dp
               :content-type mime})))))))

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
  "Recursively delete a directory (a master's derivative subtree)."
  [^File dir]
  (when (.exists dir)
    (doseq [^File f (reverse (file-seq dir))]
      (.delete f))))

(defn gc-orphans!
  "Reap unreferenced uploads older than `grace-ms`.
  Deletes the master file, its whole derivative subtree, and retracts the entity;
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
