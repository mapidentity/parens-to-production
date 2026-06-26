# Building an Admin Dashboard: Datomic Queries, Live Stats, and CSS Animations


---

You do not need an admin dashboard on day one. But the moment you have even a handful of users, you need visibility into what is happening in your application. How many people signed up? Are magic links getting verified? Is the JVM healthy? Without a dashboard, you are flying blind -- SSHing into a server and running ad-hoc REPL queries every time you want a number.

This chapter builds a complete admin dashboard: a middleware layer that restricts access to a single admin email, Datomic queries across two databases, a stat grid component with live polling, and CSS-driven animated counters. No JavaScript frameworks, no charting libraries, no build step for the frontend. Just server-rendered HTML, a handful of Datomic queries, and about 30 lines of vanilla JS.

## The Access Control Layer

Admin routes need to be locked down. Not just "requires authentication" but "requires a specific email address." This is a solo-operator SaaS -- there is exactly one admin. The middleware is straightforward:

```clojure
(ns myapp.web.routes
  (:require
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

The admin comparison is **case-insensitive** -- email addresses are not case-sensitive in their local part for any provider you will meet in practice, so `Admin@example.com` and `admin@example.com` must resolve to the same person, or you lock the admin out of their own dashboard. (This needs `[clojure.string :as str]` in the namespace.)

> **How the repo factors this.** The version above reads the email straight from the session and runs on its own, which keeps this chapter self-contained. In the companion repo, `wrap-admin` is the bottom layer of the middleware stack described in [the login-flow chapter](19-auth-email-flow.md) (under "Handler-gated here, middleware-gated in the repo"): `wrap-auth` puts `:user-email`/`:user-eid` on the request and `wrap-terms-accepted` enforces the terms gate, so `wrap-admin` just reads `(:user-email request)` rather than digging into the session itself. Same check, composed into that stack instead of re-deriving the user. If you have built the auth chapters' middleware, prefer that structure; the standalone form here is the minimal thing that works on its own.

Three branches, each with two sub-branches for HTML vs JSON responses:

1. **Not authenticated at all** -- redirect to the home page (or 401 for JSON).
2. **Authenticated but not the admin** -- redirect to the regular dashboard (or 403 for JSON).
3. **Is the admin** -- pass through to the handler.

The `json?` check uses reitit route data. This matters because the dashboard has both an HTML page and a JSON polling endpoint. You do not want the polling endpoint to return a 302 redirect with an HTML body when the session expires -- the JavaScript `fetch` call would silently follow the redirect and try to parse the login page as JSON.

The admin email is stored in config, not hardcoded. In routes, `wrap-admin` is applied to the entire `/admin` route group:

```clojure
["/admin" {:middleware [wrap-admin]}
 ["" {:get handler/admin-dashboard}]
 ["/stats"
  {:json? true
   :get handler/admin-stats}]]
```

The `:json? true` metadata on the `/stats` route tells `wrap-admin` to return JSON error responses. The HTML dashboard route inherits the middleware but uses the default HTML behavior.

## The Analytics Database

One design decision worth explaining: analytics data lives in a separate Datomic database from operational data. Same transactor, same underlying PostgreSQL storage -- zero new infrastructure. But logically separated so you can delete and recreate the analytics database without touching user data.

```clojure
(ns myapp.analytics.db
  "Analytics database layer.
  Separate Datomic database for usage/analytics events. Same transactor,
  same PostgreSQL -- zero new infrastructure. Can be deleted/recreated
  without affecting operational data."
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

The schema is small: four attributes for magic link tracking. Every magic link records the email it was sent to, a unique **nonce**, when it was requested, and when (if) it was verified. The email, request time, and verify time are enough to build a signup funnel; the nonce is what the [auth flow](19-auth-email-flow.md) keys its one-shot replay protection on -- it is a `:db.unique/identity` attribute so a verify can look the link up by nonce and CAS-stamp `:verified-at` exactly once. This schema is *defined* here, in the analytics namespace, but it is first *written to* by the magic-link flow two chapters back -- which is why that chapter forward-references "the schema is defined with the admin dashboard." If you are building strictly in order, create `myapp.analytics.db` and its database when the auth flow first calls `analytics/record!`; the admin dashboard is simply its first reader.

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

The `record!` function walks each map in the transaction data, converting any `Instant` values to `Date`. This lets the rest of the codebase work with `java.time` while Datomic gets the `java.util.Date` it expects.

## Admin Queries

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

### Counting Users

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

### Listing All Users

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

A few things to note here:

- The `[... ...]` collection find spec returns a flat vector of results instead of nested tuples.
- `pull` fetches multiple attributes in one shot, avoiding N+1 queries.
- The sort uses `#(compare %2 %1)` for reverse chronological order -- newest first.
- Dates are converted from `java.util.Date` (Datomic) to `java.time.Instant` (the rest of the app).

### Recent Magic Links with Time-to-Click

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

Time-to-click is a genuinely useful metric. If users take 10 minutes to click a magic link, there might be a deliverability problem. If they click in 3 seconds, the flow is working. The `Duration/between` computation is only done when both timestamps exist -- unverified links get `nil`.

The `cond->` threading macro conditionally adds `:verified-at` and `:time-to-click` only when they have values. This avoids polluting the result maps with nil entries.

### Signup Funnel Stats

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

Three counts, two databases. Links sent and verified come from the analytics database. Terms accepted comes from the operational database. Together they show the full funnel: how many people requested a magic link, how many clicked it, and how many accepted terms and actually signed up.

### The Polling Endpoint

The JSON endpoint for live polling bundles everything into a flat map with numeric values:

```clojure
(defn dashboard-stats
  "Returns raw numeric stats for the live-polling JSON endpoint."
  [db analytics-db]
  (let [funnel (funnel-stats db analytics-db)
        runtime (Runtime/getRuntime)
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

This function returns `long` values (not formatted strings) because the JavaScript frontend needs numbers for comparison and animation. The JVM memory figures are pure Java interop -- no Datomic involved -- read straight off `Runtime`: used is total minus free, and `max` is the ceiling the JVM is allowed to claim. Note the `^long` type hint on the `mb` helper: without it, the division would trigger a boxed-math warning -- exactly the kind of thing the strict compilation setup from [the strict-compilation chapter](04-build-hardening.md) catches. The key names match the `data-stat` attributes in the HTML, which is how the polling script knows which element to update.

## The Handler Layer

The handler orchestrates queries and renders the view:

```clojure
(defn admin-dashboard
  "Renders the admin dashboard. Access control handled by wrap-admin middleware."
  [request]
  (let [db (d/db (db/get-connection))
        analytics-db (analytics/get-db)
        runtime (Runtime/getRuntime)
        mb (fn [^long n] (long (/ n 1048576)))
        jvm-used-mb (mb (- (.totalMemory runtime) (.freeMemory runtime)))
        user-email (get-in request [:session :user-email])]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str
             (admin-views/admin-dashboard
               {:total-users (admin/total-users db)
                :users (admin/all-users db)
                :magic-links (admin/recent-magic-links analytics-db)
                :funnel (admin/funnel-stats db analytics-db)
                :jvm {:jvm-free-mb (mb (.freeMemory runtime))
                      :jvm-total-mb (mb (.totalMemory runtime))
                      :jvm-max-mb (mb (.maxMemory runtime))}
                :jvm-used-mb jvm-used-mb
                :user-email user-email}))}))

(defn admin-stats
  "JSON endpoint for live-polling admin stat cards.
  Access control handled by wrap-admin middleware."
  [_request]
  (let [db (d/db (db/get-connection))
        analytics-db (analytics/get-db)]
    (json-response (admin/dashboard-stats db analytics-db))))
```

Two handlers, one route group. The dashboard handler fetches everything and renders HTML. The stats handler returns JSON for the polling script. Both are protected by `wrap-admin` at the route level, so the handlers themselves do not need to check authorization.

Notice that the dashboard handler calls `d/db` to get a point-in-time snapshot. This is important -- all queries within a single request see the same database state, even if transactions happen concurrently. This is one of Datomic's strengths: you never get inconsistent reads within a request.

## The Stat Grid Component

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

The card does not render the number as text content. Instead, the value is set via the `--stat-value` CSS custom property, and CSS renders it using `counter()`. This is what enables the smooth animated transitions on update.

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
        (stat-card "JVM Free" "jvm-free-mb" (:jvm-free-mb jvm))
        (stat-card "JVM Total" "jvm-total-mb" jvm-total-mb jvm-total-pct)
        (stat-card "JVM Max" "jvm-max-mb" jvm-max-mb)]

       [:div.space-y-8
        (users-table users)
        (magic-links-table magic-links)]

       (live-stats-style)])))
```

The `gap-px` and `bg-border` classes create a shared-border effect: the grid container has the border color as its background, and each card has a solid surface background. The 1px gap between cards reveals the container's border-colored background, creating the appearance of shared borders without any actual border elements. This is a nice Tailwind pattern.

The verification rate ("83%") appears as a trending indicator on the "Verified" card, giving at-a-glance conversion info. JVM stats show percentages too -- used as a percentage of total, and total as a percentage of max.

The `live-stats-style` function inlines the dashboard's animated-counter CSS at the bottom of the page, loaded from a classpath resource with the `defn-asset` macro that reads the file once in production and re-reads on every call in development (for hot-reload):

```clojure
(defn-asset live-stats-style "myapp/admin/views.css")
```

The polling **JavaScript**, by contrast, is *not* inlined. It is an ordinary ES module, `static/js/admin-stats.js`, loaded once from the base layout's `<head>` alongside the other module scripts (`(script-tag "js/admin-stats.js" {:type "module"})`, from [the Hiccup views chapter](11-hiccup-views.md)). That is a deliberate split: an inline `<script>` in the page body would be forbidden by the strict Content-Security-Policy we add in [the asset pipeline chapter](22-asset-pipeline.md) -- `<main>` carries no inline scripts -- whereas a hashed-and-served module passes cleanly. The script runs on every page but no-ops unless it finds the dashboard's stat elements, so loading it globally costs nothing elsewhere. Inline CSS is fine under the policy; inline behavior is not.

## CSS Animated Counters

The CSS is where things get interesting. Modern CSS has a feature called `@property` that lets you define custom properties with types. When combined with CSS counters and transitions, you get animated number counting with no JavaScript animation code:

```css
@property --stat-value {
  syntax: "<integer>";
  initial-value: 0;
  inherits: false;
}

[data-stat] {
  --stat-value: 0;
  counter-reset: stat var(--stat-value);
  transition: --stat-value 600ms cubic-bezier(0.33, 1, 0.68, 1),
              color 400ms;
}

[data-stat]::after {
  content: counter(stat);
}
```

Here is how it works:

1. `@property` declares `--stat-value` as an integer type. This is critical -- CSS can only animate between values it understands. A raw custom property is just a string and cannot be interpolated. Declaring it as `<integer>` tells the browser it is a number.
2. `counter-reset: stat var(--stat-value)` sets a CSS counter named `stat` to the value of the custom property.
3. The `::after` pseudo-element renders `counter(stat)` as the visible text.
4. The `transition` property animates changes to `--stat-value` over 600ms with an ease-out curve.

When JavaScript updates `--stat-value` from 5 to 8, the browser interpolates through 6 and 7, updating the counter display at each frame. The result is a smooth counting animation with zero JavaScript animation logic.

JVM stat cards append " MB" to their counter display:

```css
[data-stat="jvm-used-mb"]::after {
  content: counter(stat) " MB";
}

[data-stat="jvm-free-mb"]::after { content: counter(stat) " MB"; }
[data-stat="jvm-total-mb"]::after { content: counter(stat) " MB"; }
[data-stat="jvm-max-mb"]::after { content: counter(stat) " MB"; }
```

### Change Direction Indicators

When a value changes, a small arrow appears briefly to show the direction:

```css
[data-stat].changed-up { color: #16a34a; }
[data-stat].changed-down { color: #dc2626; }

[data-stat]::before {
  font-size: 0.6em;
  margin-right: 0.15em;
  opacity: 0;
}

[data-stat].changed-up::before {
  content: "\2191";
  color: #16a34a;
  animation: fade-arrow 3s forwards;
}

[data-stat].changed-down::before {
  content: "\2193";
  color: #dc2626;
  animation: fade-arrow 3s forwards;
}

@keyframes fade-arrow {
  0% { opacity: 1; }
  60% { opacity: 1; }
  100% { opacity: 0; }
}
```

An upward arrow in green for increases, a downward arrow in red for decreases. The arrow fades out after 3 seconds. The `forwards` fill mode keeps the arrow hidden after the animation completes. The number itself also briefly changes color to match.

## Live Polling with Vanilla JavaScript

The polling script -- `static/js/admin-stats.js`, the module the base layout loads -- is intentionally simple. No framework, no build step, no dependencies:

```javascript
(function() {
  function poll() {
    fetch('/admin/stats', {credentials: 'same-origin'})
      .then(function(r) { return r.ok ? r.json() : null; })
      .then(function(data) {
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
            setTimeout(function(e, c) { e.classList.remove(c); }, 3000, el, cls);
          }
        }
      })
      .catch(function() {});
  }

  setInterval(poll, 20000);
})();
```

Every 20 seconds, it fetches `/admin/stats` and compares each value against the current `data-value` attribute. When a value changes:

1. Update the `--stat-value` CSS custom property (triggers the counter animation).
2. Update `data-value` so the next poll has a correct baseline.
3. Remove any existing direction class, then force a reflow with `void el.offsetWidth` (this restarts the CSS animation).
4. Add the appropriate direction class (`changed-up` or `changed-down`).
5. Schedule removal of the direction class after 3 seconds.

The `void el.offsetWidth` trick is worth explaining. If an element already has the `changed-up` class and the value increases again, simply removing and re-adding the class would not restart the animation -- the browser would not see a state change. Reading `offsetWidth` forces a synchronous layout recalculation between the remove and add, which the browser treats as a genuine state transition.

The `credentials: 'same-origin'` option ensures cookies are sent with the request, which is necessary for the session-based authentication that `wrap-admin` checks.

The empty `.catch(function() {})` silently swallows network errors. This is intentional -- if the server is briefly unreachable, the dashboard just keeps showing the last known values. No error toast, no retry backoff, no complexity. The next poll in 20 seconds will pick up where things left off.

## The Data Tables

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

Unverified magic links show "Pending" in a warning color instead of a timestamp. Time-to-click shows a dash for links that have not been clicked yet.

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

The duration formatter is simple but covers the useful range: seconds for short durations, minutes and seconds for medium ones, hours and minutes for long ones. A magic link that takes "2h 15m" to be clicked is a strong signal that something is landing in spam.

## How It All Fits Together

The data flow is:

1. User hits `/admin` -- `wrap-admin` checks the session, confirms the admin email.
2. The handler queries both databases (operational for users, analytics for magic links).
3. Hiccup renders the stat grid with `data-stat` attributes and `--stat-value` CSS properties.
4. The browser renders the page, CSS counter shows the initial values.
5. Every 20 seconds, JavaScript polls `/admin/stats` for fresh numbers.
6. When a value changes, JavaScript updates the CSS custom property.
7. The browser animates the counter from the old value to the new value over 600ms.
8. A directional arrow briefly appears and fades out.

No WebSockets, no server-sent events, no client-side state management. The page is server-rendered, the polling is a simple `setInterval` with `fetch`, and the animations are pure CSS. The total JavaScript is about 30 lines.

## What You Now Have

After implementing this, you have:

- **Access-controlled admin routes** using a `wrap-admin` middleware that checks a single admin email from config, with proper JSON error responses for API endpoints.
- **A separate analytics database** that can be independently created, queried, or destroyed without touching operational data.
- **Datomic queries across two databases** for user stats, signup funnel metrics, and magic link analytics including time-to-click.
- **JVM memory monitoring** with used/free/total/max stats and percentage indicators.
- **A stat grid with live polling** that updates every 20 seconds without a page reload.
- **CSS animated counters** using `@property`, CSS `counter()`, and transitions -- no JavaScript animation code.
- **Directional change indicators** (arrows and color changes) that show whether a stat went up or down.

The entire feature is about 250 lines of Clojure and 70 lines of CSS/JavaScript. It gives you real-time visibility into your application with minimal complexity. No monitoring service to pay for, no dashboard framework to learn, no JavaScript build pipeline to maintain. Just your server, your database, and the browser's built-in capabilities.
