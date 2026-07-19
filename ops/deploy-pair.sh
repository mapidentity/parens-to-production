#!/usr/bin/env bash
# /etc/scripts/deploy-pair.sh <new-jar> <new-static-dir>
#
# Minimal-downtime deploy: start the new build on the idle instance, smoke it,
# then drain the old one. Caddy's upstream pool (ops/Caddyfile) routes to
# whichever instances are alive, so the handoff drops nothing.
#
# Like the single-instance deploy, this treats the jar and its asset-manifest
# as one coherent unit. The two instances share one /mnt/data/static, but each
# reads the manifest into memory at ITS boot — so the old instance keeps
# serving against the old manifest (its hashed files stay on disk, additively)
# while the newcomer boots against the new one. The manifest is still snapshotted
# so that if the newcomer fails, a rollback (or a later crash-restart of the old
# instance) meets the manifest the old jar was built with.
set -euo pipefail

root="${MYAPP_ROOT:-/opt/myapp}"
static="${MYAPP_STATIC:-/mnt/data/static}"
base="${MYAPP_HEALTH_BASE:-http://127.0.0.1}"
manifest="$static/asset-manifest.edn"
sc="${MYAPP_SYSTEMCTL:-sudo systemctl}"
ports=(3001 3002)

new_jar="${1:?usage: deploy-pair.sh <jar> <static-dir>}"
new_static="${2:?usage: deploy-pair.sh <jar> <static-dir>}"
[ -s "$new_jar" ] || { echo "no jar at $new_jar" >&2; exit 1; }
[ -f "$new_static/asset-manifest.edn" ] || { echo "no asset-manifest.edn in $new_static" >&2; exit 1; }

# Single-flight: overlapping pair deploys race on the idle/live detection and
# the manifest snapshot.
mkdir -p "$root"
exec 9>"$root/.deploy.lock"
flock -n 9 || { echo "another deploy is already in progress" >&2; exit 1; }

smoke() {   # gate the newcomer on its own port: health, home render, assets present
  local port="$1" ok=""
  for _ in $(seq 1 30); do
    if curl -fsS "$base:$port/health" >/dev/null 2>&1; then ok=1; break; fi
    sleep 1
  done
  [ -n "$ok" ] || { echo "smoke: :$port not healthy after 30s" >&2; return 1; }
  curl -fsS "$base:$port/" >/dev/null 2>&1 || { echo "smoke: :$port home did not render" >&2; return 1; }
  local missing
  missing="$(grep -oE '/[A-Za-z0-9._/-]+\.[a-f0-9]{8}\.(css|js)' "$manifest" | while read -r a; do
    [ -f "$static/${a#/}" ] || echo "$a"
  done)"
  [ -z "$missing" ] || { echo "smoke: manifest references missing asset(s): $missing" >&2; return 1; }
  return 0
}

# Whichever port is serving stays up; the other is idle and gets the build.
live="" idle=""
for p in "${ports[@]}"; do
  if curl -fsS "$base:$p/health" >/dev/null 2>&1; then live=$p; else idle=$p; fi
done
[ -n "$idle" ] || { echo "both instances live — finish/roll back the previous deploy first" >&2; exit 1; }

# Snapshot the manifest, install new assets additively (lands the new manifest),
# then bring up the newcomer against it.
cp -f "$manifest" "$manifest.prev" 2>/dev/null || true
mkdir -p "$static"
cp -a "$new_static/." "$static/"
cp -f "$new_jar" "$root/myapp-$idle.jar"
$sc start "myapp@$idle"      # the old instance keeps serving meanwhile

if smoke "$idle"; then
  if [ -n "$live" ]; then
    # SIGTERM → the shutdown hook drains in-flight requests, then Caddy's health
    # checks steer everything to the new instance. Old jar stays as the rollback.
    $sc stop "myapp@$live"
  fi
  echo "deployed on :$idle$( [ -n "$live" ] && echo ", drained :$live" )"
  exit 0
fi

# The newcomer failed the smoke: stop it, restore the manifest so the still-live
# old instance (and any crash-restart of it) meets the manifest it was built with.
echo "new instance on :$idle failed the smoke — restoring, old instance untouched" >&2
$sc stop "myapp@$idle" || true
[ -f "$manifest.prev" ] && cp -f "$manifest.prev" "$manifest"
exit 1
