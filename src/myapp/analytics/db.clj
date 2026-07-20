(ns myapp.analytics.db
  "Analytics database layer.
  Separate Datomic database for usage/analytics events. Same transactor,
  same PostgreSQL — zero new infrastructure. Disposable, with one caveat:
  the magic-link nonces that make sign-in single-use live here, so
  recreate it only when no unexpired magic links are outstanding — a wipe
  resets replay protection for links still in flight."
  (:require
    [datomic.api :as d]
    [myapp.config :as config])
  (:import
    [java.time Instant]
    [java.util Date]))

(set! *warn-on-reflection* true)

(def schema
  "Analytics schema — magic link tracking.

  `:magic-link/nonce` is the one-shot anti-replay token: a per-link UUID
  embedded in the signed magic-link payload AND recorded here. Verify
  looks up by nonce and CAS-stamps :verified-at; a replayed token sees
  :verified-at already set and the CAS fails. Without the nonce, a
  leaked link (forwarded email, mail-server log) was replayable for
  the full 15-min token window."
  [{:db/ident :magic-link/email
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :magic-link/nonce
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Per-token nonce. Used as the one-shot anti-replay key."}
   {:db/ident :magic-link/requested-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :magic-link/verified-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn analytics-uri
  "Returns the analytics database URI from config."
  []
  (config/get-config :analytics-database-uri))

(defn create-database!
  "Creates the analytics database and transacts the schema."
  []
  (let [uri (analytics-uri)]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn schema)
      conn)))

(defn get-connection
  "Returns a connection to the analytics database."
  []
  (d/connect (analytics-uri)))

(defn get-db
  "Returns the current analytics database value."
  []
  (d/db (get-connection)))

(defn- convert-instant
  "Converts java.time.Instant to java.util.Date for Datomic compatibility."
  [x]
  (if (instance? Instant x) (Date/from x) x))

(defn record!
  "Transacts analytics events, converting Instants to Dates."
  [tx-data]
  (let [conn (get-connection)]
    @(d/transact
       conn
       (mapv (fn [m] (into {} (map (fn [[k v]] [k (convert-instant v)])) m)) tx-data))))

(defn consume-nonce!
  "CAS-stamp the magic-link record for `nonce` as verified.

  Returns true if this call was the first to consume the nonce; false on
  replay, malformed nonce, or unknown nonce. The CAS expects
  `:magic-link/verified-at` to be currently unset; a replay finds it
  already set, the CAS fails, the transact throws, and we return false.
  This is the one-shot anti-replay primitive. It lives HERE because the
  nonces are this database's data — the handler orchestrates, the owning
  layer transacts."
  [nonce verified-at]
  (try
    (let [conn (get-connection)
          eid (d/q '[:find ?e . :in $ ?n :where [?e :magic-link/nonce ?n]] (d/db conn) nonce)]
      (when eid
        @(d/transact
           conn
           [[:db.fn/cas eid :magic-link/verified-at nil (convert-instant verified-at)]])
        true))
    (catch Exception _e false)))
