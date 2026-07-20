#!/usr/bin/env bash
# /etc/scripts/myapp-watchdog.sh — the box checks itself, every two minutes
# (myapp-watchdog.timer). Any failed check exits non-zero, which flips the
# oneshot service to failed and fires OnFailure= → myapp-alert@.
#
# Four checks, one per class of silent death the audits actually found —
# an app that answers, a mail relay that connects, disks with headroom on
# every filesystem that holds state, a backup that is still advancing —
# plus the heartbeat ping that turns this box's SILENCE into an off-box
# alarm.
set -euo pipefail

# Same source of truth as the app itself.
set -a; . /etc/myapp/env; set +a
SMTP_PORT="${SMTP_PORT:-587}"

fail() { echo "watchdog: $1" >&2; exit 1; }

# 1. An instance answers /health — any one of the pool's ports. This is
#    the app's own no-lies health check (it proves both database
#    connections), asked from the box, past the proxy.
healthy=""
for p in 3000 3001 3002; do
  if curl -fsS -m 5 "http://127.0.0.1:$p/health" >/dev/null 2>&1; then healthy=$p; break; fi
done
[ -n "$healthy" ] || fail "no instance answers /health on 3000/3001/3002"

# 2. The mail relay accepts a TCP connection. Sign-in IS email here, and a
#    dead relay is the one outage that keeps every page green: the app
#    boots, health passes, and nobody can log in. The don't-reveal design
#    means no user will tell you either. A connect is the side-effect-free
#    probe /health deliberately refuses to make in-band.
timeout 5 bash -c "exec 3<>/dev/tcp/$SMTP_HOST/$SMTP_PORT" 2>/dev/null \
  || fail "SMTP relay $SMTP_HOST:$SMTP_PORT does not accept connections"

# 3. Disk headroom on every filesystem that holds state — not just /. The
#    data volume (/mnt/data), the backups, and the log dir can each fill
#    independently; df resolves a path to its containing filesystem, so paths
#    that share a mount are harmlessly redundant and separate mounts are all
#    seen. The slowest outage on a single box is the disk that fills.
BACKUP_DIR="${BACKUP_DIR:-/var/backups/myapp}"
for path in / /mnt/data "$BACKUP_DIR" /var/log/myapp; do
  [ -e "$path" ] || continue
  usage=$(df --output=pcent "$path" 2>/dev/null | tail -1 | tr -dc '0-9')
  { [ -n "$usage" ] && [ "$usage" -lt 90 ]; } || fail "filesystem for $path at ${usage:-?}% — reclaim space"
done

# 4. The backup actually ran and SUCCEEDED recently — read the manifest, not a
#    file mtime. A file-mtime proxy can't tell a good backup from a half-written
#    failure, and it doesn't even see the databases when backup-db writes them
#    off-box; the manifest records the truth of the last run. Last record must
#    be status=ok, younger than 25h, and (on an off-box target) not a failed
#    off-box file copy. An absent manifest means no backup ever completed.
MANIFEST="$BACKUP_DIR/backup-manifest.jsonl"
if [ -f "$MANIFEST" ]; then
  # The manifest interleaves nightly BACKUP records (carry "status") with
  # weekly VERIFY records (carry "verdict"); select the last backup one, so a
  # verify that ran more recently than the backup can't read as a missing status.
  last=$(grep '"status":' "$MANIFEST" | tail -n1)
  [ -n "$last" ] || fail "backup manifest has no backup record yet — has the nightly backup ever completed?"
  st=$(printf '%s' "$last" | sed -n 's/.*"status":"\([a-z]*\)".*/\1/p')
  ep=$(printf '%s' "$last" | sed -n 's/.*"epoch":\([0-9]*\).*/\1/p')
  ob=$(printf '%s' "$last" | sed -n 's/.*"offbox_files":"\([a-z]*\)".*/\1/p')
  [ "$st" = "ok" ] || fail "last backup record is status=${st:-?} — the nightly backup failed (see $MANIFEST)"
  age=$(( $(date +%s) - ${ep:-0} ))
  [ "$age" -lt 90000 ] || fail "last successful backup is $((age / 3600))h old — the nightly backup has stopped"
  [ "$ob" != "failed" ] || fail "backup off-box file copy FAILED — config/photos are not off the box (see $MANIFEST)"
elif [ -d "$BACKUP_DIR" ]; then
  fail "no backup manifest at $MANIFEST — has the nightly backup ever completed?"
fi

# All checks passed. Ping the external dead-man's-switch (healthchecks.io,
# ntfy, any cron monitor) so that SILENCE — this box gone, this watchdog
# broken, or any check above failing — becomes an alarm raised OFF the box,
# on a path that does NOT depend on the box's own mail relay. This is the
# signal that survives the very outage (a dead relay) the in-box alerter
# cannot page about. Best-effort: a monitoring blip must not flip us to failed.
[ -n "${HEARTBEAT_URL:-}" ] && curl -fsS -m 10 "$HEARTBEAT_URL" >/dev/null 2>&1 || true

exit 0
