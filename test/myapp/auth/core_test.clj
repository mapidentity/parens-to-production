(ns myapp.auth.core-test
  "Tests for HMAC token signing/verification, base64 encoding, and user account CRUD."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.auth.core :as auth]
    [myapp.test-helpers :as h]
    [myapp.time :as time])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-db)

;; --- base64 ---

(deftest base64-roundtrip
  (let [data (.getBytes "hello world" "UTF-8")
        encoded (auth/base64-encode data)
        decoded (auth/base64-decode encoded)]
    (is (= (seq data) (seq decoded)))))

(deftest base64-url-safe
  (testing "No +, /, or = in output"
    ;; 5 bytes (length not a multiple of 3) whose STANDARD base64 is "/+gA+/A=":
    ;; it carries +, /, and a trailing = pad. URL-safe encoding must swap +/ for
    ;; -_, and .withoutPadding must drop the = — so none of the three may appear.
    ;; (A multiple-of-3 length never pads, which would make the = check vacuous.)
    (let [encoded (auth/base64-encode (byte-array (map unchecked-byte [255 232 0 251 240])))]
      (is (not (str/includes? encoded "+")))
      (is (not (str/includes? encoded "/")))
      (is (not (str/includes? encoded "="))))))

;; --- sign-token / verify-token ---

(deftest sign-token-format
  (let [token
        (auth/sign-token
          h/test-signing-key
          "test@example.com"
          (.plusSeconds (time/now) 3600)
          (UUID/randomUUID))]
    (is (string? token))
    (let [parts (str/split token #"\.")]
      (is (= 2 (count parts)) "Token should have exactly one dot separator")
      (is (every? #(pos? (count %)) parts) "Both parts should be non-empty"))))

(deftest verify-token-roundtrip
  (let [nonce (UUID/randomUUID)
        token
        (auth/sign-token h/test-signing-key "user@example.com" (.plusSeconds (time/now) 3600) nonce)
        result (auth/verify-token h/test-signing-key token)]
    (is
      (=
        {:email "user@example.com"
         :nonce (str nonce)}
        result)
      "verify-token returns email AND the nonce, so the caller can CAS-stamp it as consumed")))

(deftest verify-token-expired
  (let [token
        (auth/sign-token
          h/test-signing-key
          "user@example.com"
          (.minusSeconds (time/now) 1)
          (UUID/randomUUID))]
    (is (nil? (auth/verify-token h/test-signing-key token)))))

(deftest verify-token-tampered-payload
  (let [token
        (auth/sign-token
          h/test-signing-key
          "user@example.com"
          (.plusSeconds (time/now) 3600)
          (UUID/randomUUID))
        [_payload sig] (str/split token #"\.")
        ;; Replace payload with different base64
        tampered (str
                   (auth/base64-encode
                     (.getBytes "{\"email\":\"evil@example.com\",\"exp\":9999999999999}" "UTF-8"))
                   "."
                   sig)]
    (is (nil? (auth/verify-token h/test-signing-key tampered)))))

(deftest verify-token-tampered-signature
  (let [token
        (auth/sign-token
          h/test-signing-key
          "user@example.com"
          (.plusSeconds (time/now) 3600)
          (UUID/randomUUID))
        [payload _sig] (str/split token #"\.")]
    (is (nil? (auth/verify-token h/test-signing-key (str payload ".AAAA"))))))

(deftest verify-token-wrong-key
  (let [other-key (.getBytes "other-signing-key-32-bytes-long!" "UTF-8")
        token
        (auth/sign-token
          h/test-signing-key
          "user@example.com"
          (.plusSeconds (time/now) 3600)
          (UUID/randomUUID))]
    (is (nil? (auth/verify-token other-key token)))))

(deftest verify-token-garbage-input
  (is (nil? (auth/verify-token h/test-signing-key nil)))
  (is (nil? (auth/verify-token h/test-signing-key "")))
  (is (nil? (auth/verify-token h/test-signing-key "not-a-token")))
  (is (nil? (auth/verify-token h/test-signing-key "abc.def.ghi"))))

;; --- find-user-by-email ---

(deftest find-user-not-found
  (let [db (d/db h/*conn*)]
    (is (nil? (auth/find-user-by-email db "nobody@example.com")))))

;; --- create-user! ---

(deftest create-user-and-find
  (let [user-id (auth/create-user! h/*conn* "new@example.com")
        db (d/db h/*conn*)]
    (is (instance? UUID user-id))
    (is (some? (auth/find-user-by-email db "new@example.com")))))

(deftest create-user-duplicate-upserts
  ;; :db.unique/identity causes upsert, not exception
  (auth/create-user! h/*conn* "dup@example.com")
  (auth/create-user! h/*conn* "dup@example.com")
  (let [db (d/db h/*conn*)
        n (d/q
            '[:find (count ?e) .
              :in $ ?email
              :where [?e :user/email ?email]]
            db
            "dup@example.com")]
    (is (= 1 n) "Should have exactly one entity for the email")))

;; --- get-or-create-user! ---

(deftest get-or-create-user-creates-new
  (let [email (auth/get-or-create-user! h/*conn* "fresh@example.com")
        db (d/db h/*conn*)]
    (is (= "fresh@example.com" email))
    (is (some? (auth/find-user-by-email db "fresh@example.com")))))

(deftest get-or-create-user-idempotent
  (auth/create-user! h/*conn* "existing@example.com")
  (let [email (auth/get-or-create-user! h/*conn* "existing@example.com")]
    (is (= "existing@example.com" email))))

(deftest verify-token-rotation-grace
  ;; A signing-key rotation must not break in-flight magic links: keep the
  ;; old key in the accepted set during the grace window.
  (let [old-key (.getBytes "old-signing-key-32-bytes-long!!!" "UTF-8")
        new-key (.getBytes "new-signing-key-32-bytes-long!!!" "UTF-8")
        token-signed-with-old (:token (auth/create-magic-link-token old-key "grace@x.lan"))]
    (is
      (nil? (auth/verify-token new-key token-signed-with-old))
      "the new key alone rejects an old-key token")
    (is
      (some? (auth/verify-token [new-key old-key] token-signed-with-old))
      "the grace set [new old] accepts it — no login broken during rotation")
    (is (= "grace@x.lan" (:email (auth/verify-token [new-key old-key] token-signed-with-old))))
    (is
      (nil? (auth/verify-token [new-key] token-signed-with-old))
      "once the grace window closes (old key dropped), the old token is dead")))
