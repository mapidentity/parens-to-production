# Legible at Runtime: Metrics, Access Logs, and the Errors That Happen Elsewhere

[The machine-legibility chapter](30-machine-legibility.md) taught the application to introduce its *pages* to machines. This chapter does the same for the *process*. Because here is the operator's position after [going live](35-going-live.md): the box runs, the log carries warnings and errors, `/health` answers yes-or-no -- and every question with a number in it is unanswerable. How much heap is the peer's cache actually using? What does a render cost against real storage, not the in-memory floor [the measurement chapter](32-server-path-measured.md) was honest about? What hit the box in the five minutes before the incident? Did some visitor's browser spend the evening throwing exceptions we never saw?

Four questions, four small mechanisms -- an access log, a metrics endpoint, the peer's own telemetry, and a client-error beacon -- none of them a system, all of them committed. The theme is the book's usual one, inverted into operations: the *formats* are trivial and worth owning; the *platforms* (a Prometheus server, a Grafana, a Sentry) are real infrastructure a single box does not need yet, and the mechanisms below are the contracts those platforms consume the day you do.

## What hit the box

The first question costs one word. Caddy logs nothing by default -- a fact worth a raised eyebrow, since it means [the going-live box](35-going-live.md) initially could not answer "what requests arrived around the incident?" at all. In `ops/Caddyfile`:

```caddyfile
	# Access log, to stderr and therefore journald: the answer to "what hit
	# the box around the incident?" One JSON line per request; retention is
	# journald's, like every other log on this machine.
	log
```

One JSON line per request (method, URI, status, duration, remote address) into the same journald every other log on the machine already flows to. The proxy is the right place for it, not the app: Caddy sees *everything*, including static-asset traffic the JVM never touches and, crucially, the requests that arrive while the app is down -- the exact traffic an app-side request log goes blind to at the exact moment you are reconstructing an outage. The app's log stays what it became in [going live](35-going-live.md): warnings and errors, signal only.

## The metrics endpoint is a hundred and sixty lines

The metrics *format* war is over and the text won: a Prometheus exposition is `name{label="value"} 1.0`, one sample per line. This book does not buy a client library for that: the whole of `myapp.web.metrics` is a `StringBuilder` and three sources. The JVM's, first: heap, GC, threads, straight from the `ManagementFactory` MXBeans. The request path's, second, folded in by one middleware sitting just inside [the panic belt](25-auth-email-flow.md):

```clojure
(defn wrap-metrics
  "Fold every served response into the request counters.

  Status class and duration only — no per-path labels, because label
  cardinality is a budget and URLs with ids in them would spend it all.
  Monotonic nanoTime, not the app clock: a duration is an interval, and
  the swappable clock (myapp.time) must stay free to lie in tests
  without bending the metrics."
  [handler]
  (fn [request]
    (let [t0 (System/nanoTime)
          response (handler request)]
      (metrics/record-request! (:status response) (- (System/nanoTime) t0))
      response)))
```

The docstring's two refusals are the design. *No per-path labels*: every distinct label value is a stored time series in whatever scrapes this, and `/recipes/:id` URLs would mint one per recipe -- status class and duration answer "is it healthy and is it fast," which is what a fleet-of-one needs, without an unbounded cardinality bill. And *monotonic time*: [the clock chapter's](07-time-clock.md) swappable `time/now` is for facts with dates on them; a duration is an interval, and measuring it with a clock that tests deliberately bend would be wrong twice.

The endpoint itself is a route with an audience of one:

```clojure
["/metrics"
 {:get (fn [request]
         ;; Operator surface. The real wall is the proxy: ops/Caddyfile
         ;; answers 404 for /metrics and never forwards it, so from the
         ;; internet the route does not exist. This loopback check is
         ;; the dev-mode belt (dev binds 0.0.0.0 for the compose
         ;; network); in prod every request arrives via the proxy from
         ;; loopback anyway, which is why the proxy must be the wall.
         ,,,)}]
```

Read that comment closely, because it generalizes: *on a one-box topology where the proxy shares the loopback, the app cannot distinguish inside from outside*: every request it sees comes from `127.0.0.1`. Any operator-only surface must therefore be walled at the proxy (`handle /metrics { respond 404 }` in `ops/Caddyfile` -- verified: through the proxy the endpoint 404s while the app happily serves it on the box), and the in-app check is honest only as the dev-mode belt. A gate that *looks* like defense in depth but whose inner layer cannot actually discriminate is worth calling by its real name.

## The peer, reporting

The third source is the one this book owes a debt to. [The measurement chapter](32-server-path-measured.md) benchmarked renders against `datomic:mem` and named the unpaid bill: *the peer-cache behavior that determines real-storage performance* was deferred to these chapters. The peer itself offers the payment mechanism: hand it a function name and it delivers its internal telemetry roughly once a minute. The wiring is one JVM flag in [the systemd unit](35-going-live.md) (`-Ddatomic.metricsCallback=myapp.web.metrics/datomic-callback!`), placed in the *unit*, not the code, so a dev REPL never grows a callback it does not need. The receiving side is an atom and a deliberately generic renderer:

```clojure
(defn- emit-datomic
  "Emit the peer's metrics report, generically.
  Numbers become gauges; the peer's {:lo :hi :sum :count} rollups become
  four samples each. Generic on purpose — the report's keys vary by
  activity, and an operator wants whatever the peer said, not our
  curation of it."
  ,,,)
```

And here is what it said, live, with the whole stack assembled -- the jar from [the pipeline](34-ci-cd.md), the PostgreSQL-backed storage from [going live](35-going-live.md), a thousand page renders driven through the front door:

```
http_requests_total{class="2xx"} 1048.0
http_request_duration_seconds_sum{class="2xx"} 1.950748922
datomic_object_cache_count 41.0
datomic_storage_get_msec_hi 29.0
datomic_storage_get_msec_sum 31.0
datomic_storage_get_msec_count 41.0
```

A later pass folded in the gauges that make a *silent* failure legible, because a number nobody reads is not observability. `email_send_total{outcome="fail"}` turns [the invisible login outage](38-alerting.md) into a line that climbs; `presence_channels` and `process_open_fds` turn [the reaper's leaked-socket failure mode](45-live-presence.md) into a slope you can watch approach the FD limit while the heap stays flat; and `build_info{build_id="…"}` (also on `/health`) lets you ask a box which build it is actually running. Each exists because [the resilience capstone](46-watching-the-watchers.md) found a failure that was real, ongoing, and emitting no signal at all -- and a metric is the cheapest way to make trouble visible before it is fatal.

This is the deferred measurement, paid in full, and it is worth reading like a sentence: **one thousand and forty-eight renders cost forty-one trips to PostgreSQL.** The object cache absorbed everything else. The trips that did happen cost 31 milliseconds *in total*, but the `_hi` sample is the honest tail: a single cold segment fetch took 29 ms, which is the cost `datomic:mem` structurally could not show. And the mean render through the full stack (middleware, real storage, the lot) lands at 1.86 ms against [chapter 32's](32-server-path-measured.md) 1.03 ms in-memory floor at the handler seam. The floor was real; the tax above it is now measured instead of guessed; and the shape of the answer -- *storage cost is a cold-start phenomenon that the cache turns into a rounding error* -- is exactly the one [the Datomic chapter](08-datomic.md) promised and could never demonstrate from a REPL.

## The errors that happen elsewhere

The server's failure paths all land in the operator's log -- [`wrap-errors`, the panic belt](25-auth-email-flow.md), [the CSP sink](29-asset-pipeline.md). One class of failure still died invisibly: an exception inside [an island](20-progressive-enhancement.md), in a visitor's browser, at the console nobody operating the site will ever open. The fix is the smallest module in the repo, and it loads *first* -- module execution follows document order, so its listeners attach before any other island has run a line:

```javascript
const LIMIT = 5; // per page load — an error loop must not become a beacon loop
,,,
  // sendBeacon survives page unload and never blocks the page; fetch with
  // keepalive is the fallback where beacons are unavailable.
  if (!(navigator.sendBeacon && navigator.sendBeacon('/client-error', body))) {
    fetch('/client-error', { method: 'POST', body, keepalive: true }).catch(() => {});
  }
```

`window.addEventListener('error')` and `'unhandledrejection'`, truncated fields, five reports per page load and not one more. A page stuck in an error loop must not become a beacon loop. The receiving endpoint is the CSP sink's sibling and finally makes both honest: `csp-report`'s own docstring had said "in production you would sample/rate-limit this" since [the asset-pipeline chapter](29-asset-pipeline.md) wrote it, and *would* is now *does*. Both sinks share one per-source budget through [the same rate limiter the login path uses](25-auth-email-flow.md), and both truncate before logging, because a public report sink is attacker-shaped input aimed at the one file the operator greps. The reports are logged, never rendered, never stored: a beacon, not an API.

## Proof

The smoke tests pin the walls: `/metrics` serves Prometheus text to loopback and does not exist for anyone else; the client-error sink answers 204 through its budget. The e2e test drives the real module in a real browser -- dispatch a synthetic `ErrorEvent`, watch the beacon POST arrive at `/client-error` with the message intact. And the peer telemetry above *is* the proof of the third source: it was scraped from a live instance rather than composed for the page.

## Trade-offs & limitations, in one place

- **The endpoint is the contract; nothing ships to read it.** No Prometheus server, no dashboards -- on one box they are more moving parts than the questions they answer, and [the next chapter's](38-alerting.md) watchdog reads the same numbers with `curl`. The day you outgrow that, any scraper on the box consumes this endpoint unchanged.
- **Metrics are process-local.** [The instance pair](36-minimal-downtime.md) means two endpoints; scrape both. Counters reset on restart -- which Prometheus-style consumers expect (`rate()` is reset-aware), but a bare `curl` reader should know.
- **The peer reports on its own clock** -- roughly a minute between ticks, so the Datomic gauges are a trailing indicator, not a live one. The *transactor's* telemetry, meanwhile, remains unwired: it supports the same callback property in its own properties file, one line away when its behavior becomes the question.
- **Sums and counts, no histograms.** Mean latency per status class is as far as this goes by design: percentiles need buckets, buckets need choices, and [ch.32's](32-server-path-measured.md) offline distribution already characterizes the shape. Add buckets when a live percentile would change a decision.
- **Client reports are hostile input, permanently.** Rate-limited, truncated, logged, never displayed. The five-per-page client cap plus the per-source budget bound the blast radius; nothing bounds the *lying* -- a beacon says what its sender wants, which is why it feeds an operator's grep and not a product surface.

## Numbers, unwatched

The box now answers every quantitative question an operator can ask it -- and answers them to whoever remembers to look. At 3 am, nobody does. The gap between *legible* and *loud* is the next chapter's whole subject: the numbers exist; now something has to call for help.
