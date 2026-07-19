(ns myapp.web.handler-smoke-test
  "Smoke tests that every handler page renders without throwing.
  Catch missing translations, nil pointers, query errors, and render crashes."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.auth.email]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.recipe.proposal :as proposal]
    [myapp.test-helpers :as h]
    [myapp.time :as time]
    [myapp.web.handler :as handler]
    [myapp.web.ratelimit]
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

(deftest browse-is-keyset-paginated
  (let [u (mk-user! "cook@x.lan")]
    (dotimes [i 13] ; one more than a page (12)
      (recipe/create!
        h/*conn*
        u
        {:title (format "recipe-%02d" (inc i))
         :servings 1}))
    (let [p1 (handler/recipes-index (h/request :get "/recipes"))
          token (second (re-find #"[?&]after=([A-Za-z0-9_-]+)" (:body p1)))]
      (testing "page one shows a full page + a Next link, and holds back the overflow"
        (is (ok? p1))
        (is (str/includes? (:body p1) "recipe-01"))
        (is (str/includes? (:body p1) "recipe-12"))
        (is (not (str/includes? (:body p1) "recipe-13")) "the 13th spills to page two")
        (is (some? token) "the Next link carries an opaque cursor"))
      (testing "following that cursor (the real link) renders page two"
        (let [p2 (handler/recipes-index
                   (assoc (h/request :get "/recipes")
                     :params {:after token}))]
          (is (str/includes? (:body p2) "recipe-13") "page two shows what page one held back")
          (is (not (str/includes? (:body p2) "recipe-01")) "and not page one's items"))))))

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

(deftest recipe-photo-upload-through-handlers
  (myapp.web.ratelimit/clear!)
  (let [png (fn []
              (let [img
                    (java.awt.image.BufferedImage. 12 8 java.awt.image.BufferedImage/TYPE_INT_RGB)
                    f (java.io.File/createTempFile "img" ".png")]
                (javax.imageio.ImageIO/write img "png" f)
                f))
        u (mk-user! "cook@x.lan")
        mallory (mk-user! "mallory@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Toast"
              :servings 1})
        up-req (fn [email eid tmp]
                 (assoc (h/auth-request :post (str "/recipes/" id "/image") email)
                   :user-eid eid
                   :path-params {:id (str id)}
                   :multipart-params {"image" {:tempfile tmp}}))]
    (testing "a non-owner cannot attach a photo"
      (is (= 404 (:status (handler/recipe-image-upload (up-req "mallory@x.lan" mallory (png))))))
      (is (nil? (:recipe/image (recipe/recipe-by-id (d/db h/*conn*) id)))))
    (testing "the owner attaches a photo → 302 and the recipe gains an image"
      (let [resp (handler/recipe-image-upload (up-req "cook@x.lan" u (png)))]
        (is (= 302 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Location"]) "toast=photo-added"))
        (is (some? (:recipe/image (recipe/recipe-by-id (d/db h/*conn*) id))))))
    (testing "a non-image is rejected with a reason in the redirect"
      (let [junk (doto (java.io.File/createTempFile "junk" ".png") (spit "nope"))
            resp (handler/recipe-image-upload (up-req "cook@x.lan" u junk))]
        (is (str/includes? (get-in resp [:headers "Location"]) "photo-error=not-an-image"))))
    (testing "the owner removes the photo → the ref is gone"
      (let [resp (handler/recipe-image-delete
                   (assoc (h/auth-request :post (str "/recipes/" id "/image/delete") "cook@x.lan")
                     :user-eid u
                     :path-params {:id (str id)}))]
        (is (= 302 (:status resp)))
        (is (nil? (:recipe/image (recipe/recipe-by-id (d/db h/*conn*) id))))))))

(deftest recipe-upload-is-rate-limited
  ;; An owner flooding distinct images would otherwise fill the shared state
  ;; volume; the write path is bounded per user like every other resource.
  (myapp.web.ratelimit/clear!)
  (let [png
        (fn [i]
          (let [img
                (java.awt.image.BufferedImage. (+ 8 i) 8 java.awt.image.BufferedImage/TYPE_INT_RGB)
                f (java.io.File/createTempFile "img" ".png")]
            (javax.imageio.ImageIO/write img "png" f)
            f))
        u (mk-user! "flood@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Flood"
              :servings 1})
        attempt (fn [i]
                  (get-in
                    (handler/recipe-image-upload
                      (assoc (h/auth-request :post (str "/recipes/" id "/image") "flood@x.lan")
                        :user-eid u
                        :path-params {:id (str id)}
                        :multipart-params {"image" {:tempfile (png i)}}))
                    [:headers "Location"]))
        results (mapv attempt (range 25))]
    (testing "the first upload is accepted"
      (is (str/includes? (first results) "toast=photo-added")))
    (testing "the flood is eventually refused with a rate-limit reason"
      (is (str/includes? (last results) "photo-error=rate-limited")))))

(deftest recipe-image-derivative-endpoint
  (myapp.web.ratelimit/clear!)
  (let [png
        (fn []
          (let [img
                (java.awt.image.BufferedImage. 900 600 java.awt.image.BufferedImage/TYPE_INT_RGB)
                f (java.io.File/createTempFile "img" ".png")]
            (javax.imageio.ImageIO/write img "png" f)
            f))
        u (mk-user! "cook@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Stew"
              :servings 2})
        _ (handler/recipe-image-upload
            (assoc (h/auth-request :post (str "/recipes/" id "/image") "cook@x.lan")
              :user-eid u
              :path-params {:id (str id)}
              :multipart-params {"image" {:tempfile (png)}}))
        hex (get-in (recipe/recipe-by-id (d/db h/*conn*) id) [:recipe/image :upload/hash])
        req (fn [a b h variant]
              (assoc (h/request :get (str "/img/" a "/" b "/" h "/" variant))
                :path-params {:a a
                              :b b
                              :hash h
                              :variant variant}))]
    (testing "a hero variant is served with an image type and an immutable cache"
      (let [resp (handler/recipe-image (req (subs hex 0 2) (subs hex 2 4) hex "hero.jpg"))]
        (is (= 200 (:status resp)))
        (is (= "image/jpeg" (get-in resp [:headers "Content-Type"])))
        (is (str/includes? (get-in resp [:headers "Cache-Control"]) "immutable"))
        (is (instance? java.io.File (:body resp)))))
    (testing "an unknown variant is a 404"
      (is
        (=
          404
          (:status (handler/recipe-image (req (subs hex 0 2) (subs hex 2 4) hex "banner.jpg"))))))
    (testing "a hash with no master is a 404 (prefix matches, but nothing on disk)"
      (let [ghost (apply str (repeat 64 "b"))]
        (is (= 404 (:status (handler/recipe-image (req "bb" "bb" ghost "hero.jpg")))))))
    (testing "an /img path whose a/b do not match the hash prefix is a 404 (cannot point off-tree)"
      (is (= 404 (:status (handler/recipe-image (req "zz" "zz" hex "hero.jpg"))))))))

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

(deftest report-sinks-and-metrics
  (testing "client errors land in the log through a rate-limited sink"
    (let [resp (handler/client-error
                 {:remote-addr "10.0.0.9"
                  :body (java.io.ByteArrayInputStream.
                          (.getBytes "{\"kind\":\"error\",\"message\":\"boom\"}" "UTF-8"))})]
      (is (= 204 (:status resp)))))
  (testing "/metrics answers loopback with Prometheus text"
    (let [resp (routes/app
                 {:request-method :get
                  :uri "/metrics"
                  :remote-addr "127.0.0.1"
                  :headers {}})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "jvm_memory_heap_used_bytes"))
      (is (str/includes? (:body resp) "http_requests_total"))))
  (testing "/metrics does not exist for anyone else"
    (let [resp (routes/app
                 {:request-method :get
                  :uri "/metrics"
                  :remote-addr "203.0.113.7"
                  :headers {}})]
      (is (= 404 (:status resp))))))

(deftest client-ip-trust-boundary
  ;; The auth-DoS fix: :remote-addr is always the loopback proxy in prod,
  ;; so per-IP controls must key on the proxy-set X-Client-IP — but only
  ;; when the peer is actually the loopback proxy, or a direct attacker
  ;; forges it.
  (testing "a forwarded header from the loopback proxy IS the client"
    (let [captured (atom nil)
          h (routes/wrap-client-ip
              (fn [req]
                (reset! captured (:client-ip req))
                {:status 200}))]
      (h
        {:remote-addr "127.0.0.1"
         :headers {"x-client-ip" "203.0.113.9"}})
      (is (= "203.0.113.9" @captured) "trusted proxy's header wins")))
  (testing "a forwarded header from a NON-loopback peer is ignored (unspoofable)"
    (let [captured (atom nil)
          h (routes/wrap-client-ip
              (fn [req]
                (reset! captured (:client-ip req))
                {:status 200}))]
      (h
        {:remote-addr "198.51.100.7"
         :headers {"x-client-ip" "127.0.0.1"}})
      (is (= "198.51.100.7" @captured) "a direct hit keeps its real peer; the header cannot lie")))
  (testing "no header falls back to the peer"
    (let [captured (atom nil)
          h (routes/wrap-client-ip
              (fn [req]
                (reset! captured (:client-ip req))
                {:status 200}))]
      (h
        {:remote-addr "127.0.0.1"
         :headers {}})
      (is (= "127.0.0.1" @captured)))))

(deftest per-ip-throttle-keys-on-client-ip
  ;; The auth-DoS fix, end to end: distinct clients get distinct budgets;
  ;; one client is still capped. Before the fix every request keyed on the
  ;; proxy's 127.0.0.1, so ten sends from anyone froze login site-wide.
  (myapp.web.ratelimit/clear!)
  (with-redefs [myapp.auth.email/send-magic-link! (constantly {:error :SUCCESS})]
    (let [send! (fn [client-ip email]
                  (handler/request-magic-link
                    {:client-ip client-ip
                     :locale :en
                     :params {:email email}}))
          sent? (fn [resp] (= 302 (:status resp)))]
      (testing "eleven sends from ONE client: the 11th is throttled (10/15min)"
        (let [results (doall
                        (for [i (range 11)]
                          (send! "203.0.113.1" (str "a" i "@x.lan"))))]
          ;; all redirect (don't-reveal), but only 10 actually pass the gate;
          ;; the mailer stub lets us count via a fresh limiter below instead.
          (is (every? sent? results) "every response is the same 302, by design")))
      (testing "a DIFFERENT client is unaffected — its own fresh budget"
        (myapp.web.ratelimit/clear!)
        (dotimes [_ 10]
          (send! "203.0.113.1" "a@x.lan")) ; exhaust client 1's per-IP bucket
        (is
          (myapp.web.ratelimit/allow? "ml-ip:203.0.113.2" 10 (* 15 60 1000))
          "client 2's bucket is untouched by client 1 — no shared global bucket")
        (is
          (not (myapp.web.ratelimit/allow? "ml-ip:203.0.113.1" 10 (* 15 60 1000)))
          "client 1 is (correctly) capped")))))

(deftest stale-edit-gets-409-conflict-page
  (let [u (mk-user! "editor@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Orig"
              :servings 2
              :ingredients "a"
              :steps "b"})
        tok (str (inst-ms (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) id))))
        edit-req (fn [expected title]
                   (assoc (h/auth-request :post
                                          (str "/recipes/" id "/edit")
                                          "editor@x.lan"
                                          :locale :en
                                          :params {:title title
                                                   :servings "2"
                                                   :ingredients "a"
                                                   :steps "b"
                                                   :expected-version expected})
                     :user-eid u
                     :path-params {:id (str id)}))]
    (testing "first save with the current token succeeds (redirect)"
      (is (= 302 (:status (handler/recipe-update (edit-req tok "First"))))))
    (testing "a second save with the now-stale token → 409 conflict page, edits preserved"
      (let [resp (handler/recipe-update (edit-req tok "My unsaved edit"))]
        (is (= 409 (:status resp)))
        (is (str/includes? (:body resp) "Someone else edited this recipe") "the conflict banner")
        (is
          (str/includes? (:body resp) "My unsaved edit")
          "the user's work is preserved in the form")
        (is
          (= "First" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id)))
          "the stale save changed nothing in the database")))))

(deftest proposal-flow-through-handlers
  (let [alice (mk-user! "alice@x.lan")
        bob (mk-user! "bob@x.lan")
        orig (recipe/create!
               h/*conn*
               alice
               {:title "Carbonara"
                :servings 2
                :ingredients "pasta\neggs"
                :steps "boil"})
        fork (recipe/fork! h/*conn* bob orig)
        _ (recipe/update! h/*conn* bob fork {:recipe/ingredients "pasta\neggs\npecorino"})
        propose-req (assoc (h/auth-request :post (str "/recipes/" fork "/propose") "bob@x.lan")
                      :user-eid bob
                      :path-params {:id (str fork)})]
    (testing "the fork owner proposes → redirect to the proposal page"
      (let [resp (myapp.web.handler/proposal-propose propose-req)
            pid (last (str/split (get-in resp [:headers "Location"]) #"/"))]
        (is (= 302 (:status resp)))
        (is (uuid? (java.util.UUID/fromString pid)))
        (testing "the proposal page renders the clean-apply for the target owner"
          (let [show (myapp.web.handler/proposal-show
                       (assoc (h/auth-request :get (str "/proposals/" pid) "alice@x.lan")
                         :user-eid alice
                         :path-params {:id pid}))]
            (is (= 200 (:status show)))
            (is (str/includes? (:body show) "pecorino") "the proposed line is shown")))
        (testing "the target owner accepts → the change merges into the original"
          (let [tok (str (inst-ms (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) orig))))
                acc (myapp.web.handler/proposal-accept
                      (assoc (h/auth-request :post (str "/proposals/" pid "/accept") "alice@x.lan")
                        :user-eid alice
                        :path-params {:id pid}
                        :params {:expected-version tok}))]
            (is (= 302 (:status acc)))
            (is
              (str/includes?
                (:recipe/ingredients (recipe/recipe-by-id (d/db h/*conn*) orig))
                "pecorino")
              "merged into the original")))))))

(defn- conflicting-proposal!
  "Diverge Alice's original and Bob's fork on the SAME field, then propose.
  Returns {:alice :orig :pid} for the target-owner accept path."
  []
  (let [alice (mk-user! "alice@x.lan")
        bob (mk-user! "bob@x.lan")
        orig (recipe/create!
               h/*conn*
               alice
               {:title "Carbonara"
                :servings 2
                :ingredients "pasta\neggs"
                :steps "boil\nmix"})
        fork (recipe/fork! h/*conn* bob orig)
        _ (recipe/update! h/*conn* bob fork {:recipe/ingredients "pasta\neggs\npecorino"})
        _ (recipe/update! h/*conn* alice orig {:recipe/ingredients "pasta\neggs\nguanciale"})
        propose (myapp.web.handler/proposal-propose
                  (assoc (h/auth-request :post (str "/recipes/" fork "/propose") "bob@x.lan")
                    :user-eid bob
                    :path-params {:id (str fork)}))]
    {:alice alice
     :orig orig
     :pid (UUID/fromString (last (str/split (get-in propose [:headers "Location"]) #"/")))}))

(defn- token-for
  "The stringified optimistic-concurrency token for a recipe's current version."
  [id]
  (str (inst-ms (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) id)))))

(deftest proposal-conflict-accept-through-handlers
  ;; The regression guard for the `merge`/`mrg` typo that passed clojure.core's
  ;; `merge` where the local merge result belonged: `resolutions-from` then read
  ;; the ours/theirs radios off a function, always got {}, and every conflicting
  ;; proposal answered 409 unresolved instead of merging the chosen side.
  ;; (An unresolved 409 is unreachable from the browser — the radios render
  ;; `checked`, so a choice is always posted — hence the reachable branches are
  ;; the stale-token 409 and the merge itself, both exercised here.)
  (let [{:keys [alice orig pid]} (conflicting-proposal!)
        accept (fn [params]
                 (myapp.web.handler/proposal-accept
                   (assoc (h/auth-request :post (str "/proposals/" pid "/accept") "alice@x.lan")
                     :locale :en
                     :user-eid alice
                     :path-params {:id (str pid)}
                     :params params)))]
    (testing "resolving 'theirs' but with a stale target token → 409 stale notice"
      (let [stale (token-for orig)]
        (recipe/update! h/*conn* alice orig {:recipe/steps "boil\nmix\nserve"})
        (let [resp (accept
                     {:expected-version stale
                      :resolve-ingredients "theirs"})]
          (is (= 409 (:status resp)))
          (is (str/includes? (:body resp) "This recipe changed while you were reviewing")))))
    (testing "resolving 'theirs' with the fresh token merges the chosen side → 302"
      (let [resp (accept
                   {:expected-version (token-for orig)
                    :resolve-ingredients "theirs"})]
        (is (= 302 (:status resp)))
        (is
          (str/includes?
            (:recipe/ingredients (recipe/recipe-by-id (d/db h/*conn*) orig))
            "pecorino")
          "the chosen (theirs) value won — the radios ARE read")))))

(deftest proposal-decline-through-handlers
  (let [alice (mk-user! "alice@x.lan")
        bob (mk-user! "bob@x.lan")
        orig (recipe/create!
               h/*conn*
               alice
               {:title "Stew"
                :servings 2
                :ingredients "beef"
                :steps "simmer"})
        fork (recipe/fork! h/*conn* bob orig)
        _ (recipe/update! h/*conn* bob fork {:recipe/ingredients "beef\ncarrot"})
        pid (proposal/create-proposal! h/*conn* bob fork)
        decline (fn [id]
                  (myapp.web.handler/proposal-decline
                    (assoc (h/auth-request :post (str "/proposals/" id "/decline") "alice@x.lan")
                      :user-eid alice
                      :path-params {:id (str id)})))]
    (testing "a missing proposal → 404" (is (= 404 (:status (decline (UUID/randomUUID))))))
    (testing "the target owner declines → 302 to the target, proposal closed"
      (let [resp (decline pid)]
        (is (= 302 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Location"]) (str orig)))
        (is (empty? (proposal/open-proposals-targeting (d/db h/*conn*) orig)))))))

(deftest proposal-show-visibility
  (let [{:keys [orig pid]} (conflicting-proposal!)
        bob (d/q '[:find ?e . :in $ ?m :where [?e :user/email ?m]] (d/db h/*conn*) "bob@x.lan")
        carol (mk-user! "carol@x.lan")
        show (fn [email eid id]
               (myapp.web.handler/proposal-show
                 (assoc (h/auth-request :get (str "/proposals/" id) email)
                   :locale :en
                   :user-eid eid
                   :path-params {:id (str id)})))]
    (testing "a non-party sees a 404, not the merge"
      (is (= 404 (:status (show "carol@x.lan" carol pid)))))
    (testing "the source owner sees the preview but no accept form"
      (let [resp (show "bob@x.lan" bob pid)]
        (is (= 200 (:status resp)))
        (is
          (str/includes? (:body resp) "Waiting for the recipe")
          "the source owner is told to await review — no accept form")))
    (testing "a missing proposal → 404"
      (is (= 404 (:status (show "carol@x.lan" carol (UUID/randomUUID))))))
    (testing "a closed proposal redirects to the (merged) target"
      (let [alice
            (d/q '[:find ?e . :in $ ?m :where [?e :user/email ?m]] (d/db h/*conn*) "alice@x.lan")]
        (proposal/decline! h/*conn* alice pid)
        (let [resp (show "alice@x.lan" alice pid)]
          (is (= 302 (:status resp)))
          (is (str/includes? (get-in resp [:headers "Location"]) (str orig))))))))
