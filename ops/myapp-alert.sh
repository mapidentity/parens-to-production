#!/usr/bin/env bash
# /etc/scripts/myapp-alert.sh <unit> — email the operator that <unit> failed,
# through the same relay the application already requires at boot. curl
# speaks SMTP; the box needs no MTA.
set -euo pipefail

unit="$1"
set -a; . /etc/myapp/env; set +a
SMTP_PORT="${SMTP_PORT:-587}"

# Cooldown: OnFailure= plus a 2-minute timer means a persistent failure
# would email every 2 minutes. One alert per unit per half hour; the
# operator is awake after the first one.
stamp="/run/myapp-alert-${unit//\//_}"
now=$(date +%s)
if [ -f "$stamp" ] && [ $((now - $(cat "$stamp"))) -lt 1800 ]; then
  exit 0
fi
echo "$now" > "$stamp"

body=$(mktemp)
trap 'rm -f "$body"' EXIT
{
  echo "From: $SMTP_FROM"
  echo "To: $ADMIN_EMAIL"
  echo "Subject: [myapp] $unit failed on $(hostname)"
  echo
  echo "Unit $unit entered failed state at $(date -Is)."
  echo
  echo "Last journal lines:"
  journalctl -u "$unit" -n 20 --no-pager 2>/dev/null || echo "(no journal available)"
} > "$body"

# Out-of-band FIRST. The application's worst outage is a dead SMTP relay —
# and the email below would route straight through it, so the one alert you
# most need would be the one the outage swallows. If ALERT_WEBHOOK_URL is set
# (an ntfy/Slack/Discord/push endpoint reached over HTTPS, NOT the relay),
# POST there on an independent path. Best-effort: never let the alerter die.
if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
  curl -sS -m 10 -X POST "$ALERT_WEBHOOK_URL" \
    -H 'Content-Type: application/json' \
    --data "$(printf '{"text":"[myapp] %s failed on %s at %s"}' "$unit" "$(hostname)" "$(date -Is)")" \
    >/dev/null 2>&1 || true
fi

# The email path, now best-effort (|| true): a relay failure must not crash
# the alerter and leave the dead-man's-switch (ops/myapp-watchdog.sh) as the
# only signal — it is the backstop, not the primary.
auth=()
[ -n "${SMTP_USER:-}" ] && auth=(--user "$SMTP_USER:$SMTP_PASS")
tls=(--ssl-reqd)
[ "${SMTP_TLS:-true}" = "false" ] && tls=()   # plaintext relays (port 25 internal, lab drills)
curl -sS "${tls[@]}" \
  --url "smtp://$SMTP_HOST:$SMTP_PORT" \
  --mail-from "$SMTP_FROM" --mail-rcpt "$ADMIN_EMAIL" \
  "${auth[@]}" \
  -T "$body" || true
