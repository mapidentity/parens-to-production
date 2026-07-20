(ns myapp.config-test
  "Tests for config loading: expected keys, crypto key sizes, nested path access."
  (:require
    [clojure.test :refer [deftest is]]
    [myapp.config :as config]))

(deftest dev-profile-loads
  (let [cfg (config/load-config :dev)]
    (is (map? cfg))
    (is (contains? cfg :server))
    (is (contains? cfg :session-key))
    (is (contains? cfg :signing-key))
    (is (contains? cfg :smtp))
    (is (contains? cfg :base-url))))

(deftest session-key-is-16-bytes
  (let [cfg (config/load-config :dev)]
    (is (bytes? (:session-key cfg)))
    (is (= 16 (alength ^bytes (:session-key cfg))))))

(deftest signing-key-is-byte-array
  (let [cfg (config/load-config :dev)]
    (is (bytes? (:signing-key cfg)))))

(deftest get-config-nested-path
  (with-redefs [config/config (delay {:server {:port 3000}})]
    (is (= 3000 (config/get-config :server :port)))))

(deftest get-config-missing-key
  (with-redefs [config/config (delay {:server {:port 3000}})]
    (is (nil? (config/get-config :nonexistent)))))

(deftest prod-refuses-missing-config
  ;; Every #env under :prod is a boot refusal, not a runtime surprise:
  ;; a nil database URI would be an NPE in the Peer, a nil SMTP host a
  ;; login email that silently never sends, a nil base URL a broken link
  ;; inside that email. The private guard is tested directly because
  ;; load-config's key resolution throws first when the env is empty.
  (let [ex (try
             (#'config/require-prod-config! :prod {:smtp {}})
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "missing prod config must throw")
    (is
      (=
        ["DATABASE_URI" "ANALYTICS_DATABASE_URI" "ADMIN_EMAIL"
         "BASE_URL" "SMTP_HOST" "SMTP_FROM"]
        (:missing (ex-data ex)))
      "every missing var is named in one refusal"))
  (let [complete {:database-uri "datomic:sql://myapp?jdbc:postgresql://h/d"
                  :analytics-database-uri "datomic:sql://a?jdbc:postgresql://h/d"
                  :admin-email "admin@example.com"
                  :base-url "https://example.com"
                  :smtp {:host "smtp.example.com"
                         :from "no-reply@example.com"}}]
    (is
      (= complete (#'config/require-prod-config! :prod complete))
      "complete config passes through untouched")
    (is
      (thrown?
        clojure.lang.ExceptionInfo
        (#'config/require-prod-config!
         :prod
         (assoc complete
           :smtp {:host "h"
                  :from "f"
                  :user "u"})))
      "SMTP_USER without SMTP_PASS is refused")))

(deftest prod-without-session-key-fails-fast
  ;; The :prod profile reads SESSION_KEY/SIGNING_KEY/ADMIN_EMAIL from env.
  ;; In test the env is not set, so loading the prod profile must throw —
  ;; we'd rather refuse to start than fall back to a random key that
  ;; breaks multi-instance deployments and silently logs everyone out
  ;; on each restart.
  (let [missing-vars (atom #{})
        try-load (fn []
                   (try
                     (config/load-config :prod)
                     :loaded
                     (catch clojure.lang.ExceptionInfo e
                       (swap! missing-vars conj
                         (-> e
                             ex-data
                             :var))
                       :threw)))]
    (is (= :threw (try-load)) "prod profile must fail fast without env vars")))

(deftest session-key-hex-decoding
  ;; SESSION_KEY prefers 32 hex chars decoded to the 16 AES-128 key bytes —
  ;; full 128-bit entropy through a text env file (`openssl rand -hex 16`).
  ;; A raw 16-char string is still honored byte-for-byte; the lengths cannot
  ;; collide (raw is 16 chars, hex is 32).
  (let [hex "00ff10a04b3c2d1e00ff10a04b3c2d1e"
        ^bytes decoded (#'config/parse-session-key hex)]
    (is (= 16 (alength decoded)))
    (is
      (= [0x00 0xff 0x10 0xa0] (map #(bit-and 0xff %) (take 4 decoded)))
      "hex pairs decode to key bytes, not characters")
    (is
      (= 16 (alength ^bytes (#'config/parse-session-key "raw-16-char-key!")))
      "the raw 16-char spelling still works byte-for-byte")))
