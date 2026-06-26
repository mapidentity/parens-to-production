# Datomic for Your SaaS: Schema, Queries, and the java.time Bridge


Most SaaS applications reach for PostgreSQL or MySQL without a second thought. They are battle-tested, well-documented, and familiar. But for an application where the history of data matters -- think accounting, compliance, audit trails -- you quickly find yourself bolting on soft deletes, history tables, and temporal queries. You end up reimplementing, badly, what Datomic gives you out of the box.

Datomic treats data as immutable facts over time. Every transaction is recorded. Every past state of the database is queryable. Nothing is ever truly deleted -- it is retracted, and the retraction itself is a fact. For a SaaS that handles financial data, this is not a nice-to-have. It is the correct data model.

In this post, we will set up Datomic in a Clojure SaaS application: the Peer library, schema design, a wrapper layer that bridges java.time and Datomic's java.util.Date, and a test fixture that gives you a fresh database per test.

## The Datomic Peer Library

Datomic offers different deployment models. We use the Peer library, where the application process itself contains the query engine. There is no separate query server to manage -- your app connects directly to the storage backend and runs queries in-process.

In `deps.edn`:

```clojure
{:deps {com.datomic/peer {:mvn/version "1.0.7491"}
        org.postgresql/postgresql {:mvn/version "42.7.10"}}}
```

The PostgreSQL driver is there because in production, Datomic Peer stores its data in a SQL database. But during development and testing, we use something much lighter.

> **Which Datomic is this?** `com.datomic/peer` is the artifact for **Datomic Pro** -- the on-prem product (it was the paid edition until Datomic became free in 2023, and Pro is the name it ships under now). The Peer model embeds the query engine in your application process and reads from a storage backend you run (here, PostgreSQL). The other current option is **Datomic Cloud**, an AWS-native deployment fronted by the *client* API (`com.datomic/client-cloud`) rather than the Peer library: queries run in a query group, your app talks to it over the wire, and there is no in-process Peer. We choose Pro/Peer here because it runs anywhere, has the in-memory backend that makes the per-test fixture below trivial, and keeps the query engine in-process where the time-travel reads this app leans on are cheapest. If you deploy on Cloud, the schema and query *shapes* in this chapter carry over, but the connection and transaction calls go through the client API instead.

## Two Storage Backends: Memory and SQL

Datomic's connection URI determines the storage backend. This is one of its most practical features: the same code runs against a throwaway in-memory database during development and a durable SQL-backed database in production. No conditional logic, no test doubles for the database layer.

Here is how we configure it:

```clojure
;; resources/config.edn  (must be on the classpath — :paths includes "resources")
{:database-uri #profile {:dev  "datomic:mem://myapp-dev"
                         :prod #env "DATABASE_URI"}}
```

In development, `datomic:mem://myapp-dev` creates an in-memory database that disappears when the process stops. Fast startup, zero infrastructure. In production, the `DATABASE_URI` environment variable points to something like `datomic:sql://myapp?jdbc:postgresql://db-host:5432/datomic`, backed by PostgreSQL.

The code that connects to the database does not know or care which backend it is talking to:

```clojure
(ns myapp.db.core
  (:require
    [datomic.api :as d]
    [myapp.config :as config]
    [myapp.db.schema :as schema])
  (:import
    [java.time Instant]
    [java.util Date]))

(defn db-uri []
  (config/get-config :database-uri))

(defn create-database! []
  (let [uri (db-uri)]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn schema/schema)
      conn)))

(defn get-connection []
  (d/connect (db-uri)))

(defn get-db []
  (d/db (get-connection)))
```

`create-database!` is safe to call repeatedly. `d/create-database` returns `false` rather than erroring when the database already exists, and the schema transaction behind it is itself idempotent -- Datomic no-ops a `:db/ident` that is already installed with the same definition -- so re-running it reinstalls nothing. (It is not a literal no-op: the schema is re-transacted each call; it simply has no effect.) It creates the database, connects, and transacts the schema. `get-db` returns an immutable database value: a snapshot of the database at a point in time. This is a key Datomic concept. The database value you get from `(d/db conn)` never changes. You can pass it around, query it later, and the results will always be consistent with that moment.

## Schema Design

Datomic schema is data. You define attributes as maps and transact them into the database like any other data. There are no DDL statements, no migration files, no schema versioning tools. You add attributes by transacting new attribute definitions.

Here is the user schema:

```clojure
(ns myapp.db.schema
  "Datomic schema definitions.
  All entity types and their attributes are defined here as transaction data,
  installed on database creation.")

(def user-schema
  [{:db/ident       :user/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "Unique user ID"}
   {:db/ident       :user/email
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc         "User email address (unique)"}
   {:db/ident       :user/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the user account was created"}
   {:db/ident       :user/active?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "Whether the user account is active"}
   {:db/ident       :user/terms-accepted-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the user accepted terms of service (gates access)"}])

(def schema
  "The database schema. Grows as the domain does."
  user-schema)
```

Right now `schema` is just `user-schema`. As the recipe domain takes shape later in the book, this same file gains a `recipe-schema` and `schema` becomes `(vec (concat user-schema recipe-schema))`; `user-schema` itself also picks up a `:user/display-name` attribute. The shape -- one `schema` var transacted at startup -- does not change; the contents grow.

A few things to note about this design:

**Namespaced attributes.** Every attribute is namespaced (`:user/email`, `:user/created-at`). In Datomic, attributes are global -- they exist at the database level, not the table level. Namespacing is how you organize them. An entity can have attributes from any namespace. This is more flexible than relational tables but demands discipline in naming.

**Identity attributes.** Both `:user/id` and `:user/email` are marked `:db.unique/identity`. This means they serve as lookup refs -- you can find an entity by either its UUID or its email. It also means that transacting a new entity with an existing email will *upsert* (merge into the existing entity) rather than throw a constraint violation. This is intentional behavior worth understanding early.

**Cardinality.** Every attribute declares `:db.cardinality/one` or `:db.cardinality/many`. There is no implicit default. `:db.cardinality/many` gives you a set-valued attribute -- useful for tags, roles, or any multi-valued relationship.

**Value types.** Datomic has a fixed set of value types: `:db.type/string`, `:db.type/long`, `:db.type/boolean`, `:db.type/instant`, `:db.type/uuid`, `:db.type/ref`, and others. The `:db.type/instant` type stores points in time, which brings us to a practical problem.

## The java.time Bridge

Datomic's `:db.type/instant` stores `java.util.Date` internally -- and still does today; this is not deprecated behavior we are working around, it is the current contract. It dates from Datomic's origins, when `java.util.Date` was the standard JVM date type. Modern Java code uses `java.time.Instant`, which is immutable, thread-safe, and generally superior, so the value a query hands back is the one place the older type would otherwise leak into our code.

You do not want `java.util.Date` leaking into your application code. The solution is a thin conversion layer that translates automatically at the boundary. These functions live in the same `myapp.db.core` namespace shown above (whose `:import` already pulls in `java.time.Instant` and `java.util.Date`):

```clojure
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
```

Both functions walk data structures recursively. `convert-instants` is used on the way *in* (before transacting), converting every `java.time.Instant` to `java.util.Date`. `convert-dates` is used on the way *out* (after pulling or querying), converting every `java.util.Date` back to `java.time.Instant`.

The type hint `^Date` is there because we have `*warn-on-reflection*` enabled -- without it, the `.toInstant` call would resolve via reflection, which is both slow and triggers a compiler warning. (`convert-instants` needs no hint: `Date/from` is a static method, so there is nothing to reflect on.)

## Wrapped API Functions

With the conversion functions in place, we wrap Datomic's core API to apply them transparently:

```clojure
(defn transact*
  "Like d/transact but converts Instant values to Date in tx-data."
  [conn tx-data]
  (d/transact conn (mapv convert-instants tx-data)))

(defn pull*
  "Like d/pull but converts Date values to Instant in the result."
  [db pattern eid]
  (convert-dates (d/pull db pattern eid)))

(defn pull-many*
  "Like d/pull-many but converts Date values to Instant in results."
  [db pattern eids]
  (mapv convert-dates (d/pull-many db pattern eids)))

(defn q*
  "Like d/q but converts Date values to Instant in result tuples."
  [query & args]
  (let [results (apply d/q query args)]
    (into
      #{}
      (map (fn [tuple]
             (mapv (fn [v]
                     (if (instance? Date v)
                       (.toInstant ^Date v)
                       v))
                   tuple)))
      results)))
```

The naming convention -- `transact*`, `pull*`, `q*` -- signals that these are enhanced versions of the originals. The rest of the application uses these wrappers exclusively and never touches `java.util.Date`.

## Reading the Clock: `myapp.time`

The bridge handles the *type* of a timestamp. There is a second time problem, orthogonal to it: *where the current time comes from*. Scatter `(time/now)` through the codebase and you get code that is impossible to test deterministically -- every run sees a different "now" -- and a clock you cannot pin for a point-in-time feature. So we route every clock read through one namespace, `myapp.time`, whose underlying `java.time.Clock` is swappable:

```clojure
(ns myapp.time
  "Single source of truth for the current time. Everywhere else reads time
  through this namespace, never through Instant/now etc. directly."
  (:import [java.time Clock Instant LocalDate ZoneId]
           [java.util Date]))

(def ^ZoneId amsterdam (ZoneId/of "Europe/Amsterdam"))

(defonce ^:private clock-state (atom (Clock/system amsterdam)))
(defn current-clock ^Clock [] @clock-state)
(defn set-clock! [^Clock c] (reset! clock-state c))

(defmacro with-clock
  "Run body with c as the active clock, restoring the previous one after."
  [c & body]
  `(let [old# (current-clock)]
     (try (set-clock! ~c) ~@body (finally (set-clock! old#)))))

;; The ONLY allowed direct /now calls in the codebase live here.
(defn now      ^Instant [] (Instant/now (current-clock)))
(defn today    ^LocalDate [] (LocalDate/now (.withZone (current-clock) amsterdam)))
(defn now-date ^Date [] (Date/from (now)))            ; for raw Date interop

(defn fixed-clock
  "A clock frozen at `instant` — pin it in tests via (with-clock (fixed-clock i) …)."
  (^Clock [^Instant instant] (fixed-clock instant amsterdam))
  (^Clock [^Instant instant ^ZoneId zone] (Clock/fixed instant zone)))
```

`now` returns an `Instant` (which `transact*` then bridges to `Date`), `today` a `LocalDate` in the app's display timezone, `now-date` a `java.util.Date` for the rare raw-interop path. The point is the indirection: production runs on `Clock/system`, but a test wraps its body in `(with-clock (time/fixed-clock instant) …)` and every downstream `now`/`today` returns the pinned value -- so a token-expiry test or a "what did this look like last Tuesday" assertion is exact, not racy.

This is the wrapper the [build-hardening chapter](04-build-hardening.md)'s `lint` script enforces: its grep fails the build on a stray `Instant/now` (or `LocalDate/now`, `System/currentTimeMillis`, …) anywhere but this file, because clj-kondo's `:discouraged-var` rule only sees Clojure vars, not Java static calls. From here on, application code calls `(time/now)`, never `Instant/now` (or any other raw clock call) directly.

Notice that `transact*` returns a future (just like `d/transact`). You deref it with `@` when you need to wait for the transaction to complete:

```clojure
@(db/transact* conn
   [{:user/id (java.util.UUID/randomUUID)
     :user/email "alice@example.com"
     :user/created-at (time/now)
     :user/active? true}])
```

The `time/now` value (a `java.time.Instant`) gets transparently converted to a `Date` before Datomic sees it. When you later pull this entity, the `Date` comes back as an `Instant`.

## Query Patterns

Datomic queries use Datalog, a declarative query language. If you have used SQL, the mental model is different but not difficult. Where SQL stores data in tables of rows and columns, Datomic stores it as a single flat set of **entity-attribute-value** facts -- one row per fact, not per record. A user is not a row in a `users` table; it is a handful of facts that share an entity id: `[42 :user/email "alice@example.com"]`, `[42 :user/active? true]`, and so on. A query, then, is not a `SELECT` over tables with `JOIN`s on keys -- it is a set of *patterns* with logic variables (the `?`-prefixed symbols) that Datomic unifies against those facts. A join across "tables" is just two patterns sharing a variable, with no foreign keys to declare. Read the first query below with that in mind and the rest follow.

**Find an entity by attribute:**

```clojure
;; Find the entity ID for a user by email
(d/q '[:find ?e .
        :in $ ?email
        :where [?e :user/email ?email]]
     db
     "alice@example.com")
```

The `.` after `?e` in the `:find` clause is a scalar binding -- it returns the single value directly instead of wrapping it in a set of tuples. Without it, you would get `#{[12345]}` instead of `12345`.

**Pull entity data:**

```clojure
;; Pull specific attributes
(db/pull* db [:user/email :user/created-at :user/active?] eid)
;; => {:user/email "alice@example.com"
;;     :user/created-at #object[java.time.Instant "2025-06-15T12:00:00Z"]
;;     :user/active? true}

;; Pull all attributes
(db/pull* db '[*] eid)
```

**Query with results containing dates:**

```clojure
;; Find all users and their creation dates
(db/q* '[:find ?email ?created
          :where
          [?e :user/email ?email]
          [?e :user/created-at ?created]]
       db)
;; => #{["alice@example.com" #object[java.time.Instant ...]]}
```

Because we use `q*` instead of `d/q`, every `Date` in the result tuples is automatically converted to an `Instant`.

**Entity API for navigation:**

```clojure
;; Get a lazy, map-like view of an entity
(let [user (d/entity db [:user/email "alice@example.com"])]
  (:user/active? user))
;; => true
```

The entity API uses lookup refs -- `[:user/email "alice@example.com"]` -- to find entities by identity attributes. This is often more readable than a separate query when you already know the identity value.

One caveat: `d/entity` is raw Datomic, so it sits *outside* our conversion wrappers. Reading a non-temporal attribute like `:user/active?` is fine, but reading a `:db.type/instant` attribute through it gives you back a raw `java.util.Date` -- the very thing the wrapper layer exists to keep out of application code. So reach for `d/entity` only for the lazy, navigational reads where you are not pulling timestamps; when you need instant-typed attributes, go through `pull*`/`q*`, which convert. (If you wanted entity-style navigation *with* conversion, you would wrap it the same way -- `(convert-dates (into {} (d/entity db ref)))` -- but realizing the whole entity defeats the laziness, so we keep the two tools separate.)

## Isolated Test Databases

Testing database code well requires isolation. Each test should start with a clean database and leave no trace when it finishes. Datomic's in-memory backend makes this trivially fast:

```clojure
(ns myapp.test-helpers
  (:require
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.db.schema :as schema]))

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
```

This fixture does four things:

1. Creates a uniquely-named in-memory database using `System/nanoTime` to avoid collisions.
2. Installs the full schema.
3. Binds the connection to a dynamic var and redefines `db/get-connection` to return it, so all application code that calls `get-connection` gets the test database.
4. Deletes the database after the test completes.

Using it in a test namespace is one line:

```clojure
(ns myapp.db.core-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.test-helpers :as h])
  (:import
    [java.time Instant]))

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
```

These tests verify the full roundtrip: write with `java.time.Instant`, read back with `java.time.Instant`, with Datomic storing `java.util.Date` internally. The test suite never touches `java.util.Date` -- the bridge is invisible.

Each test gets its own database, runs in milliseconds, and cleans up after itself. No Docker containers, no test database provisioning, no cleanup scripts.

## Schema Tests

It is also worth testing that your schema installs correctly and that uniqueness constraints behave as expected:

```clojure
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
```

The email uniqueness test demonstrates an important Datomic behavior: when you transact an entity with a `:db.unique/identity` attribute that already exists, Datomic merges the new data into the existing entity rather than creating a duplicate. This is upsert semantics. The second transaction with the same email does not fail -- it updates the existing entity. Understanding this early prevents subtle bugs.

## What You Now Have

At this point, the database layer is complete:

- **Schema as data.** Attributes are defined as plain maps, transacted like any other data. Adding new attributes is a single transaction -- no migration framework, no schema version table.
- **Environment-agnostic connections.** The same code runs against `datomic:mem` in development and `datomic:sql` in production. The URI in config is the only difference.
- **Transparent date handling.** The `transact*`, `pull*`, and `q*` wrappers mean the rest of your application works exclusively with `java.time.Instant`. The `java.util.Date` conversion is invisible.
- **Fast, isolated tests.** Each test gets a fresh in-memory database that spins up in milliseconds and is deleted afterward. No shared state, no test ordering dependencies, no cleanup scripts.

The wrapper functions are thin -- about 40 lines total. They do not try to be a framework or an ORM. They solve one specific problem (the `Date`/`Instant` mismatch) and stay out of the way for everything else. You still write Datalog queries directly, use `d/entity` for navigation, and call `d/transact` (via `transact*`) with plain maps. Datomic's API is good; it just needs this one bridge.

Later chapters build on this foundation to implement domain logic -- creating users, handling authentication state, and querying across entity relationships.
