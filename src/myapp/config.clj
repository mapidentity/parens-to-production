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

(defn- require-session-key-length!
  "Refuse a session key that is not exactly 16 bytes.

  Ring's cookie session store uses AES-128, whose key is exactly 16 bytes. Ring
  itself asserts this when the cookie store is built, throwing a bare
  `AssertionError: the secret key must be exactly 16 bytes`. We check earlier,
  during config resolution, so a misconfigured `SESSION_KEY` fails with a
  domain-specific message before the middleware stack is even assembled."
  ^bytes [^bytes k]
  (when (not= (alength k) 16)
    (throw
      (ex-info
        (str "Session key must be exactly 16 bytes (got " (alength k)
             "). Ring's cookie store uses AES-128.")
        {:length (alength k)})))
  k)

(defn- require-signing-key-strength!
  "Refuse a HMAC-SHA256 signing key shorter than the 256-bit hash output.

  A key shorter than the block/output size weakens the MAC; HMAC keys should
  carry at least as much entropy as the digest. 32 bytes is the floor. Caught
  at boot rather than discovered as a silently-weak signature."
  ^bytes [^bytes k]
  (when (< (alength k) 32)
    (throw
      (ex-info
        (str
          "Signing key must be at least 32 bytes (got "
          (alength k)
          "). HMAC-SHA256 needs a key with at least 256 bits of entropy.")
        {:length (alength k)})))
  k)

(defn- resolve-keys
  "Convert string keys to bytes, with profile-aware fallback policy.

  In :dev, generate random keys when env vars are unset. In :prod, fail
  closed — refusing to start beats silently drifting apart across
  instances or invalidating sessions on each restart."
  [config profile]
  (-> config
      (update :session-key
              (fn [^String k]
                (require-session-key-length!
                  (or (when k (.getBytes k "ISO-8859-1"))
                      (do (require-prod-key! profile "SESSION_KEY")
                          (println "⚠️  Generating random session key (dev mode)")
                          (println "⚠️  Sessions will not survive server restart")
                          (generate-session-key))))))
      (update :signing-key
              (fn [^String k]
                (require-signing-key-strength!
                  (or (when k (.getBytes k "ISO-8859-1"))
                      (do (require-prod-key! profile "SIGNING_KEY")
                          (println "⚠️  Generating random signing key (dev mode)")
                          (generate-signing-key))))))))

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
