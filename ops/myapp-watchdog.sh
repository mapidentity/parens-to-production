#!/usr/bin/env bash
# /etc/scripts/myapp-watchdog.sh — the box checks itself, every two minutes
# (myapp-watchdog.timer). Any failed check exits non-zero, which flips the
# oneshot service to failed and fires OnFailure= → myapp-alert@.
#
# Three checks, one per class of silent death the audits actually found:
# an app that answers, a mail relay that connects, a disk with headroom.
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

# 4. The backup actually ran recently. A backup that silently stopped
#    advancing is Schrödinger's backup — green until the night you need it.
#    The newest file under BACKUP_DIR must be younger than 25h (nightly +
#    slack); an empty dir means no backup has ever completed.
if [ -d "$BACKUP_DIR" ]; then
  newest=$(find "$BACKUP_DIR" -type f -printf '%T@\n' 2>/dev/null | sort -n | tail -1)
  if [ -n "$newest" ]; then
    age=$(( $(date +%s) - ${newest%.*} ))
    [ "$age" -lt 90000 ] || fail "newest backup is $((age / 3600))h old — the nightly backup has stopped"
  else
    fail "backup dir $BACKUP_DIR is empty — no backup has ever completed"
  fi
fi

# All checks passed. Ping the external dead-man's-switch (healthchecks.io,
# ntfy, any cron monitor) so that SILENCE — this box gone, this watchdog
# broken, or any check above failing — becomes an alarm raised OFF the box,
# on a path that does NOT depend on the box's own mail relay. This is the
# signal that survives the very outage (a dead relay) the in-box alerter
# cannot page about. Best-effort: a monitoring blip must not flip us to failed.
[ -n "${HEARTBEAT_URL:-}" ] && curl -fsS -m 10 "$HEARTBEAT_URL" >/dev/null 2>&1 || true

exit 0
