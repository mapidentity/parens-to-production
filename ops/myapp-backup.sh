#!/usr/bin/env bash
# /etc/scripts/myapp-backup.sh — nightly (myapp-backup.timer). Datomic's own
# backup-db, once per database, plus the box's config. Same env file as
# everything else; BACKUP_DIR and DATOMIC_HOME are overridable there.
set -euo pipefail

set -a; . /etc/myapp/env; set +a
BACKUP_DIR="${BACKUP_DIR:-/var/backups/myapp}"
DATOMIC="${DATOMIC_HOME:-/opt/datomic}/bin/datomic"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"   # the backup contains everything the database does

# backup-db is incremental: pointing at the same target URI only writes
# segments new since last time. Swap file: for s3:// and the same command
# is the offsite story (credentials via the environment).
"$DATOMIC" backup-db "$DATABASE_URI" "file:$BACKUP_DIR/myapp"
"$DATOMIC" backup-db "$ANALYTICS_DATABASE_URI" "file:$BACKUP_DIR/myapp-analytics"

# The other half of "what is state on this box" (ch.35): /etc/myapp.
# The env file holds the crypto keys — the backup directory must be
# treated as exactly as secret as the box itself.
cp -a /etc/myapp "$BACKUP_DIR/etc-myapp"

echo "backup complete: $(du -sh "$BACKUP_DIR" | cut -f1) in $BACKUP_DIR"
