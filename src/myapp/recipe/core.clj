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
    [java.util Date UUID]))

(set! *warn-on-reflection* true)

(def pull-pattern
  "Pull pattern for a fully-rendered recipe.
  Includes its owner and (if any) the recipe it was forked from."
  [:db/id :recipe/id :recipe/title :recipe/description :recipe/servings
   :recipe/ingredients :recipe/steps :recipe/position
   :recipe/created-at :recipe/updated-at
   {:recipe/user [:db/id :user/email :user/display-name]}
   {:recipe/image [:db/id :upload/hash :upload/content-type :upload/width :upload/height]}
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
  "All recipes, most-recently-updated first.
  Used where the whole set is genuinely needed (the sitemap); the public browse
  is keyset-paginated (`browse-page`) instead of loading everything."
  [db]
  (->> (d/q '[:find [?e ...] :where [?e :recipe/id]] db)
       (map #(db/pull* db pull-pattern %))
       (sort-by :recipe/updated-at #(compare %2 %1))
       vec))

(def catalog-page-size
  "Recipes per public catalog page."
  12)

(defn browse-page
  "One page of the public catalog, alphabetical by title, keyset-paginated.

  `after` is nil (first page) or {:recipe/title <str> :eid <long>} — the keyset
  of the previous page's last row. Returns {:recipes [..] :next <cursor|nil>}.

  O(page): `d/index-range` seeks the `:recipe/title` AVET index straight to the
  cursor and reads only one page forward, so the browse read does not grow with
  the catalog — the fix for a list that loaded every recipe on every request.
  Datomic's covering indexes are ascending, so the order is A→Z; a descending
  'newest first' is not an index primitive here and is the dashboard's job.

  The keyset is `(title, eid)` — and it MUST be `eid`, not the recipe's uuid,
  because that is exactly the order the AVET index yields datoms in. Tie-breaking
  on the uuid would disagree with the index and skip a row whose uuid happened to
  sort before its same-title predecessor's. `eid` is the datom's own entity id,
  so it never disagrees. Callers keep the cursor opaque (see the handler)."
  [db after ^long limit]
  (let [cursor [(:recipe/title after) (:eid after)]
        datoms (->> (d/index-range db :recipe/title (:recipe/title after) nil)
                    (drop-while (fn [d] (and after (<= (compare [(:v d) (:e d)] cursor) 0))))
                    (take (inc limit)))
        page (vec (take limit datoms))]
    {:recipes (mapv #(db/pull* db pull-pattern (:e %)) page)
     :next (when (> (count datoms) limit)
             (let [d (peek page)]
               {:recipe/title (:v d)
                :eid (:e d)}))}))

(defn- fulltext-escape
  "Neutralize Lucene query syntax in user input.
  The fulltext datalog fn hands `q` to a Lucene QueryParser, where `*`,
  `~`, `&&`, `AND`… are operators (and some inputs throw outright). We
  search literal words, so operators become spaces and the analyzer does
  the rest. `&` and `|` are in the class because `&&`/`||` are the
  operator spellings that THROW from the parser — an input of bare
  operators must conform to nothing, not 500 a public endpoint."
  [q]
  (-> (or q "")
      (str/replace #"[+\-!(){}\[\]^\"~*?:\\/&|]" " ")
      (str/replace #"(?i)\b(AND|OR|NOT)\b" " ")
      str/trim))

(def ^:private search-limit
  "One search's result ceiling.
  A stated bound, not a read that grows with the catalog."
  50)

(defn search
  "Recipes whose title matches `q`, best match first.
  Runs on the fulltext index `:recipe/title` has carried since the
  schema's first version; returns pulled maps (the same shape the browse
  list renders), at most `search-limit`. Blank or operator-only input
  returns []."
  [db q]
  (let [q (fulltext-escape q)]
    (if (str/blank? q)
      []
      (->> (d/q '[:find ?e ?score
                  :in $ ?q
                  :where [(fulltext $ :recipe/title ?q) [[?e _ _ ?score]]]]
                db
                q)
           (sort-by second #(compare %2 %1))
           (take search-limit)
           (mapv (fn [[e _]] (db/pull* db pull-pattern e)))))))

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
;; Activity (notifications as a query — d/since)
;; ---------------------------------------------------------------------------

(defn activity
  "What happened around `user-eid`'s recipes since the cursor.
  `since` is an Instant (nil means ever). Two kinds of news: forks of
  their recipes by others, and content edits to originals they forked.
  Newest first.

  A read, not infrastructure: `d/since` narrows the database to datoms
  asserted after the cursor, and each query joins that window back to the
  full db for identity — a since-db alone knows nothing older than the
  cursor, so the two-source join is the idiom, not a workaround. Entries
  are {:type :fork|:upstream-edit, :recipe <pull>, :at Instant}."
  [db user-eid since]
  (let [since-db (if since (d/since db (Date/from since)) db)
        fork-rows (d/q
                    '[:find ?f ?inst
                      :in $s $ ?u
                      :where
                      [$s ?f :recipe/forked-from ?orig ?tx]
                      [$ ?orig :recipe/user ?u]
                      (not [$ ?f :recipe/user ?u])
                      [$ ?tx :db/txInstant ?inst]]
                    since-db
                    db
                    user-eid)
        upstream (d/q
                   '[:find ?orig (max ?inst)
                     :in $s $ ?u [?a ...]
                     :where
                     [$ ?mine :recipe/user ?u]
                     [$ ?mine :recipe/forked-from ?orig]
                     (not [$ ?orig :recipe/user ?u])
                     [$s ?orig ?a _ ?tx]
                     [$ ?tx :db/txInstant ?inst]]
                   since-db
                   db
                   user-eid
                   versioned-attrs)]
    (->> (concat (for [[f inst] fork-rows]
                   {:type :fork
                    :recipe (db/pull* db pull-pattern f)
                    :at (db/as-instant inst)})
                 (for [[orig inst] upstream]
                   {:type :upstream-edit
                    :recipe (db/pull* db pull-pattern orig)
                    :at (db/as-instant inst)}))
         (sort-by :at #(compare %2 %1))
         vec)))

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
          (let [ann (db/pull* db [{:tx/author [:user/email :user/display-name]} :tx/note] tx)]
            {:tx tx
             :t (d/tx->t tx)
             :instant (db/as-instant inst)
             :author (:tx/author ann)
             :note (:tx/note ann)
             :recipe (db/pull* (d/as-of db tx) pull-pattern eid)}))
        txs))))

(defn version-as-of
  "The state of recipe `id` as of basis point `t` (a basis-t, tx-eid, or Date).
  nil if the recipe doesn't exist."
  [db id t]
  (when-let [eid (d/entid db [:recipe/id id])]
    (db/pull* (d/as-of db t) pull-pattern eid)))

(defn first-version
  "The earliest version of recipe `id`.
  Its state as of the first content transaction. This is what the proposal
  merge reads as its BASE: a fork's
  first version is the content copied from the parent at fork time. One
  min-tx query plus one as-of pull, where taking `(first (version-history …))`
  would reconstruct an annotation + as-of pull for EVERY version just to
  keep the first. nil if the recipe doesn't exist."
  [db id]
  (when-let [eid (d/entid db [:recipe/id id])]
    (when-let [tx (d/q
                    '[:find (min ?tx) .
                      :in $ ?e [?a ...]
                      :where
                      [?e ?a _ ?tx true]]
                    (d/history db)
                    eid
                    versioned-attrs)]
      (db/pull* (d/as-of db tx) pull-pattern eid))))

;; ---------------------------------------------------------------------------
;; Line diff (Myers O(ND)) — the version-to-version comparison
;;
;; The greedy algorithm from Myers 1986 ("An O(ND) Difference Algorithm"),
;; the core of what git's own diff runs. Its cost scales with the EDIT
;; DISTANCE D (how much actually changed), not with the product of the two
;; lengths — so a one-line edit to a thousand-line recipe is O(lines), not
;; O(lines^2). That is why it needs no line-count cap: the old LCS table was
;; O(n*m) in time AND heap for every diff, so a big pair OOMed the box and a
;; 2000-line ceiling plus an all-del/all-add fallback were bolted on to
;; contain it. Real recipe edits have a tiny D and never approach the one
;; bound this keeps — an edit-distance ceiling, not a size guess — which
;; exists only so a deliberately antagonistic "share almost nothing" pair
;; (a line diff of which would be visual noise anyway) cannot make the
;; trace's own memory the DoS. All arithmetic is primitive-long over a
;; java long-array, so the strict build stays clean of boxed math.
;; ---------------------------------------------------------------------------

(def ^:private max-edit-distance
  "Ceiling on the Myers edit distance D before we stop and emit a coarse diff.
  Bounds the trace's time (O(D*(n+m))) and heap (O(D^2)) against a pair that
  shares almost nothing. A real recipe edit's D is in the dozens; this is
  generous headroom above any genuine change, reached only by antagonistic
  input, and even the fields it guards are capped at ~20k chars by `conform`."
  4000)

(defn- myers-trace
  "Run the Myers greedy search and return its per-`d` frontier snapshots.
  Each snapshot is the array `V` after edit-distance `d`, where `V` maps a
  diagonal k -> the furthest x reached with d edits (k in [-d, d], stored
  offset by `max-edit-distance` in a primitive long-array). Returns nil when
  the edit distance exceeds the ceiling."
  [a b ^long n ^long m]
  (let [ceiling (long max-edit-distance)
        off ceiling
        size (inc (* 2 ceiling))
        v (long-array size)
        av (object-array a)
        bv (object-array b)]
    (loop [d 0
           trace (transient [])]
      (if (> d (min ceiling (+ n m)))
        nil
        (let [snap (long-array size)
              _ (System/arraycopy v 0 snap 0 size)
              result
              (loop [k (- d)]
                (if (> k d)
                  :continue
                  (let [x0
                        (if
                          (or
                            (= k (- d))
                            (and (not= k d) (< (aget v (+ off (dec k))) (aget v (+ off (inc k))))))
                          (aget v (+ off (inc k))) ; move down (an insertion)
                          (inc (aget v (+ off (dec k))))) ; move right (a deletion)
                        ;; Slide down the diagonal over equal lines (the snake).
                        [x y] (loop [x (long x0)
                                     y (- (long x0) k)]
                                (if (and (< x n) (< y m) (= (aget av x) (aget bv y)))
                                  (recur (inc x) (inc y))
                                  [x y]))]
                    (aset v (+ off k) (long x))
                    (if (and (>= (long x) n) (>= (long y) m)) :done (recur (+ k 2))))))
              trace' (conj! trace snap)]
          (if (= result :done) (persistent! trace') (recur (inc d) trace')))))))

(defn- backtrack
  "Walk the Myers `trace` from (n,m) to the origin, emitting ops in display order.
  Each step moves along a diagonal (context lines) then takes one edit — a
  deletion when x advanced, an insertion when y advanced."
  [trace a b n m]
  (let [off (long max-edit-distance)]
    (loop [d (dec (count trace))
           x (long n)
           y (long m)
           acc ()]
      (if (neg? d)
        (vec acc)
        (let [^longs v (nth trace d)
              k (- x y)
              prev-k (if
                       (or
                         (= k (- d))
                         (and (not= k d) (< (aget v (+ off (dec k))) (aget v (+ off (inc k))))))
                       (inc k)
                       (dec k))
              prev-x (aget v (+ off prev-k))
              prev-y (- prev-x prev-k)
              ;; Snake: the diagonal run of context lines before the edit.
              ;; (y falls out of the destructure but is not needed downstream —
              ;; the next hop is driven by prev-x/prev-y.)
              [x _ acc]
              (loop [x x
                     y y
                     acc acc]
                (if (and (> x prev-x) (> y prev-y))
                  (recur
                    (dec x)
                    (dec y)
                    (cons
                      {:op :ctx
                       :text (nth a (dec x))}
                      acc))
                  [x y acc]))
              acc (cond
                    (zero? d) acc
                    (= x prev-x) (cons
                                   {:op :add
                                    :text (nth b prev-y)}
                                   acc) ; y advanced
                    :else (cons
                            {:op :del
                             :text (nth a prev-x)}
                            acc))]      ; x advanced
          (recur (dec d) (long prev-x) (long prev-y) acc))))))

(defn- coarse-diff
  "Emit an all-del/all-add diff for a pair whose edit distance blew the ceiling.
  Every old line as `:del`, every new line as `:add` — not minimal, but
  bounded and honest, reached only by antagonistic input a line-level diff
  could not meaningfully align anyway."
  [a b]
  (into
    (mapv
      (fn [t]
        {:op :del
         :text t})
      a)
    (map
      (fn [t]
        {:op :add
         :text t})
      b)))

(defn line-diff
  "A git-style line diff of two newline-separated strings.

  Returns a vector of `{:op :ctx|:add|:del :text <line>}` in display order,
  from Myers' O(ND) shortest edit script: shared lines are `:ctx`, lines only
  in `new-text` are `:add`, lines only in `old-text` are `:del`. Pure data →
  data. The cost tracks the edit distance, so an ordinary edit is near-linear;
  only a pair that shares almost nothing (D past `max-edit-distance`) falls
  back to a coarse all-del/all-add diff."
  [old-text new-text]
  (let [a (lines old-text)
        b (lines new-text)
        n (count a)
        m (count b)]
    (if-let [trace (myers-trace a b n m)]
      (backtrack trace a b n m)
      (coarse-diff a b))))

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

(def ^:private limits
  "Field size ceilings for `conform`.
  The content ceilings are deliberately generous — the goal is refusing the absurd (a pasted novel, an overflowing
  integer), not policing prose."
  {:title 200
   :description 20000
   :ingredients 20000
   :steps 20000})

(defn conform
  "Validate and coerce raw recipe form fields into content values.
  Takes the strings straight off the wire; produces what `create!` and
  `update!` accept.

  Returns `{:values {…}}` when everything conforms, else
  `{:errors {field [code …]}}` — e.g. `{:title [:blank]}`. Error codes are
  data: the domain decides *what* is wrong, and rendering translates codes
  into words (i18n) at the boundary — so the rules live in exactly one
  place for every caller, the form handler today, an API tomorrow."
  [{:keys [title description servings ingredients steps note]}]
  (let [title (str/trim (or title ""))
        note (some-> note
                     str/trim
                     not-empty)
        description (or description "")
        ingredients (or ingredients "")
        steps (or steps "")
        servings-n (parse-long (str/trim (str (or servings ""))))
        errors (cond-> {}
                 (str/blank? title) (assoc :title
                                      [:blank])
                 (> (count title) (long (:title limits))) (assoc :title
                                                            [:too-long])
                 (nil? servings-n) (assoc :servings
                                     [:not-a-number])
                 (and servings-n (not (<= 1 servings-n 100))) (assoc :servings
                                                                [:out-of-range])
                 (> (count description) (long (:description limits))) (assoc :description
                                                                        [:too-long])
                 (> (count ingredients) (long (:ingredients limits))) (assoc :ingredients
                                                                        [:too-long])
                 (> (count (or note "")) 500) (assoc :note
                                                [:too-long])
                 (> (count steps) (long (:steps limits))) (assoc :steps
                                                            [:too-long]))]
    (if (seq errors)
      {:errors errors}
      {:values {:title title
                :description description
                :servings servings-n
                :ingredients ingredients
                :steps steps
                :note note}})))

(defn- assert-content!
  "Defense in depth: refuse what `conform` would reject, never repair it.
  The old behavior — coining \"Untitled recipe\" for a blank title — put a lie in the database and kept it forever; a throw here means
  a handler that skipped `conform` fails loudly in development, not quietly
  in the data."
  [{:keys [title servings]}]
  (when (or (str/blank? title) (not (int? servings)) (not (<= 1 servings 100)))
    (throw
      (ex-info
        "unconformed recipe content — call conform first"
        {:title title
         :servings servings}))))

(defn annotate
  "Append the transaction annotation to `tx-data`.
  Every user-driven write names its author, and carries the user's note
  when one was offered. The
  \"datomic.tx\" tempid resolves to the transaction entity itself.
  Public because every domain write — proposals included — routes its
  provenance through this one place."
  ([tx-data user-eid] (annotate tx-data user-eid nil))
  ([tx-data user-eid note]
   (conj
     (vec tx-data)
     (cond-> {:db/id "datomic.tx"
              :tx/author user-eid}
       (seq note) (assoc :tx/note
                    note)))))

(defn create!
  "Create a new recipe owned by `user-eid`; returns the new `:recipe/id`.
  `content` must be conformed (see `conform`) — the domain refuses
  unvalidated input. Pass `:forked-from-eid` to record provenance (used by
  `fork!`)."
  [conn user-eid
   {:keys [title description servings ingredients steps forked-from-eid]
    :as content}]
  (assert-content! content)
  (let [id (UUID/randomUUID)
        now (time/now)
        position (next-position (d/db conn) user-eid)]
    @(db/transact* conn
       (annotate
         [(cond-> {:db/id "new-recipe"
                   :recipe/id id
                   :recipe/user user-eid
                   :recipe/title title
                   :recipe/description (or description "")
                   :recipe/servings (long servings)
                   :recipe/ingredients (or ingredients "")
                   :recipe/steps (or steps "")
                   :recipe/position position
                   :recipe/created-at now
                   :recipe/updated-at now}
            forked-from-eid (assoc :recipe/forked-from
                              forked-from-eid))]
         user-eid))
    id))

(defn reorder!
  "Set explicit :recipe/position on `ids` (UUIDs) in the given order, for recipes owned by `user-eid`.
  Ids not owned by the user are silently skipped (tenant
  isolation). Does NOT bump :recipe/updated-at — a reorder is not a content edit
  and must not create a version. Returns true."
  [conn user-eid ids]
  ;; Each position write rides with an existence CAS (`:recipe/id` → same
  ;; value): a recipe deleted between the owner check and the transactor
  ;; must fail the guard, not be re-created as a position-only orphan by a
  ;; bare assert on its stale eid. One failed guard aborts the whole tx —
  ;; so on that (rare) race we re-read and retry once, at which point the
  ;; deleted id falls out via `entid-owned` like any other missing recipe.
  (loop [attempt 0]
    (let [db (d/db conn)
          tx (->> ids
                  (map-indexed (fn [i id]
                                 (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
                                   [[:db.fn/cas eid :recipe/id id id]
                                    {:db/id eid
                                     :recipe/position (long i)}])))
                  (remove nil?)
                  (apply concat)
                  vec)
          result (if (seq tx)
                   (try
                     @(db/transact* conn (annotate tx user-eid))
                     true
                     (catch Throwable e (if (db/cas-failed? e) :retry (throw e))))
                   true)]
      (if (and (= :retry result) (zero? attempt)) (recur 1) true))))

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

(defn preview
  "The recipe as it WOULD read, rendered from a speculative database.
  Applies conformed content `values` — to the existing recipe `id` (owner
  only), or as a new recipe when `id` is nil. `d/with` returns a db that contains
  the change without transacting anything; we pull from it exactly as
  `recipe-by-id` would. Returns the pulled map, or nil when `id` isn't the
  caller's to preview.

  Because the speculative db is a real db value, everything downstream is
  the real read path: same pull pattern, same markdown, same formatting —
  and, for an edit, the recipe's real fork provenance and timestamps."
  [db user-eid id {:keys [title description servings ingredients steps]}]
  (assert-content!
    {:title title
     :servings servings})
  (let [now (time/now)
        content {:recipe/title title
                 :recipe/description (or description "")
                 :recipe/servings (long servings)
                 :recipe/ingredients (or ingredients "")
                 :recipe/steps (or steps "")}]
    (if id
      (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
        (let [{:keys [db-after]} (db/with*
                                   db
                                   [(assoc content
                                      :db/id eid
                                      :recipe/updated-at now)])]
          (db/pull* db-after pull-pattern eid)))
      (let [{:keys [db-after tempids]}
            (db/with*
              db
              [(assoc content
                 :db/id "preview"
                 :recipe/id (UUID/randomUUID)
                 :recipe/user user-eid
                 :recipe/position 0
                 :recipe/created-at now
                 :recipe/updated-at now)])]
        (db/pull* db-after pull-pattern (d/resolve-tempid db-after tempids "preview"))))))

(defn- assert-changes!
  "Partial-map sibling of `assert-content!`.
  Only the keys present are held to `conform`'s rules — `update!`
  legitimately takes subsets."
  [{:recipe/keys [title servings]
    :as changes}]
  (when
    (or
      (and (contains? changes :recipe/title) (str/blank? (or title "")))
      (and
        (contains? changes :recipe/servings)
        (or (not (int? servings)) (not (<= 1 servings 100)))))
    (throw
      (ex-info
        "unconformed recipe changes — call conform first"
        {:changes (select-keys changes [:recipe/title :recipe/servings])}))))

(defn update!
  "Apply `changes` (a subset of the `:recipe/*` content keys) to recipe `id`, if owned by `user-eid`.
  Content must be conformed (see `conform`); the keys present are checked.
  Bumps `:recipe/updated-at`. Returns true on success, nil if the recipe
  is missing or not owned by the user.

  Optional `expected` is the `:recipe/updated-at` the editor loaded — an
  optimistic-concurrency token. When supplied, the write is a
  compare-and-swap on THAT per-recipe attribute: it applies only if the
  recipe's content has not changed underneath the edit, and returns
  `:conflict` (touching nothing) if it has. The token is scoped to this
  recipe's content clock, not the global basis-t, precisely because a
  reorder or an unrelated recipe's edit must not read as a conflict —
  which is why `:recipe/updated-at` (bumped by content edits only, never
  by a reorder) is the right thing to compare.

  Optional `extra-tx` (a seq of tx forms) commits IN THE SAME transaction
  as the content write — for a caller whose own bookkeeping must be atomic
  with the edit (the proposal accept's status flip rides here). It shares
  the write's annotation and its conflict fate: a CAS refusal aborts both."
  ([conn user-eid id changes] (update! conn user-eid id changes nil nil))
  ([conn user-eid id changes note] (update! conn user-eid id changes note nil))
  ([conn user-eid id changes note expected] (update! conn user-eid id changes note expected nil))
  ([conn user-eid id changes note expected extra-tx]
   (assert-changes! changes)
   (let [db (d/db conn)]
     (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
       (let [content (select-keys
                       changes
                       [:recipe/title :recipe/description :recipe/servings
                        :recipe/ingredients :recipe/steps])
             ;; With a token: CAS asserts the new updated-at only if the
             ;; current one still equals `expected` — atomic on the
             ;; transactor, no read-then-write race in the handler.
             ;; Without one: the plain bump — guarded by a CAS on
             ;; `:recipe/id` (same value in, same value out), whose only
             ;; job is to REFUSE if the entity vanished between the owner
             ;; check above and the transactor's turn. A bare assert on a
             ;; retracted eid would silently re-create the recipe as an
             ;; ownerless ghost; the guard makes a delete race read as
             ;; missing (nil), exactly like every other missing recipe.
             tx-forms (if expected
                        [[:db.fn/cas eid :recipe/updated-at expected (time/now)]
                         (merge {:db/id eid} content)]
                        [[:db.fn/cas eid :recipe/id id id]
                         (merge
                           {:db/id eid
                            :recipe/updated-at (time/now)}
                           content)])]
         (try
           @(db/transact* conn (annotate (concat tx-forms extra-tx) user-eid note))
           true
           (catch Throwable e
             (if (db/cas-failed? e)
               ;; Which CAS failed tells us what happened: the token CAS
               ;; means a concurrent edit (:conflict); the existence guard
               ;; means the recipe is gone (nil — same answer as missing).
               (when expected :conflict)
               (throw e)))))))))

(defn set-image!
  "Point recipe `id`'s photo at the stored upload with `upload-hash`, if owned by `user-eid`.
  A domain write like any other: owner-gated, annotated, and guarded by the
  existence CAS — the first cut lived in the handler as a bare lookup-ref
  upsert, and an upsert on `{:recipe/id id}` RE-CREATES a just-deleted
  recipe as a ghost holding only an id and an image. Does not bump
  `:recipe/updated-at`: the photo is presentation, not content — a photo
  swap must not create a version or a false edit conflict. Returns true on
  success, nil if missing/not owned (including deleted-in-the-window)."
  [conn user-eid id upload-hash]
  (let [db (d/db conn)]
    (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
      (try
        @(db/transact* conn
           (annotate
             [[:db.fn/cas eid :recipe/id id id]
              {:db/id eid
               :recipe/image [:upload/hash upload-hash]}]
             user-eid))
        true
        (catch Throwable e (if (db/cas-failed? e) nil (throw e)))))))

(defn remove-image!
  "Retract recipe `id`'s photo reference, if owned by `user-eid`.
  Only the reference goes; the blob stays for the orphan sweep, because
  content-addressing means another recipe may share the same bytes.
  Returns true on success (including no-photo-to-remove), nil if the
  recipe is missing/not owned."
  [conn user-eid id]
  (let [db (d/db conn)]
    (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
      (when-let [img-eid (:db/id (:recipe/image (db/pull* db [{:recipe/image [:db/id]}] eid)))]
        ;; A retraction is race-proof where the upsert was not: retracting
        ;; a datom that is already gone (photo replaced, recipe deleted) is
        ;; a no-op, never a resurrection.
        @(db/transact* conn (annotate [[:db/retract eid :recipe/image img-eid]] user-eid)))
      true)))

(defn delete!
  "Retract recipe `id` if owned by `user-eid`.
  Returns true on success, nil otherwise. (Forks keep their copied content,
  but :db/retractEntity also retracts their inbound :recipe/forked-from refs —
  in the current db they become originals; the provenance survives in history.)"
  [conn user-eid id]
  (let [db (d/db conn)]
    (when-let [eid (db/entid-owned db user-eid [:recipe/id id])]
      @(db/transact* conn (annotate [[:db/retractEntity eid]] user-eid))
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
