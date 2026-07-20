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
   4xx/5xx spike, a climbing `presence_channels`/`process_open_fds` (an FD
   leak), or a stalled `email_send_total{outcome="fail"}`. Before restarting a
   wedge, [capture a thread dump](#a-wedged-but-alive-jvm) — the restart
   destroys the only evidence of *why*.
3. **Is the data layer intact?** If pages 500 but the process is up, suspect
   Datomic. `systemctl status myapp-transactor` (the peer rides out a blip for
   reads but not writes); `df -h` for the storage/data mount; check the
   [backup is fresh](#restore-from-backup) if you may need it. A dead disk or a
   wrong `rm` on the data directory is a [restore](#restore-from-backup), not a
   restart.
4. **Is this an attack?** `tail -n 200 /var/log/myapp/security.log` and
   `systemctl status fail2ban`. Bursts of `auth.failure`/`auth.replay`,
   `tenancy.refused`, or `admin.denied` are the tells.

## Containment levers (no redeploy)

- **Ban an IP** (packet level, temporary):
  `fail2ban-client set myapp banip <ip>` — or let the jail do it
  automatically at 5 failures / 10 min. Unban: `... unbanip <ip>`.
- **Ban a user** (their session dies on its next request, reversible):
  over the [loopback REPL](#the-production-repl) —
  `(myapp.auth.core/set-active! (myapp.db.core/get-connection) "<email>" false)`.
  Re-enable with `true`.
- **Revoke ALL sessions** (the blunt instrument, for a suspected session
  compromise — everyone is logged out): rotate `SESSION_KEY` in
  `/etc/myapp/env` and `systemctl restart myapp`. This is intended to be
  disruptive; that is the containment.
- **Take the app offline** but keep the branded page: `systemctl stop
  myapp` — Caddy serves the [maintenance 503](../static/error/503.html).
- **Disable ONE broken feature?** There is no per-feature kill-switch — the
  levers are whole-app. If a single feature is actively *corrupting data* (a
  bad write path), the correct move is the whole-app 503 above: integrity over
  availability. If it is only *melting the box* (a runaway query), ban the
  actor (IP/user) and, if that fails, 503. Naming this gap is the honest
  single-box answer; see [ch.46](../chapters/46-watching-the-watchers.md).

## The production REPL

The live levers above (ban a user, inspect the presence registry or config,
apply a surgical fix) run in a **loopback-only socket REPL** the app starts
when `MYAPP_REPL_PORT` is set (5555 in `myapp.service`). It binds `127.0.0.1`
only — a connection is unauthenticated code execution, so it must never be
reachable from the network. Reach it by tunnelling over SSH, which is the
authentication:

```
ssh -L 5555:127.0.0.1:5555 box     # then, locally:
rlwrap nc 127.0.0.1 5555           # a bare prompt into the running process
```

Rules of the road, because this is total power over the live process:

- **It is not a redeploy.** That is the point — you can read and repair the
  running state that a restart would erase. But every action is real and
  immediate; there is no dry-run and no undo.
- **Read before you write.** Confirm the entity you are about to change
  (`(d/pull (d/db conn) '[*] eid)`) before you transact against it.
- **Irreversible actions get a second person.** A retraction, an
  [excision](../chapters/40-excision.md), a `restore` — pair on it. The
  3am-typo-against-the-wrong-datom is its own incident.
- **It is audited.** The REPL runs as the app user; `journalctl` and shell
  history are the trail. Write down what you ran in the incident notes.

## A wedged-but-alive JVM

The one failure that trips neither `Restart=` nor `OnFailure=`: the process
answers `/health` but serves nothing well — every worker blocked on a slow
query, or an SMTP stall (now bounded, but a dependency can still be slow).
`/metrics` shows *that* (a 5xx/latency climb) but not *why*. Get the why
**before** you restart, because the restart destroys it:

```
pid=$(systemctl show -p MainPID --value myapp)
jcmd "$pid" Thread.print > /var/log/myapp/threaddump-$(date -Is).txt
```

Read the dump for a pile-up of threads parked in the same place (a lock, a
socket read, a Datomic call). If the JVM died of OutOfMemoryError, the unit
already wrote `java_pid<pid>.hprof` to `/var/log/myapp/` before exiting —
**that file contains secrets (SMTP creds, session tokens) in cleartext**;
copy it off for analysis, then delete it from the box, and treat it as
exactly as sensitive as `/etc/myapp/env`. Only then restart.

## Restore from backup

The [nightly backup](../chapters/39-backup-restore.md) is drilled weekly by
`myapp-backup-verify.timer` (it restores into scratch storage and proves
history survived — so the archive you reach for here has been *proven*
restorable, not merely written). To restore for real, restore into **fresh
storage under the same database name** — Datomic refuses a rename (the
drill's first stumble: a database's identity travels with it), so "fresh"
means a new PostgreSQL database (the distribution's stock `bin/sql`
bootstrap scripts, thirty seconds), never a new name, and never over the
live storage, which stays standing as evidence:

```
# 1. Create the fresh shelf (a second PostgreSQL database, same role):
#    psql -f bin/sql/postgres-db.sql … → datomic_restore
# 2. Restore the archive into it — same db names, new storage:
datomic restore-db "$BACKUP_URI/myapp" \
  "datomic:sql://myapp?jdbc:postgresql://localhost:5432/datomic_restore?user=…"
datomic restore-db "$BACKUP_URI/myapp-analytics" \
  "datomic:sql://myapp-analytics?jdbc:postgresql://localhost:5432/datomic_restore?user=…"
# 3. A peer finds its transactor THROUGH storage (the drill's second
#    stumble), so the transactor must serve the restored shelf before any
#    peer can: edit sql-url in /etc/myapp/transactor.properties to
#    …/datomic_restore…, edit DATABASE_URI + ANALYTICS_DATABASE_URI in
#    /etc/myapp/env to match, then:
systemctl restart myapp-transactor myapp
```

Rolling back the restore is editing those lines back. Photos restore as a
file copy: `rsync` the backup's `uploads/` tree back onto the uploads root
(the mirror is a superset of what any restored database references — see
the grace-window argument in [ch.49](../chapters/49-file-storage.md)). If the backup target is
off-box (`BACKUP_URI=s3://…`, as production should be — the local disk shares
the failure domain you are recovering from), the DB restores from there and
the uploads/config come back with `aws s3 sync "$BACKUP_URI/uploads/"
"$UPLOADS_ROOT/"` — the same channel the nightly backup pushed them out on.

## Roll back a bad deploy

A deploy that passed its smoke but is wrong in production rolls back as one unit
— jar *and* asset-manifest together, so old code never meets a newer manifest:

```
/etc/scripts/deploy-myapp.sh --rollback     # single-instance; restores .prev + re-smokes
# pair topology: the previous jar is still at the other port's path, but
# restore the MANIFEST first — the old instance keeps its manifest in
# memory only until its next restart, and old code must never boot
# against the newer manifest:
#   cp -f /mnt/data/static/asset-manifest.edn.prev /mnt/data/static/asset-manifest.edn
#   systemctl start myapp@<old-port> ; systemctl stop myapp@<new-port>
```

Rollback is **one build deep** (`myapp.jar.prev` + `asset-manifest.edn.prev`).
To go further back, rebuild from the git sha — `/health` reports the running
`build_id`, so you know exactly which sha you are on — and deploy that. The
[reproducible build](../chapters/04-build-hardening.md) makes the rebuild exact.

## Repair a bad write (surgical, in place)

A logic bug can write *valid-but-wrong* data — every availability signal green,
the content quietly corrupt. A whole-DB restore would roll back *everyone's*
work to fix *one* datom; excision only deletes. The middle path is a corrective
transaction over the [REPL](#the-production-repl), which the immutable log makes
safe: you can see exactly what was written, and when.

```clojure
;; Find the bad assertion in history (who/when), then correct it forward.
(require '[datomic.api :as d])
(let [conn (myapp.db.core/get-connection)]
  ;; inspect first — never transact blind:
  (d/pull (d/db conn) '[*] eid)
  ;; then a normal assertion (a new version, not a rewrite of the past):
  @(myapp.db.core/transact* conn [{:db/id eid :recipe/title "corrected"}]))
```

This adds a version; it does not erase the wrong one, so the mistake and its
correction are both in the history — which is the audit trail you want. Reach
for excision only when the wrong value must legally *not exist* (ch.40).

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
   means no user-visible window. **One exception:** if the bumped dependency
   is `com.datomic/peer`, this is NOT an ordinary deploy — the peer and the
   running transactor must be version-compatible, and CI (which tests on
   `datomic:mem`) cannot catch a mismatch. The new jar would fail to connect,
   fail its smoke, and roll back — a stuck deploy, not a broken box. Upgrade
   the transactor distribution first (match `deps.edn`'s peer version), then
   ship the jar.
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
