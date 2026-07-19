(ns myapp.upload.core-test
  "Content-addressed image storage, driven with real image bytes.
  Normalize-on-ingest into a WebP source (via libvips), lazy derivative
  generation, rejection of hostile input, and deferred orphan collection — all
  exercised against ImageIO-written inputs in a temp uploads dir. Requires the
  `vips`/`vipsthumbnail`/`vipsheader` CLIs on PATH (apt install libvips-tools)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.test-helpers :as h]
    [myapp.upload.core :as upload]
    [myapp.upload.vips :as vips])
  (:import
    [java.awt Color]
    [java.awt.image BufferedImage]
    [java.io File]
    [javax.imageio ImageIO]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-db)

(defn- with-uploads-root
  "Run `f` with :uploads-root pointing at a fresh temp dir, cleaned up after."
  [f]
  (let [dir (File/createTempFile "uploads" "")]
    (.delete dir)
    (.mkdirs dir)
    (with-redefs [config/config (delay
                                  (assoc h/test-config
                                    :uploads-root (.getPath dir)))]
      (try
        (f dir)
        (finally
          (doseq [^File x (reverse (file-seq dir))]
            (.delete x)))))))

(defn- solid-png
  "Write a `w`x`h` OPAQUE PNG (solid `rgb`) to a temp file, return it."
  ^File [w h rgb]
  (let [img (BufferedImage. (int w) (int h) BufferedImage/TYPE_INT_RGB)
        g (.createGraphics img)]
    (.setColor g (Color. (int rgb)))
    (.fillRect g 0 0 (int w) (int h))
    (.dispose g)
    (let [f (File/createTempFile "img" ".png")]
      (ImageIO/write img "png" f)
      f)))

(defn- text-file
  "A .png-named file whose bytes are plain text — it lies about being an image."
  ^File [^String s]
  (let [f (File/createTempFile "junk" ".png")]
    (spit f s)
    f))

(defn- dims-of
  "The [width height] of a stored image, read via libvips (WebP-aware)."
  [^File f]
  (let [{:keys [width height]} (vips/probe f)]
    [width height]))

(deftest image-processor-check-fails-fast
  (testing "with libvips present, the startup check returns its version"
    (is (re-find #"vips" (upload/check-image-processor!)) "reports a libvips version"))
  (testing "a missing libvips throws at the check, so the boot fails loudly"
    (with-redefs [vips/vipsthumbnail-bin "definitely-not-a-real-vips-binary-xyz"]
      (is (thrown? clojure.lang.ExceptionInfo (upload/check-image-processor!))))))

(deftest store-normalizes-into-a-content-addressed-webp-source
  (with-uploads-root
    (fn [_dir]
      (testing "a photo larger than the edge cap is downscaled and re-encoded to WebP"
        (let [{:keys [upload error]} (upload/store! h/*conn* (solid-png 2600 1300 0x3366cc))]
          (is (nil? error))
          (is (= "image/webp" (:upload/content-type upload)) "the source canonicalizes to WebP")
          (is
            (= [2048 1024] [(:upload/width upload) (:upload/height upload)])
            "the long edge is capped at 2048, aspect preserved")
          (is (= 64 (count (:upload/hash upload))) "a SHA-256 hex address")
          (let [^File src (upload/stored-path (:upload/hash upload) "webp")]
            (is (.exists src) "the source is on disk")
            (is (= "webpload" (:loader (vips/probe src))) "and it really is a WebP"))
          (is (re-matches #"/uploads/../../[0-9a-f]{64}\.webp" (upload/url-for upload)))))
      (testing "a small image is kept at its own size (never upscaled)"
        (let [{:keys [upload]} (upload/store! h/*conn* (solid-png 120 90 0x22aa44))]
          (is (= [120 90] [(:upload/width upload) (:upload/height upload)])))))))

(deftest identical-content-dedups-to-one-source
  (with-uploads-root
    (fn [_dir]
      (let [a (upload/store! h/*conn* (solid-png 300 200 0x00aa55))
            b (upload/store! h/*conn* (solid-png 300 200 0x00aa55))] ; normalize to identical bytes
        (is
          (= (get-in a [:upload :upload/hash]) (get-in b [:upload :upload/hash]))
          "same normalized content, same address")
        (is
          (= 1 (ffirst (d/q '[:find (count ?e) :where [?e :upload/hash _]] (d/db h/*conn*))))
          "one upload entity, not two")))))

(deftest hostile-and-oversized-input-is-rejected
  (with-uploads-root
    (fn [_dir]
      (testing "a non-image (bytes that lie about being a png) is refused"
        (is (= :not-an-image (:error (upload/store! h/*conn* (text-file "not an image"))))))
      (testing "an empty file is refused"
        (is (= :empty (:error (upload/store! h/*conn* (File/createTempFile "empty" ".png"))))))
      (testing "an over-cap file is refused before it is even decoded"
        (with-redefs [upload/max-bytes 8]
          (is (= :too-large (:error (upload/store! h/*conn* (solid-png 40 40 0x0000ff)))))))
      (testing "a pixel-count over the bomb ceiling is refused (dimensions read from the header)"
        (with-redefs [upload/max-pixels 10]
          (is
            (= :too-many-pixels (:error (upload/store! h/*conn* (solid-png 40 40 0x0000ff))))))))))

(deftest derivatives-are-generated-lazily-and-cached
  (with-uploads-root
    (fn [_dir]
      (let [{:keys [upload]} (upload/store! h/*conn* (solid-png 2600 1300 0x884400))
            hex (:upload/hash upload)]
        (testing "a card variant is generated on first request, exactly cover-cropped"
          (let [{:keys [^File file content-type]} (upload/ensure-derivative! hex "card" "webp")]
            (is (= "image/webp" content-type))
            (is (.exists file) "the derivative is cached on disk")
            (is (= [400 300] (dims-of file)) "cover crops to an exact box")
            (is (= [400 300] (upload/variant-dimensions upload "card")))))
        (testing "a hero variant fits inside its box, aspect preserved, and the view agrees"
          (let [{:keys [file]} (upload/ensure-derivative! hex "hero" "webp")
                [w hgt] (dims-of file)]
            (is (= 1200 w) "hero long edge is 1200")
            (is
              (= [1200 hgt] (upload/variant-dimensions upload "hero"))
              "variant-dimensions matches the generated bytes, so <img> never shifts")))
        (testing "the second request reuses the cached file (mtime unchanged)"
          (let [f (:file (upload/ensure-derivative! hex "card" "webp"))
                m1 (.lastModified ^File f)
                _ (upload/ensure-derivative! hex "card" "webp")]
            (is (= m1 (.lastModified ^File f)) "not regenerated")))
        (testing "an unknown variant or a missing source yields nil (a 404 upstream)"
          (is (nil? (upload/ensure-derivative! hex "banner" "webp")) "not in the whitelist")
          (is (nil? (upload/ensure-derivative! hex "card" "jpg")) "unsupported ext")
          (is
            (nil? (upload/ensure-derivative! (apply str (repeat 64 "a")) "card" "webp"))
            "no source for this hash"))))))

(deftest orphan-gc-reaps-unreferenced-sources-and-their-derivatives
  (with-uploads-root
    (fn [_dir]
      (let [orphan (:upload (upload/store! h/*conn* (solid-png 500 400 0x111111)))
            kept (:upload (upload/store! h/*conn* (solid-png 500 400 0x222222)))]
        ;; Materialize a derivative for the orphan, so GC must clear its subtree too.
        (upload/ensure-derivative! (:upload/hash orphan) "card" "webp")
        ;; Reference `kept` from a recipe; leave `orphan` unreferenced.
        @(db/transact* h/*conn*
           [{:db/id "u"
             :user/email "gc@x.lan"
             :user/id (java.util.UUID/randomUUID)}])
        (let [uid (d/q '[:find ?e . :in $ ?m :where [?e :user/email ?m]] (d/db h/*conn*) "gc@x.lan")
              rid (recipe/create!
                    h/*conn*
                    uid
                    {:title "With photo"
                     :servings 1})]
          @(db/transact* h/*conn*
             [{:recipe/id rid
               :recipe/image [:upload/hash (:upload/hash kept)]}]))
        (testing "grace not elapsed → nothing reaped"
          (is (zero? (upload/gc-orphans! h/*conn* (* 24 60 60 1000)))))
        (testing
          "past grace → the unreferenced source AND its derivatives go; the referenced one stays"
          (is (= 1 (upload/gc-orphans! h/*conn* -1)) "one orphan reaped")
          (is
            (not (.exists (upload/stored-path (:upload/hash orphan) "webp")))
            "orphan source gone")
          (is
            (not (.exists (upload/derived-path (:upload/hash orphan) "card" "webp")))
            "orphan's cached derivative gone")
          (is (.exists (upload/stored-path (:upload/hash kept) "webp")) "referenced source kept")
          (is
            (nil? (d/entid (d/db h/*conn*) [:upload/hash (:upload/hash orphan)]))
            "orphan entity retracted")
          (is
            (some? (d/entid (d/db h/*conn*) [:upload/hash (:upload/hash kept)]))
            "kept entity remains"))))))
