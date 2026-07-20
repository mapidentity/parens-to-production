#!/usr/bin/env bash
# /etc/scripts/myapp-backup.sh — nightly (myapp-backup.timer). Datomic's own
# backup-db, once per database, plus the box's config. Same env file as
# everything else; BACKUP_DIR and DATOMIC_HOME are overridable there.
set -euo pipefail

set -a; . /etc/myapp/env; set +a
BACKUP_DIR="${BACKUP_DIR:-/var/backups/myapp}"
# The backup TARGET. Default file: is on THIS box — which shares the exact
# failure domain ch.39 opens on (the dead disk, the wrong rm, the box that
# never comes back). A backup that can only die with the thing it protects is
# not a backup. In production, set BACKUP_URI=s3://your-bucket/myapp: datomic
# backup-db writes there natively (credentials via the environment), and that
# copy is the one that survives the box. See ch.46.
BACKUP_URI="${BACKUP_URI:-file:$BACKUP_DIR}"
DATOMIC="${DATOMIC_HOME:-/opt/datomic}/bin/datomic"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"   # the backup contains everything the database does

# backup-db is incremental: pointing at the same target URI only writes
# segments new since last time.
"$DATOMIC" backup-db "$DATABASE_URI" "$BACKUP_URI/myapp"
"$DATOMIC" backup-db "$ANALYTICS_DATABASE_URI" "$BACKUP_URI/myapp-analytics"

# The other half of "what is state on this box" (ch.35): /etc/myapp.
# The env file holds the crypto keys — the backup directory must be
# treated as exactly as secret as the box itself. rsync --delete keeps an
# exact mirror; cp -a into an existing target would NEST a second copy at
# etc-myapp/myapp/ on every run after the first and never drop a file
# deleted at the source.
rsync -a --delete /etc/myapp/ "$BACKUP_DIR/etc-myapp/"

# User photos (ch.49): the content-addressed blob tree. A blob is written
# BEFORE the transaction that references it and deleted only long AFTER it
# goes unreferenced, so a copy taken at-or-after backup-db is always a
# superset of what the restored database points at — every reference
# resolves. Content-addressed names make rsync near-free: only new blobs
# move. No --delete here, on purpose: a blob the GC reaped may still be
# referenced by a DB backup older than the sweep, and a restore from that
# backup wants it — prune this mirror the day you prune old DB backups.
UPLOADS_ROOT="${MYAPP_UPLOADS_ROOT:-/mnt/data/uploads}"
rsync -a "$UPLOADS_ROOT/" "$BACKUP_DIR/uploads/"

# Off-box, when the DB goes off-box. backup-db writes s3:// natively, but
# /etc/myapp and the uploads tree are plain files — they ride the SAME
# channel via the aws CLI, so the copy that survives the box is complete,
# not just the database. If BACKUP_URI is off-box and aws is missing, we
# say so loudly and fail: a silent half-backup is the trap ch.39 opens on.
case "${BACKUP_URI}" in
  s3://*)
    if command -v aws >/dev/null 2>&1; then
      aws s3 sync --delete "$BACKUP_DIR/etc-myapp/" "$BACKUP_URI/etc-myapp/"
      aws s3 sync "$UPLOADS_ROOT/" "$BACKUP_URI/uploads/"
    else
      echo "ERROR: BACKUP_URI is off-box ($BACKUP_URI) but the 'aws' CLI is not installed — /etc/myapp and the uploads tree were NOT copied off-box (the database was). Install awscli or sync them yourself." >&2
      exit 1
    fi
    ;;
esac

echo "backup complete: $(du -sh "$BACKUP_DIR" | cut -f1) in $BACKUP_DIR"
