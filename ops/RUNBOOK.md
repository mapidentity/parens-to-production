# MyApp Operator Runbook

Every action here uses a lever built into the system, not a heroic
improvisation. Read it before you need it.

## The 3am triage order

1. **Is the box reachable at all?** The [off-site observer](observer/) has
   already emailed if not — DNS, TLS, or the box itself. If the site is
   dark from outside but the box answers on the LAN, it is network/DNS,
   not the app.
2. **What does the box say about itself?** `systemctl status myapp` and
   `journalctl -u myapp -S -30min`. A crash loop has already emailed
   (`OnFailure=`); a wedged-but-alive JVM has not — check `/metrics` for a
   4xx/5xx spike.
3. **Is this an attack?** `tail -n 200 /var/log/myapp/security.log` and
   `systemctl status fail2ban`. Bursts of `auth.failure`/`auth.replay`,
   `tenancy.refused`, or `admin.denied` are the tells.

## Containment levers (no redeploy)

- **Ban an IP** (packet level, temporary):
  `fail2ban-client set myapp banip <ip>` — or let the jail do it
  automatically at 5 failures / 10 min. Unban: `... unbanip <ip>`.
- **Ban a user** (their session dies on its next request, reversible):
  from a REPL on the box,
  `(myapp.auth.core/set-active! (myapp.db.core/get-connection) "<email>" false)`.
  Re-enable with `true`.
- **Revoke ALL sessions** (the blunt instrument, for a suspected session
  compromise — everyone is logged out): rotate `SESSION_KEY` in
  `/etc/myapp/env` and `systemctl restart myapp`. This is intended to be
  disruptive; that is the containment.
- **Take the app offline** but keep the branded page: `systemctl stop
  myapp` — Caddy serves the [maintenance 503](../static/error/503.html).

## Rotating a compromised SIGNING_KEY (no mass logout)

The signing key forges login tokens if leaked — rotate immediately:

1. Generate a new key: `openssl rand -hex 32`.
2. In `/etc/myapp/env`: set `SIGNING_KEY_PREVIOUS` to the CURRENT
   `SIGNING_KEY`, then set `SIGNING_KEY` to the new value.
3. `systemctl restart myapp`. New links sign with the new key; links
   already in flight keep working against the previous key.
4. After 15 minutes (the token TTL — every outstanding link has expired),
   delete `SIGNING_KEY_PREVIOUS` and restart again. The old key is dead.

For a full compromise where you do NOT want the grace window, skip step 2's
previous-key line: every in-flight link breaks (users re-request), and the
leaked key is worthless immediately.

## When a CVE lands (dependency)

1. Assess: does it touch a dependency the app actually resolves?
   `clojure -M:nvd "" "$(clojure -Spath)"` names the artifact and CVSS.
   The nightly CI run should already have flagged it (ch.34).
2. If a fix version exists: bump it in `deps.edn`, run the gates
   (`clojure -X:test`, `./lint`, `clojure -T:build uber`), deploy via the
   pipeline. The [minimal-downtime pair](../chapters/36-minimal-downtime.md)
   means no user-visible window.
3. If no fix yet and the CVE does not apply to how the dependency is used,
   add a scoped, dated, justified entry to `.nvd-suppressions.xml` so the
   gate stays green *honestly* — never suppress blind.

## When a CVE lands (OS / library)

The box's `unattended-upgrades` applies distribution security fixes
nightly (`ops/apt/`). For an urgent one: `apt-get update && apt-get
install --only-upgrade <pkg>`. A kernel fix needs a reboot, which is
manual on a single box — schedule it, and it is a few seconds of the
[maintenance page](../static/error/503.html).

## After any incident

- Preserve evidence before you clean up: `journalctl` is persistent
  (`ops/journald.conf.d/`), and `/var/log/myapp/security.log` is the
  forensic trail. Copy both off-box.
- If personal data was exposed, the [excision](../chapters/40-excision.md)
  runbook is how you erase it for good — and the disclosure clock is a
  legal question, not a technical one.
- Write down what the lever was. This file grows from incidents.
