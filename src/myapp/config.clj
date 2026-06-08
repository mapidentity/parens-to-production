(ns myapp.config
  "Configuration loading via Aero.
  Reads config.edn with profile-based overrides (:dev, :prod). Generates
  random crypto keys in dev mode when not configured."
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]
    [crypto.random :as random]))

(set! *warn-on-reflection* true)

(defn generate-session-key
  "Generate a secure random 16-byte key for session encryption."
  []
  (random/bytes 16))

(defn generate-signing-key
  "Generate a secure random 32-byte key for HMAC-SHA256 signing."
  []
  (.getBytes ^String (random/base64 32) "ISO-8859-1"))

(defn- active-profile
  "Returns the active config profile from MYAPP_PROFILE env var, defaulting to :dev."
  []
  (keyword (or (System/getenv "MYAPP_PROFILE") "dev")))

(defn- require-prod-key!
  "Refuse to start when `var-name` is unset in :prod.

  A random fallback would invalidate sessions on every restart and
  diverge across instances of a load-balanced deployment."
  [profile var-name]
  (when (= profile :prod)
    (throw
      (ex-info
        (str
          var-name
          " env var is required in :prod — refusing to start with a random key. "
          "Random keys break multi-instance deployments and silently log out users on restart.")
        {:profile profile
         :var var-name}))))

(defn- resolve-keys
  "Convert string keys to bytes, with profile-aware fallback policy.

  In :dev, generate random keys when env vars are unset. In :prod, fail
  closed — refusing to start beats silently drifting apart across
  instances or invalidating sessions on each restart."
  [config profile]
  (-> config
      (update :session-key
              (fn [^String k]
                (or (when k (.getBytes k "ISO-8859-1"))
                    (do (require-prod-key! profile "SESSION_KEY")
                        (println "⚠️  Generating random session key (dev mode)")
                        (println "⚠️  Sessions will not survive server restart")
                        (generate-session-key)))))
      (update :signing-key
              (fn [^String k]
                (or (when k (.getBytes k "ISO-8859-1"))
                    (do (require-prod-key! profile "SIGNING_KEY")
                        (println "⚠️  Generating random signing key (dev mode)")
                        (generate-signing-key)))))))

(defn- require-prod-admin-email!
  "Refuse to start when :prod is missing ADMIN_EMAIL.

  The admin gate (`wrap-admin`) compares the session email to this value;
  an unset admin email would silently block /admin for everyone, which
  we'd rather catch at boot than discover when needed."
  [profile config]
  (when (and (= profile :prod) (nil? (:admin-email config)))
    (throw (ex-info "ADMIN_EMAIL env var is required in :prod" {:profile profile})))
  config)

(defn load-config
  "Load and resolve config.edn for the given profile (defaults to active profile)."
  ([] (load-config (active-profile)))
  ([profile]
   (-> (io/resource "config.edn")
       (aero/read-config {:profile profile})
       (resolve-keys profile)
       (->> (require-prod-admin-email! profile)))))

(def config
  "Delayed config map. Deref triggers a one-time load from config.edn."
  (delay (load-config)))

(defn get-config
  "Get configuration value by path."
  [& path]
  (get-in @config path))
