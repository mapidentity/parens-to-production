#!/usr/bin/env bash
# /etc/scripts/deploy-myapp.sh — run on the app server, handed the freshly-uploaded jar.
set -euo pipefail

new_jar="$1"
target=/opt/myapp/myapp.jar
health=http://127.0.0.1:3000/health   # the app's own readiness endpoint

# Refuse a missing or truncated upload before we stop anything.
[ -s "$new_jar" ] || { echo "no jar at $new_jar" >&2; exit 1; }

cp -f "$target" "$target.prev" 2>/dev/null || true  # keep the last-good jar to roll back to

sudo systemctl stop myapp             # the brief outage window opens here
mv -f "$new_jar" "$target"            # service is stopped, so the swap need not be atomic
sudo systemctl start myapp            # spawns the process; does NOT wait for it to serve

# `systemctl start` returns the instant the process is spawned, not when the JVM
# is answering requests. Poll the real health endpoint until it returns 200,
# giving the app up to 30s to bind the port and finish booting.
for _ in $(seq 1 30); do
  if curl -fsS "$health" >/dev/null 2>&1; then
    exit 0                            # the app answered — the deploy succeeded
  fi
  sleep 1
done

# No healthy response in the window: the new jar booted and died (bad config,
# schema mismatch, port clash) or never came up. Restore the previous jar,
# restart, and fail the deploy loudly rather than leaving a crash-loop behind.
echo "myapp did not become healthy — rolling back to previous jar" >&2
[ -s "$target.prev" ] && mv -f "$target.prev" "$target"
sudo systemctl restart myapp
exit 1
