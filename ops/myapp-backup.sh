#!/usr/bin/env bash
# /etc/scripts/myapp-backup.sh — nightly (myapp-backup.timer). Datomic's own
# backup-db, once per database, plus the box's config and the user photos.
# Every run appends ONE record to a manifest (backup-manifest.jsonl) so a
# backup is a fact you can read — when it ran, what basis-t it captured, how
# many photos, whether the off-box copy went — not a file mtime you infer from.
set -euo pipefail

set -a; . /etc/myapp/env; set +a
BACKUP_DIR="${BACKUP_DIR:-/var/backups/myapp}"
# The backup TARGET. Default file: is on THIS box — which shares the exact
# failure domain ch.39 opens on (the dead disk, the wrong rm, the box that
# never comes back). A backup that can only die with the thing it protects is
# not a backup. In production, set BACKUP_URI=s3://your-bucket/myapp: datomic
# backup-db writes there natively (AWS SDK, credentials via the environment —
# no aws CLI on the box), and that copy is the one that survives the box.
BACKUP_URI="${BACKUP_URI:-file:$BACKUP_DIR}"
DATOMIC="${DATOMIC_HOME:-/opt/datomic}/bin/datomic"
UPLOADS_ROOT="${MYAPP_UPLOADS_ROOT:-/mnt/data/uploads}"
MANIFEST="$BACKUP_DIR/backup-manifest.jsonl"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"   # the backup contains everything the database does

started=$(date +%s)

# Append one JSON object to the manifest. Written on success AND (via the trap
# below) on failure, so the watchdog and the verify drill read the TRUTH of the
# last run rather than guessing from file timestamps. The field order is fixed
# so a reader without jq can grep it; a reader with jq or the JVM gets a map.
record() {
  local status="$1"
  printf '{"finished_at":"%s","epoch":%s,"status":"%s","target":"%s","db_basis_t":%s,"analytics_basis_t":%s,"uploads_files":%s,"uploads_bytes":%s,"config":%s,"offbox_files":"%s","duration_s":%s}\n' \
    "$(date -Is)" "$(date +%s)" "$status" "$BACKUP_URI" \
    "${db_basis_t:-null}" "${an_basis_t:-null}" \
    "${up_files:-0}" "${up_bytes:-0}" "${config_done:-false}" \
    "${offbox_files:-na}" "$(( $(date +%s) - started ))" \
    >> "$MANIFEST"
}
# On any error before we reach the success record, stamp a failed record so a
# silent backup-db exit or a dead disk still shows up as a dated FAILURE — the
# "we think we have backups" trap ch.39 opens on.
trap 'record failed' ERR

# backup-db is incremental: pointing at the same target URI only writes
# segments new since last time. Capture its output to lift the basis-t it
# stamps (backup-db reports the point it captured) into the manifest.
db_out=$("$DATOMIC" backup-db "$DATABASE_URI" "$BACKUP_URI/myapp" 2>&1); echo "$db_out"
an_out=$("$DATOMIC" backup-db "$ANALYTICS_DATABASE_URI" "$BACKUP_URI/myapp-analytics" 2>&1); echo "$an_out"
db_basis_t=$(printf '%s' "$db_out" | grep -oE ':basis-t[[:space:]]+[0-9]+' | grep -oE '[0-9]+' | tail -1)
an_basis_t=$(printf '%s' "$an_out" | grep -oE ':basis-t[[:space:]]+[0-9]+' | grep -oE '[0-9]+' | tail -1)

# The other half of "what is state on this box" (ch.35): /etc/myapp. The env
# file holds the crypto keys — treat this directory as exactly as secret as the
# box. rsync --delete keeps an exact mirror; a `cp -a` into an existing target
# would nest a stale copy on every run after the first.
rsync -a --delete /etc/myapp/ "$BACKUP_DIR/etc-myapp/"
config_done=true

# User photos (ch.49): the content-addressed blob tree. A blob is written
# BEFORE the transaction that references it and deleted only long AFTER it goes
# unreferenced, so a copy taken at-or-after backup-db is always a superset of
# what the restored database points at — every reference resolves. Content-
# addressed names make rsync near-free: only new blobs move. No --delete here,
# on purpose: a blob the GC reaped may still be referenced by an OLDER database
# backup, and a restore from that backup wants it.
rsync -a "$UPLOADS_ROOT/" "$BACKUP_DIR/uploads/"
up_files=$(find "$UPLOADS_ROOT" -type f 2>/dev/null | wc -l | tr -d ' ')
up_bytes=$(du -sb "$UPLOADS_ROOT" 2>/dev/null | cut -f1)

# Off-box files, WITHOUT a hard aws dependency. backup-db already put the
# databases off-box via its own SDK; the config and photos are plain files, so
# they ride whatever tool the operator already runs off-box — set
# BACKUP_FILES_SYNC to that command (an `aws s3 sync`, `rclone`, `restic`, …).
# It receives the local backup dir and the target as $1/$2. Provider-agnostic
# by design: the box commits to no single cloud. The manifest records which of
# {na, ok, skipped, failed} happened, so "are the photos off-box?" is a field
# you can read, not an assumption.
offbox_files=na
case "$BACKUP_URI" in
  file:*) : ;;                                   # local target: nothing to push off
  *)
    if [ -n "${BACKUP_FILES_SYNC:-}" ]; then
      if "$BACKUP_FILES_SYNC" "$BACKUP_DIR" "$BACKUP_URI"; then offbox_files=ok; else offbox_files=failed; fi
    else
      offbox_files=skipped
      echo "WARNING: BACKUP_URI is off-box but BACKUP_FILES_SYNC is unset — the databases went off-box, the config + photos did NOT. Set BACKUP_FILES_SYNC to your off-box file tool (aws/rclone/restic). Recorded as offbox_files=skipped." >&2
    fi
    ;;
esac

trap - ERR
record ok
echo "backup complete: $(du -sh "$BACKUP_DIR" | cut -f1) in $BACKUP_DIR (basis-t ${db_basis_t:-?}, ${up_files:-0} photos, off-box: ${offbox_files})"
