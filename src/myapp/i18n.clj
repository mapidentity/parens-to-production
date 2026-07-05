(ns myapp.i18n
  "Internationalization.
  Provides the t function for translating namespaced keys by locale. Translations
  live in myapp.i18n.nl and myapp.i18n.en as plain Clojure defs —
  hot-reloadable via the Beholder file watcher."
  (:require
    [myapp.i18n.en :as en]
    [myapp.i18n.nl :as nl])
  (:import
    [java.util Locale Locale$LanguageRange]))

(set! *warn-on-reflection* true)

(def default-locale
  "Fallback locale when a key is missing in the requested locale, and the default for visitors whose Accept-Language matches neither :en nor :nl."
  :en)

(def ^:private locale-vars
  "Map of locale keyword to the var holding its translation map.
  Var derefs ensure hot-reloaded translations take effect immediately."
  {:nl #'nl/translations
   :en #'en/translations})

(defn t
  "Translate key for locale. Falls back to default locale, then to key name."
  [locale k]
  (or
    (when-let [v (locale-vars locale)]
      (get @v k))
    (get @(locale-vars default-locale) k)
    (name k)))

(defn detect-locale
  "Detect best matching locale from an Accept-Language header value.
  Uses Java's built-in RFC 4647 language matching. Returns nil if no match."
  [accept-language]
  (try
    (when accept-language
      (let [ranges (Locale$LanguageRange/parse accept-language)
            supported (mapv #(Locale/of (name %)) (keys locale-vars))
            match (Locale/lookup ranges supported)]
        (when match (keyword (.getLanguage ^Locale match)))))
    (catch Exception _ nil)))