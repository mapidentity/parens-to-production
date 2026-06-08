(ns myapp.web.views
  "Hiccup view templates for all pages.
  Each function returns a Hiccup data structure wrapped in a shared layout.
  All user-facing text uses i18n translations keyed by locale."
  (:require
    [clojure.string :as str]
    [hiccup2.core :as h]
    [myapp.i18n :refer [t]]
    [myapp.recipe.core :as recipe]
    [myapp.web.assets :as assets :refer [defn-asset]]
    [myapp.web.inspector :refer [tag-root]]
    [myapp.web.markdown :as markdown])
  (:import
    [java.time Instant ZoneId]
    [java.time.format DateTimeFormatter]
    [java.util Locale]))

(set! *warn-on-reflection* true)

(defn-asset toast-script "myapp/web/toast.js")
(defn-asset dev-reload-script "myapp/web/dev-reload.js")
(defn-asset inspector-script "myapp/web/inspector.js")

(def ^:private ^DateTimeFormatter dt-fmt
  (.withZone (DateTimeFormatter/ofPattern "d MMM yyyy, HH:mm" Locale/ENGLISH)
             (ZoneId/of "Europe/Amsterdam")))

(defn- fmt-time
  "Format an Instant as a human date/time in the display timezone."
  [^Instant inst]
  (when inst (.format dt-fmt inst)))

(defn- author-name
  "Display name for a pulled `:recipe/user` map, falling back to the email
  local-part."
  [user]
  (or (:user/display-name user)
      (some-> (:user/email user) (str/split #"@") first)
      "anonymous"))

;; ---------------------------------------------------------------------------
;; Layout
;; ---------------------------------------------------------------------------

(defn- script-tag
  "A <script> for a served asset, with SRI integrity when the manifest provides it
  (prod). `attrs` adds e.g. {:type \"module\"} or {:defer true}."
  [logical attrs]
  (let [url (assets/asset logical)]
    [:script (cond-> (assoc attrs :src url)
               (assets/asset-sri url) (assoc :integrity (assets/asset-sri url)))]))

(defn- base-layout
  "Base HTML5 wrapper. All pages use this — never called directly by page fns."
  [locale & body]
  ;; Rendered with the ESCAPING hiccup2 renderer (h/html): all string content is
  ;; HTML-escaped by default — the primary XSS defense. Only h/raw content
  ;; (markdown, inline scripts/styles) is emitted verbatim; the strict CSP is the
  ;; defense-in-depth backup.
  (h/html
    {:mode :html}
    (h/raw "<!DOCTYPE html>")
    [:html {:lang (name locale)}
     [:head [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:meta {:name "description" :content (t locale :meta/description)}]
      [:title "MyApp"]
      [:link {:rel "icon" :type "image/svg+xml" :href "/icon.svg"}]
      [:link {:rel "stylesheet" :href (assets/asset "styles.css")}]
      ;; Import map (must precede any module script) remaps each module's absolute
      ;; import specifier to its hashed URL in prod; identity no-op in dev.
      [:script {:type "importmap"} (h/raw (assets/importmap-json))]
      ;; Idiomorph (classic script) must load before the dispatcher module
      ;; so window.Idiomorph is available when dispatcher.js runs.
      (script-tag "idiomorph" {:defer true})
      (script-tag "js/dispatcher.js" {:type "module"})
      (script-tag "js/live-form.js" {:type "module"})
      (script-tag "js/defer-details.js" {:type "module"})
      (script-tag "js/server-preview.js" {:type "module"})
      (script-tag "js/admin-stats.js" {:type "module"})
      [:style
       (h/raw "@keyframes page-enter{from{opacity:.92;transform:translateY(3px)}to{opacity:1;transform:translateY(0)}}main{animation:page-enter .12s ease-out}")]]
     [:body (tag-root body)
      [:div#toast-container.fixed.bottom-4.right-4.z-50
       {:aria-live "polite" :aria-atomic "true"}]
      (toast-script)
      ;; Dev-only: live-reload + source inspector overlay (absent in prod).
      (when
        (try
          (requiring-resolve 'dev-reload/websocket-handler)
          (catch Exception _ nil))
        (list (dev-reload-script) (inspector-script)))]]))

(defn- hero-icon
  "Inline 24x24 Heroicon-style outline SVG. `paths` is a seq of d-strings."
  [paths]
  [:svg.h-5.w-5
   {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
   (for [d paths]
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :d d}])])

(def ^:private nav-icons
  {:browse ["M12 6.042A8.967 8.967 0 0 0 6 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 0 1 6 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 0 1 6-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0 0 18 18a8.967 8.967 0 0 0-6 2.292m0-14.25v14.25"]
   :new ["M12 4.5v15m7.5-7.5h-15"]
   :dashboard ["M3.75 6A2.25 2.25 0 0 1 6 3.75h2.25A2.25 2.25 0 0 1 10.5 6v2.25a2.25 2.25 0 0 1-2.25 2.25H6a2.25 2.25 0 0 1-2.25-2.25V6ZM3.75 15.75A2.25 2.25 0 0 1 6 13.5h2.25a2.25 2.25 0 0 1 2.25 2.25V18a2.25 2.25 0 0 1-2.25 2.25H6A2.25 2.25 0 0 1 3.75 18v-2.25ZM13.5 6a2.25 2.25 0 0 1 2.25-2.25H18A2.25 2.25 0 0 1 20.25 6v2.25A2.25 2.25 0 0 1 18 10.5h-2.25A2.25 2.25 0 0 1 13.5 8.25V6ZM13.5 15.75a2.25 2.25 0 0 1 2.25-2.25H18a2.25 2.25 0 0 1 2.25 2.25V18A2.25 2.25 0 0 1 18 20.25h-2.25A2.25 2.25 0 0 1 13.5 18v-2.25Z"]
   :admin ["M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87l.074.04c.326.196.72.257 1.076.124l1.217-.456a1.125 1.125 0 0 1 1.37.49l1.296 2.247a1.125 1.125 0 0 1-.26 1.431l-1.003.827c-.293.241-.438.613-.431.992a6.759 6.759 0 0 1 0 .255c-.007.38.138.75.43.992l1.005.828c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 0 1-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 0 1-.074.04c-.332.183-.582.495-.644.869l-.214 1.281c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.282a1.823 1.823 0 0 0-.645-.869 6.48 6.48 0 0 1-.074-.04c-.326-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 0 1-1.369-.49l-1.297-2.247a1.125 1.125 0 0 1 .26-1.431l1.004-.828c.292-.24.437-.613.43-.991a6.932 6.932 0 0 1 0-.255c.007-.38-.138-.751-.43-.992l-1.004-.828a1.125 1.125 0 0 1-.26-1.43l1.297-2.247a1.125 1.125 0 0 1 1.37-.491l1.216.456c.356.133.751.072 1.076-.124l.074-.04c.332-.183.582-.495.645-.869l.213-1.28Z"
           "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"]})

(defn- nav-tab
  "Single tab in the top navigation bar."
  [locale label-key href icon-key active?]
  [:a
   {:href href
    :class
    (if active?
      "flex items-center gap-1.5 text-white border-b-2 border-white px-3 py-3 text-sm font-semibold"
      "flex items-center gap-1.5 text-white/70 hover:text-white border-b-2 border-transparent hover:border-white/30 px-3 py-3 text-sm font-medium")}
   (hero-icon (get nav-icons icon-key))
   [:span.hidden.sm:inline (t locale label-key)]])

(defn- top-nav
  "Top navigation bar. Adapts to whether a user is signed in."
  [locale user-email active-tab admin?]
  [:nav.bg-chrome.sticky.top-0.z-50
   [:div.mx-auto.max-w-5xl.px-4
    [:div.flex.h-14.items-center.justify-between
     [:div.flex.items-center.gap-x-2
      [:a.mr-2 {:href "/recipes"}
       [:img.h-7 {:src "/scriptlogo-white.svg" :alt "MyApp" :width 132 :height 32}]]
      (nav-tab locale :nav/browse "/recipes" :browse (= active-tab :browse))
      (when user-email
        (nav-tab locale :nav/new "/recipes/new" :new (= active-tab :new)))
      (when user-email
        (nav-tab locale :nav/dashboard "/dashboard" :dashboard (= active-tab :dashboard)))
      (when admin?
        (nav-tab locale :nav/admin "/admin" :admin (= active-tab :admin)))]
     [:div.flex.items-center.gap-x-2
      (if user-email
        (list
          [:span {:key "email" :class "text-sm text-white/70 hidden sm:block"} user-email]
          [:form {:key "out" :method "POST" :action "/auth/logout"}
           [:button {:type "submit" :class "text-sm text-white/70 hover:text-white"}
            (t locale :auth/sign-out)]])
        [:a {:href "/" :class "text-sm text-white/70 hover:text-white"}
         (t locale :home/sign-in)])]]]])

(defn public-layout
  "Centered card layout for unauthenticated pages (landing, auth, terms)."
  [locale & body]
  (base-layout
    locale
    [:main.min-h-screen.bg-surface-subtle.flex.items-center.justify-center.px-4
     {:data-layout "public"}
     [:div.max-w-md.w-full body]]))

(defn app-layout
  "Layout with the top navigation. Used for recipe browsing (public OR signed
  in), the dashboard, and admin. `opts` may include `:admin?`. `user-email`
  may be nil for anonymous visitors browsing recipes."
  [locale user-email active-tab opts & body]
  (let [admin? (:admin? opts)]
    (base-layout
      locale
      [:div.min-h-screen.flex.flex-col.bg-surface-subtle
       (top-nav locale user-email active-tab admin?)
       [:main.flex-1 {:data-layout "app"}
        [:div.mx-auto.max-w-5xl.px-4.py-8.sm:px-6
         body]]])))

;; ---------------------------------------------------------------------------
;; Landing + auth + terms
;; ---------------------------------------------------------------------------

(def ^:private tagline-keys
  (mapv #(keyword "home" (str "tagline-" %)) (range 1 7)))

(defn home-page
  "Landing page with the magic-link sign-in form."
  [locale]
  (public-layout
    locale
    [:div.space-y-8
     [:div.text-center
      [:img.h-12.mx-auto {:src "/logo.svg" :alt "MyApp" :width 220 :height 48}]
      [:p.mt-3.text-lg.font-medium.text-text-primary (t locale (rand-nth tagline-keys))]
      [:p.mt-2.text-sm.text-text-secondary (t locale :home/lead)]]
     [:div.bg-surface.py-8.px-6.border.border-border.rounded-lg
      [:h2.text-2xl.font-semibold.text-text-primary.mb-4 (t locale :home/get-started)]
      [:form {:method "POST" :action "/auth/request"}
       [:div
        [:label.block.text-sm.font-medium.text-text-primary {:for "email"}
         (t locale :home/email-label)]
        [:input.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid.focus:border-primary-vivid
         {:type "email" :id "email" :name "email" :required true
          :placeholder (t locale :home/email-placeholder)}]]
       [:div.mt-6
        [:button.w-full.flex.justify-center.py-3.px-4.border.border-transparent.rounded-md.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-primary-vivid
         {:type "submit"} (t locale :home/sign-in)]]]
      [:p.mt-4.text-xs.text-text-secondary.text-center (t locale :home/magic-link-explanation)]]
     [:p.text-center
      [:a.text-sm.text-primary-vivid.hover:text-primary {:href "/recipes"}
       (str (t locale :recipe/browse-title) " →")]]]))

(defn magic-link-sent
  "Confirmation page after requesting a magic link."
  [locale email]
  (public-layout
    locale
    [:div.bg-surface.py-8.px-6.border.border-border.rounded-lg
     [:div.text-center
      [:svg.mx-auto.h-16.w-16.text-positive
       {:fill "none" :viewBox "0 0 24 24" :stroke "currentColor" :stroke-width "1.5"
        :stroke-linecap "round" :stroke-linejoin "round"}
       [:path {:d "M3 10h18v7a2 2 0 01-2 2H5a2 2 0 01-2-2z"}]
       [:path {:d "M3 10l9 6 9-6"}]]
      [:h2.mt-4.text-2xl.font-semibold.text-text-primary (t locale :auth/check-email)]
      [:p.mt-2.text-text-secondary (t locale :auth/email-sent-to) [:span.font-medium email]]
      [:p.mt-2.text-sm.text-text-secondary (t locale :auth/link-expires)]
      [:p.mt-1.text-sm.text-text-secondary.opacity-60 (t locale :auth/safe-to-close)]
      [:div.mt-6
       [:a.text-primary-vivid.hover:text-primary.text-sm {:href "/"}
        (t locale :auth/back-to-home)]]]]))

(defn welcome-page
  "Terms acceptance page shown on first login."
  [locale]
  (public-layout
    locale
    [:div.space-y-6
     [:div.text-center
      [:img.h-12.mx-auto {:src "/logo.svg" :alt "MyApp" :width 220 :height 48}]
      [:h2.mt-4.text-2xl.font-semibold.text-text-primary (t locale :terms/welcome-title)]]
     [:div.bg-surface.py-8.px-6.border.border-border.rounded-lg
      [:p.text-text-secondary (t locale :terms/welcome-explanation)]
      [:p.mt-3.text-sm.text-text-secondary (t locale :terms/terms-summary)]
      [:form.mt-8 {:method "POST" :action "/terms/accept"}
       [:button.w-full.flex.justify-center.py-3.px-4.border.border-transparent.rounded-md.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-primary-vivid
        {:type "submit"} (t locale :terms/accept-button)]]
      [:div.mt-4.text-center
       [:form {:method "POST" :action "/auth/logout"}
        [:button.text-sm.text-text-secondary.hover:text-text-primary {:type "submit"}
         (t locale :terms/decline-button)]]]]]))

(defn error-page
  "Generic error page."
  [locale message]
  (public-layout
    locale
    [:div.bg-surface.py-8.px-6.border.border-border.rounded-lg
     [:div.text-center
      [:h2.text-2xl.font-semibold.text-negative.mb-4 (t locale :error/title)]
      [:p.text-text-secondary message]
      [:div.mt-6
       [:a.text-primary-vivid.hover:text-primary {:href "/"} (t locale :auth/back-to-home)]]]]))

;; ---------------------------------------------------------------------------
;; Recipe components
;; ---------------------------------------------------------------------------

(defn- fork-badge
  "Small 'forked from X' pill linking to the parent recipe."
  [locale recipe]
  (when-let [parent (:recipe/forked-from recipe)]
    [:a.inline-flex.items-center.gap-1.text-xs.text-text-secondary.hover:text-primary-vivid
     {:href (str "/recipes/" (:recipe/id parent))}
     [:svg.h-3.5.w-3.5 {:fill "none" :viewBox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor"}
      [:path {:stroke-linecap "round" :stroke-linejoin "round"
              :d "M6 3v12m0 0a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm0-12a3 3 0 1 0 0-.001M18 9a3 3 0 1 0 0-.001M18 9c0 4-6 3-6 9"}]]
     (t locale :recipe/forked-from) " " [:span.font-medium (:recipe/title parent)]]))

(defn- recipe-card
  "Summary card for a recipe in a list."
  [locale recipe]
  [:a.card.block.bg-surface.border.border-border.rounded-lg.p-5.no-underline
   {:href (str "/recipes/" (:recipe/id recipe))}
   [:div.flex.items-baseline.justify-between.gap-3
    [:h3.text-lg.font-semibold.text-text-primary (:recipe/title recipe)]
    [:span.text-xs.text-text-secondary.whitespace-nowrap
     (t locale :recipe/servings) ": " (:recipe/servings recipe)]]
   [:p.mt-1.text-sm.text-text-secondary
    (t locale :recipe/by) " " (author-name (:recipe/user recipe))]
   [:div.mt-3 (fork-badge locale recipe)]])

(defn- text-block
  "Render a newline-separated text field as an ordered/unordered list."
  [text ordered?]
  (let [items (recipe/lines text)
        tag (if ordered? :ol :ul)]
    (if (seq items)
      [tag {:class (str (if ordered? "list-decimal" "list-disc")
                        " pl-5 space-y-1 text-text-primary")}
       (for [line items] [:li line])]
      [:p.text-sm.text-text-secondary "—"])))

(defn- lineage-trail
  "Breadcrumb of ancestors with the 'descends from N ancestors' summary."
  [_locale lineage]
  (when (seq lineage)
    [:div.mb-6.rounded-lg.border.border-border.bg-surface-subtle.px-4.py-3
     [:p.text-sm.font-medium.text-text-primary
      (let [n (count lineage)]
        (if (= n 1)
          "Descends from 1 ancestor"
          (str "Descends from " n " ancestors")))]
     [:nav.mt-2.flex.flex-wrap.items-center.gap-1.text-sm
      ;; oldest → newest (lineage is parent-first, so reverse for root-first)
      (->> (reverse lineage)
           (map-indexed
             (fn [i anc]
               (list
                 (when (pos? (long i)) [:span.text-text-secondary {:key (str "sep" i)} "→"])
                 [:a.text-primary-vivid.hover:text-primary
                  {:key (:recipe/id anc) :href (str "/recipes/" (:recipe/id anc))}
                  (:recipe/title anc)
                  " "
                  [:span.text-text-secondary (str "(" (author-name (:recipe/user anc)) ")")]]))))
      [:span.text-text-secondary "→"]
      [:span.font-medium.text-text-primary "this recipe"]]]))

;; ---------------------------------------------------------------------------
;; Recipe pages
;; ---------------------------------------------------------------------------

(defn recipes-index
  "Public browse list of all recipes."
  [locale user-email admin? recipes]
  (app-layout
    locale user-email :browse {:admin? admin?}
    [:div.flex.items-center.justify-between.mb-6
     [:h1.text-2xl.font-bold.text-text-primary (t locale :recipe/browse-title)]
     (when user-email
       [:a.inline-flex.items-center.gap-1.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.px-3.py-2.rounded-md
        {:href "/recipes/new"} "+ " (t locale :recipe/new)])]
    (if (seq recipes)
      [:div.grid.gap-4.sm:grid-cols-2
       (for [r recipes] ^{:key (:recipe/id r)} (recipe-card locale r))]
      [:p.text-text-secondary (t locale :recipe/no-recipes)])))

(defn recipe-detail
  "A single recipe at its current version, with actions, lineage, and forks."
  [locale user-email admin? recipe {:keys [owner? lineage forks version-count]}]
  (app-layout
    locale user-email :browse {:admin? admin?}
    [:div.max-w-3xl.mx-auto
     (lineage-trail locale lineage)
     [:div.flex.items-start.justify-between.gap-4
      [:div
       [:h1.text-3xl.font-bold.text-text-primary (:recipe/title recipe)]
       [:p.mt-1.text-text-secondary
        (t locale :recipe/by) " " [:span.font-medium (author-name (:recipe/user recipe))]
        " · " (t locale :recipe/servings) " " (:recipe/servings recipe)]]
      [:div.flex.flex-col.items-end.gap-2
       (if user-email
         [:form {:method "POST" :action (str "/recipes/" (:recipe/id recipe) "/fork")}
          [:button.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.px-3.py-2.rounded-md
           {:type "submit"} (t locale :recipe/fork-this)]]
         [:a.text-sm.font-semibold.text-primary-vivid.hover:text-primary {:href "/"}
          (t locale :recipe/login-to-fork)])
       (when owner?
         [:div.flex.gap-2
          [:a.text-sm.text-text-secondary.hover:text-primary-vivid
           {:href (str "/recipes/" (:recipe/id recipe) "/edit")} (t locale :recipe/edit)]
          [:form {:method "POST" :action (str "/recipes/" (:recipe/id recipe) "/delete")}
           [:button.text-sm.text-negative.hover:underline {:type "submit"}
            (t locale :recipe/delete)]]])]]

     (when-not (str/blank? (:recipe/description recipe))
       [:div.legal-content.mt-4 (h/raw (markdown/render (:recipe/description recipe)))])

     [:div.mt-8.grid.gap-8.sm:grid-cols-2
      [:section
       [:h2.text-lg.font-semibold.text-text-primary.mb-2 (t locale :recipe/ingredients)]
       (text-block (:recipe/ingredients recipe) false)]
      [:section
       [:h2.text-lg.font-semibold.text-text-primary.mb-2 (t locale :recipe/steps)]
       (text-block (:recipe/steps recipe) true)]]

     [:div.mt-8.flex.flex-wrap.items-center.gap-4.text-sm.border-t.border-border.pt-4
      [:a.text-primary-vivid.hover:text-primary
       {:href (str "/recipes/" (:recipe/id recipe) "/history")}
       (str (t locale :recipe/history) " (" version-count ")")]
      [:span.text-text-secondary
       (t locale :recipe/updated) " " (fmt-time (:recipe/updated-at recipe))]]

     [:section.mt-8
      [:h2.text-lg.font-semibold.text-text-primary.mb-3 (t locale :recipe/forks)]
      (if (seq forks)
        [:ul.space-y-2
         (for [f forks]
           [:li {:key (:recipe/id f)}
            [:a.text-primary-vivid.hover:text-primary {:href (str "/recipes/" (:recipe/id f))}
             (:recipe/title f)]
            [:span.text-text-secondary.text-sm
             " " (t locale :recipe/by) " " (author-name (:recipe/user f))]])]
        [:p.text-sm.text-text-secondary (t locale :recipe/no-forks)])]]))

(defn recipe-form
  "Create/edit form. `recipe` is nil for new, or a pulled map for edit."
  [locale user-email admin? recipe]
  (let [editing? (some? recipe)
        action (if editing?
                 (str "/recipes/" (:recipe/id recipe) "/edit")
                 "/recipes/new")
        active (if editing? :browse :new)]
    (app-layout
      locale user-email active {:admin? admin?}
      [:div.max-w-2xl.mx-auto
       [:h1.text-2xl.font-bold.text-text-primary.mb-6
        (if editing? (t locale :recipe/edit) (t locale :recipe/new))]
       [:form.space-y-5 {:method "POST" :action action}
        [:div
         [:label.block.text-sm.font-medium.text-text-primary {:for "title"}
          (t locale :recipe/title-label)]
         [:input#title.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
          {:type "text" :name "title" :required true :value (:recipe/title recipe)}]]
        [:div
         [:label.block.text-sm.font-medium.text-text-primary {:for "servings"}
          (t locale :recipe/servings-label)]
         [:input#servings.mt-1.block.w-32.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
          {:type "number" :name "servings" :min "1" :value (or (:recipe/servings recipe) 1)}]]
        [:div
         [:label.block.text-sm.font-medium.text-text-primary {:for "description"}
          (t locale :recipe/description-label)]
         [:textarea#description.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
          {:name "description" :rows "3"} (:recipe/description recipe)]]
        [:div
         [:label.block.text-sm.font-medium.text-text-primary {:for "ingredients"}
          (t locale :recipe/ingredients-label)]
         [:textarea#ingredients.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.font-mono.text-sm.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
          {:name "ingredients" :rows "8"} (:recipe/ingredients recipe)]]
        [:div
         [:label.block.text-sm.font-medium.text-text-primary {:for "steps"}
          (t locale :recipe/steps-label)]
         [:textarea#steps.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.font-mono.text-sm.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
          {:name "steps" :rows "8"} (:recipe/steps recipe)]]
        [:div.flex.items-center.gap-3
         [:button.py-2.px-4.rounded-md.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid
          {:type "submit"} (if editing? (t locale :recipe/save) (t locale :recipe/create))]
         (when editing?
           [:a.text-sm.text-text-secondary.hover:text-text-primary
            {:href (str "/recipes/" (:recipe/id recipe))} "Cancel"])]]])))

(defn recipe-history
  "Version timeline for a recipe (newest first), built from Datomic history."
  [locale user-email admin? recipe versions]
  (let [rid (:recipe/id recipe)
        n (count versions)]
    (app-layout
      locale user-email :browse {:admin? admin?}
      [:div.max-w-3xl.mx-auto
       [:a.text-sm.text-primary-vivid.hover:text-primary {:href (str "/recipes/" rid)}
        (t locale :recipe/back-to-recipe)]
       [:h1.text-2xl.font-bold.text-text-primary.mt-2.mb-6
        (str (:recipe/title recipe) " — " (t locale :recipe/history))]
       [:ol.relative.border-l.border-border.ml-2
        ;; versions arrive oldest-first; show newest at the top
        (for [[idx v] (->> versions (map-indexed vector) reverse)]
          (let [idx (long idx)
                latest? (= idx (dec n))
                first? (zero? idx)]
            [:li.mb-6.ml-6 {:key (:t v)}
             [:span.absolute.-left-1.5.flex.h-3.w-3.rounded-full.bg-primary-vivid]
             [:div.flex.flex-wrap.items-baseline.gap-x-3
              [:span.font-medium.text-text-primary
               (str (t locale :recipe/version) " " (inc idx))]
              (when latest? [:span.text-xs.text-positive.font-medium (t locale :recipe/current)])
              (when first? [:span.text-xs.text-text-secondary (t locale :recipe/initial)])
              [:span.text-sm.text-text-secondary (fmt-time (:instant v))]]
             [:div.mt-1.flex.gap-4.text-sm
              [:a.text-primary-vivid.hover:text-primary
               {:href (str "/recipes/" rid "/at/" (:t v))} (t locale :recipe/view-this-version)]
              (when-not first?
                [:a.text-primary-vivid.hover:text-primary
                 {:href (str "/recipes/" rid "/diff?from=" (:t (nth versions (dec idx)))
                             "&to=" (:t v))}
                 (t locale :recipe/diff-from-previous)])]]))]])))

(defn recipe-version
  "Read-only point-in-time view of a recipe as of a past transaction."
  [locale user-email admin? recipe instant]
  (app-layout
    locale user-email :browse {:admin? admin?}
    [:div.max-w-3xl.mx-auto
     [:div.mb-4.rounded-md.border.border-accent.bg-amber-50.px-4.py-2.text-sm.text-warning
      (t locale :recipe/viewing-historical) " "
      [:span.font-medium (fmt-time instant)]
      " · "
      [:a.underline {:href (str "/recipes/" (:recipe/id recipe))}
       (t locale :recipe/back-to-recipe)]]
     [:h1.text-3xl.font-bold.text-text-primary (:recipe/title recipe)]
     [:p.mt-1.text-text-secondary
      (t locale :recipe/servings) " " (:recipe/servings recipe)]
     (when-not (str/blank? (:recipe/description recipe))
       [:div.legal-content.mt-4 (h/raw (markdown/render (:recipe/description recipe)))])
     [:div.mt-8.grid.gap-8.sm:grid-cols-2
      [:section
       [:h2.text-lg.font-semibold.text-text-primary.mb-2 (t locale :recipe/ingredients)]
       (text-block (:recipe/ingredients recipe) false)]
      [:section
       [:h2.text-lg.font-semibold.text-text-primary.mb-2 (t locale :recipe/steps)]
       (text-block (:recipe/steps recipe) true)]]]))

(defn- diff-lines
  "Render a line diff (seq of {:op :text}) as a monospace +/- block."
  [diff]
  [:div.font-mono.text-sm.rounded-md.border.border-border.overflow-hidden
   (for [[i {:keys [op text]}] (map-indexed vector diff)]
     [:div {:key i
            :class (case op
                     :add "diff-add px-3 py-0.5"
                     :del "diff-del px-3 py-0.5"
                     "px-3 py-0.5 text-text-secondary")}
      [:span.select-none.inline-block.w-4.opacity-60
       (case op :add "+" :del "-" " ")]
      text])])

(defn- scalar-diff
  "Render a changed scalar field (old → new)."
  [label {:keys [changed? old new]}]
  (let [nw new]
    (when changed?
      [:div.mb-4
       [:p.text-sm.font-medium.text-text-primary label]
       [:p.text-sm
        [:span.diff-del.px-1 (str old)] " → " [:span.diff-add.px-1 (str nw)]]])))

(defn recipe-diff
  "Diff between two versions of a recipe."
  [locale user-email admin? recipe from-instant to-instant d]
  (app-layout
    locale user-email :browse {:admin? admin?}
    [:div.max-w-3xl.mx-auto
     [:a.text-sm.text-primary-vivid.hover:text-primary
      {:href (str "/recipes/" (:recipe/id recipe) "/history")}
      (str "← " (t locale :recipe/history))]
     [:h1.text-2xl.font-bold.text-text-primary.mt-2
      (str (:recipe/title recipe) " — " (t locale :recipe/changes-title))]
     [:p.text-sm.text-text-secondary.mb-6
      (fmt-time from-instant) " → " (fmt-time to-instant)]
     (if (:changed? d)
       [:div
        [:div.flex.gap-4.text-xs.mb-4
         [:span [:span.diff-add.px-1 "+"] " " (t locale :recipe/legend-added)]
         [:span [:span.diff-del.px-1 "-"] " " (t locale :recipe/legend-removed)]]
        (scalar-diff (t locale :recipe/title-label) (:title d))
        (scalar-diff (t locale :recipe/servings) (:servings d))
        (scalar-diff (t locale :recipe/description-label) (:description d))
        [:section.mb-6
         [:h2.text-lg.font-semibold.text-text-primary.mb-2 (t locale :recipe/ingredients)]
         (diff-lines (:ingredients d))]
        [:section
         [:h2.text-lg.font-semibold.text-text-primary.mb-2 (t locale :recipe/steps)]
         (diff-lines (:steps d))]]
       [:p.text-text-secondary (t locale :recipe/no-changes)])]))

;; ---------------------------------------------------------------------------
;; Dashboard
;; ---------------------------------------------------------------------------

(defn dashboard
  "Signed-in user's home: their own recipes."
  [locale user-email admin? recipes]
  (app-layout
    locale user-email :dashboard {:admin? admin?}
    [:div.flex.items-center.justify-between.mb-6
     [:h1.text-2xl.font-bold.text-text-primary (t locale :dashboard/your-recipes)]
     [:a.inline-flex.items-center.gap-1.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.px-3.py-2.rounded-md
      {:href "/recipes/new"} "+ " (t locale :recipe/new)]]
    (if (seq recipes)
      [:div.grid.gap-4.sm:grid-cols-2
       (for [r recipes] ^{:key (:recipe/id r)} (recipe-card locale r))]
      [:div.text-center.py-12
       [:p.text-text-secondary (t locale :dashboard/no-recipes)]
       [:a.mt-4.inline-block.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.px-4.py-2.rounded-md
        {:href "/recipes/new"} (t locale :dashboard/create-cta)]])))
