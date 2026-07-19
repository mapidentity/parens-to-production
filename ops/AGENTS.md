# AGENTS.md — the production box: run it, defend it, drill it

> Everything the single box IS off the JVM: systemd units, the prod Caddyfile,
> deploy scripts, firewall + fail2ban, the operator RUNBOOK, and the `lab/`
> failover drill. Nothing here compiles — it is shell, unit files, and config
> that surround the app. Edits here change how the box boots, deploys, alerts,
> and gets attacked, not what a request renders.

## Key files
- `myapp.service` — the steady-state unit (binds 3000; carries the loopback REPL, OOM heap-dump, systemd sandbox).
- `myapp@.service` — template pair unit (`myapp@3001`/`@3002`), one jar per instance; NO REPL (two instances can't share the port).
- `myapp-transactor.service` — Datomic transactor (`User=datomic`, reads `/etc/myapp/transactor.properties`).
- `deploy-myapp.sh` / `deploy-pair.sh` — single-instance / minimal-downtime deploys; both flock-guarded, both smoke-gated, both roll back jar+manifest together.
- `Caddyfile` — TLS edge + upstream pool (3000/3001/3002), health checks, static/uploads/img serving, the branded 503, security headers (but NOT CSP).
- `myapp-{backup,backup-verify,watchdog,static-gc}.sh` + matching `.service`/`.timer` — the oneshot+timer chores; each fails loud via `OnFailure=myapp-alert@`.
- `myapp-alert.sh` / `myapp-notify.sh` — the one alert spine (webhook-first, relay best-effort); reused by units AND fail2ban.
- `nftables.conf`, `fail2ban/` — default-deny firewall + the auth-brute-force jail.
- `RUNBOOK.md` — the 3am triage order and every containment lever; read it before editing any script it documents.
- `lab/` — the failover drill: `DRILL.md`, `failover-probe.clj`, `txor-a/b.properties`. `observer/` — the off-site second opinion.

## Conventions / rules
- **DRILLED vs REASONED is a load-bearing distinction, not decoration.** Backup/restore/excision/failover are *drilled* (a probe or timer proves them); the firewall and systemd sandbox are *reasoned*. If you add an ops claim, mark which it is — never upgrade "reasoned" to "drilled" without a real run.
- **`/etc/myapp/env` is the single source of truth.** Every script does `set -a; . /etc/myapp/env; set +a`. `env.example` is the contract and boot refuses on any missing value (`myapp.config/require-prod-config!`). Values are **double-quoted** because two readers parse the file — systemd `EnvironmentFile=` and shell `source`; an unquoted `&` in a JDBC URI backgrounds half the line.
- **A deploy is ONE unit: jar + `asset-manifest.edn` together.** Snapshot both to `.prev` before touching anything, install assets *additively*, and on any failure restore both. Old code must never meet a newer manifest — that regression is why the scripts exist.
- **Alerts go out-of-band FIRST, relay best-effort (`|| true`).** The app's worst outage is a dead SMTP relay; an alert about it must not route through it. Never make the relay path fatal in `myapp-alert.sh`/`myapp-notify.sh`/`observer.sh`.
- **The fail2ban filter regex is a CONTRACT with `src/myapp/web/security.clj`.** `failregex` anchors `<HOST>` to the FIRST `ip=` field (`^.* SECURITY event=auth\.failure ip=<HOST>`). A greedy `.*ip=` would bind the last `ip=` (attacker-smuggleable). Change the log format, change this file.
- **Caddy sets security headers but MUST NOT set CSP** — the app owns the per-document inline-script hashes. `/metrics` is 404 to the internet.
- **`MemoryDenyWriteExecute` is deliberately ABSENT from every JVM unit** (the JIT maps W-then-X pages). Do not "complete the hardening set" by adding it — it kills the process.
- **The datastore has two independent walls:** loopback bind (config) AND default-deny (`nftables.conf`). Keep both; one misconfig must not be an exposure.
- Scripts install to `/etc/scripts/`; unit `ExecStart=` paths assume that. Deploy scripts take env overrides (`MYAPP_ROOT`, `MYAPP_STATIC`, `MYAPP_SYSTEMCTL`, `MYAPP_HEALTH_BASE`) so the logic is drillable off a real box.

## Gotchas (sandbox / drill traps)
- **`host=localhost` in a transactor `.properties` hangs the peer on a dual-stack box** (`localhost`→`::1` first, transactor bound to IPv4). The lab configs pin `host=127.0.0.1` for exactly this — do not "restore" it to `localhost`.
- **Launching a JVM with `&` leaks `SIGURG` into the parent shell** (the JVM's async-preempt signal). Run each transactor in its own terminal or a real detaching job manager; guard `pkill` with `|| true`.
- **A dev container has no PID-1 systemd.** Every `systemctl`/timer/`OnFailure=` path only runs on the real box. The shell scripts are drillable standalone (via the env-var path overrides); the units are not.
- **`datomic:mem` makes backup/restore/excision silent no-ops.** `backup-verify.sh` and the failover lab need real SQL storage (PostgreSQL). `backup-verify.sh` refuses to run without `VERIFY_SCRATCH_URI` so it can never restore over production.
- The production REPL (`MYAPP_REPL_PORT=5555`) is **unauthenticated RCE on loopback**; SSH-tunnel is the only auth. Never bind it to a routable address, never add it to the pair unit.

## Running / testing what's here
- Failover drill: `clojure -M -e '(load-file "ops/lab/failover-probe.clj")'` in one shell, then `pkill -9 -f txor-a.properties` in another. Full procedure + captured result in `lab/DRILL.md`.
- Deploy scripts off-box: set `MYAPP_SYSTEMCTL` to a stub on PATH and `MYAPP_ROOT`/`MYAPP_STATIC`/`MYAPP_HEALTH_BASE` to scratch dirs, then run `./deploy-myapp.sh <jar> <static>` / `--rollback`.
- There are no clj test namespaces in `ops/` — these are shell + systemd + a lab, verified by running them, not by `clojure -X:test`. Shell edits: keep `set -euo pipefail` and re-run the script against scratch paths.

## See also
- Book: ch.35 *Going Live*, ch.36 *Minimal Downtime*, ch.37 *Legible at Runtime*, ch.38 *Alerting*, ch.39 *Backup, Restore, and the Drill*, ch.40 *Excision*, ch.41 *Beyond One Box* (the failover the lab finally drills), ch.42 *Detect and Respond*, ch.43 *Harden and Patch*, ch.46 *Watching the Watchers*.
- Depends on: `../src/myapp/web/security.clj` (the fail2ban log contract), `../src/myapp/config.clj` (`require-prod-config!`), `../static/error/503.html` (the maintenance page Caddy serves), `../caddy/Caddyfile` (the dev sibling this mirrors), `../dev/bench.clj` (where the peer object-cache size was measured).
