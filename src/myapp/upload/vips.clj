(ns myapp.upload.vips
  "Interop with libvips: image processing in a child process, not the app JVM.

  Decoding and resizing a hostile image is heavy and memory-shaped nothing like
  request serving, and it wants a purpose-built tool. So this app shells out to
  libvips (`vipsheader`, `vipsthumbnail`) rather than decode on the heap: the
  raster lives and dies in a child process, a decoder crash fails one request
  instead of the whole box, and we get correct EXIF autorotation and modern
  formats (WebP) that the JDK's ImageIO does not offer. libvips is a native
  dependency the box opts into (`apt install libvips-tools`) — the same posture as
  Caddy or PostgreSQL — kept off the deploy artifact (the jar stays pure JVM) and
  behind this one namespace.

  The interop is the careful part, and it is careful in four specific ways:
  arguments are passed as a VECTOR to ProcessBuilder, never a shell string, so
  nothing a filename contains can be interpreted as a command; stdout and stderr
  are drained CONCURRENTLY on their own threads, or a child that fills the pipe
  buffer would deadlock against our `waitFor`; every call has a hard TIMEOUT and
  is force-killed past it, so a pathological image cannot wedge a worker; and a
  semaphore bounds how many children run at once, because the raster memory and
  CPU are now the box's to spend, not one heap's."
  (:require
    [clojure.string :as str])
  (:import
    [java.io File]
    [java.util.concurrent Semaphore TimeUnit]))

(set! *warn-on-reflection* true)

(def vipsheader-bin
  "The vipsheader executable, a bare name resolved on PATH.
  Overridable for a box that installs libvips somewhere non-standard."
  "vipsheader")
(def vipsthumbnail-bin
  "The vipsthumbnail executable (see `vipsheader-bin`)."
  "vipsthumbnail")

(def ^:private timeout-ms
  "Hard ceiling on any single vips call.
  Past it the child is force-killed and the call throws, so a pathological image
  cannot wedge a worker."
  15000)

(def ^Semaphore processes
  "Bounds concurrent vips children.
  The raster memory and CPU are the box's now, not one heap's, so cap how many run
  at once; ingest is rare, so a small pool is plenty."
  (Semaphore. 3))

(def acquire-timeout-ms
  "How long a call waits for a vips permit before shedding load as BUSY.
  A short queue absorbs a transient burst; past it we refuse rather than pile
  request threads up waiting, and the caller turns that into a 503/retry."
  2000)

(defn busy?
  "True when `e` is the exception `run` throws on saturation (no permit in time)."
  [e]
  (= ::busy (:type (ex-data e))))

(defn- run
  "Run `argv` in a child process and return {:exit :out :err}.
  `argv` is a VECTOR, never a shell string, so nothing a filename contains can be
  interpreted as a command.

  stdout and stderr are drained on their own threads: a child that fills the pipe
  buffer while we sit in `waitFor` would otherwise deadlock, each side blocked on
  the other. The call is bounded by `timeout-ms` and the child is
  `destroyForcibly`-killed past it. Acquiring a `processes` permit is itself
  bounded by `acquire-timeout-ms`: rather than block a request thread forever
  under saturation, we throw a ::busy signal the caller sheds as a 503."
  [argv]
  (if-not (.tryAcquire processes acquire-timeout-ms TimeUnit/MILLISECONDS)
    (throw (ex-info "image processor is busy" {:type ::busy}))
    (try
      (let [proc (.start (ProcessBuilder. ^java.util.List argv))
            out (future (slurp (.getInputStream proc)))
            err (future (slurp (.getErrorStream proc)))]
        (if (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)
          {:exit (.exitValue proc)
           :out @out
           :err @err}
          (do
            (.destroyForcibly proc)
            (throw
              (ex-info
                "vips timed out"
                {:argv argv
                 :timeout-ms timeout-ms})))))
      (finally (.release processes)))))

(defn probe
  "Read {:width :height :loader} from an image header, or nil if not an image.

  `vipsheader` reads only the header — no raster is expanded — so a decompression
  bomb is measured (by pixels) before it could be. The loader (\"jpegload\",
  \"pngload\", …) is libvips' verdict on the REAL format from the bytes, which is
  what the caller whitelists — never the client's declared type or filename."
  [^File f]
  (let [{:keys [exit out]}
        (run [vipsheader-bin "-f" "width" "-f" "height" "-f" "vips-loader" (.getAbsolutePath f)])]
    (when (zero? (long exit))
      (let [[w h loader] (str/split-lines out)]
        (when (and w h loader)
          {:width (parse-long (str/trim w))
           :height (parse-long (str/trim h))
           :loader (str/trim loader)})))))

(defn thumbnail!
  "Produce `out` from `in` with `vipsthumbnail`, and return `out`.

  Fits (or, with `:smartcrop?`, fills-and-crops) to `geometry`, applies EXIF
  orientation and then strips all metadata, and encodes by `out`'s extension at
  `:quality`. `geometry` is a libvips size string: `\"1200x1200>\"` shrinks to fit
  the box (aspect preserved, never enlarging); `\"400x300\"` with `:smartcrop?`
  crops to EXACTLY that box using attention-based cropping (uniform tiles). Throws
  on a non-zero exit; `out`'s extension MUST be one libvips can save (e.g. .webp)."
  [^File in ^File out geometry {:keys [smartcrop? quality]}]
  ;; Absolute paths on both sides: vipsthumbnail resolves a relative `-o` output
  ;; RELATIVE TO THE INPUT's directory, which silently doubles a relative path.
  (let [argv (cond-> [vipsthumbnail-bin (.getAbsolutePath in) "--size" geometry]
               smartcrop? (into ["--smartcrop" "attention"])
               :always (conj "-o" (str (.getAbsolutePath out) "[Q=" (or quality 80) ",strip]")))
        {:keys [exit err]} (run argv)]
    (when-not (zero? (long exit))
      (throw
        (ex-info
          "vipsthumbnail failed"
          {:in (.getPath in)
           :exit exit
           :err err})))
    out))

(defn check!
  "Verify libvips is installed and runnable, returning its version, or throw.
  Meant to run at STARTUP so a missing image processor fails the boot — loud, and
  at deploy time — instead of surfacing as a 500 on a user's first upload. A
  missing binary makes `ProcessBuilder` throw, which is caught and reported as the
  actionable message rather than a raw stack trace."
  []
  (let [{:keys [exit out err]}
        (try
          (run [vipsthumbnail-bin "--version"])
          (catch Exception e
            {:exit -1
             :err (.getMessage e)}))]
    (when-not (zero? (long exit))
      (throw
        (ex-info
          "libvips (libvips-tools) is not available on this host — the image processor is required"
          {:exit exit
           :err err})))
    (str/trim (if (str/blank? out) (str err) out))))
