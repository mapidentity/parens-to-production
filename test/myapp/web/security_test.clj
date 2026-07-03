(ns myapp.web.security-test
  "Regression tests that lock in the security posture.
  Cover output escaping for plain content, markdown sanitization for the one
  h/raw field (recipe descriptions), the strict Content-Security-Policy, and
  asset/import-map resolution. These guard against silently reintroducing a
  non-escaping renderer, an unsanitized markdown path, or a loosened CSP."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [myapp.web.assets :as assets]
    [myapp.web.markdown :as markdown]
    [myapp.web.routes :as routes]
    [myapp.web.views :as views]))

(set! *warn-on-reflection* true)

(defn- sha256-b64
  "Compute the `sha256-<base64>` CSP hash of `s`, matching `assets`' inline-hash format."
  [^String s]
  (str
    "sha256-"
    (.encodeToString
      (java.util.Base64/getEncoder)
      (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))

(deftest output-escaping-prevents-stored-xss
  (testing "plain text content is HTML-escaped by the shared layout"
    ;; error-page routes a caller-supplied string through base-layout, the same
    ;; escaping renderer every page uses. Recipe titles, ingredients, and steps
    ;; take this exact path: they are emitted as Hiccup strings, never h/raw.
    (let [payload "<img src=x onerror=alert(document.cookie)>"
          html (str (views/error-page :en payload))]
      (is
        (str/includes? html "&lt;img src=x onerror=alert(document.cookie)&gt;")
        "the payload must render ESCAPED")
      (is
        (not (str/includes? html "<img src=x onerror"))
        "the raw executable payload must NOT appear in the output"))))

(deftest markdown-render-sanitizes-stored-xss
  (testing "the markdown renderer neutralizes HTML and dangerous URLs"
    ;; Recipe descriptions are the ONE field rendered as markdown and emitted
    ;; with h/raw (views.clj render-recipe / render-version). The escaping
    ;; layout does NOT protect this path, so the renderer itself must sanitize.
    ;; This guards against the non-escaping renderer silently coming back.
    (testing "inline HTML is escaped, not passed through"
      (let [html (markdown/render "Tasty <script>alert(document.cookie)</script> pasta")]
        (is (not (str/includes? html "<script")) "script tags must not survive")
        (is (str/includes? html "&lt;script") "the tag must render escaped")))
    (testing "event-handler injection via inline HTML is escaped"
      (let [html (markdown/render "<img src=x onerror=alert(1)>")]
        (is (not (str/includes? html "<img src=x onerror")) "img/onerror must not survive")))
    (testing "javascript: link targets are stripped"
      (let [html (markdown/render "[click me](javascript:alert(1))")]
        (is
          (not (str/includes? html "javascript:alert"))
          "the javascript: scheme must be removed")))
    (testing "ordinary markdown still renders"
      (let [html (markdown/render "A **bold** [link](https://example.com).")]
        (is (str/includes? html "<strong>bold</strong>"))
        (is (str/includes? html "href=\"https://example.com\""))))))

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
      (is
        (not (re-find #"script-src[^;]*'unsafe-inline'" csp))
        "scripts must never be unsafe-inline"))))

(deftest csp-authorizes-the-import-map
  (testing "the import map's own content hash is in script-src (else the browser blocks it)"
    (let [a @#'assets/manifest
          saved @a]
      (try
        (reset! a
          {:assets {"js/app.js" "/js/app.abcdef12.js"}
           :sri {"/js/app.abcdef12.js" "sha384-deadbeef"}})
        ;; build fresh (not the cached prod value) so it reflects this manifest
        (let [csp (#'assets/build-csp-header)]
          (is
            (str/includes? csp (sha256-b64 (assets/importmap-json)))
            "the importmap hash must appear in script-src"))
        (finally (reset! a saved))))))

(deftest asset-resolution-and-importmap-shape
  (testing "manifest resolution, SRI lookup, and import map with integrity"
    (let [a @#'assets/manifest
          saved @a]
      (try
        (reset! a
          {:assets {"styles.css" "/styles.abcdef12.css"
                    "js/dispatcher.js" "/js/dispatcher.12345678.js"
                    "idiomorph" "/idiomorph-0.7.4.min.js"}
           :sri {"/js/dispatcher.12345678.js" "sha384-MODHASH"}})
        (is (= "/styles.abcdef12.css" (assets/asset "styles.css")))
        (is (= "/missing.js" (assets/asset "missing.js")) "identity fallback for unknown names")
        (is (= "sha384-MODHASH" (assets/asset-sri "/js/dispatcher.12345678.js")))
        (let [im (assets/importmap-json)]
          (is
            (str/includes? im "\"/js/dispatcher.js\":\"/js/dispatcher.12345678.js\"")
            "imports remap identity URL -> hashed URL")
          (is (str/includes? im "integrity") "integrity block present when SRI exists")
          (is (str/includes? im "sha384-MODHASH")))
        (finally (reset! a saved))))))

(deftest wrap-errors-returns-generic-500
  (testing "an uncaught handler exception becomes a styled, generic 500"
    ;; The ERROR line this logs is expected — it proves the log path runs.
    (let [handler (routes/wrap-errors (fn [_] (throw (RuntimeException. "secret internal detail"))))
          resp (handler
                 {:request-method :get
                  :uri "/boom"
                  :locale :en})]
      (is (= 500 (:status resp)))
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/html"))
      (is (str/includes? (:body resp) "Something went wrong"))
      (testing "the failure's internals never reach the body"
        (is (not (str/includes? (:body resp) "secret internal detail")))
        (is (not (str/includes? (:body resp) "RuntimeException"))))))
  (testing "a healthy response passes through untouched"
    (let [resp {:status 200
                :headers {}
                :body "ok"}]
      (is (= resp ((routes/wrap-errors (fn [_] resp)) {}))))))
