#!/usr/bin/env bash
# /etc/scripts/myapp-backup-verify.sh — weekly (myapp-backup-verify.timer).
#
# A backup that is never restored is Schrödinger's backup: green until the
# night you need it, and by then it is too late to learn it was corrupt, empty,
# or that the incremental chain silently stopped advancing. `datomic backup-db`
# can exit 0 on all of those. So once a week we RESTORE the newest backup into
# throwaway storage and prove it is actually usable — the ch.39 drill, run by a
# timer instead of by a human who remembers. On any failure it alerts on BOTH
# channels (out-of-band webhook + relay, via myapp-notify.sh) and exits
# non-zero, which also trips OnFailure= → myapp-alert@.
set -euo pipefail

set -a; . /etc/myapp/env; set +a
BACKUP_DIR="${BACKUP_DIR:-/var/backups/myapp}"
BACKUP_URI="${BACKUP_URI:-file:$BACKUP_DIR}"
DATOMIC="${DATOMIC_HOME:-/opt/datomic}/bin/datomic"
JAR="${MYAPP_JAR:-/opt/myapp/myapp.jar}"

# Scratch storage the restore lands in — NEVER production. A dedicated
# throwaway URI (a scratch PostgreSQL, ch.39). Refuse to run without it, so a
# misconfiguration can never restore over the live database.
: "${VERIFY_SCRATCH_URI:?set VERIFY_SCRATCH_URI to a throwaway datomic URI (never production)}"

fail() {
  /etc/scripts/myapp-notify.sh "backup verify FAILED on $(hostname)" "$1" || true
  echo "backup-verify: $1" >&2
  exit 1
}

echo "restoring $BACKUP_URI/myapp -> $VERIFY_SCRATCH_URI"
"$DATOMIC" restore-db "$BACKUP_URI/myapp" "$VERIFY_SCRATCH_URI" || fail "restore-db failed"

# Prove the RESTORED database is usable, not merely that files copied: entities
# are present AND the history/time axis crossed the backup boundary — the one
# property this versioning product is actually about (ch.39). Non-zero on any
# miss so the timer flips to failed.
java -cp "$JAR" clojure.main -e "
  (require '[datomic.api :as d])
  (let [conn (d/connect \"$VERIFY_SCRATCH_URI\")
        db   (d/db conn)
        n    (count (d/q '[:find ?e :where [?e :recipe/id]] db))
        hist (count (d/q '[:find ?tx :where [?e :recipe/title _ ?tx]] (d/history db)))]
    (when (zero? n)    (println \"FAIL: no recipes restored\") (System/exit 2))
    (when (zero? hist) (println \"FAIL: history empty — time axis lost\") (System/exit 3))
    (println (format \"RESULT recipes: %d history-txs: %d basis-t: %d\" n hist (d/basis-t db)))
    (System/exit 0))
" || fail "restored database did not verify (empty, or history lost)"

echo "backup-verify PASS at $(date -Is)"
