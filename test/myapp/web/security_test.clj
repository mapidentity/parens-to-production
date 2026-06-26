(ns myapp.web.security-test
  "Regression tests that lock in the security posture: output escaping (the
  stored-XSS fix), the strict Content-Security-Policy, and asset/import-map
  resolution. These guard against silently reintroducing the non-escaping
  renderer or loosening the CSP."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [myapp.web.assets :as assets]
    [myapp.web.views :as views]))

(defn- sha256-b64
  [^String s]
  (str
    "sha256-"
    (.encodeToString
      (java.util.Base64/getEncoder)
      (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))

(deftest output-escaping-prevents-stored-xss
  (testing "user-controlled content is HTML-escaped by the shared layout"
    ;; error-page routes a caller-supplied string through base-layout, the same
    ;; escaping renderer every page uses. A recipe title took this exact path.
    (let [payload "<img src=x onerror=alert(document.cookie)>"
          html (str (views/error-page :en payload))]
      (is
        (str/includes? html "&lt;img src=x onerror=alert(document.cookie)&gt;")
        "the payload must render ESCAPED")
      (is
        (not (str/includes? html "<img src=x onerror"))
        "the raw executable payload must NOT appear in the output"))))

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
