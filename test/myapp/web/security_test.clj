(ns myapp.web.security-test
  "Regression tests that lock in the security posture.
  Cover output escaping for plain content, markdown sanitization for the one
  h/raw field (recipe descriptions), the strict Content-Security-Policy,
  asset/import-map resolution, and the detection/response layer (security-
  event format, the ban lever). These guard against silently reintroducing a
  non-escaping renderer, an unsanitized markdown path, or a loosened CSP."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.tools.logging]
    [datomic.api :as d]
    [myapp.analytics.db :as analytics]
    [myapp.auth.core :as auth]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.test-helpers :as h]
    [myapp.time :as time]
    [myapp.web.assets :as assets]
    [myapp.web.handler :as handler]
    [myapp.web.markdown :as markdown]
    [myapp.web.routes :as routes]
    [myapp.web.security :as security]
    [myapp.web.views :as views])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)

(defn- mk-user!
  "Create a terms-accepted user and return its eid (detection tests)."
  [email]
  @(db/transact* h/*conn*
     [{:db/id "u"
       :user/id (UUID/randomUUID)
       :user/email email
       :user/active? true
       :user/created-at (time/now)
       :user/terms-accepted-at (time/now)}])
  (auth/find-user-by-email (d/db h/*conn*) email))

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

;; --- Detection & Response layer ---

(use-fixtures :each h/with-test-db h/with-test-analytics-db h/with-test-config)

(defn- capture-security
  "Run `f`, returning the security log messages it emits (log* redef)."
  [f]
  (let [lines (atom [])]
    (with-redefs [clojure.tools.logging/log*
                  (fn [_logger _level _throwable message] (swap! lines conj message))]
      (f))
    @lines))

(deftest event-format-is-the-fail2ban-contract
  (testing "the line shape fail2ban's failregex depends on"
    (let [[line] (capture-security
                   #(security/event!
                      :auth/failure
                      {:ip "203.0.113.9"
                       :reason "bad-token"}))]
      (is (str/starts-with? line "SECURITY "))
      (is (str/includes? line "event=auth.failure"))
      (is (str/includes? line "ip=203.0.113.9"))
      (is (str/includes? line "reason=bad-token"))))
  (testing "a hostile field cannot inject a newline to forge a second event"
    (let [[line] (capture-security
                   #(security/event!
                      :auth/failure
                      {:ip "1.2.3.4\nSECURITY event=auth.success ip=1.2.3.4"}))]
      (is (= 1 (count (str/split-lines line))) "the forged newline was collapsed"))))

(deftest active-flag-bans-the-session
  ;; wrap-current-user is the ban lever: an explicitly-inactive user's
  ;; session stops authenticating on its next request — live, no redeploy.
  (let [conn h/*conn*
        _ @(db/transact* conn
             [{:db/id "u"
               :user/id (UUID/randomUUID)
               :user/email "banme@x.lan"
               :user/active? true
               :user/created-at (time/now)
               :user/terms-accepted-at (time/now)}])
        seen (atom nil)
        wrapped (routes/wrap-current-user
                  (fn [req]
                    (reset! seen (:user-eid req))
                    {:status 200}))
        req {:session {:user-email "banme@x.lan"}
             :headers {}}]
    (testing "active user authenticates" (wrapped req) (is (some? @seen) "resolved to a user-eid"))
    (testing "set-active! false deactivates the SAME session, live"
      (is (true? (auth/set-active! conn "banme@x.lan" false)))
      (reset! seen :untouched)
      (wrapped req)
      (is (nil? @seen) "the banned session no longer carries a user-eid"))
    (testing "re-enabling restores it — a reversible ban"
      (auth/set-active! conn "banme@x.lan" true)
      (reset! seen nil)
      (wrapped req)
      (is (some? @seen)))))

(deftest missing-active-flag-never-bans
  ;; Only an EXPLICIT false bans, so a schema gap can't lock everyone out.
  (let [conn h/*conn*
        _ @(db/transact* conn
             [{:db/id "u"
               :user/id (UUID/randomUUID)
               :user/email "noflag@x.lan"
               :user/created-at (time/now)
               :user/terms-accepted-at (time/now)}])
        seen (atom nil)
        wrapped (routes/wrap-current-user
                  (fn [req]
                    (reset! seen (:user-eid req))
                    {:status 200}))]
    (wrapped
      {:session {:user-email "noflag@x.lan"}
       :headers {}})
    (is (some? @seen) "a user with no active? flag still authenticates")))

(deftest verify-magic-link-emits-the-right-event-per-gate
  ;; The three-gate wiring, end to end through the handler: which gate
  ;; failed decides which event fires, and BOTH failures return a
  ;; byte-identical opaque 400 (don't-reveal intact).
  (let [signing-key (config/get-config :signing-key)
        {:keys [token nonce]} (auth/create-magic-link-token signing-key "gate@x.lan")
        _ (analytics/record!
            [{:magic-link/email "gate@x.lan"
              :magic-link/nonce nonce
              :magic-link/requested-at (time/now)}])
        verify (fn [tok]
                 (handler/verify-magic-link
                   {:client-ip "203.0.113.7"
                    :locale :en
                    :params {:token tok}}))]
    (testing "a forged token → auth.failure reason=bad-token, opaque 400"
      (let [[line] (capture-security #(verify "forged.garbage"))
            resp (verify "forged.garbage")]
        (is (str/includes? line "event=auth.failure"))
        (is (str/includes? line "reason=bad-token"))
        (is (str/includes? line "ip=203.0.113.7"))
        (is (= 400 (:status resp)))))
    (testing "a valid token → auth.success, then the SAME token replayed → auth.replay"
      (let [[ok-line] (capture-security #(verify token))]
        (is (str/includes? ok-line "event=auth.success"))
        (is (str/includes? ok-line "ip=203.0.113.7")))
      (let [[replay-line] (capture-security #(verify token))
            replay-resp (verify token)]
        (is
          (str/includes? replay-line "event=auth.replay")
          "the spent nonce is a replay, not a bad token")
        (is (= 400 (:status replay-resp)) "replay returns the same opaque 400 as a forgery")))))

(deftest tenancy-refusal-fires-on-a-cross-owner-mutation
  ;; The IDOR signal: a non-owner's edit against a recipe that EXISTS emits
  ;; :tenancy/refused and returns the same opaque 404 as a missing recipe.
  (let [owner (mk-user! "owner@x.lan")
        _attacker (mk-user! "attacker@x.lan")
        id (recipe/create!
             h/*conn*
             owner
             {:title "Not Yours"
              :servings 2
              :ingredients "a"
              :steps "b"})
        attack-req {:client-ip "198.51.100.4"
                    :locale :en
                    :user-email "attacker@x.lan"
                    :user-eid (auth/find-user-by-email (d/db h/*conn*) "attacker@x.lan")
                    :path-params {:id (str id)}
                    :params {:title "Hijacked"
                             :servings "2"
                             :ingredients "x"
                             :steps "y"}}]
    (testing "cross-owner update → tenancy.refused + opaque 404"
      (let [[line] (capture-security #(handler/recipe-update attack-req))
            resp (handler/recipe-update attack-req)]
        (is (some? line) "an event fired")
        (is (str/includes? line "event=tenancy.refused"))
        (is (str/includes? line "ip=198.51.100.4"))
        (is (= 404 (:status resp)) "same opaque 404 as a missing recipe — no existence leak")))
    (testing "a genuinely-missing recipe fires NO tenancy event (just a 404)"
      (let [missing (assoc-in attack-req [:path-params :id] (str (java.util.UUID/randomUUID)))
            lines (capture-security #(handler/recipe-update missing))]
        (is (empty? lines) "no false-positive IDOR signal for a plain 404")))))

(deftest admin-gate-emits-events-both-ways
  ;; Finding: admin.access / admin.denied had no coverage.
  (let [wrapped (routes/wrap-admin (constantly {:status 200}))]
    (testing "a granted admin logs admin.access with identity"
      (let [[line] (capture-security
                     #(wrapped
                        {:admin? true
                         :user-email "boss@x.lan"
                         :client-ip "203.0.113.1"
                         :uri "/admin"}))]
        (is (str/includes? line "event=admin.access"))
        (is (str/includes? line "ip=203.0.113.1"))))
    (testing "a denied non-admin logs admin.denied and redirects"
      (let [[line] (capture-security
                     #(wrapped
                        {:admin? false
                         :client-ip "198.51.100.9"
                         :uri "/admin"}))
            resp (wrapped
                   {:admin? false
                    :client-ip "198.51.100.9"
                    :uri "/admin"})]
        (is (str/includes? line "event=admin.denied"))
        (is (str/includes? line "ip=198.51.100.9"))
        (is (= 302 (:status resp)) "non-admin is redirected, not served")))))

(deftest signing-key-previous-config-wiring
  ;; Finding: rotation grace tested only as a pure fn — pin the config parse
  ;; that feeds it (a string SIGNING_KEY_PREVIOUS becomes bytes; unset → nil).
  (let [resolve-keys @#'config/resolve-keys
        with-prev (resolve-keys
                    {:session-key "0123456789abcdef"
                     :signing-key "0123456789abcdef0123456789abcdef"
                     :signing-key-previous "fedcba9876543210fedcba9876543210"}
                    :prod)
        without (resolve-keys
                  {:session-key "0123456789abcdef"
                   :signing-key "0123456789abcdef0123456789abcdef"
                   :signing-key-previous nil}
                  :prod)]
    (is (bytes? (:signing-key-previous with-prev)) "a set previous key resolves to bytes")
    (is (nil? (:signing-key-previous without)) "unset previous key stays nil (no grace)")))
