#!/usr/bin/env bash
# /etc/scripts/myapp-notify.sh <subject> [body] — send a free-form alert
# through the box's own relay. The unit-failure sibling (myapp-alert.sh)
# emails about a systemd unit; this one carries an arbitrary line, so a
# fail2ban ban (or any script) reaches the operator on the same channel.
set -euo pipefail

subject="$1"
body="${2:-$1}"
set -a; . /etc/myapp/env; set +a
SMTP_PORT="${SMTP_PORT:-587}"

msg=$(mktemp); trap 'rm -f "$msg"' EXIT
{
  echo "From: $SMTP_FROM"
  echo "To: $ADMIN_EMAIL"
  echo "Subject: [myapp] $subject"
  echo
  echo "$body"
  echo
  echo "$(hostname) at $(date -Is)"
} > "$msg"

# Out-of-band webhook first (independent of the relay), then the relay
# best-effort — same reasoning as myapp-alert.sh: the relay is a thing that
# fails, and an alert about it must not travel through it.
if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
  curl -sS -m 10 -X POST "$ALERT_WEBHOOK_URL" \
    -H 'Content-Type: application/json' \
    --data "$(printf '{"text":"[myapp] %s — %s (%s)"}' "$subject" "$body" "$(hostname)")" \
    >/dev/null 2>&1 || true
fi

auth=(); [ -n "${SMTP_USER:-}" ] && auth=(--user "$SMTP_USER:$SMTP_PASS")
tls=(--ssl-reqd); [ "${SMTP_TLS:-true}" = "false" ] && tls=()
curl -sS "${tls[@]}" --url "smtp://$SMTP_HOST:$SMTP_PORT" \
  --mail-from "$SMTP_FROM" --mail-rcpt "$ADMIN_EMAIL" "${auth[@]}" -T "$msg" || true
