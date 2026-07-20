# Harden and Patch: The Firewall, the CVE Gate, and the Morning After

[The detection chapter](42-detect-respond.md) gave the box eyes and levers. This one shrinks what those eyes have to watch. Detection and response answer *an attack is happening*; hardening and patching answer the quieter, more constant question — *how small and how current is the surface an attacker even gets to touch?* This is the unglamorous half of a security-operations practice, and the half most single-box deployments skip entirely. It is also the last chapter, because with it the box graduates from running and observed to *defensible*.

Four surfaces, four closures: the network, the dependency tree, the OS, and the process itself.

## The firewall: default-deny, as a rule not a hope

[Going live](35-going-live.md) bound the datastore to loopback — the transactor on `4334`, PostgreSQL on `5432`, the app on `3000` — so nothing but Caddy faces the internet. That was true, and it rested entirely on *bind addresses*: one config typo, one service that defaults to `0.0.0.0`, and a port is exposed with nothing to catch it. A firewall makes the same guarantee a second time, at a different layer, so the two are independent. `ops/nftables.conf` is default-deny:

```
	chain input {
		type filter hook input priority filter; policy drop;

		ct state established,related accept
		ct state invalid drop
		iif "lo" accept
		,,,
		tcp dport 22 ct state new limit rate 5/minute accept
		tcp dport { 80, 443 } accept

		# Everything else — including 3000-3002, 4334, 5432 — is dropped by
		# the chain policy. No rule needs to name them; default-deny does.
	}
```

The shape is the argument. `policy drop` means the datastore ports are closed *without a rule mentioning them* — you cannot forget to block what is blocked by default; you can only forget to *open*, which fails safe. Loopback is accepted, because [the whole app↔proxy↔datastore conversation rides `lo`](35-going-live.md). SSH is rate-limited at the packet level (five new connections per minute per source) so a credential-spray is throttled before `sshd` even accepts it (key-only auth is `sshd`'s job; this is depth beneath it). And only `80`/`443` are open to the world, because everything else the box runs is behind Caddy on loopback. The [fail2ban bans from the previous chapter](42-detect-respond.md) land in nftables' own separate table, so the two compose without touching.

One honesty note, and it is the same one [the scaling audit](41-beyond-one-box.md) made about the transactor's failover: this ruleset is *reviewed against the nftables grammar, not applied in the book's sandbox*, which lacks the kernel capability to load packet rules. Applying and testing it (`nft -f`, then a port scan from off-box confirming only 80/443 answer) is a step for the real host, and the runbook says so. A firewall you have not port-scanned from outside is a firewall you are trusting; this chapter marks which claims it drilled and which it reasoned.

## The CVE gate, made real

[Chapter 34 described](34-ci-cd.md) a dependency CVE scan — `nvd-clojure`, walking the resolved classpath against the National Vulnerability Database, failing the build on a scored advisory. The SecOps audit found the catch: it was *described*, in prose, and the `:nvd` alias was never in `deps.edn`. A control you have documented but not wired is a control you have only imagined. So it is wired now:

```clojure
  :nvd {:replace-deps {nvd-clojure/nvd-clojure {:mvn/version "5.3.0"}}
        :main-opts ["-m" "nvd.task.check"]}
```

`clojure -M:nvd "" "$(clojure -Spath)"` now resolves and runs, verified to the point where the tool loads and demands its classpath argument, which the CI invocation supplies. The full scan downloads the NVD data feed on first run, which is slow and rate-limited without an `NVD_API_KEY`, the caveat chapter 34 already stated, and the reason the feed is cached in CI, downloaded once and reused. What matters for the audit is that the gate is no longer vapor: the alias exists, the tool is on the classpath, and [the pipeline](34-ci-cd.md) has a real step to run.

The gate has a blind spot that motivates the next section: **it scans the application's Java classpath, and nothing else.** The kernel, `openssl`, `curl`, the C libraries the JVM links — the CVE that lands there is invisible to `nvd-clojure`, because that is not what it looks at.

## Patching the OS: the boring closure

The layer the CVE gate cannot see is the one a distribution patches for you, if you let it. `ops/apt/20auto-upgrades` turns on unattended *security* upgrades:

```
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
```

Nightly, the box pulls its distribution's security pocket and applies it (the kernel, TLS libraries, everything under the JVM) with no human in the loop, which for security fixes is the correct amount of human. One deliberate exception: **reboots stay manual.** A kernel update that needs a reboot, on a single box with no drain partner, is a scheduling decision, not an automatic one, and when you take it, it costs the few seconds of [the branded maintenance page](34-ci-cd.md), no more. The pairing is the patch story: `nvd-clojure` for what you bundled, `unattended-upgrades` for what the OS ships, and neither pretends to cover the other's half.

(Base-image freshness is the same concern one layer up, and [chapter 34 already argued it](34-ci-cd.md): digest-pin the CI/production base image and pair it with the scan, so a rebuild picks up a *known* base rather than whatever drifted into a floating tag. The argument is there; the discipline is to actually pin, and to rebuild deliberately.)

## Hardening the process: the JVM-shaped hole

The last surface is the process itself: if the app is compromised, how much of the box does the attacker inherit? systemd answers with sandboxing, and [the going-live units](35-going-live.md) carried a starter set — `NoNewPrivileges`, `PrivateTmp`, `ProtectSystem`. This chapter completes it. The long-running, internet-adjacent JVM units — the app instances and the transactor — carry the full block:

```ini
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
RestrictNamespaces=true
LockPersonality=true
SystemCallFilter=@system-service
SystemCallErrorNumber=EPERM
```

`ProtectSystem=strict` makes the entire filesystem read-only except the few paths a unit is explicitly granted: the app gets exactly two writable directories -- `/var/log/myapp` (via `LogsDirectory=`, which is also where [the security log](42-detect-respond.md) lands) and [the uploads root](49-file-storage.md) (via `ReadWritePaths=`, a literal path the sandbox will not infer; the boot check proves the grant took, so a forgotten line fails the deploy rather than a user's photo); the backup unit gets its backup directory and nothing else. `RestrictAddressFamilies` means a compromised process cannot open a raw socket to spoof packets or scan. `SystemCallFilter=@system-service` denies the syscalls a normal service never makes — `mount`, `ptrace`, kernel-module loading — turning a code-execution foothold into a much smaller box.

And one directive is conspicuously, deliberately **absent**: `MemoryDenyWriteExecute`. It is the standard hardening advice (forbid pages that are both writable and executable, defeating a class of code-injection exploit) and it is *poison to a JVM*, whose JIT compiler writes machine code to memory and then executes it. Set it and the app dies on its first hot method. This is the JVM-shaped hole in the standard hardening checklist, and the right move is to name it in the unit file rather than let a future operator copy a "recommended" line that breaks the box. Every hardening set is a negotiation with what the workload actually does; this is where ours draws the line, out loud. The auxiliary oneshot units (watchdog, backup, alert, GC, observer) carry a strong subset of the same block (`ProtectSystem=strict`, `ProtectHome`, `RestrictAddressFamilies`, and per-unit write paths) pared to what a short-lived shell script needs, well short of the full JVM-service filter. All the units still verify clean under `systemd-analyze verify` — the syntax is proven; the runtime behavior, like the firewall, is the real host's to confirm.

## The morning after: a runbook that uses what we built

Detection, response, and hardening only pay off if someone can act at 3am without inventing procedure. `ops/RUNBOOK.md` is that procedure, and every step is a lever this volume actually built:

- **Triage in order:** is the box reachable ([the observer](38-alerting.md) already emailed if not), what does it say about itself ([journalctl, metrics](37-runtime-legibility.md)), is it an attack ([the security log, fail2ban](42-detect-respond.md)).
- **Contain without a redeploy:** [ban an IP](42-detect-respond.md) (`fail2ban-client set myapp banip`), [ban a user](42-detect-respond.md) (`set-active!`), revoke all sessions (rotate `SESSION_KEY`, the blunt instrument that is *correct* for a session compromise), or take the app to its maintenance page.
- **Rotate a leaked signing key** through [the grace window](42-detect-respond.md) so no active login breaks — the exact four steps, including deleting `SIGNING_KEY_PREVIOUS` after the token TTL.
- **When a CVE lands,** the two paths: a dependency (`:nvd` names it, bump and ship through [the pair](36-minimal-downtime.md)) or the OS (unattended-upgrades already took it; urgent ones by hand).
- **After any incident,** preserve the [persistent journal](37-runtime-legibility.md) and the security log off-box before cleaning up, and reach for [excision](40-excision.md) if personal data was exposed.

The runbook is short because the system did the hard part: it made the levers exist. A runbook over a system with no levers is a wish list.

## Trade-offs & limitations, in one place

- **The firewall and hardening are reasoned, not sandbox-drilled.** Loading nftables rules and enforcing systemd sandboxes both need kernel capabilities the book's build environment lacks. The configs are grammar-checked (`systemd-analyze verify` passes; the ruleset parses) and the runbook prescribes the real-host confirmation — a port scan from outside, a `systemctl show` of the effective sandbox. This is [the same honesty line the scaling audit drew](41-beyond-one-box.md): named, not faked.
- **The CVE feed is heavy.** `nvd-clojure`'s first run downloads the whole NVD database; without an `NVD_API_KEY` it is rate-limited. This is a CI-caching concern, not a per-developer one, and it is why the gate lives in the pipeline, not a git hook.
- **Unattended upgrades can, rarely, break a running service.** Security-only scope and manual reboots keep the blast radius small, but the mitigation that matters is that [the backup drill](39-backup-restore.md) and [the observer](38-alerting.md) exist: if a nightly upgrade wedges the box, you have a restore and you will be told.
- **Suppressions are a footgun.** An `.nvd-suppressions.xml` that grows unaudited turns the gate green by lying. The runbook's rule — scoped, dated, justified, never blind — is a discipline the tool cannot enforce.
- **This is one box's security.** fail2ban's bans, the firewall's rules, the security log, all local. [A fleet](41-beyond-one-box.md) needs shared ban state, a central SIEM, and a real threat-intelligence loop; the audit priced that road. What this volume delivers is a single box you can defend, patch, and reason about completely — which was the wager all along.

## What the box can now defend

The defending arc closes not on a product bought or a fear outsourced but on a box that can *see* an attacker, *answer* without a redeploy, and keep its own surface small and current. Every control here is either drilled or honestly named as reasoned. A [security-event trail](42-detect-respond.md) fail2ban reads and bans at the packet level; [live containment levers](42-detect-respond.md) (ban an IP, ban a user, rotate a key through a grace window) that need no deploy; a default-deny firewall, a real dependency-CVE gate, and unattended OS patching. The same "own it all the way down" that rendered the first page in a millisecond now watches, answers for, and hardens the last one. Which means the box is now built, operated, and *held*, and that is the standing from which the chapters that follow return to the application, for the features everyone said choosing the server would force you to give up.
