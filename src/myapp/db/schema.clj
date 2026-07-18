(ns myapp.db.schema
  "Datomic schema definitions.
  All entity types and their attributes are defined here as transaction data,
  installed on database creation (see myapp.db.core/create-database!).")

(def user-schema
  "Schema for user accounts.

  Authentication is passwordless (magic links), so there is no password
  attribute — a user is just an email plus some bookkeeping."
  [{:db/ident :user/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique user ID"}
   {:db/ident :user/email
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "User email address (unique)"}
   {:db/ident :user/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the user account was created"}
   {:db/ident :user/active?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether the user account is active"}
   {:db/ident :user/terms-accepted-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the user accepted the terms (gates access — see wrap-terms-accepted)"}
   {:db/ident :user/activity-seen-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When the user last viewed their activity feed — the d/since cursor."}

   {:db/ident :user/display-name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Public display name shown as the author on recipes"}])

(def recipe-schema
  "Schema for the recipe-versioning domain — \"Git for recipes\".

  A recipe is a single entity. EDITS are ordinary Datomic transactions, so the
  full version history of a recipe comes for free: `d/history` enumerates the
  transactions that touched it, `d/as-of` reconstructs its state at any point,
  and a diff is just two `as-of` pulls compared field-by-field.

  A FORK is a brand-new recipe entity whose `:recipe/forked-from` ref points at
  the recipe it was copied from. Walking that ref upward yields the lineage
  (\"this carbonara descends from N ancestors\"); it spans users, since forking
  someone else's recipe is the whole point.

  `:recipe/ingredients` and `:recipe/steps` are stored as newline-separated
  text precisely so that version-to-version diffs are line diffs, exactly like
  source control."
  [{:db/ident :recipe/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/doc "Unique recipe ID (stable across edits; a fork gets a new one)"}
   {:db/ident :recipe/user
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning user. Only the owner may edit or delete (tenant isolation)."}
   {:db/ident :recipe/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/fulltext true
    :db/doc "Recipe title"}
   {:db/ident :recipe/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Short description / headnote, rendered as Markdown"}
   {:db/ident :recipe/servings
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Number of servings the quantities are written for"}
   {:db/ident :recipe/ingredients
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Ingredients, one per line (newline-separated so versions line-diff)"}
   {:db/ident :recipe/steps
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Method, one step per line (newline-separated so versions line-diff)"}
   {:db/ident :recipe/forked-from
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The recipe this one was forked from. Absent on originals."}
   {:db/ident :recipe/position
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc
    "Display order within the owner's dashboard (ascending). User-controlled
     via drag-reorder. Deliberately excluded from the version timeline (see
     myapp.recipe.core/versioned-attrs) so reordering never shows up as a
     content change. Absent until the user first reorders; unset recipes sort
     after positioned ones by recency."}
   {:db/ident :recipe/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When this recipe (this fork) was first created"}
   {:db/ident :recipe/updated-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "When this recipe was last edited"}])

(def schema
  "The full schema, transacted on database creation.
  Order matters only in that referenced idents must exist; these are all
  independent attribute installs, so a single concatenated vector is fine."
  (vec (concat user-schema recipe-schema)))
