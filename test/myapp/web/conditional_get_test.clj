(ns myapp.web.conditional-get-test
  "The basis-t conditional GET.
  Anonymous HTML pages carry a validator the database already knows, and a
  matching If-None-Match short-circuits the render entirely."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.auth.core :as auth]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.test-helpers :as h]
    [myapp.time :as time]
    [myapp.web.routes :as routes])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-db h/with-test-analytics-db h/with-test-config)

(defn- mk-user!
  "Create an active user and return its eid."
  [email]
  @(db/transact* h/*conn*
     [{:user/id (UUID/randomUUID)
       :user/email email
       :user/active? true
       :user/created-at (time/now)
       :user/terms-accepted-at (time/now)}])
  (auth/find-user-by-email (d/db h/*conn*) email))

(defn- get-request
  "A bare anonymous GET through the full middleware stack."
  [uri & [headers]]
  {:request-method :get
   :uri uri
   :headers (or headers {})})

(deftest anonymous-html-carries-the-validator
  (let [resp (routes/app (get-request "/recipes"))
        etag (get-in resp [:headers "ETag"])]
    (is (= 200 (:status resp)))
    (is (some? etag) "the page is validated")
    (is (str/starts-with? etag "W/\"") "weak — same content, not same bytes")
    (is (= "no-cache" (get-in resp [:headers "Cache-Control"])) "always revalidate")
    (is
      (= "Accept-Language" (get-in resp [:headers "Vary"]))
      "the locale is part of the identity")))

(deftest matching-validator-short-circuits-to-304
  (let [first-resp (routes/app (get-request "/recipes"))
        etag (get-in first-resp [:headers "ETag"])
        replay (routes/app (get-request "/recipes" {"if-none-match" etag}))]
    (is (= 304 (:status replay)) "nothing changed, nothing rendered")
    (is (nil? (:body replay)))
    (is (= etag (get-in replay [:headers "ETag"])))))

(deftest a-transaction-invalidates-every-page
  (let [before (get-in (routes/app (get-request "/recipes")) [:headers "ETag"])
        u (mk-user! "etag@x.lan")]
    (recipe/create!
      h/*conn*
      u
      {:title "Cache Buster"
       :servings 1})
    (let [resp (routes/app (get-request "/recipes" {"if-none-match" before}))]
      (is (= 200 (:status resp)) "the old validator no longer matches")
      (is (not= before (get-in resp [:headers "ETag"])))
      (is (str/includes? (:body resp) "Cache Buster") "…because the world changed"))))

(deftest stated-cache-policies-are-left-alone
  (testing "signed-in requests never enter the conditional path"
    (let [wrapped (routes/wrap-conditional-get
                    (constantly
                      {:status 200
                       :headers {"Content-Type" "text/html"}
                       :body "x"}))
          resp (wrapped
                 {:request-method :get
                  :uri "/x"
                  :locale :en
                  :session {:user-email "someone@x.lan"}})]
      (is (nil? (get-in resp [:headers "ETag"])))))
  (testing "a response that already states its policy keeps it"
    (let [wrapped (routes/wrap-conditional-get
                    (constantly
                      {:status 200
                       :headers {"Content-Type" "text/html"
                                 "Cache-Control" "private, max-age=31536000, immutable"}
                       :body "x"}))
          resp (wrapped
                 {:request-method :get
                  :uri "/x"
                  :locale :en
                  :headers {}})]
      (is (nil? (get-in resp [:headers "ETag"])))
      (is (= "private, max-age=31536000, immutable" (get-in resp [:headers "Cache-Control"])))))
  (testing "non-HTML passes untouched"
    (let [wrapped (routes/wrap-conditional-get
                    (constantly
                      {:status 200
                       :headers {"Content-Type" "text/css"}
                       :body "x"}))
          resp (wrapped
                 {:request-method :get
                  :uri "/s.css"
                  :locale :en
                  :headers {}})]
      (is (nil? (get-in resp [:headers "ETag"]))))))
