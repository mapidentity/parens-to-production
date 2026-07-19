# The System Calls for Help: Alerting Without a Pager Service

[The previous chapter](37-runtime-legibility.md) ended on its own limitation: a box that can answer every question and volunteers nothing. The audit that started these chapters found the canonical version of the problem, and it is worth restating because it justifies everything below: **this application's worst outage is invisible.** Sign-in *is* email; if the relay dies, the site stays green -- every page renders, `/health` passes, and [the don't-reveal design](25-auth-email-flow.md) shows each failed visitor a cheerful "Check your email." No user can tell you, because telling you requires logging in. A system like that does not need a dashboard. It needs to *speak*.

"Alerting" usually names a platform -- PagerDuty, OpsGenie, a Grafana with rules. A fleet earns those. One box has, already installed and already configured, the only two ingredients alerting is made of: a scheduler with failure semantics (systemd) and a message channel the operator reads (the SMTP relay [the config refuses to boot without](35-going-live.md)). This chapter wires those together and buys one thing at the end.

## The watchdog

`ops/myapp-watchdog.sh` runs three checks, one per class of silent death the audits actually surfaced:

```bash
# Same source of truth as the app itself.
set -a; . /etc/myapp/env; set +a
,,,
# 1. An instance answers /health — any one of the pool's ports. This is
#    the app's own no-lies health check (it proves both database
#    connections), asked from the box, past the proxy.
healthy=""
for p in 3000 3001 3002; do
  if curl -fsS -m 5 "http://127.0.0.1:$p/health" >/dev/null 2>&1; then healthy=$p; break; fi
done
[ -n "$healthy" ] || fail "no instance answers /health on 3000/3001/3002"

# 2. The mail relay accepts a TCP connection. Sign-in IS email here, and a
#    dead relay is the one outage that keeps every page green: the app
#    boots, health passes, and nobody can log in. The don't-reveal design
#    means no user will tell you either. A connect is the side-effect-free
#    probe /health deliberately refuses to make in-band.
timeout 5 bash -c "exec 3<>/dev/tcp/$SMTP_HOST/$SMTP_PORT" 2>/dev/null \
  || fail "SMTP relay $SMTP_HOST:$SMTP_PORT does not accept connections"

# 3. Disk headroom. The slowest outage on any single box is the full disk;
#    it deserves two minutes' notice, not a crashed transactor's.
usage=$(df --output=pcent / | tail -1 | tr -dc '0-9')
[ "$usage" -lt 90 ] || fail "root filesystem at ${usage}% — reclaim space"
```

Each check earns its place by *division of labor* with `/health`. The endpoint itself [deliberately refuses](35-going-live.md) slow or external probes. It is polled by the deploy script and [the proxy's load balancer](36-minimal-downtime.md), so it must stay fast, local, and side-effect-free. The watchdog is where the slow questions belong: it runs out-of-band on a timer, it can afford a five-second TCP connect to the relay, and -- the property that decides where it lives -- **it must outlive the thing it watches**. A check scheduled inside the app dies with the app; the watchdog is systemd's process, not the JVM's, which is the entire argument against the in-process scheduler a job library would have brought.

The schedule is a five-line timer (`ops/myapp-watchdog.timer`, every two minutes, enabled like any unit) -- systemd timers are this book's answer to cron, and [the next chapter](39-backup-restore.md) reuses the pattern for work with a longer horizon.

## The messenger

When the watchdog exits non-zero, its oneshot service enters *failed* state, and the unit's one interesting line fires:

```ini
OnFailure=myapp-alert@%n.service
```

`myapp-alert@` is a template whose instance name is the failing unit, and its script is a message and a `curl` -- because curl speaks SMTP, and the box therefore needs no mail daemon at all:

```bash
{
  echo "From: $SMTP_FROM"
  echo "To: $ADMIN_EMAIL"
  echo "Subject: [myapp] $unit failed on $(hostname)"
  echo
  echo "Unit $unit entered failed state at $(date -Is)."
  echo
  echo "Last journal lines:"
  journalctl -u "$unit" -n 20 --no-pager 2>/dev/null || echo "(no journal available)"
} > "$body"
,,,
curl -sS "${tls[@]}" \
  --url "smtp://$SMTP_HOST:$SMTP_PORT" \
  --mail-from "$SMTP_FROM" --mail-rcpt "$ADMIN_EMAIL" \
  "${auth[@]}" \
  -T "$body"
```

Three decisions ride in that small script. The alert goes through **the same relay, credentials, and env file the product uses** -- one channel, already required at boot, already covered by the watchdog's own canary. The body carries the **last twenty journal lines of the failing unit**, because an alert that says only "it broke" schedules a second investigation the message could have pre-empted; the 3 am operator should wake up already knowing. And a **cooldown** (one alert per unit per half hour, a timestamp in `/run`) keeps a persistent failure from converting the two-minute timer into a two-minute inbox drumbeat. The operator is awake after the first one, and an alert channel that spams is an alert channel that gets muted.

## Crash loops converge to a message

`OnFailure=` earns a second job on the long-running units. [The going-live units](35-going-live.md) carry `Restart=on-failure`, which heals blips -- and, unbounded, would also *hide* a genuine crash loop as an eternal flicker no one is told about. The app and transactor units now bound it:

```ini
OnFailure=myapp-alert@%n.service
StartLimitIntervalSec=60
StartLimitBurst=5
```

Five failed starts inside a minute and systemd stops retrying, the unit lands in *failed* state, and failed state now means *email*. The three outcomes are finally distinct, which is the design's aim: a blip restarts silently, a crash loop converges to a message with the stack trace attached, and a healthy service says nothing at all.

## The second opinion: an observer somewhere else

Everything above shares one blind spot, and naming it is what keeps the design honest: **the box cannot report the loss of the box.** And "loss of the box" is broader than power failure. Anyone who has run in a real datacenter has the list: network problems upstream of you, DNS that rots or stops resolving from half the world, the VM host that takes your guest down with it. All outside your control, all availability to your users, all invisible to every check that runs *on* the box. No amount of on-box engineering closes this. The observer has to stand somewhere else -- and *somewhere else* is the entire specification.

There are two conventional answers, and this book builds a third. A SaaS uptime pinger is a form and five minutes, and a third party holding your alerting. A monitoring platform of the Nagios lineage is the operators' classic, and it is a *platform*: a plugin ecosystem, a configuration language, a state model, a web UI, all of which is itself a service to install, upgrade, and secure, on a box you must now also operate. Both earn their keep watching *fleets*. This observer has exactly one patient, and for one patient the entire job -- probe, judge, latch, notify -- is sixty lines of bash you own completely: `ops/observer/` is the kit, self-contained by design. Rent the cheapest VPS in a *different* datacenter, ideally a different provider; copy the directory; fill in the env file; enable the timer.

Its three checks are one per failure class from that datacenter list. **DNS**: does the name resolve *from out there*, the check no on-box probe can even express. **TLS**: read the certificate's expiry and warn at fourteen days out, because a silently broken ACME renewal has weeks of runway that only a calendar-watcher converts into an alert instead of an outage. **The full public path**: fetch `/health` exactly as a visitor would -- DNS, TLS, proxy, pool, app, both databases -- and assert the *body* as well as the status, because a hijacked domain or a misrouted vhost can answer `200` with somebody else's page.

The judgment layer is where rolling your own pays in behavior no pinger's checkbox offers. Failures must be *consecutive* -- two strikes at one-minute spacing -- so [a pair-deploy window](36-minimal-downtime.md) or a transient route flap never wakes anyone. And the alert is a **latch, not a stream**: one email when the second strike lands, one email when health returns, exactly two per incident. The recovery message is half the value. An operator driving to a laptop deserves to know the patient got up. One independence rule is the crux: the observer's env file names its *own* mail path, a different relay or at least a different account, because an observer that alerts through the relay it is observing goes silent with its patient.

The two watchers now compose into full coverage with no overlap: the observer answers "is the site reachable from a world that doesn't share your failures," the on-box watchdog answers everything the world cannot see -- the relay canary, the disk, the crash-looped unit. Neither is bought; both are drilled; and the day the fleet outgrows them is [the audit's](41-beyond-one-box.md) Nagios-shaped threshold, entered with sixty lines of understanding about what such a platform is actually for.

## Proof

The whole apparatus was drilled against the live stack, with a real SMTP server standing in as the relay. The healthy pass: all three checks green, exit 0, no mail. Then each failure, provoked: every instance made unreachable --

```
watchdog: no instance answers /health on 3000/3001/3002
```

-- the relay stopped: `watchdog: SMTP relay localhost:2525 does not accept connections`. And the messenger end-to-end: the alert script delivered through actual SMTP, the capture showing `Subject: [myapp] myapp.service failed on <host>` with the journal tail in the body -- then invoked again immediately and *correctly refusing*, the cooldown answering with a silent exit 0 and no second message.

The observer drilled the same way, against a TLS front and a live SMTP capture. Healthy: silent. Front stopped: strike one logged and quiet, strike two delivered the single `ALERT: ... failing from outside`; front restored: one `RESOLVED: ... healthy again` and the latch re-armed. A five-day certificate produced `TLS: certificate expires in 4d — renewal broken?`, and an unresolvable name produced `DNS: ... does not resolve from the observer`. Four failure classes, four named messages, two emails per incident.

## Trade-offs & limitations, in one place

- **Email is a minutes channel, not a seconds one.** Delivery latency plus a two-minute probe interval means detection-to-operator can be five minutes. For a one-operator SaaS this is the honest tier; escalation policies, acknowledgment, and paging are what the platforms sell, and the day two people share on-call is the day to buy one.
- **The watchdog checks liveness, not correctness.** An instance serving 500s to every request still answers `/health`; wiring [the metrics endpoint's](37-runtime-legibility.md) error-class counters into a watchdog threshold is the natural next check, left until a real incident says which threshold matters.
- **The cooldown is per-unit and blunt.** Thirty silent minutes of a *different* failure of the same unit is the price of an inbox that stays readable. The stamp lives in `/run`, so a reboot resets it -- which is correct, since a reboot is news.
- **`OnFailure` hears unit-level death only.** A wedged-but-running JVM (answering health, serving nothing well) trips neither restart nor alert. [`ExitOnOutOfMemoryError`](35-going-live.md) converts the most common wedge into a death this chapter *does* hear; the rest is what the metrics are for.
- **The watcher is unwatched.** The observer box is deliberately boring (one timer, no inbound surface), but nothing alerts when *it* dies; its silence is indistinguishable from good news. The honest mitigations, in rising order of ceremony: a calendar note to glance at `systemctl list-timers` on it monthly; a periodic heartbeat email ("still watching") whose *absence* the operator notices; and full symmetric cross-observation, which is the first rung of the fleet ladder [the audit prices](41-beyond-one-box.md). This book takes the first and names the others.

## What the call reports

The box now speaks: crash loops converge to a message, the invisible outage has a canary, and the failure the box cannot speak about is watched from somewhere else. What none of this yet survives is the failure with no alert at all: the disk that dies rather than fills, the `rm` with the wrong argument, the datacenter that keeps the box. For those, the operator needs yesterday's data somewhere else entirely, and proof -- rehearsed, not assumed -- that it comes back. That is the next chapter.
