(ns myapp.auth.email
  "SMTP email delivery for magic link authentication.
  Uses Jakarta Mail directly (via Eclipse Angus) — no wrapper library.

  Two hazards shape this namespace, both learned from the resilience audit:

  1. The relay is a dependency that can be *slow*, not just down. Jakarta's
     socket timeouts default to INFINITE, so a relay that completes the TCP
     handshake but then stalls would pin the caller forever. `smtp-session`
     sets finite connect/read/write timeouts so a stall is bounded.
  2. Login IS email, and the send used to run inline on the http-kit worker
     thread — so a stalled relay would starve the request pool and take
     `/health` (same pool) down with it, a one-dependency total outage. The
     send now runs on a small BOUNDED background mailer (`deliver-magic-link!`);
     the request thread never blocks on the relay, and a relay outage backs
     up against a bounded queue (backpressure) instead of the whole box."
  (:require
    [clojure.tools.logging :as log]
    [myapp.config :as config]
    [myapp.i18n :refer [t]]
    [myapp.web.metrics :as metrics])
  (:import
    [jakarta.mail Message$RecipientType Session Transport]
    [jakarta.mail.internet InternetAddress MimeMessage]
    [java.util Properties]
    [java.util.concurrent ArrayBlockingQueue ExecutorService RejectedExecutionException
     ThreadFactory ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit]))

(set! *warn-on-reflection* true)

(defn- smtp-session
  "Create a Jakarta Mail session from SMTP config.
  Finite socket timeouts are the per-send deadline: Jakarta defaults them to
  infinite, so a relay that accepts the connection and then stalls would hang
  the sender indefinitely. Connect 5s, read 10s, write 10s — a stalled relay
  fails fast and is caught, rather than parking a mailer thread forever."
  ^Session []
  (let [{:keys [host port tls user pass]} (config/get-config :smtp)
        props (doto (Properties.)
                (.put "mail.smtp.host" (str host))
                (.put "mail.smtp.port" (str port))
                (.put "mail.smtp.starttls.enable" (str (boolean tls)))
                (.put "mail.smtp.connectiontimeout" "5000")
                (.put "mail.smtp.timeout" "10000")
                (.put "mail.smtp.writetimeout" "10000"))]
    (if user
      (do
        (.put props "mail.smtp.auth" "true")
        (Session/getInstance
          props
          (proxy [jakarta.mail.Authenticator] []
            (getPasswordAuthentication [] (jakarta.mail.PasswordAuthentication. user pass)))))
      (Session/getInstance props))))

(defn- deliver!
  "Send one plain-text email synchronously via SMTP.
  Returns {:error :SUCCESS} or {:error :FAIL :message ..}."
  [to subject body]
  (let [^String from (config/get-config :smtp :from)
        ^Session session (smtp-session)
        msg (doto (MimeMessage. session)
              (.setFrom (InternetAddress. from))
              (.setRecipient Message$RecipientType/TO (InternetAddress. ^String to))
              (.setSubject ^String subject)
              (.setText ^String body))]
    (try
      (Transport/send msg)
      {:error :SUCCESS}
      (catch Exception e
        (log/error e "Failed to send email"
          {:to to
           :subject subject})
        {:error :FAIL
         :message (.getMessage e)}))))

(defn send-magic-link!
  "Send a magic link email to `email` via SMTP (synchronous).
  Returns {:error :SUCCESS} or {:error :FAIL :message ..}. This is the raw
  send; `deliver-magic-link!` is what the request path calls, to keep the
  blocking SMTP work off the worker thread."
  [locale email token base-url]
  (let [magic-link (str base-url "/auth/verify?token=" token)
        result (deliver!
                 email
                 (t locale :email/magic-link-subject)
                 (format (t locale :email/magic-link-body) magic-link))]
    (when (= :SUCCESS (:error result)) (log/info "Magic link email sent" {:to email}))
    result))

(defn send-notification!
  "Send a plain-text notification email (synchronous). Returns {:error ..}.
  Called from a background job handler, off the request thread by construction,
  where the job queue — not the in-memory mailer — is the durable retry layer."
  [to subject body]
  (deliver! to subject body))

;; ---------------------------------------------------------------------------
;; The bounded background mailer — keeps the relay off the request thread
;; ---------------------------------------------------------------------------

(defonce
  ^{:private true
    :doc "The bounded mailer ExecutorService, or nil when stopped."}
  mailer
  (atom nil))

(defn- send-and-record!
  "Run one send and fold its outcome into the email metric.
  A discarded failure is an invisible outage — login silently stops working
  while every availability signal stays green (ch.38's thesis)."
  [locale email token base-url]
  (let [{:keys [error]} (send-magic-link! locale email token base-url)]
    (metrics/record-email! (if (= error :SUCCESS) :success :fail))))

(defn start-mailer!
  "Start the bounded background mailer if not already running (idempotent).
  Tied to the server lifecycle (core.clj), like the presence reaper, so a dev
  reload cannot stack a second. Core 1 / max 2 threads, a 100-deep bounded
  queue, and an abort policy: a relay outage fills the queue and then REJECTS
  (counted as a failure) rather than growing without bound."
  []
  (swap! mailer
    (fn [ex]
      (or
        ex
        (ThreadPoolExecutor.
          1
          2
          30
          TimeUnit/SECONDS
          (ArrayBlockingQueue. 100)
          (reify
            ThreadFactory
              (newThread [_ r] (doto (Thread. r "magic-link-mailer") (.setDaemon true))))
          (ThreadPoolExecutor$AbortPolicy.)))))
  nil)

(defn stop-mailer!
  "Stop the background mailer (drops queued sends). Paired with start-mailer!."
  []
  (swap! mailer
    (fn [ex]
      (when ex (.shutdownNow ^ExecutorService ex))
      nil))
  nil)

(defn deliver-magic-link!
  "Hand the magic-link send to the bounded background mailer and return at once.
  The request thread never blocks on the relay — a slow or dead relay can no
  longer pin a worker or take `/health` down with it. If the queue is full (a
  relay outage backing up), the send is dropped and counted, not queued
  without bound. With no mailer running (a REPL, or the e2e/unit stubs), it
  sends inline so the behaviour is never silently a no-op."
  [locale email token base-url]
  (if-let [^ExecutorService ex @mailer]
    (try
      (.submit ex ^Runnable (fn [] (send-and-record! locale email token base-url)))
      nil
      (catch RejectedExecutionException _
        (log/error "Mailer queue full — magic-link send dropped" {:to email})
        (metrics/record-email! :fail)))
    (send-and-record! locale email token base-url)))
