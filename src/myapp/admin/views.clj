(ns myapp.admin.views
  "Admin dashboard views. English only — internal tooling."
  (:require
    [myapp.web.assets :refer [defn-asset]]
    [myapp.web.views :as views])
  (:import
    [java.time Duration Instant ZoneId]
    [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

(defn-asset live-stats-style "myapp/admin/views.css")

(def ^:private ^DateTimeFormatter datetime-fmt
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss") (ZoneId/of "Europe/Amsterdam")))

(defn- fmt-instant
  "Formats an Instant as yyyy-MM-dd HH:mm:ss in Europe/Amsterdam."
  [^Instant inst]
  (when inst (.format datetime-fmt inst)))

(defn- fmt-duration
  "Formats a Duration as a human-readable string."
  [^Duration dur]
  (when dur
    (let [secs (.toSeconds dur)]
      (cond
        (< secs 60) (str secs "s")
        (< secs 3600) (format "%dm %ds" (quot secs 60) (rem secs 60))
        :else (format "%dh %dm" (quot secs 3600) (quot (rem secs 3600) 60))))))

(defn- stat-card
  "Renders a single stat cell in the shared-border grid.
  Optional trending text appears top-right (e.g. '83%')."
  ([label stat-key raw-value] (stat-card label stat-key raw-value nil))
  ([label stat-key raw-value trending]
   [:div
    {:class
     "flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2 bg-surface px-4 py-8 sm:px-6"}
    [:dt {:class "text-sm/6 font-medium text-text-secondary"} label]
    (when trending [:dd {:class "text-xs font-medium text-positive"} trending])
    [:dd
     {:class "w-full flex-none text-3xl/10 font-medium tracking-tight text-text-primary"
      :data-stat stat-key
      :data-value (str raw-value)
      :style (str "--stat-value:" raw-value)}]]))

(defn- users-table
  "Renders the users table."
  [users]
  [:div.bg-surface.border.border-border.rounded-lg.overflow-hidden
   [:div.px-6.py-4.border-b.border-border
    [:h3.text-lg.font-medium.text-text-primary "Users"]]
   (if (seq users)
     [:table.min-w-full.divide-y.divide-border
      [:thead.bg-surface-subtle
       [:tr
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase "Email"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase "Created"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Terms Accepted"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase "Active"]]]
      [:tbody.bg-surface.divide-y.divide-border
       (for [u users]
         [:tr
          [:td.px-6.py-3.5.text-sm.text-text-primary (:user/email u)]
          [:td.px-6.py-3.5.text-sm.text-text-secondary (fmt-instant (:user/created-at u))]
          [:td.px-6.py-3.5.text-sm.text-text-secondary (fmt-instant (:user/terms-accepted-at u))]
          [:td.px-6.py-3.5.text-sm
           (if (:user/active? u) [:span.text-positive "Yes"] [:span.text-negative "No"])]])]]
     [:p.px-6.py-4.text-sm.text-text-secondary "No users yet."])])

(defn- magic-links-table
  "Renders the recent magic links table."
  [links]
  [:div.bg-surface.border.border-border.rounded-lg.overflow-hidden
   [:div.px-6.py-4.border-b.border-border
    [:h3.text-lg.font-medium.text-text-primary "Recent Magic Links"]]
   (if (seq links)
     [:table.min-w-full.divide-y.divide-border
      [:thead.bg-surface-subtle
       [:tr
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase "Email"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase "Requested"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase "Verified"]
        [:th.px-6.py-3.5.text-left.text-xs.font-medium.text-text-secondary.uppercase
         "Time to Click"]]]
      [:tbody.bg-surface.divide-y.divide-border
       (for [ml links]
         [:tr
          [:td.px-6.py-3.5.text-sm.text-text-primary (:email ml)]
          [:td.px-6.py-3.5.text-sm.text-text-secondary (fmt-instant (:requested-at ml))]
          [:td.px-6.py-3.5.text-sm.text-text-secondary
           (or (fmt-instant (:verified-at ml)) [:span.text-warning "Pending"])]
          [:td.px-6.py-3.5.text-sm.text-text-secondary
           (or (fmt-duration (:time-to-click ml)) "-")]])]]
     [:p.px-6.py-4.text-sm.text-text-secondary "No magic links yet."])])

(defn admin-dashboard
  "Renders the admin dashboard page."
  [{:keys [users magic-links funnel jvm total-users jvm-used-mb user-email]}]
  (let [verification-rate
        (when (pos? (long (:links-sent funnel)))
          (format
            "%.0f%%"
            (* 100.0 (/ (double (:links-verified funnel)) (double (:links-sent funnel))))))
        jvm-total-mb (long (:jvm-total-mb jvm))
        jvm-max-mb (long (:jvm-max-mb jvm))
        jvm-used-pct (when (pos? jvm-total-mb)
                       (format "%.0f%%" (* 100.0 (/ (double jvm-used-mb) (double jvm-total-mb)))))
        jvm-total-pct (when (pos? jvm-max-mb)
                        (format "%.0f%%" (* 100.0 (/ (double jvm-total-mb) (double jvm-max-mb)))))]
    (views/app-layout
      :en
      user-email
      :admin
      {:admin? true}
      [:div
       ;; Unified stat grid — 4 columns, 2 rows
       [:dl
        {:class "grid grid-cols-2 gap-px rounded-lg bg-border overflow-hidden sm:grid-cols-4 mb-8"}
        (stat-card "Total Users" "total-users" total-users)
        (stat-card "Links Sent" "links-sent" (:links-sent funnel))
        (stat-card "Verified" "links-verified" (:links-verified funnel) verification-rate)
        (stat-card "Terms Accepted" "terms-accepted" (:terms-accepted funnel))
        (stat-card "JVM Used" "jvm-used-mb" jvm-used-mb jvm-used-pct)
        (stat-card
          "JVM Free"
          "jvm-free-mb"
          (:jvm-free-mb jvm)
          (when (pos? jvm-total-mb)
            (format "%.0f%%" (* 100.0 (/ (double (:jvm-free-mb jvm)) (double jvm-total-mb))))))
        (stat-card "JVM Total" "jvm-total-mb" jvm-total-mb jvm-total-pct)
        (stat-card "JVM Max" "jvm-max-mb" jvm-max-mb)]

       ;; Tables (unchanged)
       [:div.space-y-8
        (users-table users)
        (magic-links-table magic-links)]

       (live-stats-style)])))
