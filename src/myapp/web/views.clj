(ns myapp.web.views
  "Hiccup view templates for all pages.
  Each function returns a Hiccup data structure wrapped in a shared layout.
  All user-facing text uses i18n translations keyed by locale."
  (:require
    [clojure.data.json :as json]
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
(defn-asset trace-overlay-script "myapp/web/trace-overlay.js")

(def ^:private formatter-for
  "Localized date/time formatter for a locale keyword, in the display timezone.
  Localizes month names (and weekday names when the pattern asks for them);
  memoized, one formatter per locale."
  (let [zone (ZoneId/of "Europe/Amsterdam")]
    (memoize
      (fn [locale]
        (.withZone
          (DateTimeFormatter/ofPattern "d MMM yyyy, HH:mm" (Locale/of (name locale)))
          zone)))))

(defn- fmt-time
  "Format an Instant as a human date/time in the display timezone, localized to `locale`."
  [locale ^Instant inst]
  (when inst (.format ^DateTimeFormatter (formatter-for (or locale :en)) inst)))

(defn- author-name
  "Display name for a pulled `:recipe/user` map, falling back to the email local-part."
  [user]
  (or
    (:user/display-name user)
    (some-> (:user/email user)
            (str/split #"@")
            first)
    "anonymous"))

;; ---------------------------------------------------------------------------
;; Layout
;; ---------------------------------------------------------------------------

(defn- script-tag
  "A <script> for a served asset, with SRI integrity when the manifest provides it (prod).
  `attrs` adds e.g. {:type \"module\"} or {:defer true}."
  [logical attrs]
  (let [url (assets/asset logical)]
    [:script
     (cond-> (assoc attrs
               :src url)
       (assets/asset-sri url) (assoc :integrity
                                (assets/asset-sri url)))]))

(defn- base-layout
  "Base HTML5 wrapper. All pages use this — never called directly by page fns.
  `page-meta` (may be nil) makes the page legible to machines: `:title` and
  `:description` override the defaults; `:canonical` names the one true URL;
  `:robots` opts a page out of indexing; `:json-ld` is a structured-data map
  emitted as application/ld+json (never executed, so the strict CSP is
  indifferent to it); `:og` adds Open Graph pairs for link unfurlers."
  [locale page-meta & body]
  ;; Rendered with the ESCAPING hiccup2 renderer (h/html): all string content is
  ;; HTML-escaped by default — the primary XSS defense. Only h/raw content
  ;; (markdown, inline scripts/styles) is emitted verbatim; the strict CSP is the
  ;; defense-in-depth backup.
  (h/html
    {:mode :html}
    (h/raw "<!DOCTYPE html>")
    [:html {:lang (name locale)}
     [:head [:meta {:charset "UTF-8"}]
      [:meta
       {:name "viewport"
        :content "width=device-width, initial-scale=1.0"}]
      [:meta
       {:name "description"
        :content (or (:description page-meta) (t locale :meta/description))}]
      [:title
       (if-let [t* (:title page-meta)]
         (str t* " — MyApp")
         "MyApp")]
      (when-let [href (:canonical page-meta)]
        [:link
         {:rel "canonical"
          :href href}])
      (when-let [robots (:robots page-meta)]
        [:meta
         {:name "robots"
          :content robots}])
      (for [[k content] (:og page-meta)]
        [:meta
         {:property (str "og:" (name k))
          :content content}])
      (when-let [ld (:json-ld page-meta)]
        [:script {:type "application/ld+json"}
         (h/raw (json/write-str ld))])
      [:link
       {:rel "icon"
        :type "image/svg+xml"
        :href "/icon.svg"}]
      [:link
       {:rel "stylesheet"
        :href (assets/asset "styles.css")}]
      ;; Import map (must precede any module script) remaps each module's absolute
      ;; import specifier to its hashed URL in prod; identity no-op in dev.
      [:script {:type "importmap"} (h/raw (assets/importmap-json))]
      ;; Idiomorph (classic script) must load before the dispatcher module
      ;; so window.Idiomorph is available when dispatcher.js runs.
      (script-tag "idiomorph" {:defer true})
      ;; Error capture first: module execution follows document order, so
      ;; its listeners are attached before any other island runs a line.
      (script-tag "js/client-errors.js" {:type "module"})
      (script-tag "js/dispatcher.js" {:type "module"})
      ;; Island layer: the registry, then the controllers that build on it.
      (script-tag "js/controllers.js" {:type "module"})
      (script-tag "js/sortable.js" {:type "module"})
      (script-tag "js/confirm.js" {:type "module"})
      (script-tag "js/live-form.js" {:type "module"})
      (script-tag "js/defer-details.js" {:type "module"})
      (script-tag "js/server-preview.js" {:type "module"})
      (script-tag "js/admin-stats.js" {:type "module"})
      (script-tag "js/tagline.js" {:type "module"})
      (script-tag "js/viewers.js" {:type "module"})
      ;; Speculation Rules: prerender same-origin GET pages on hover, so a click
      ;; activates an already-built page. Honoured only where supported; an inert
      ;; <script type=speculationrules> elsewhere. CSP allows it by content hash.
      (assets/speculation-rules-tag)
      [:style
       (h/raw
         "@keyframes page-enter{from{opacity:.92;transform:translateY(3px)}to{opacity:1;transform:translateY(0)}}main{animation:page-enter .12s ease-out}")]]
     [:body (tag-root body)
      [:div#toast-container.fixed.bottom-4.right-4.z-50
       {:aria-live "polite"
        :aria-atomic "true"}]
      (toast-script)
      ;; Dev-only: live-reload + source inspector overlay (absent in prod).
      (when
        (try
          (requiring-resolve 'dev-reload/websocket-handler)
          (catch Exception _ nil))
        (list (dev-reload-script) (inspector-script) (trace-overlay-script)))]]))

(defn- hero-icon
  "Inline 24x24 Heroicon-style outline SVG. `paths` is a seq of d-strings."
  [paths]
  [:svg.h-5.w-5
   {:fill "none"
    :viewBox "0 0 24 24"
    :stroke-width "1.5"
    :stroke "currentColor"}
   (for [d paths]
     [:path
      {:stroke-linecap "round"
       :stroke-linejoin "round"
       :d d}])])

(def ^:private nav-icons
  {:browse
   ["M12 6.042A8.967 8.967 0 0 0 6 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 0 1 6 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 0 1 6-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0 0 18 18a8.967 8.967 0 0 0-6 2.292m0-14.25v14.25"]
   :new ["M12 4.5v15m7.5-7.5h-15"]
   :dashboard
   ["M3.75 6A2.25 2.25 0 0 1 6 3.75h2.25A2.25 2.25 0 0 1 10.5 6v2.25a2.25 2.25 0 0 1-2.25 2.25H6a2.25 2.25 0 0 1-2.25-2.25V6ZM3.75 15.75A2.25 2.25 0 0 1 6 13.5h2.25a2.25 2.25 0 0 1 2.25 2.25V18a2.25 2.25 0 0 1-2.25 2.25H6A2.25 2.25 0 0 1 3.75 18v-2.25ZM13.5 6a2.25 2.25 0 0 1 2.25-2.25H18A2.25 2.25 0 0 1 20.25 6v2.25A2.25 2.25 0 0 1 18 10.5h-2.25A2.25 2.25 0 0 1 13.5 8.25V6ZM13.5 15.75a2.25 2.25 0 0 1 2.25-2.25H18a2.25 2.25 0 0 1 2.25 2.25V18A2.25 2.25 0 0 1 18 20.25h-2.25A2.25 2.25 0 0 1 13.5 18v-2.25Z"]
   :admin
   ["M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87l.074.04c.326.196.72.257 1.076.124l1.217-.456a1.125 1.125 0 0 1 1.37.49l1.296 2.247a1.125 1.125 0 0 1-.26 1.431l-1.003.827c-.293.241-.438.613-.431.992a6.759 6.759 0 0 1 0 .255c-.007.38.138.75.43.992l1.005.828c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 0 1-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 0 1-.074.04c-.332.183-.582.495-.644.869l-.214 1.281c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.282a1.823 1.823 0 0 0-.645-.869 6.48 6.48 0 0 1-.074-.04c-.326-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 0 1-1.369-.49l-1.297-2.247a1.125 1.125 0 0 1 .26-1.431l1.004-.828c.292-.24.437-.613.43-.991a6.932 6.932 0 0 1 0-.255c.007-.38-.138-.751-.43-.992l-1.004-.828a1.125 1.125 0 0 1-.26-1.43l1.297-2.247a1.125 1.125 0 0 1 1.37-.491l1.216.456c.356.133.751.072 1.076-.124l.074-.04c.332-.183.582-.495.645-.869l.213-1.28Z"
    "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"]})

(defn- nav-tab
  "Single tab in the top navigation bar."
  [locale label-key href icon-key active?]
  [:a
   {:href href
    ;; The label is hidden below the `sm` breakpoint, leaving an icon-only
    ;; link; the aria-label keeps the link's accessible name present at every
    ;; viewport (and matches the visible text on `sm`+).
    :aria-label (t locale label-key)
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
       [:img.h-7
        {:src "/scriptlogo-white.svg"
         :alt "MyApp"
         :width 132
         :height 32}]]
      (nav-tab locale :nav/browse "/recipes" :browse (= active-tab :browse))
      (when user-email (nav-tab locale :nav/new "/recipes/new" :new (= active-tab :new)))
      (when user-email
        (nav-tab locale :nav/dashboard "/dashboard" :dashboard (= active-tab :dashboard)))
      (when admin? (nav-tab locale :nav/admin "/admin" :admin (= active-tab :admin)))]
     [:div.flex.items-center.gap-x-2
      (if user-email
        (list
          [:span
           {:class "text-sm text-white/70 hidden sm:block"} user-email]
          [:form
           {:method "POST"
            :action "/auth/logout"}
           [:button
            {:type "submit"
             :class "text-sm text-white/70 hover:text-white"}
            (t locale :auth/sign-out)]])
        [:a
         {:href "/"
          :class "text-sm text-white/70 hover:text-white"}
         (t locale :home/sign-in)])]]]])

(defn public-layout
  "Centered card layout for unauthenticated pages (landing, auth, terms)."
  [locale & body]
  (base-layout
    locale
    nil
    [:main.min-h-screen.bg-surface-subtle.flex.items-center.justify-center.px-4
     {:data-layout "public"}
     [:div.max-w-md.w-full body]]))

(defn app-layout
  "Layout with the top navigation.
  Used for recipe browsing (public OR signed in), the dashboard, and admin.
  `opts` may include `:admin?`. `user-email`may be nil for anonymous visitors
  browsing recipes."
  [locale user-email active-tab opts & body]
  (let [admin? (:admin? opts)]
    (base-layout
      locale
      (:page-meta opts)
      [:div.min-h-screen.flex.flex-col.bg-surface-subtle
       (top-nav locale user-email active-tab admin?)
       [:main.flex-1 {:data-layout "app"}
        [:div.mx-auto.max-w-5xl.px-4.py-8.sm:px-6
         body]]])))

;; ---------------------------------------------------------------------------
;; Landing + auth + terms
;; ---------------------------------------------------------------------------

(defn home-page
  "Landing page with the magic-link sign-in form."
  [locale]
  (public-layout
    locale
    [:div.space-y-8
     [:div.text-center
      [:img.h-12.mx-auto
       {:src "/logo.svg"
        :alt "MyApp"
        :width 220
        :height 48}]
      ;; A fixed default tagline keeps this page deterministic (cacheable /
      ;; prerenderable); the `tagline` island swaps in a random one after load,
      ;; and with no JS the default simply stays. See handler/tagline.
      [:p#tagline.mt-3.text-lg.font-medium.text-text-primary
       {:data-controller "tagline"
        :data-tagline-url "/partials/tagline"}
       (t locale :home/tagline-1)]
      [:p.mt-2.text-sm.text-text-secondary (t locale :home/lead)]]
     [:div.bg-surface.py-8.px-6.border.border-border.rounded-lg
      [:h2.text-2xl.font-semibold.text-text-primary.mb-4 (t locale :home/get-started)]
      [:form
       {:method "POST"
        :action "/auth/request"}
       [:div
        [:label.block.text-sm.font-medium.text-text-primary {:for "email"}
         (t locale :home/email-label)]
        [:input.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid.focus:border-primary-vivid
         {:type "email"
          :id "email"
          :name "email"
          :required true
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
       {:fill "none"
        :viewBox "0 0 24 24"
        :stroke "currentColor"
        :stroke-width "1.5"
        :stroke-linecap "round"
        :stroke-linejoin "round"}
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
      [:img.h-12.mx-auto
       {:src "/logo.svg"
        :alt "MyApp"
        :width 220
        :height 48}]
      [:h2.mt-4.text-2xl.font-semibold.text-text-primary (t locale :terms/welcome-title)]]
     [:div.bg-surface.py-8.px-6.border.border-border.rounded-lg
      [:p.text-text-secondary (t locale :terms/welcome-explanation)]
      [:p.mt-3.text-sm.text-text-secondary (t locale :terms/terms-summary)]
      [:form.mt-8
       {:method "POST"
        :action "/terms/accept"}
       [:button.w-full.flex.justify-center.py-3.px-4.border.border-transparent.rounded-md.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-primary-vivid
        {:type "submit"} (t locale :terms/accept-button)]]
      [:div.mt-4.text-center
       [:form
        {:method "POST"
         :action "/auth/logout"}
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
  "Small 'forked from X' pill linking to the parent recipe.
  `.relative.z-10` lifts it above a card's stretched-link ::after overlay so it
  stays independently clickable (see `recipe-card`)."
  [locale recipe]
  (when-let [parent (:recipe/forked-from recipe)]
    [:a.fork-badge.relative.z-10.inline-flex.items-center.gap-1.text-xs.text-text-secondary.hover:text-primary-vivid
     {:href (str "/recipes/" (:recipe/id parent))}
     [:svg.h-3.5.w-3.5
      {:fill "none"
       :viewBox "0 0 24 24"
       :stroke-width "1.5"
       :stroke "currentColor"}
      [:path
       {:stroke-linecap "round"
        :stroke-linejoin "round"
        :d
        "M6 3v12m0 0a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm0-12a3 3 0 1 0 0-.001M18 9a3 3 0 1 0 0-.001M18 9c0 4-6 3-6 9"}]]
     (t locale :recipe/forked-from) " " [:span.font-medium (:recipe/title parent)]]))

(defn- recipe-card
  "Summary card for a recipe in a list.
  The whole card is clickable via a STRETCHED LINK on the title -- its ::after
  overlays the card (see .card-link in the stylesheet) -- while the fork badge is
  a SEPARATE sibling link that sits above the overlay. The card and the badge are
  never nested `<a>`s: an anchor inside an anchor is invalid HTML, and the browser
  splits it (hoisting the inner link out and leaving an empty stub)."
  [locale recipe]
  [:div.card.relative.bg-surface.border.border-border.rounded-lg.p-5
   [:div.flex.items-baseline.justify-between.gap-3
    [:h3.text-lg.font-semibold.text-text-primary
     [:a.card-link.no-underline.text-text-primary
      {:href (str "/recipes/" (:recipe/id recipe))} (:recipe/title recipe)]]
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
      [tag
       {:class (str (if ordered? "list-decimal" "list-disc") " pl-5 space-y-1 text-text-primary")}
       (for [line items]
         [:li line])]
      [:p.text-sm.text-text-secondary "—"])))

(defn- lineage-trail
  "Breadcrumb of ancestors with the 'descends from N ancestors' summary."
  [_locale lineage]
  (when (seq lineage)
    [:div.mb-6.rounded-lg.border.border-border.bg-surface-subtle.px-4.py-3
     [:p.text-sm.font-medium.text-text-primary
      (let [n (count lineage)]
        (if (= n 1) "Descends from 1 ancestor" (str "Descends from " n " ancestors")))]
     [:nav.mt-2.flex.flex-wrap.items-center.gap-1.text-sm
      ;; oldest → newest (lineage is parent-first, so reverse for root-first)
      (->> (reverse lineage)
           (map-indexed (fn [i anc]
                          (list (when (pos? (long i)) [:span.text-text-secondary "→"])
                                [:a.text-primary-vivid.hover:text-primary
                                 {:href (str "/recipes/" (:recipe/id anc))}
                                 (:recipe/title anc)
                                 " "
                                 [:span.text-text-secondary
                                  (str "(" (author-name (:recipe/user anc)) ")")]]))))
      [:span.text-text-secondary "→"]
      [:span.font-medium.text-text-primary "this recipe"]]]))

(defn- owner-actions-menu
  "Owner-only Edit/Delete as a declarative Popover menu (Layer 1).
  The menu opens with zero JS via `popovertarget`; CSS anchor positioning places
  it under the trigger. Delete carries `data-controller=confirm`, so it asks via
  a <dialog> when JS is present and posts directly otherwise."
  [locale recipe]
  (let [id (:recipe/id recipe)
        pop-id (str "actions-" id)
        anchor (str "--" pop-id)]
    [:div.relative.inline-block
     [:button.actions-trigger
      {:type "button"
       :popovertarget pop-id
       :style (str "anchor-name:" anchor)
       :aria-label (t locale :recipe/actions)}
      "⋯"]
     [:div.actions-menu
      {:id pop-id
       :popover "auto"
       :style (str "position-anchor:" anchor)}
      [:a {:href (str "/recipes/" id "/edit")} (t locale :recipe/edit)]
      [:form
       {:method "POST"
        :action (str "/recipes/" id "/delete")
        :data-controller "confirm"
        :data-confirm (t locale :recipe/delete-confirm)
        :data-confirm-ok (t locale :recipe/delete)
        :data-confirm-cancel (t locale :common/cancel)}
       [:button.actions-danger {:type "submit"} (t locale :recipe/delete)]]]]))

;; ---------------------------------------------------------------------------
;; Recipe pages
;; ---------------------------------------------------------------------------

(defn- search-form
  "The search box — a plain GET form, so layer 0 needs nothing else.
  Submitting navigates to /search?q=…; the dispatcher upgrades it to a
  morph like any other GET."
  [locale q]
  [:form.flex.gap-2.mb-6
   {:method "GET"
    :action "/search"
    :role "search"}
   [:label.sr-only {:for "q"} (t locale :search/label)]
   [:input#q.flex-1.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
    {:type "search"
     :name "q"
     :value q
     :placeholder (t locale :search/placeholder)}]
   [:button.py-2.px-4.rounded-md.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid
    {:type "submit"} (t locale :search/button)]])

(defn search-page
  "The public search results page.
  For /search?q=… — `results` nil means no query was made; [] means a
  query found nothing."
  [locale user-email admin? q results]
  (app-layout
    locale
    user-email
    :browse
    {:admin? admin?}
    [:div.max-w-3xl.mx-auto
     [:h1.text-2xl.font-bold.text-text-primary.mb-6 (t locale :search/title)]
     (search-form locale q)
     (cond
       (nil? results) nil
       (seq results)
       [:div.grid.gap-4.sm:grid-cols-2
        (for [r results]
          (recipe-card locale r))]
       :else [:p.text-text-secondary (t locale :search/no-results)])]))

(defn recipes-index
  "Public browse list of all recipes."
  [locale user-email admin? recipes]
  (app-layout
    locale
    user-email
    :browse
    {:admin? admin?}
    [:div.flex.items-center.justify-between.mb-6
     [:h1.text-2xl.font-bold.text-text-primary (t locale :recipe/browse-title)]
     (when user-email
       [:a.inline-flex.items-center.gap-1.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.px-3.py-2.rounded-md
        {:href "/recipes/new"} "+ " (t locale :recipe/new)])]
    (search-form locale nil)
    (if (seq recipes)
      [:div.grid.gap-4.sm:grid-cols-2
       (for [r recipes]
         (recipe-card locale r))]
      [:p.text-text-secondary (t locale :recipe/no-recipes)])))

(defn- recipe-body
  "The recipe's content: markdown description, ingredients, steps.
  Shared verbatim between the detail page and the live-preview pane: the preview is not a reimplementation of this view, it IS this
  view, fed a database value that was never transacted."
  [locale recipe]
  (list
    (when-not (str/blank? (:recipe/description recipe))
      [:div.legal-content.mt-4 (h/raw (markdown/render (:recipe/description recipe)))])
    [:div.mt-8.grid.gap-8.sm:grid-cols-2
     [:section
      [:h2.text-lg.font-semibold.text-text-primary.mb-2 (t locale :recipe/ingredients)]
      (text-block (:recipe/ingredients recipe) false)]
     [:section
      [:h2.text-lg.font-semibold.text-text-primary.mb-2 (t locale :recipe/steps)]
      (text-block (:recipe/steps recipe) true)]]))

(defn- recipe-json-ld
  "The schema.org/Recipe structured-data map.
  Built from the SAME pulled map the page renders — one source of truth,
  two audiences. Emitted by
  base-layout as application/ld+json (data, never executed; the strict CSP
  is indifferent to it)."
  [recipe canonical]
  (cond-> {"@context" "https://schema.org"
           "@type" "Recipe"
           "name" (:recipe/title recipe)
           "author" {"@type" "Person"
                     "name" (author-name (:recipe/user recipe))}
           "recipeYield" (str (:recipe/servings recipe) " servings")
           "recipeIngredient" (recipe/lines (:recipe/ingredients recipe))
           "recipeInstructions" (mapv (fn [s]
                                        {"@type" "HowToStep"
                                         "text" s})
                                      (recipe/lines (:recipe/steps recipe)))
           "url" canonical}
    (:recipe/created-at recipe) (assoc "datePublished"
                                  (str (:recipe/created-at recipe)))
    (:recipe/updated-at recipe) (assoc "dateModified"
                                  (str (:recipe/updated-at recipe)))
    (not (str/blank? (:recipe/description recipe))) (assoc "description"
                                                      (:recipe/description recipe))))

(defn recipe-detail
  "A single recipe at its current version, with actions, lineage, and forks.
  When `base-url` is present the page introduces itself to machines too:
  canonical URL, Open Graph pairs, and schema.org/Recipe JSON-LD."
  [locale user-email admin? recipe
   {:keys [owner? lineage forks version-count base-url pending-proposals]}]
  (app-layout
    locale
    user-email
    :browse
    {:admin? admin?
     :page-meta (when base-url
                  (let [canonical (str base-url "/recipes/" (:recipe/id recipe))
                        description (first (recipe/lines (:recipe/description recipe)))]
                    (cond-> {:title (:recipe/title recipe)
                             :canonical canonical
                             :og (cond-> {:title (:recipe/title recipe)
                                          :type "article"
                                          :url canonical
                                          :site_name "MyApp"}
                                   description (assoc :description
                                                 description))
                             :json-ld (recipe-json-ld recipe canonical)}
                      description (assoc :description
                                    description))))}
    [:div.max-w-3xl.mx-auto
     (lineage-trail locale lineage)
     [:div.flex.items-start.justify-between.gap-4
      [:div
       [:h1.text-3xl.font-bold.text-text-primary (:recipe/title recipe)]
       [:p.mt-1.text-text-secondary
        (t locale :recipe/by) " " [:span.font-medium (author-name (:recipe/user recipe))]
        " · " (t locale :recipe/servings) " " (:recipe/servings recipe)]
       ;; Live viewer count — filled in by the viewers island over SSE.
       ;; Empty (and hidden) until JS connects: pure enhancement.
       [:p.mt-1.text-sm.text-primary-vivid
        {:data-viewers-url (str "/recipes/" (:recipe/id recipe) "/viewers")
         :hidden true}]]
      [:div.flex.flex-col.items-end.gap-2
       (if user-email
         [:form
          {:method "POST"
           :action (str "/recipes/" (:recipe/id recipe) "/fork")}
          [:button.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.px-3.py-2.rounded-md
           {:type "submit"} (t locale :recipe/fork-this)]]
         [:a.text-sm.font-semibold.text-primary-vivid.hover:text-primary {:href "/"}
          (t locale :recipe/login-to-fork)])
       ;; If you own this recipe AND it is a fork, you can propose its
       ;; changes back to the recipe it descends from — a pull request.
       (when (and owner? (:recipe/forked-from recipe))
         [:form
          {:method "POST"
           :action (str "/recipes/" (:recipe/id recipe) "/propose")}
          [:button.text-sm.font-medium.text-primary-vivid.hover:text-primary
           {:type "submit"} (t locale :proposal/propose)]])
       (when owner? (owner-actions-menu locale recipe))]]

     ;; Pending suggestions for the owner: forks proposing changes back.
     (when (and owner? (seq pending-proposals))
       [:div
        {:class "mt-6 rounded-md border border-primary bg-primary/5 p-4"}
        [:p.text-sm.font-medium.text-text-primary
         (t locale :proposal/pending-heading)]
        [:ul.mt-2.space-y-1
         (for [p pending-proposals]
           [:li
            [:a.text-sm.text-primary-vivid.hover:text-primary.underline
             {:href (str "/proposals/" (:proposal/id p))}
             (t locale :proposal/review-suggestion)]])]])

     (recipe-body locale recipe)

     [:div.mt-8.flex.flex-wrap.items-center.gap-4.text-sm.border-t.border-border.pt-4
      [:a.text-primary-vivid.hover:text-primary
       {:href (str "/recipes/" (:recipe/id recipe) "/history")}
       (str (t locale :recipe/history) " (" version-count ")")]
      [:span.text-text-secondary
       (t locale :recipe/updated) " " (fmt-time locale (:recipe/updated-at recipe))]]

     [:section.mt-8
      [:h2.text-lg.font-semibold.text-text-primary.mb-3 (t locale :recipe/forks)]
      (if (seq forks)
        [:ul.space-y-2
         (for [f forks]
           [:li
            [:a.text-primary-vivid.hover:text-primary {:href (str "/recipes/" (:recipe/id f))}
             (:recipe/title f)]
            [:span.text-text-secondary.text-sm
             " " (t locale :recipe/by) " " (author-name (:recipe/user f))]])]
        [:p.text-sm.text-text-secondary (t locale :recipe/no-forks)])]]))

(defn- recipe-preview-content
  "Inside of the live-preview pane.
  A compact header (title, servings) over the shared `recipe-body`, or the
  waiting hint until the input conforms."
  [locale recipe]
  (if recipe
    (list
      [:h2.text-2xl.font-bold.text-text-primary (:recipe/title recipe)]
      [:p.mt-1.text-sm.text-text-secondary
       (t locale :recipe/servings) " " (:recipe/servings recipe)]
      (recipe-body locale recipe))
    [:p.text-sm.text-text-secondary (t locale :recipe/preview-waiting)]))

(defn recipe-preview-pane
  "The fragment the preview endpoint returns.
  The root id must stay `recipe-preview`: it is the stable morph target — the dispatcher picks
  this element out of the response and morphs its children over the live
  pane's, leaving the pane's own marker attributes untouched."
  [locale recipe]
  [:div#recipe-preview
   (recipe-preview-content locale recipe)])

(defn- field-error
  "Inline error line under a form field, or nil.
  `codes` is the vector of error-code keywords `recipe/conform` produced for
  this field; we show the first — one problem at a time reads better than a
  stack. The element id is what the input's `aria-describedby` points at."
  [locale field codes]
  (when-let [code (first codes)]
    [:p.mt-1.text-sm.text-negative
     {:id (str (name field) "-error")}
     (t locale (keyword "error" (str (name field) "-" (name code))))]))

(defn- field-aria
  "Accessibility attributes for a field in error.
  Screen readers announce the state and read the error line as the field's
  description."
  [field codes]
  (when (seq codes)
    {:aria-invalid "true"
     :aria-describedby (str (name field) "-error")}))

(defn recipe-form
  "Create/edit form. `recipe` is nil for new, or a pulled map for edit.

  The optional trailing map carries a failed submission back to the user:
  `:errors` is `recipe/conform`'s `{field [code …]}`, and `:submitted` the
  raw params — which take precedence over the stored recipe, so what the
  user typed survives the round trip, mistakes and all. A field's error
  renders directly under it; the input keeps `aria-invalid` +
  `aria-describedby` so the failure is announced, not just painted."
  ([locale user-email admin? recipe] (recipe-form locale user-email admin? recipe nil))
  ([locale user-email admin? recipe {:keys [errors submitted conflict?]}]
   (let [editing? (some? recipe)
         action (if editing? (str "/recipes/" (:recipe/id recipe) "/edit") "/recipes/new")
         active (if editing? :browse :new)
         ;; Submitted values (raw strings) win over stored ones: after a 422
         ;; the form must show what was typed, not what the database holds.
         fv (fn [field db-key] (if submitted (get submitted field) (get recipe db-key)))]
     (app-layout
       locale
       user-email
       active
       {:admin? admin?}
       [:div.max-w-6xl.mx-auto.lg:grid.lg:grid-cols-2.lg:gap-10
        [:div
         [:h1.text-2xl.font-bold.text-text-primary.mb-6
          (if editing? (t locale :recipe/edit) (t locale :recipe/new))]
         ;; The conflict banner: someone else saved this recipe between the
         ;; time the form was loaded and this submission. The edits below are
         ;; preserved; the hidden token now carries the CURRENT version, so a
         ;; second save is a deliberate overwrite.
         (when conflict?
           [:div
            {:class "mb-5 rounded-md border border-negative bg-negative/10 p-4"}
            [:p.text-sm.font-medium.text-negative (t locale :recipe/conflict-title)]
            [:p.mt-1.text-sm.text-text-secondary
             (t locale :recipe/conflict-body) " "
             [:a.underline {:href (str "/recipes/" (:recipe/id recipe) "/history")}
              (t locale :recipe/conflict-review)]]])
         [:form#recipe-form.space-y-5
          {:method "POST"
           :action action}
          ;; The optimistic-concurrency token: the recipe's content clock
          ;; (`:recipe/updated-at`) at the moment this form was rendered.
          ;; Scoped to THIS recipe on purpose — the global basis-t would
          ;; advance on every unrelated write and conflict on every save.
          (when editing?
            [:input
             {:type "hidden"
              :name "expected-version"
              :value (some-> (:recipe/updated-at recipe)
                             inst-ms
                             str)}])
          [:div
           [:label.block.text-sm.font-medium.text-text-primary {:for "title"}
            (t locale :recipe/title-label)]
           [:input#title.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
            (merge
              {:type "text"
               :name "title"
               :required true
               :value (fv :title :recipe/title)}
              (field-aria :title (:title errors)))]
           (field-error locale :title (:title errors))]
          [:div
           [:label.block.text-sm.font-medium.text-text-primary {:for "servings"}
            (t locale :recipe/servings-label)]
           [:input#servings.mt-1.block.w-32.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
            (merge
              {:type "number"
               :name "servings"
               :min "1"
               :value (if submitted (:servings submitted) (or (:recipe/servings recipe) 1))}
              (field-aria :servings (:servings errors)))]
           (field-error locale :servings (:servings errors))]
          [:div
           [:label.block.text-sm.font-medium.text-text-primary {:for "description"}
            (t locale :recipe/description-label)]
           [:textarea#description.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
            (merge
              {:name "description"
               :rows "3"}
              (field-aria :description (:description errors)))
            (fv :description :recipe/description)]
           (field-error locale :description (:description errors))]
          [:div
           [:label.block.text-sm.font-medium.text-text-primary {:for "ingredients"}
            (t locale :recipe/ingredients-label)]
           [:textarea#ingredients.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.font-mono.text-sm.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
            (merge
              {:name "ingredients"
               :rows "8"}
              (field-aria :ingredients (:ingredients errors)))
            (fv :ingredients :recipe/ingredients)]
           (field-error locale :ingredients (:ingredients errors))]
          [:div
           [:label.block.text-sm.font-medium.text-text-primary {:for "steps"}
            (t locale :recipe/steps-label)]
           [:textarea#steps.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.font-mono.text-sm.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
            (merge
              {:name "steps"
               :rows "8"}
              (field-aria :steps (:steps errors)))
            (fv :steps :recipe/steps)]
           (field-error locale :steps (:steps errors))]
          ;; The commit message. Only offered on edits: a creation IS its own
          ;; note, and fork provenance is recorded structurally.
          (when editing?
            [:div
             [:label.block.text-sm.font-medium.text-text-primary {:for "note"}
              (t locale :recipe/note-label)]
             [:input#note.mt-1.block.w-full.px-3.py-2.border.border-border.rounded-md.focus:outline-none.focus:ring-2.focus:ring-primary-vivid
              (merge
                {:type "text"
                 :name "note"
                 :value (when submitted (:note submitted))
                 :placeholder (t locale :recipe/note-placeholder)}
                (field-aria :note (:note errors)))]
             (field-error locale :note (:note errors))])
          [:div.flex.items-center.gap-3
           [:button.py-2.px-4.rounded-md.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid
            {:type "submit"} (if editing? (t locale :recipe/save) (t locale :recipe/create))]
           (when editing?
             [:a.text-sm.text-text-secondary.hover:text-text-primary
              {:href (str "/recipes/" (:recipe/id recipe))} "Cancel"])]]]
        ;; The live-preview column. The aside is the server-preview island's
        ;; marker: on debounced input it POSTs the form's fields to the
        ;; preview endpoint and morphs the response into itself. Initial
        ;; content is server-rendered — an edit page shows the stored recipe
        ;; before a single keystroke, with no JavaScript involved.
        [:div.mt-10.lg:mt-0
         [:h2.text-xs.font-semibold.uppercase.tracking-wide.text-text-secondary.mb-3
          (t locale :recipe/preview-title)]
         [:aside#recipe-preview.rounded-lg.border.border-border.bg-surface.p-6
          {:data-preview-action
           (if editing? (str "/recipes/" (:recipe/id recipe) "/preview") "/recipes/new/preview")
           :data-preview-from "#recipe-form input, #recipe-form textarea"}
          (recipe-preview-content locale recipe)]]]))))

(defn recipe-history
  "Version timeline for a recipe (newest first), built from Datomic history."
  [locale user-email admin? recipe versions]
  (let [rid (:recipe/id recipe)
        n (count versions)]
    (app-layout
      locale
      user-email
      :browse
      {:admin? admin?}
      [:div.max-w-3xl.mx-auto
       [:a.text-sm.text-primary-vivid.hover:text-primary {:href (str "/recipes/" rid)}
        (t locale :recipe/back-to-recipe)]
       [:h1.text-2xl.font-bold.text-text-primary.mt-2.mb-6
        (str (:recipe/title recipe) " — " (t locale :recipe/history))]
       [:ol.relative.border-l.border-border.ml-2
        ;; versions arrive oldest-first; show newest at the top
        (for [[idx v] (->> versions
                           (map-indexed vector)
                           reverse)]
          (let [idx (long idx)
                latest? (= idx (dec n))
                first? (zero? idx)]
            [:li.mb-6.ml-6
             [:span.absolute.-left-1.5.flex.h-3.w-3.rounded-full.bg-primary-vivid]
             [:div.flex.flex-wrap.items-baseline.gap-x-3
              [:span.font-medium.text-text-primary
               (str (t locale :recipe/version) " " (inc idx))]
              (when latest? [:span.text-xs.text-positive.font-medium (t locale :recipe/current)])
              (when first? [:span.text-xs.text-text-secondary (t locale :recipe/initial)])
              [:span.text-sm.text-text-secondary (fmt-time locale (:instant v))]
              (when-let [author (:author v)]
                [:span.text-sm.text-text-secondary
                 (t locale :recipe/by) " " [:span.font-medium (author-name author)]])]
             (when-let [note (:note v)]
               [:p.mt-1.text-sm.text-text-primary.italic "\u201C" note "\u201D"])
             [:div.mt-1.flex.gap-4.text-sm
              [:a.text-primary-vivid.hover:text-primary
               {:href (str "/recipes/" rid "/at/" (:t v))} (t locale :recipe/view-this-version)]
              (when-not first?
                [:a.text-primary-vivid.hover:text-primary
                 {:href
                  (str "/recipes/" rid "/diff?from=" (:t (nth versions (dec idx))) "&to=" (:t v))}
                 (t locale :recipe/diff-from-previous)])]]))]])))

(defn recipe-version
  "Read-only point-in-time view of a recipe as of a past transaction.
  `page-meta` (optional) carries the noindex + canonical-to-current pair —
  a thousand historical copies of one recipe must not compete with it in a
  search index."
  ([locale user-email admin? recipe instant]
   (recipe-version locale user-email admin? recipe instant nil))
  ([locale user-email admin? recipe instant page-meta]
   (app-layout
     locale
     user-email
     :browse
     {:admin? admin?
      :page-meta page-meta}
     [:div.max-w-3xl.mx-auto
      [:div.mb-4.rounded-md.border.border-accent.bg-amber-50.px-4.py-2.text-sm.text-warning
       (t locale :recipe/viewing-historical) " "
       [:span.font-medium (fmt-time locale instant)]
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
        (text-block (:recipe/steps recipe) true)]]])))

(defn- diff-lines
  "Render a line diff (seq of {:op :text}) as a monospace +/- block."
  [diff]
  [:div.font-mono.text-sm.rounded-md.border.border-border.overflow-hidden
   (for [{:keys [op text]} diff]
     [:div
      {:class (case op
                :add "diff-add px-3 py-0.5"
                :del "diff-del px-3 py-0.5"
                "px-3 py-0.5 text-text-secondary")}
      [:span.select-none.inline-block.w-4.opacity-60
       (case op
         :add "+"
         :del "-"
         " ")]
      text])])

(defn- scalar-diff
  "Render a changed scalar field (old → new)."
  [label
   {:keys [changed? old]
    nw :new}]
  (when changed?
    [:div.mb-4
     [:p.text-sm.font-medium.text-text-primary label]
     [:p.text-sm
      [:span.diff-del.px-1 (str old)] " → " [:span.diff-add.px-1 (str nw)]]]))

(defn recipe-diff
  "Diff between two versions of a recipe.
  `page-meta` (optional): noindex + canonical-to-current, as on the
  point-in-time page."
  ([locale user-email admin? recipe from-instant to-instant d]
   (recipe-diff locale user-email admin? recipe from-instant to-instant d nil))
  ([locale user-email admin? recipe from-instant to-instant d page-meta]
   (app-layout
     locale
     user-email
     :browse
     {:admin? admin?
      :page-meta page-meta}
     [:div.max-w-3xl.mx-auto
      [:a.text-sm.text-primary-vivid.hover:text-primary
       {:href (str "/recipes/" (:recipe/id recipe) "/history")}
       (str "← " (t locale :recipe/history))]
      [:h1.text-2xl.font-bold.text-text-primary.mt-2
       (str (:recipe/title recipe) " — " (t locale :recipe/changes-title))]
      [:p.text-sm.text-text-secondary.mb-6
       (fmt-time locale from-instant) " → " (fmt-time locale to-instant)]
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
        [:p.text-text-secondary (t locale :recipe/no-changes)])])))

;; ---------------------------------------------------------------------------
;; Proposals (a pull request's three-way merge)
;; ---------------------------------------------------------------------------

(def ^:private proposal-field-meta
  "Which merge fields are line-diffed (text) vs shown as scalars."
  [{:fkey :recipe/title
    :label :recipe/title-label
    :text? false}
   {:fkey :recipe/servings
    :label :recipe/servings
    :text? false}
   {:fkey :recipe/description
    :label :recipe/description-label
    :text? false}
   {:fkey :recipe/ingredients
    :label :recipe/ingredients
    :text? true}
   {:fkey :recipe/steps
    :label :recipe/steps
    :text? true}])

(defn- merge-side
  "Render one side of a value.
  A line-diff from base for text fields, the bare value for scalars."
  [text? base v]
  (if text?
    (diff-lines (recipe/line-diff (or base "") (or v "")))
    [:span.text-sm [:span.diff-add.px-1 (str v)]]))

(defn proposal-page
  "A proposal's three-way merge: what applies cleanly, and what conflicts.
  `merge` is `proposal/merge-for`'s result. Opts: `:target-owner?` gates
  the accept/decline form; `:notice` shows a banner (e.g. after a stale
  merge or an unresolved conflict)."
  [locale user-email admin? proposal mrg {:keys [target-owner? notice]}]
  (let [{:keys [fields ours theirs conflict?]} mrg
        source-title (:recipe/title theirs)
        target-title (:recipe/title ours)
        target-id (get-in proposal [:proposal/target :recipe/id])]
    (app-layout
      locale
      user-email
      :browse
      {:admin? admin?}
      [:div.max-w-3xl.mx-auto
       [:a.text-sm.text-primary-vivid.hover:text-primary
        {:href (str "/recipes/" target-id)} (str "← " target-title)]
       [:h1.text-2xl.font-bold.text-text-primary.mt-2 (t locale :proposal/title)]
       [:p.text-sm.text-text-secondary.mb-6
        (t locale :proposal/from) " " [:span.font-medium source-title]]
       (when notice
         [:div.mb-5.rounded-md.border.border-negative.p-4
          [:p.text-sm.text-negative (t locale notice)]])

       [:form
        {:method "POST"
         :action (str "/proposals/" (:proposal/id proposal) "/accept")}
        ;; OCC token: the target's content clock, so accepting a stale merge
        ;; (the recipe moved during review) is caught, not silently applied.
        [:input
         {:type "hidden"
          :name "expected-version"
          :value (some-> (:recipe/updated-at ours)
                         inst-ms
                         str)}]
        (for [{:keys [fkey label text?]} proposal-field-meta
              :let [{:keys [status base ours theirs]
                     :as f}
                    (get fields fkey)]
              :when (not= status :unchanged)]
          [:section.mb-6.rounded-lg.border.border-border.p-4
           {:class (when (= status :conflict) "border-negative")}
           [:h2.text-sm.font-semibold.text-text-primary.mb-2 (t locale label)]
           (case status
             :applied [:div
                       [:p.text-xs.text-positive.mb-1 (t locale :proposal/applies-cleanly)]
                       (merge-side text? (:base f) theirs)]
             :conflict [:div
                        [:p.text-xs.text-negative.mb-2 (t locale :proposal/conflict)]
                        [:label.block.mb-2
                         [:input.mr-2
                          {:type "radio"
                           :name (str "resolve-" (name fkey))
                           :value "ours"
                           :checked true}]
                         [:span.text-sm.font-medium (t locale :proposal/keep-mine)]
                         (merge-side text? base ours)]
                        [:label.block
                         [:input.mr-2
                          {:type "radio"
                           :name (str "resolve-" (name fkey))
                           :value "theirs"}]
                         [:span.text-sm.font-medium (t locale :proposal/take-theirs)]
                         (merge-side text? base theirs)]])])
        (when (empty? (remove #(= :unchanged (:status (val %))) fields))
          [:p.text-text-secondary.mb-6 (t locale :proposal/nothing-to-merge)])
        (when target-owner?
          [:div.flex.items-center.gap-3.mt-6
           [:button.py-2.px-4.rounded-md.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid
            {:type "submit"}
            (if conflict? (t locale :proposal/resolve-and-merge) (t locale :proposal/merge))]
           [:button.py-2.px-4.rounded-md.text-sm.text-text-secondary.hover:text-text-primary
            {:type "submit"
             :formaction (str "/proposals/" (:proposal/id proposal) "/decline")}
            (t locale :proposal/decline)]])]
       (when-not target-owner?
         [:p.text-sm.text-text-secondary (t locale :proposal/awaiting-owner)])])))

;; ---------------------------------------------------------------------------
;; Dashboard
;; ---------------------------------------------------------------------------

(defn- drag-handle
  "Pointer drag affordance for a sortable item.
  `data-drag-handle` is the hook the sortable controller grabs (via Pointer
  Events — mouse, touch, and pen); the controller reorders the parent <li>. The
  grab cursor and `touch-action: none` come from CSS keyed on the marker."
  [locale]
  [:button.p-1.text-text-secondary.hover:text-text-primary
   {:type "button"
    :data-drag-handle true
    :aria-label (t locale :dashboard/drag-handle)}
   [:svg.h-5.w-5
    {:fill "none"
     :viewBox "0 0 24 24"
     :stroke-width "1.5"
     :stroke "currentColor"
     :aria-hidden "true"}
    [:path
     {:stroke-linecap "round"
      :stroke-linejoin "round"
      :d "M3.75 9h16.5m-16.5 6.75h16.5"}]]])

(defn- reorder-controls
  "No-JS / keyboard reorder path (Layer 0).
  Up & down submit buttons POST a single-step move to /recipes/reorder. The
  dispatcher enhances the submit into an animated morph; without JS it is an
  ordinary form post + redirect."
  [locale id]
  [:form.flex.flex-col.items-center.leading-none
   {:method "POST"
    :action "/recipes/reorder"}
   [:input
    {:type "hidden"
     :name "id"
     :value (str id)}]
   [:button.px-1.text-text-secondary.hover:text-primary-vivid
    {:type "submit"
     :name "dir"
     :value "up"
     :aria-label (t locale :dashboard/move-up)} "▲"]
   [:button.px-1.text-text-secondary.hover:text-primary-vivid
    {:type "submit"
     :name "dir"
     :value "down"
     :aria-label (t locale :dashboard/move-down)} "▼"]])

(defn- dashboard-recipe-item
  "A dashboard recipe row: a control rail beside the shared recipe card.
  The rail holds the drag handle + up/down buttons. `data-id` identifies the row
  to the sortable controller and `view-transition-name` lets it animate to its
  new slot on reorder."
  [locale recipe]
  (let [id (:recipe/id recipe)]
    [:li.flex.items-stretch.gap-2
     {:data-id (str id)
      :style (str "view-transition-name:recipe-" id)}
     [:div.flex.flex-col.items-center.justify-center.gap-1.shrink-0
      (drag-handle locale)
      (reorder-controls locale id)]
     [:div.min-w-0.flex-1 (recipe-card locale recipe)]]))

(defn- activity-panel
  "What happened while you were away, or nothing at all.
  Each entry names the actor (the recipe's own author — the forker for a
  :fork, the upstream owner for an :upstream-edit), what they did, and
  links the recipe it happened to."
  [locale items]
  (when (seq items)
    [:section.mb-8.rounded-lg.border.border-border.bg-surface.p-4
     [:h2.text-sm.font-semibold.uppercase.tracking-wide.text-text-secondary.mb-3
      (t locale :activity/title)]
     [:ul.space-y-2
      (for [{:keys [recipe at]
             entry-type :type}
            items]
        [:li.text-sm.text-text-primary
         [:span.font-medium (author-name (:recipe/user recipe))]
         " "
         (t locale (if (= entry-type :fork) :activity/forked-yours :activity/updated-upstream))
         " "
         [:a.text-primary-vivid.hover:text-primary
          {:href (str "/recipes/" (:recipe/id recipe))}
          (:recipe/title recipe)]
         [:span.text-text-secondary " · " (fmt-time locale at)]])]]))

(defn dashboard
  "Signed-in user's home: their own recipes, in their chosen order.
  Leads with the activity panel when anything happened since last visit.
  Drag to reorder — see the sortable controller and POST /recipes/reorder."
  [locale user-email admin? recipes activity]
  (app-layout
    locale
    user-email
    :dashboard
    {:admin? admin?}
    (activity-panel locale activity)
    [:div.flex.items-center.justify-between.mb-6
     [:h1.text-2xl.font-bold.text-text-primary (t locale :dashboard/your-recipes)]
     [:a.inline-flex.items-center.gap-1.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.px-3.py-2.rounded-md
      {:href "/recipes/new"} "+ " (t locale :recipe/new)]]
    (if (seq recipes)
      [:div
       [:p.mb-3.text-xs.text-text-secondary (t locale :dashboard/reorder-hint)]
       [:ul.space-y-3.list-none.p-0.m-0
        {:data-controller "sortable"
         :data-sortable-url "/recipes/reorder"}
        (for [r recipes]
          (dashboard-recipe-item locale r))]]
      [:div.text-center.py-12
       [:p.text-text-secondary (t locale :dashboard/no-recipes)]
       [:a.mt-4.inline-block.text-sm.font-semibold.text-white.bg-primary.hover:bg-primary-vivid.px-4.py-2.rounded-md
        {:href "/recipes/new"} (t locale :dashboard/create-cta)]])))
