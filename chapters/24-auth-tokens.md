# Passwordless Auth Part 1: HMAC-Signed Magic Link Tokens

Passwords are a liability. They get reused, leaked, phished, and forgotten. For a small SaaS, they also mean building password reset flows, hashing infrastructure, and breach notification procedures. That is a lot of surface area for something your users already hate.

Magic links sidestep all of this. The user enters their email, gets a link, clicks it, and they are in. No password to remember, no credential to steal, no database of hashed secrets waiting to be breached. The email inbox becomes the authenticator.

## The trade, and where you'd raise the bar

That is also the whole cost, stated plainly. A magic link is a single factor -- possession of that inbox -- so an account is only as strong as the email protecting it, and a phished or hijacked mailbox is a hijacked account. By the current federal bar (NIST SP 800-63-4, final July 2025) an emailed link is not even a permitted out-of-band channel and is not phishing-resistant: nothing binds the link to the site that asked for it or the session waiting on it, so anyone who obtains it (by phishing, by reading the inbox, by a corporate mail-scanner) can complete the login. That is the same property, an authenticator output not bound to the session, that makes a TOTP code single-factor and phishable; a magic link is no stronger. What it buys back is real: no password reuse, credential stuffing, reset flows, hashing, or password-table breach. So this is a trade of password risk for email risk, not a clean win. For a recipe box whose users sign in from personal email it is the right trade, because that inbox is already the anchor those accounts recover through.

When the stakes rise, the upgrade is not a second password-era factor but a **passkey** (WebAuthn/FIDO2): a public-key credential bound to your domain, phishing-resistant by construction, and now mainstream -- billions in use by 2026, backed by Apple, Google, and Microsoft. The on-brand shape here is to keep the magic link as the bootstrap-and-recovery path and add a passkey as the phishing-resistant primary for returning users, enrolled after first login; the seam is the verify step that issues the session. We do not build it: the recipe box does not warrant it, and doing it properly (the WebAuthn ceremony, credential storage, the cross-device and lost-device recovery cases) is its own chapter. And one honest limit survives even then: recovery still falls back to email, so a passkey raises the login bar without lifting the recovery bar, and the email channel still needs hardening. (A TOTP app on top is possible but weaker -- a shared secret, still phishable, and it leaves that recovery ceiling untouched; SMS is weaker still, and NIST now restricts it.)

And when identity stops being one app's feature and becomes a system, you stop rolling your own and reach for an identity provider -- but the threshold is capability, not head count. It is warranted when enterprise customers demand SSO into their own directory (SAML or OIDC federation), when several of your services must share one sign-on plane, when you need delegated administration or SCIM provisioning, or when MFA and audit policy have to be enforced centrally for compliance. Below that line (one app, your own users, email identity) an IdP is a net loss: **Keycloak** (the de-facto open-source choice, CNCF-incubating, speaking OIDC, OAuth2, and SAML, federating LDAP/AD) is a heavy stateful service you now patch on its own CVE cadence, fail over, back up, and re-validate every upgrade -- its own attack surface and an availability dependency, bought for federation you are not doing. Above the line it is the right call, and the buy-don't-run tier (Auth0, now part of Okta; WorkOS for enterprise SSO; Clerk; Stytch) trades ownership for someone else running it. This book's road is the one below the line, built to be understood; naming the line is what tells you when you have crossed it.

This chapter covers the first half of the implementation: building the cryptographic token that powers those magic links. We will handle signing, verification, expiry, and user management -- all backed by tests. The next chapter wires this into HTTP routes, email delivery, and sessions.

## Why HMAC tokens instead of JWTs?

JWTs are the default answer for signed tokens, and for good reason -- they are standardized, well-understood, and interoperable. But interoperability is a feature you pay for in complexity, and in a system where you are both the producer and consumer of every token, that tax buys you nothing.

Here is what a JWT library brings: header parsing, algorithm negotiation, claim validation, multiple signature schemes, and a spec that has produced a steady stream of security vulnerabilities over the years (algorithm confusion attacks, `"alg": "none"`, key confusion between HMAC and RSA). That is a lot of moving parts for what amounts to "sign some JSON and check the signature later."

Our needs are simpler:

- One signing algorithm (HMAC-SHA256)
- One producer and one consumer (our server)
- Three claims (email, expiration, and a one-time nonce we introduce shortly)
- No need for third-party verification

For this, the signing-and-verification pair is fewer than 40 lines of Clojure, uses only `javax.crypto` from the JDK (no dependencies), and has zero ambiguity about what algorithm is in use. The format is dead simple: `base64(payload).base64(signature)`. You can read it, reason about it, and audit it in minutes.

That said -- if you ever need tokens that cross service boundaries, or that third parties need to verify, reach for a proper JWT library. The right tool depends on the problem.

## The token format

Our tokens follow a two-part structure separated by a dot:

```
base64url({"email":"user@example.com","exp":1709654400000,"nonce":"3f2a…"}).base64url(hmac-sha256-signature)
```

The left side is the payload -- a JSON object with the user's email, an expiration timestamp in milliseconds, and a one-time nonce whose purpose we take up later in the chapter. The right side is the HMAC-SHA256 signature computed over the base64-encoded payload. Both sides use URL-safe base64 encoding (no `+`, `/`, or `=` characters), which matters because these tokens live in URLs.

To verify a token, you recompute the signature from the payload using your secret key and compare. If the signatures match, the payload has not been tampered with. Then you check the expiration. Two checks, no ambiguity.

## Crypto primitives: HMAC-SHA256 and Base64

The low-level building blocks come first. Java's `javax.crypto` package gives us everything we need, so nothing third-party touches the signing path.

```clojure
(ns myapp.auth.core
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
```

A quick note on `*warn-on-reflection*`: this tells the Clojure compiler to warn when it cannot resolve a Java method at compile time and would fall back to reflection. Reflection is slow and, in crypto code that may run on every request, worth avoiding. The type hints you will see (like `^bytes` and `^String`) are how we help the compiler.

Now the HMAC function:

```clojure
(defn hmac-sha256
  "Compute HMAC-SHA256 of data using secret key."
  ^bytes [^bytes secret-key ^String data]
  (let [^Mac mac (Mac/getInstance "HmacSHA256")
        secret-key-spec (SecretKeySpec. secret-key "HmacSHA256")]
    (.init mac secret-key-spec)
    (.doFinal mac (.getBytes data "UTF-8"))))
```

Three lines of Java interop, no external dependencies. `Mac/getInstance` gives us an HMAC engine. `SecretKeySpec` wraps our raw key bytes into something the JCA (Java Cryptography Architecture) understands. We initialize the engine with our key, then feed it the data. Out comes a byte array -- the signature.

The `^Mac`, `^bytes`, and `^String` type hints are not just documentation. Without them, Clojure would fall back to reflection to resolve the `.init`, `.doFinal`, and `.getBytes` calls at runtime (the `^Mac` hint on the `mac` local is what lets the compiler resolve `.init` and `.doFinal` directly instead of reflecting on each call), which is both slower and noisier in the logs.

Next, base64 encoding and decoding:

```clojure
(defn base64-encode
  "Base64 URL-safe encode."
  ^String [^bytes data]
  (let [^java.util.Base64$Encoder encoder (.withoutPadding (Base64/getUrlEncoder))]
    (.encodeToString encoder data)))

(defn base64-decode
  "Base64 URL-safe decode."
  ^bytes [^String data]
  (.decode (Base64/getUrlDecoder) data))
```

We use `getUrlEncoder` / `getUrlDecoder` rather than the standard variants. Standard base64 uses `+` and `/`, which have special meaning in URLs. The URL-safe variant substitutes `-` and `_`. We also strip padding (`=` characters) since the decoder handles unpadded input just fine, and padding adds noise to URLs.

## Signing tokens

With the primitives in place, signing is straightforward:

```clojure
(defn sign-token
  "Create a signed token carrying email, expiration, and a nonce.
   Format: base64(payload).base64(signature)
   Payload: {\"email\": \"...\", \"exp\": <ms>, \"nonce\": \"<uuid>\"}"
  [signing-key email ^Instant expires-at nonce]
  (let [payload-map {:email email
                     :exp (.toEpochMilli expires-at)
                     :nonce (str nonce)}
        ^String payload-json (json/write-str payload-map)
        payload-b64 (base64-encode (.getBytes payload-json "UTF-8"))
        signature (hmac-sha256 signing-key payload-b64)
        signature-b64 (base64-encode signature)]
    (str payload-b64 "." signature-b64)))
```

The construction reads left to right: a map of the email, the expiration as epoch milliseconds, and a one-time **nonce** is serialized to JSON, base64-encoded to become the token's left side, HMAC-SHA256-signed under our signing key, the signature base64-encoded as the right side, and the two joined with a dot. The nonce is a random UUID whose purpose we return to in a moment: it is what turns an unforgeable-but-replayable token into a single-use credential.

An important detail: we sign the base64-encoded payload, not the raw JSON. Verification then recomputes the HMAC over the bytes that arrived -- the payload half of the token as received, with nothing decoded or re-serialized between the wire and the comparison. It also means the payload is never base64-decoded, let alone parsed as JSON, until its signature has passed: untrusted input is not interpreted until it is authenticated. (The signature half does get decoded before the comparison, but turning base64 into raw bytes for the equality check is all that ever happens to it.)

## Verifying tokens

Verification is the mirror image of signing, with two additional checks: signature validity and expiration.

```clojure
(defn verify-token
  "Verify signed token and extract claims.
   Returns {:email \"...\" :nonce \"<uuid-string>\"} if valid,
   nil if invalid or expired."
  [signing-key token]
  (try
    (let [[payload-b64 signature-b64] (str/split token #"\." 2)]
      (when (and payload-b64 signature-b64)
        ;; Verify signature with a constant-time compare on the raw bytes.
        (let [expected-signature (hmac-sha256 signing-key payload-b64)
              actual-signature (base64-decode signature-b64)]
          (when (MessageDigest/isEqual expected-signature actual-signature)
            ;; Signature valid, check expiration
            (let [payload-json (String. ^bytes (base64-decode payload-b64) "UTF-8")
                  payload (json/read-str payload-json :key-fn keyword)
                  exp-time (long (:exp payload))
                  now (.toEpochMilli (time/now))]
              (when (> exp-time now)
                ;; Not expired -- hand the caller the email and the nonce.
                ;; verify-token proves the token is authentic and fresh; the
                ;; nonce lets the caller enforce single use (next chapter).
                {:email (:email payload)
                 :nonce (:nonce payload)}))))))
    (catch Exception _e nil)))
```

The order of operations is the security-relevant part -- each step is a gate the token has to pass before the next one runs, and the cheap, tamper-proof checks come first:

1. **Split the token** on the first dot. We pass `2` as the limit to `str/split` so that any dots in the signature do not cause extra parts.
2. **Check both parts exist.** If the token is malformed (no dot, empty parts), bail early with `nil`.
3. **Recompute the signature** from the payload using our signing key and compare it to the provided signature with `MessageDigest/isEqual`, a constant-time byte comparison. If they do not match, someone tampered with either the payload or the signature -- return `nil`.
4. **Decode the payload** and parse the JSON. Only now do we touch the payload contents, after we have confirmed they are authentic.
5. **Check expiration.** If the current time is past the expiry, the token is dead -- return `nil`.
6. **Return the email and the nonce** if everything checks out. The signature and expiry are settled here; the nonce travels back to the caller, who is responsible for the one-shot replay check (see the next chapter).

The entire function is wrapped in a `try/catch` that returns `nil` on any exception. Malformed base64, invalid JSON, missing keys: all produce `nil`. This is a deliberate design choice: from the caller's perspective, a token is either valid (returns a map) or it is not (returns `nil`). There is no need to distinguish between "expired" and "tampered" and "garbage" -- they all mean "do not authenticate this request."

### Constant-time comparison

We compare signatures with `MessageDigest/isEqual` rather than `=`. Clojure's `=` on strings (or `java.util.Arrays/equals` on bytes) short-circuits at the first differing byte, so the time it takes to reject a forgery leaks how many leading bytes were guessed correctly -- the classic byte-at-a-time timing oracle. `MessageDigest/isEqual` compares every byte regardless, in time independent of where the first mismatch is. It costs nothing here and removes the question entirely, so there is no reason to reach for `=` and then argue about whether the leak is exploitable. Comparing the raw HMAC bytes (we decode the incoming signature first) is the standard form; a malformed base64 signature throws in `base64-decode` and is caught as an invalid token.

> **One caveat on `isEqual`.** "Constant time" has a precise shape here. On the Temurin 25 this project pins, a length mismatch does *not* short-circuit: the implementation folds the length difference into its running result (`result |= lenA - lenB`) and still walks every byte of its *first* argument, so the comparison time depends only on that argument's length. We pass the freshly recomputed HMAC first, and that is always 32 bytes, so rejection takes the same time whether a forged signature has the wrong bytes or the wrong length. (The one early exit for non-null inputs is an empty second argument, a token with nothing after the dot, and "you sent no signature" is not a leak.) The caveat is about reuse: because timing tracks the first argument's length, handing this helper two variable-length, secret-dependent inputs can turn a secret length into an oracle, so hash both sides to a fixed width first.

### A note on the signing key

The security of the whole scheme rests on the signing key being secret and strong. HMAC-SHA256 should be keyed with at least as much entropy as its 256-bit output, so the key must be **at least 32 bytes from a CSPRNG** -- not a passphrase, not a short string. `myapp.config` enforces this at boot (it throws if the resolved key is under 32 bytes) and, in production, refuses to start without an explicitly configured `SIGNING_KEY` rather than inventing a random one that would differ across instances. A short or guessable key would let an attacker forge tokens outright, which no amount of constant-time comparison can defend against.

## Token expiry

Tokens get a 15-minute window. This is a convenience function that wraps `sign-token` with the expiry logic:

```clojure
(defn create-magic-link-token
  "Create a signed magic-link token plus the nonce embedded in it.
   Returns {:token <signed-string> :nonce <UUID>}. The caller records the
   nonce server-side before sending the link; verification consumes it once."
  [signing-key email]
  (let [^Instant expires-at (.plusSeconds (time/now) (* 15 60))
        nonce (UUID/randomUUID)]
    {:token (sign-token signing-key email expires-at nonce)
     :nonce nonce}))
```

Why 15 minutes? Long enough that the email can survive a slow mail server or a user who gets distracted. Short enough that a token sitting in someone's inbox is not a long-lived credential. This is a judgment call, not a science -- adjust based on your users' behavior.

Notice that the expiry is baked into the signed payload itself. There is no database lookup needed to check if a token has expired. The token carries its own expiration, and the signature guarantees it has not been modified. This is one of the key advantages of signed tokens: verification is a pure function. No database, no cache, no network call. Just bytes and math.

### Why a nonce? The replay problem

A signed token has one weakness an attacker can still exploit: it is *valid until it expires*, and "valid" means "valid every time." If a magic link leaks (a forwarded email, a proxy log, a shared inbox) anyone holding it can log in, again and again, for the full 15-minute window. Expiry shortens that window; it does not close it.

We considered three ways to make a link single-use:

- **Store the whole token server-side and delete it on use.** Works, but it throws away the main advantage of signed tokens -- that they are self-contained. Now every issuance is a database write of an opaque blob, and verification is a lookup.
- **Track "consumed" tokens by their signature.** Smaller than storing the token, but the signature is long and the semantics are awkward -- we would be indexing on a base64 HMAC.
- **Embed a small random nonce and track that.** The token stays self-contained and pure-function-verifiable; we only persist a tiny UUID per issuance and flip it from "unused" to "used" exactly once.

We chose the nonce. `create-magic-link-token` therefore returns *two* things: the token to email, and the nonce to record. The signing side is now complete -- the next chapter records the nonce at send time and atomically consumes it at verify time, which is where "single use" actually gets enforced.

## User management in Datomic

With tokens handled, we need somewhere to put the users. Datomic makes this pleasantly simple.

### Finding a user

```clojure
(defn find-user-by-email
  "Find user by email address."
  [db email]
  (d/q '[:find ?e . :in $ ?email :where [?e :user/email ?email]] db email))
```

This is a Datalog query. The `. ` after `?e` in the find clause is a scalar binding -- it returns the entity ID directly instead of wrapping it in a set of tuples. If no user exists with that email, it returns `nil`.

The query takes `db` (an immutable database value) rather than a connection. This is idiomatic Datomic: functions that read take a database value, functions that write take a connection. Database values are immutable snapshots, so queries are inherently thread-safe and reproducible.

### Creating a user

```clojure
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
```

Four small things are doing real work in those eight lines:

- **`transact*`** is a thin wrapper around `d/transact` that converts `java.time.Instant` values to `java.util.Date`, which is what Datomic stores internally. It lets us use the modern Java time API everywhere else.
- **`"temp-user"`** is a temporary ID. Datomic assigns the real entity ID during the transaction. We do not need to track it -- we identify users by their `:user/email` or `:user/id` going forward.
- **`@`** dereferences the future returned by `d/transact`, making the call synchronous. The transaction either succeeds or throws.
- **`:user/email`** is defined as `:db.unique/identity` in our schema, which means Datomic enforces uniqueness and will upsert (update rather than duplicate) if we transact a new entity with an email that already exists.

### Get or create

The login flow needs a function that finds an existing user or creates one. With Datomic's upsert behavior on unique identity attributes, this is clean:

```clojure
(defn get-or-create-user!
  "Get existing user or create new one, return email."
  [conn email]
  (let [db (d/db conn)]
    (when-not (find-user-by-email db email) (create-user! conn email))
    email))
```

If the user exists, we skip the transaction entirely. If they do not, we create them. Either way, we return the email. This function is what the magic link handler will call when a user clicks a valid token -- ensuring they have an account before we create their session.

One property of this function should be stated rather than assumed. The find runs against a snapshot and the create is a separate transaction, so two concurrent first logins for the same address can both see no user and both call `create-user!`. The email upsert from the previous section keeps that race from duplicating the entity, but the merge has a cost: `:user/id` and `:user/created-at` are cardinality-one, so the second transaction overwrites both, and the user's supposedly stable UUID rotates out from under it. Today that costs nothing: the session the next chapter builds identifies the user by email, and nothing in the application reads `:user/id`. The day something does hold a reference to that UUID, this check-then-act window becomes a bug. The fix then is to move the conditional create into a transaction function, where check and write run atomically inside the transactor -- the same move the next chapter makes with `:db.fn/cas` to consume the nonce.

## Testing

Tests are where the design proves itself, and a signing primitive earns trust less by what it accepts than by what it refuses. The suite below is weighted accordingly: a couple of round-trip tests to show the happy path works, and then a battery of rejection tests -- expired, tampered, wrong-key, garbage -- because those are the cases an attacker actually probes.

### Test setup

Tests run against a fresh in-memory Datomic database per test, with a deterministic signing key:

```clojure
(ns myapp.auth.core-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.auth.core :as auth]
    [myapp.test-helpers :as h]
    [myapp.time :as time])
  (:import
    [java.util UUID]))

(use-fixtures :each h/with-test-db)
```

The `with-test-db` fixture creates a disposable in-memory Datomic database with the full schema loaded, binds it to a dynamic var, and tears it down after each test. No shared state between tests, no cleanup headaches.

The test signing key is a fixed 32-byte string, defined once in the shared test-helpers namespace (aliased `h` in these tests, which is why every call below reads `h/test-signing-key`):

```clojure
;; in myapp.test-helpers
(def test-signing-key
  (.getBytes "test-signing-key-32-bytes-long!!" "UTF-8"))
```

Deterministic keys in tests mean deterministic results. No randomness, no flakiness.

### Base64 correctness

First, we verify our base64 implementation round-trips correctly and produces URL-safe output:

```clojure
(deftest base64-roundtrip
  (let [data (.getBytes "hello world" "UTF-8")
        encoded (auth/base64-encode data)
        decoded (auth/base64-decode encoded)]
    (is (= (seq data) (seq decoded)))))

(deftest base64-url-safe
  (testing "No +, /, or = in output"
    (let [encoded (auth/base64-encode
                    (byte-array (map unchecked-byte [255 232 0 251 240])))]
      (is (not (str/includes? encoded "+")))
      (is (not (str/includes? encoded "/")))
      (is (not (str/includes? encoded "="))))))
```

The second test is particularly important. The five-byte sequence `[255 232 0 251 240]` is specifically chosen to produce all three of `+`, `/`, and `=` in standard base64 -- it encodes to `/+gA+/A=`. The `=` appears in the test only because of the length: base64 pads only when the input is not a multiple of three bytes, so a five-byte input is what actually exercises `.withoutPadding`. (A six-byte input emits no `=` no matter its contents, which would make that assertion vacuous -- it can only ever pass.) If URL-safe, unpadded encoding is working, none of the three characters should appear.

### Token format and round-trip

```clojure
(deftest sign-token-format
  (let [token (auth/sign-token h/test-signing-key "test@example.com"
                (.plusSeconds (time/now) 3600) (UUID/randomUUID))]
    (is (string? token))
    (let [parts (str/split token #"\.")]
      (is (= 2 (count parts)) "Token should have exactly one dot separator")
      (is (every? #(pos? (count %)) parts) "Both parts should be non-empty"))))

(deftest verify-token-roundtrip
  (let [nonce (UUID/randomUUID)
        token (auth/sign-token h/test-signing-key "user@example.com"
                (.plusSeconds (time/now) 3600) nonce)
        result (auth/verify-token h/test-signing-key token)]
    (is (= {:email "user@example.com" :nonce (str nonce)} result))))
```

The format test confirms structural correctness: it is a string, it has exactly two parts separated by a dot, and neither part is empty. The round-trip test confirms semantic correctness: sign a token, verify it, get back the original email *and* the nonce -- the caller needs the nonce to enforce single use.

### Failure modes

These are the tests that matter most: each hands `verify-token` a token that is wrong in a different way and expects a clean `nil`.

**Expired tokens:**

```clojure
(deftest verify-token-expired
  (let [token (auth/sign-token h/test-signing-key "user@example.com"
                (.minusSeconds (time/now) 1) (UUID/randomUUID))]
    (is (nil? (auth/verify-token h/test-signing-key token)))))
```

Create a token that expired one second ago. Verification returns `nil`. Simple.

**Tampered payload:**

```clojure
(deftest verify-token-tampered-payload
  (let [token (auth/sign-token h/test-signing-key "user@example.com"
                (.plusSeconds (time/now) 3600) (UUID/randomUUID))
        [_payload sig] (str/split token #"\.")
        tampered (str
                   (auth/base64-encode
                     (.getBytes "{\"email\":\"evil@example.com\",\"exp\":9999999999999}"
                       "UTF-8"))
                   "." sig)]
    (is (nil? (auth/verify-token h/test-signing-key tampered)))))
```

This simulates an attacker who intercepts a valid token and replaces the payload with a different email while keeping the original signature. The signature will not match the new payload, so verification fails. This is the core security property of HMAC signatures.

**Tampered signature:**

```clojure
(deftest verify-token-tampered-signature
  (let [token (auth/sign-token h/test-signing-key "user@example.com"
                (.plusSeconds (time/now) 3600) (UUID/randomUUID))
        [payload _sig] (str/split token #"\.")]
    (is (nil? (auth/verify-token h/test-signing-key (str payload ".AAAA"))))))
```

Valid payload, garbage signature. Rejected.

**Wrong signing key:**

```clojure
(deftest verify-token-wrong-key
  (let [other-key (.getBytes "other-signing-key-32-bytes-long!" "UTF-8")
        token (auth/sign-token h/test-signing-key "user@example.com"
                (.plusSeconds (time/now) 3600) (UUID/randomUUID))]
    (is (nil? (auth/verify-token other-key token)))))
```

A token signed with one key cannot be verified with a different key. This matters for key rotation: if you change your signing key, all outstanding tokens become invalid immediately.

**Garbage input:**

```clojure
(deftest verify-token-garbage-input
  (is (nil? (auth/verify-token h/test-signing-key nil)))
  (is (nil? (auth/verify-token h/test-signing-key "")))
  (is (nil? (auth/verify-token h/test-signing-key "not-a-token")))
  (is (nil? (auth/verify-token h/test-signing-key "abc.def.ghi"))))
```

`nil`, an empty string, a token with no dot separator, and a token that is dot-shaped but whose signature half is malformed -- all return `nil`. (`"abc.def.ghi"` splits on the first dot into the payload `"abc"` and the signature `"def.ghi"`, which is not valid base64, so the decode throws.) The `try/catch` in `verify-token` does its job here. No matter what garbage comes in, the function returns a clean `nil` rather than throwing.

### User management tests

```clojure
(deftest find-user-not-found
  (let [db (d/db h/*conn*)]
    (is (nil? (auth/find-user-by-email db "nobody@example.com")))))

(deftest create-user-and-find
  (let [user-id (auth/create-user! h/*conn* "new@example.com")
        db (d/db h/*conn*)]
    (is (instance? UUID user-id))
    (is (some? (auth/find-user-by-email db "new@example.com")))))
```

Create a user, find them by email. `create-user!` returns a UUID, and `find-user-by-email` finds the entity.

**Duplicate handling** is worth calling out:

```clojure
(deftest create-user-duplicate-upserts
  (auth/create-user! h/*conn* "dup@example.com")
  (auth/create-user! h/*conn* "dup@example.com")
  (let [db (d/db h/*conn*)
        n (d/q '[:find (count ?e) . :in $ ?email :where [?e :user/email ?email]]
            db "dup@example.com")]
    (is (= 1 n) "Should have exactly one entity for the email")))
```

Because `:user/email` has `:db.unique/identity`, Datomic performs an upsert rather than throwing a uniqueness constraint violation. Creating the same user twice results in one entity, not two, and no error. This is a property of Datomic's identity model -- entities are identified by their unique attributes, and transacting the same identity twice merges rather than duplicates.

Note what the test does not assert: it pins the entity count and nothing else. The merge that holds the count at one also rewrites `:user/id` and `:user/created-at`, the rotation named in the get-or-create section. One entity per email and a stable `:user/id` are different guarantees, and this test buys only the first.

**Idempotent get-or-create:**

```clojure
(deftest get-or-create-user-creates-new
  (let [email (auth/get-or-create-user! h/*conn* "fresh@example.com")
        db (d/db h/*conn*)]
    (is (= "fresh@example.com" email))
    (is (some? (auth/find-user-by-email db "fresh@example.com")))))

(deftest get-or-create-user-idempotent
  (auth/create-user! h/*conn* "existing@example.com")
  (let [email (auth/get-or-create-user! h/*conn* "existing@example.com")]
    (is (= "existing@example.com" email))))
```

Call it with a new email, get a new user. Call it with an existing email, get the same one back. No errors either way.

## Where this leaves us

The chapter has produced a self-contained token primitive, and its size is the point: about 60 lines of Clojure in all (the signing-and-verification pair from the start of the chapter is fewer than 40 of them), with zero external dependencies beyond `clojure.data.json` and Datomic, the crypto coming entirely from the JDK. That is the payoff of declining the JWT spec's generality -- a format simple enough to explain in one sentence and audit in five minutes, with a test suite weighted, as argued above, toward rejection rather than the happy path.

What it does not yet have is everything that makes it a *login*: HTTP routes, email sending, sessions, the server-side recording and one-shot consumption of the nonce, and the flow that ties them together. That is [Part 2](25-auth-email-flow.md).
