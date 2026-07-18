(ns myapp.web.security
  "Structured security-event logging — the forensic trail and fail2ban's feed.

  Everything here goes to the dedicated `myapp.security` logger, which
  logback routes to its own stream (resources/logback.xml) so security
  events are grep-able and parseable apart from application noise. The
  line format is deliberately machine-first and stable:

    SECURITY event=auth.failure ip=203.0.113.9 reason=bad-token detail=…

  fail2ban's filter (ops/fail2ban/) keys its `<HOST>` on that `ip=` field,
  so the format is a CONTRACT: change it and update the filter. Events
  carry the real client IP (see wrap-client-ip) — the whole point, since
  the app's own socket only ever sees the loopback proxy."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

(defn- fmt
  "Render one `key=value` pair, sanitizing the value.
  Both whitespace AND `=` are collapsed to `_`, because a value carries
  attacker-controlled data (an email, a URL). Whitespace could smuggle a
  newline and forge a second event line; a stray `=` could smuggle a
  second `key=value` token — e.g. `ip=` inside an email local part — that
  fail2ban's parser would bind its <HOST> to instead of the real client.
  Guaranteeing at most one `ip=` per line is a security invariant, not
  cosmetics."
  [k v]
  (str (name k) "=" (str/replace (str v) #"[\s=]+" "_")))

(defn event!
  "Emit a security event to the dedicated security logger.
  `event` is a dotted keyword (:auth/failure); `fields` is a map (always
  include :ip). Logged at WARN under this namespace's logger, which
  logback routes to the security stream."
  [event fields]
  (log/warn
    (str
      "SECURITY " (fmt :event (str (namespace event) "." (name event)))
      " " (str/join " " (map (fn [[k v]] (fmt k v)) fields)))))
