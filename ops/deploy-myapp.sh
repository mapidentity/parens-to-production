#!/usr/bin/env bash
# /etc/scripts/deploy-myapp.sh <new-jar> <new-static-dir>
#                            | --rollback
#
# A deploy is ONE coherent unit: the jar, and the asset-manifest its rendered
# pages resolve against. Earlier these shipped separately — the static tree
# (manifest included) was copied first and overwritten in place, so by the time
# the jar swapped there was no old manifest left to restore. Rolling the jar
# back then paired OLD code with the NEWER manifest, 404-ing or mis-serving the
# CSS/JS: the rollback, the thing you reach for while panicking, made it worse.
#
# Now the script owns both halves. It snapshots jar+manifest together BEFORE
# touching anything, installs the new assets ADDITIVELY (content-hashed files
# are shared across releases and reaped later by static-gc), swaps the jar, and
# gates on a real smoke — not just /health. Any failure restores jar AND
# manifest together, so old code always meets the manifest it was built with.
set -euo pipefail

# Paths are overridable so the deploy logic is drillable off a real box.
root="${MYAPP_ROOT:-/opt/myapp}"
static="${MYAPP_STATIC:-/mnt/data/static}"
base="${MYAPP_HEALTH_BASE:-http://127.0.0.1:3000}"
target="$root/myapp.jar"
manifest="$static/asset-manifest.edn"
# In a drill, systemctl is stubbed on PATH; on the box it needs sudo.
sc="${MYAPP_SYSTEMCTL:-sudo systemctl}"

restart() { $sc restart myapp; }

smoke() {
  # 1. The app answers its own readiness endpoint (both DB connections).
  local ok=""
  for _ in $(seq 1 30); do
    if curl -fsS "$base/health" >/dev/null 2>&1; then ok=1; break; fi
    sleep 1
  done
  [ -n "$ok" ] || { echo "smoke: not healthy after 30s" >&2; return 1; }
  # 2. The home page actually RENDERS. A jar that boots and reads the DB but
  #    throws 500 on a real render passes /health and fails here.
  curl -fsS "$base/" >/dev/null 2>&1 || { echo "smoke: home page did not render" >&2; return 1; }
  # 3. Every hashed asset the LIVE manifest points at is present on disk — the
  #    exact failure a jar/manifest mismatch produces, caught before users see it.
  local missing
  missing="$(grep -oE '/[A-Za-z0-9._/-]+\.[a-f0-9]{8}\.(css|js)' "$manifest" | while read -r a; do
    [ -f "$static/${a#/}" ] || echo "$a"
  done)"
  [ -z "$missing" ] || { echo "smoke: manifest references missing asset(s): $missing" >&2; return 1; }
  return 0
}

roll_back() {
  echo "rolling back to the previous jar + manifest" >&2
  [ -s "$target.prev" ] && cp -f "$target.prev" "$target"
  [ -f "$manifest.prev" ] && cp -f "$manifest.prev" "$manifest"
  restart
}

# Single-flight: two overlapping deploys race on target/.prev and the manifest
# snapshot, and can leave .prev pointing at a bad jar — a poisoned rollback.
mkdir -p "$root"
exec 9>"$root/.deploy.lock"
flock -n 9 || { echo "another deploy is already in progress" >&2; exit 1; }

if [ "${1:-}" = "--rollback" ]; then
  [ -s "$target.prev" ] || { echo "no previous release to roll back to" >&2; exit 1; }
  roll_back
  smoke && { echo "rolled back, healthy"; exit 0; }
  echo "rollback did not become healthy — manual intervention required" >&2
  exit 1
fi

new_jar="${1:?usage: deploy-myapp.sh <jar> <static-dir> | --rollback}"
new_static="${2:?usage: deploy-myapp.sh <jar> <static-dir> | --rollback}"
[ -s "$new_jar" ] || { echo "no jar at $new_jar" >&2; exit 1; }
[ -f "$new_static/asset-manifest.edn" ] || { echo "no asset-manifest.edn in $new_static" >&2; exit 1; }

# 1. Snapshot the CURRENT release (jar + manifest) before anything changes.
cp -f "$target" "$target.prev" 2>/dev/null || true
cp -f "$manifest" "$manifest.prev" 2>/dev/null || true

# 2. Install new assets additively (old hashes stay for in-flight pages and for
#    rollback); this also lands the new manifest, matched to the new jar.
mkdir -p "$static"
cp -a "$new_static/." "$static/"

# 3. Swap the jar (service stopped, so the swap need not be atomic) and start.
$sc stop myapp
mv -f "$new_jar" "$target"
$sc start myapp

# 4. Gate on the full smoke, not just a spawned process.
if smoke; then
  echo "deployed: $(basename "$new_jar") + assets, smoke passed"
  exit 0
fi

echo "new build failed the smoke — rolling back" >&2
roll_back
if smoke; then echo "rolled back to previous release" >&2; else
  echo "rollback ALSO failed the smoke — manual intervention required" >&2
fi
exit 1
