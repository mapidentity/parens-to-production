(ns myapp.db.core-test
  "Round-trip tests for the db wrappers.
  Write with java.time.Instant, read back java.time.Instant, with Datomic
  storing java.util.Date internally. Also covers schema installation and
  :db.unique/identity upsert semantics."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.test-helpers :as h]
    [myapp.time :as time])
  (:import
    [java.time Instant]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-db)

(deftest transact-with-instant-values
  (let [now (time/now)
        user-id (java.util.UUID/randomUUID)]
    @(db/transact* h/*conn*
       [{:user/id user-id
         :user/email "test@example.com"
         :user/created-at now
         :user/active? true}])
    (let [db (d/db h/*conn*)
          eid (d/q '[:find ?e .
                     :in $ ?email
                     :where [?e :user/email ?email]]
                   db "test@example.com")]
      (is (some? eid)))))

(deftest pull-returns-instants
  (let [now (Instant/parse "2025-06-15T12:00:00Z")
        user-id (java.util.UUID/randomUUID)]
    @(db/transact* h/*conn*
       [{:user/id user-id
         :user/email "pull@example.com"
         :user/created-at now
         :user/active? true}])
    (let [db (d/db h/*conn*)
          eid (d/q '[:find ?e .
                     :in $ ?email
                     :where [?e :user/email ?email]]
                   db "pull@example.com")
          user (db/pull* db [:user/created-at] eid)]
      (is (instance? Instant (:user/created-at user)))
      (is (= (.toEpochMilli now)
             (.toEpochMilli ^Instant (:user/created-at user)))))))

(deftest q-converts-dates-in-results
  (let [now (Instant/parse "2025-06-15T12:00:00Z")
        user-id (java.util.UUID/randomUUID)]
    @(db/transact* h/*conn*
       [{:user/id user-id
         :user/email "q@example.com"
         :user/created-at now
         :user/active? true}])
    (let [db (d/db h/*conn*)
          results (db/q*
                    '[:find ?email ?created
                      :where
                      [?e :user/email ?email]
                      [?e :user/created-at ?created]]
                    db)]
      (is (= 1 (count results)))
      (let [[email created] (first results)]
        (is (= "q@example.com" email))
        (is (instance? Instant created))))))

(deftest schema-idents-exist
  (let [db (d/db h/*conn*)
        expected-idents #{:user/id :user/email :user/created-at
                          :user/active? :user/terms-accepted-at}]
    (doseq [ident expected-idents]
      (is (some? (d/entity db ident))
          (str "Schema ident " ident " should exist")))))

(deftest email-uniqueness-is-identity
  (let [now (time/now)]
    @(db/transact* h/*conn*
       [{:user/id (java.util.UUID/randomUUID)
         :user/email "unique@example.com"
         :user/created-at now
         :user/active? true}])
    @(db/transact* h/*conn*
       [{:user/id (java.util.UUID/randomUUID)
         :user/email "unique@example.com"
         :user/created-at now
         :user/active? true}])
    (let [db (d/db h/*conn*)
          n (d/q '[:find (count ?e) .
                   :in $ ?email
                   :where [?e :user/email ?email]]
                 db "unique@example.com")]
      (is (= 1 n)
          "Same email should resolve to one entity (upsert)"))))
