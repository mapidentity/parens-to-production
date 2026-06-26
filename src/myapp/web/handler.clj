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

(defn- admin?
  "True when the request's session email matches the configured admin email."
  [request]
  (let [user-email (:user-email request)
        admin-email (config/get-config :admin-email)]
    (boolean
      (and user-email admin-email (= (str/lower-case user-email) (str/lower-case admin-email))))))

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

  Belt-and-suspenders against stale sessions: a session cookie carrying an
  email for a deleted user must NOT redirect to /dashboard, because wrap-auth
  would then redirect back here — an infinite loop. `/` lives outside wrap-auth,
  so we verify the user exists before redirecting and clear the session
  otherwise."
  [request]
  (let [email (get-in request [:session :user-email])
        user-exists? (and email (auth/find-user-by-email (d/db (db/get-connection)) email))]
    (cond
      user-exists? (response/redirect "/dashboard")
      email (-> (html (views/home-page (:locale request)))
                (assoc :session nil))
      :else (html (views/home-page (:locale request))))))

(defn request-magic-link
  "Handle a magic-link request — send the email + record the nonce, then redirect (PRG).

  The nonce is the one-shot key: we record it here, alongside email and
  request-time, so verify can CAS-stamp the matching analytics entry."
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
      (str
        "/auth/sent?email="
        (java.net.URLEncoder/encode ^String email java.nio.charset.StandardCharsets/UTF_8)))))

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
  "Signed-in home: the user's own recipes."
  [request]
  (let [db (d/db (db/get-connection))
        recipes (recipe/recipes-by-user db (:user-eid request))]
    (html (views/dashboard (:locale request) (:user-email request) (admin? request) recipes))))

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
        (admin? request)
        (recipe/all-recipes db)))))

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
          (admin? request)
          r
          {:owner? (recipe/owned-by? r (:user-eid request))
           :lineage (recipe/lineage db id)
           :forks (recipe/forks db id)
           :version-count (count (recipe/version-history db id))}))
      (not-found request))))

(defn recipe-new-form
  "GET /recipes/new — blank create form (auth + terms)."
  [request]
  (html (views/recipe-form (:locale request) (:user-email request) (admin? request) nil)))

(defn- recipe-params
  "Extract the recipe content fields from request params."
  [request]
  {:title (str/trim (or (get-in request [:params :title]) ""))
   :description (or (get-in request [:params :description]) "")
   :servings (or (parse-long (str (get-in request [:params :servings]))) 1)
   :ingredients (or (get-in request [:params :ingredients]) "")
   :steps (or (get-in request [:params :steps]) "")})

(defn recipe-create
  "POST /recipes/new — create a recipe owned by the current user."
  [request]
  (let [id (recipe/create! (db/get-connection) (:user-eid request) (recipe-params request))]
    (response/redirect (str "/recipes/" id))))

(defn recipe-edit-form
  "GET /recipes/:id/edit — prefilled edit form (owner only)."
  [request]
  (let [db (d/db (db/get-connection))
        id (path-uuid request)
        r (recipe/recipe-by-id db id)]
    (if (and r (recipe/owned-by? r (:user-eid request)))
      (html (views/recipe-form (:locale request) (:user-email request) (admin? request) r))
      (not-found request))))

(defn recipe-update
  "POST /recipes/:id/edit — apply an edit (owner only).
  Creates a new version in Datomic history."
  [request]
  (let [id (path-uuid request)
        {:keys [title description servings ingredients steps]} (recipe-params request)
        ok? (recipe/update!
              (db/get-connection)
              (:user-eid request)
              id
              {:recipe/title title
               :recipe/description description
               :recipe/servings servings
               :recipe/ingredients ingredients
               :recipe/steps steps})]
    (if ok? (response/redirect (str "/recipes/" id)) (not-found request))))

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
        (views/recipe-history (:locale request) (:user-email request) (admin? request) r versions))
      (not-found request))))

(defn recipe-version
  "GET /recipes/:id/at/:t — read-only point-in-time view as of basis-t `:t`."
  [request]
  (let [db (d/db (db/get-connection))
        id (path-uuid request)
        t (parse-long (str (get-in request [:path-params :t])))
        r (when t (recipe/version-as-of db id t))]
    (if r
      (html
        (views/recipe-version
          (:locale request)
          (:user-email request)
          (admin? request)
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
        old (when from-t (recipe/version-as-of db id from-t))
        new (when to-t (recipe/version-as-of db id to-t))]
    (if (and old new)
      (html
        (views/recipe-diff
          (:locale request)
          (:user-email request)
          (admin? request)
          new
          (:recipe/updated-at old)
          (:recipe/updated-at new)
          (recipe/diff old new)))
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
