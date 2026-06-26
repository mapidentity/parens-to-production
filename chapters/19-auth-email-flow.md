# Passwordless Auth Part 2: Magic Link Emails and the Full Login Flow


In the [previous chapter](18-auth-tokens.md), we built the cryptographic foundation for passwordless authentication: HMAC-SHA256 signed tokens with expiration. But a token sitting in memory is useless until it reaches the user's inbox and completes the round trip back to our server. This post wires up the complete flow: sending magic link emails, handling the callback, creating sessions, and gating access behind terms acceptance.

By the end, you will have a fully working passwordless login system with no passwords stored anywhere.

## The Full Login Flow

Before diving into code, here is the sequence of events:

1. User enters their email and submits the login form
2. Server generates a signed token with a nonce, records the nonce, and sends an email containing the magic link
3. User clicks the link in their email
4. Server verifies the token, consumes the nonce (one-shot), creates the user if needed, and sets a session cookie
5. Server checks whether the user has accepted terms of service
6. If not, redirect to terms acceptance; otherwise, show the dashboard

Every step uses the Post-Redirect-Get pattern where appropriate, and the session is an encrypted cookie -- no server-side session store needed.

## Sending Email with Jakarta Mail (Eclipse Angus)

There are Clojure email libraries out there, but they are all thin wrappers around Jakarta Mail anyway. Using Jakarta Mail directly means one fewer dependency to track, and the API is straightforward enough that a wrapper does not add much value. Eclipse Angus is the reference implementation of Jakarta Mail since it moved out of the javax namespace.

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

### Configuring the SMTP Session

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

Two things worth noting. First, we branch on whether `user` is present. In development, we connect to Mailpit without authentication. In production, we use authenticated SMTP with STARTTLS. The same code handles both -- the config drives the behavior.

Second, the `^Session` type hint on the return value. With `*warn-on-reflection*` set to true, the Clojure compiler will tell us if we miss a hint that causes reflective method lookup. In a namespace full of Java interop, this matters for both performance and correctness.

### The send-magic-link! Function

```clojure
(defn send-magic-link!
  "Send magic link email to user via SMTP."
  [locale email token base-url]
  (let [magic-link (str base-url "/auth/verify?token=" token)
        from (config/get-config :smtp :from)
        session (smtp-session)
        msg (doto (MimeMessage. session)
              (.setFrom (InternetAddress. from))
              (.setRecipient Message$RecipientType/TO (InternetAddress. email))
              (.setSubject (t locale :email/magic-link-subject))
              (.setText (format (t locale :email/magic-link-body) magic-link)))]
    (try
      (Transport/send msg)
      (log/info "Magic link email sent" {:to email})
      {:error :SUCCESS}
      (catch Exception e
        (log/error e "Failed to send magic link email" {:to email})
        {:error :FAIL
         :message (.getMessage e)}))))
```

The function takes four arguments: the locale (for i18n), the recipient email, the signed token, and the base URL. It constructs the full magic link URL, builds a `MimeMessage`, and sends it.

A few design choices:

**Plain text email.** No HTML templates, no inline CSS wrestling. A magic link email should contain exactly one thing: the link. Plain text is universally readable, does not get clipped by email clients, and is trivial to test.

**i18n from the start.** The subject and body come from translation maps via the `t` function. The body template uses `%s` for the magic link URL, filled in with `format`. This means Dutch users get Dutch emails and English users get English ones. Adding this later would mean touching every email template. Adding it now costs nothing.

**Return value, not exception.** The function returns `{:error :SUCCESS}` or `{:error :FAIL :message "..."}`. The caller can decide what to do. In our case, the handler always shows the "check your email" page regardless -- we do not want to leak information about whether an email address is registered.

## The Handler Layer

With token creation (from the previous post) and email sending in place, the handlers orchestrate the full flow.

### Requesting a Magic Link (POST /auth/request)

```clojure
(defn request-magic-link
  "Handle a magic-link request -- send the email + record the nonce,
  then redirect (PRG pattern)."
  [request]
  (let [email (get-in request [:params :email])
        locale (:locale request)
        signing-key (config/get-config :signing-key)
        base-url (config/get-config :base-url)
        {:keys [token nonce]} (auth/create-magic-link-token signing-key email)]
    (email/send-magic-link! locale email token base-url)
    (analytics/record!
      [{:magic-link/email email
        :magic-link/nonce nonce
        :magic-link/requested-at (time/now)}])
    (response/redirect
      (str "/auth/sent?email="
           (java.net.URLEncoder/encode ^String email "UTF-8")))))
```

This handler does four things: create a token (and its nonce), send the email, **record the nonce**, and redirect. The redirect is the **Post-Redirect-Get pattern**; the nonce record is what makes the link single-use.

Recall from [Part 1](18-auth-tokens.md) that `create-magic-link-token` returns `{:token ... :nonce ...}`. The token goes in the email; the nonce we write to a small server-side store keyed by `:magic-link/nonce`, alongside the email and request time. (This store is the same lightweight event log the admin dashboard reads for analytics -- its schema is defined there; here we only need the nonce field.) When the user clicks the link, verification will look this record up and atomically flip it to "consumed." A second click finds it already consumed and is rejected.

### Why Post-Redirect-Get Matters

Without PRG, refreshing the "check your email" page would resubmit the form and send another email. The browser would show a "resubmit form data?" dialog. With PRG:

1. **POST** `/auth/request` -- sends the email, returns a 302 redirect
2. Browser follows redirect to **GET** `/auth/sent?email=user@example.com`
3. Refreshing this page is a harmless GET -- no duplicate emails

(The dispatcher from the views chapter enhances this form submission into an in-place fetch when JavaScript is available, but the server is oblivious to that -- it always renders the same full page and redirects. No `X-Enhanced` header, no content negotiation, no separate code path.)

### The Confirmation Page (GET /auth/sent)

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

### Verifying the Token (GET /auth/verify)

When the user clicks the magic link, three things must all hold before we sign them in: the token must be authentic and unexpired, its nonce must not have been used before, and a user account must exist (creating one on first sign-in). Let us build the one-shot check first.

#### Consuming the nonce exactly once

`verify-token` proves the token is genuine, but a genuine token can be presented twice. The nonce closes that gap -- but only if "mark this nonce as used" is **atomic**. If we read "unused," then separately wrote "used," two near-simultaneous clicks could both read "unused" and both succeed. We need a compare-and-swap.

Datomic gives us exactly this with the built-in `:db.fn/cas` transaction function:

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

The session is the moment where "stateless token" becomes "stateful session." The token was a one-time bridge to prove the user controls that email address; the nonce guaranteed it was crossed only once. The session persists across requests.

## Session Management

Sessions are configured in the middleware stack using Ring's cookie store:

```clojure
(def ^:private app*
  (delay
    (ring/ring-handler
      (ring/router routes)
      (ring/create-default-handler)
      {:middleware [[params/wrap-params]
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
                    [wrap-no-cache-authenticated]]})))
```

The session cookie is:

- **Encrypted** with a 16-byte key (AES via Ring's cookie store)
- **http-only** so JavaScript cannot read it
- **secure** so it only travels over HTTPS
- **same-site :lax** to prevent CSRF while still allowing the magic link GET request to work (the link opens in a new tab, which is a top-level navigation -- `:lax` permits this)
- **30 days** expiry

No server-side session store. The session data is encrypted inside the cookie itself. For our use case -- storing just an email address -- this is ideal. No session table to query, no cleanup jobs, no scaling concerns.

### Cache Control for Authenticated Pages

One subtle middleware worth highlighting:

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

## The Terms Acceptance Gate

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
         :body (str (views/dashboard (:locale request) user-email {}))}
        ;; Authenticated but terms not accepted -- show welcome page
        (response/redirect "/terms/welcome")))
    ;; Not authenticated
    (response/redirect "/")))
```

Three possible outcomes:

1. **Not authenticated** -- redirect to home page
2. **Authenticated, terms not accepted** -- redirect to `/terms/welcome`
3. **Authenticated, terms accepted** -- render the dashboard

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

> **Handler-gated here, middleware-gated in the repo.** The handlers above each read `[:session :user-email]` and run their own "are you signed in? have you accepted terms?" checks inline, which keeps this chapter self-contained -- every handler is readable on its own. The companion repo factors those two questions out into middleware instead: a `wrap-auth` resolves the signed-in user once and puts `:user-email`/`:user-eid` directly on the request (redirecting to `/` if there is none), and a `wrap-terms-accepted` enforces the terms gate (redirecting to `/terms/welcome` until `:user/terms-accepted-at` is set). The route tree then nests the protected routes under those wrappers, and the handlers shrink to their actual work -- `dashboard` becomes a three-line render that trusts `(:user-email request)` is present. It is the same logic, lifted from each handler into one place so it cannot be forgotten on a new route; the [admin chapter](21-admin-dashboard.md)'s `wrap-admin` is the next layer in that same stack. If you have built the middleware, prefer it; the inline form here is the minimal equivalent.

State-changing operations (request link, logout, accept terms) are POST. Idempotent reads (sent page, verify link, welcome page) are GET. The verify endpoint is GET because the user clicks a link in an email -- email clients do not POST.

## Testing with GreenMail

You cannot test email delivery by checking logs and hoping. GreenMail is an embedded SMTP server written in Java that captures emails in-process. No external mail server needed, no network flakiness, tests run in milliseconds.

The dependency goes in the `:test` alias:

```clojure
:test {:extra-paths ["test"]
       :extra-deps {com.icegreen/greenmail {:mvn/version "2.1.8"}}
       ...}
```

### The GreenMail Fixture

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

Key details:

- **Port 0** tells GreenMail to pick a random available port. No port conflicts, tests can run in parallel.
- **`with-redefs`** swaps the app config to point SMTP at the GreenMail instance. The production code does not know it is talking to a test server.
- **`binding`** makes the GreenMail instance available to test assertions via the `*greenmail*` dynamic var.
- **`finally`** ensures the server stops even if a test fails.

The `^"[Lcom.icegreen.greenmail.util.ServerSetup;"` type hint is the JVM's notation for an array of `ServerSetup` objects. It looks ugly, but it eliminates a reflection warning.

### Testing Email Delivery

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

## The Config Layer

One detail worth calling out: the SMTP configuration is read at runtime, not compile time. The `smtp-session` function calls `(config/get-config :smtp)` every time it creates a session. This means:

- Tests can swap the config with `with-redefs` and point to GreenMail
- The dev config points to Mailpit
- Production reads from environment variables
- No code changes between environments

This is the same pattern used for the signing key, the database URI, and the session key. Aero's profile system handles the branching, and the code just calls `get-config`.

## What We Have Now

Starting from the token infrastructure in the previous post, we have added:

1. **Email delivery** -- Jakarta Mail via Eclipse Angus, no wrapper libraries
2. **Magic link flow** -- request, send, verify, session creation
3. **Session management** -- encrypted cookie, http-only, secure, 30-day expiry
4. **Post-Redirect-Get** -- no accidental double-submissions
5. **Terms acceptance gate** -- users must agree before accessing the app
6. **GreenMail tests** -- real SMTP assertions, no mocking
7. **Mailpit for development** -- visual email inspection during development

The complete flow works like this: a user enters their email on the home page. The server creates a signed token, emails a magic link, and redirects to a "check your email" page. The user clicks the link. The server verifies the token, creates a session, and redirects to the dashboard. If the user has not accepted terms, they see the terms page first. All of this with zero passwords stored anywhere.

The system is also testable at every layer. Unit tests verify token signing and verification. Integration tests verify email delivery end-to-end with GreenMail. And the config system makes it trivial to swap between test, development, and production SMTP.

Next time, we will look at the view layer -- how the HTML for all these pages gets generated with Hiccup, and how progressive enhancement gives us a smoother experience without sacrificing the non-JavaScript baseline.
