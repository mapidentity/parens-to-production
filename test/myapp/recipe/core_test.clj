(ns myapp.recipe.core-test
  "Tests for the recipe-versioning domain: line diff, edit history (Datomic
  time travel), forks/lineage, and owner-only mutations."
  (:require
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
    (is (= [{:op :ctx :text "a"}
            {:op :add :text "B"}
            {:op :del :text "b"}
            {:op :ctx :text "c"}
            {:op :add :text "d"}]
          d))))

(deftest line-diff-identical-is-all-context
  (is (every? #(= :ctx (:op %)) (recipe/line-diff "x\ny" "x\ny"))))

(deftest line-diff-from-empty-is-all-adds
  (is (= [{:op :add :text "one"} {:op :add :text "two"}]
        (recipe/line-diff "" "one\ntwo"))))

;; --- create / read ---

(deftest create-and-read
  (let [u (mk-user! "a@x.lan")
        id (recipe/create! h/*conn* u {:title "Bread" :servings 1 :ingredients "flour\nwater"})
        r (recipe/recipe-by-id (d/db h/*conn*) id)]
    (is (= "Bread" (:recipe/title r)))
    (is (= 1 (:recipe/servings r)))
    (is (recipe/owned-by? r u))))

;; --- edit history (Datomic time travel) ---

(deftest edits-create-versions
  (let [u (mk-user! "a@x.lan")
        id (recipe/create! h/*conn* u {:title "Soup" :ingredients "water"})]
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
        id (recipe/create! h/*conn* u {:title "Cake" :servings 8 :ingredients "flour\neggs"})]
    (recipe/update! h/*conn* u id {:recipe/servings 12 :recipe/ingredients "flour\neggs\nsugar"})
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
        orig (recipe/create! h/*conn* alice {:title "Pesto" :ingredients "basil\noil"})
        fork-id (recipe/fork! h/*conn* bob orig)
        f (recipe/recipe-by-id (d/db h/*conn*) fork-id)]
    (is (recipe/owned-by? f bob) "fork is owned by the forking user")
    (is (= "basil\noil" (:recipe/ingredients f)) "content copied from source")
    (is (= orig (get-in f [:recipe/forked-from :recipe/id])) "provenance recorded")))

(deftest lineage-walks-to-root
  (let [a (mk-user! "a@x.lan")
        r1 (recipe/create! h/*conn* a {:title "v1"})
        r2 (recipe/fork! h/*conn* a r1)
        r3 (recipe/fork! h/*conn* a r2)
        r4 (recipe/fork! h/*conn* a r3)
        lineage (recipe/lineage (d/db h/*conn*) r4)]
    (is (= 3 (count lineage)) "r4 descends from 3 ancestors (r3, r2, r1)")
    (is (= [r3 r2 r1] (mapv :recipe/id lineage)) "parent-first order to the root")
    (is (empty? (recipe/lineage (d/db h/*conn*) r1)) "an original has no ancestors")))

(deftest forks-lists-direct-children
  (let [a (mk-user! "a@x.lan")
        orig (recipe/create! h/*conn* a {:title "base"})
        _f1 (recipe/fork! h/*conn* a orig)
        _f2 (recipe/fork! h/*conn* a orig)
        children (recipe/forks (d/db h/*conn*) orig)]
    (is (= 2 (count children)))))

;; --- tenant isolation (owner-only mutations) ---

(deftest only-owner-can-update
  (let [alice (mk-user! "alice@x.lan")
        mallory (mk-user! "mallory@x.lan")
        id (recipe/create! h/*conn* alice {:title "Mine" :ingredients "secret"})]
    (is (nil? (recipe/update! h/*conn* mallory id {:recipe/title "Hacked"}))
      "a non-owner update is refused")
    (is (= "Mine" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id)))
      "the recipe is unchanged")
    (is (true? (recipe/update! h/*conn* alice id {:recipe/title "Mine v2"}))
      "the owner can update")
    (is (= "Mine v2" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) id))))))

(deftest only-owner-can-delete
  (let [alice (mk-user! "alice@x.lan")
        mallory (mk-user! "mallory@x.lan")
        id (recipe/create! h/*conn* alice {:title "Keep"})]
    (is (nil? (recipe/delete! h/*conn* mallory id)))
    (is (some? (recipe/recipe-by-id (d/db h/*conn*) id)) "still there")
    (is (true? (recipe/delete! h/*conn* alice id)))
    (is (nil? (recipe/recipe-by-id (d/db h/*conn*) id)) "gone")))

;; --- aggregates ---

(deftest aggregate-counts
  (let [a (mk-user! "a@x.lan")
        orig (recipe/create! h/*conn* a {:title "x"})]
    (recipe/fork! h/*conn* a orig)
    (is (= 2 (recipe/total-recipes (d/db h/*conn*))))
    (is (= 1 (recipe/total-forks (d/db h/*conn*))))))
