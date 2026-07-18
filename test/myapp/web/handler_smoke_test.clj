(ns myapp.web.handler-smoke-test
  "Smoke tests that every handler page renders without throwing.
  Catch missing translations, nil pointers, query errors, and render crashes."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.test-helpers :as h]
    [myapp.time :as time]
    [myapp.web.handler :as handler]
    [myapp.web.routes :as routes])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-db h/with-test-analytics-db h/with-test-config)

(def ^:private admin-email
  "admin@test.myapp.lan")

(defn- mk-user!
  "Create a user (with terms accepted) and return its eid."
  [email]
  @(db/transact* h/*conn*
     [{:db/id "u"
       :user/id (UUID/randomUUID)
       :user/email email
       :user/active? true
       :user/created-at (time/now)
       :user/terms-accepted-at (time/now)}])
  (d/q '[:find ?e . :in $ ?m :where [?e :user/email ?m]] (d/db h/*conn*) email))

(defn- ok?
  "True when `response` is a 200 with a non-empty body."
  [response]
  (and (= 200 (:status response)) (string? (:body response)) (pos? (count (:body response)))))

(deftest public-pages-render
  (testing "landing page (no session)" (is (ok? (handler/home (h/request :get "/")))))
  (testing "browse list when empty" (is (ok? (handler/recipes-index (h/request :get "/recipes"))))))

(deftest recipe-pages-render
  (let [u (mk-user! "cook@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Risotto"
              :servings 4
              :ingredients "rice\nstock"
              :steps "stir\nstir more"})
        _ (recipe/update! h/*conn* u id {:recipe/ingredients "rice\nstock\nwine"})
        req (fn [uri]
              (assoc (h/request :get uri)
                :path-params {:id (str id)}))]
    (testing "detail page renders with lineage/forks"
      (let [resp (handler/recipe-show (req (str "/recipes/" id)))]
        (is (ok? resp))
        (is (str/includes? (:body resp) "Risotto"))))
    (testing "history page renders"
      (is (ok? (handler/recipe-history (req (str "/recipes/" id "/history"))))))
    (testing "point-in-time + diff render, immutably cacheable by basis-t"
      (let [versions (recipe/version-history (d/db h/*conn*) id)
            t0 (:t (first versions))
            t1 (:t (last versions))
            immutable "private, max-age=31536000, immutable"
            vresp (handler/recipe-version
                    (assoc (h/request :get "x")
                      :path-params {:id (str id)
                                    :t (str t0)}))
            dresp (handler/recipe-diff
                    (assoc (h/request :get "x"
                                      :params {:from (str t0)
                                               :to (str t1)})
                      :path-params {:id (str id)}))]
        (is (ok? vresp))
        (is
          (= immutable (get-in vresp [:headers "Cache-Control"]))
          "point-in-time page is immutable by (id, basis-t)")
        (is (ok? dresp))
        (is
          (= immutable (get-in dresp [:headers "Cache-Control"]))
          "diff page is immutable by (id, from-t, to-t)")
        ;; the CURRENT read is mutable — it must NOT carry the immutable header
        (is
          (nil?
            (get-in (handler/recipe-show (req (str "/recipes/" id))) [:headers "Cache-Control"]))
          "current recipe read is not immutably cached")))
    (testing "unknown recipe id 404s"
      (is
        (=
          404
          (:status
            (handler/recipe-show
              (assoc (h/request :get "x")
                :path-params {:id (str (UUID/randomUUID))}))))))))

(deftest authed-pages-render
  (let [_ (mk-user! "cook@x.lan")]
    (testing "dashboard"
      (is (ok? (handler/dashboard (h/auth-request :get "/dashboard" "cook@x.lan")))))
    (testing "new recipe form"
      (is (ok? (handler/recipe-new-form (h/auth-request :get "/recipes/new" "cook@x.lan")))))
    (testing "terms welcome"
      (is (ok? (handler/terms-welcome (h/auth-request :get "/terms/welcome" "cook@x.lan")))))))

(deftest admin-dashboard-renders
  (mk-user! admin-email)
  (is (ok? (handler/admin-dashboard (h/auth-request :get "/admin" admin-email)))))

(deftest create-then-redirects
  (let [u (mk-user! "cook@x.lan")
        resp (handler/recipe-create
               (assoc (h/auth-request :post
                                      "/recipes/new" "cook@x.lan"
                                      :params {:title "New One"
                                               :servings "2"
                                               :ingredients "a\nb"
                                               :steps "x"})
                 :user-eid u))]
    (is (= 302 (:status resp)) "PRG redirect after create")
    (is (str/starts-with? (get-in resp [:headers "Location"]) "/recipes/"))))

(deftest authenticated-responses-are-never-cached
  (testing "wrap-no-cache-authenticated forces no-store, overriding even an immutable header"
    ;; A point-in-time/diff page is immutable by basis-t, but its rendered chrome
    ;; embeds the signed-in user's nav — so a SIGNED-IN response must never be
    ;; cached (else bfcache shows it after logout). This is what makes the
    ;; `private, immutable` header on those pages safe: it only ever sticks for
    ;; anonymous viewers.
    (let [immutable {:status 200
                     :headers {"Content-Type" "text/html; charset=UTF-8"
                               "Cache-Control" "private, max-age=31536000, immutable"}
                     :body "x"}
          wrapped (routes/wrap-no-cache-authenticated (constantly immutable))]
      (is
        (=
          "private, max-age=31536000, immutable"
          (get-in (wrapped (h/request :get "/recipes/x/at/1")) [:headers "Cache-Control"]))
        "anonymous: the handler's immutable header is preserved")
      (is
        (=
          "no-store"
          (get-in
            (wrapped (assoc-in (h/request :get "/recipes/x/at/1") [:session :user-email] "a@b.lan"))
            [:headers "Cache-Control"]))
        "authenticated: no-store overrides the immutable header"))))

(deftest invalid-recipe-create-rerenders-at-422
  (let [u (mk-user! "typo@x.lan")
        resp (handler/recipe-create
               (assoc (h/auth-request :post
                                      "/recipes/new" "typo@x.lan"
                                      :locale :en
                                      :params {:title "   " ; passes HTML `required`, fails conform
                                               :servings "0"
                                               :ingredients "kept exactly as typed"
                                               :steps ""})
                 :user-eid u))]
    (is (= 422 (:status resp)) "nothing was created, and the status says so")
    (is (str/includes? (:body resp) "Give the recipe a title.") "title error rendered")
    (is (str/includes? (:body resp) "between 1 and 100") "servings error rendered")
    (is
      (str/includes? (:body resp) "kept exactly as typed")
      "submitted values survive the round trip")
    (is (str/includes? (:body resp) "aria-invalid") "the failure is announced, not just painted")
    (is (zero? (recipe/total-recipes (d/db h/*conn*))) "the database is untouched")))

(deftest invalid-recipe-update-rerenders-at-422
  (let [u (mk-user! "editor@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Stew"
              :servings 4})
        resp (handler/recipe-update
               (assoc (h/auth-request :post
                                      (str "/recipes/" id "/edit")
                                      "editor@x.lan"
                                      :locale :en
                                      :params {:title ""
                                               :servings "4"})
                 :user-eid u
                 :path-params {:id (str id)}))]
    (is (= 422 (:status resp)))
    (is (str/includes? (:body resp) "Give the recipe a title."))
    (is
      (= "Stew" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id)))
      "the stored recipe is unchanged")))

(deftest preview-endpoint-renders-speculatively
  (let [u (mk-user! "previewer@x.lan")
        resp (handler/recipe-preview
               (assoc (h/auth-request :post
                                      "/recipes/new/preview" "previewer@x.lan"
                                      :locale :en
                                      :params {:title "Draft Cake"
                                               :servings "8"
                                               :description "Very **fluffy** indeed."})
                 :user-eid u))]
    (is (ok? resp))
    (is (str/includes? (:body resp) "Draft Cake"))
    (is
      (str/includes? (:body resp) "<strong>fluffy</strong>")
      "the preview runs the real markdown pipeline")
    (is (= "no-store" (get-in resp [:headers "Cache-Control"])))
    (is (zero? (recipe/total-recipes (d/db h/*conn*))) "nothing was created"))
  (testing "unconformable input gets the waiting state, still 200"
    (let [u2 (mk-user! "previewer2@x.lan")
          resp (handler/recipe-preview
                 (assoc (h/auth-request :post
                                        "/recipes/new/preview" "previewer2@x.lan"
                                        :locale :en
                                        :params {:title ""
                                                 :servings "0"})
                   :user-eid u2))]
      (is (ok? resp))
      (is (str/includes? (:body resp) "appears once the recipe")))))

(deftest search-page-renders
  (let [u (mk-user! "searcher@x.lan")]
    (recipe/create!
      h/*conn*
      u
      {:title "Findable Focaccia"
       :servings 1})
    (testing "no query: just the form" (is (ok? (handler/search-page (h/request :get "/search")))))
    (testing "query with a hit renders the card"
      (let [resp (handler/search-page (h/request :get "/search" :params {:q "focaccia"}))]
        (is (ok? resp))
        (is (str/includes? (:body resp) "Findable Focaccia"))))
    (testing "query without hits says so"
      (let [resp (handler/search-page (h/request :get "/search" :locale :en :params {:q "zzz"}))]
        (is (str/includes? (:body resp) "No recipes match"))))))

(deftest dashboard-activity-shows-once-then-advances
  (let [alice (mk-user! "alice5@x.lan")
        bob (mk-user! "bob5@x.lan")
        orig (recipe/create!
               h/*conn*
               alice
               {:title "Seen Once Stew"
                :servings 2})
        _ (recipe/fork! h/*conn* bob orig)
        req #(assoc (h/auth-request :get "/dashboard" "alice5@x.lan" :locale :en)
               :user-eid alice)
        first-view (handler/dashboard (req))
        second-view (handler/dashboard (req))]
    (is (str/includes? (:body first-view) "While you were away") "the panel leads")
    (is (str/includes? (:body first-view) "forked your recipe"))
    (is
      (not (str/includes? (:body second-view) "While you were away"))
      "the cursor advanced: shown once, then folded into history")))

(deftest machine-legibility
  (let [u (mk-user! "seo@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Legible Lasagna"
              :servings 6
              :description "Layered **properly**."
              :ingredients "pasta\nragu"
              :steps "layer\nbake"})
        req (fn [uri]
              (assoc (h/request :get uri)
                :path-params {:id (str id)}))]
    (testing "the recipe page introduces itself to machines"
      (let [body (:body (handler/recipe-show (req (str "/recipes/" id))))]
        (is (str/includes? body "<title>Legible Lasagna — MyApp</title>"))
        (is
          (str/includes?
            body
            (str "href=\"https://test.myapp.lan/recipes/" id "\" rel=\"canonical\"")))
        (is (str/includes? body "property=\"og:title\""))
        (is (str/includes? body "application/ld+json"))
        (is (str/includes? body "schema.org"))
        (is (str/includes? body "HowToStep"))))
    (testing "historical pages defer to the current page and stay unindexed"
      (let [versions (recipe/version-history (d/db h/*conn*) id)
            body (:body
                   (handler/recipe-version
                     (assoc (h/request :get "x")
                       :path-params {:id (str id)
                                     :t (str (:t (first versions)))})))]
        (is (str/includes? body "content=\"noindex\" name=\"robots\""))
        (is
          (str/includes?
            body
            (str "href=\"https://test.myapp.lan/recipes/" id "\" rel=\"canonical\"")))))
    (testing "the sitemap is a database read"
      (let [body (:body (handler/sitemap (h/request :get "/sitemap.xml")))]
        (is (str/includes? body (str "/recipes/" id)))
        (is (str/includes? body "<lastmod>"))))
    (testing "robots.txt names the sitemap and fences the signed-in rooms"
      (let [body (:body (handler/robots (h/request :get "/robots.txt")))]
        (is (str/includes? body "Sitemap: https://test.myapp.lan/sitemap.xml"))
        (is (str/includes? body "Disallow: /dashboard"))))))

(deftest health-and-404
  (testing "/health proves both database connections, not just the process"
    (let [resp (routes/app
                 {:request-method :get
                  :uri "/health"
                  :headers {}})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "\"status\":\"ok\""))
      (is (str/includes? (:body resp) "\"basis-t\":") "operational db was read")
      (is (str/includes? (:body resp) "\"analytics-basis-t\":") "analytics db was read")))
  (testing "an unrouted path gets the branded 404, inside the full envelope"
    (let [resp (routes/app
                 {:request-method :get
                  :uri "/no-such-page"
                  :headers {}})]
      (is (= 404 (:status resp)))
      (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/html"))
      (is (str/includes? (:body resp) "Not found.") "the real error view, negotiated locale")
      (is
        (some? (get-in resp [:headers "Content-Security-Policy"]))
        "the failure path wears the CSP like any other page"))))

(deftest panic-belt-catches-stack-failures
  ;; wrap-errors owns handler exceptions; the belt exists for the stack
  ;; itself. Simulate a middleware explosion and assert the response is
  ;; still a served 500, not a dropped connection.
  (let [resp ((routes/wrap-panic (fn [_] (throw (RuntimeException. "session store exploded"))))
               {:request-method :get
                :uri "/x"})]
    (is (= 500 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/plain"))
    (is (= "Internal server error." (:body resp)))))
