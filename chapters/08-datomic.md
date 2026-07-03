# Datomic for Your SaaS: Schema, Queries, and the java.time Bridge

Most SaaS applications reach for PostgreSQL or MySQL without a second thought. They are battle-tested, well-documented, and familiar. But for an application where the history of data matters -- think accounting, compliance, audit trails -- you quickly find yourself bolting on soft deletes, history tables, and temporal queries. You end up reimplementing, badly, what Datomic gives you out of the box.

Datomic treats data as immutable facts over time. Every transaction is recorded. Every past state of the database is queryable. Nothing is ever truly deleted -- it is retracted, and the retraction itself is a fact. For a SaaS whose product is the history of its data -- ours versions recipes -- this is not a nice-to-have. It is the correct data model.

> **If you are on PostgreSQL.** The cost of this chapter's choice, priced for the reader who cannot make it. Everything [the recipe-domain chapter](09-recipe-domain.md) will read out of Datomic for free, you build: version history becomes a `recipe_versions` table plus a write to it on every edit (application code or trigger -- both work, both can drift from the live row); "as of" becomes temporal tables where your tooling supports them, or timestamp predicates against the versions table; the stable version *address* (our basis-t in a URL) becomes a version id you mint and index; and the guarantee that the audit trail cannot disagree with the data -- because they are the same thing -- becomes a discipline your tests enforce instead of a property the store provides. What survives the swap in shape: the domain functions' signatures and the scenarios their tests pin down, the line-diff (pure data in, pure data out), the web layer's structure, and the tenancy waist -- `entid-owned` becomes the `WHERE user_id = ?` you refuse to omit. The trade in the other direction is real too: a mainstream database buys operational maturity, hosting everywhere, and a hiring pool. This book judges a history-shaped domain worth the niche store; your domain may judge differently, and now the bill is itemized.

Setting up Datomic in a Clojure SaaS application has four parts: the Peer library, schema design, a wrapper layer that bridges `java.time` and Datomic's `java.util.Date`, and a test fixture that gives you a fresh database per test.

## The Datomic peer library

Datomic offers different deployment models. We use the Peer library, where the application process itself contains the query engine. There is no separate query server to manage -- your app reads the storage backend directly and runs queries in-process; only writes pass through Datomic's transactor, a piece of the production topology named honestly below.

In `deps.edn`:

```clojure
{:deps {com.datomic/peer {:mvn/version "1.0.7491"}
        org.postgresql/postgresql {:mvn/version "42.7.10"}}}
```

The PostgreSQL driver is there because in production, Datomic Peer stores its data in a SQL database. But during development and testing, we use something much lighter.

> **Which Datomic is this?** `com.datomic/peer` is the artifact for **Datomic Pro** -- the on-prem product (it was the paid edition until Datomic became free in 2023, and Pro is the name it ships under now). The Peer model embeds the query engine in your application process and reads from a storage backend you run (here, PostgreSQL). The other current option is **Datomic Cloud**, an AWS-native deployment fronted by the *client* API (`com.datomic/client-cloud`) rather than the Peer library: queries run in a query group, your app talks to it over the wire, and there is no in-process Peer. We choose Pro/Peer here because it runs anywhere, has the in-memory backend that makes the per-test fixture below trivial, and keeps the query engine in-process where the time-travel reads this app leans on are cheapest. If you deploy on Cloud, the schema and query *shapes* in this chapter carry over, but the connection and transaction calls go through the client API instead.

## Two storage backends: memory and SQL

Datomic's connection URI determines the storage backend. This is one of its most practical features: the same code runs against a throwaway in-memory database during development and a durable SQL-backed database in production. No conditional logic, no test doubles for the database layer.

Here is how we configure it:

```clojure
;; resources/config.edn  (must be on the classpath — :paths includes "resources")
{:database-uri #profile {:dev  "datomic:mem://myapp-dev"
                         :prod #env "DATABASE_URI"}}
```

In development, `datomic:mem://myapp-dev` creates an in-memory database that disappears when the process stops. Fast startup, zero infrastructure. In production, the `DATABASE_URI` environment variable points to something like `datomic:sql://myapp?jdbc:postgresql://db-host:5432/datomic`, backed by PostgreSQL.

One honest word about what that URI implies, because no other chapter stops to explain it: production Datomic Pro is not just PostgreSQL. A **transactor** -- one small JVM process -- sits between every write and the storage, serializing transactions; the Peer reads storage directly and never queues behind it, but the transactor must be provisioned, supervised, and pointed at the same storage the peers read. Development never meets it (`datomic:mem` is transactor-free), which is exactly why it is worth naming here: it is a piece of production infrastructure the dev loop cannot rehearse. The [afterword](afterword.md)'s horizontal-scale caveat -- a single process against a single transactor -- is about exactly this piece.

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

## Schema design

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

Right now `schema` is just `user-schema`. In the [next chapter](09-recipe-domain.md), as the recipe domain takes shape, this same file gains a `recipe-schema` and `schema` becomes `(vec (concat user-schema recipe-schema))`; `user-schema` itself also picks up a `:user/display-name` attribute. The shape -- one `schema` var transacted at startup -- does not change; the contents grow.

Three decisions in this schema are worth drawing out:

**Namespaced attributes.** Every attribute is namespaced (`:user/email`, `:user/created-at`). In Datomic, attributes are global -- they exist at the database level, not the table level. Namespacing is how you organize them. An entity can have attributes from any namespace. This is more flexible than relational tables but demands discipline in naming.

**Identity attributes.** Both `:user/id` and `:user/email` are marked `:db.unique/identity`. This means they serve as lookup refs -- you can find an entity by either its UUID or its email. It also means that transacting a new entity with an existing email will *upsert* (merge into the existing entity) rather than throw a constraint violation. This is intentional behavior worth understanding early.

**Cardinality.** Every attribute declares `:db.cardinality/one` or `:db.cardinality/many`. There is no implicit default. `:db.cardinality/many` gives you a set-valued attribute -- useful for tags, roles, or any multi-valued relationship.

**Value types.** Datomic has a fixed set of value types: `:db.type/string`, `:db.type/long`, `:db.type/boolean`, `:db.type/instant`, `:db.type/uuid`, `:db.type/ref`, and others. The `:db.type/instant` type stores points in time, which brings us to a practical problem.

## The java.time bridge

The previous chapter settled the *source* of time -- one swappable clock behind `myapp.time`. This is the other half of the same story, and a deliberately separate one: the *type* of a timestamp once it crosses into the database. The two never mix, which is why they live in two layers.

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

## Wrapped API functions

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

One scoping note on `q*`: it assumes a *relation* result -- a set of tuples, the shape a `:find ?e ?email` query returns -- because that is what the application's reads ask for. A scalar find (`:find ?e .`) returns a bare value and a collection find (`:find [?e ...]`) a flat vector, neither of which is a set of tuples; those go straight through `d/q` rather than `q*` (you will see exactly that a few queries down, where a scalar lookup is written against `d/q` directly). The wrapper earns its place for the relation case the app overwhelmingly uses; reach past it for the other find shapes.

Notice that `transact*` returns a future (just like `d/transact`). You deref it with `@` when you need to wait for the transaction to complete. The `(time/now)` it carries is the clock wrapper from [the previous chapter](07-time-clock.md) -- the single source of "now" whose output is the `java.time.Instant` the bridge below converts:

```clojure
@(db/transact* conn
   [{:user/id (java.util.UUID/randomUUID)
     :user/email "alice@example.com"
     :user/created-at (time/now)
     :user/active? true}])
```

The `time/now` value (a `java.time.Instant`) gets transparently converted to a `Date` before Datomic sees it. When you later pull this entity, the `Date` comes back as an `Instant`.

## Query patterns

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

Read the `:where` clause against the fact shape above: `[?e :user/email ?email]` is `[entity :user/email value]` with logic variables in the entity and value slots. It matches every stored fact whose attribute is `:user/email`, binding `?e` to the entity id and `?email` to the value -- and because `:in` already binds `?email` to the address you pass (alongside `$`, the database itself), the pattern is pinned to that one user, so `?e` comes back as their entity id.

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

## Isolated test databases

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

The fixture pays for itself in two of its four moves. The uniquely-named database (`System/nanoTime` in the URI) means no test ever inherits a leftover `datomic:mem` database from an earlier run in the same JVM, and rebinding `db/get-connection` is what points *all* application code -- not just code that happens to take a connection parameter -- at the test database for the fixture's extent. The other two moves are the obvious bookends: install the schema on the way in, delete the database on the way out.

Using it in a test namespace is one line:

```clojure
(ns myapp.db.core-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.test-helpers :as h]
    [myapp.time :as time])
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

These tests verify the full round trip: write with `java.time.Instant`, read back with `java.time.Instant`, with Datomic storing `java.util.Date` internally. The test suite never touches `java.util.Date` -- the bridge is invisible.

Each test gets its own database, runs in milliseconds, and cleans up after itself. No Docker containers, no test database provisioning, no cleanup scripts.

## Schema tests

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

The conversion wrappers are thin -- about 40 lines. They are not a framework or an ORM; they solve one specific problem, the `Date`/`Instant` mismatch, and stay out of the way for everything else. You still write Datalog directly, navigate with `d/entity`, and transact plain maps through `transact*`. Datomic's API is good; it just needs this one bridge -- and with it in place, the chapters that follow build domain logic on top without ever seeing a `java.util.Date`. (The same `myapp.db.core` namespace later grows a second, unrelated concern: a tenant-isolation layer -- the `*-owned` helpers that enforce per-user data access -- which we reach for once there are users to isolate, in [the progressive-enhancement chapter](19-progressive-enhancement.md). It is not part of the bridge, so it is not in that 40-line count.)
