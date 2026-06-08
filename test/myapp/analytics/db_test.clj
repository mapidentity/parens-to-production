(ns myapp.analytics.db-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [datomic.api :as d]
    [myapp.analytics.db :as analytics]
    [myapp.test-helpers :as h])
  (:import
    [java.time Instant]
    [java.util Date]))

(set! *warn-on-reflection* true)

(use-fixtures :each h/with-test-analytics-db)

;; -- convert-instant --

(deftest convert-instant-converts-instant-to-date
  (let [convert-instant #'myapp.analytics.db/convert-instant
        inst (Instant/parse "2026-01-15T12:00:00Z")
        result (convert-instant inst)]
    (is (instance? Date result))
    (is (= (.toEpochMilli inst) (.getTime ^Date result)))))

(deftest convert-instant-passthrough-non-instant
  (let [convert-instant #'myapp.analytics.db/convert-instant]
    (is (= "hello" (convert-instant "hello")))
    (is (= 42 (convert-instant 42)))
    (let [d (Date.)]
      (is (identical? d (convert-instant d))))))

;; -- schema --

(deftest schema-is-non-empty-vector-of-maps
  (is (vector? analytics/schema))
  (is (pos? (count analytics/schema)))
  (is (every? map? analytics/schema)))

;; -- record! --

(deftest record-transacts-and-queryable
  (let [inst (Instant/parse "2026-02-01T09:00:00Z")]
    (analytics/record!
      [{:magic-link/email "test@example.com"
        :magic-link/requested-at inst}])
    (let [db (d/db h/*analytics-conn*)
          results (d/q
                    '[:find ?e ?email ?req
                      :where
                      [?e :magic-link/email ?email]
                      [?e :magic-link/requested-at ?req]]
                    db)]
      (is (= 1 (count results)))
      (let [[_ email req] (first results)]
        (is (= "test@example.com" email))
        (is (instance? Date req))
        (is (= (.toEpochMilli inst) (.getTime ^Date req)))))))

(deftest record-converts-instants-to-dates
  (let [requested (Instant/parse "2026-03-01T08:00:00Z")
        verified (Instant/parse "2026-03-01T08:05:00Z")]
    (analytics/record!
      [{:magic-link/email "convert@example.com"
        :magic-link/requested-at requested
        :magic-link/verified-at verified}])
    (let [db (d/db h/*analytics-conn*)
          results (d/q
                    '[:find ?req ?ver
                      :where
                      [?e :magic-link/email "convert@example.com"]
                      [?e :magic-link/requested-at ?req]
                      [?e :magic-link/verified-at ?ver]]
                    db)]
      (is (= 1 (count results)))
      (let [[req ver] (first results)]
        (is (instance? Date req))
        (is (instance? Date ver))
        (is (= (.toEpochMilli requested) (.getTime ^Date req)))
        (is (= (.toEpochMilli verified) (.getTime ^Date ver)))))))
