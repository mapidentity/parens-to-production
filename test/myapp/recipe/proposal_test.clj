(ns myapp.recipe.proposal-test
  "The three-way merge and the proposal lifecycle.
  The merge cases are the crux; the negative/authz cases and the
  optimistic-concurrency composition round it out."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.recipe.proposal :as prop]
    [myapp.test-helpers :as h]
    [myapp.time :as time])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-db)

(defn- mk-user!
  "Create a terms-accepted user, return its eid."
  [email]
  @(db/transact* h/*conn*
     [{:db/id "u"
       :user/id (UUID/randomUUID)
       :user/email email
       :user/active? true
       :user/created-at (time/now)
       :user/terms-accepted-at (time/now)}])
  (d/q '[:find ?e . :in $ ?m :where [?e :user/email ?m]] (d/db h/*conn*) email))

;; --- the pure merge ---

(deftest three-way-merge-cases
  (let [base {:recipe/title "Soup"
              :recipe/servings 2
              :recipe/ingredients "water"}
        m (fn [ours theirs] (prop/three-way-merge base (merge base ours) (merge base theirs)))]
    (testing "only the fork changed a field → applied, no conflict"
      (let [r (m {} {:recipe/title "Better Soup"})]
        (is (false? (:conflict? r)))
        (is (= :applied (get-in r [:fields :recipe/title :status])))
        (is (= "Better Soup" (get-in r [:clean :recipe/title])))))
    (testing "only the target changed a field → kept, fork proposed nothing there"
      (let [r (m {:recipe/servings 4} {})]
        (is (false? (:conflict? r)))
        (is (= :unchanged (get-in r [:fields :recipe/servings :status])))
        (is (= 4 (get-in r [:clean :recipe/servings])) "the target's own change is preserved")))
    (testing "both changed the SAME field to the same value → converged, no conflict"
      (let [r (m {:recipe/title "Same"} {:recipe/title "Same"})]
        (is (false? (:conflict? r)))
        (is (= :unchanged (get-in r [:fields :recipe/title :status])))))
    (testing "both changed a field DIFFERENTLY → conflict, that field absent from :clean"
      (let [r (m {:recipe/title "Ours"} {:recipe/title "Theirs"})]
        (is (true? (:conflict? r)))
        (is (= :conflict (get-in r [:fields :recipe/title :status])))
        (is (not (contains? (:clean r) :recipe/title)))
        (is (= "Soup" (get-in r [:fields :recipe/title :base])))
        (is (= "Ours" (get-in r [:fields :recipe/title :ours])))
        (is (= "Theirs" (get-in r [:fields :recipe/title :theirs])))))))

;; --- the lifecycle ---

(defn- setup-fork!
  "Alice owns an original; Bob forks it. Returns the eids/ids."
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
        fork (recipe/fork! h/*conn* bob orig)]
    {:alice alice
     :bob bob
     :orig orig
     :fork fork}))

(deftest propose-and-accept-clean
  (let [{:keys [alice bob orig fork]} (setup-fork!)]
    ;; Bob improves his fork, proposes it back to Alice's original.
    (recipe/update! h/*conn* bob fork {:recipe/ingredients "pasta\neggs\npecorino"})
    (let [pid (prop/create-proposal! h/*conn* bob fork)]
      (is (uuid? pid) "the fork owner may propose")
      (testing "a non-target-owner cannot accept"
        (is (nil? (prop/accept! h/*conn* bob pid {} nil)) "bob doesn't own the target"))
      (testing "the target owner sees the pending proposal"
        (is (= 1 (count (prop/open-proposals-targeting (d/db h/*conn*) orig)))))
      (testing "a clean accept merges the fork's change into the original"
        (let [tok (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) orig))]
          (is (true? (prop/accept! h/*conn* alice pid {} tok)))
          (is
            (=
              "pasta\neggs\npecorino"
              (:recipe/ingredients (recipe/recipe-by-id (d/db h/*conn*) orig)))
            "the merge became a new version of the original")
          (is
            (empty? (prop/open-proposals-targeting (d/db h/*conn*) orig))
            "the proposal is no longer open"))))))

(deftest propose-and-accept-with-conflict
  (let [{:keys [alice bob orig fork]} (setup-fork!)]
    ;; Both diverge on the SAME field.
    (recipe/update! h/*conn* bob fork {:recipe/title "Bob's Carbonara"})
    (recipe/update! h/*conn* alice orig {:recipe/title "Alice's Carbonara"})
    (let [pid (prop/create-proposal! h/*conn* bob fork)
          tok (fn [] (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) orig)))]
      (testing "the merge reports the conflict"
        (let [m (prop/merge-for (d/db h/*conn*) (prop/proposal-by-id (d/db h/*conn*) pid))]
          (is (true? (:conflict? m)))))
      (testing "accepting without resolving the conflict is refused"
        (is (= :incomplete (prop/accept! h/*conn* alice pid {} (tok)))))
      (testing "accepting WITH a resolution merges the chosen value"
        (is (true? (prop/accept! h/*conn* alice pid {:recipe/title "Bob's Carbonara"} (tok))))
        (is (= "Bob's Carbonara" (:recipe/title (recipe/recipe-by-id (d/db h/*conn*) orig))))))))

(deftest accept-composes-with-optimistic-concurrency
  (let [{:keys [alice bob orig fork]} (setup-fork!)]
    (recipe/update! h/*conn* bob fork {:recipe/servings 3})
    (let [pid (prop/create-proposal! h/*conn* bob fork)
          stale-tok (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) orig))]
      ;; Alice edits the original AFTER opening the review → her token is stale.
      (recipe/update! h/*conn* alice orig {:recipe/description "meanwhile edited"})
      (testing "accept with a stale target token → :conflict, proposal stays open"
        (is (= :conflict (prop/accept! h/*conn* alice pid {} stale-tok)))
        (is (= 1 (count (prop/open-proposals-targeting (d/db h/*conn*) orig)))))
      (testing "re-reviewing with the fresh token succeeds"
        (let [fresh (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) orig))]
          (is (true? (prop/accept! h/*conn* alice pid {} fresh)))
          (is (= 3 (:recipe/servings (recipe/recipe-by-id (d/db h/*conn*) orig)))))))))

(deftest closed-proposal-is-idempotent
  ;; The lifecycle guard on :open protects the double-submit path — a second
  ;; accept (or a decline after accept) must be a no-op, never a second merge.
  (let [{:keys [alice bob orig fork]} (setup-fork!)]
    (recipe/update! h/*conn* bob fork {:recipe/ingredients "pasta\neggs\npecorino"})
    (let [pid (prop/create-proposal! h/*conn* bob fork)
          fresh (fn [] (:recipe/updated-at (recipe/recipe-by-id (d/db h/*conn*) orig)))]
      (is (true? (prop/accept! h/*conn* alice pid {} (fresh))) "the first accept merges")
      (let [merged (:recipe/ingredients (recipe/recipe-by-id (d/db h/*conn*) orig))]
        (testing "accepting an already-accepted proposal is a no-op (nil)"
          (is (nil? (prop/accept! h/*conn* alice pid {} (fresh))))
          (is
            (= merged (:recipe/ingredients (recipe/recipe-by-id (d/db h/*conn*) orig)))
            "no second merge landed"))
        (testing "declining a closed proposal is refused (nil)"
          (is (nil? (prop/decline! h/*conn* alice pid))))))))

(deftest proposal-authz
  (let [{:keys [alice bob orig fork]} (setup-fork!)
        carol (mk-user! "carol@x.lan")]
    (testing "only the fork owner may propose"
      (is (nil? (prop/create-proposal! h/*conn* carol fork)) "carol doesn't own the fork"))
    (testing "an original (non-fork) cannot be proposed"
      (is (nil? (prop/create-proposal! h/*conn* alice orig)) "orig has no parent"))
    (let [pid (prop/create-proposal! h/*conn* bob fork)]
      (testing "a stranger cannot decline" (is (nil? (prop/decline! h/*conn* carol pid))))
      (testing "the target owner can decline"
        (is (true? (prop/decline! h/*conn* alice pid)))
        (is (empty? (prop/open-proposals-targeting (d/db h/*conn*) orig)))))))
