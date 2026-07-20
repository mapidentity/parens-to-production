# Backup, Restore, and the Drill

[The alerting chapter](38-alerting.md) ended on the failures that send no alert: the disk that dies rather than fills, the wrong `rm`, the box that simply never comes back. For those, the operator needs yesterday's data standing somewhere else -- and, much more importantly, *proof* that it stands back up. The industry's quiet shame is that most backup systems are write-only: run nightly for years, restored never, discovered broken at the worst possible moment. So this chapter's center of gravity is not the backup script. It is the drill.

## What is state, and which tool carries it

[Going live](35-going-live.md) reduced "what is state on this box" to a two-item list: the PostgreSQL data directory, and `/etc/myapp`. For the first item there are two honest tools. `pg_dump` backs up the shelf -- and since [the shelf is one four-column table](35-going-live.md), it would work fine. The book chooses Datomic's own `backup-db` instead, for three properties the drill will exploit:

- **The restore is storage-agnostic.** A `backup-db` archive restores into *any* storage (a sibling PostgreSQL database, the dev storage on your laptop), which is what lets you rehearse a restore without breathing near production. A `pg_dump` restores into PostgreSQL, full stop.
- **It is incremental by construction.** Pointed at the same target URI, it writes only segments new since last time; the nightly cost is proportional to the night's writes, not the database.
- **It stamps its own consistency.** The archive is taken at a basis-t and says so -- the restore below announces `:basis-t 1037` in its own output. No quiescing, no lock coordination with a live transactor; immutable segments make hot backup boring.

What `backup-db` cannot carry is the second item, so the script carries it alongside -- with the consequence stated where the operator will read it:

```bash
# The backup TARGET: file: on this box by default, but production sets
# BACKUP_URI=s3://… so the copy that survives the box lives off it (ch.46).
BACKUP_URI="${BACKUP_URI:-file:$BACKUP_DIR}"
"$DATOMIC" backup-db "$DATABASE_URI" "$BACKUP_URI/myapp"
"$DATOMIC" backup-db "$ANALYTICS_DATABASE_URI" "$BACKUP_URI/myapp-analytics"

# The other half of "what is state on this box" (ch.35): /etc/myapp.
# The env file holds the crypto keys — treat this directory as exactly as
# secret as the box. rsync --delete keeps an exact mirror; a `cp -a` into
# an existing target would nest a stale copy on every run after the first.
rsync -a --delete /etc/myapp/ "$BACKUP_DIR/etc-myapp/"
,,,
```

The listing stops there, but the shipped script grew one more line when [the file-storage chapter](49-file-storage.md) gave the app user photos: an `rsync` of the content-addressed uploads tree, taken *after* `backup-db`, so the copied blobs are always a superset of what the restored database references. The whole photo-backup argument -- why the grace window makes that copy consistent without a lock -- lives in that chapter; here it is one more directory on the same nightly run.

The schedule is [the watchdog chapter's](38-alerting.md) pattern at a longer period, `myapp-backup.timer`, nightly at 04:00, `Persistent=true` so a box that was off runs the missed backup on boot. And the backup *unit* wires into the alerting spine for the reason its comment states outright: `OnFailure=myapp-alert@%n.service`, because **a backup that fails silently is worse than none: it converts "we have no backups" into "we think we have backups."** The databases go offsite by pointing `BACKUP_URI` at `s3://…` (`backup-db` speaks it natively, credentials from the environment, no cloud CLI on the box); the *files* -- config and photos -- go by whatever off-box tool the operator already runs, named in one env var (`BACKUP_FILES_SYNC`) rather than hard-wired to a provider. All of it is named here and not drilled, because this book's sandbox has no bucket and pretending otherwise would break the chapter's own rule.

### The backup you can read

`OnFailure=` catches a backup that *crashes*. It says nothing about a backup that exits 0 having quietly done less than you think -- the off-box sync that was never configured, the incremental chain that stopped advancing, the run that captured a basis-t from last Tuesday. So every run appends one line to a manifest, `backup-manifest.jsonl` in the backup directory, and a backup becomes a fact you can read rather than a file whose mtime you infer from:

```
{"finished_at":"2026-07-20T04:00:03Z","epoch":1784...,"status":"ok",
 "target":"s3://…","db_basis_t":1037,"analytics_basis_t":42,
 "uploads_files":128,"uploads_bytes":53400000,"config":true,
 "offbox_files":"ok","duration_s":6}
```

Three things fall out of that line, and each closes a gap a green checkmark would have left open. The **`status`** is written on failure too (a shell trap stamps `status:"failed"` before the script dies), so a crashed run is a dated failure in the log, not an absence you have to notice. The **`offbox_files`** field turns "are the photos off the box?" from an assumption into a value -- `ok`, `skipped`, or `failed` -- so the operator who set `BACKUP_URI` to s3 but forgot `BACKUP_FILES_SYNC` sees `skipped` rather than discovering it during a restore. And the **`db_basis_t`** records *what point in time* this archive actually captured, which is the difference between "a backup ran" and "the backup holds Tuesday's writes." [The watchdog](38-alerting.md) now reads this manifest instead of a file timestamp -- a file-mtime check cannot tell a good backup from a half-written one, and it never even sees the databases once they write off-box -- and [the restore drill below](#the-drill) appends its own `verdict:"PASS"` line, so *when a backup was last proven restorable, and to what basis-t* is one `tail` away too. The manifest is small, append-only, and rides off-box with everything else; making the backup legible cost one function and bought the one property the alerting could not give it -- the ability to answer, at 3 a.m., not "did it run?" but "what exactly do I have?"

One scar from building this, kept because every operator will eventually earn it themselves: `/etc/myapp/env` acquired **two readers with two grammars**. systemd's `EnvironmentFile=` parses raw `key=value`; the ops scripts `source` it through bash -- where the unquoted `&` inside a JDBC URI backgrounds half the line and the script dies on an unbound variable. The fix is to double-quote values (both grammars accept quotes), and `env.example` now says why.

## The drill

A backup you have not restored is a prayer with a timestamp. The drill was run for real, and both of its stumbles are kept in the text because they *are* the documentation:

First stumble: restoring under a different database name into the *same* storage (the obvious lazy drill) is refused by Datomic (`Could not find myapp-drill in catalog`); a database's identity travels with it. The drill therefore restores into **separate storage**, which is what you wanted anyway: a scratch PostgreSQL database (stock bootstrap scripts, thirty seconds) stands in for "your laptop," and production is never touched:

```
$ datomic restore-db file:$BACKUP_DIR/myapp \
    "datomic:sql://myapp?jdbc:postgresql://localhost:5432/datomic_drill?..."
{:event :restore, :db myapp, :basis-t 1037, ...}
```

Second stumble: the restored database answered no peer, because *a peer finds its transactor through storage* -- restored storage has no heartbeat until a transactor serves it. The fix demonstrates how cheap a whole second Datomic stack is: copy `transactor.properties`, change the port and the JDBC URL, start it. Two lines of `sed`. Then the verification -- live database and restored database, queried side by side:

```
RESULT live-recipes: 8 restored-recipes: 8
RESULT titles-equal: true
RESULT history-intact: true
RESULT verdict: PASS
```

The third line is the one this book cares about most. `d/history` works on the restored database -- every version, [every diff](09-recipe-domain.md), [every transaction's author and note](10-provenance.md) crossed the backup boundary intact, because the archive carries the log, not a snapshot of the present. The features this application is *about* survive its disaster story. That is not a given elsewhere: a `mysqldump` of a hand-built versions table restores whatever the versions table happened to contain; this restores *the time axis itself*.

The production runbook falls out of the same property that made the drill safe, and [the URI-is-config design](35-going-live.md) pays one more time: restore into *fresh storage under the same name* (the first stumble is why "fresh database name" is not on the menu), point the transactor's `sql-url` and then `DATABASE_URI` at it -- the second stumble is why the transactor moves first -- and restart both. The damaged original is never overwritten (it remains standing as evidence), and "rollback of the restore" is editing those lines back.

## The chore ch.36 promised

[The pair-deploy chapter](36-minimal-downtime.md) made static assets accumulate (old builds must keep finding their old hashes during the overlap) and named the garbage collection a small chore for these chapters to inherit. Inherited, drilled, done: `myapp-static-gc.sh` deletes hashed files that the *current* manifest does not reference **and** that are older than thirty days, weekly, on a timer, alert-on-failure like everything else. The double condition is the safety: age alone never deletes (a referenced file's mtime means nothing: the drill aged the live stylesheet forty days and it survived), and non-reference alone never deletes (yesterday's build's assets are unreferenced and *must* survive the overlap). The drill planted a forty-day-old orphan and an aged-but-referenced stylesheet; the script removed only the orphan.

## Scheduled work, the general answer

Three timers now run this box (watchdog, backup, asset GC) -- [the resilience capstone](46-watching-the-watchers.md) adds a fourth that re-runs this chapter's restore drill on a schedule -- and that is this book's entire background-jobs story, so the position deserves stating once, plainly. Every periodic need this system has turned out to have is *box-shaped*: check the machine, copy the machine's state, sweep the machine's disk. For box-shaped work, systemd timers are strictly better than an in-process scheduler -- they survive the app, they compose with `OnFailure=` alerting, they show up in `systemctl list-timers` next to everything else, and they cost zero new infrastructure. What this application has never yet needed is a *domain-shaped* job (the digest email, the delayed webhook), and the day one appears is the day a jobs queue earns real consideration, [as the afterword has said all along](afterword.md). Until then, a queue would be machinery in search of a requirement.

## Trade-offs & limitations, in one place

- **The incremental target grows forever.** `backup-db` accumulates; deleted and excised data lives on in old segments. Rotate to a fresh target periodically (monthly is plenty at this write rate) and let the old target *be* the long-term archive tier.
- **The backup is a secret.** It contains every datom *and* `/etc/myapp`'s keys. The directory is `0700`; offsite copies deserve encryption at rest; and a backup shelf with weaker access control than the box is the box's real perimeter.
- **Two databases, two archives, no atomic cut.** `myapp` and `myapp-analytics` back up sequentially, so a restore pair can differ by a few seconds of writes -- concretely, a magic-link nonce recorded in the gap could re-verify after a restore of the analytics side alone. Real, tiny, and bounded by [the fifteen-minute token expiry](24-auth-tokens.md).
- **The drill does not schedule itself.** The timer runs backups; only the operator's calendar runs restores. Monthly, into scratch storage, checking the three RESULT lines -- twenty minutes that convert the backup system from faith to knowledge.
- **PostgreSQL-level tooling remains the other road.** `pg_dump`/PITR of the shelf backs both databases in one consistent cut and leans on the deepest operational tooling in the industry -- at the price of the storage-agnostic restore and the laptop drill. Choosing it instead is defensible; running *both* is cheap paranoia with two restore paths.

## What backup cannot forget

One thread runs uncomfortably through this chapter: the archive remembers *everything*, forever, incrementally -- which is exactly its job, and the problem the next chapter has to face. This application [promised its users deletion is real](09-recipe-domain.md) only in the present tense; the history, [the log](26-activity.md), and now the backup shelf all remember. The right to be forgotten has to contend with all three, and Datomic's answer, excision (the deliberately heavyweight write that unsays), has been deferred since chapter 9. No longer.
