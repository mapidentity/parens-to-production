(ns myapp.config
  "Configuration loading via Aero.
  Reads config.edn with profile-based overrides (:dev, :prod). Generates
  random crypto keys in dev mode when not configured."
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]
    [clojure.string :as str]
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

(defn- parse-session-key
  "The two spellings of SESSION_KEY, disambiguated by length.

  32 hex characters decode to the 16 AES-128 key bytes — what
  `openssl rand -hex 16` prints, and the spelling to prefer: the full 128
  bits of entropy survive the trip through a text env file. A raw
  16-character string is used byte-for-byte (ISO-8859-1) — it satisfies
  Ring's length assertion but carries only as much entropy as its
  characters do (16 hex chars: 64 bits — half the strength the cipher was
  chosen for). The lengths cannot collide: raw is 16 chars, hex is 32."
  ^bytes [^String k]
  (if (re-matches #"[0-9a-fA-F]{32}" k)
    (let [out (byte-array 16)]
      (dotimes [i 16]
        (aset out i (unchecked-byte (Integer/parseInt (subs k (* 2 i) (+ 2 (* 2 i))) 16))))
      out)
    (.getBytes k "ISO-8859-1")))

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
        (str
          "Session key must be exactly 16 bytes (got "
          (alength k)
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
                  (or (when k (parse-session-key k))
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
                          (generate-signing-key))))))
      ;; The optional rotation-grace key: bytes when SIGNING_KEY_PREVIOUS is
      ;; set (strength-checked like the primary), nil otherwise. No fallback
      ;; and no prod requirement — it exists only during a rotation window.
      (update :signing-key-previous
              (fn [^String k]
                (when k (require-signing-key-strength! (.getBytes k "ISO-8859-1")))))))

(def ^:private prod-required
  "Config paths that must resolve in :prod, with the env var supplying each.
  The comment on each entry is what its nil would otherwise become — none
  of them fails as legibly as a boot refusal."
  [[[:database-uri] "DATABASE_URI"] ; bare NPE inside the Peer at create-database!
   [[:analytics-database-uri] "ANALYTICS_DATABASE_URI"] ; the same NPE, analytics side
   [[:admin-email] "ADMIN_EMAIL"] ; admin gate silently locks out everyone
   [[:base-url] "BASE_URL"] ; login emails carry broken relative links
   [[:smtp :host] "SMTP_HOST"] ; login emails silently never send
   [[:smtp :from] "SMTP_FROM"]]) ; first sign-in throws building the message

(defn- require-prod-config!
  "Refuse to start when :prod is missing required configuration.

  Aero's #env yields nil for an unset variable, and every nil in
  `prod-required` fails somewhere worse than boot: an NPE in the Peer, a
  sign-in that silently sends nothing behind the don't-reveal confirmation
  page, an email whose link doesn't resolve. Boot is the one place they
  can all fail loudly, next to the table that explains them."
  [profile config]
  (when (= profile :prod)
    (when-let [missing (seq
                         (for [[path env-var] prod-required
                               :when (nil? (get-in config path))]
                           env-var))]
      (throw
        (ex-info
          (str (str/join ", " missing) " env var(s) are required in :prod — refusing to start.")
          {:profile profile
           :missing (vec missing)})))
    (when (and (get-in config [:smtp :user]) (nil? (get-in config [:smtp :pass])))
      (throw (ex-info "SMTP_PASS is required in :prod when SMTP_USER is set." {:profile profile}))))
  config)

(defn load-config
  "Load and resolve config.edn for the given profile (defaults to active profile)."
  ([] (load-config (active-profile)))
  ([profile]
   (-> (io/resource "config.edn")
       (aero/read-config {:profile profile})
       (resolve-keys profile)
       (->> (require-prod-config! profile)))))

(def config
  "Delayed config map. Deref triggers a one-time load from config.edn."
  (delay (load-config)))

(defn get-config
  "Get configuration value by path."
  [& path]
  (get-in @config path))
