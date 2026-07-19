# Watching the Watchers: When the Safety Net Needs Its Own

This book has spent nine chapters building a safety net — [health checks](35-going-live.md), a [watchdog](38-alerting.md), [backups](39-backup-restore.md), [metrics](37-runtime-legibility.md), [alerting](38-alerting.md), a [reaper](45-live-presence.md), a [runbook](../ops/RUNBOOK.md). Each was proven the same way: it worked *once*, in isolation, at the moment it was written. The backup restored in [the drill](39-backup-restore.md). The alert fired in the test. The health check went green. And then the book moved on, quietly assuming each mechanism would keep working forever, unwatched.

That assumption is the one bug this chapter is about, because it is the bug with the worst blast radius: **the safety machinery is the one system in the book that nothing watches.** A resilience audit put it plainly — the whole apparatus proves each part works once and never asks what *continuously* proves it still does, what happens when it fails, or what happens when a safety net depends on the very thing it is meant to catch. This chapter closes that loop. It is short on new features and long on a single discipline: a guard is not done when it works; it is done when its own failure is caught by something else.

## The alarm that routes through the fire

Start with the sharpest instance, because it is almost funny once you see it. This application's [designated worst outage](38-alerting.md) is a dead mail relay: sign-in *is* email, so a broken relay means nobody can log in, while every page stays green and, by the [don't-reveal design](25-auth-email-flow.md), no user can even tell you. That is the event the alerting spine exists to page you about.

And the alert was an email. Both `myapp-alert.sh` and `myapp-notify.sh` delivered through `curl smtp://…` — the same relay. The one outage you most need to hear about was the one outage that silenced its own alarm.

The fix is not a bigger relay; it is a *second, independent* path, because no channel can announce its own death. Two of them:

```bash
# Out-of-band FIRST — an HTTPS webhook (ntfy, Slack, a push service) that
# does NOT touch the relay. Then the email, best-effort (|| true) so a dead
# relay can never crash the alerter and leave nothing.
[ -n "${ALERT_WEBHOOK_URL:-}" ] && curl -sS -m 10 -X POST "$ALERT_WEBHOOK_URL" ... || true
```

The webhook is the *active* out-of-band push. But there is a subtler, stronger signal — the *absence* of one. The [watchdog](38-alerting.md) already checks the box every two minutes; on success it now pings an external dead-man's-switch:

```bash
# All checks passed. Ping the external monitor so that SILENCE — box gone,
# watchdog broken, or any check failing — is itself an alarm, raised OFF the
# box on a path that does not depend on the box's own relay.
[ -n "${HEARTBEAT_URL:-}" ] && curl -fsS -m 10 "$HEARTBEAT_URL" >/dev/null 2>&1 || true
```

This inverts the dependency. An active alert has to travel *from* the failing box *through* some channel to reach you; if the box is gone or the channel is the thing that broke, it never arrives, and silence reads identically to health. A dead-man's-switch makes silence the alarm: a free external monitor (a hosted cron-check service, or a second cheap box) expects a ping every few minutes and pages *you* when the pings stop. The box no longer has to be alive, or have a working relay, to raise the alarm about being neither. It is the one signal that survives the outage it reports.

## Schrödinger's backup

The [backup chapter](39-backup-restore.md) ended on a drill: a human restored the archive into scratch storage and watched `history-intact: true` scroll by. It was the right drill. It also happened exactly once.

`datomic backup-db` exits `0` on a backup that is corrupt, empty, or whose incremental chain silently stopped advancing three weeks ago. Nothing re-checks. So the backup enters a superposition — restorable or not, and you do not get to collapse it until the night you need it, which is the worst possible moment to learn the answer. "We have no backups" is a bad state; "we *think* we have backups" is worse, and a drill you ran once and a green exit code are exactly how you get there.

Two changes make the backup a *watched* system:

- **Re-verify it, on a timer.** `myapp-backup-verify.timer` runs the drill weekly — restore the newest archive into throwaway storage, then prove it is usable, not merely present: entities are there *and* `d/history` is populated, because the [time axis is the thing this product is about](39-backup-restore.md) and a restore that loses it is a restore that lost everything that matters. Any failure pages on both channels. The drill is no longer something a human remembers; a timer proves it.
- **Freshness is a canary.** The watchdog now fails if the newest backup is older than 25 hours, or if the backup directory is empty. A backup that stops running is now a two-minute alarm, not a silent three-week gap.

And one placement fix that the [chapter's own opening](39-backup-restore.md) demanded and the config violated: the default target, `file:/var/backups/myapp`, is *on the box*. It shares the exact failure domain the chapter opens on — the dead disk, the wrong `rm`, the box that never comes back. A backup that can only die with the thing it protects is not a backup. `backup-db` speaks `s3://` natively, so production sets `BACKUP_URI=s3://…` and the copy that matters lives somewhere the box's disaster cannot reach.

## The blind spots the watchers share

A health check is a narrow instrument: it answers one question, and the failures it does *not* ask about are invisible to it because it is green. Three of those live here.

The **wedged-but-alive JVM** (every worker blocked on a slow query, the process answering `/health` but serving nothing) trips neither `Restart=` nor `OnFailure=`. The honest move is to make its symptoms *legible* and give the operator a path to the cause, rather than pretend the health check catches it. `/metrics` now carries the tells: `email_send_total{outcome="fail"}` climbing is the [invisible email outage](38-alerting.md) made visible; `presence_channels` and `process_open_fds` climbing while the heap stays flat is [the reaper's own failure mode](45-live-presence.md): leaked sockets marching toward the FD limit, at which point *everything that needs a socket* fails at once while the heap monitor and the liveness probe both stay green. That last one is why an FD leak is so dangerous: it defeats the very automation you trust to catch trouble. The gauge that makes it observable was written [in the presence chapter](45-live-presence.md) and, until now, was never wired to `/metrics` — a guard that existed only in a docstring, which is to say did not exist. It does now.

And when the symptom is a wedge, the runbook's instinct (restart it) destroys the only evidence of *why*. So the [runbook](../ops/RUNBOOK.md) now says: capture a `jcmd Thread.print` first, read it for the pile-up, *then* restart. The [heap dump on OOM](35-going-live.md) is the same instinct for the same reason — with the same warning attached, because a heap dump is a file full of SMTP credentials and live session tokens in cleartext. The forensic artifact you add for safety is itself a secret to guard; a fix that spawns a new hazard is not a fix until you have named the hazard.

## Two of everything, in memory

The [pair deploy](36-minimal-downtime.md) runs two jars against one transactor so a release has no zero-instance moment. Datomic gives those two heads a single, consistent, durable view of the data. What it does *not* cover (and what the deploy chapter did not say) is the state that never went to Datomic: the [rate-limiter buckets](24-auth-tokens.md), the [presence registry](45-live-presence.md), the [metrics counters](37-runtime-legibility.md) are all per-process atoms, and for the deploy window there are *two* of each. Rate limits effectively double (an attacker who lands on both heads gets twice the magic-link budget); presence splits and each reaper sees only its own half; metrics under-count. None of it corrupts data, and the window is brief, so for this application it is a limitation to *name*, not a bug to fix — the [single-box wager](02-positioning.md) accepts a few minutes of soft in-memory state twice a week as the price of a durable data layer and a simple deploy. But naming it is not optional, because the one that bites (a per-IP limit that is really per-IP-per-head) is a security property, and an unstated security property is a false one. The day the window stops being acceptable is the day that state moves to Redis or the cutover becomes atomic, and [the scaling audit](41-beyond-one-box.md) already priced both.

## The remediation is the incident

The last watcher to watch is the operator. This chapter gave you a [loopback REPL](../ops/RUNBOOK.md) so the runbook's live levers (ban a user, inspect the registry, [repair a bad write](../ops/RUNBOOK.md)) actually exist to be pulled. That REPL is unauthenticated code execution against the live process. It is the most powerful tool in the book and the most dangerous, and the two are the same sentence.

So its provisioning is defensive by construction: it binds `127.0.0.1` only, reachable solely through an SSH tunnel, so the network cannot touch it and SSH auth is the gate. And its *use* carries rules the runbook states outright, because the failure mode of a recovery tool is a recovery action that becomes the incident: read the entity before you transact against it; pair on anything irreversible (a retraction, an [excision](40-excision.md), a `restore` run against the wrong URI); and remember there is no dry-run. The 3am typo against the wrong datom, the restore pointed at the live database instead of the scratch one, the `fail2ban` rule that bans a customer's whole office behind one NAT — these are not hypotheticals, they are the characteristic way that competent operators cause outages while fixing them. The tool that lets you fix anything lets you break anything, and the only guard against that is a procedure that assumes the operator is as fallible as the system.

## The wager, one more time

The through-line of every fix in this chapter is a single shift in stance. The book had been asking, of each safety mechanism, *does it work?* and answering once, at authoring time, in isolation. The question that makes a thing dependable is different: *what proves it still works, what happens when it fails, and does it depend on the thing it guards?* A backup you never re-restore, an alert that routes through the fire, a gauge that lives only in a docstring, a health check mistaken for a readiness check, a recovery tool with no procedure — each one *worked*, and each one was one bad day from being worthless.

None of the fixes was large. A dead-man's-switch is one `curl`. Re-verifying the backup is the drill you already wrote, on a timer. Wiring the gauge is four lines. The out-of-band alert is a webhook. That is the quiet lesson of designing against production failure: the expensive part is not the mechanism, it is the discipline to treat the mechanism as another system that can fail — to build the net, and then to hang a second, smaller net under *it*, watching. "Something dependable to build on" is not a stack where nothing goes wrong. It is a stack where the things that go wrong are *seen*, and where the seeing, too, is watched.
