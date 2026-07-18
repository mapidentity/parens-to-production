#!/usr/bin/env bash
# /etc/scripts/myapp-static-gc.sh [static-dir] — the chore ch.36 created:
# pair deploys ship hashed assets ADDITIVELY (an old build must keep
# finding its old hashes during the overlap), so generations accumulate.
# Weekly, delete hashed files that (a) the CURRENT manifest does not
# reference and (b) are older than 30 days — any page that could
# reference them is long gone from every cache that matters.
set -euo pipefail

static_dir="${1:-/mnt/data/static}"
manifest="$static_dir/asset-manifest.edn"
[ -f "$manifest" ] || { echo "no manifest at $manifest — refusing to guess" >&2; exit 1; }

deleted=0
while IFS= read -r f; do
  rel="${f#"$static_dir"/}"
  if ! grep -qF "/$rel" "$manifest"; then
    rm -- "$f"
    deleted=$((deleted + 1))
  fi
done < <(find "$static_dir" -type f -mtime +30 \
           -regextype posix-extended -regex '.*\.[a-f0-9]{8}\.(css|js)$')

echo "static-gc: removed $deleted unreferenced hashed file(s) from $static_dir"
