(ns myapp.recipe.core-test
  "Tests for the recipe-versioning domain.
  Cover line diff, edit history (Datomic time travel), forks/lineage, and
  owner-only mutations."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.test-helpers :as h]
    [myapp.time :as time])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-db)

(defn- mk-user!
  "Create a user and return its eid."
  [email]
  @(db/transact* h/*conn*
     [{:db/id (str "u-" email)
       :user/id (UUID/randomUUID)
       :user/email email
       :user/active? true
       :user/created-at (time/now)}])
  (d/q '[:find ?e . :in $ ?m :where [?e :user/email ?m]] (d/db h/*conn*) email))

;; --- line diff (pure) ---

(deftest line-diff-detects-add-and-delete
  (let [d (recipe/line-diff "a\nb\nc" "a\nB\nc\nd")]
    (is
      (=
        [{:op :ctx
          :text "a"}
         {:op :add
          :text "B"}
         {:op :del
          :text "b"}
         {:op :ctx
          :text "c"}
         {:op :add
          :text "d"}]
        d))))

(deftest line-diff-identical-is-all-context
  (is (every? #(= :ctx (:op %)) (recipe/line-diff "x\ny" "x\ny"))))

(deftest line-diff-from-empty-is-all-adds
  (is
    (=
      [{:op :add
        :text "one"}
       {:op :add
        :text "two"}]
      (recipe/line-diff "" "one\ntwo"))))

(deftest line-diff-caps-oversized-input-to-a-coarse-bounded-diff
  ;; The LCS table is O(n*m) in memory; on a public, cacheable endpoint two
  ;; large inputs would be a quadratic-blowup DoS. Past the ceiling the diff
  ;; degrades to all-del/all-add — bounded, O(n+m), and quick to compute.
  (let [big-a (str/join "\n" (map #(str "old-" %) (range 6000)))
        big-b (str/join "\n" (map #(str "new-" %) (range 6000)))
        started (System/nanoTime)
        d (recipe/line-diff big-a big-b)
        elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
    (testing "it returns a coarse diff: every old line deleted, every new added"
      (is (= 12000 (count d)))
      (is (= 6000 (count (filter #(= :del (:op %)) d))))
      (is (= 6000 (count (filter #(= :add (:op %)) d))))
      (is
        (=
          {:op :del
           :text "old-0"}
          (first d)))
      (is
        (=
          {:op :add
           :text "new-5999"}
          (last d))))
    (testing "it does not build the quadratic table (stays fast, not seconds)"
      (is (< elapsed-ms 1000) "6000x6000 lines complete well under a second"))))

;; --- create / read ---

(deftest create-and-read
  (let [u (mk-user! "a@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Bread"
              :servings 1
              :ingredients "flour\nwater"})
        r (recipe/recipe-by-id (d/db h/*conn*) id)]
    (is (= "Bread" (:recipe/title r)))
    (is (= 1 (:recipe/servings r)))
    (is (recipe/owned-by? r u))))

;; --- edit history (Datomic time travel) ---

(deftest edits-create-versions
  (let [u (mk-user! "a@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Soup"
              :servings 2
              :ingredients "water"})]
    (recipe/update! h/*conn* u id {:recipe/ingredients "water\nsalt"})
    (recipe/update! h/*conn* u id {:recipe/ingredients "water\nsalt\npepper"})
    (let [versions (recipe/version-history (d/db h/*conn*) id)]
      (is (= 3 (count versions)) "create + 2 edits = 3 versions")
      (testing "oldest version reconstructs the original via d/as-of"
        (is (= "water" (:recipe/ingredients (:recipe (first versions))))))
      (testing "newest version has the latest content"
        (is (= "water\nsalt\npepper" (:recipe/ingredients (:recipe (last versions))))))
      (testing "version-as-of by basis-t returns that point's state"
        (let [t (:t (first versions))]
          (is (= "water" (:recipe/ingredients (recipe/version-as-of (d/db h/*conn*) id t)))))))))

(deftest diff-between-versions
  (let [u (mk-user! "a@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Cake"
              :servings 8
              :ingredients "flour\neggs"})]
    (recipe/update!
      h/*conn*
      u
      id
      {:recipe/servings 12
       :recipe/ingredients "flour\neggs\nsugar"})
    (let [[v0 v1] (recipe/version-history (d/db h/*conn*) id)
          d (recipe/diff (:recipe v0) (:recipe v1))]
      (is (:changed? d))
      (is (get-in d [:servings :changed?]))
      (is (= 8 (get-in d [:servings :old])))
      (is (= 12 (get-in d [:servings :new])))
      (is (some #(and (= :add (:op %)) (= "sugar" (:text %))) (:ingredients d))))))

;; --- forks / lineage ---

(deftest fork-records-provenance-and-copies-content
  (let [alice (mk-user! "alice@x.lan")
        bob (mk-user! "bob@x.lan")
        orig (recipe/create!
               h/*conn*
               alice
               {:title "Pesto"
                :servings 2
                :ingredients "basil\noil"})
        fork-id (recipe/fork! h/*conn* bob orig)
        f (recipe/recipe-by-id (d/db h/*conn*) fork-id)]
    (is (recipe/owned-by? f bob) "fork is owned by the forking user")
    (is (= "basil\noil" (:recipe/ingredients f)) "content copied from source")
    (is (= orig (get-in f [:recipe/forked-from :recipe/id])) "provenance recorded")))

(deftest lineage-walks-to-root
  (let [a (mk-user! "a@x.lan")
        r1 (recipe/create!
             h/*conn*
             a
             {:title "v1"
              :servings 1})
        r2 (recipe/fork! h/*conn* a r1)
        r3 (recipe/fork! h/*conn* a r2)
        r4 (recipe/fork! h/*conn* a r3)
        lineage (recipe/lineage (d/db h/*conn*) r4)]
    (is (= 3 (count lineage)) "r4 descends from 3 ancestors (r3, r2, r1)")
    (is (= [r3 r2 r1] (mapv :recipe/id lineage)) "parent-first order to the root")
    (is (empty? (recipe/lineage (d/db h/*conn*) r1)) "an original has no ancestors")))

(deftest forks-lists-direct-children
  (let [a (mk-user! "a@x.lan")
        orig (recipe/create!
               h/*conn*
               a
               {:title "base"
                :servings 1})
        _f1 (recipe/fork! h/*conn* a orig)
        _f2 (recipe/fork! h/*conn* a orig)
        children (recipe/forks (d/db h/*conn*) orig)]
    (is (= 2 (count children)))))

;; --- tenant isolation (owner-only mutations) ---

(deftest only-owner-can-update
  (let [alice (mk-user! "alice@x.lan")
        mallory (mk-user! "mallory@x.lan")
        id (recipe/create!
             h/*conn*
             alice
             {:title "Mine"
              :servings 2
              :ingredients "secret"})]
    (is
      (nil? (recipe/update! h/*conn* mallory id {:recipe/title "Hacked"}))
      "a non-owner update is refused")
    (is
      (= "Mine" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id)))
      "the recipe is unchanged")
    (is (true? (recipe/update! h/*conn* alice id {:recipe/title "Mine v2"})) "the owner can update")
    (is (= "Mine v2" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id))))))

(deftest only-owner-can-delete
  (let [alice (mk-user! "alice@x.lan")
        mallory (mk-user! "mallory@x.lan")
        id (recipe/create!
             h/*conn*
             alice
             {:title "Keep"
              :servings 1})]
    (is (nil? (recipe/delete! h/*conn* mallory id)))
    (is (some? (recipe/recipe-by-id (d/db h/*conn*) id)) "still there")
    (is (true? (recipe/delete! h/*conn* alice id)))
    (is (nil? (recipe/recipe-by-id (d/db h/*conn*) id)) "gone")))

;; --- aggregates ---

(deftest aggregate-counts
  (let [a (mk-user! "a@x.lan")
        orig (recipe/create!
               h/*conn*
               a
               {:title "x"
                :servings 1})]
    (recipe/fork! h/*conn* a orig)
    (is (= 2 (recipe/total-recipes (d/db h/*conn*))))
    (is (= 1 (recipe/total-forks (d/db h/*conn*))))))

;; --- conform: the validation boundary ---

(deftest conform-coerces-and-trims
  (let [{:keys [values errors]} (recipe/conform
                                  {:title "  Carbonara  "
                                   :servings "4"
                                   :description "d"
                                   :ingredients "eggs"
                                   :steps "whisk"})]
    (is (nil? errors))
    (is (= "Carbonara" (:title values)) "title arrives trimmed")
    (is (= 4 (:servings values)) "servings arrives as a long")))

(deftest conform-names-the-field-and-the-problem
  (is
    (=
      {:title [:blank]
       :servings [:not-a-number]}
      (:errors
        (recipe/conform
          {:title "   "
           :servings "many"})))
    "codes are data, keyed by field — no prose in the domain")
  (is
    (=
      {:servings [:out-of-range]}
      (:errors
        (recipe/conform
          {:title "ok"
           :servings "0"}))))
  (is
    (=
      {:servings [:out-of-range]}
      (:errors
        (recipe/conform
          {:title "ok"
           :servings "101"}))))
  (is
    (nil?
      (:errors
        (recipe/conform
          {:title "ok"
           :servings "1"})))
    "range is inclusive low")
  (is
    (nil?
      (:errors
        (recipe/conform
          {:title "ok"
           :servings "100"})))
    "range is inclusive high")
  (is
    (=
      [:too-long]
      (:title
        (:errors
          (recipe/conform
            {:title (apply str (repeat 201 "x"))
             :servings "2"}))))))

(deftest domain-refuses-unconformed-content
  (let [u (mk-user! "strict@x.lan")]
    (is
      (thrown?
        clojure.lang.ExceptionInfo
        (recipe/create!
          h/*conn*
          u
          {:title "   "
           :servings 2}))
      "create! throws instead of coining a title — no more \"Untitled recipe\"")
    (let [id (recipe/create!
               h/*conn*
               u
               {:title "Real"
                :servings 2})]
      (is
        (thrown? clojure.lang.ExceptionInfo (recipe/update! h/*conn* u id {:recipe/servings 0}))
        "update! holds the keys it is given to the same rules"))))

;; --- preview: the speculative database ---

(deftest preview-renders-the-future-without-writing-it
  (let [u (mk-user! "seer@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Bolognese"
              :servings 4
              :ingredients "beef"})
        db (d/db h/*conn*)
        p (recipe/preview
            db
            u
            id
            {:title "Bolognese v2"
             :servings 6
             :description "Now with **wine**."
             :ingredients "beef\nwine"
             :steps "simmer"})]
    (is (= "Bolognese v2" (:recipe/title p)) "the preview carries the unsaved edit")
    (is (= 6 (:recipe/servings p)))
    (is (= "seer@x.lan" (get-in p [:recipe/user :user/email])) "pulled through the real pattern")
    (is
      (= "Bolognese" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id)))
      "…and the database never heard about it")
    (is (= 1 (count (recipe/version-history (d/db h/*conn*) id))) "no phantom version either")))

(deftest preview-respects-ownership-and-supports-new
  (let [alice (mk-user! "alice2@x.lan")
        mallory (mk-user! "mallory2@x.lan")
        id (recipe/create!
             h/*conn*
             alice
             {:title "Hers"
              :servings 2})
        db (d/db h/*conn*)]
    (is
      (nil?
        (recipe/preview
          db
          mallory
          id
          {:title "Mine now"
           :servings 2}))
      "previewing someone else's recipe is refused like any other write")
    (let [p (recipe/preview
              db
              mallory
              nil
              {:title "Fresh"
               :servings 3})]
      (is (= "Fresh" (:recipe/title p)) "a new-recipe preview needs no existing entity")
      (is (= 1 (recipe/total-recipes (d/db h/*conn*))) "and creates nothing"))))

;; --- search: the index the schema already carries ---

(deftest search-uses-the-fulltext-index
  (let [u (mk-user! "chef@x.lan")]
    (recipe/create!
      h/*conn*
      u
      {:title "Classic Carbonara"
       :servings 2})
    (recipe/create!
      h/*conn*
      u
      {:title "Carbonara, but vegan"
       :servings 2})
    (recipe/create!
      h/*conn*
      u
      {:title "Gazpacho"
       :servings 4})
    (let [db (d/db h/*conn*)]
      (is
        (=
          #{"Classic Carbonara" "Carbonara, but vegan"}
          (set (map :recipe/title (recipe/search db "carbonara"))))
        "matching is on words, case-insensitively")
      (is (= ["Gazpacho"] (map :recipe/title (recipe/search db "GAZPACHO"))))
      (is (= [] (recipe/search db "tiramisu")) "no hits is an empty vector")
      (is (= [] (recipe/search db "   ")) "blank input searches nothing")
      (is
        (= [] (recipe/search db "AND OR * ~ ("))
        "Lucene operator soup is neutralized, not thrown"))))

;; --- activity: notifications as a query (d/since) ---

(deftest activity-reads-forks-and-upstream-edits-from-the-log
  (let [alice (mk-user! "alice3@x.lan")
        bob (mk-user! "bob3@x.lan")
        orig (recipe/create!
               h/*conn*
               alice
               {:title "Alice's Bread"
                :servings 1})
        ;; Bob forks Alice's recipe → Alice hears about it; Bob doesn't.
        _ (recipe/fork! h/*conn* bob orig)
        items (recipe/activity (d/db h/*conn*) alice nil)]
    (is (= [:fork] (map :type items)))
    (is
      (=
        "bob3@x.lan"
        (-> items
            first
            :recipe
            :recipe/user
            :user/email)))
    ;; Alice edits her original → Bob (who forked it) hears about that.
    (recipe/update! h/*conn* alice orig {:recipe/title "Alice's Better Bread"})
    (let [bob-items (recipe/activity (d/db h/*conn*) bob nil)
          upstream (filter #(= :upstream-edit (:type %)) bob-items)]
      (is (seq upstream))
      (is (= "Alice's Better Bread" (:recipe/title (:recipe (first upstream))))))
    ;; Nobody is notified about themselves.
    (is
      (empty? (filter #(= :fork (:type %)) (recipe/activity (d/db h/*conn*) bob nil)))
      "bob's own fork is not bob's news")))

(deftest activity-cursor-narrows-the-window
  (let [alice (mk-user! "alice4@x.lan")
        bob (mk-user! "bob4@x.lan")
        orig (recipe/create!
               h/*conn*
               alice
               {:title "Cursor Soup"
                :servings 2})]
    (recipe/fork! h/*conn* bob orig)
    (is (= 1 (count (recipe/activity (d/db h/*conn*) alice nil))) "the fork is visible")
    (is
      (empty? (recipe/activity (d/db h/*conn*) alice (time/now)))
      "…and invisible past a cursor set after it")))

;; --- provenance: the transaction is an entity ---

(deftest every-write-names-its-author-and-keeps-the-note
  (let [u (mk-user! "annalist@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Annotated Stew"
              :servings 2})]
    (recipe/update! h/*conn* u id {:recipe/servings 3} "Feeds one more now")
    (let [[v1 v2] (recipe/version-history (d/db h/*conn*) id)]
      (is (= "annalist@x.lan" (get-in v1 [:author :user/email])) "creation is authored")
      (is (nil? (:note v1)) "no note was offered at creation")
      (is (= "annalist@x.lan" (get-in v2 [:author :user/email])))
      (is (= "Feeds one more now" (:note v2)) "the commit message survives on the tx"))))

(deftest conform-accepts-and-bounds-the-note
  (is
    (nil?
      (:note
        (:values
          (recipe/conform
            {:title "t"
             :servings "1"
             :note "   "}))))
    "a whitespace note is no note")
  (is
    (=
      "why"
      (:note
        (:values
          (recipe/conform
            {:title "t"
             :servings "1"
             :note " why "})))))
  (is
    (=
      [:too-long]
      (:note
        (:errors
          (recipe/conform
            {:title "t"
             :servings "1"
             :note (apply str (repeat 501 "x"))}))))))

(deftest optimistic-concurrency-on-update
  (let [u (mk-user! "occ@x.lan")
        id (recipe/create!
             h/*conn*
             u
             {:title "Shared"
              :servings 2
              :ingredients "a"
              :steps "b"})]
    (testing "a save WITHOUT a token still works (backward compatible)"
      (is (true? (recipe/update! h/*conn* u id {:recipe/title "No token"} nil nil))))
    ;; re-read the token after that untracked save
    (let [tok (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) id))]
      (testing "concurrent edit: editor A saves with the current token -> wins"
        (is (true? (recipe/update! h/*conn* u id {:recipe/title "A wins"} "a" tok))))
      (testing "editor B saves with the NOW-STALE token -> :conflict, nothing changes"
        (is (= :conflict (recipe/update! h/*conn* u id {:recipe/title "B stale"} "b" tok)))
        (is
          (= "A wins" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id)))
          "B's stale write touched nothing"))
      (testing "B reloads the fresh token and retries -> overwrite succeeds"
        (let [fresh (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) id))]
          (is (true? (recipe/update! h/*conn* u id {:recipe/title "B retried"} "b" fresh)))
          (is (= "B retried" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id)))))))
    (testing "a REORDER between load and save does NOT false-conflict (scoped to content)"
      (let [tok (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) id))]
        (recipe/reorder! h/*conn* u [id]) ; bumps :recipe/position, NOT :recipe/updated-at
        (is
          (true? (recipe/update! h/*conn* u id {:recipe/title "After reorder"} "c" tok))
          "the content-scoped token survived a bookkeeping reorder")))
    (testing "an unrelated recipe's edit does not conflict either (per-entity scope)"
      (let [id2 (recipe/create!
                  h/*conn*
                  u
                  {:title "Other"
                   :servings 1
                   :ingredients "x"
                   :steps "y"})
            tok (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) id))]
        (recipe/update! h/*conn* u id2 {:recipe/title "Other edited"} "d" nil) ; advances GLOBAL basis-t
        (is
          (true? (recipe/update! h/*conn* u id {:recipe/title "Unaffected"} "e" tok))
          "the global basis-t moved, but this recipe's content clock did not")))))
