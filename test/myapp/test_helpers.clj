(ns myapp.test-helpers
  "Shared test fixtures and utilities.
  Provides a fresh in-memory Datomic DB per test, deterministic config values,
  and a Ring request builder."
  (:require
    [datomic.api :as d]
    [myapp.analytics.db :as analytics]
    [myapp.auth.core :as auth]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.db.schema :as schema]))

(set! *warn-on-reflection* true)

;; Dynamic var bound to a fresh connection per test
(def ^:dynamic *conn*
  "Bound to a fresh Datomic connection per test by the with-test-db fixture."
  nil)

(defn with-test-db
  "Fixture: creates a fresh in-memory Datomic DB per test.
   Binds *conn* and stubs db/get-connection."
  [f]
  (let [uri (str "datomic:mem://myapp-test-" (System/nanoTime))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn schema/schema)
      (binding [*conn* conn]
        (with-redefs [db/get-connection (fn [] *conn*)]
          (f)))
      (d/delete-database uri))))

(def ^:dynamic *analytics-conn*
  "Bound to a fresh analytics Datomic connection per test."
  nil)

(defn with-test-analytics-db
  "Fixture: creates a fresh in-memory analytics DB per test.
   Binds *analytics-conn* and stubs analytics/get-connection and analytics/get-db."
  [f]
  (let [uri (str "datomic:mem://myapp-analytics-test-" (System/nanoTime))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn analytics/schema)
      (binding [*analytics-conn* conn]
        (with-redefs [analytics/get-connection (fn [] *analytics-conn*)
                      analytics/get-db (fn [] (d/db *analytics-conn*))]
          (f)))
      (d/delete-database uri))))

(def test-signing-key
  "Deterministic signing key for tests."
  (.getBytes "test-signing-key-32-bytes-long!!" "UTF-8"))

(def test-session-key
  "Deterministic 16-byte session key for tests."
  (.getBytes "0123456789abcdef" "UTF-8"))

(def test-config
  "Deterministic config for tests."
  {:server {:port 3000
            :host "0.0.0.0"}
   :base-url "https://test.myapp.lan"
   :uploads-root "target/test-uploads"
   :session-key test-session-key
   :signing-key test-signing-key
   :admin-email "admin@test.myapp.lan"
   :analytics-database-uri "datomic:mem://myapp-analytics-test"
   :smtp {:host "localhost"
          :port 1025
          :tls false
          :user nil
          :pass nil
          :from "test@myapp.lan"}})

(defn with-test-config
  "Fixture: stubs myapp.config/config with deterministic test values."
  [f]
  (with-redefs [config/config (delay test-config)]
    (f)))

(defn request
  "Build a minimal Ring request map. Defaults locale to :nl.

  When a `:session` with `:user-email` is supplied, also resolves and
  populates `:user-eid` and `:user-email` directly on the request,
  mirroring what `wrap-auth` does in production. This keeps test code
  free of an extra step and the production-vs-test request shape
  identical for authenticated handlers."
  [method uri &
   {:keys [session params locale]
    :or {locale :nl}}]
  (let [user-email (:user-email session)
        user-eid (when (and user-email *conn*) (auth/find-user-by-email (d/db *conn*) user-email))]
    (cond-> {:request-method method
             :uri uri
             :locale locale}
      session (assoc :session
                session)
      user-email (assoc :user-email
                   user-email)
      user-eid (assoc :user-eid
                 user-eid)
      params (assoc :params
               params))))

(defn auth-request
  "Build a request as if `wrap-auth` had already run.

  Convenience wrapper around `request` for tests that don't care about
  the surrounding session — passes `email` and lets `request` resolve
  `:user-eid`."
  [method uri user-email & opts]
  (apply request method uri :session {:user-email user-email} opts))
