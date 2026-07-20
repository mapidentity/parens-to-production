# Passwordless Auth Part 2: Magic Link Emails and the Full Login Flow

In the [previous chapter](24-auth-tokens.md), we built the cryptographic foundation for passwordless authentication: HMAC-SHA256 signed tokens with expiration. But a token sitting in memory is useless until it reaches the user's inbox and completes the round trip back to our server. This chapter wires up the complete flow: sending magic link emails, handling the callback, creating sessions, and gating access behind terms acceptance. The thread running through all of it is that no secret the user has to remember exists anywhere in the system -- the inbox is the credential, and the server's only job is to prove the user reached it.

## The full login flow

Six steps make up the round trip, and it is worth holding them in view before the code arrives one handler at a time:

1. User enters their email and submits the login form
2. Server generates a signed token with a nonce, sends an email containing the magic link, and records the nonce
3. User clicks the link in their email
4. Server verifies the token, consumes the nonce (one-shot), creates the user if needed, and sets a session cookie
5. Server checks whether the user has accepted terms of service
6. If not, redirect to terms acceptance; otherwise, show the dashboard

Every step uses the Post-Redirect-Get pattern where appropriate, and the session is an encrypted cookie -- no server-side session store needed.

## Sending email with Jakarta Mail (Eclipse Angus)

There are Clojure email libraries out there, but they are all thin wrappers around Jakarta Mail anyway. Using Jakarta Mail directly means one fewer dependency to track, and the API is straightforward enough that a wrapper does not add much value. Jakarta Mail is the old JavaMail, renamed after its move to Eclipse; its packages left the `javax` namespace at Jakarta Mail 2.0, and when the 2.1 spec separated the API from its implementation, the reference implementation became Eclipse Angus.

The dependency in `deps.edn`:

```clojure
org.eclipse.angus/angus-mail {:mvn/version "2.0.5"}
```

Here is the email namespace:

```clojure
(ns myapp.auth.email
  "SMTP email delivery for magic link authentication.
  Uses Jakarta Mail directly (via Eclipse Angus) -- no wrapper library."
  (:require
    [clojure.tools.logging :as log]
    [myapp.config :as config]
    [myapp.i18n :refer [t]])
  (:import
    [jakarta.mail Message$RecipientType Session Transport]
    [jakarta.mail.internet InternetAddress MimeMessage]
    [java.util Properties]))

(set! *warn-on-reflection* true)
```

The imports tell the story. We need `Session` to configure the SMTP connection, `MimeMessage` to build the email, and `Transport` to send it. Nothing else.

### Configuring the SMTP session

```clojure
(defn- smtp-session
  "Create a Jakarta Mail session from SMTP config."
  ^Session []
  (let [{:keys [host port tls user pass]} (config/get-config :smtp)
        props (doto (Properties.)
                (.put "mail.smtp.host" (str host))
                (.put "mail.smtp.port" (str port))
                (.put "mail.smtp.starttls.enable" (str (boolean tls))))]
    (if user
      (do
        (.put props "mail.smtp.auth" "true")
        (Session/getInstance
          props
          (proxy [jakarta.mail.Authenticator] []
            (getPasswordAuthentication []
              (jakarta.mail.PasswordAuthentication. user pass)))))
      (Session/getInstance props))))
```

The branch on whether `user` is present is intentional. In development, we connect to Mailpit without authentication. In production, we use authenticated SMTP with STARTTLS. The same code handles both; the config drives the behavior.

So is the `^Session` type hint on the return value. With `*warn-on-reflection*` set to true, the Clojure compiler will tell us if we miss a hint that causes reflective method lookup. In a namespace full of Java interop, this matters for both performance and correctness.

### The send-magic-link! function

```clojure
(defn send-magic-link!
  "Send magic link email to user via SMTP."
  [locale email token base-url]
  (let [magic-link (str base-url "/auth/verify?token=" token)
        ^String from (config/get-config :smtp :from)
        ^Session session (smtp-session)
        msg (doto (MimeMessage. session)
              (.setFrom (InternetAddress. from))
              (.setRecipient Message$RecipientType/TO (InternetAddress. ^String email))
              (.setSubject ^String (t locale :email/magic-link-subject))
              (.setText ^String (format (t locale :email/magic-link-body) magic-link)))]
    (try
      (Transport/send msg)
      (log/info "Magic link email sent" {:to email})
      {:error :SUCCESS}
      (catch Exception e
        (log/error e "Failed to send magic link email" {:to email})
        {:error :FAIL
         :message (.getMessage e)}))))
```

The function takes four arguments: the locale (for i18n), the recipient email, the signed token, and the base URL. It constructs the full magic link URL, builds a `MimeMessage`, and sends it. None of the type hints here is strictly required today. The `session` local inherits its type from `smtp-session`'s `^Session` return hint -- the same propagation noted above -- so `(MimeMessage. session)` resolves without a local hint; the `^String` hints likewise mark where untyped values (config lookups, translations, the caller's arguments) meet Java setters that happen to resolve without them. They are here defensively: each pins the intended overload so the call stays reflection-free as the API or the surrounding code moves, and `*warn-on-reflection*` is what would catch it if one ever stopped resolving.

Each of the choices folded into those few lines is a small refusal of a more elaborate default:

**Plain text email.** No HTML templates, no inline CSS wrestling. A magic link email should contain one thing: the link. Plain text is universally readable, does not get clipped by email clients, and is trivial to test.

**i18n from the start.** The subject and body come from translation maps via the `t` function. The body template uses `%s` for the magic link URL, filled in with `format`. This means Dutch users get Dutch emails and English users get English ones. Adding this later would mean touching every email template. Adding it now costs nothing.

**Return value, not exception.** The function returns `{:error :SUCCESS}` or `{:error :FAIL :message "..."}`. The caller can decide what to do. In our case, the handler always shows the "check your email" page regardless: we do not want to leak information about whether an email address is registered. The shape has a naming wart worth owning -- a key called `:error` whose value can be `:SUCCESS` reads backwards, and screaming keywords are unidiomatic Clojure. Read the pair as a status code (the map answers "what went wrong?", and `:SUCCESS` means "nothing"), or rename it `{:ok? true}` in your own code. The property that matters is that failure is a value the caller inspects, not an exception that unwinds the handler.

**Bounded, and off the request thread.** Two later hardenings, both for the same reason: login *is* this send, so it is the one dependency whose slowness is a total outage. First, `smtp-session` sets finite `connectiontimeout`/`timeout`/`writetimeout`: Jakarta defaults them to *infinite*, so a relay that completes the handshake and then stalls would hang the caller forever. Second, the handler calls `deliver-magic-link!`, not `send-magic-link!` directly -- the blocking SMTP work runs on a small bounded background mailer, so a slow relay backs up against a bounded queue instead of pinning the http-kit worker pool (and taking `/health`, which shares that pool, down with it). The [resilience capstone](46-watching-the-watchers.md) is where this stops being a local fix and becomes a rule.

## Deliverability: SPF, DKIM, and DMARC

`send-magic-link!` ends at the relay's door; whether the message reaches the inbox is decided outside the application, by DNS you publish and the reputation of the IP that sends it. For most apps that is a marketing concern. Here it is a security one: the login *is* the email, so a magic link that lands in spam is a failed authentication the user cannot retry their way out of. Three DNS records stand between a passwordless login and the junk folder.

**SPF (Sender Policy Framework)** publishes, as a TXT record on your domain, which servers are allowed to send mail for it. A receiver checks the sending IP against that list and treats mail from an unlisted server as suspect:

```
example.com.  TXT  "v=spf1 include:amazonses.com -all"
```

`include:` delegates to your relay's own SPF record; `-all` means "anything not listed, reject." Swap the include for your provider's.

**DKIM (DomainKeys Identified Mail)** has your relay sign every outgoing message with a private key; you publish the matching public key in DNS, and receivers verify that the signature is valid and the body was not altered in transit:

```
selector._domainkey.example.com.  TXT  "v=DKIM1; k=rsa; p=MIGfMA0GCSq...QAB"
```

The relay gives you the `selector` and the key; you paste the record.

**DMARC** ties the two together and tells receivers what to do when a message disagrees with its visible `From:` address. It requires that a passing SPF or DKIM result be *aligned* with the From domain -- so an attacker cannot pass SPF for their own domain while spoofing yours -- sets a policy for failures, and asks for aggregate reports:

```
_dmarc.example.com.  TXT  "v=DMARC1; p=reject; rua=mailto:dmarc@example.com; adkim=s; aspf=s"
```

Start at `p=none` while you read the reports and confirm your own mail passes, then tighten to `quarantine` and finally `p=reject`.

Records aside, one operational choice dominates: **send through a reputable relay, not a fresh VPS.** A brand-new IP that suddenly starts emitting login links to strangers' inboxes looks, to a spam filter, exactly like a spam run -- because behaviorally it is indistinguishable from one. A managed sender (Amazon SES, Postmark, Mailgun, and their peers) brings warmed IP reputation, per-message DKIM signing, bounce and complaint handling, and the deliverability monitoring you would otherwise build and babysit yourself. `smtp-session` already speaks authenticated STARTTLS to any of them; only the host, port, and credentials change between Mailpit in development and the relay in production.

The application's own contribution is small but real: a plain-text, single-purpose message with no tracking pixel, no image payload, and no link farm -- the shape we already built -- is the one least likely to trip a content filter. The rest lives in DNS and operations, not in Clojure, which is why it is easy to forget until a user writes in that the link never arrived. On a passwordless app, treat these three records as part of the authentication system, because they are.

## The handler layer

With token creation (from the previous chapter) and email sending in place, the handlers orchestrate the full flow.

### Requesting a magic link (POST /auth/request)

```clojure
(defn- normalize-email
  "Canonicalize for storage and comparison: trim and lower-case.
  Returns nil for anything without a plausible local@domain shape."
  [raw]
  (when (string? raw)
    (let [e (str/lower-case (str/trim raw))]
      (when (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" e) e))))

(def ^:private ml-window-ms (* 15 60 1000))
(def ^:private ml-per-email 3)
(def ^:private ml-per-ip 10)

(defn request-magic-link
  "Send the email + record the nonce, then redirect (PRG). Always lands on
  the same confirmation page, whatever the outcome."
  [request]
  (let [email (normalize-email (get-in request [:params :email]))
        ip (or (:client-ip request) "?")
        locale (:locale request)
        ip-ok? (ratelimit/allow? (str "ml-ip:" ip) ml-per-ip ml-window-ms)
        send? (and email ip-ok?
                   (ratelimit/allow? (str "ml-email:" email) ml-per-email ml-window-ms))]
    (when send?
      (let [signing-key (config/get-config :signing-key)
            base-url (config/get-config :base-url)
            {:keys [token nonce]} (auth/create-magic-link-token signing-key email)]
        (email/deliver-magic-link! locale email token base-url)
        (analytics/record!
          [{:magic-link/email email
            :magic-link/nonce nonce
            :magic-link/requested-at (time/now)}])))
    (response/redirect
      (str "/auth/sent?email="
           (java.net.URLEncoder/encode ^String (or email "") "UTF-8")))))
```

The happy path does four things: create a token (and its nonce), send the email, **record the nonce**, and redirect. The redirect is the **Post-Redirect-Get pattern**; the nonce record is what makes the link single-use. But two guards sit in front of it, and they matter as much as the happy path.

**Normalize the address once, at the boundary.** `:user/email` is `:db.unique/identity` and the admin gate compares lower-cased, so if `Foo@x.com` and `foo@x.com` reached the database as different strings you would split one person into two accounts -- and could lock an admin out of their own dashboard. We trim and lower-case here, before the address is signed into a token, written to the log, or handed to the SMTP layer, and reject anything without a plausible `local@domain` shape rather than discovering it as a late exception inside `InternetAddress`.

**Rate-limit the send, because every request emails an attacker-chosen address.** An unthrottled endpoint that mails any address on demand is a mail-bombing and SMTP-reputation hazard: a script can flood one inbox, or spray links at thousands of addresses to make your domain look like a spammer. So we cap requests per email (blunting a bombing run on one inbox) and per IP (blunting a spray from one source) over a trailing window. The per-IP check runs even on malformed input, so junk still counts against the source. (One subtlety hides in `(:client-ip request)`: behind a reverse proxy the app's own socket sees only the proxy, so "per IP" quietly means "per *proxy*" -- one global bucket -- unless the real client is recovered from a trusted forwarded header. That recovery, and why it must not trust the header blindly, is [the detection chapter's](42-detect-respond.md) opening fix; here the key is written to depend on it.) The limiter (`myapp.web.ratelimit`) is a small in-process sliding-window counter: it keeps each key's recent hit timestamps in a local atom and prunes those past the trailing window, so the cap holds exactly for any window-length span. That local atom is also why it is **single-instance**, a constraint worth its own short section below. Crucially, a rate-limited or invalid request still redirects to the *same* confirmation page: throttling must not become an oracle that tells an attacker which addresses are real or which requests were dropped.

Recall from [Part 1](24-auth-tokens.md) that `create-magic-link-token` returns `{:token ... :nonce ...}`. The token goes in the email; the nonce we write to a small server-side store keyed by `:magic-link/nonce`, alongside the email and request time. (This store is the same lightweight event log the admin dashboard reads for analytics -- its schema is defined there; here we only need the nonce field.) When the user clicks the link, verification will look this record up and atomically flip it to "consumed." A second click finds it already consumed and is rejected. A caveat attaches to *where* that store lives: it shares the disposable analytics database, so wiping that database drops every nonce record with it. `consume-nonce!` fails closed on a nonce it cannot find, so any link still outstanding at that instant stops verifying -- an already-consumed link was dead anyway, but a still-valid link minted just before the wipe dies too rather than turning replayable, and its owner has to request a fresh one. [The admin-dashboard chapter](28-admin-dashboard.md) spells this out where the analytics database is introduced; the rule is simply to recreate that database only when no unexpired links are in the wild.

Doing the send before the record leaves an ordering subtlety: a nonce write that failed *after* a successful send would leave the user holding a link whose nonce verification cannot find -- it would be rejected as if it had already been used. In practice the send and the record run in the same request against the same transactor and the window is vanishingly small; if you wanted to close it entirely, record the nonce first and send only once that write has committed, trading a slightly slower happy path for a guarantee that no link is ever in an inbox without its nonce already durable.

### Why Post-Redirect-Get matters

Without PRG, refreshing the "check your email" page would resubmit the form and send another email. The browser would show a "resubmit form data?" dialog. With PRG:

1. **POST** `/auth/request` -- sends the email, returns a 302 redirect
2. Browser follows redirect to **GET** `/auth/sent?email=user@example.com`
3. Refreshing this page is a harmless GET -- no duplicate emails

(The dispatcher from [the morph-dispatcher chapter](15-morph-dispatcher.md) enhances this form submission into an in-place fetch when JavaScript is available, but the server is oblivious to that -- it always renders the same full page and redirects. No `X-Enhanced` header, no content negotiation, no separate code path.)

### Rate limiting beyond one instance

The in-process counter is the right default -- one instance, no external dependency, and rate limiting that works the moment you boot. But the moment you run two app instances behind a load balancer, each keeps its own counts, so the effective limit silently multiplies by the number of replicas -- nothing errors, the throttle you configured just becomes that many times looser. That makes a shared store a prerequisite for going multi-instance, not a later optimization: a horizontally-scaled deploy on the in-process counter has no working rate limit. The fix is not to change *what* we limit, only *where the count lives*, and the call sites above already make that separation clean: every check goes through one function with one signature.

```clojure
(defn allow?
  "True if KEY is still under LIMIT within the trailing WINDOW-MS, and counts
   this hit. KEY already carries the dimension: \"ml-ip:1.2.3.4\", \"ml-email:a@b.com\"."
  [key limit window-ms]
  ...)
```

Because the *key* carries the dimension (`ml-ip:…`, `ml-email:…`) and the policy (which dimensions, what limits, what window) lives entirely in the caller, swapping the backing store is a body-only change behind that same signature. A Redis implementation is the canonical one: a counter per key with an atomic increment and a TTL that *is* the window. The sketch below uses Carmine, the de facto Clojure Redis client, where `car/wcar` runs the enclosed commands against the connection spec `conn`. (This is a true fixed window rather than the in-process sliding log: it is slightly bursty at the window boundary, an acceptable trade for a coarse cross-instance abuse limit.)

```clojure
;; One atomic round trip: INCR, and — only on the first hit — stamp the TTL, in
;; a single Lua script (Redis runs each script atomically). Splitting this into
;; an INCR then a separate PEXPIRE would reopen the very window the nonce CAS
;; closes: a crash between the two calls leaves the key with no expiry, throttling
;; that dimension forever. Carmine's `lua` maps `_:k` to KEYS and `_:window` to ARGV.
(def ^:private allow-script
  "local n = redis.call('incr', _:k)
   if n == 1 then redis.call('pexpire', _:k, _:window) end
   return n")

(defn allow? [key limit window-ms]
  (<= (car/wcar conn (car/lua allow-script {:k key} {:window window-ms}))
      limit))
```

A Datomic-backed counter or a Postgres `UPDATE … RETURNING` works the same way; the only requirement is that the increment-and-read is atomic so two simultaneous requests cannot both read "under the limit." The decision the chapter made -- per-email *and* per-IP, fail-closed, same redirect either way -- is independent of all of this. That is the payoff of routing both checks through one `allow?`: the security policy is fixed in the handler, and the store is a deployment detail you change once, in one namespace, the day you add the second instance.

### The confirmation page (GET /auth/sent)

```clojure
(defn magic-link-sent
  "Show confirmation page (GET after redirect)."
  [request]
  (let [email (get-in request [:params :email])]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str (views/magic-link-sent (:locale request) email))}))
```

Simple: read the email from the query string, render a page telling the user to check their inbox.

### Verifying the token (GET /auth/verify)

When the user clicks the magic link, three things must all hold before we sign them in: the token must be authentic and unexpired, its nonce must not have been used before, and a user account must exist (creating one on first sign-in). The one-shot check is the piece to build first, because the other two are familiar and it is the subtle one.

#### Consuming the nonce exactly once

`verify-token` proves the token is genuine, but a genuine token can be presented twice. The nonce closes that gap -- but only if "mark this nonce as used" is **atomic**. If we read "unused," then separately wrote "used," two near-simultaneous clicks could both read "unused" and both succeed. We need a compare-and-swap.

Datomic gives us this with the built-in `:db.fn/cas` transaction function:

```clojure
(defn- consume-magic-link-nonce!
  "CAS-stamp the magic-link record for `nonce` as verified.

  Returns true if this call was the first to consume the nonce; false on
  replay, malformed nonce, or unknown nonce. The CAS expects
  :magic-link/verified-at to be currently unset; a replay finds it already
  set, the CAS fails, the transaction throws, and we return false."
  [^UUID nonce]
  (try
    (let [conn (analytics/get-connection)
          eid (d/q '[:find ?e . :in $ ?n :where [?e :magic-link/nonce ?n]]
                (d/db conn) nonce)]
      (when eid
        @(d/transact conn [[:db.fn/cas eid :magic-link/verified-at nil (time/now-date)]])
        true))
    (catch Exception _e
      false)))
```

`:db.fn/cas` asserts the new value *only if* the current value matches the expected one -- here, only if `:magic-link/verified-at` is still `nil`. The first click matches `nil`, stamps the time, and commits. A replay finds the field already stamped, the CAS fails inside Datomic's transactor, the transaction throws, and we fall through to `false`. The atomicity is the database's job, not ours -- there is no read-then-write window to lose a race in.

#### The handler

```clojure
(defn verify-magic-link
  "Verify the magic-link token, then create the user-and-session.

  Three gates must pass: (1) HMAC + expiration via verify-token;
  (2) one-shot consumption of the token's nonce; (3) user-entity
  creation if needed. Any gate failing produces the same generic
  error, never revealing which gate failed."
  [request]
  (let [token (get-in request [:params :token])
        signing-key (config/get-config :signing-key)
        locale (:locale request)
        token-data (auth/verify-token signing-key token)
        nonce-str (:nonce token-data)
        nonce-uuid (when nonce-str
                     (try (UUID/fromString nonce-str)
                          (catch IllegalArgumentException _ nil)))]
    (if (and token-data nonce-uuid (consume-magic-link-nonce! nonce-uuid))
      (let [email (:email token-data)
            conn (db/get-connection)]
        (auth/get-or-create-user! conn email)
        (-> (response/redirect "/dashboard")
            (assoc :session {:user-email email})))
      {:status 400
       :headers {"Content-Type" "text/html"}
       :body (str (views/error-page locale
                    (t locale :error/invalid-magic-link)))})))
```

The three gates run in order, short-circuiting on the first failure:

1. **`verify-token`** checks the HMAC signature and expiry, returning the email and nonce (or `nil`).
2. **`consume-magic-link-nonce!`** atomically claims the nonce. A replayed link gets `false` here even though its signature is still valid.
3. **`get-or-create-user!`** creates the account on first sign-in and is a no-op thereafter -- so the user record is created *here, at verification*, the moment we know the person controls the email.

Crucially, every failure path produces the *same* generic error page. We never tell the caller whether the token was forged, expired, or already used -- that would hand an attacker a probe.

The same discipline extends to the failures nobody anticipated. `wrap-errors`, the middleware mounted innermost in the app's stack (the session-management listing below shows it), catches anything a handler throws, logs the stack trace where an operator can read it, and serves the same styled `error-page` as a 500. That page reveals nothing: not the exception class, not the message, not a frame. Without it, an uncaught exception would fall through to `http-kit`'s default: an unstyled 500 carrying none of the app's headers. The placement is the crux: innermost means the error response exits through the CSP and cache-control layers like any other HTML, so even the failure path wears the full security envelope.

Innermost has a blind spot, though: `wrap-errors` can only catch what happens *below* it. An exception thrown by the stack itself -- a session cookie that fails to decrypt, a middleware's own database read -- would still fall through to the bare default. So a second, deliberately crude catch, `wrap-panic`, mounts outermost: no locale, no styled view (those layers may be what broke), just a plain-text 500 and the stack trace to the log. The pairing is the design: the inner boundary is well-dressed and does the everyday work; the outer one exists for the day the dressing itself is on fire. The same instinct covers the URL nobody routes -- an unmatched path gets the branded `error-page` as a 404 rather than reitit's bare-text default, and because that fallback runs inside the middleware stack, it exits wearing the CSP like every other page.

And when one of the gates does refuse and you want to know which, the [construction view](17-construction-view.md) answers without adding a single log line. Replay a used link under the `:storm` alias and the recorded tree shows `verify-token` returning the decoded claims (the signature is fine) and `consume-magic-link-nonce!` returning `false`, the CAS refusing a nonce that is already stamped. The generic page tells the visitor nothing; the recording tells *you* everything; the asymmetry is the point. The two halves of the design -- opaque outside, transparent inside -- are the same decision made twice.

The session is the moment where "stateless token" becomes "stateful session." The token was a one-time bridge to prove the user controls that email address; the nonce guaranteed it was crossed only once. The session persists across requests.

## Session management

Sessions are configured in the middleware stack using Ring's cookie store:

```clojure
(def ^:private app*
  (delay
    (ring/ring-handler
      (ring/router routes)
      (ring/create-default-handler)
      {:middleware [[wrap-panic]
                    [params/wrap-params]
                    [keyword-params/wrap-keyword-params]
                    [session/wrap-session
                     {:store (cookie/cookie-store
                               {:key (config/get-config :session-key)})
                      :cookie-name "session"
                      :cookie-attrs {:http-only true
                                     :secure true
                                     :same-site :lax
                                     :max-age (* 30 24 60 60)}}]
                    [wrap-locale]
                    [wrap-no-cache-authenticated]
                    [wrap-csp]
                    [wrap-errors]]})))
```

That is the base stack in the shape this chapter needs, outermost first: the panic belt on the outside, and then the layers the verify section leaned on -- `wrap-csp` stamps the strict Content-Security-Policy on every HTML response ([the asset pipeline chapter](29-asset-pipeline.md)), and `wrap-errors` sits innermost so anything a handler throws exits through every layer above it. The repo's stack mounts three more layers this listing leaves out -- `wrap-client-ip`, `wrap-metrics`, and `wrap-conditional-get`, each earned in a later chapter -- and, under the `:storm` alias, the construction-view tracer outermost ([the construction-view chapter](17-construction-view.md)). The piece that is new here is the session store.

The session cookie is:

- **Encrypted and authenticated** with a 16-byte key. Ring's cookie store does not merely scramble the session, it *seals* it: AES-CBC with a random IV for confidentiality, then an HMAC-SHA256 tag over the ciphertext, verified in constant time *before* anything is decrypted (encrypt-then-MAC).
- **http-only** so JavaScript cannot read it
- **secure** so it only travels over HTTPS
- **same-site :lax** to prevent CSRF while still allowing the magic link GET request to work (the link opens in a new tab, which is a top-level navigation -- `:lax` permits this)
- **30 days** max-age -- a directive to the browser to drop the cookie after thirty days, not a server-checked expiry: the sealed cookie carries no timestamp and the server never inspects a cookie's age (see the revocation note below)

No server-side session store. The session data is sealed inside the cookie itself. For our use case -- storing just an email address -- this is ideal. No session table to query, no cleanup jobs, no scaling concerns.

### Why a client-held session is safe

Putting the session in the cookie only works because of that HMAC, and it is worth being precise about why, because encryption alone would not be enough. Encryption keeps the contents secret; it says nothing about *integrity*, and integrity is the property that matters when the client holds the data. The session is just `{:user-email "..."}`. If a user could flip those bytes to someone else's address and have the server believe them, the whole scheme would be theatre.

They cannot. Any edit to the sealed blob -- one byte, the whole thing, or a value lifted from another site -- fails the MAC check, and `unseal` returns `nil`, which Ring reads as *no session*. Forging a valid cookie requires the server's secret key, which never leaves the server. So "no server-side session store" is no shortcut around safety; it trades a database lookup for a signature check, and the signature is what lets the server trust a value it did not keep.

Two honest caveats. The first: why 16 bytes here, when we insisted on a 32-byte magic-link signing key? Because the length is not ours to choose. Ring's cookie store fixes the session key at exactly 16 bytes -- that is AES-128, and 128 bits of random key is plenty -- whereas the HMAC signing key, which *is* ours to size, we make 32.

The second: that same 16-byte key drives both the AES and the HMAC. With encrypt-then-MAC over two distinct algorithms that poses no practical weakness, though it is one more reason the key must be real CSPRNG bytes. The config refuses to boot without `SESSION_KEY` in production, exactly as it does for the magic-link signing key.

### Revocation: what a sealed session gives up

One more cost, and it is the mirror image of the upside. Because the cookie authenticates *itself* and the server keeps nothing, there is nothing on our side to revoke. A cookie that leaks -- copied off a shared machine, lifted from a backup, pulled from a proxy log -- stays valid indefinitely, and no action of ours cuts it short. The thirty-day `:max-age` is no help here: it is a directive to the *original* browser to drop its own copy, but the sealed cookie carries no timestamp and the server never checks its age, so a copy lifted out of that browser has no expiry at all and is good until the key changes. Logout runs `(assoc :session nil)` on the *requesting* browser and clears that one cookie, but it cannot reach a copy already made, there is no "sign out of all my devices," and the only global lever is rotating `SESSION_KEY`, which fails every existing cookie at once and signs the whole userbase out. A server-side session store is what would buy back the power to strike one session dead; declining the store declines the revocation with it. That is the trade the "no session table" line above is really making, and it belongs in the open rather than under the word *ideal*.

The gap is narrower than it first looks, though, because one half of revocation is already free and the other half is cheap. `wrap-current-user` resolves the cookie's email to a live user entity on *every* request, so authority is never staler than the last read: delete a user and their next request arrives with no `:user-eid`, which `wrap-auth` treats as unauthenticated and clears -- deletion is already a working kill switch. Disabling short of deletion is one attribute, and the schema carries it -- `:user/active?` is set on every account and shown on the admin dashboard. `wrap-current-user` already gates the resolve on it: a session whose user is flipped to `active? false` is treated as anonymous on its very next request, so a single flip is a live ban with no store to keep and no redeploy. That lever is [the detection chapter's](42-detect-respond.md) containment move, wired here (with the "missing flag never bans" care that keeps a schema gap from locking the userbase out). Only true per-session revocation -- keep this laptop, kill that phone -- still needs more: a `:user/sessions-valid-after` instant stamped into the session at login and compared on read, so "sign out everywhere" becomes one write. That last one the app does not ship, because a recipe box does not need it; what matters is that the sealed cookie forecloses none of these, and each has an obvious home in the lookup `wrap-current-user` already does.

### CSRF: what `same-site :lax` does, and what it leaves on the table

Notice what is *not* here: a CSRF token. There is no hidden `_csrf` field on the forms and no synchronizer token in the session, and that is a deliberate choice with a named cost. The defense we rely on instead is `SameSite=Lax`, which tells the browser not to attach the session cookie to cross-site subrequests. The classic attack, a hidden form on `evil.com` auto-POSTing to `/terms/accept`, arrives with no cookie and is simply unauthenticated. It works because every endpoint that mutates *on the authority of the cookie* is a POST (`/terms/accept`, `/auth/logout`): `:lax` *does* send the cookie on top-level cross-site GET navigations, which is what makes the magic link work, but never on a cross-site POST.

The cost of leaning on `:lax` is that enforcing the protection is the browser's job, out of our hands. It assumes a current browser: every maintained one now defaults to honoring it, but a sufficiently old client may not. It does nothing against a same-origin attacker such as an XSS hole or a hostile subdomain. And it does not cover the one state-changing GET we already have.

`/auth/verify` mutates on the authority of the token in the URL, not the cookie, so `SameSite` never enters into it. That opens a different door: login CSRF. An attacker requests a magic link for *their own* account and lures the victim into opening the verify URL -- a top-level GET navigation, exactly the case `:lax` waves through -- and the victim is silently signed in as the attacker, so anything they then save lands in an account the attacker can read. The real fix is binding verification to the browser that asked: a short-lived cookie set by `/auth/request` and required at verify, so only that browser can complete the sign-in. The interstitial confirm-POST that defuses mail scanners (see the routes section below) merely raises this attack's cost, since a hostile page can script the confirm POST. We name the gap rather than close it: a recipe box is a low-value target for it, and the fix has a natural home, the interstitial, when the stakes rise.

For this app -- modern browsers, every mutation behind a POST, and escaping plus a strict CSP closing the XSS door upstream ([the asset pipeline chapter](29-asset-pipeline.md)) -- `:lax` is sufficient and is the simplest thing that is correct. A higher-value app, or one that must accept a cross-site POST, should add a double-submit or synchronizer token on top; the place to put it is this same middleware stack.

### Cache control for authenticated pages

The cache-control layer from the stack above is short enough to quote in full:

```clojure
(defn wrap-no-cache-authenticated
  "Sets Cache-Control: no-store on authenticated HTML responses.
  Prevents browser bfcache from showing stale pages after logout."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (get-in request [:session :user-email])
        (assoc-in response [:headers "Cache-Control"] "no-store")
        response))))
```

Without this, a user who logs out and hits the back button might see their dashboard from the browser's back-forward cache. `no-store` prevents this. It only applies to authenticated responses, so public pages still benefit from caching.

## The terms acceptance gate

Authentication proves who you are. But before a new user can use the application, they need to accept the terms of service. The dashboard handler enforces this:

```clojure
(defn dashboard
  "Dashboard handler (requires authentication + terms acceptance)."
  [request]
  (if-let [user-email (get-in request [:session :user-email])]
    (let [db (d/db (db/get-connection))
          user-eid (auth/find-user-by-email db user-email)
          user (when user-eid (db/pull* db [:user/terms-accepted-at] user-eid))]
      (if (:user/terms-accepted-at user)
        {:status 200
         :headers {"Content-Type" "text/html"}
         ;; The repo's dashboard view takes more positionals than this
         ;; terms-gate listing builds -- :admin?, the user's recipes, and the
         ;; activity feed (chapter 26); elided here with ,,,
         :body (str (views/dashboard (:locale request) user-email ,,,))}
        ;; Authenticated but terms not accepted -- show welcome page
        (response/redirect "/terms/welcome")))
    ;; Not authenticated
    (response/redirect "/")))
```

A request lands here in one of three states -- not authenticated (redirect home), authenticated without terms (redirect to `/terms/welcome`), authenticated with terms accepted (render the dashboard) -- and the nested `if`s test them in that order.

The user account already exists by this point -- `verify-magic-link` created it the moment the link was confirmed. So accepting terms is a single stamp, not a creation:

```clojure
(defn accept-terms
  "Stamp :user/terms-accepted-at on the authenticated user, then continue."
  [request]
  (if-let [user-email (get-in request [:session :user-email])]
    (let [conn (db/get-connection)
          user-eid (auth/find-user-by-email (d/db conn) user-email)]
      @(db/transact* conn
         [{:db/id user-eid
           :user/terms-accepted-at (time/now)}])
      (response/redirect "/dashboard"))
    (response/redirect "/")))
```

A natural question: why create the user at verification rather than waiting until they accept terms? It keeps each handler doing one job. `verify-magic-link` answers "does this person control this email?" -- and the honest record of that fact is a user row. `accept-terms` answers a separate question, "have they agreed to the terms?", and records *that* with a single timestamp. The terms gate then lives entirely in the read path (the `dashboard` handler above redirects until `:user/terms-accepted-at` is set), so an account that exists but has not accepted terms is a perfectly valid, well-defined state -- not a half-written record. Folding user creation into `accept-terms` would mean two code paths (create-with-terms vs stamp-existing) and a user-creation step hidden inside a handler named for something else.

## Logout

Logout is refreshingly simple:

```clojure
(defn logout
  "Logout handler."
  [_request]
  (-> (response/redirect "/")
      (assoc :session nil)))
```

Setting `:session` to `nil` tells Ring's session middleware to clear the cookie. The redirect sends the user back to the home page. That is the entire logout implementation.

## Routes

All auth-related routes in one place:

```clojure
["/auth/request" {:post handler/request-magic-link}]
["/auth/sent" {:get handler/magic-link-sent}]
["/auth/verify" {:get handler/verify-magic-link}]
["/auth/logout" {:post handler/logout}]
["/terms/welcome" {:get handler/terms-welcome}]
["/terms/accept" {:post handler/accept-terms}]
```

> **Handler-gated here, middleware-gated in the repo.** The handlers above each read `[:session :user-email]` and run their own "are you signed in? have you accepted terms?" checks inline, which keeps this chapter self-contained -- every handler is readable on its own. The companion repo factors those two questions out into middleware instead: a `wrap-current-user` resolves the signed-in user once and puts `:user-email`/`:user-eid` (and `:admin?`) on the request, a `wrap-auth` then hard-gates the protected routes (redirecting to `/` when no user is present), and a `wrap-terms-accepted` enforces the terms gate (redirecting to `/terms/welcome` until `:user/terms-accepted-at` is set). The route tree then nests the protected routes under those wrappers, and the handlers shrink to their actual work -- `dashboard` becomes a three-line render that trusts `(:user-email request)` is present. It is the same logic, lifted from each handler into one place so it cannot be forgotten on a new route; the [admin chapter](28-admin-dashboard.md)'s `wrap-admin` is the next layer in that same stack. If you have built the middleware, prefer it; the inline form here is the minimal equivalent.

State-changing operations the browser originates (request link, logout, accept terms) are POST, and the plain reads (sent page, welcome page) are GET. `/auth/verify` is the deliberate exception, and it deserves to be named as one: it is a GET that changes state -- consumes the nonce, creates the user, issues the session -- because the user reaches it by clicking a link in an email, and email clients do not POST. A mutating GET forfeits the safety GET is supposed to promise, and the forfeit has a well-known production face: corporate mail gateways (Microsoft Defender's link scanning among them) GET every URL in an inbound message to inspect it, before the user ever sees the email. Against a strictly one-shot nonce, the scanner consumes the link and the human's click a minute later lands on the generic error page. The standard escapes are an interstitial -- the GET renders a harmless page whose button POSTs the token, so only an intentional click consumes the nonce -- or scanner-tolerant nonce semantics, where consumption is bound to the session actually issued rather than to the first fetch. (Our own speculation rules in [the progressive-enhancement chapter](20-progressive-enhancement.md) already refuse to prerender `/auth/*` for this reason: a prerender is a GET the user has not meant yet.) This app accepts the failure mode instead: the error page says to request a new link, and a new link costs one click. For an audience on personal email that is the right trade; the day your users live behind a corporate mail filter is the day you buy the interstitial.

## Testing with GreenMail

You cannot test email delivery by checking logs and hoping. GreenMail is an embedded SMTP server written in Java that captures emails in-process. No external mail server needed, no network flakiness, tests run in milliseconds.

The dependency goes in the `:test` alias:

```clojure
:test {:extra-paths ["test"]
       :extra-deps {com.icegreen/greenmail {:mvn/version "2.1.8"}}
       ...}
```

### The GreenMail fixture

```clojure
(ns myapp.auth.email-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [myapp.auth.email :as email]
    [myapp.config :as config]
    [myapp.test-helpers :as h])
  (:import
    [com.icegreen.greenmail.util GreenMail ServerSetup]
    [jakarta.mail.internet MimeMessage]))

(set! *warn-on-reflection* true)

(def ^:dynamic *greenmail*
  "Bound to a running GreenMail instance per test."
  nil)

(defn with-greenmail
  "Fixture: starts GreenMail SMTP on a dynamic port, stubs config."
  [f]
  (let [setup (ServerSetup. 0 "127.0.0.1" "smtp")
        gm (GreenMail.
             ^"[Lcom.icegreen.greenmail.util.ServerSetup;"
             (into-array ServerSetup [setup]))]
    (.start gm)
    (try
      (let [port (.getPort (.getSmtp gm))
            test-cfg (assoc h/test-config
                       :smtp {:host "127.0.0.1"
                              :port port
                              :tls false
                              :user nil
                              :pass nil
                              :from "noreply@myapp.test"})]
        (with-redefs [config/config (delay test-cfg)]
          (binding [*greenmail* gm]
            (f))))
      (finally (.stop gm)))))

(use-fixtures :each with-greenmail)
```

The fixture is why the email tests run in milliseconds with no network in sight:

- **Port 0** tells GreenMail to pick a random available port. No port conflicts, tests can run in parallel.
- **`with-redefs`** swaps the app config to point SMTP at the GreenMail instance. The production code does not know it is talking to a test server.
- **`binding`** makes the GreenMail instance available to test assertions via the `*greenmail*` dynamic var.
- **`finally`** ensures the server stops even if a test fails.

The `^"[Lcom.icegreen.greenmail.util.ServerSetup;"` type hint is the JVM's notation for an array of `ServerSetup` objects. It looks ugly, but it eliminates a reflection warning.

### Testing email delivery

```clojure
(deftest send-magic-link-delivers-email
  (let [result (email/send-magic-link!
                 :nl "user@example.com" "test-token-abc"
                 "https://myapp.test")]
    (is (= :SUCCESS (:error result)))
    (let [messages (.getReceivedMessages ^GreenMail *greenmail*)]
      (is (= 1 (alength messages)))
      (let [^MimeMessage msg (aget messages 0)]
        (is (= "Inloggen bij MyApp" (.getSubject msg)))
        (is (= "noreply@myapp.test" (str (first (.getFrom msg)))))
        (let [body (str (.getContent msg))]
          (is (str/includes? body
                "https://myapp.test/auth/verify?token=test-token-abc"))
          (is (str/includes? body "15 minuten")))))))
```

This test verifies the complete email: return value, recipient count, subject line, sender address, magic link URL in the body, and the expiration notice. We pass locale `:nl` so we can assert on the Dutch subject line and body text.

```clojure
(deftest send-magic-link-recipient-is-correct
  (email/send-magic-link!
    :nl "other@example.com" "token-123" "https://myapp.test")
  (let [messages (.getReceivedMessages ^GreenMail *greenmail*)
        ^MimeMessage msg (aget messages 0)
        recipients (.getAllRecipients msg)]
    (is (= "other@example.com" (str (first recipients))))))
```

A separate test for the recipient address. Seems redundant, but sending an email to the wrong person is the kind of bug you want caught by its own focused test.

## Development with Mailpit

Tests use GreenMail. But during development, you want to see the actual emails in a UI. Mailpit is a lightweight SMTP server with a web interface -- think of it as a local email inbox for development.

The SMTP config in `config.edn` uses Aero profiles to switch between environments:

```clojure
:smtp {:host #profile {:dev "mailpit"
                       :prod #env "SMTP_HOST"}
       :port #profile {:dev 1025
                       :prod 587}
       :tls  #profile {:dev false
                       :prod true}
       :user #profile {:dev nil
                       :prod #env "SMTP_USER"}
       :pass #profile {:dev nil
                       :prod #env "SMTP_PASS"}
       :from #profile {:dev "no-reply@myapp.lan"
                       :prod #env "SMTP_FROM"}}
```

In development:
- SMTP goes to `mailpit` on port 1025 (the Mailpit SMTP port)
- No TLS, no authentication
- Mailpit's web UI (typically on port 8025) shows every email the app sends

In production:
- SMTP goes to a real mail provider with STARTTLS
- Credentials come from environment variables

The production email code is identical to the development email code. Only the config changes. This is the advantage of using a real SMTP server for development instead of mocking -- you exercise the actual email path every time.

## The config layer

The SMTP configuration is read at runtime, not compile time. The `smtp-session` function calls `(config/get-config :smtp)` every time it creates a session. This means:

- Tests can swap the config with `with-redefs` and point to GreenMail
- The dev config points to Mailpit
- Production reads from environment variables
- No code changes between environments

This is the same pattern used for the signing key, the database URI, and the session key. Aero's profile system handles the branching, and the code just calls `get-config`.

## Trade-offs & limitations, in one place

- **No server-side session store means no per-session revocation.** A sealed cookie authenticates itself and the server keeps nothing, so a leaked cookie stays valid until `SESSION_KEY` is rotated -- and rotation signs the whole userbase out at once. Deleting a user and the `:user/active?` ban lever are the only sub-nuclear kill switches; a true "sign out this one device" would need a `:user/sessions-valid-after` stamp the app does not carry.
- **CSRF rests on `SameSite=Lax` and POST-only mutations, not a token.** Enforcement is the browser's job, so it is out of our hands; it does nothing against a same-origin attacker (an XSS hole, a hostile subdomain), and it does not cover the one state-changing GET, `/auth/verify`, which stays open to login CSRF -- named and left open, since the interstitial that would close it is the same fix the mail-scanner problem wants.
- **`/auth/verify` is a mutating GET.** Corporate mail scanners GET every link before the user does, consuming the one-shot nonce so the human's click lands on the error page. The app accepts this (request a new link) rather than building the interstitial -- the right trade for an audience on personal email, wrong the day users live behind a corporate filter.
- **Rate limiting is single-instance.** The sliding-window counter lives in an in-process atom, so two app instances behind a load balancer each keep their own counts and the effective limit multiplies by the replica count. A shared store is a prerequisite for going multi-instance, not a later optimization.
- **Deliverability lives in DNS and ops, not Clojure.** On a passwordless app a login that lands in spam is a failed authentication the user cannot retry their way out of, so SPF, DKIM, DMARC, and a reputable relay are part of the auth system -- and none of them is code the suite can test.

## Where this leaves us

The previous chapter's token primitive is now a working login, and the shape of it is the argument: email through Jakarta Mail with no wrapper library, the request/send/verify cycle on Post-Redirect-Get so a refresh never re-sends, a sealed http-only cookie with no server-side session store to scale or clean up, and a terms gate in the read path, so an account can exist as a clean, complete row before it has agreed to anything. None of it needs mocking to test: GreenMail captures real SMTP in-process, and the runtime-read config swaps between GreenMail, Mailpit, and a production provider without a line of code changing. No passwords are stored anywhere, because none are ever set.
