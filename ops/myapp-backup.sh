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
# treated as exactly as secret as the box itself.
cp -a /etc/myapp "$BACKUP_DIR/etc-myapp"

echo "backup complete: $(du -sh "$BACKUP_DIR" | cut -f1) in $BACKUP_DIR"
