# The Production Asset Pipeline: Content Hashing, SRI, Import Maps, and CSP

The app is built. It server-renders HTML from Hiccup, styles it with Tailwind, enhances it with a handful of small ES modules, and morphs the DOM with a vendored library. In development all of that is served straight out of the source tree at stable URLs, and that is fine -- development optimizes for fast feedback, not for the wire.

Production is a different problem. Every served asset needs to be cache-busted on deploy so a new release is never masked by a stale cache. It needs to be tamper-evident over the wire. And the whole front-end needs to be locked down by a Content-Security-Policy strict enough to be worth the name -- one that would block an injected `<script>` even if our output escaping somehow failed.

This chapter covers that pipeline end to end. How every served asset gets a content hash. How the JavaScript modules are minified per-file -- no bundler -- with Subresource Integrity. How the vendored library is built. How the running app resolves a logical asset name to a hashed URL through a manifest, and how an integrity gate keeps a lying filename out of production. Then the two delivery modes -- a stable-URL `no-store` engine for development and immutable content-hashed assets for production -- and why they never drift. Finally the security layer: the escaping renderer, the strict CSP the app emits, the long-lived headers Caddy adds, and the regression tests that pin it all down.

The result: dev and prod outputs stay in lockstep because they come from the same sources through the same pipeline, with the CSS bytes identical and the JavaScript differing only by minification. The app reads a manifest at startup to resolve every asset, and a defense-in-depth security posture runs from output encoding through to the browser's CSP enforcement.

## Project layout: sources vs. the generated tree

The single most important idea in this chapter is the split between **sources** and the **generated served tree**.

```
myapp/
  input.css                       # Tailwind source: imports, tokens, custom CSS
  static/                         # SOURCES (committed)
    js/
      dispatcher.js               # ESM modules, authored with absolute imports
      live-form.js
      defer-details.js
      server-preview.js
      admin-stats.js
      util.js
    idiomorph-0.7.4.js            # vendored library source (committed)
    fonts/GeistVF.woff2
    icon.svg  logo.svg  ...
    styles.css                    # dev-only Tailwind output, served unhashed (gitignored)
  myapp/static/                   # GENERATED served tree (gitignored)
    styles.<hash>.css
    js/dispatcher.<hash>.js  ...
    idiomorph-0.7.4.min.js  idiomorph-0.7.4.min.js.map
    asset-manifest.edn
  src/myapp/web/
    assets.clj                    # manifest, SRI, CSP, the defn-asset macro
    views.clj                     # base-layout: link/importmap/script tags
  dev/
    hot_reload.clj                # long-lived `tailwindcss --watch`, file watcher
  build.clj                       # the `assets` and `verify-assets` tasks
```

`static/` is committed source. You author your ESM modules there, you drop the vendored library source there, you keep fonts and SVGs there. What you do **not** commit is anything generated: the dev-time `static/styles.css` is git-ignored (dev serves it unhashed -- no hashing happens in development), and the entire `myapp/static/` tree -- the production-served, content-hashed, minified output -- is git-ignored too.

```gitignore
/myapp/static
# Dev-generated CSS (Tailwind --watch writes this; sources are input.css + static/)
/static/styles.css
/static/styles.*.css
```

The build's `assets` task reads from `static/` and writes the served tree into `myapp/static/`, alongside an `asset-manifest.edn` that the running app reads to map each logical asset name to its hashed URL. Nothing hashed is ever committed; the artifact is regenerated from source on every build.

One thing this layout makes explicit: **development produces no hashed CSS.** Dev serves `static/styles.css` unhashed at `/styles.css`. The `styles.<hash>.css` file only ever exists in the generated `myapp/static/` tree, which only the production build writes.

## One build, one content hash

The cache-busting strategy is the same for every served file: embed a content hash in the filename. `styles.css` becomes `styles.2c7c3332.css`; `dispatcher.js` becomes `dispatcher.<hash>.js`. When the content changes, the hash changes, the filename changes, and browsers fetch the new version. When it does not change, the filename is stable and the browser uses its cache. Perfect invalidation with zero revalidation traffic.

The hash is the first eight hex characters of the SHA-256 of the file's bytes. Eight characters give over four billion values, enough that an *accidental* collision is vanishingly unlikely rather than impossible. By the birthday bound, a collision only stops being negligible as the number of distinct files approaches the square root of 2³² -- on the order of sixty-five thousand (the 32 bits those eight hex digits carry), with even odds a little past that; a deploy ships a few dozen, so the practical margin is enormous. (If you ever served that many hashed assets, widen the slice.) The helper is shared by both the build and the verifier so the two can never disagree about what "the hash" means.

```clojure
(defn content-hash
  "First 8 hex chars of the SHA-256 of a file's bytes -- the cache-bust fingerprint."
  [^java.io.File file]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.readAllBytes (io/input-stream file)))]
    (subs (format "%064x" (BigInteger. 1 bs)) 0 8)))
```

The same module also defines an SRI helper -- a base64 SHA-384 token -- used to make each JavaScript module tamper-evident over the wire:

```clojure
(defn- sri384
  "Subresource-Integrity token: base64 SHA-384 of a file's bytes, prefixed sha384-."
  [^java.io.File file]
  (let [bs (.digest (java.security.MessageDigest/getInstance "SHA-384")
             (.readAllBytes (io/input-stream file)))]
    (str "sha384-" (.encodeToString (java.util.Base64/getEncoder) bs))))
```

Content hash for cache busting; SRI for integrity. Two digests, two jobs.

## The `assets` build task

A single tools.build task, `assets`, generates the entire served tree. It clears `myapp/static/`, then runs five passes, accumulating two maps as it goes -- logical-name to URL (`assets*`) and URL to SRI token (`sri`).

```clojure
(def ^:private asset-src "static")
(def ^:private asset-out "myapp/static")

(def ^:private esbuild
  "Standalone esbuild via npx (pinned). Same class of tool as the tailwindcss CLI."
  ["npx" "--yes" "esbuild@0.24.0"])

(defn assets
  "Build the production static-asset tree into myapp/static/ + asset-manifest.edn.
  Tailwind one-shot + content-hash; esbuild-minify each ESM module + content-hash;
  esbuild-minify the vendored lib WITH a sourcemap (version-pinned filename, no
  content hash); copy fonts/svgs/error through unchanged. Run: clojure -T:build assets"
  [_]
  (b/delete {:path asset-out})
  (.mkdirs (io/file asset-out))
  (let [assets* (atom {})
        sri (atom {})]
    ;; 1. passthrough: everything except ESM sources, vendored lib sources, generated css
    ...
    ;; 2. CSS: Tailwind (minified) -> content-hash
    (let [css (io/file asset-out "styles.css")]
      (sh! "tailwindcss" "-i" "input.css" "-o" (.getPath css) "--minify")
      (let [hn (str "styles." (content-hash css) ".css")]
        (.renameTo css (io/file asset-out hn))
        (swap! assets* assoc "styles.css" (str "/" hn))))
    ;; 3. app ESM: esbuild minify (no bundle, keep ESM + absolute imports) -> content-hash
    (let [jsout (io/file asset-out "js")]
      (.mkdirs jsout)
      (doseq [^java.io.File f (sort (.listFiles (io/file asset-src "js")))
              :when (str/ends-with? (.getName f) ".js")
              :let [nm (.getName f)
                    tmp (io/file jsout nm)]]
        (apply sh! (concat esbuild [(.getPath f) "--minify" "--format=esm"
                                    (str "--outfile=" (.getPath tmp))]))
        (let [out (io/file jsout (insert-hash nm (content-hash tmp)))
              url (str "/js/" (.getName out))]
          (.renameTo tmp out)
          (swap! assets* assoc (str "js/" nm) url)
          (swap! sri assoc url (sri384 out)))))
    ;; 4. vendored lib: our own minify + sourcemap (upstream ships no map); version in
    ;; filename (NOT content-hashed) so it survives app deploys; debuggable when needed.
    (let [idsrc (io/file asset-src "idiomorph-0.7.4.js")
          idmin (io/file asset-out "idiomorph-0.7.4.min.js")]
      (apply sh! (concat esbuild [(.getPath idsrc) "--minify" "--sourcemap"
                                  (str "--outfile=" (.getPath idmin))]))
      (swap! assets* assoc "idiomorph" "/idiomorph-0.7.4.min.js")
      (swap! sri assoc "/idiomorph-0.7.4.min.js" (sri384 idmin)))
    ;; 5. manifest the running app reads: {:assets name->url :sri url->sri}
    (spit (io/file asset-out "asset-manifest.edn")
      (pr-str {:assets (into (sorted-map) @assets*) :sri (into (sorted-map) @sri)}))
    (println (str "assets: " (count @assets*) " entries (+SRI) -> " asset-out))
    @assets*))
```

Walking the passes:

**1. Passthrough.** Everything under `static/` that is *not* an ESM source, the vendored library source, or generated CSS is copied straight through: fonts, SVGs, the `error/` directory. These keep their names; Caddy gives them a conservative TTL.

**2. CSS.** Tailwind runs once (`--minify`), writing `styles.css` into the output tree, which is then renamed to `styles.<hash>.css`. The logical name `"styles.css"` maps to the hashed URL.

**3. App ESM.** Each `.js` under `static/js/` is minified by `esbuild` **per file** -- `--minify --format=esm`, no `--bundle`. This is intentional: we keep one module per file, with absolute `import` specifiers intact. esbuild here is a minifier, not a bundler. Each minified file is content-hashed, gets an SRI token, and its logical name (`js/dispatcher.js`) maps to its hashed URL (`/js/dispatcher.<hash>.js`).

   Keeping the modules separate -- rather than bundling into one blob -- means each is independently cacheable, independently integrity-checked, and the browser's native module loader resolves the graph. The import map (below) rewrites the absolute specifiers to the hashed URLs at load time.

**4. Vendored library.** The committed `idiomorph-0.7.4.js` source is minified *with a sourcemap* (upstream ships none) into `idiomorph-0.7.4.min.js`. Its version lives in the filename, so it is **not** content-hashed: the version string already changes whenever the bytes do, and a stable name lets it survive routine app deploys in the browser cache. It still gets an SRI token. The sourcemap makes the rare debugging session into the morph engine bearable.

**5. Manifest.** Finally the two maps are written to `asset-manifest.edn` as `{:assets name->url :sri url->sri}`, both sorted for a stable diff.

A representative manifest:

```clojure
{:assets {"idiomorph"        "/idiomorph-0.7.4.min.js"
          "js/admin-stats.js" "/js/admin-stats.<hash>.js"
          "js/dispatcher.js"  "/js/dispatcher.<hash>.js"
          "js/live-form.js"   "/js/live-form.<hash>.js"
          "styles.css"        "/styles.<hash>.css"}
 :sri    {"/idiomorph-0.7.4.min.js" "sha384-..."
          "/js/dispatcher.<hash>.js" "sha384-..."
          ...}}
```

## Resolving assets at runtime: `load-manifest!` and `asset`

The application never hard-codes a hashed filename. It asks `assets.clj` for a logical name and gets back the served URL. The manifest is loaded once at startup, into an atom.

```clojure
(def dev?
  "True in the dev environment (the dev/ source dir is on the classpath)."
  (some? (io/resource "hot_reload.clj")))

(def static-root
  "Dir the Ring file handler serves from: source static/ in dev, the built
  myapp/static/ tree in prod (also what Caddy mounts)."
  (if dev? "static" "myapp/static"))

(defonce ^:private manifest
  ;; {:assets {logical-name served-url} :sri {served-url sri-token}}
  (atom {:assets {} :sri {}}))

(defn load-manifest!
  "Load the asset manifest once at startup. PROD reads myapp/static/asset-manifest.edn;
  DEV derives an identity/source manifest from static/."
  []
  (reset! manifest
    (if dev?
      (dev-manifest)
      (let [f (io/file asset-out "asset-manifest.edn")]
        (if (.exists f)
          (edn/read-string (slurp f))
          (do (println "Assets: WARNING no asset-manifest.edn -- run `clojure -T:build assets`")
              {:assets {} :sri {}})))))
  (println (str "Assets: " (count (:assets @manifest))
                (if dev? " dev" " prod") " manifest entries")))
```

`load-manifest!` is called once from `myapp.core/start-server!`, before the database is even initialized. In production it reads `myapp/static/asset-manifest.edn`. In development there is no generated tree, so it *derives* a manifest from the live `static/` directory -- identity URLs (`styles.css` -> `/styles.css`, `js/dispatcher.js` -> `/js/dispatcher.js`), the vendored library served unminified at `/idiomorph-0.7.4.js`, and no SRI (the source files change as you edit them, so a fixed integrity hash would be wrong by design).

The lookups themselves are tiny:

```clojure
(defn asset
  "Resolve a logical asset name (e.g. \"styles.css\", \"js/dispatcher.js\",
  \"idiomorph\") to its served URL. Falls back to an identity URL if unmapped."
  [asset-name]
  (or (get-in @manifest [:assets asset-name]) (str "/" asset-name)))

(defn asset-sri
  "SRI token for a served URL, or nil (e.g. always nil in dev)."
  [url]
  (get-in @manifest [:sri url]))
```

There is one resolution mechanism, a single manifest atom the whole app reads through, filled two ways: read the build's `asset-manifest.edn` in prod, derive an identity manifest from source in dev. No globbing for `styles.<hash>.css`, no probing of multiple candidate directories, no atom poked by the hot-reload loop.

## The import map and SRI-aware script tags

Because the ESM modules are *not* bundled and keep their absolute import specifiers, the browser needs to know that `/js/dispatcher.js` actually lives at `/js/dispatcher.<hash>.js`. That is exactly what an import map is for. `assets.clj` builds it from the manifest:

```clojure
(defn importmap-json
  "JSON for a <script type=importmap> remapping each ESM module's identity URL to
  its served (hashed) URL, with an `integrity` block (per-module SRI) in prod so a
  hash-based CSP can authorize the resolved modules. Identity no-op in dev. Emit it
  BEFORE any module script."
  []
  (let [as (:assets @manifest)
        sri (:sri @manifest)
        imports (into (sorted-map)
                  (for [[k v] as :when (str/starts-with? k "js/")]
                    [(str "/" k) v]))
        integrity (into (sorted-map)
                    (for [[_ v] imports :when (sri v)] [v (sri v)]))]
    (json/write-value-as-string
      (cond-> {"imports" imports}
        (seq integrity) (assoc "integrity" integrity)))))
```

The map remaps every `/js/*.js` identity URL to its hashed URL and, in production, carries an `integrity` block so the browser checks each resolved module's SRI. In development the imports are identity no-ops and there is no integrity block.

One caveat, since the rest of this chapter is careful to name its limits: the import map's `integrity` key is a recent addition to the spec, and browser support for it is newer and less even than for the `integrity` attribute on a plain `<script>`. Where a browser honors it, the resolved modules are tamper-evident; where it does not, the modules still load -- the `imports` remapping itself is broadly supported -- but without that second check. So treat map-level integrity as defense in depth layered on top of the per-`<script>` SRI and the hash-based CSP, not as the sole guarantee, and confirm it against the browsers you actually target before leaning on it.

The layout emits the map (before any module loads) and then the scripts. A small `script-tag` helper attaches the SRI `integrity` attribute whenever the manifest has one:

```clojure
(defn- script-tag
  "A <script> for a served asset, with SRI integrity when the manifest provides it
  (prod). `attrs` adds e.g. {:type \"module\"} or {:defer true}."
  [logical attrs]
  (let [url (assets/asset logical)]
    [:script (cond-> (assoc attrs :src url)
               (assets/asset-sri url) (assoc :integrity (assets/asset-sri url)))]))
```

And the head of `base-layout`:

```clojure
[:link {:rel "stylesheet" :href (assets/asset "styles.css")}]
;; Import map (must precede any module script) remaps each module's absolute
;; import specifier to its hashed URL in prod; identity no-op in dev.
[:script {:type "importmap"} (h/raw (assets/importmap-json))]
;; Idiomorph (classic script) must load before the dispatcher module
;; so window.Idiomorph is available when dispatcher.js runs.
(script-tag "idiomorph" {:defer true})
(script-tag "js/dispatcher.js" {:type "module"})
(script-tag "js/live-form.js" {:type "module"})
(script-tag "js/defer-details.js" {:type "module"})
(script-tag "js/server-preview.js" {:type "module"})
(script-tag "js/admin-stats.js" {:type "module"})
```

The stylesheet link is `(assets/asset "styles.css")`, which renders as `<link rel="stylesheet" href="/styles.<hash>.css">` in production and `<link ... href="/styles.css">` in dev. The import-map JSON is emitted with `h/raw` for a reason we will come back to in the CSP section: the bytes the browser receives must be exactly the bytes the CSP hashed.

> **What the repo head adds.** The listing above is trimmed to the pieces this chapter reasons about. The companion repo's `base-layout` head also carries a few more module `script-tag`s (the island controllers, `sortable`, `confirm`, `tagline`), an inline `<script type="speculationrules">` for prerender hints, and one inline `<style>` block holding a small `@keyframes page-enter` animation. The last two matter for the CSP that follows: the inline `<style>` falls under the `'unsafe-inline'` that `style-src` carries -- though, as the CSP section explains, a static `<style>` element could in principle be allowed by hash instead; what actually *forces* `'unsafe-inline'` is the `style=` attributes that morphs rewrite, which cannot be hashed. The speculation-rules script, by contrast, is hashed into `script-src` by the very same mechanism as every other inline script (below). They are omitted here only to keep the head readable, so neither is a surprise when the CSP section accounts for both. What a reader diffing against the repo will find is a couple more module `script-tag`s in the head than this trimmed listing shows.

## `verify-assets`: an integrity gate, not a rebuild

A filename that embeds a content hash is making a promise: "my bytes hash to this." `verify-assets` enforces that promise. It is a gate, not a build step -- it never runs Tailwind or esbuild. It just checks that the generated tree is internally consistent.

```clojure
(defn verify-assets
  "Integrity gate for the built asset tree. Asserts: a manifest exists; every
  manifest target file exists; and every content-hashed filename matches the
  SHA-256 of its own bytes (so a name can never lie about its contents).
  Run `clojure -T:build assets` first. Run: clojure -T:build verify-assets"
  [_]
  (let [mf (io/file asset-out "asset-manifest.edn")]
    (when-not (.exists mf)
      (println "FAIL: no asset-manifest.edn -- run `clojure -T:build assets` first")
      (System/exit 1))
    (let [m (:assets (read-string (slurp mf)))
          problems
          (for [[n url] m
                :let [f (io/file asset-out (subs url 1))]    ; url is "/..."
                :when (or (not (.exists f))
                          (when-let [[_ h] (re-find #"\.([a-f0-9]{8})\.(?:css|js)$" url)]
                            (not= h (content-hash f))))]
            (str n " -> " url (if (.exists f) " (hash mismatch)" " (missing)")))]
      (if (seq problems)
        (do (println "FAIL: asset integrity problems:")
            (doseq [p problems] (println "  " p))
            (System/exit 1))
        (println (str "OK: " (count m) " assets verified"))))))
```

It asserts three things:

1. **The manifest exists.** If not, the build never ran -- stop.
2. **Every manifest target exists on disk.** A logical name pointing at a missing file is a broken deploy.
3. **Every content-hashed filename matches its own bytes.** For any URL ending in `.<hash>.css` or `.<hash>.js`, it recomputes the SHA-256 of the file and compares it to the hash in the name, using the *same* `content-hash` helper the build used. A name can never lie about its contents.

The vendored library is intentionally exempt from the hash check (its URL has no `.<hash>.` segment), but its existence is still verified.

Run it in CI right after `assets`:

```bash
clojure -T:build assets
clojure -T:build verify-assets
```

If the gate fails, the pipeline stops and no inconsistent asset tree reaches production.

## One engine, two deliveries

Here is the part that makes the whole pipeline drift-free. There is exactly one set of sources and one build. Development and production differ only in *how the same files are delivered* -- content-hashed, and the JavaScript minified -- not in what they do.

**Development: stable URLs + `no-store`.** Dev serves the `static/` source directory directly (`static-root` is `"static"`), at stable, unhashed URLs. A single long-lived `tailwindcss --watch` process rebuilds `static/styles.css` incrementally as you edit; esbuild is not involved (the ESM is served as-is, the vendored library unminified). Because the URLs are stable but the bytes behind them change as you work, the Ring file handler is wrapped in `wrap-dev-no-store` to send `Cache-Control: no-store` for every `.css`/`.js` -- the mechanism we built in [the morph-reload chapter](19-morph-reload.md#stable-dev-urls-with-no-store), so a stable URL never serves stale bytes after a rebuild. That is the *dev* half of this engine; the production half is the rest of this chapter.

**Production: content hash + immutable.** Prod serves the generated `myapp/static/` tree (`static-root` is `"myapp/static"`), at content-hashed URLs, with year-long `immutable` cache headers set by Caddy. No `no-store` -- a hashed URL's bytes can never change, so the browser need never revalidate.

The key property: **the bytes prod ships are produced from the exact sources dev develops against.** You are never reconciling two parallel asset trees, and there is no "did you remember to rebuild and commit the hashed file" failure mode, because nothing hashed is committed -- it is regenerated by `assets` and gated by `verify-assets` on every build. Dev's stability comes from `no-store` on a stable URL; prod's immutability comes from a hash in the URL. Same engine, two deliveries.

## Caddy: immutable caching and long-lived security headers

Caddy sits in front of the app and serves the static tree directly. Here is the production-shaped vhost (the committed `Caddyfile` shows the `myapp.lan` dev block, which is identical in structure -- it mounts the same `/static` root and the app behind `reverse_proxy`):

```caddyfile
myapp.lan {
    tls /certs/myapp.lan/myapp.lan.crt /certs/myapp.lan/myapp.lan.key
    encode zstd gzip

    # Long-lived, request-invariant security headers (applied to every response).
    # The per-document Content-Security-Policy is set by the app, which owns the
    # inline-script hashes -- Caddy must NOT set CSP or it would conflict.
    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains"
        X-Content-Type-Options "nosniff"
        Referrer-Policy "strict-origin-when-cross-origin"
        Permissions-Policy "geolocation=(), microphone=(), camera=()"
        Cross-Origin-Opener-Policy "same-origin"
        Cross-Origin-Resource-Policy "same-origin"
        -Server
    }

    root * /static
    @static file
    handle @static {
        # Content-hashed filenames (styles.<hash>.css, dispatcher.<hash>.js) are immutable
        @hashed path_regexp \.([a-f0-9]{8})\.(css|js)$
        header @hashed Cache-Control "public, max-age=31536000, immutable"

        # Vendored libs are version-pinned in the filename, so equally immutable
        @vendor path /idiomorph-*.min.js /idiomorph-*.min.js.map
        header @vendor Cache-Control "public, max-age=31536000, immutable"

        # Static assets that rarely change
        @assets path *.svg *.png *.jpg *.woff2
        header @assets Cache-Control "public, max-age=604800"

        file_server
    }

    handle {
        reverse_proxy myapp:3000
    }
}
```

**Caching tiers.** Content-hashed files (`styles.<hash>.css`, `dispatcher.<hash>.js`) get a one-year `immutable` lifetime -- the browser never revalidates, and a content change means a new filename. The vendored library gets the same immutable treatment because its version is pinned in the filename. Fonts, icons, and images -- which keep their plain names -- get a one-week TTL.

**Long-lived security headers.** Caddy owns the request-invariant headers, applied to every response: HSTS (`Strict-Transport-Security`), `X-Content-Type-Options: nosniff`, `Referrer-Policy`, a restrictive `Permissions-Policy`, and the cross-origin protection pair `Cross-Origin-Opener-Policy` / `Cross-Origin-Resource-Policy` (both `same-origin`). It also strips the `Server` header. Two of these families earn a threat model in a chapter that justifies every CSP directive. HSTS (`max-age=31536000; includeSubDomains`) pins the origin to HTTPS for a year: once the browser has seen the header, an SSL-stripping attacker on the network can no longer downgrade a navigation to plain `http` and read or rewrite it in transit. The cross-origin pair closes two distinct cross-origin holes. `Cross-Origin-Opener-Policy: same-origin` severs the `window.opener` link, so a page we open (or one that opened us) cannot reach into our window and script it; `Cross-Origin-Resource-Policy: same-origin` tells the browser to refuse cross-origin attempts to embed our responses as resources. (This pair is not the same as cross-origin *isolation*: the `crossOriginIsolated` state that unlocks `SharedArrayBuffer` and high-resolution timers additionally requires `Cross-Origin-Embedder-Policy: require-corp`, which we do not set because we need none of what it unlocks.) These headers are static -- they do not depend on the page being rendered -- so the proxy is the right place for them.

**Caddy does NOT set the Content-Security-Policy.** That is the one security header Caddy must stay out of. The CSP is per-document -- it embeds the hashes of the inline scripts the app emits -- so only the app can build it. The `@static file` matcher checks that a file exists on disk before serving it; everything else falls through to `reverse_proxy` and reaches the Clojure app, which attaches the CSP to its HTML responses.

## Output escaping: the primary XSS defense

Before the CSP, the more fundamental defense. Recipes carry user-supplied text (titles, ingredient lines, step text) that gets rendered straight into pages, and that is exactly where stored XSS lives: a title of `<script>steal()</script>` written into the page verbatim would execute for every visitor. (The one field that is *not* escaped this way is the markdown description, emitted through `h/raw` because the markdown renderer sanitizes it upstream -- escaping embedded HTML and stripping dangerous URL schemes -- as [the views chapter](14-hiccup-views.md) sets up.) The base layout forecloses that by rendering through the **escaping** hiccup2 renderer, the decision [the views chapter](14-hiccup-views.md#output-encoding-escaping-is-the-primary-xss-defense) took when it first built the layout.

`h/html` HTML-escapes every string by default; the only content emitted verbatim is what is explicitly wrapped in `h/raw` -- rendered markdown, intentional inline scripts and styles, the import map.

```clojure
(defn- base-layout
  "Base HTML5 wrapper. All pages use this -- never called directly by page fns."
  [locale & body]
  ;; Rendered with the ESCAPING hiccup2 renderer (h/html): all string content is
  ;; HTML-escaped by default -- the primary XSS defense. Only h/raw content
  ;; (markdown, inline scripts/styles) is emitted verbatim; the strict CSP is the
  ;; defense-in-depth backup.
  (h/html
    {:mode :html}
    (h/raw "<!DOCTYPE html>")
    [:html {:lang (name locale)}
     ...]))
```

Output encoding is the primary XSS defense. The CSP that follows is defense-in-depth *behind* it -- a second wall that would also block the payload, not a substitute for escaping.

## The strict, no-nonce Content-Security-Policy

The app emits a strict CSP, set by the `wrap-csp` middleware on every HTML response:

```clojure
(defn wrap-csp
  "Set the app's strict, no-nonce Content-Security-Policy on HTML responses; static
  assets (served by Caddy in prod) don't need it. See myapp.web.assets/csp-header."
  [handler]
  (fn [request]
    (let [resp (handler request)
          ct (get-in resp [:headers "Content-Type"])]
      (if (and ct (str/includes? ct "text/html"))
        (-> resp
            (assoc-in [:headers "Content-Security-Policy"] (assets/csp-header))
            (assoc-in [:headers "Reporting-Endpoints"] "csp=\"/csp-report\""))
        resp))))
```

The policy is built in `assets.clj`. It is **hash-based, not nonce-based**: instead of stamping a per-request nonce onto inline scripts, it allows exactly the inline scripts it knows it emits, by their SHA-256 content hash.

That choice is worth defending, because both mechanisms are legitimate. A nonce must be unique per response, so a nonce-based policy is *regenerated on every request*; a hash-based one is a fixed set the app computes once at boot and caches for the life of the process (exactly what the `delay` below does in production). More than the performance, the hash policy *enumerates* its allowances -- it names the precise scripts that may run, and nothing else. A nonce is closer to a per-request wildcard: any inline script carrying it executes. That is the right tool when you *cannot* enumerate your scripts ahead of time -- third-party embeds, or a CMS that emits markup you do not control -- but we can enumerate ours exactly, so we take the stricter, static policy. The price is that introducing a brand-new inline script becomes a code change (it must be registered so its hash joins the policy) rather than a runtime act. For this app that is a feature: there is no path by which an inline script the build did not see can run.

```clojure
(def ^:private csp-rest
  "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")

(defn- build-csp-header
  []
  (str "default-src 'none'; "
       "script-src 'self' " (str/join " " (map #(str "'" % "'") (csp-script-hashes))) "; "
       "connect-src 'self'" (if dev? " ws: wss:" "") "; "
       csp-rest
       "; report-uri /csp-report; report-to csp"))

(def ^:private csp-cached
  (delay (build-csp-header)))

(defn csp-header
  "The strict CSP header value.
  Static in prod (computed once); recomputed in dev
  so it tracks hot-reloaded inline scripts."
  []
  (if dev? (build-csp-header) @csp-cached))
```

Reading it directive by directive:

- **`default-src 'none'`** -- deny everything by default, then open the minimum.
- **`script-src 'self' <sha256...>`** -- same-origin scripts (the modules, authorized further by SRI) plus the SHA-256 hash of every inline `<script>` the app can emit. Crucially there is **no `'unsafe-inline'`** for scripts. An injected `<script>` whose hash is not on the list simply does not run -- which is exactly why the CSP would have blocked the stored XSS even if escaping had failed.
- **`style-src 'self' 'unsafe-inline'`** -- this one is pragmatic. A truly strict `style-src` is unsolved across DOM-morphing front-ends (inline style attributes get rewritten during morphs), so this directive is the concession we make. It is documented as such, not hand-waved, and the residual risk it leaves is worth naming. `'unsafe-inline'` on styles permits CSS injection, which is not as harmless as it sounds: an attacker who can inject a `<style>` or a `style=` attribute can restyle the page for clickjacking or phishing. The textbook escalation from there, using attribute selectors plus `background: url(...)` to exfiltrate a hidden CSRF token one character at a time, needs a fetch to an attacker-observable origin; this policy's own `img-src 'self' data:` and `font-src 'self'` (under `default-src 'none'`) block that fetch, so the exfiltration channel is largely closed here even though the injection is not. And CSS injection cannot run script, since `script-src` carries no `'unsafe-inline'`, so its blast radius is meaningfully smaller than a script injection's, though not nothing. The escaping renderer is what keeps injected markup out in the first place; this concession is why that defense, not the style CSP, is the one carrying the weight.
- **`connect-src 'self'`** -- plus `ws: wss:` in dev for the hot-reload socket.
- The rest: `img-src 'self' data:`, `font-src 'self'`, `object-src 'none'`, `base-uri 'none'`, `form-action 'self'`, `frame-ancestors 'none'`.
- **`report-uri /csp-report; report-to csp`** -- violations are reported back to the app. `wrap-csp` also sets a `Reporting-Endpoints: csp="/csp-report"` header (the modern reporting group); `report-uri` is the widely-supported fallback.

Reports land at a small public endpoint that logs them:

```clojure
(defn csp-report
  "Receive a browser CSP violation report and log it. Public + unauthenticated, so
  in production you would sample/rate-limit this (or point report-to at a managed
  collector) -- an open report sink can be spammed."
  [request]
  (try
    (when-let [body (:body request)]
      (log/warn "CSP violation" {:report (slurp body)}))
    (catch Exception _ nil))
  {:status 204 :headers {}})
```

The route is public and unauthenticated, declared alongside the other public routes:

```clojure
["/csp-report" {:post #'handler/csp-report}]
```

### How inline-script hashes get into the policy

The inline scripts the app emits are defined through a macro, `defn-asset`, which both produces the hiccup element *and* registers the script's resource path so its hash enters the CSP:

```clojure
(defn-asset toast-script "myapp/web/toast.js")
(defn-asset dev-reload-script "myapp/web/dev-reload.js")
(defn-asset inspector-script "myapp/web/inspector.js")
(defn-asset trace-overlay-script "myapp/web/trace-overlay.js")
```

The macro records each script's path with `register-inline-script!`, and `csp-script-hashes` then hashes each registered inline script, the import-map JSON, and the speculation-rules script:

```clojure
(defn- csp-script-hashes
  "sha256 CSP tokens for every inline <script> the app may emit (registered inline
  assets + the import map JSON + the speculation-rules script). Recomputed each
  call (cheap); in dev the inline content hot-reloads, so the policy self-heals."
  []
  (-> (mapv (fn [p] (sha256-b64 (slurp (io/resource p)))) (sort @inline-scripts))
      (conj (sha256-b64 (importmap-json)))
      ;; same hash mechanism as defn-asset scripts; carries no executable JS
      ;; but is still governed by script-src.
      (conj (sha256-b64 speculation-rules-json))))
```

There are two subtleties worth internalizing:

**The emitted bytes must equal the hashed bytes.** The CSP authorizes a script by hashing its *content*; if the browser receives even one byte different from what we hashed, the script is blocked. That is why inline content is emitted with `h/raw` -- escaping would alter the bytes and break the hash. The `defn-asset` macro wraps every inline asset's content in `h/raw` for exactly this reason.

**A hash-allowed import map still needs per-module SRI.** Allowing the inline `<script type="importmap">` by hash lets the map *load*, but the modules it resolves to are then fetched as same-origin scripts. The per-module SRI in the map's `integrity` block is what makes those resolved modules tamper-evident -- which is why `importmap-json` emits both the imports and the integrity block in production. Hash for the map, SRI for what the map points at.

A final note on the morphing front-end: DOM morphing is *not* fundamentally at odds with a strict CSP, and the no-nonce choice is what keeps them aligned. A morph injects inert DOM nodes, and the browser does not auto-execute inline `<script>`s that arrive via `innerHTML`. Because the policy is a fixed, boot-time set of hashes, the dispatcher does **not** try to revive scripts found in a morphed fragment -- a re-executed inline script would have to be hashed into the policy in advance, and a fixed policy cannot anticipate arbitrary fragment content. So the invariant is structural and holds in both directions: no inline `<script>` ever lives inside `<main>`, and the morph never executes one if it did. All per-page behavior attaches instead through the island controller registry (the progressive-enhancement chapter) -- idiomatic ES modules loaded once from `<head>` and re-scanned after each morph, never inline blobs in the body. It is the same reasoning as the nonce decision above: enumerate exactly what may run, and shut every door you did not open.

In production the policy is computed once and cached (`delay`); in dev it is rebuilt on each request so it tracks hot-reloaded inline scripts and self-heals as you edit.

## The security tests

These guarantees are pinned by regression tests in `test/myapp/web/security_test.clj`. They guard against silently reintroducing the non-escaping renderer or loosening the CSP -- revert the escaping renderer or weaken a directive and the build fails.

**Output escaping prevents stored XSS.** A caller-supplied payload routed through `base-layout` (via `error-page`, which takes the same escaping path a recipe title did) must come out HTML-escaped, and the raw executable form must not appear:

```clojure
(deftest output-escaping-prevents-stored-xss
  (testing "user-controlled content is HTML-escaped by the shared layout"
    (let [payload "<img src=x onerror=alert(document.cookie)>"
          html (str (views/error-page :en payload))]
      (is (str/includes? html "&lt;img src=x onerror=alert(document.cookie)&gt;")
        "the payload must render ESCAPED")
      (is (not (str/includes? html "<img src=x onerror"))
        "the raw executable payload must NOT appear in the output"))))
```

**The CSP is strict.** The policy must keep `default-src 'none'`, hash-based `script-src`, the locked-down `object-src` / `base-uri` / `frame-ancestors` / `form-action`, and reporting -- and it must *never* allow `'unsafe-eval'` or an `'unsafe-inline'` script source:

```clojure
(deftest csp-is-strict
  (testing "the Content-Security-Policy locks sources down"
    (let [csp (assets/csp-header)]
      (is (str/includes? csp "default-src 'none'"))
      (is (re-find #"script-src 'self' 'sha256-" csp) "scripts: self + inline hashes")
      (is (str/includes? csp "object-src 'none'"))
      (is (str/includes? csp "base-uri 'none'"))
      (is (str/includes? csp "frame-ancestors 'none'"))
      (is (str/includes? csp "form-action 'self'"))
      (is (str/includes? csp "report-uri /csp-report") "violations are reported")
      (is (not (str/includes? csp "'unsafe-eval'")) "must NEVER allow eval")
      (is (not (re-find #"script-src[^;]*'unsafe-inline'" csp))
        "scripts must never be unsafe-inline"))))
```

**The CSP authorizes the import map.** With a stubbed manifest, the import map's own SHA-256 must appear in `script-src` -- otherwise the browser would block the very `<script type="importmap">` the app emits:

```clojure
(deftest csp-authorizes-the-import-map
  (testing "the import map's own content hash is in script-src (else the browser blocks it)"
    (let [a @#'assets/manifest
          saved @a]
      (try
        (reset! a {:assets {"js/app.js" "/js/app.abcdef12.js"}
                   :sri {"/js/app.abcdef12.js" "sha384-deadbeef"}})
        ;; build fresh (not the cached prod value) so it reflects this manifest
        (let [csp (#'assets/build-csp-header)]
          (is (str/includes? csp (sha256-b64 (assets/importmap-json)))
            "the importmap hash must appear in script-src"))
        (finally (reset! a saved))))))
```

**Asset resolution and import-map shape.** A stubbed manifest exercises the lookups: `asset` resolves logical names and falls back to identity for unknowns, `asset-sri` returns the token, and `importmap-json` remaps identity URLs to hashed URLs and carries the integrity block when SRI exists:

```clojure
(deftest asset-resolution-and-importmap-shape
  (testing "manifest resolution, SRI lookup, and import map with integrity"
    (let [a @#'assets/manifest
          saved @a]
      (try
        (reset! a {:assets {"styles.css" "/styles.abcdef12.css"
                            "js/dispatcher.js" "/js/dispatcher.12345678.js"
                            "idiomorph" "/idiomorph-0.7.4.min.js"}
                   :sri {"/js/dispatcher.12345678.js" "sha384-MODHASH"}})
        (is (= "/styles.abcdef12.css" (assets/asset "styles.css")))
        (is (= "/missing.js" (assets/asset "missing.js")) "identity fallback for unknown names")
        (is (= "sha384-MODHASH" (assets/asset-sri "/js/dispatcher.12345678.js")))
        (let [im (assets/importmap-json)]
          (is (str/includes? im "\"/js/dispatcher.js\":\"/js/dispatcher.12345678.js\"")
            "imports remap identity URL -> hashed URL")
          (is (str/includes? im "integrity") "integrity block present when SRI exists")
          (is (str/includes? im "sha384-MODHASH")))
        (finally (reset! a saved))))))
```

No webpack, no PostCSS plugin chain, no application bundle. A Tailwind CLI, a pinned esbuild used purely as a minifier, a few functions for hashing, SRI, the manifest, and the CSP -- one `assets` task building from committed sources into a hashed tree, one `verify-assets` gate proving no hashed filename can lie about its bytes, and one engine behind two deliveries (dev source at stable URLs, prod hashed and `immutable`) that cannot drift because both come from the same sources. That is the entire pipeline.
