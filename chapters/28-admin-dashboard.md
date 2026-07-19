# The Admin Dashboard: Locking Down a Privileged Surface

An admin dashboard is a tempting target. It reads across every user, exposes operational internals, and, unlike the rest of the app, has no legitimate audience but you. So the question that carries the weight is not "what does it show" but "who is allowed to see it, and what happens to everyone else." Get the authorization wrong and you have built a single page that leaks the whole tenancy.

This chapter sits in the book's hardening arc, and its real subject is that gate: a `wrap-admin` middleware that restricts the route group to one admin email, the JSON-vs-HTML branch that keeps an expired session from being mis-parsed as data, and the case-insensitive comparison that keeps a stray capital letter from locking the admin out of their own dashboard. The dashboard it guards is a conventional query-and-render once the gate is right: Datomic reads across two databases, a stat grid, a pair of tables. The one thing downstream of the gate that still needs an argument of its own is the live-polling lifecycle, where a naive poller leaks intervals across morph navigation; the CSS-animated counters are a presentational aside a security-minded reader can skip.

## The access control layer

Admin routes need to be locked down -- not "requires authentication" but "requires a specific email address." This is a solo-operator SaaS, so there is exactly one admin, which makes the rule simple to state and the failure modes the only interesting part. Three things have to be true at once: an unauthenticated request must not see the page, an authenticated non-admin must not see it, and (the trap) the admin must not be excluded by an accident of casing. The middleware encodes all three:

```clojure
(ns myapp.web.routes
  (:require
    [clojure.string :as str]
    [myapp.config :as config]
    [myapp.web.handler :as handler]
    [ring.util.response :as response]))

(defn wrap-admin
  "Restricts access to admin routes. Checks session for admin email.
  Unauthenticated HTML requests redirect to /, non-admin to /dashboard.
  Routes with {:json? true} get 401/403 JSON responses instead."
  [handler]
  (fn [request]
    (let [user-email (get-in request [:session :user-email])
          json? (get-in request [:reitit.core/match :data :json?])]
      (cond
        (nil? user-email)
        (if json?
          (handler/json-response {:error "unauthorized"} :status 401)
          (response/redirect "/"))

        (not= (some-> user-email str/lower-case)
              (some-> (config/get-config :admin-email) str/lower-case))
        (if json?
          (handler/json-response {:error "forbidden"} :status 403)
          (response/redirect "/dashboard"))

        :else (handler request)))))
```

The comparison is **case-insensitive**, and that is a hardening choice, not a stylistic one. A naive `(= user-email admin-email)` is the kind of check that passes every test and then locks you out in production the first time a session carries `Admin@example.com` while config holds `admin@example.com`. Email local parts are, strictly, case-sensitive under RFC 5321 -- but essentially no mailbox provider you will meet treats them that way, and here both sides name the same configured mailbox, so the only sensible behavior is to fold both sides to lower case before comparing. Skip the fold and the gate sits one stray capital letter away from excluding the one person it exists to admit.

> **How the repo factors this.** The version above does the lookup and the case-fold inline, which keeps this chapter self-contained. In the companion repo the work is split across the middleware stack from [the login-flow chapter](25-auth-email-flow.md): `wrap-current-user` resolves the session once and, via an `admin-email?` helper that does this same lower-cased comparison, stamps a boolean `:admin?` onto the request; `wrap-admin` is then the bottom layer that simply reads `(:admin? request)` and chooses redirect-or-JSON. The check is identical -- it has just moved up into the layer that already touched the session, so the gate itself is a one-line flag read. If you have built the auth chapters' middleware, prefer that structure; the standalone form here is the minimal thing that works on its own.

Three branches. The two that reject split again on HTML versus JSON; the third just passes through:

1. **Not authenticated at all** -- redirect to the home page (or 401 for JSON).
2. **Authenticated but not the admin** -- redirect to the regular dashboard (or 403 for JSON).
3. **Is the admin** -- pass through to the handler.

The branching on `json?` is the second decision that carries real weight here, and it exists because the same authorization rule has to serve two consumers with incompatible failure expectations. The dashboard is an HTML page; the live-polling endpoint is JSON. A browser navigation wants a redirect to a login page when the session lapses; a `fetch` does not. If the JSON endpoint answered an expired session with a 302 to an HTML login page, the `fetch` would silently follow the redirect and hand the polling script a chunk of HTML to parse as JSON -- a confusing client-side error standing in for a clean 401. So the middleware reads `:json?` from the reitit route data and chooses the representation to match the caller: a redirect for the page, a status-coded JSON error for the API.

The admin email is stored in config, not hardcoded. In routes, `wrap-admin` is applied to the entire `/admin` route group:

```clojure
["/admin" {:middleware [wrap-admin]}
 ["" {:get handler/admin-dashboard}]
 ["/stats"
  {:json? true
   :get handler/admin-stats}]]
```

The `:json? true` metadata on the `/stats` route tells `wrap-admin` to return JSON error responses. The HTML dashboard route inherits the middleware but uses the default HTML behavior.

## The analytics database

One design decision worth explaining: analytics data lives in a separate Datomic database from operational data. Same transactor, same underlying PostgreSQL storage -- zero new infrastructure. But logically separated so you can delete and recreate the analytics database without touching user data.

One caveat rides on that disposability, and it couples a security invariant to a database we have just called throwaway: the magic-link nonces that make sign-in single-use (see [the email-flow chapter](25-auth-email-flow.md)) live in this same analytics database. Deleting and recreating it clears the nonce log. That is harmless for nonces already consumed, but it resets replay protection for any magic link still outstanding at that instant, so a link minted just before the wipe could be replayed just after.

The obvious alternative is to keep the anti-replay nonce in the operational database and leave only the funnel counts disposable. It does not pay, because there is no separate record to move. One entity carries the email, the request and verify timestamps, and the nonce; consuming the nonce is the same transaction that stamps `:verified-at`, so the one-shot check and the "verified" funnel fact are a single write. Splitting the invariant out would mean writing two rows per link (one to each database) and joining across both to build the funnel, or else merging the databases back into one and giving up the line that lets analytics be dropped without touching user data. One row is one write and one source of truth. The window it costs opens only during a wipe of still-valid links, and a wipe is a maintenance action you schedule, so the rule is to run it when no unexpired links are outstanding, not to treat this database as unconditionally safe to discard.

```clojure
(ns myapp.analytics.db
  "Analytics database layer.
  Separate Datomic database for usage/analytics events. Same transactor,
  same PostgreSQL -- zero new infrastructure. Disposable, with one caveat:
  the magic-link nonces that make sign-in single-use live here, so
  recreate it only when no unexpired magic links are outstanding -- a wipe
  resets replay protection for links still in flight."
  (:require
    [datomic.api :as d]
    [myapp.config :as config])
  (:import
    [java.time Instant]
    [java.util Date]))

(set! *warn-on-reflection* true)

(def schema
  "Analytics schema -- magic link tracking."
  [{:db/ident :magic-link/email
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :magic-link/nonce
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Per-token nonce. Used as the one-shot anti-replay key."}
   {:db/ident :magic-link/requested-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :magic-link/verified-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])
```

The schema is small: four attributes for magic link tracking. Every magic link records the email it was sent to, a unique **nonce**, when it was requested, and when (if) it was verified. The email, request time, and verify time are enough to build a signup funnel; the nonce is what the [auth flow](25-auth-email-flow.md) keys its one-shot replay protection on. It is a `:db.unique/identity` attribute so a verify can look the link up by nonce and CAS-stamp `:verified-at` exactly once. This schema is *defined* here, in the analytics namespace, but it is first *written to* by the magic-link flow two chapters back -- which is why that chapter forward-references "the schema is defined with the admin dashboard." If you are building strictly in order, create `myapp.analytics.db` and its database when the auth flow first calls `analytics/record!`; the admin dashboard is simply its first reader.

The database setup functions follow:

```clojure
(defn analytics-uri
  "Returns the analytics database URI from config."
  []
  (config/get-config :analytics-database-uri))

(defn create-database!
  "Creates the analytics database and transacts the schema."
  []
  (let [uri (analytics-uri)]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn schema)
      conn)))

(defn get-connection
  "Returns a connection to the analytics database."
  []
  (d/connect (analytics-uri)))

(defn get-db
  "Returns the current analytics database value."
  []
  (d/db (get-connection)))
```

Recording events needs one small utility -- Datomic uses `java.util.Date` but the rest of the application works with `java.time.Instant`. The `record!` function handles the conversion:

```clojure
(defn- convert-instant
  "Converts java.time.Instant to java.util.Date for Datomic compatibility."
  [x]
  (if (instance? Instant x) (Date/from x) x))

(defn record!
  "Transacts analytics events, converting Instants to Dates."
  [tx-data]
  (let [conn (get-connection)]
    @(d/transact
       conn
       (mapv (fn [m] (into {} (map (fn [[k v]] [k (convert-instant v)])) m)) tx-data))))
```

It walks each map in the transaction data, converting any `Instant` to `Date` -- so the rest of the codebase works in `java.time` while Datomic gets the `java.util.Date` it expects.

## Admin queries

The admin dashboard needs data from both databases: user information from the operational database and magic link analytics from the analytics database. The query layer lives in its own namespace:

```clojure
(ns myapp.admin.core
  "Admin dashboard queries.
  Reads from both operational DB (users) and analytics DB (magic links)."
  (:require
    [datomic.api :as d])
  (:import
    [java.time Duration]
    [java.util Date]))

(set! *warn-on-reflection* true)
```

### Counting users

The simplest query -- total registered users:

```clojure
(defn total-users
  "Returns the total number of registered users."
  [db]
  (or
    (d/q
      '[:find (count ?e) .
        :where [?e :user/email]]
      db)
    0))
```

The `.` after `(count ?e)` is Datomic's scalar return syntax -- it returns a single value instead of a set of tuples. The `or` with `0` handles the empty-database case, where `d/q` returns `nil`.

One caveat to carry forward, because this number sits next to "Terms Accepted" in the grid: `total-users` counts user entities that *exist*, and a user entity is created at email verification -- so it is a superset of the people who passed the terms gate, and will read higher than "Terms Accepted" whenever someone verified but never accepted the terms.

### Listing all users

For the users table, we need more detail:

```clojure
(defn all-users
  "Returns all users sorted newest-first, with dates converted to Instants."
  [db]
  (->> (d/q '[:find
              [(pull ?e [:user/email :user/created-at
                         :user/terms-accepted-at :user/active?]) ...]
              :where [?e :user/email]]
            db)
       (sort-by :user/created-at #(compare %2 %1))
       (mapv (fn [u]
               (let [convert (fn [^Date d] (when d (.toInstant d)))]
                 (-> u
                     (update :user/created-at convert)
                     (update :user/terms-accepted-at convert)))))))
```

Four details do quiet work here:

- The `[... ...]` collection find spec returns a flat vector of results instead of nested tuples.
- `pull` fetches multiple attributes in one shot, avoiding N+1 queries.
- The sort uses `#(compare %2 %1)` for reverse chronological order -- newest first.
- Dates are converted from `java.util.Date` (Datomic) to `java.time.Instant` (the rest of the app).

### Recent magic links with time-to-click

This query is more interesting. It joins the request and verification timestamps, computes the time between them, and returns the 50 most recent:

```clojure
(defn recent-magic-links
  "Returns the 50 most recent magic links with time-to-click computed."
  [analytics-db]
  (let [to-instant (fn [^Date d] (when d (.toInstant d)))]
    (->> (d/q '[:find
                [(pull ?e [:magic-link/email :magic-link/requested-at
                           :magic-link/verified-at]) ...]
                :where [?e :magic-link/email]]
              analytics-db)
         (sort-by :magic-link/requested-at #(compare %2 %1))
         (take 50)
         (mapv (fn [ml]
                 (let [requested (to-instant (:magic-link/requested-at ml))
                       verified (to-instant (:magic-link/verified-at ml))
                       ttc (when (and requested verified)
                             (Duration/between requested verified))]
                   (cond-> {:email (:magic-link/email ml)
                            :requested-at requested}
                     verified (assoc :verified-at verified)
                     ttc (assoc :time-to-click ttc))))))))
```

Time-to-click is a useful metric: ten minutes between request and click suggests a deliverability problem, three seconds means the flow is working. `Duration/between` runs only when both timestamps exist (unverified links get `nil`), and `cond->` adds `:verified-at` and `:time-to-click` only when present, so the result maps stay free of nil entries.

### Signup funnel stats

The funnel query spans both databases:

```clojure
(defn funnel-stats
  "Returns signup funnel counts: links sent, verified, and terms accepted."
  [db analytics-db]
  (let [links-sent (or
                     (d/q
                       '[:find (count ?e) .
                         :where [?e :magic-link/requested-at]]
                       analytics-db)
                     0)
        links-verified (or
                         (d/q
                           '[:find (count ?e) .
                             :where [?e :magic-link/verified-at]]
                           analytics-db)
                         0)
        terms-accepted (or
                         (d/q
                           '[:find (count ?e) .
                             :where [?e :user/terms-accepted-at]]
                           db)
                         0)]
    {:links-sent links-sent
     :links-verified links-verified
     :terms-accepted terms-accepted}))
```

Three counts, two databases: links sent and verified from the analytics database, terms accepted from the operational one. Together they trace the full funnel -- requested, clicked, accepted -- and `terms-accepted`, not `total-users`, is the count that means "completed signup."

Three counts across two databases is also the shape that gets hard to debug from the outside: when a funnel number looks wrong, the first question is which database the count came from and at which basis it was read. Under the `:storm` alias that question stops being archaeology. Load `/admin` with the [construction view](17-construction-view.md) recording and the call tree shows `funnel-stats` with each `d/q` as real Datalog, each against its own database value, each carrying the basis-t it read at; the wrong number is one click from the query that produced it -- the promised payoff of building the tooling before the features that need it.

### The polling endpoint

The JSON endpoint for live polling bundles everything into a flat map with numeric values:

```clojure
(defn dashboard-stats
  "Returns raw numeric stats for the live-polling JSON endpoint."
  [db analytics-db]
  (let [funnel (funnel-stats db analytics-db)
        ^Runtime runtime (Runtime/getRuntime)
        mb (fn [^long n] (long (/ n 1048576)))]
    {:total-users (total-users db)
     :links-sent (:links-sent funnel)
     :links-verified (:links-verified funnel)
     :terms-accepted (:terms-accepted funnel)
     :jvm-used-mb (mb (- (.totalMemory runtime) (.freeMemory runtime)))
     :jvm-free-mb (mb (.freeMemory runtime))
     :jvm-total-mb (mb (.totalMemory runtime))
     :jvm-max-mb (mb (.maxMemory runtime))}))
```

It returns `long` values, not formatted strings, because the frontend compares numbers across polls. The JVM figures are pure Java interop read straight off `Runtime` (used is total minus free, `max` is the ceiling the JVM may claim), with a `^long` hint on `mb` so the division does not trip the boxed-math warning the [strict-compilation chapter](04-build-hardening.md) turns on. The key names match the `data-stat` attributes in the HTML, which is how the polling script knows which element to update.

## The handler layer

The handler orchestrates queries and renders the view:

```clojure
(defn admin-dashboard
  "Render the admin dashboard. Access control handled by wrap-admin."
  [request]
  (let [db (d/db (db/get-connection))
        analytics-db (analytics/get-db)
        ^Runtime runtime (Runtime/getRuntime)
        mb (fn [^long n] (long (/ n 1048576)))
        jvm-used-mb (mb (- (.totalMemory runtime) (.freeMemory runtime)))]
    (html
      (admin-views/admin-dashboard
        {:total-users (admin/total-users db)
         :users (admin/all-users db)
         :magic-links (admin/recent-magic-links analytics-db)
         :funnel (admin/funnel-stats db analytics-db)
         :jvm {:jvm-free-mb (mb (.freeMemory runtime))
               :jvm-total-mb (mb (.totalMemory runtime))
               :jvm-max-mb (mb (.maxMemory runtime))}
         :jvm-used-mb jvm-used-mb
         :user-email (:user-email request)}))))

(defn admin-stats
  "JSON endpoint for the live-polling admin stat cards. Guarded by wrap-admin."
  [_request]
  (let [db (d/db (db/get-connection))
        analytics-db (analytics/get-db)]
    (json-response (admin/dashboard-stats db analytics-db))))
```

Two handlers, one route group. The dashboard handler fetches everything and renders HTML. The stats handler returns JSON for the polling script. Both are protected by `wrap-admin` at the route level, so the handlers themselves do not need to check authorization.

Notice that the dashboard handler calls `d/db` once to get a point-in-time snapshot, so every query in the request sees the same database state even under concurrent writes -- no inconsistent reads within a request.

## The stat grid component

The view layer renders a grid of stat cards using Hiccup:

```clojure
(defn- stat-card
  "Renders a single stat cell in the shared-border grid.
  Optional trending text appears top-right (e.g. '83%')."
  ([label stat-key raw-value] (stat-card label stat-key raw-value nil))
  ([label stat-key raw-value trending]
   [:div
    {:class
     "flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2
      bg-surface px-4 py-8 sm:px-6"}
    [:dt {:class "text-sm/6 font-medium text-text-secondary"} label]
    (when trending
      [:dd {:class "text-xs font-medium text-positive"} trending])
    [:dd
     {:class "w-full flex-none text-3xl/10 font-medium tracking-tight
              text-text-primary"
      :data-stat stat-key
      :data-value (str raw-value)
      :style (str "--stat-value:" raw-value)}]]))
```

Each stat card has three data attributes that make live polling work:

- `data-stat` -- the key name (matches the JSON response keys).
- `data-value` -- the current numeric value (used to detect changes).
- `style="--stat-value:N"` -- a CSS custom property that drives the animated counter.

The card does not render the number as text content; the value rides on the `--stat-value` custom property and CSS renders it with `counter()`, which is what lets the value animate on update (covered below).

The dashboard view assembles eight cards into a 4-column, 2-row grid:

```clojure
(defn admin-dashboard
  "Renders the admin dashboard page."
  [{:keys [users magic-links funnel jvm total-users jvm-used-mb user-email]}]
  (let [verification-rate
        (when (pos? (long (:links-sent funnel)))
          (format
            "%.0f%%"
            (* 100.0 (/ (double (:links-verified funnel))
                        (double (:links-sent funnel))))))
        jvm-total-mb (long (:jvm-total-mb jvm))
        jvm-max-mb (long (:jvm-max-mb jvm))
        jvm-used-pct
        (when (pos? jvm-total-mb)
          (format "%.0f%%" (* 100.0 (/ (double jvm-used-mb)
                                       (double jvm-total-mb)))))
        jvm-total-pct
        (when (pos? jvm-max-mb)
          (format "%.0f%%" (* 100.0 (/ (double jvm-total-mb)
                                       (double jvm-max-mb)))))]
    (views/app-layout
      :en
      user-email
      :admin
      {:admin? true}
      [:div
       [:dl
        {:class "grid grid-cols-2 gap-px rounded-lg bg-border
                 overflow-hidden sm:grid-cols-4 mb-8"}
        (stat-card "Total Users" "total-users" total-users)
        (stat-card "Links Sent" "links-sent" (:links-sent funnel))
        (stat-card "Verified" "links-verified" (:links-verified funnel)
                   verification-rate)
        (stat-card "Terms Accepted" "terms-accepted" (:terms-accepted funnel))
        (stat-card "JVM Used" "jvm-used-mb" jvm-used-mb jvm-used-pct)
        (stat-card "JVM Free" "jvm-free-mb" (:jvm-free-mb jvm)
                   (when (pos? jvm-total-mb)
                     (format "%.0f%%" (* 100.0 (/ (double (:jvm-free-mb jvm))
                                                  (double jvm-total-mb))))))
        (stat-card "JVM Total" "jvm-total-mb" jvm-total-mb jvm-total-pct)
        (stat-card "JVM Max" "jvm-max-mb" jvm-max-mb)]

       [:div.space-y-8
        (users-table users)
        (magic-links-table magic-links)]

       (live-stats-style)])))
```

The `gap-px` and `bg-border` classes create a shared-border effect: the container's border-colored background shows through a 1px gap between solid-surface cards, so the cards appear to share borders without any border elements. The verification rate ("83%") rides on the "Verified" card as a trending indicator, and three JVM cards carry one the same way: used and free as a percentage of total, and total as a percentage of max.

The `live-stats-style` function inlines the dashboard's animated-counter CSS at the bottom of the page, loaded from a classpath resource with the `defn-asset` macro that reads the file once in production and re-reads on every call in development (for hot-reload):

```clojure
(defn-asset live-stats-style "myapp/admin/views.css")
```

The polling **JavaScript**, by contrast, is *not* inlined. It is an ordinary ES module, `static/js/admin-stats.js`, loaded once from the base layout's `<head>` alongside the other module scripts (`(script-tag "js/admin-stats.js" {:type "module"})`, from [the Hiccup views chapter](14-hiccup-views.md)). What keeps it out of the page body is the morph lifecycle. The dashboard is reached by morphing a fragment into the live document, and the dispatcher never runs `<script>` elements inside a morphed fragment (the live-polling section below leans on this), so a `<script>` in `<main>` would never fire on a morph navigation into `/admin`. A module in the `<head>` evaluates once when the document first loads and stays resident. The strict Content-Security-Policy from [the asset pipeline chapter](29-asset-pipeline.md) is not the constraint here: the same `defn-asset` machinery used for the CSS above registers a script asset's content hash into `script-src`, so a registered inline `<script>` would clear the policy cleanly. Morph execution is the real reason; the policy would have allowed it. The module runs on every page but no-ops unless it finds the dashboard's stat elements, so loading it globally costs nothing elsewhere.

## A note on the animated counters

One presentational detail, kept short on purpose: the stat numbers tween to their new values instead of snapping. This is polish. The dashboard's correctness and its authorization gate are the chapter's real subject, and a reader auditing this for security can skip the rest of this section. It survives the cut only because it costs no JavaScript animation code at all.

The mechanism is `@property`. Declaring a custom property with an `<integer>` type makes it animatable; a CSS counter reads that property, and a `::after` pseudo-element renders the counter as text. A transition on the property does the interpolation:

```css
@property --stat-value {
  syntax: "<integer>";
  initial-value: 0;
  inherits: false;
}

[data-stat] {
  counter-reset: stat var(--stat-value);
  transition: --stat-value 600ms cubic-bezier(0.33, 1, 0.68, 1), color 400ms;
}

[data-stat]::after { content: counter(stat); }
```

When the poller sets `--stat-value` from 5 to 8, the browser interpolates through 6 and 7 and the counter follows. The type declaration is what makes this work -- an untyped custom property is just a string and cannot be interpolated. The JVM cards append `" MB"` to their `::after` content; a `.changed-up` / `.changed-down` class added on update flips the color and fades a directional arrow in via a keyframe. None of it touches the data path.

## Live polling with vanilla JavaScript

The polling script -- `static/js/admin-stats.js`, the module the base layout loads -- has no framework and no build step. What it does have is a lifecycle, and that is the part worth dwelling on. The dashboard is reached by morphing a fragment in (the morph dispatcher, [progressive enhancement](20-progressive-enhancement.md)), and an ES module evaluates once per document. The dispatcher never re-executes scripts in morphed fragments. So the naive shapes fail in two distinct ways. An IIFE that calls `setInterval(poll, 20000)` at import time starts its interval on the first page the user loads (any page) and, since morph navigation never unloads the document, keeps polling the privileged `/admin/stats` endpoint from every page forever after. Wire the interval on each `dispatcher:morphed` event instead and it stacks: without a matching teardown, three visits to `/admin` leave the endpoint being polled three times over. The fix is to hang the poller off the controller registry from that same chapter, which gives every enhancer a `connect`/`disconnect` pair tied to whether its element is actually in the live DOM:

```javascript
import { register } from '/js/controllers.js';

let timer = null;
let liveCards = 0;

function poll() {
  fetch('/admin/stats', { credentials: 'same-origin' })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (data) {
      if (!data) return;
      var els = document.querySelectorAll('[data-stat]');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        var key = el.getAttribute('data-stat');
        if (!(key in data)) continue;
        var oldVal = parseInt(el.getAttribute('data-value'), 10);
        var newVal = data[key];
        if (oldVal !== newVal) {
          el.style.setProperty('--stat-value', newVal);
          el.setAttribute('data-value', newVal);
          el.classList.remove('changed-up', 'changed-down');
          void el.offsetWidth;
          var cls = newVal > oldVal ? 'changed-up' : 'changed-down';
          el.classList.add(cls);
          setTimeout(function (e, c) { e.classList.remove(c); }, 3000, el, cls);
        }
      }
    })
    .catch(function () {});
}

register('live-stats', {
  selector: '[data-stat]',
  connect() {
    liveCards++;
    if (!timer) timer = setInterval(poll, 20000);
  },
  disconnect() {
    liveCards = Math.max(0, liveCards - 1);
    if (liveCards === 0 && timer) { clearInterval(timer); timer = null; }
  },
});
```

A single shared interval serves every card: a refcount (`liveCards`) starts it on the first `connect` and clears it when the last card disconnects, so navigating in and out of `/admin` never leaks a second poller and the script is wholly inert anywhere the `[data-stat]` cards do not exist. The `poll` body itself is the simple part. Every 20 seconds it fetches `/admin/stats` and compares each value against the current `data-value`. On a change it updates `--stat-value` (which triggers the tween), writes the new `data-value` as the next baseline, and briefly applies a `changed-up`/`changed-down` class for the directional arrow. The `void el.offsetWidth` between removing and re-adding that class forces a synchronous reflow so the arrow animation restarts rather than no-opping -- a standard CSS-restart trick, called out only so the line is not mistaken for dead code.

The `credentials: 'same-origin'` option makes the session cookie's behavior explicit at the call site: it rides along with the `fetch`, which is what `wrap-admin` reads to authorize the polling endpoint. A same-origin `fetch` already sends credentials by default, so stating it is belt-and-suspenders -- it documents the requirement and survives a refactor that moves the endpoint cross-origin, or a caller that sets `credentials: 'omit'`, either of which would strip the cookie and earn the 401 the JSON branch exists to produce.

The empty `.catch(function() {})` swallows network errors on purpose: if the server is briefly unreachable the dashboard keeps showing the last known values, and the next poll in 20 seconds picks up where it left off. No toast, no retry backoff, no complexity.

## The data tables

Below the stat grid, the dashboard renders two data tables. Here is the users table:

```clojure
(defn- users-table
  "Renders the users table."
  [users]
  [:div.bg-surface.border.border-border.rounded-lg.overflow-hidden
   [:div.px-6.py-4.border-b.border-border
    [:h3.text-lg.font-medium.text-text-primary "Users"]]
   (if (seq users)
     [:table.min-w-full.divide-y.divide-border
      [:thead.bg-surface-subtle
       [:tr
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Email"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Created"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Terms Accepted"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Active"]]]
      [:tbody.bg-surface.divide-y.divide-border
       (for [u users]
         [:tr
          [:td.px-6.py-3.5.text-sm.text-text-primary (:user/email u)]
          [:td.px-6.py-3.5.text-sm.text-text-secondary
           (fmt-instant (:user/created-at u))]
          [:td.px-6.py-3.5.text-sm.text-text-secondary
           (fmt-instant (:user/terms-accepted-at u))]
          [:td.px-6.py-3.5.text-sm
           (if (:user/active? u)
             [:span.text-positive "Yes"]
             [:span.text-negative "No"])]])]]
     [:p.px-6.py-4.text-sm.text-text-secondary "No users yet."])])
```

And the magic links table, which includes the time-to-click metric:

```clojure
(defn- magic-links-table
  "Renders the recent magic links table."
  [links]
  [:div.bg-surface.border.border-border.rounded-lg.overflow-hidden
   [:div.px-6.py-4.border-b.border-border
    [:h3.text-lg.font-medium.text-text-primary "Recent Magic Links"]]
   (if (seq links)
     [:table.min-w-full.divide-y.divide-border
      [:thead.bg-surface-subtle
       [:tr
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Email"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Requested"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Verified"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Time to Click"]]]
      [:tbody.bg-surface.divide-y.divide-border
       (for [ml links]
         [:tr
          [:td.px-6.py-3.5.text-sm.text-text-primary (:email ml)]
          [:td.px-6.py-3.5.text-sm.text-text-secondary
           (fmt-instant (:requested-at ml))]
          [:td.px-6.py-3.5.text-sm.text-text-secondary
           (or (fmt-instant (:verified-at ml))
               [:span.text-warning "Pending"])]
          [:td.px-6.py-3.5.text-sm.text-text-secondary
           (or (fmt-duration (:time-to-click ml)) "-")]])]]
     [:p.px-6.py-4.text-sm.text-text-secondary "No magic links yet."])])
```

Where the users table renders a fixed cell for every row, this one carries the two fallbacks an unverified link forces: it shows "Pending" in a warning color instead of a verify timestamp, and a dash instead of a time-to-click nobody has produced yet. Both fall out of a plain `or` in the cell: the computed value when it exists, a placeholder when it does not.

The date formatting uses `java.time.format.DateTimeFormatter` configured for the Europe/Amsterdam timezone:

```clojure
(def ^:private ^DateTimeFormatter datetime-fmt
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
      (.withZone (ZoneId/of "Europe/Amsterdam"))))

(defn- fmt-instant
  "Formats an Instant as yyyy-MM-dd HH:mm:ss in Europe/Amsterdam."
  [^Instant inst]
  (when inst (.format datetime-fmt inst)))

(defn- fmt-duration
  "Formats a Duration as a human-readable string."
  [^Duration dur]
  (when dur
    (let [secs (.toSeconds dur)]
      (cond
        (< secs 60) (str secs "s")
        (< secs 3600) (format "%dm %ds" (quot secs 60) (rem secs 60))
        :else (format "%dh %dm" (quot secs 3600) (quot (rem secs 3600) 60))))))
```

The duration formatter covers the useful range -- seconds, then minutes and seconds, then hours and minutes. A link that takes "2h 15m" to be clicked is a strong signal that mail is landing in spam.

## How it all fits together

The data flow is one straight line: `wrap-admin` checks the session at the door; the handler queries both databases -- operational for users, analytics for magic links; Hiccup renders the stat grid with its `data-stat` attributes and `--stat-value` custom properties; the browser paints the initial numbers with CSS counters; and every 20 seconds the poller fetches `/admin/stats` and updates the custom property on any card whose value changed, letting the counter tween to the new figure.

No WebSockets, no server-sent events, no client-side state management. The page is server-rendered, the polling is a simple `setInterval` with `fetch`, and the presentation is pure CSS.

The authorization is the part that has to be right; everything downstream of it is conventional query-and-render. No monitoring service to pay for, no dashboard framework to learn, no JavaScript build pipeline to maintain -- just your server, your database, and a gate you can audit in one screen.
