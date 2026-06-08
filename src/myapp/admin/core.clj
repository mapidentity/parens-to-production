(ns myapp.admin.core
  "Admin dashboard queries.
  Reads from both operational DB (users) and analytics DB (magic links)."
  (:require
    [datomic.api :as d])
  (:import
    [java.time Duration]
    [java.util Date]))

(set! *warn-on-reflection* true)

(defn total-users
  "Returns the total number of registered users."
  [db]
  (or
    (d/q
      '[:find (count ?e) .
        :where [?e :user/email]]
      db)
    0))

(defn all-users
  "Returns all users sorted newest-first, with dates converted to Instants."
  [db]
  (->> (d/q '[:find
              [(pull ?e [:user/email :user/created-at :user/terms-accepted-at :user/active?]) ...]
              :where [?e :user/email]]
            db)
       (sort-by :user/created-at #(compare %2 %1))
       (mapv (fn [u]
               (let [convert (fn [^Date d] (when d (.toInstant d)))]
                 (-> u
                     (update :user/created-at convert)
                     (update :user/terms-accepted-at convert)))))))

(defn recent-magic-links
  "Returns the 50 most recent magic links with time-to-click computed."
  [analytics-db]
  (let [to-instant (fn [^Date d] (when d (.toInstant d)))]
    (->> (d/q '[:find
                [(pull ?e [:magic-link/email :magic-link/requested-at :magic-link/verified-at]) ...]
                :where [?e :magic-link/email]]
              analytics-db)
         (sort-by :magic-link/requested-at #(compare %2 %1))
         (take 50)
         (mapv (fn [ml]
                 (let [requested (to-instant (:magic-link/requested-at ml))
                       verified (to-instant (:magic-link/verified-at ml))
                       ttc (when (and requested verified) (Duration/between requested verified))]
                   (cond-> {:email (:magic-link/email ml)
                            :requested-at requested}
                     verified (assoc :verified-at
                                verified)
                     ttc (assoc :time-to-click
                           ttc))))))))

(defn funnel-stats
  "Returns signup funnel counts: links sent, verified, and terms accepted."
  [db analytics-db]
  (let [links-sent (or
                     (d/q
                       '[:find (count ?e) .
                         :where [?e :magic-link/requested-at]]
                       analytics-db)
                     0)
        links-verified (or
                         (d/q
                           '[:find (count ?e) .
                             :where [?e :magic-link/verified-at]]
                           analytics-db)
                         0)
        terms-accepted (or
                         (d/q
                           '[:find (count ?e) .
                             :where [?e :user/terms-accepted-at]]
                           db)
                         0)]
    {:links-sent links-sent
     :links-verified links-verified
     :terms-accepted terms-accepted}))

(defn jvm-stats
  "Returns current JVM memory usage."
  []
  (let [^Runtime runtime (Runtime/getRuntime)
        max-mem (.maxMemory runtime)
        total-mem (.totalMemory runtime)
        free-mem (.freeMemory runtime)
        used-mem (- total-mem free-mem)
        mb (fn [^long n] (format "%.0f MB" (double (/ n 1048576))))]
    {:max (mb max-mem)
     :total (mb total-mem)
     :free (mb free-mem)
     :used (mb used-mem)}))

(defn dashboard-stats
  "Returns raw numeric stats for the live-polling JSON endpoint."
  [db analytics-db]
  (let [funnel (funnel-stats db analytics-db)
        ^Runtime runtime (Runtime/getRuntime)
        mb (fn [^long n] (long (/ n 1048576)))]
    {:total-users (total-users db)
     :links-sent (:links-sent funnel)
     :links-verified (:links-verified funnel)
     :terms-accepted (:terms-accepted funnel)
     :jvm-used-mb (mb (- (.totalMemory runtime) (.freeMemory runtime)))
     :jvm-free-mb (mb (.freeMemory runtime))
     :jvm-total-mb (mb (.totalMemory runtime))
     :jvm-max-mb (mb (.maxMemory runtime))}))
