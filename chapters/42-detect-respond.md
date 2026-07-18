# Detect and Respond: Security Events, fail2ban, and the Levers

The [operations chapters](37-runtime-legibility.md) taught the box to stay *up* and stay *legible*. This one teaches it to defend itself — and it opens by fixing a bug those chapters papered over, because the bug is also the foundation everything else here stands on.

A security operations centre, even a one-person one, needs three things the application did not have: to **see** an attack (a security-event trail), to **catch** a brute-force automatically (fail2ban), and to **respond** to a live incident without shipping a build (containment levers). The prevention layer was always strong — [strict CSP](29-asset-pipeline.md), [HMAC tokens](24-auth-tokens.md), [tenant isolation](09-recipe-domain.md), [don't-reveal auth](25-auth-email-flow.md). What was missing was everything that happens *after* prevention fails, or nearly does. That is this chapter.

## Prerequisite: the app could not see who anyone was

Behind [the reverse proxy](35-going-live.md), the application binds loopback — so every request arrives from `127.0.0.1`, and `:remote-addr` is *always* the proxy. This is not a cosmetic detail. It silently collapsed every per-IP control in the codebase to a single global bucket. [The magic-link limiter's](25-auth-email-flow.md) "10 sends per IP per 15 minutes" became *10 sends for the entire internet*: ten requests from any one attacker froze login **site-wide** for fifteen minutes — a trivial, unauthenticated auth-DoS. And any per-IP detection this chapter might build would key on a constant.

The fix is a small middleware with a sharp trust boundary:

```clojure
(defn wrap-client-ip
  "Resolve the real client IP and assoc it as `:client-ip`.
  ,,,
  We trust it solely when the TCP peer is loopback; a direct hit (no
  proxy) keeps its own peer address, so the header can never be spoofed by
  a client that bypasses Caddy."
  [handler]
  (fn [request]
    (let [peer (:remote-addr request)
          fwd (when (contains? loopback-peers peer)
                (some-> (get-in request [:headers "x-client-ip"])
                        str/trim
                        not-empty))]
      (handler (assoc request :client-ip (or fwd peer "?"))))))
```

The trust boundary is the whole point, and it is worth stating as a rule: **a forwarded header is trustworthy only from a source you control.** Caddy sets `X-Client-IP` to the real peer it saw (`header_up X-Client-IP {remote_host}`), *overwriting* whatever the client sent — so through the proxy the header is authoritative. But the app must still refuse to believe it from anyone else, because an attacker who reaches the app directly could forge it. So we trust the header only when the request's own TCP peer is the loopback proxy; a direct hit keeps its real peer address. The test pins both directions -- the proxy's header wins, a non-loopback peer's header is ignored -- because a client-IP check that can be spoofed is worse than none: it launders an attacker's address into your allowlists and your logs.

`:client-ip` now feeds the rate limiters, and everything below. `:remote-addr` stays exactly where it should — [the `/metrics` loopback belt](37-runtime-legibility.md) genuinely wants the peer, not the client.

## Seeing: the security event log

Detection begins with a record, and the application had almost none: auth failures, admin access, tenant-isolation refusals — all silent. `myapp.web.security` is the fix, and it is deliberately tiny:

```clojure
(defn event!
  "Emit a security event to the dedicated security logger.
  `event` is a dotted keyword (:auth/failure); `fields` is a map (always
  include :ip). Logged at WARN under this namespace's logger, which
  logback routes to the security stream."
  [event fields]
  (log/warn
    (str "SECURITY " (fmt :event (str (namespace event) "." (name event)))
      " " (str/join " " (map (fn [[k v]] (fmt k v)) fields)))))
```

Three decisions make it more than a `log/warn` wrapper. **It is a machine-first format** — `SECURITY event=auth.failure ip=203.0.113.9 reason=bad-token` — because two readers consume it: an operator doing forensics, and fail2ban matching for IPs. That format is a *contract*, pinned by a test, so it cannot drift out from under the filter. **It routes to its own stream.** [logback](35-going-live.md) sends `myapp.web.security` to a dedicated file (as well as journald), so the security trail is a clean, greppable stream instead of a needle in the application's log. And **`fmt` collapses whitespace in every value**, because these fields carry attacker-controlled data (an email, a URL) and a newline smuggled into a field could forge a *second* log line — a real log-injection defense, tested with exactly that payload.

The events are wired at the seams the [SecOps audit](41-beyond-one-box.md) found blind. Authentication, in [the verify handler](25-auth-email-flow.md), now distinguishes three outcomes where before it emitted one generic 400:

```clojure
    (cond
      ;; Gate 1: the signature/expiry. A nil here is a forged, tampered, or
      ;; expired token — the shape of a brute-force or a stale-link probe.
      (not (and token-data nonce-uuid))
      (do (security/event! :auth/failure {:ip ip :reason "bad-token"})
          ,,,)
      ;; Gate 2: one-shot consumption. A valid signature whose nonce is
      ;; already spent is a REPLAY — a distinct, louder signal.
      (not (consume-magic-link-nonce! nonce-uuid))
      (do (security/event! :auth/replay {:ip ip :email (:email token-data)})
          ,,,)
      :else
      (do (security/event! :auth/success {:ip ip :email email})
          ,,,))))
```

The visitor still gets the same opaque error on both failures — [don't-reveal is intact](25-auth-email-flow.md) — but the *log* now tells the operator which gate failed, and a replay (a valid signature whose nonce is already spent) is called out distinctly from a bad token, because the two mean very different things about who is knocking. The [admin gate](28-admin-dashboard.md) logs both grants and denials with the identity that tried; and tenant-isolation refusals — an authenticated user's mutation against a recipe that exists but isn't theirs — now emit `:tenancy/refused`, the signal of IDOR probing that was previously indistinguishable from a 404 and logged nowhere. That last one reuses a check the app already had the pieces for, without ever leaking existence to the prober.

## Catching: fail2ban reads the trail

A record an operator must remember to read is not detection. fail2ban turns the security log into an automatic reflex. Its filter keys on the format above:

```
failregex = ^.* SECURITY event=auth\.failure .*ip=<HOST>\b
            ^.* SECURITY event=auth\.replay .*ip=<HOST>\b
```

and the jail turns a burst into a ban:

```
[myapp]
enabled  = true
logpath  = /var/log/myapp/security.log
maxretry = 5
findtime = 10m
bantime  = 1h
banaction = nftables-multiport
```

`maxretry = 5` reflects a fact about *this* auth system: [HMAC tokens are not mistyped](24-auth-tokens.md), they are forged or replayed, so five failures from one address in ten minutes is not a fat-fingered link — it is an attack, and it earns an hour in the packet-level penalty box the [firewall](43-harden-patch.md) owns. The jail scopes deliberately to the auth events: `admin.denied` and `tenancy.refused` are logged for forensics but do not themselves ban, because a confused logged-in user probing `/admin` is not the same threat as a login brute-force, and banning them would be a self-inflicted support ticket.

This was drilled against the real thing, not the manual: the running application generated a security log (six forged-token attempts from `203.0.113.5`, two from `198.51.100.2`, one admin denial), and `fail2ban-regex` over the committed filter matched all eight auth events, extracted each `<HOST>`, and grouped them — `203.0.113.5` at six hits (over the five-strike line → **ban**), `198.51.100.2` at two (under → left alone), the admin-denial line correctly *unmatched* by the jail. The detection contract holds end to end, from the app's own log line to fail2ban's ban decision.

## Responding: alerting, and the three levers

A ban is containment; the operator should also *know*. fail2ban's action reaches them through [the box's own alert relay](38-alerting.md) — one notification channel for every incident, banned IPs included:

```
action = %(banaction)s[...]
         myapp-notify[name=%(__name__)s]
```

so a ban both blocks the source and emails, on the same spine the watchdog uses. But automatic banning only answers the shape of attack fail2ban can see. A SOC needs levers it can pull *by hand*, live, against a threat the machine did not catch — and until now the only ones were "restart the service" or "rotate the key and log everyone out." Three sharper ones, none needing a redeploy:

**Ban an IP** is fail2ban again, manually: `fail2ban-client set myapp banip 203.0.113.5` drops a source at the packet level for as long as you say, no config edit, no restart.

**Ban a user** is the flag [the admin dashboard displayed but nobody enforced](28-admin-dashboard.md). `wrap-current-user` now reads it:

```clojure
          ;; The ban lever: `:user/active?` explicitly false deactivates the
          ;; session on its very next request — live, no redeploy, no key
          ;; rotation. `false?` (not `not`) is deliberate: a missing flag
          ;; never bans, so a schema gap can't lock the userbase out.
          banned? (and eid (false? (:user/active? (db/pull* db [:user/active?] eid))))
```

`(auth/set-active! conn "attacker@x.lan" false)` from the runbook deactivates that account's session on its *next request* — and flipping it back restores the same session, a reversible ban. `false?` rather than `not` is the load-bearing subtlety: only an *explicit* false bans, so a user created before the attribute existed, or a migration that misses it, can never accidentally lock the whole userbase out. It stays a runbook action and pointedly *not* a web endpoint: [the admin surface is read-only by design](28-admin-dashboard.md), and a privileged "ban this user" route would be new attack surface guarding against attackers.

**Rotate a key without the mass logout.** [Rotating the signing key](24-auth-tokens.md) used to break every magic link in flight. Now the verifier accepts a *grace set*:

```clojure
  [signing-key token]
  (let [key-set (if (bytes? signing-key) [signing-key] signing-key)]
    (some #(verify-token-1 % token) key-set)))
```

New tokens sign with the new key; the previous key stays in the accepted set (`SIGNING_KEY_PREVIOUS`) until every outstanding link has aged past its 15-minute TTL, then it drops. Routine key hygiene stops being a disruptive event. The test walks the whole window: a token signed with the old key is rejected by the new key alone, accepted by the `[new old]` grace set, and dead again once the old key leaves it. (For an actual *compromise*, note, the mass logout is the feature, not the bug — you *want* every session gone; the [session key](25-auth-email-flow.md) rotation remains the blunt instrument for that, and the blunt instrument is correct there.)

## Trade-offs & limitations, in one place

- **fail2ban is single-box.** Its ban table lives on this host; [a second box](41-beyond-one-box.md) needs its own, or a shared ban store — the same shared-state boundary the rate limiter hits. On one box, packet-level bans are exactly right.
- **The security log is on the banned box.** fail2ban reads a local file, so a source can be banned only after its events land locally; log-shipping to an off-box SIEM (where the [off-site observer](38-alerting.md) already argues for external vantage) is the next rung and is not built here.
- **Low-and-slow slips the jail.** Five failures in ten minutes bans; four every ten minutes forever does not. The events are all logged, so the pattern is *visible* to a human or a future threshold check — but fail2ban's window catches bursts, not patience. Tightening it trades false positives against coverage, which is why the numbers are config, not code.
- **The ban-a-user lever needs the session to make a request.** `:user/active?` is checked on the *next* request, so it deactivates rather than pre-empts — correct for a stateless-cookie design with [no server-side session store](25-auth-email-flow.md), but not instantaneous. Instant global revocation is still the session-key rotation.
- **`:tenancy/refused` costs one extra read on the failure path.** Distinguishing an IDOR probe from a benign 404 means checking existence after an owner-gated mutation fails — a read paid only on the (rare) failure, and only for authenticated users. Cheap, and the signal is worth it.

## Half the SOC, standing

The box can now see an attack, catch the brute-force shape of it automatically, and be steered through a live incident without a deploy. What it cannot yet do is keep its *own* surface small and current: the host has no firewall, the dependency CVE scan is [described but not running](34-ci-cd.md), nothing patches the OS, and there is no runbook for the morning a CVE lands. Detection and response are half a security-operations practice; the other half is hardening and patching, and it is the last chapter.
