(ns myapp.auth.core
  "Passwordless authentication via HMAC-signed magic link tokens.
  Handles token creation/verification and user account management in Datomic.
  No passwords are stored — authentication is purely token-based via email."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.time :as time])
  (:import
    [java.security MessageDigest]
    [java.time Instant]
    [java.util Base64 UUID]
    [javax.crypto Mac]
    [javax.crypto.spec SecretKeySpec]))

(set! *warn-on-reflection* true)

(defn hmac-sha256
  "Compute HMAC-SHA256 of data using secret key."
  ^bytes [^bytes secret-key ^String data]
  (let [^Mac mac (Mac/getInstance "HmacSHA256")
        secret-key-spec (SecretKeySpec. secret-key "HmacSHA256")]
    (.init mac secret-key-spec)
    (.doFinal mac (.getBytes data "UTF-8"))))

(defn base64-encode
  "Base64 URL-safe encode."
  ^String [^bytes data]
  (let [^java.util.Base64$Encoder encoder (.withoutPadding (Base64/getUrlEncoder))]
    (.encodeToString encoder data)))

(defn base64-decode
  "Base64 URL-safe decode."
  ^bytes [^String data]
  (.decode (Base64/getUrlDecoder) data))

(defn sign-token
  "Create a signed token carrying email, expiration, and a nonce.

  Format: base64(payload).base64(signature)
  Payload: {\"email\": \"...\", \"exp\": <ms>, \"nonce\": \"<uuid>\"}

  The nonce is what makes the token one-shot: it's recorded server-side
  on issuance and CAS-stamped on verification, so replaying the token
  fails CAS even though the HMAC still validates."
  [signing-key email ^Instant expires-at nonce]
  (let [payload-map {:email email
                     :exp (.toEpochMilli expires-at)
                     :nonce (str nonce)}
        ^String payload-json (json/write-str payload-map)
        payload-b64 (base64-encode (.getBytes payload-json "UTF-8"))
        signature (hmac-sha256 signing-key payload-b64)
        signature-b64 (base64-encode signature)]
    (str payload-b64 "." signature-b64)))

(defn- verify-token-1
  "Verify `token` against ONE `signing-key`; nil on any failure.
  Extracted so `verify-token` can try a key-rotation grace set."
  [signing-key token]
  (try
    (let [[payload-b64 signature-b64] (str/split token #"\." 2)]
      (when (and payload-b64 signature-b64)
        ;; Verify signature with a constant-time compare on the raw bytes.
        ;; `=` on the base64 strings is content-dependent and short-circuits,
        ;; leaking timing; `MessageDigest/isEqual` runs in time independent of
        ;; where the first mismatching byte is. (A malformed base64 signature
        ;; throws in `base64-decode` and is caught below as an invalid token.)
        (let [expected-signature (hmac-sha256 signing-key payload-b64)
              actual-signature (base64-decode signature-b64)]
          (when (MessageDigest/isEqual expected-signature actual-signature)
            ;; Signature valid, check expiration
            (let [payload-json (String. ^bytes (base64-decode payload-b64) "UTF-8")
                  payload (json/read-str payload-json :key-fn keyword)
                  exp-time (long (:exp payload))
                  now (.toEpochMilli (time/now))]
              (when (> exp-time now)
                {:email (:email payload)
                 :nonce (:nonce payload)}))))))
    (catch Exception _e nil)))

(defn verify-token
  "Verify signed token and extract claims.

  Returns `{:email \"...\" :nonce \"<uuid-string>\"}` if valid, nil if
  invalid or expired. The HMAC + expiration check happen here; the
  caller is responsible for the one-shot replay check via `:nonce`.

  `signing-key` may be a single key (bytes) OR a sequence of accepted
  keys. The sequence is the rotation grace window: sign new tokens with
  the new key, keep the old one in the accepted set until every
  outstanding link has aged past its 15-minute TTL, then drop it — so the
  key rotates without breaking a single in-flight login. Claims return if
  ANY accepted key validates; the constant-time compare runs per key."
  [signing-key token]
  (let [key-set (if (bytes? signing-key) [signing-key] signing-key)]
    (some #(verify-token-1 % token) key-set)))

(defn mark-activity-seen!
  "Advance the user's activity cursor to now.
  The dashboard calls this after computing the feed, so 'since your last
  visit' is true by construction — the cursor always trails the render."
  [conn user-eid]
  @(db/transact* conn
     [{:db/id user-eid
       :user/activity-seen-at (time/now)}]))

(defn find-user-by-email
  "Find user by email address."
  [db email]
  (d/q '[:find ?e . :in $ ?email :where [?e :user/email ?email]] db email))

(defn set-active!
  "Enable or disable a user account by email — the operator's live ban lever.
  A deactivated account stops authenticating on its session's next request
  (see `wrap-current-user`), with no redeploy and no key rotation; flip it
  back and the same session works again. Returns true if a user matched.
  Deliberately NOT a web endpoint: the admin surface is read-only, and a
  privileged mutation route would be attack surface of its own — this is a
  runbook/REPL action (see the detection chapter's containment section)."
  [conn email active?]
  (when-let [eid (find-user-by-email (d/db conn) email)]
    @(db/transact* conn [[:db/add eid :user/active? active?]])
    true))

(defn create-user!
  "Create a new user account."
  [conn email]
  (let [user-id (UUID/randomUUID)
        now (time/now)]
    @(db/transact* conn
       [{:db/id "temp-user"
         :user/id user-id
         :user/email email
         :user/created-at now
         :user/active? true}])
    user-id))

(defn create-magic-link-token
  "Create a signed magic-link token + the nonce embedded in it.

  Returns `{:token <signed-token-string> :nonce <UUID>}`. The caller
  must record the nonce in analytics before sending the link to the
  user; verification looks the nonce up there to enforce one-shot use."
  [signing-key email]
  (let [^Instant expires-at (.plusSeconds (time/now) (* 15 60))
        nonce (UUID/randomUUID)]
    {:token (sign-token signing-key email expires-at nonce)
     :nonce nonce}))

(defn get-or-create-user!
  "Get existing user or create new one, return email."
  [conn email]
  (let [db (d/db conn)]
    (when-not (find-user-by-email db email) (create-user! conn email))
    email))

(comment
  ;; REPL testing
  (require '[myapp.config :as config])
  (def conn
    (db/create-database!))
  (def signing-key
    (config/get-config :signing-key))
  ;; Create user and signed token
  (def email
    (get-or-create-user! conn "test@example.com"))
  (def link
    (create-magic-link-token signing-key email))
  ;; Verify token
  (verify-token signing-key (:token link))
  ;; Try with expired token (manual test)
  (def expired-token
    (sign-token signing-key "test@example.com" (.minusSeconds (time/now) 1) (UUID/randomUUID)))
  (verify-token signing-key expired-token))
