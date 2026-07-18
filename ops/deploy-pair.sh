#!/usr/bin/env bash
# /etc/scripts/deploy-pair.sh — minimal-downtime deploy: start the new build
# on the idle instance, health-gate it, then drain the old one. Caddy's
# upstream pool (ops/Caddyfile) routes to whichever instances are alive, so
# the handoff drops nothing.
set -euo pipefail

new_jar="$1"
ports=(3001 3002)

[ -s "$new_jar" ] || { echo "no jar at $new_jar" >&2; exit 1; }

# Whichever port is serving stays up; the other is idle and gets the build.
live="" idle=""
for p in "${ports[@]}"; do
  if curl -fsS "http://127.0.0.1:$p/health" >/dev/null 2>&1; then live=$p; else idle=$p; fi
done
[ -n "$idle" ] || { echo "both instances live — finish/roll back the previous deploy first" >&2; exit 1; }

cp -f "$new_jar" "/opt/myapp/myapp-$idle.jar"
sudo systemctl start "myapp@$idle"      # the old instance keeps serving meanwhile

# Health-gate the newcomer directly on its own port, not through the proxy.
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:$idle/health" >/dev/null 2>&1; then
    if [ -n "$live" ]; then
      # SIGTERM → the shutdown hook drains in-flight requests, then the
      # port closes and Caddy's health checks steer everything to the
      # new instance. Old jar stays on disk as the rollback.
      sudo systemctl stop "myapp@$live"
    fi
    echo "deployed on :$idle$( [ -n "$live" ] && echo ", drained :$live" )"
    exit 0
  fi
  sleep 1
done

# The new build never became healthy: stop it, leave the old one serving.
echo "new instance on :$idle did not become healthy — old instance untouched" >&2
sudo systemctl stop "myapp@$idle" || true
exit 1
