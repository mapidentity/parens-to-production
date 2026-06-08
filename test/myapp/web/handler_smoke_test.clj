(ns myapp.web.handler-smoke-test
  "Smoke tests for the handlers: every page renders without throwing —
  catches missing translations, nil pointers, query errors, and render crashes."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.test-helpers :as h]
    [myapp.time :as time]
    [myapp.web.handler :as handler])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-db h/with-test-analytics-db h/with-test-config)

(def ^:private admin-email "admin@test.myapp.lan")

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
  (testing "landing page (no session)"
    (is (ok? (handler/home (h/request :get "/")))))
  (testing "browse list when empty"
    (is (ok? (handler/recipes-index (h/request :get "/recipes"))))))

(deftest recipe-pages-render
  (let [u (mk-user! "cook@x.lan")
        id (recipe/create! h/*conn* u {:title "Risotto" :servings 4
                                       :ingredients "rice\nstock" :steps "stir\nstir more"})
        _ (recipe/update! h/*conn* u id {:recipe/ingredients "rice\nstock\nwine"})
        req (fn [uri] (assoc (h/request :get uri) :path-params {:id (str id)}))]
    (testing "detail page renders with lineage/forks"
      (let [resp (handler/recipe-show (req (str "/recipes/" id)))]
        (is (ok? resp))
        (is (str/includes? (:body resp) "Risotto"))))
    (testing "history page renders"
      (is (ok? (handler/recipe-history (req (str "/recipes/" id "/history"))))))
    (testing "point-in-time + diff render"
      (let [versions (recipe/version-history (d/db h/*conn*) id)
            t0 (:t (first versions))
            t1 (:t (last versions))]
        (is (ok? (handler/recipe-version
                   (assoc (h/request :get "x") :path-params {:id (str id) :t (str t0)}))))
        (is (ok? (handler/recipe-diff
                   (assoc (h/request :get "x" :params {:from (str t0) :to (str t1)})
                     :path-params {:id (str id)}))))))
    (testing "unknown recipe id 404s"
      (is (= 404 (:status (handler/recipe-show
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
  (is (ok? (handler/admin-dashboard
             (h/auth-request :get "/admin" admin-email)))))

(deftest create-then-redirects
  (let [u (mk-user! "cook@x.lan")
        resp (handler/recipe-create
               (assoc (h/auth-request :post "/recipes/new"
                        "cook@x.lan"
                        :params {:title "New One" :servings "2" :ingredients "a\nb" :steps "x"})
                 :user-eid u))]
    (is (= 302 (:status resp)) "PRG redirect after create")
    (is (str/starts-with? (get-in resp [:headers "Location"]) "/recipes/"))))
