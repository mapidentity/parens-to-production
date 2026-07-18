(ns myapp.web.handler
  "Ring request handlers.
  Each handler receives a Ring request map (with :locale from middleware, and
  :user-eid/:user-email on authenticated routes) and returns a Ring response.
  Orchestrates auth, the recipe domain, and the view layer."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [datomic.api :as d]
    [myapp.admin.core :as admin]
    [myapp.admin.views :as admin-views]
    [myapp.analytics.db :as analytics]
    [myapp.auth.core :as auth]
    [myapp.auth.email :as email]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.i18n :refer [t]]
    [myapp.recipe.core :as recipe]
    [myapp.time :as time]
    [myapp.web.ratelimit :as ratelimit]
    [myapp.web.views :as views]
    [ring.util.response :as response])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn json-response
  "Ring response with JSON content-type, no-store caching, and serialized body.
  Status defaults to 200. Extra headers are merged in (can override defaults)."
  [data &
   {:keys [status headers]
    :or {status 200}}]
  {:status status
   :headers (merge
              {"Content-Type" "application/json"
               "Cache-Control" "no-store"}
              headers)
   :body (if (string? data) data (json/write-str data))})

(defn- html
  "Ring HTML response (200) from a Hiccup-rendered string."
  [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=UTF-8"}
   :body (str body)})

(def ^:private immutable-cache
  "Cache-Control for pages addressed by (recipe id, Datomic basis-t).
  Such a page is a pure function of those two values, so its bytes can never
  change and the browser may cache them indefinitely.
  `private` (not `public`) because the rendered chrome embeds the signed-in user's
  nav, and `wrap-no-cache-authenticated` additionally forces no-store for
  authenticated requests — so in practice this sticks for anonymous viewers,
  exactly who benefits from prerender, back/forward, and revisits."
  "private, max-age=31536000, immutable")

(defn- html-immutable
  "Like `html`, but marks the response hard-cacheable (see `immutable-cache`).
  Use only for point-in-time and diff pages, which are immutable by basis-t."
  [body]
  (-> (html body)
      (assoc-in [:headers "Cache-Control"] immutable-cache)))

(defn csp-report
  "Receive a browser CSP violation report and log it.
  Public + unauthenticated, so in production you would
   sample/rate-limit this (or point report-to at a managed
  collector) — an open report sink can be spammed."
  [request]
  (try
    (when-let [body (:body request)]
      (log/warn "CSP violation" {:report (slurp body)}))
    (catch Exception _ nil))
  {:status 204
   :headers {}})

(defn- path-uuid
  "Parse the `:id` path param as a UUID, or nil."
  [request]
  (try
    (UUID/fromString (get-in request [:path-params :id]))
    (catch Exception _ nil)))

(defn- not-found
  "A 404 HTML response."
  [request]
  (let [locale (:locale request)]
    {:status 404
     :headers {"Content-Type" "text/html; charset=UTF-8"}
     :body (str (views/error-page locale (t locale :error/not-found)))}))

;; ---------------------------------------------------------------------------
;; Public + auth
;; ---------------------------------------------------------------------------

(defn home
  "Landing page handler. Redirects authenticated users to the dashboard.

  Belt-and-suspenders against stale sessions: a session cookie carrying an email
  for a deleted user must NOT redirect to /dashboard, because wrap-auth would then
  redirect back here — an infinite loop. `wrap-current-user` (which wraps this
  route) resolves the session user once and assocs `:user-eid` only when the user
  still exists, so we redirect on that; a stale cookie (email present but no
  `:user-eid`) renders the landing page and clears the session."
  [request]
  (cond
    (:user-eid request) (response/redirect "/dashboard")
    (get-in request [:session :user-email])
    (-> (html (views/home-page (:locale request)))
        (assoc :session nil))
    :else (html (views/home-page (:locale request)))))

(def ^:private tagline-keys
  (mapv #(keyword "home" (str "tagline-" %)) (range 1 7)))

(defn tagline
  "GET /partials/tagline — one random landing tagline as text/plain.
  Keeps the landing page itself deterministic (and so cacheable / prerenderable):
  the page server-renders a fixed default tagline, and the `tagline` island swaps
  in a random one after load. no-store so each fetch can rotate; the body is plain
  text, set via textContent on the client, so there is nothing to escape."
  [request]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=UTF-8"
             "Cache-Control" "no-store"}
   :body (t (:locale request) (rand-nth tagline-keys))})

(defn- normalize-email
  "Canonicalize an email for storage and comparison: trim and lower-case.

  `:user/email` is `:db.unique/identity`, and the admin gate compares
  lower-cased, so `Foo@x.com` and `foo@x.com` must resolve to one identity or
  we would split accounts (and risk locking the admin out). Returns nil for
  anything without a plausible `local@domain` shape."
  [raw]
  (when (string? raw)
    (let [e (str/lower-case (str/trim raw))]
      (when (re-matches #"[^@\s]+@[^@\s]+\.[^@\s]+" e) e))))

;; Coarse abuse limits for the unauthenticated send path: each sends an email
;; to an attacker-chosen address, so an unthrottled endpoint is a mail-bombing
;; / SMTP-abuse vector. Per-email blunts bombing one inbox; per-IP blunts
;; spraying many addresses from one source. Tune to your sending reputation.
(def ^:private ml-window-ms
  (* 15 60 1000))
(def ^:private ml-per-email
  3)
(def ^:private ml-per-ip
  10)

(defn request-magic-link
  "Handle a magic-link request — send the email + record the nonce, then redirect (PRG).

  The nonce is the one-shot key: we record it here, alongside email and
  request-time, so verify can CAS-stamp the matching analytics entry.

  Always redirects to the same confirmation page regardless of outcome
  (unknown address, malformed input, or rate-limited), so the endpoint never
  reveals whether an address is registered or whether a send actually happened."
  [request]
  (let [raw-email (get-in request [:params :email])
        email (normalize-email raw-email)
        ip (or (:remote-addr request) "?")
        locale (:locale request)
        ;; Check the per-IP limit even on invalid input, so malformed-email
        ;; spam still counts against the source.
        ip-ok? (ratelimit/allow? (str "ml-ip:" ip) ml-per-ip ml-window-ms)
        send?
        (and email ip-ok? (ratelimit/allow? (str "ml-email:" email) ml-per-email ml-window-ms))]
    (when send?
      (let [signing-key (config/get-config :signing-key)
            base-url (config/get-config :base-url)
            {:keys [token nonce]} (auth/create-magic-link-token signing-key email)]
        (email/send-magic-link! locale email token base-url)
        (analytics/record!
          [{:magic-link/email email
            :magic-link/nonce nonce
            :magic-link/requested-at (time/now)}])))
    (let [^String enc-email (or email "")]
      (response/redirect
        (str
          "/auth/sent?email="
          (java.net.URLEncoder/encode enc-email java.nio.charset.StandardCharsets/UTF_8))))))

(defn magic-link-sent
  "Show the confirmation page (GET after redirect)."
  [request]
  (html (views/magic-link-sent (:locale request) (get-in request [:params :email]))))

(defn- consume-magic-link-nonce!
  "CAS-stamp the analytics record for `nonce` as verified.

  Returns true if this call was the first to consume the nonce; false on
  replay, malformed nonce, or unknown nonce. The CAS expects
  `:magic-link/verified-at` to be currently unset; a replay finds it already
  set, the CAS fails, the transact throws, and we return false. This is the
  one-shot anti-replay primitive."
  [^UUID nonce]
  (try
    (let [conn (analytics/get-connection)
          eid (d/q '[:find ?e . :in $ ?n :where [?e :magic-link/nonce ?n]] (d/db conn) nonce)]
      (when eid
        @(d/transact conn [[:db.fn/cas eid :magic-link/verified-at nil (time/now-date)]])
        true))
    (catch Exception _e false)))

(defn verify-magic-link
  "Verify the magic-link token and create the user-and-session.

  Three gates must pass: (1) HMAC + expiration via `auth/verify-token`;
  (2) one-shot consumption of the token's nonce; (3) user-entity creation if
  needed. All-or-nothing — any gate failing produces the same generic error,
  never revealing which gate failed."
  [request]
  (let [token (get-in request [:params :token])
        signing-key (config/get-config :signing-key)
        locale (:locale request)
        token-data (auth/verify-token signing-key token)
        nonce-str (:nonce token-data)
        nonce-uuid (when nonce-str
                     (try
                       (UUID/fromString nonce-str)
                       (catch IllegalArgumentException _ nil)))]
    (if (and token-data nonce-uuid (consume-magic-link-nonce! nonce-uuid))
      (let [email (:email token-data)
            conn (db/get-connection)]
        (auth/get-or-create-user! conn email)
        (-> (response/redirect "/dashboard")
            (assoc :session {:user-email email})))
      {:status 400
       :headers {"Content-Type" "text/html; charset=UTF-8"}
       :body (str (views/error-page locale (t locale :error/invalid-magic-link)))})))

(defn terms-welcome
  "Show the terms acceptance page (requires authentication)."
  [request]
  (if (:user-eid request) (html (views/welcome-page (:locale request))) (response/redirect "/")))

(defn accept-terms
  "Stamp `:user/terms-accepted-at` on the authenticated user, then continue."
  [request]
  (let [conn (db/get-connection)
        user-eid (:user-eid request)]
    @(db/transact* conn
       [{:db/id user-eid
         :user/terms-accepted-at (time/now)}])
    (response/redirect "/dashboard")))

(defn logout
  "Clear the session and return to the landing page."
  [_request]
  (-> (response/redirect "/")
      (assoc :session nil)))

;; ---------------------------------------------------------------------------
;; Dashboard
;; ---------------------------------------------------------------------------

(defn dashboard
  "Signed-in home: the user's recipes, and what happened while away.
  The feed — forks of their recipes, upstream edits to originals they
  forked — is read from the transaction log via d/since. After it is
  computed the cursor advances, so the panel is 'since your previous
  visit' by construction: shown once, then folded into history."
  [request]
  (let [conn (db/get-connection)
        db (d/db conn)
        user-eid (:user-eid request)
        recipes (recipe/recipes-by-user db user-eid)
        seen-at (:user/activity-seen-at (db/pull* db [:user/activity-seen-at] user-eid))
        items (recipe/activity db user-eid seen-at)]
    (auth/mark-activity-seen! conn user-eid)
    (html
      (views/dashboard (:locale request) (:user-email request) (:admin? request) recipes items))))

;; ---------------------------------------------------------------------------
;; Recipes
;; ---------------------------------------------------------------------------

(defn recipes-index
  "GET /recipes — public browse list."
  [request]
  (let [db (d/db (db/get-connection))]
    (html
      (views/recipes-index
        (:locale request)
        (:user-email request)
        (:admin? request)
        (recipe/all-recipes db)))))

(defn search-page
  "GET /search?q= — public fulltext search over recipe titles.
  A plain GET: the query lives in the URL, so results are addressable,
  shareable, and bookmarkable by construction. nil results = no query
  yet; [] = a query with no hits — the view renders them differently."
  [request]
  (let [q (str (or (get-in request [:params :q]) ""))
        results (when-not (str/blank? q) (recipe/search (d/db (db/get-connection)) q))]
    (html (views/search-page (:locale request) (:user-email request) (:admin? request) q results))))

(defn recipe-show
  "GET /recipes/:id — a recipe at its current version, with lineage and forks."
  [request]
  (let [db (d/db (db/get-connection))
        id (path-uuid request)
        r (recipe/recipe-by-id db id)]
    (if r
      (html
        (views/recipe-detail
          (:locale request)
          (:user-email request)
          (:admin? request)
          r
          {:owner? (recipe/owned-by? r (:user-eid request))
           :lineage (recipe/lineage db id)
           :forks (recipe/forks db id)
           :version-count (count (recipe/version-history db id))}))
      (not-found request))))

(defn recipe-new-form
  "GET /recipes/new — blank create form (auth + terms)."
  [request]
  (html (views/recipe-form (:locale request) (:user-email request) (:admin? request) nil)))

(defn- recipe-params
  "The recipe form fields exactly as submitted: raw strings, no defaults.
  Coercion and validation are `recipe/conform`'s job; keeping the raw map intact is what lets an invalid submission re-render the form
  with precisely what the user typed."
  [request]
  {:title (get-in request [:params :title])
   :description (get-in request [:params :description])
   :servings (get-in request [:params :servings])
   :ingredients (get-in request [:params :ingredients])
   :steps (get-in request [:params :steps])
   :note (get-in request [:params :note])})

(defn- invalid-form
  "Re-render the recipe form at 422 with field errors + submitted values.
  The dispatcher morphs this over the live form (typed input and focus
  survive — dispatcher.js's 4xx-with-HTML rule); without JavaScript it is
  the same page as a full response. The 422 keeps the contract honest for
  machines: this POST changed nothing."
  [request recipe raw errors]
  (-> (html (views/recipe-form (:locale request)
                               (:user-email request)
                               (:admin? request)
                               recipe
                               {:errors errors
                                :submitted raw}))
      (assoc :status 422)))

(defn recipe-create
  "POST /recipes/new — create a recipe owned by the current user.
  Invalid input re-renders the form at 422; only conformed values reach the
  domain."
  [request]
  (let [raw (recipe-params request)
        {:keys [values errors]} (recipe/conform raw)]
    (if errors
      (invalid-form request nil raw errors)
      (let [id (recipe/create! (db/get-connection) (:user-eid request) values)]
        (response/redirect (str "/recipes/" id))))))

(defn recipe-edit-form
  "GET /recipes/:id/edit — prefilled edit form (owner only)."
  [request]
  (let [db (d/db (db/get-connection))
        id (path-uuid request)
        r (recipe/recipe-by-id db id)]
    (if (and r (recipe/owned-by? r (:user-eid request)))
      (html (views/recipe-form (:locale request) (:user-email request) (:admin? request) r))
      (not-found request))))

(defn recipe-update
  "POST /recipes/:id/edit — apply an edit (owner only).
  Creates a new version in Datomic history. Invalid input re-renders the
  form at 422 — against the stored recipe (so the heading and action URL
  stay right) with the submitted values painted on top."
  [request]
  (let [id (path-uuid request)
        raw (recipe-params request)
        {:keys [values errors]} (recipe/conform raw)]
    (if errors
      (let [r (recipe/recipe-by-id (d/db (db/get-connection)) id)]
        (if (and r (recipe/owned-by? r (:user-eid request)))
          (invalid-form request r raw errors)
          (not-found request)))
      (let [{:keys [title description servings ingredients steps note]} values
            ok? (recipe/update!
                  (db/get-connection)
                  (:user-eid request)
                  id
                  {:recipe/title title
                   :recipe/description description
                   :recipe/servings servings
                   :recipe/ingredients ingredients
                   :recipe/steps steps}
                  note)]
        (if ok? (response/redirect (str "/recipes/" id)) (not-found request))))))

(defn recipe-preview
  "POST /recipes/new/preview and /recipes/:id/preview — the preview pane.
  Renders the shared recipe views against a d/with speculative db;
  transacts nothing. Always 200 — a preview is not a mutation attempt, and
  until the input conforms the pane simply shows its waiting state.
  no-store: every keystroke is a new hypothetical."
  [request]
  (let [id (path-uuid request)
        {:keys [values errors]} (recipe/conform (recipe-params request))
        pulled (when-not errors
                 (recipe/preview (d/db (db/get-connection)) (:user-eid request) id values))]
    (-> (html (views/recipe-preview-pane (:locale request) pulled))
        (assoc-in [:headers "Cache-Control"] "no-store"))))

(defn recipe-fork
  "POST /recipes/:id/fork — fork any recipe into one owned by the current user."
  [request]
  (let [src-id (path-uuid request)
        new-id (recipe/fork! (db/get-connection) (:user-eid request) src-id)]
    (if new-id (response/redirect (str "/recipes/" new-id)) (not-found request))))

(defn recipe-delete
  "POST /recipes/:id/delete — retract a recipe (owner only)."
  [request]
  (recipe/delete! (db/get-connection) (:user-eid request) (path-uuid request))
  (response/redirect "/dashboard"))

(defn- parse-uuid*
  "Parse an arbitrary string as a UUID, or nil.
  (Distinct from `path-uuid`,
  which reads the `:id` PATH param; this reads a body/query field.)"
  [s]
  (try
    (UUID/fromString s)
    (catch Exception _ nil)))

(defn recipe-reorder
  "POST /recipes/reorder — persist the owner's dashboard order, then PRG-redirect to /dashboard.
  Two request shapes converge here:
    - drag-and-drop sends `ids` (comma-separated UUIDs) → a full explicit order;
    - the no-JS up/down buttons send `id` + `dir` (up|down) → a single-step move.
  The dispatcher enhances both into a morph (animated by the View Transition);
  without JS the 302 is an ordinary full navigation. Either way the server is the
  source of truth and the dashboard re-renders in the new order."
  [request]
  (let [conn (db/get-connection)
        user-eid (:user-eid request)
        {:keys [ids id dir]} (:params request)
        move-id (parse-uuid* id)
        dir (some-> dir
                    keyword)]
    (cond
      (seq ids)
      (recipe/reorder! conn user-eid (keep parse-uuid* (str/split ids #",")))

      (and move-id (#{:up :down} dir))
      (recipe/move! conn user-eid move-id dir))
    (response/redirect "/dashboard")))

(defn recipe-history
  "GET /recipes/:id/history — the version timeline (public)."
  [request]
  (let [db (d/db (db/get-connection))
        id (path-uuid request)
        r (recipe/recipe-by-id db id)
        versions (recipe/version-history db id)]
    (if r
      (html
        (views/recipe-history (:locale request) (:user-email request) (:admin? request) r versions))
      (not-found request))))

(defn recipe-version
  "GET /recipes/:id/at/:t — read-only point-in-time view as of basis-t `:t`."
  [request]
  (let [db (d/db (db/get-connection))
        id (path-uuid request)
        basis-t (parse-long (str (get-in request [:path-params :t])))
        r (when basis-t (recipe/version-as-of db id basis-t))]
    (if r
      (html-immutable
        (views/recipe-version
          (:locale request)
          (:user-email request)
          (:admin? request)
          r
          (:recipe/updated-at r)))
      (not-found request))))

(defn recipe-diff
  "GET /recipes/:id/diff?from=<t>&to=<t> — diff between two versions (public)."
  [request]
  (let [db (d/db (db/get-connection))
        id (path-uuid request)
        from-t (parse-long (str (get-in request [:params :from])))
        to-t (parse-long (str (get-in request [:params :to])))
        old-r (when from-t (recipe/version-as-of db id from-t))
        new-r (when to-t (recipe/version-as-of db id to-t))]
    (if (and old-r new-r)
      (html-immutable
        (views/recipe-diff
          (:locale request)
          (:user-email request)
          (:admin? request)
          new-r
          (:recipe/updated-at old-r)
          (:recipe/updated-at new-r)
          (recipe/diff old-r new-r)))
      (not-found request))))

;; ---------------------------------------------------------------------------
;; Admin (ch13) — the signup funnel, unchanged from the book
;; ---------------------------------------------------------------------------

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
