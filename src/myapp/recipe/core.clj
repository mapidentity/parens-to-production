(ns myapp.recipe.core
  "The recipe-versioning domain — \"Git for recipes\".

  The interesting properties all fall out of Datomic's immutability:

  - **Versions** of a recipe are just the transactions that touched it.
    `version-history` enumerates them via `d/history`; `version-as-of`
    reconstructs the recipe at any past basis-t via `d/as-of`.
  - **Diffs** between two versions are a line diff (`line-diff`) of the
    `:recipe/ingredients` / `:recipe/steps` text pulled at each basis-t —
    which is exactly why those fields are stored newline-separated.
  - **Forks** are new entities carrying a `:recipe/forked-from` ref;
    `lineage` walks that ref to the root original and `forks` finds the
    children. A fork may point at another user's recipe — that's the point.

  Reads are public (anyone may browse and fork any recipe). Mutations
  (`update!`, `delete!`) are owner-only, enforced through the tenant-isolation
  helpers in `myapp.db.core` (the conventional `:recipe/user` owner ref)."
  (:require
    [clojure.string :as str]
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.time :as time])
  (:import
    [java.util UUID]))

(set! *warn-on-reflection* true)

(def pull-pattern
  "Pull pattern for a fully-rendered recipe.
  Includes its owner and (if any) the recipe it was forked from."
  [:db/id :recipe/id :recipe/title :recipe/description :recipe/servings
   :recipe/ingredients :recipe/steps :recipe/position
   :recipe/created-at :recipe/updated-at
   {:recipe/user [:db/id :user/email :user/display-name]}
   {:recipe/forked-from [:recipe/id :recipe/title {:recipe/user [:user/display-name]}]}])

(def ^:private versioned-attrs
  "The content attributes whose changes constitute a new VERSION of a recipe.
  `version-history` counts a transaction as a version only when it touched one
  of these — so bookkeeping-only edits (notably :recipe/position from a
  dashboard reorder) never pollute the timeline or show up as an empty diff."
  [:recipe/title :recipe/description :recipe/servings
   :recipe/ingredients :recipe/steps :recipe/forked-from])

(defn lines
  "Split newline-separated text into a vector of non-blank, right-trimmed lines."
  [s]
  (if (str/blank? s)
    []
    (->> (str/split-lines s)
         (map str/trimr)
         (remove str/blank?)
         vec)))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn recipe-by-id
  "Pull the current state of the recipe with `:recipe/id` = `id` (a UUID), or nil.

  Resolves the eid first so a missing/retracted recipe returns nil rather than
  the `{:db/id nil}` that a dangling lookup-ref pull would yield."
  [db id]
  (when id
    (when-let [eid (d/entid db [:recipe/id id])]
      (db/pull* db pull-pattern eid))))

(defn all-recipes
  "All recipes, most-recently-updated first. Public browse list."
  [db]
  (->> (d/q '[:find [?e ...] :where [?e :recipe/id]] db)
       (map #(db/pull* db pull-pattern %))
       (sort-by :recipe/updated-at #(compare %2 %1))
       vec))

(defn- dashboard-order
  "Comparator for the owner's dashboard.
  Explicit :recipe/position ascending, then (for recipes the user hasn't
  reordered yet) most-recently-updated first. Positioned recipes always sort
  ahead of unpositioned ones."
  [a b]
  (let [pa (:recipe/position a)
        pb (:recipe/position b)]
    (cond
      (and pa pb) (compare (long pa) (long pb))
      pa -1
      pb 1
      :else (compare (:recipe/updated-at b) (:recipe/updated-at a)))))

(defn recipes-by-user
  "Recipes owned by `user-eid`, in the owner's chosen dashboard order (see `dashboard-order`)."
  [db user-eid]
  (->> (d/q '[:find [?e ...] :in $ ?u :where [?e :recipe/user ?u]] db user-eid)
       (map #(db/pull* db pull-pattern %))
       (sort dashboard-order)
       vec))

(defn owned-by?
  "True when `recipe` (a pulled map) is owned by `user-eid`."
  [recipe user-eid]
  (and user-eid (= user-eid (get-in recipe [:recipe/user :db/id]))))

;; ---------------------------------------------------------------------------
;; Lineage (the fork graph)
;; ---------------------------------------------------------------------------

(defn lineage
  "Ancestors of recipe `id`, immediate parent first, up to the root original.
  Empty vector for an original recipe. Spans owners."
  [db id]
  (loop [cur (recipe-by-id db id)
         acc []]
    (if-let [parent-id (get-in cur [:recipe/forked-from :recipe/id])]
      (let [parent (recipe-by-id db parent-id)]
        ;; Guard against a pathological cycle (can't happen via the UI, but a
        ;; hand-crafted transaction could): stop if we loop back on ourselves.
        (if (or (nil? parent) (some #(= (:recipe/id parent) (:recipe/id %)) acc))
          acc
          (recur parent (conj acc parent))))
      acc)))

(defn forks
  "Direct forks (children) of recipe `id`, oldest first."
  [db id]
  (if-let [eid (d/entid db [:recipe/id id])]
    (->> (d/q '[:find [?c ...] :in $ ?e :where [?c :recipe/forked-from ?e]] db eid)
         (map #(db/pull* db pull-pattern %))
         (sort-by :recipe/created-at)
         vec)
    []))

;; ---------------------------------------------------------------------------
;; Version history (time travel over a single recipe)
;; ---------------------------------------------------------------------------

(defn version-history
  "Every version of recipe `id`, oldest first, reconstructed from Datomic history.
  Each entry is `{:tx <tx-eid> :t <basis-t> :instant <Instant>
  :recipe <state as-of that tx>}`. Returns nil if the recipe doesn't exist."
  [db id]
  (when-let [eid (d/entid db [:recipe/id id])]
    (let [h (d/history db)
          txs (->> (d/q '[:find ?tx ?inst
                          :in $ ?e [?a ...]
                          :where
                          ;; Only transactions that asserted a CONTENT attribute
                          ;; count as a version — a position-only reorder does not.
                          [?e ?a _ ?tx true]
                          [?tx :db/txInstant ?inst]]
                        h
                        eid
                        versioned-attrs)
                   ;; Sort by basis-t, not :db/txInstant — two edits in the same
                   ;; millisecond tie on the instant, which would make version
                   ;; order (and "latest") non-deterministic. t is monotonic.
                   (sort-by (fn [[tx _]] (d/tx->t tx))))]
      (mapv
        (fn [[tx inst]]
          {:tx tx
           :t (d/tx->t tx)
           :instant (db/as-instant inst)
           :recipe (db/pull* (d/as-of db tx) pull-pattern eid)})
        txs))))

(defn version-as-of
  "The state of recipe `id` as of basis point `t` (a basis-t, tx-eid, or Instant/Date).
  nil if the recipe doesn't exist."
  [db id t]
  (when-let [eid (d/entid db [:recipe/id id])]
    (db/pull* (d/as-of db t) pull-pattern eid)))

;; ---------------------------------------------------------------------------
;; Line diff (LCS) — the version-to-version comparison
;; ---------------------------------------------------------------------------

(defn- lcs-suffix-table
  "Map from [i j] to the length of the longest common subsequence of the suffixes a[i..] and b[j..].
  Missing entries (the boundary row/column) read as 0 via the lookup default."
  [a b]
  (let [n (count a)
        m (count b)]
    ;; Primitive-long index loops (not reduce-over-range) so the arithmetic
    ;; stays unboxed — the strict build (ch2) fails on boxed math.
    (loop [i (dec n)
           dp (transient {})]
      (if (neg? i)
        (persistent! dp)
        (recur
          (dec i)
          (loop [j (dec m)
                 dp dp]
            (if (neg? j)
              dp
              (recur
                (dec j)
                (assoc!
                  dp
                  [i j]
                  (if (= (nth a i) (nth b j))
                    (inc (long (get dp [(inc i) (inc j)] 0)))
                    (max (long (get dp [(inc i) j] 0)) (long (get dp [i (inc j)] 0)))))))))))))

(defn line-diff
  "A git-style line diff of two newline-separated strings.

  Returns a vector of `{:op :ctx|:add|:del :text <line>}` in display order,
  computed from a longest-common-subsequence of the lines: shared lines are
  `:ctx`, lines only in `new-text` are `:add`, lines only in `old-text` are
  `:del`. Pure data → data."
  [old-text new-text]
  (let [a (lines old-text)
        b (lines new-text)
        n (count a)
        m (count b)
        dp (lcs-suffix-table a b)]
    (loop [i 0
           j 0
           acc (transient [])]
      (cond
        (and (< i n) (< j m) (= (nth a i) (nth b j)))
        (recur
          (inc i)
          (inc j)
          (conj!
            acc
            {:op :ctx
             :text (nth a i)}))

        (and (< j m) (or (= i n) (>= (long (dp [i (inc j)] 0)) (long (dp [(inc i) j] 0)))))
        (recur
          i
          (inc j)
          (conj!
            acc
            {:op :add
             :text (nth b j)}))

        (< i n)
        (recur
          (inc i)
          j
          (conj!
            acc
            {:op :del
             :text (nth a i)}))

        :else
        (persistent! acc)))))

(defn diff
  "Field-level diff between two recipe states (pulled maps).

  Scalar fields (`title`, `description`, `servings`) report `{:changed? bool
  :old .. :new ..}`; the list fields (`ingredients`, `steps`) report a
  `line-diff`. `:changed?` at the top level is true if anything differs."
  [old-recipe new-recipe]
  (let [scalar (fn [k]
                 (let [o (get old-recipe k)
                       nw (get new-recipe k)]
                   {:changed? (not= o nw)
                    :old o
                    :new nw}))
        ingredients (line-diff (:recipe/ingredients old-recipe) (:recipe/ingredients new-recipe))
        steps (line-diff (:recipe/steps old-recipe) (:recipe/steps new-recipe))
        any-line-change? (fn [d] (some #(not= :ctx (:op %)) d))]
    {:title (scalar :recipe/title)
     :description (scalar :recipe/description)
     :servings (scalar :recipe/servings)
     :ingredients ingredients
     :steps steps
     :changed? (boolean
                 (or
                   (not= (:recipe/title old-recipe) (:recipe/title new-recipe))
                   (not= (:recipe/description old-recipe) (:recipe/description new-recipe))
                   (not= (:recipe/servings old-recipe) (:recipe/servings new-recipe))
                   (any-line-change? ingredients)
                   (any-line-change? steps)))}))

;; ---------------------------------------------------------------------------
;; Mutations
;; ---------------------------------------------------------------------------

(defn- next-position
  "One past the highest :recipe/position among `user-eid`'s recipes (0 if none).
  So a freshly created recipe appends to the end of the owner's dashboard."
  [db user-eid]
  (let [m (d/q
            '[:find (max ?p) .
              :in $ ?u
              :where [?r :recipe/user ?u] [?r :recipe/position ?p]]
            db
            user-eid)]
    (if m (inc (long m)) 0)))

(defn create!
  "Create a new recipe owned by `user-eid`. Returns the new `:recipe/id` (UUID).
  Pass `:forked-from-eid` to record provenance (used by `fork!`)."
  [conn user-eid {:keys [title description servings ingredients steps forked-from-eid]}]
  (let [id (UUID/randomUUID)
        now (time/now)
        position (next-position (d/db conn) user-eid)]
    @(db/transact* conn
       [(cond-> {:db/id "new-recipe"
                 :recipe/id id
                 :recipe/user user-eid
                 :recipe/title (or title "Untitled recipe")
                 :recipe/description (or description "")
                 :recipe/servings (long (or servings 1))
                 :recipe/ingredients (or ingredients "")
                 :recipe/steps (or steps "")
                 :recipe/position position
                 :recipe/created-at now
                 :recipe/updated-at now}
          forked-from-eid (assoc :recipe/forked-from
                            forked-from-eid))])
    id))

(defn reorder!
  "Set explicit :recipe/position on `ids` (UUIDs) in the given order, for recipes owned by `user-eid`.
  Ids not owned by the user are silently skipped (tenant
  isolation). Does NOT bump :recipe/updated-at — a reorder is not a content edit
  and must not create a version. Returns true."
  [conn user-eid ids]
  (let [db (d/db conn)
        tx (->> ids
                (map-indexed (fn [i id]
                               (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
                                 {:db/id eid
                                  :recipe/position (long i)})))
                (remove nil?)
                vec)]
    (when (seq tx) @(db/transact* conn tx))
    true))

(defn move!
  "Move recipe `id` one step `dir` (:up or :down) within its owner's ordered dashboard list, owner-checked.
  This is the no-JS reorder path (the up/down
  form buttons); it normalises every recipe's position as a side effect via
  `reorder!`. Returns true."
  [conn user-eid id dir]
  (let [db (d/db conn)
        ids (mapv :recipe/id (recipes-by-user db user-eid))
        i (long (.indexOf ^java.util.List ids id))
        j (long
            (case dir
              :up (dec i)
              :down (inc i)
              i))]
    (when (and (nat-int? i) (nat-int? j) (< j (count ids)) (not= i j))
      (reorder!
        conn
        user-eid
        (assoc ids
          i (nth ids j)
          j (nth ids i))))
    true))

(defn fork!
  "Fork the recipe `source-id` (any owner's) into a new recipe owned by `user-eid`.
  Copies the current fields and records `:recipe/forked-from`.
  Returns the new `:recipe/id`, or nil if the source doesn't exist."
  [conn user-eid source-id]
  (let [db (d/db conn)
        src (recipe-by-id db source-id)
        src-eid (d/entid db [:recipe/id source-id])]
    (when src
      (create!
        conn
        user-eid
        {:title (:recipe/title src)
         :description (:recipe/description src)
         :servings (:recipe/servings src)
         :ingredients (:recipe/ingredients src)
         :steps (:recipe/steps src)
         :forked-from-eid src-eid}))))

(defn update!
  "Apply `changes` (a subset of the `:recipe/*` content keys) to recipe `id`, if owned by `user-eid`.
  Bumps `:recipe/updated-at`. Returns true on success,
  nil if the recipe is missing or not owned by the user."
  [conn user-eid id changes]
  (let [db (d/db conn)]
    (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
      @(db/transact* conn
         [(merge
            {:db/id eid
             :recipe/updated-at (time/now)}
            (select-keys
              changes
              [:recipe/title :recipe/description :recipe/servings
               :recipe/ingredients :recipe/steps]))])
      true)))

(defn delete!
  "Retract recipe `id` if owned by `user-eid`.
  Returns true on success, nil otherwise. (Forks keep their own copies —
  `:recipe/forked-from` simply dangles, which lineage tolerates.)"
  [conn user-eid id]
  (let [db (d/db conn)]
    (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
      @(db/transact* conn [[:db/retractEntity eid]])
      true)))

;; ---------------------------------------------------------------------------
;; Aggregates (admin dashboard)
;; ---------------------------------------------------------------------------

(defn total-recipes
  "Count of all recipes."
  [db]
  (or (d/q '[:find (count ?e) . :where [?e :recipe/id]] db) 0))

(defn total-forks
  "Count of recipes that were forked from another."
  [db]
  (or (d/q '[:find (count ?e) . :where [?e :recipe/forked-from]] db) 0))
