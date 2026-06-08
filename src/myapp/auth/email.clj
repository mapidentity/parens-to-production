(ns myapp.auth.email
  "SMTP email delivery for magic link authentication.
  Uses Jakarta Mail directly (via Eclipse Angus) — no wrapper library."
  (:require
    [clojure.tools.logging :as log]
    [myapp.config :as config]
    [myapp.i18n :refer [t]])
  (:import
    [jakarta.mail Message$RecipientType Session Transport]
    [jakarta.mail.internet InternetAddress MimeMessage]
    [java.util Properties]))

(set! *warn-on-reflection* true)

(defn- smtp-session
  "Create a Jakarta Mail session from SMTP config."
  ^Session []
  (let [{:keys [host port tls user pass]} (config/get-config :smtp)
        props (doto (Properties.)
                (.put "mail.smtp.host" (str host))
                (.put "mail.smtp.port" (str port))
                (.put "mail.smtp.starttls.enable" (str (boolean tls))))]
    (if user
      (do
        (.put props "mail.smtp.auth" "true")
        (Session/getInstance
          props
          (proxy [jakarta.mail.Authenticator] []
            (getPasswordAuthentication [] (jakarta.mail.PasswordAuthentication. user pass)))))
      (Session/getInstance props))))

(defn send-magic-link!
  "Send magic link email to user via SMTP."
  [locale email token base-url]
  (let [magic-link (str base-url "/auth/verify?token=" token)
        ^String from (config/get-config :smtp :from)
        ^Session session (smtp-session)
        msg (doto (MimeMessage. session)
              (.setFrom (InternetAddress. from))
              (.setRecipient Message$RecipientType/TO (InternetAddress. ^String email))
              (.setSubject ^String (t locale :email/magic-link-subject))
              (.setText ^String (format (t locale :email/magic-link-body) magic-link)))]
    (try
      (Transport/send msg)
      (log/info "Magic link email sent" {:to email})
      {:error :SUCCESS}
      (catch Exception e
        (log/error e "Failed to send magic link email" {:to email})
        {:error :FAIL
         :message (.getMessage e)}))))
