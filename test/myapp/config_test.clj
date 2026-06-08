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
