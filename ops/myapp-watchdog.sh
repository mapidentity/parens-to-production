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

# 3. Disk headroom. The slowest outage on any single box is the full disk;
#    it deserves two minutes' notice, not a crashed transactor's.
usage=$(df --output=pcent / | tail -1 | tr -dc '0-9')
[ "$usage" -lt 90 ] || fail "root filesystem at ${usage}% — reclaim space"

exit 0
