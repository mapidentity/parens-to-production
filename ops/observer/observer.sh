#!/usr/bin/env bash
# ops/observer/observer.sh — the off-site second opinion, self-contained.
#
# Runs on a small box in a DIFFERENT datacenter (any provider, any cheap
# VPS): the on-box watchdog cannot see datacenter-level failure — network,
# DNS, the VM itself — so this observer stands where those failures are
# visible. Plain bash + curl + openssl; no agent, no platform. Copy this
# directory, fill in env, enable the timer.
set -euo pipefail

OBSERVER_ENV="${OBSERVER_ENV:-/etc/myapp-observer/env}"
set -a; . "$OBSERVER_ENV"; set +a

STATE_DIR="${STATE_DIR:-/var/lib/myapp-observer}"
FAILS_BEFORE_ALERT="${FAILS_BEFORE_ALERT:-2}"   # 2 × the timer interval of grace:
                                                # rides out a deploy window and a
                                                # transient route flap without noise
CERT_WARN_DAYS="${CERT_WARN_DAYS:-14}"
SMTP_PORT="${SMTP_PORT:-587}"
mkdir -p "$STATE_DIR"

hostport=$(sed -E 's#^https?://##; s#/.*$##' <<<"$TARGET_URL")
host=${hostport%%:*}
scheme=${TARGET_URL%%:*}
tls_port=443
[[ "$hostport" == *:* ]] && tls_port=${hostport##*:}
problems=()

# 1. DNS — the failure class no on-box check can ever see: the records
#    can rot or the resolver path can break while the box hums along.
if ! getent hosts "$host" >/dev/null 2>&1; then
  problems+=("DNS: $host does not resolve from the observer")
fi

# 2. TLS certificate window. ACME renewal failing is silent on the box
#    for weeks — the observer counts the days and warns while there is
#    still runway. (s_client reads the dates without needing trust.)
if [ "$scheme" = "https" ] && [ ${#problems[@]} -eq 0 ]; then
  not_after=$(echo | timeout 10 openssl s_client -servername "$host" \
                -connect "$host:$tls_port" 2>/dev/null \
              | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2 || true)
  if [ -z "$not_after" ]; then
    problems+=("TLS: could not read a certificate from $host")
  else
    days=$(( ($(date -d "$not_after" +%s) - $(date +%s)) / 86400 ))
    if [ "$days" -lt "$CERT_WARN_DAYS" ]; then
      problems+=("TLS: certificate expires in ${days}d — renewal broken?")
    fi
  fi
fi

# 3. The full public path: DNS → TLS → proxy → pool → app → both
#    databases, exactly as a visitor travels it. The body assertion
#    matters: a proxy serving its maintenance page answers 503, but a
#    hijacked domain or a wrong vhost can answer 200 with the wrong site.
if [ ${#problems[@]} -eq 0 ]; then
  if ! body=$(curl -fsS -m 15 "$TARGET_URL" 2>&1); then
    problems+=("HTTP: $TARGET_URL unreachable — ${body:0:160}")
  elif [[ "$body" != *"$EXPECT"* ]]; then
    problems+=("HTTP: answered, but body lacks '$EXPECT'")
  fi
fi

send_mail() {  # subject, body
  local msg; msg=$(mktemp); trap 'rm -f "$msg"' RETURN
  {
    echo "From: $SMTP_FROM"
    echo "To: $ALERT_EMAIL"
    echo "Subject: $1"
    echo
    echo "$2"
    echo
    echo "Observer: $(hostname) at $(date -Is)"
  } > "$msg"
  local auth=(); [ -n "${SMTP_USER:-}" ] && auth=(--user "$SMTP_USER:$SMTP_PASS")
  local tls=(--ssl-reqd); [ "${SMTP_TLS:-true}" = "false" ] && tls=()
  curl -sS "${tls[@]}" --url "smtp://$SMTP_HOST:$SMTP_PORT" \
    --mail-from "$SMTP_FROM" --mail-rcpt "$ALERT_EMAIL" "${auth[@]}" -T "$msg"
}

# State machine: N consecutive failures raise ONE alert; recovery sends
# the all-clear and re-arms. (Not a cooldown — a latch. The operator gets
# exactly two emails per incident: it broke, it recovered.)
fails_file="$STATE_DIR/consecutive-failures"
alerted_file="$STATE_DIR/alerted"

if [ ${#problems[@]} -eq 0 ]; then
  if [ -f "$alerted_file" ]; then
    send_mail "[myapp-observer] RESOLVED: $host healthy again" \
      "All external checks pass (DNS, TLS, HTTP body)."
    rm -f "$alerted_file"
  fi
  rm -f "$fails_file"
  exit 0
fi

fails=$(( $(cat "$fails_file" 2>/dev/null || echo 0) + 1 ))
echo "$fails" > "$fails_file"
echo "observer: check failed ($fails/$FAILS_BEFORE_ALERT): ${problems[*]}" >&2

if [ "$fails" -ge "$FAILS_BEFORE_ALERT" ] && [ ! -f "$alerted_file" ]; then
  send_mail "[myapp-observer] ALERT: $host failing from outside" \
    "$(printf '%s\n' "${problems[@]}")"
  touch "$alerted_file"
fi
exit 1
