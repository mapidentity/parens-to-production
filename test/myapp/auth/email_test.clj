(ns myapp.auth.email-test
  "Integration tests for magic link email delivery.
  Uses an embedded GreenMail SMTP server to verify subject, recipient, body
  content, and i18n."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [myapp.auth.email :as email]
    [myapp.config :as config]
    [myapp.test-helpers :as h]
    [myapp.web.metrics :as metrics])
  (:import
    [com.icegreen.greenmail.util GreenMail ServerSetup]
    [jakarta.mail.internet MimeMessage]))

(set! *warn-on-reflection* true)

(def ^:dynamic *greenmail*
  "Bound to a running GreenMail instance per test by the with-greenmail fixture."
  nil)

(defn with-greenmail
  "Fixture: starts GreenMail SMTP on a dynamic port, stubs config to point at it."
  [f]
  (let [setup (ServerSetup. 0 "127.0.0.1" "smtp")
        gm (GreenMail.
             ^"[Lcom.icegreen.greenmail.util.ServerSetup;" (into-array ServerSetup [setup]))]
    (.start gm)
    (try
      (let [port (.getPort (.getSmtp gm))
            test-cfg (assoc h/test-config
                       :smtp {:host "127.0.0.1"
                              :port port
                              :tls false
                              :user nil
                              :pass nil
                              :from "noreply@myapp.test"})]
        (with-redefs [config/config (delay test-cfg)]
          (binding [*greenmail* gm]
            (f))))
      (finally (.stop gm)))))

(use-fixtures :each with-greenmail)

(deftest send-magic-link-delivers-email
  (let [result
        (email/send-magic-link! :nl "user@example.com" "test-token-abc" "https://myapp.test")]
    (is (= :SUCCESS (:error result)))
    (let [messages (.getReceivedMessages ^GreenMail *greenmail*)]
      (is (= 1 (alength messages)))
      (let [^MimeMessage msg (aget messages 0)]
        (is (= "Inloggen bij MyApp" (.getSubject msg)))
        (is (= "noreply@myapp.test" (str (first (.getFrom msg)))))
        (let [body (str (.getContent msg))]
          (is (str/includes? body "https://myapp.test/auth/verify?token=test-token-abc"))
          (is (str/includes? body "15 minuten")))))))

(deftest send-magic-link-recipient-is-correct
  (email/send-magic-link! :nl "other@example.com" "token-123" "https://myapp.test")
  (let [messages (.getReceivedMessages ^GreenMail *greenmail*)
        ^MimeMessage msg (aget messages 0)
        recipients (.getAllRecipients msg)]
    (is (= "other@example.com" (str (first recipients))))))

(deftest send-magic-link-smtp-failure-returns-error
  (let [_ (.stop ^GreenMail *greenmail*)
        result (email/send-magic-link! :nl "user@example.com" "token-fail" "https://myapp.test")]
    (is (= :FAIL (:error result)))
    (is (string? (:message result)))))

(deftest send-magic-link-english-locale
  (let [result
        (email/send-magic-link! :en "user@example.com" "token-en" "https://myapp.test")]
    (is (= :SUCCESS (:error result)))
    (let [messages (.getReceivedMessages ^GreenMail *greenmail*)]
      (is (= 1 (alength messages)))
      (let [^MimeMessage msg (aget messages 0)]
        (is (= "Sign in to MyApp" (.getSubject msg)))
        (let [body (str (.getContent msg))]
          (is (str/includes? body "https://myapp.test/auth/verify?token=token-en"))
          (is (str/includes? body "15 minutes")))))))

(deftest send-magic-link-authenticated-smtp
  ;; Covers the SMTP auth branch in smtp-session (when user/pass are configured).
  (let [setup (ServerSetup. 0 "127.0.0.1" "smtp")
        gm (GreenMail.
             ^"[Lcom.icegreen.greenmail.util.ServerSetup;" (into-array ServerSetup [setup]))]
    (.start gm)
    (try
      (.setUser gm "testuser@myapp.test" "testuser" "testpass")
      (let [port (.getPort (.getSmtp gm))
            test-cfg (assoc h/test-config
                       :smtp {:host "127.0.0.1"
                              :port port
                              :tls false
                              :user "testuser"
                              :pass "testpass"
                              :from "noreply@myapp.test"})]
        (with-redefs [config/config (delay test-cfg)]
          (let [result
                (email/send-magic-link! :nl "user@example.com" "auth-token" "https://myapp.test")]
            (is (= :SUCCESS (:error result)))
            (let [messages (.getReceivedMessages gm)]
              (is (= 1 (alength messages)))))))
      (finally (.stop gm)))))

(deftest deliver-inline-sends-and-counts-the-outcome
  ;; With no mailer running (a REPL, or these tests), deliver falls back to an
  ;; inline send so it is never a silent no-op — and folds the outcome into the
  ;; metric that makes an email outage visible.
  (let [before (:success @metrics/emails)]
    (email/deliver-magic-link! :nl "inline@example.com" "tok" "https://myapp.test")
    (testing "the message actually went out (inline fallback)"
      (is
        (= 1 (alength (.getReceivedMessages ^com.icegreen.greenmail.util.GreenMail *greenmail*)))))
    (testing "a success was counted" (is (= (inc before) (:success @metrics/emails))))))

(deftest deliver-through-the-mailer-eventually-sends
  ;; The bounded background mailer path: submit, then wait (bounded) for the
  ;; daemon thread to run the send. Off-thread, so the request never blocks.
  (email/start-mailer!)
  (try
    (email/deliver-magic-link! :nl "async@example.com" "tok2" "https://myapp.test")
    (let [gm ^com.icegreen.greenmail.util.GreenMail *greenmail*]
      (is (.waitForIncomingEmail gm 3000 1) "the mailer delivered within 3s"))
    (finally (email/stop-mailer!))))

(deftest mailer-lifecycle-is-idempotent
  (email/stop-mailer!)
  (let [ratom @#'email/mailer]
    (is (nil? @ratom) "baseline: stopped")
    (email/start-mailer!)
    (let [ex1 @ratom]
      (is (some? ex1) "started")
      (email/start-mailer!)
      (is (identical? ex1 @ratom) "starting again does not spawn a second mailer"))
    (email/stop-mailer!)
    (is (nil? @ratom) "stopped: cleared")))
