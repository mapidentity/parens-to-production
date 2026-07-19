# The failover drill

Datomic Pro's high-availability design is a **standby transactor pair coordinating
through storage**: two transactors point at one storage, whichever holds the
storage lease is *active* and serves, the other *waits*. When the active dies,
the standby acquires the lease, promotes, and peers rediscover it through
storage. Chapter 41 and the afterword flagged this pair as the one thing the book
*ran* but could not *drill* — the kill-the-active takeover would not converge in a
sandbox afternoon.

This lab drills it. It is a **single-host** lab (two transactors on one box,
distinct ports), so it verifies the *takeover mechanism* and measures a window;
it does not stand in for a two-machine production failover with a real network
and a tuned heartbeat. That much stays fleet rehearsal (§ *What this does not
prove*).

## Prerequisites

- **PostgreSQL** (the storage). `apt install postgresql` gives you a running
  cluster on 5432.
- **Datomic Pro** (the transactor — free since 2023, no licence key). Download
  the distribution matching the peer version in `deps.edn`
  (`com.datomic/peer`), e.g. `datomic-pro-1.0.7491.zip` from
  `https://datomic-pro-downloads.s3.amazonaws.com/1.0.7491/datomic-pro-1.0.7491.zip`,
  and unzip it. The peer + the postgres JDBC driver are already on the app's
  classpath, so no extra deps.

## 1. Storage

Create the Datomic storage in Postgres — the three scripts ship inside the
distribution (`bin/sql/postgres-*.sql`): a `datomic` database, a `datomic` role,
and the `datomic_kvs` table. Then set the role's password to match the
transactor config:

```bash
sudo -u postgres psql -f <datomic>/bin/sql/postgres-user.sql
sudo -u postgres psql -f <datomic>/bin/sql/postgres-db.sql
sudo -u postgres psql -d datomic -f <datomic>/bin/sql/postgres-table.sql
sudo -u postgres psql -c "ALTER ROLE datomic LOGIN PASSWORD 'datomic';"
sudo -u postgres psql -d datomic -c "GRANT ALL ON TABLE datomic_kvs TO datomic;"
```

## 2. The transactor pair

Start A (active) and B (standby) against that one storage. B will log `System
started` but will NOT bind its port 4335 — it is waiting as standby.

```bash
cd <datomic>
./bin/transactor /workspace/ops/lab/txor-a.properties   # active, binds 4334
./bin/transactor /workspace/ops/lab/txor-b.properties   # standby, waits
```

> A JVM launched with `&` in some shells leaks `SIGURG` (the JVM's async-preempt
> signal) back to the parent; run each transactor in its own terminal or a
> background job manager that detaches cleanly.

## 3. The workload, then the kill

Start the probe (commits every 500ms, timestamps every result), let it run a few
seconds so writes are flowing through A, then **kill the active transactor**:

```bash
clojure -M -e '(load-file "ops/lab/failover-probe.clj")'   # in one shell
pkill -9 -f txor-a.properties                              # in another: kill A
```

Watch the probe output: `OK` lines stop, `ERR` lines fill the gap while the
standby notices the dead lease and promotes, then `OK` lines resume — through B,
which has now bound 4335. The peer never reconnected by hand; it followed the
new active transactor's location out of storage on its own.

## The captured result

Killing A mid-write (peer on the same `conn` throughout):

```
  [last OK  −83ms]  OK  tx=29  basis-t=7022     <- last commit via A
  [   +437ms]       ERR tx=30                    <- A gone; writes fail
  ...               ERR  (tx 30..53, 24 writes)  <- the failover gap
  [ +12450ms]       OK  tx=54  basis-t=7024      <- standby promoted; peer resumed via B
  [ +13023ms]       OK  tx=55  basis-t=7026      <- steady state again
```

- **Write-unavailability window: ~12.5 s** (24 failed writes × 500ms). It is
  dominated by Datomic's default heartbeat/lease timeout — how long the standby
  waits before deciding the active is truly gone — which is configurable.
- **Zero committed transactions lost.** basis-t is continuous across the gap
  (7022 → 7024); the failed writes never committed, so the immutable log has no
  hole. A real app would surface those failures to the user (or retry) exactly
  as it would any transient transactor error.
- **No application code involved.** The same peer connection transparently
  rediscovered the promoted transactor through storage and resumed. The app does
  not know a failover happened, only that some writes briefly failed.
- **Reads stay up throughout.** A Datomic peer serves reads from its own object
  cache without talking to the transactor; only the *write* path gaps. The probe
  exercises writes because they are the half that fails.

## A simpler case: the restart blip

The same harness drills the everyday case a peer is claimed to "ride out on its
own" -- a transactor *restart* (a deploy, an OOM-and-Restart, a `systemctl
restart`), where there is no standby, just the one transactor coming back:

```bash
clojure -M -e '(load-file "ops/lab/failover-probe.clj")'   # peer writing
pkill -9 -f txor-b.properties && ./bin/transactor .../txor-b.properties  # kill + restart the SAME node
```

Captured: writes paused for **~20 s**, then resumed on the same `conn`, `basis-t`
continuous (7418 -> 7420), no committed transaction lost. Worth noting it is
*slower* than the standby failover above (~12.5 s): a restart-blip has no warm
standby waiting, so the peer must wait out the whole transactor JVM rebooting and
reacquiring the storage lease. A warm standby exists precisely to shorten this.

## What this does not prove

- **Production topology.** Two transactors on one host is not two machines with a
  network between them. The takeover *mechanism* (storage-coordinated lease +
  peer rediscovery) is the same; the ~12.5 s *number* is a single-host artifact
  of default timings.
- **Split brain.** A real network partition can make both transactors believe
  they are alone. Datomic's lease coordination is designed to prevent two active
  transactors, but *proving* that under partition needs a two-host lab with
  fault injection — fleet rehearsal, not this afternoon.
- **A tuned heartbeat.** Shortening the lease timeout shrinks the window at the
  cost of more false failovers under load; picking that trade-off is an
  operating decision this lab only makes visible.
