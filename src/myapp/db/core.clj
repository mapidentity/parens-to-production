(ns myapp.db.core
  "Datomic database access layer.
  Wraps Datomic API with automatic conversion between java.time.Instant and
  java.util.Date, since Datomic stores :db.type/instant as Date internally."
  (:require
    [datomic.api :as d]
    [myapp.config :as config]
    [myapp.db.schema :as schema])
  (:import
    [java.time Instant]
    [java.util Date]))

(set! *warn-on-reflection* true)

(defn as-instant
  "Coerce a Datomic instant value to java.time.Instant.

  Datomic stores `:db.type/instant` as java.util.Date internally but
  callers see either `java.util.Date` or `java.time.Instant` depending
  on driver version + read path (pull vs raw query, pulled-via-ref vs
  pulled-by-eid). This helper smooths the inconsistency so calling code
  doesn't have to branch.

  nil → nil. Anything else throws — silently returning the input would
  let callers chain `.atZone` on a Date and fail far from the source."
  ^Instant [v]
  (cond
    (nil? v) nil
    (instance? Instant v) v
    (instance? Date v) (.toInstant ^Date v)
    :else (throw
            (ex-info
              (str
                "Cannot coerce to Instant: "
                (some-> v
                        class
                        .getName))
              {:value v
               :type (class v)}))))

(defn db-uri
  "Returns the Datomic connection URI from config."
  []
  (config/get-config :database-uri))

(defn create-database!
  "Ensure database exists and schema is up-to-date.

  Idempotent on both axes: `d/create-database` is a no-op on existing
  databases, and Datomic schema transactions are no-ops for attributes
  already defined identically. New attributes appended to schema/schema
  are picked up automatically on app startup — no manual REPL transact
  step required (which was a real dogfood-found dev-env papercut)."
  []
  (let [uri (db-uri)]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn schema/schema)
      conn)))

(defn get-connection
  "Get database connection."
  []
  (d/connect (db-uri)))

(defn get-db
  "Get current database value."
  []
  (d/db (get-connection)))

(defn convert-instants
  "Recursively convert Instant to Date (for writing to Datomic)."
  [x]
  (cond (instance? Instant x) (Date/from x)
        (map? x) (into {} (map (fn [[k v]] [k (convert-instants v)])) x)
        (vector? x) (mapv convert-instants x)
        (set? x) (set (map convert-instants x))
        :else x))

(defn convert-dates
  "Recursively convert Date to Instant (for reading from Datomic)."
  [x]
  (cond (instance? Date x) (.toInstant ^Date x)
        (map? x) (into {} (map (fn [[k v]] [k (convert-dates v)])) x)
        (vector? x) (mapv convert-dates x)
        (set? x) (set (map convert-dates x))
        :else x))

(defn pull*
  "Like d/pull but converts Date values to Instant in the result."
  [db pattern eid]
  ;; This is the canonical wrapper — raw d/pull is allowed here
  ;; because the conversion happens on the next line.
  (convert-dates #_:clj-kondo/ignore (d/pull db pattern eid)))

(defn pull-many*
  "Like d/pull-many but converts Date values to Instant in results."
  [db pattern eids]
  (mapv convert-dates (d/pull-many db pattern eids)))

(defn transact*
  "Like d/transact but converts Instant values to Date in tx-data."
  [conn tx-data]
  (d/transact conn (mapv convert-instants tx-data)))

(defn q*
  "Like d/q but converts Date values to Instant in result tuples."
  [query & args]
  (let [results (apply d/q query args)]
    (into
      #{}
      (map (fn [tuple] (mapv (fn [v] (if (instance? Date v) (.toInstant ^Date v) v)) tuple)))
      results)))

;; -------------------------------------------------------------------------
;; Tenant isolation primitives
;;
;; Every domain entity in this system carries an owning user via a
;; conventional `:<ns>/user` ref (e.g. :booking/user, :asset/user).
;; The helpers below are the ONLY safe way to convert an entity id
;; that came from outside the session — a path param, query param,
;; form field, htmx hidden input — into a Datomic eid. They refuse to
;; return foreign entities, returning nil instead, so the handler can
;; respond with 404 indistinguishably from "not found".
;;
;; **Rule:** if you find yourself writing `(d/entid db [:foo/id …])` or
;; `(d/pull db pattern [:foo/id …])` in a request handler, you are
;; bypassing tenant isolation — switch to `entid-owned` / `pull-owned`.
;; -------------------------------------------------------------------------

(defn- infer-user-attr
  "Derive the conventional `:<ns>/user` ref attribute from a lookup-ref.
  Example: [:booking/id …] → :booking/user.

  Throws if the lookup-ref's attribute has no namespace. Reaching this
  branch means a programmer error (a non-namespaced attr was passed),
  not a runtime condition — fail loudly rather than silently returning
  `:user` and then mismatching every entity's owner check."
  [lookup-ref]
  (let [attr (first lookup-ref)
        ns-str (namespace attr)]
    (when-not ns-str
      (throw
        (ex-info "infer-user-attr requires a namespaced lookup-ref attr" {:lookup-ref lookup-ref})))
    (keyword ns-str "user")))

(defn- entity-owner-eid
  "Return the :db/id of the entity at `eid`'s `user-attr` ref, or nil."
  [db eid user-attr]
  ;; Raw d/pull is fine here: pulling only :db/id (no date attrs) and the
  ;; ref-only result has no Date leakage. This is the only safe place to
  ;; bypass pull*; everywhere else uses the wrapper.
  (-> #_:clj-kondo/ignore
      (d/pull db [{user-attr [:db/id]}] eid)
      user-attr
      :db/id))

(defn entid-owned
  "Resolve a lookup-ref to an entity id, only if owned by `user-eid`.

  Returns nil for both 'no such entity' and 'entity exists but is owned
  by another user' — those are indistinguishable to the caller, so a
  handler can 404 in either case without leaking existence.

  `user-attr` defaults to the conventional `:<ns>/user` derived from the
  lookup-ref's attribute (e.g. [:booking/id …] → :booking/user). Pass an
  explicit `user-attr` only for entities that don't follow the convention.

  Use everywhere a non-session-derived id is converted to an eid."
  ([db user-eid lookup-ref] (entid-owned db user-eid lookup-ref (infer-user-attr lookup-ref)))
  ([db user-eid lookup-ref user-attr]
   (when-let [eid (d/entid db lookup-ref)]
     (when (= user-eid (entity-owner-eid db eid user-attr)) eid))))

(defn pull-owned
  "Pull an entity but return nil unless owned by `user-eid`.

  Like `d/pull` with Date→Instant conversion. Same `user-attr` defaulting
  as `entid-owned`. Use for read paths that prefill a form or render
  details from an externally-supplied id."
  ([db user-eid pattern lookup-ref]
   (pull-owned db user-eid pattern lookup-ref (infer-user-attr lookup-ref)))
  ([db user-eid pattern lookup-ref user-attr]
   (when-let [eid (entid-owned db user-eid lookup-ref user-attr)]
     (pull* db pattern eid))))

(defn eid-owned?
  "Return true if `eid` is owned by `user-eid` per `user-attr`.

  Predicate variant for raw-eid call sites where the lookup-ref form
  isn't available (e.g. an eid arriving from a numeric path-param).
  Prefer `entid-owned`/`pull-owned` when you have a lookup-ref."
  [db user-eid eid user-attr]
  (= user-eid (entity-owner-eid db eid user-attr)))

(defn assert-owned!
  "Throw an ex-info tagged `:error :tenancy/forbidden` if `eid` is foreign.

  Belt-and-suspenders precondition for mutation functions that take a
  raw eid: even if the caller resolved via `entid-owned`, this guards
  against future refactors that pass an eid through unchecked.

  `user-attr` MUST be supplied — there's no lookup-ref to infer from."
  [db user-eid eid user-attr]
  (when-not (eid-owned? db user-eid eid user-attr)
    (throw
      (ex-info
        "Forbidden: cross-tenant access"
        {:error :tenancy/forbidden
         :eid eid
         :user-attr user-attr
         :user-eid user-eid}))))

(comment
  ;; REPL helpers
  (def conn
    (create-database!))
  (def db
    (d/db conn))
  ;; Query all users
  (d/q '[:find ?e ?email :where [?e :user/email ?email]] db))
