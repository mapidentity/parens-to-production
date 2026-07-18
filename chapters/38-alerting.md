# The System Calls for Help: Alerting Without a Pager Service

[The previous chapter](37-runtime-legibility.md) ended on its own limitation: a box that can answer every question and volunteers nothing. The audit that started these chapters found the canonical version of the problem, and it is worth restating because it justifies everything below: **this application's worst outage is invisible.** Sign-in *is* email; if the relay dies, the site stays green -- every page renders, `/health` passes, and [the don't-reveal design](25-auth-email-flow.md) shows each failed visitor a cheerful "Check your email." No user can tell you, because telling you requires logging in. A system like that does not need a dashboard. It needs to *speak*.

"Alerting" usually names a platform -- PagerDuty, OpsGenie, a Grafana with rules. A fleet earns those. One box has, already installed and already configured, the only two ingredients alerting is made of: a scheduler with failure semantics (systemd) and a message channel the operator reads (the SMTP relay [the config refuses to boot without](35-going-live.md)). This chapter wires those together and buys exactly one thing at the end.

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

Each check earns its place by *division of labor* with `/health`. The endpoint itself [deliberately refuses](35-going-live.md) slow or external probes -- it is polled by the deploy script and [the proxy's load balancer](36-minimal-downtime.md), so it must stay fast, local, and side-effect-free. The watchdog is where the slow questions belong: it runs out-of-band on a timer, it can afford a five-second TCP connect to the relay, and -- the property that decides where it lives -- **it must outlive the thing it watches**. A check scheduled inside the app dies with the app; the watchdog is systemd's process, not the JVM's, which is the entire argument against the in-process scheduler a job library would have brought.

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

Three decisions ride in that small script. The alert goes through **the same relay, credentials, and env file the product uses** -- one channel, already required at boot, already covered by the watchdog's own canary. The body carries the **last twenty journal lines of the failing unit**, because an alert that says only "it broke" schedules a second investigation the message could have pre-empted; the 3 am operator should wake up already knowing. And a **cooldown** (one alert per unit per half hour, a timestamp in `/run`) keeps a persistent failure from converting the two-minute timer into a two-minute inbox drumbeat -- the operator is awake after the first one, and an alert channel that spams is an alert channel that gets muted.

## Crash loops converge to a message

`OnFailure=` earns a second job on the long-running units. [The going-live units](35-going-live.md) carry `Restart=on-failure`, which heals blips -- and, unbounded, would also *hide* a genuine crash loop as an eternal flicker no one is told about. The app and transactor units now bound it:

```ini
OnFailure=myapp-alert@%n.service
StartLimitIntervalSec=60
StartLimitBurst=5
```

Five failed starts inside a minute and systemd stops retrying, the unit lands in *failed* state, and failed state now means *email*. The three outcomes are finally distinct, which is the whole design: a blip restarts silently, a crash loop converges to a message with the stack trace attached, and a healthy service says nothing at all.

## The second opinion

Everything above shares one blind spot, and naming it precisely is what keeps the design honest: **the box cannot report the loss of the box.** Power, kernel panic, network partition, or the failure mode with a sense of irony -- the relay is down, which the watchdog detects and then cannot email anybody about. No amount of on-box engineering closes this; the observer has to stand somewhere else.

This is the one place the chapter buys instead of builds, and deliberately the cheapest possible purchase: an external uptime service -- pick any; the free tiers all suffice for one URL -- probing `https://myapp.example.com/health` from the outside every minute and emailing on failure. It exercises the *entire* path (DNS, TLS, proxy, pool, app, both databases), it lives on infrastructure whose failures are uncorrelated with the box's, and it costs a form and five minutes. The two watchers compose into full coverage with no overlap: the external probe answers "is the site reachable from the world," the on-box watchdog answers everything the world cannot see -- the relay canary, the disk, the crash-looped unit. Buying the first and building the second is not a compromise between philosophies; it is each tool standing exactly where only it can stand.

## Proof

The whole apparatus was drilled against the live stack, with a real SMTP server standing in as the relay. The healthy pass: all three checks green, exit 0, no mail. Then each failure, provoked: every instance made unreachable --

```
watchdog: no instance answers /health on 3000/3001/3002
```

-- the relay stopped: `watchdog: SMTP relay localhost:2525 does not accept connections`. And the messenger end-to-end: the alert script delivered through actual SMTP, the capture showing `Subject: [myapp] myapp.service failed on <host>` with the journal tail in the body -- then invoked again immediately and *correctly refusing*, the cooldown answering with a silent exit 0 and no second message.

## Trade-offs & limitations, in one place

- **Email is a minutes channel, not a seconds one.** Delivery latency plus a two-minute probe interval means detection-to-operator can be five minutes. For a one-operator SaaS this is the honest tier; escalation policies, acknowledgment, and paging are exactly what the platforms sell, and the day two people share on-call is the day to buy one.
- **The watchdog checks liveness, not correctness.** An instance serving 500s to every request still answers `/health`; wiring [the metrics endpoint's](37-runtime-legibility.md) error-class counters into a watchdog threshold is the natural next check, deliberately left until a real incident says which threshold matters.
- **The cooldown is per-unit and blunt.** Thirty silent minutes of a *different* failure of the same unit is the price of an inbox that stays readable. The stamp lives in `/run`, so a reboot resets it -- which is correct, since a reboot is news.
- **`OnFailure` hears unit-level death only.** A wedged-but-running JVM -- answering health, serving nothing well -- trips neither restart nor alert. [`ExitOnOutOfMemoryError`](35-going-live.md) converts the most common wedge into a death this chapter *does* hear; the rest is what the metrics are for.
- **The external monitor is a dependency with your uptime in its hands.** It sees only what the internet sees, and its alert address had better not be an inbox behind the relay it is monitoring. Route it to a second channel; redundancy that shares a failure mode is theater.

## What the call reports

The box now speaks: crash loops converge to a message, the invisible outage has a canary, and the failure the box cannot speak about is watched from somewhere else. What none of this yet survives is the failure with no alert at all -- the disk that dies rather than fills, the `rm` with the wrong argument, the datacenter that keeps the box. For those, the operator needs yesterday's data somewhere else entirely, and proof -- rehearsed, not assumed -- that it comes back. That is the next chapter.
