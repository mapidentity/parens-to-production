# Backup, Restore, and the Drill

[The alerting chapter](38-alerting.md) ended on the failures that send no alert: the disk that dies rather than fills, the wrong `rm`, the box that simply never comes back. For those, the operator needs yesterday's data standing somewhere else -- and, much more importantly, *proof* that it stands back up. The industry's quiet shame is that most backup systems are write-only: run nightly for years, restored never, discovered broken at the worst possible moment. So this chapter's center of gravity is not the backup script. It is the drill.

## What is state, and which tool carries it

[Going live](35-going-live.md) reduced "what is state on this box" to a two-item list: the PostgreSQL data directory, and `/etc/myapp`. For the first item there are two honest tools. `pg_dump` backs up the shelf -- and since [the shelf is one four-column table](35-going-live.md), it would work fine. The book chooses Datomic's own `backup-db` instead, for three properties the drill will exploit:

- **The restore is storage-agnostic.** A `backup-db` archive restores into *any* storage -- a sibling PostgreSQL database, the dev storage on your laptop -- which is precisely what lets you rehearse a restore without breathing near production. A `pg_dump` restores into PostgreSQL, full stop.
- **It is incremental by construction.** Pointed at the same target URI, it writes only segments new since last time; the nightly cost is proportional to the night's writes, not the database.
- **It stamps its own consistency.** The archive is taken at a basis-t and says so -- the restore below announces `:basis-t 1037` in its own output. No quiescing, no lock coordination with a live transactor; immutable segments make hot backup boring.

What `backup-db` cannot carry is the second item, so the script carries it alongside -- with the consequence stated where the operator will read it:

```bash
"$DATOMIC" backup-db "$DATABASE_URI" "file:$BACKUP_DIR/myapp"
"$DATOMIC" backup-db "$ANALYTICS_DATABASE_URI" "file:$BACKUP_DIR/myapp-analytics"

# The other half of "what is state on this box" (ch.35): /etc/myapp.
# The env file holds the crypto keys — the backup directory must be
# treated as exactly as secret as the box itself.
cp -a /etc/myapp "$BACKUP_DIR/etc-myapp"
```

The schedule is [the watchdog chapter's](38-alerting.md) pattern at a longer period -- `myapp-backup.timer`, nightly at 04:00, `Persistent=true` so a box that was off runs the missed backup on boot. And the backup *unit* wires into the alerting spine for the reason its comment states outright: `OnFailure=myapp-alert@%n.service`, because **a backup that fails silently is worse than none -- it converts "we have no backups" into "we think we have backups."** Offsite is the same command with a different URI -- `backup-db` speaks `s3://` natively, credentials from the environment -- named here and not drilled, because this book's sandbox has no bucket and pretending otherwise would break the chapter's own rule.

One scar from building this, kept because every operator will eventually earn it themselves: `/etc/myapp/env` acquired **two readers with two grammars**. systemd's `EnvironmentFile=` parses raw `key=value`; the ops scripts `source` it through bash -- where the unquoted `&` inside a JDBC URI backgrounds half the line and the script dies on an unbound variable. The fix is to double-quote values (both grammars accept quotes), and `env.example` now says why.

## The drill

A backup you have not restored is a prayer with a timestamp. The drill was run for real, and both of its stumbles are kept in the text because they *are* the documentation:

First stumble: restoring under a different database name into the *same* storage -- the obvious lazy drill -- is refused by Datomic (`Could not find myapp-drill in catalog`); a database's identity travels with it. The drill therefore restores into **separate storage**, which is what you wanted anyway: a scratch PostgreSQL database (stock bootstrap scripts, thirty seconds) stands in for "your laptop," and production is never touched:

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

The production runbook falls out of the same property that made the drill safe, and [the URI-is-config design](35-going-live.md) pays one more time: restore into a *fresh* database, point `DATABASE_URI` at it, restart. The damaged original is never overwritten -- it remains standing as evidence -- and "rollback of the restore" is editing one line back.

## The chore ch.36 promised

[The pair-deploy chapter](36-minimal-downtime.md) made static assets accumulate -- old builds must keep finding their old hashes during the overlap -- and named the garbage collection a small chore for these chapters to inherit. Inherited, drilled, done: `myapp-static-gc.sh` deletes hashed files that the *current* manifest does not reference **and** that are older than thirty days, weekly, on a timer, alert-on-failure like everything else. The double condition is the safety: age alone never deletes (a referenced file's mtime means nothing -- the drill aged the live stylesheet forty days and it survived), and non-reference alone never deletes (yesterday's build's assets are unreferenced and *must* survive the overlap). The drill planted a forty-day-old orphan and an aged-but-referenced stylesheet; the script removed exactly the orphan.

## Scheduled work, the general answer

Three timers now run this box -- watchdog, backup, asset GC -- and that is this book's entire background-jobs story, so the position deserves stating once, plainly. Every periodic need this system has turned out to have is *box-shaped*: check the machine, copy the machine's state, sweep the machine's disk. For box-shaped work, systemd timers are strictly better than an in-process scheduler -- they survive the app, they compose with `OnFailure=` alerting, they show up in `systemctl list-timers` next to everything else, and they cost zero new infrastructure. What this application has never yet needed is a *domain-shaped* job -- the digest email, the delayed webhook -- and the day one appears is the day a jobs queue earns real consideration, [as the afterword has said all along](afterword.md). Until then, a queue would be machinery in search of a requirement.

## Trade-offs & limitations, in one place

- **The incremental target grows forever.** `backup-db` accumulates; deleted and excised data lives on in old segments. Rotate to a fresh target periodically (monthly is plenty at this write rate) and let the old target *be* the long-term archive tier.
- **The backup is a secret.** It contains every datom *and* `/etc/myapp`'s keys. The directory is `0700`; offsite copies deserve encryption at rest; and a backup shelf with weaker access control than the box is the box's real perimeter.
- **Two databases, two archives, no atomic cut.** `myapp` and `myapp-analytics` back up sequentially, so a restore pair can differ by a few seconds of writes -- concretely, a magic-link nonce recorded in the gap could re-verify after a restore of the analytics side alone. Real, tiny, and bounded by [the fifteen-minute token expiry](24-auth-tokens.md).
- **The drill does not schedule itself.** The timer runs backups; only the operator's calendar runs restores. Monthly, into scratch storage, checking the three RESULT lines -- twenty minutes that convert the backup system from faith to knowledge.
- **PostgreSQL-level tooling remains the other road.** `pg_dump`/PITR of the shelf backs both databases in one consistent cut and leans on the deepest operational tooling in the industry -- at the price of the storage-agnostic restore and the laptop drill. Choosing it instead is defensible; running *both* is cheap paranoia with two restore paths.

## What backup cannot forget

One thread runs uncomfortably through this chapter: the archive remembers *everything*, forever, incrementally -- which is exactly its job, and exactly the problem the next chapter has to face. This application [promised its users deletion is real](09-recipe-domain.md) only in the present tense; the history, [the log](26-activity.md), and now the backup shelf all remember. The right to be forgotten has to contend with all three, and Datomic's answer -- excision, the deliberately heavyweight write that unsays -- has been deferred since chapter 9. No longer.
