(ns myapp.recipe.proposal
  "A fork's changes offered back to the recipe it forked from.
  A pull request for recipes, resolved by a three-way merge.

  The whole reason this is small is that immutable history hands us the
  merge's three inputs for free. A classic 3-way merge needs a common
  ANCESTOR (base), plus each side's current state; the hard part elsewhere
  is reconstructing the base. Here the base is the fork's own first version
  — the content copied out of the parent at fork time — read straight from
  `version-history`. ours = the parent now, theirs = the fork now."
  (:require
    [datomic.api :as d]
    [myapp.auth.email :as email]
    [myapp.config :as config]
    [myapp.db.core :as db]
    [myapp.i18n :as i18n]
    [myapp.jobs.core :as jobs]
    [myapp.recipe.core :as recipe]
    [myapp.time :as time]))

(set! *warn-on-reflection* true)

(def ^:private merge-fields
  "The content fields a merge reconciles.
  The versioned ones, minus the structural `:recipe/forked-from`."
  [:recipe/title :recipe/description :recipe/servings
   :recipe/ingredients :recipe/steps])

(defn three-way-merge
  "Field-level three-way merge of three pulled recipe maps.

  - `base`   — the common ancestor (the fork's first version)
  - `ours`   — the target's current state
  - `theirs` — the fork's current state (the proposed changes)

  Per field the base is what distinguishes a clean apply from a conflict —
  a two-way diff sees only that two values differ, never *who* changed:
    - the fork left the field alone (theirs = base)     → keep ours
    - only the fork changed it (ours = base)            → apply theirs
    - both changed it to the same thing (ours = theirs) → keep ours
    - both changed it differently                       → CONFLICT
  Returns {:fields {field {:status :unchanged|:applied|:conflict
                           :value <resolved> (absent when :conflict)
                           :base .. :ours .. :theirs ..}}
           :conflict? bool
           :clean {recipe-key value …}}  ; the auto-mergeable fields."
  [base ours theirs]
  (let [decide (fn [f]
                 (let [b (get base f)
                       o (get ours f)
                       t (get theirs f)]
                   (cond
                     (= t b) {:status :unchanged
                              :value o
                              :base b
                              :ours o
                              :theirs t}
                     (= o b) {:status :applied
                              :value t
                              :base b
                              :ours o
                              :theirs t}
                     (= o t) {:status :unchanged
                              :value o
                              :base b
                              :ours o
                              :theirs t}
                     :else {:status :conflict
                            :base b
                            :ours o
                            :theirs t})))
        fields (into {} (map (juxt identity decide)) merge-fields)
        conflict? (boolean (some (fn [[_ v]] (= :conflict (:status v))) fields))
        clean (into {} (keep (fn [[f v]] (when (contains? v :value) [f (:value v)]))) fields)]
    {:fields fields
     :conflict? conflict?
     :clean clean}))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(def ^:private pull-pattern
  [:proposal/id :proposal/status :proposal/created-at
   {:proposal/source [:recipe/id]}
   {:proposal/target [:recipe/id]}])

(defn proposal-by-id
  "Pull a proposal by its UUID (with source/target recipe ids), or nil."
  [db pid]
  (when pid
    (when-let [eid (d/entid db [:proposal/id pid])]
      (db/pull* db pull-pattern eid))))

(defn merge-for
  "Compute the three-way merge for `proposal` (a pulled proposal map).
  Reads base (fork's first version), ours (target now), theirs (fork now)
  and returns the `three-way-merge` result plus the three source maps, or
  nil if either recipe is gone."
  [db proposal]
  (let [source-id (get-in proposal [:proposal/source :recipe/id])
        target-id (get-in proposal [:proposal/target :recipe/id])
        ;; One min-tx query + one as-of pull — NOT (first (version-history …)),
        ;; which reconstructs every version of the fork to keep its first.
        ;; The accept path computes this merge up to three times per request;
        ;; a long-lived fork would pay hundreds of pulls per click.
        base (recipe/first-version db source-id)
        ours (recipe/recipe-by-id db target-id)
        theirs (recipe/recipe-by-id db source-id)]
    (when (and base ours theirs)
      (assoc (three-way-merge base ours theirs)
        :base base
        :ours ours
        :theirs theirs))))

(defn open-proposals-targeting
  "Open proposals whose target is recipe `target-id`, newest first.
  What the recipe's owner sees as pending suggestions."
  [db target-id]
  (if-let [eid (d/entid db [:recipe/id target-id])]
    (->> (d/q '[:find [?p ...]
                :in $ ?t
                :where
                [?p :proposal/target ?t]
                [?p :proposal/status :open]]
              db
              eid)
         (map #(db/pull* db pull-pattern %))
         (sort-by :proposal/created-at #(compare %2 %1))
         vec)
    []))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(defn- open-proposal-between
  "The already-open proposal from `source-eid` to `target-eid`, or nil."
  [db source-eid target-eid]
  (when-let [p (d/q
                 '[:find ?p .
                   :in $ ?s ?t
                   :where
                   [?p :proposal/source ?s]
                   [?p :proposal/target ?t]
                   [?p :proposal/status :open]]
                 db
                 source-eid
                 target-eid)]
    (:proposal/id (db/pull* db [:proposal/id] p))))

(defn create-proposal!
  "Open a proposal: offer fork `source-id`'s changes back to its parent.
  Caller must own the fork, and it must actually be a fork with a live
  parent. IDEMPOTENT per (source, target): if an open proposal already
  exists it is returned instead of opened again — a proposal is 'these two
  recipes differ', which is one fact however often it is submitted, and
  every duplicate would otherwise queue another email at the target's
  owner (an authenticated spam lever; the route is also rate-limited at
  the handler). Returns the (new or existing) proposal id, or nil."
  [conn user-eid source-id]
  (let [db (d/db conn)
        src (recipe/recipe-by-id db source-id)
        parent-id (get-in src [:recipe/forked-from :recipe/id])]
    (when (and src (recipe/owned-by? src user-eid) parent-id (recipe/recipe-by-id db parent-id))
      (let [source-eid (d/entid db [:recipe/id source-id])
            target-eid (d/entid db [:recipe/id parent-id])]
        (or
          (open-proposal-between db source-eid target-eid)
          (let [pid (random-uuid)]
            ;; The proposal and the "you have a proposed change" notification
            ;; job commit in ONE transaction: a crash can never leave a
            ;; proposal opened with no notification queued, or a notification
            ;; for a proposal that was never written. The worker delivers it,
            ;; with retries. Annotated like every other domain write.
            @(db/transact* conn
               (recipe/annotate
                 [{:proposal/id pid
                   :proposal/source source-eid
                   :proposal/target target-eid
                   :proposal/status :open
                   :proposal/created-at (time/now)}
                  (jobs/enqueue-tx :proposal-notification {:proposal-id pid})]
                 user-eid))
            pid))))))

(defn accept!
  "Merge an open proposal into its target — target owner only.

  `resolutions` maps each CONFLICTING field to the chosen value; `expected`
  is the target's `:recipe/updated-at` the reviewer saw. The merge is
  recomputed against the CURRENT target, so the write goes through
  `recipe/update!`'s optimistic-concurrency check: if the target moved
  during review the merge is stale, `update!` returns `:conflict`, and the
  proposal stays open for a re-review. Returns true, :conflict, :incomplete
  (an unresolved conflict), or nil (not open / not owned / gone)."
  [conn user-eid proposal-id resolutions expected]
  (let [db (d/db conn)
        prop (proposal-by-id db proposal-id)]
    (when (and prop (= :open (:proposal/status prop)))
      (let [target-id (get-in prop [:proposal/target :recipe/id])
            target (recipe/recipe-by-id db target-id)]
        (when (and target (recipe/owned-by? target user-eid))
          (let [{:keys [clean fields conflict?]} (merge-for db prop)
                conflict-fs (keep (fn [[f v]] (when (= :conflict (:status v)) f)) fields)]
            (if (and conflict? (not (every? #(contains? resolutions %) conflict-fs)))
              :incomplete
              (let [merged (reduce
                             #(assoc %1
                                %2 (get resolutions %2))
                             clean
                             conflict-fs)]
                ;; The merge write and the status flip are ONE transaction
                ;; (update!'s extra-tx): a crash between "merged" and
                ;; "accepted" cannot leave merged content behind a still-open
                ;; proposal, and an OCC refusal aborts both together — the
                ;; proposal stays open precisely when nothing was merged.
                ;; The flip also shares the write's annotation.
                (recipe/update!
                  conn
                  user-eid
                  target-id
                  merged
                  "Merged a proposed change"
                  expected
                  [{:proposal/id proposal-id
                    :proposal/status :accepted}])))))))))

(defn decline!
  "Decline an open proposal — target owner only. Returns true or nil."
  [conn user-eid proposal-id]
  (let [db (d/db conn)
        prop (proposal-by-id db proposal-id)
        target (some->> (get-in prop [:proposal/target :recipe/id])
                        (recipe/recipe-by-id db))]
    (when (and prop (= :open (:proposal/status prop)) target (recipe/owned-by? target user-eid))
      @(db/transact* conn
         (recipe/annotate
           [{:proposal/id proposal-id
             :proposal/status :declined}]
           user-eid))
      true)))

;; The job handler for the notification enqueued by create-proposal!. Lives here,
;; with the domain it serves, so `jobs.core` stays generic. It throws on a failed
;; send so the worker retries; a rare duplicate (at-least-once) is acceptable for
;; a notification. No request means no Accept-Language, so it defaults to English
;; — a per-user locale preference would be a small refinement.
(defmethod jobs/run-job :proposal-notification
  [conn {:keys [proposal-id]} _kind]
  (let [db (d/db conn)
        prop (proposal-by-id db proposal-id)
        target-id (get-in prop [:proposal/target :recipe/id])
        info (some->> target-id
                      (vector :recipe/id)
                      (db/pull* db [:recipe/title {:recipe/user [:user/email]}]))
        to (get-in info [:recipe/user :user/email])]
    (when (and prop to)
      (let [url (str (config/get-config :base-url) "/proposals/" proposal-id)
            {:keys [error message]}
            (email/send-notification!
              to
              (i18n/t :en :email/proposal-subject)
              (format (i18n/t :en :email/proposal-body) (:recipe/title info) url))]
        (when (= :FAIL error)
          (throw
            (ex-info
              "proposal notification send failed"
              {:to to
               :message message})))))))
