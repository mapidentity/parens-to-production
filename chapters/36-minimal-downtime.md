# Updates with Minimal Downtime: Two of the Jar

[The previous chapter](35-going-live.md) ended on an observation: the deploy window -- softened by [the drain hook and the maintenance page](34-ci-cd.md), fully parameterized by the box -- still opens on *every single deploy*, because one box runs one jar at a time. That is an assumption, not a necessity. This chapter runs two, and turns a deploy from "stop, swap, start" into a handoff a visitor cannot perceive. It also spends a whole section on what the trick costs, because minimal-downtime updates are the kind of feature that is all upside in the demo and all fine print in the third month.

## The stack was already built for this

Running two instances of an application behind one proxy is trivial or terrifying depending entirely on where its state lives, so inventory this application's state honestly. Sessions? [Sealed inside the cookie](25-auth-email-flow.md) -- any instance holding the same `SESSION_KEY` can decode any visitor's session, so there is no session store to share and no sticky routing to configure. In-process caches? The [asset manifest](29-asset-pipeline.md), loaded identically at each boot; nothing else. The database? This is the part most stacks trip on and [the Peer model](08-datomic.md) was practically designed for: *peers are read-parallel by construction*. Two JVMs each embedding a query engine, both reading the same storage, both funneling writes through the same single transactor -- that is not a clustering feature bolted on for this chapter; it is the ordinary topology of chapter 8's architecture, exercised at n=2.

One prerequisite is absolute, and it is why this chapter comes *after* going live: the pair only makes sense against [the shared PostgreSQL storage](35-going-live.md). Two `datomic:mem` processes are two different universes; two peers on one `datomic:sql` URI are one application twice. The dev loop cannot rehearse this chapter, and now you know exactly which piece it is missing.

The one place the rate limiter's in-process bucket and its [single-instance caveat](25-auth-email-flow.md) get real: a pair *briefly* halves the effective limit during overlap. For a deploy measured in seconds this is noise; it is the first line of the fine print, and the shared-store fix stays where the operations volume owns it.

## An instance is a port

The `:prod` profile learns to take its port from the environment -- per-instance, with the single-instance default intact:

```clojure
{:server {;; In :prod the port is per-instance (MYAPP_PORT, set by the systemd
          ;; unit) so an update pair can run two builds side by side; unset,
          ;; it stays 3000 for the single-instance topology.
          :port #profile {:dev 3000
                          :prod #long #or [#env "MYAPP_PORT" "3000"]}
          ,,,}}
```

and the systemd unit becomes a *template*, `ops/myapp@.service`, where the instance name **is** the port:

```ini
[Service]
,,,
Environment=MYAPP_PORT=%i
EnvironmentFile=/etc/myapp/env
# One jar file PER INSTANCE, so the running instance's jar is never
# replaced underneath a live JVM — deploy-pair.sh copies the new build to
# the idle instance's path before starting it.
ExecStart=/usr/bin/java -Xmx1g -XX:+ExitOnOutOfMemoryError -jar /opt/myapp/myapp-%i.jar
,,,
```

`systemctl start myapp@3001` is a complete sentence. The per-instance jar path is the quiet load-bearing line: a JVM reads its jar lazily, so overwriting the file under a running process is a lottery ticket, and giving each instance its own copy retires the whole class of bug for the price of one 40MB file.

## The proxy stops choosing

In [the going-live Caddyfile](35-going-live.md), the `handle` block proxied one address. It becomes a pool:

```
	handle {
		# One pool serves both topologies: the single-instance unit
		# (myapp.service) binds 3000; the update pair (myapp@.service)
		# binds 3001/3002. Health checks steer traffic to whichever
		# instances are alive — during a pair deploy, briefly both.
		reverse_proxy 127.0.0.1:3000 127.0.0.1:3001 127.0.0.1:3002 {
			health_uri /health
			health_interval 2s
			fail_duration 10s
			lb_try_duration 4s
		}
	}
```

Four settings, one idea: Caddy actively polls each upstream's [`/health`](35-going-live.md) -- the endpoint that proves both database connections, now earning its keep a third time (the deploy script and the operator were the first two) -- marks the dead ones down, and `lb_try_duration` retries a refused dial against the next upstream instead of surfacing it. The pool deliberately lists all three ports so the same committed file serves the single-instance topology and the pair: whichever units are running are the pool, and "switching topologies" is starting different units, not editing proxy config. The permanently dead entries cost a local dial every two seconds -- a price paid in nothing.

## The deploy becomes a handoff

`ops/deploy-pair.sh` replaces stop-swap-start with: find the idle port, give it the new build, gate it, then retire the old --

```bash
# Whichever port is serving stays up; the other is idle and gets the build.
live="" idle=""
for p in "${ports[@]}"; do
  if curl -fsS "http://127.0.0.1:$p/health" >/dev/null 2>&1; then live=$p; else idle=$p; fi
done
,,,
cp -f "$new_jar" "/opt/myapp/myapp-$idle.jar"
sudo systemctl start "myapp@$idle"      # the old instance keeps serving meanwhile

# Health-gate the newcomer directly on its own port, not through the proxy.
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:$idle/health" >/dev/null 2>&1; then
    if [ -n "$live" ]; then
      # SIGTERM → the shutdown hook drains in-flight requests, then the
      # port closes and Caddy's health checks steer everything to the
      # new instance. Old jar stays on disk as the rollback.
      sudo systemctl stop "myapp@$live"
    fi
    ,,,
```

Compare the failure modes with [chapter 34's script](34-ci-cd.md), because they have quietly inverted. There, an unhealthy new build meant *rollback under pressure*: the site was already down, and the script raced to restore the previous jar. Here, an unhealthy new build means *nothing happened*: the old instance never stopped serving, the script stops the broken newcomer and exits non-zero, and the failed deploy is a log line instead of an outage. Rollback after a *healthy-but-wrong* deploy is equally calm: the old jar is still sitting at the other port's path, one `systemctl start` away. The health poll also gates the other direction -- the old instance is not stopped until the new one has *proven* it serves, so there is no instant with zero live instances.

## One build, one validator

Running two processes surfaces a bug this book planted in [the conditional-GET chapter](31-conditional-get.md) -- and flagged there as a forward reference the moment it was written. The page validator folded in a `boot-token`: a UUID minted per process, folding "the rendering code" into the ETag. Per *process*. Two instances of the *same build* would mint two tokens, and every revalidation that landed on the other instance would miss: no staleness, ever (the failure direction held), but cache churn that silently taxes exactly the traffic the feature exists to serve, with the browser flapping between two validators for one unchanged page.

The fix states what the token always meant. "This build of the rendering code" is not a process property; it is a *build* property, and the build system knows it precisely:

```clojure
  ;; Bake the build identity into the jar. The page validator
  ;; (myapp.web.routes/build-token) folds this into every anonymous-page
  ;; ETag: every process of one build agrees on it (an instance pair must
  ;; not flap validators), and every new build changes it (a deploy still
  ;; invalidates the world). The git sha IS the build identity — same
  ;; input, same token; no clock involved.
  (spit (io/file class-dir "build-id")
    (str/trim (b/git-process {:git-args "rev-parse --short HEAD"})))
```

and the token reads it, falling back to the old behavior exactly where the old behavior was right:

```clojure
(defonce ^:private build-token
  ;; The rendering code's identity in the page validator. A production jar
  ;; carries a `build-id` resource baked at uberjar time (the git sha), so
  ;; every process of one build shares one token — two instances behind one
  ;; proxy must agree or ETags flap between them — while a deploy still
  ;; invalidates every cached page. Dev has no jar and no pair, so it falls
  ;; back to a per-process UUID, keeping restart-invalidates semantics.
  ;; An identity, not a timestamp, either way: the swappable clock
  ;; (myapp.time) must stay free to lie in tests.
  (or
    (some-> (io/resource "build-id")
            slurp
            str/trim
            not-empty)
    (str (random-uuid))))
```

There is a bonus hiding in the fix: in production, a *restart without a deploy* no longer invalidates anyone's cache -- same build, same data, same pages, and now, correctly, same validator. The per-process UUID was over-invalidating on restarts all along; it took a second process to make the imprecision visible.

## Proof

Everything above ran, against the real artifacts. The pipeline's own `clojure -T:build uber` produced the jar (build id `fce9306`); two copies of it ran as the pair -- `MYAPP_PORT=3001` and `3002`, full `:prod` profile -- against [the previous chapter's](35-going-live.md) PostgreSQL-backed storage and transactor, behind a real Caddy running the committed pool. Both instances, asked directly:

```
A: ETag: W/"1035-en-fce9306"
B: ETag: W/"1035-en-fce9306"
```

One validator across the pair: shared basis-t from shared storage, shared build id from the jar. (The basis had ticked from 1034 to 1035 when instance B booted -- its idempotent schema transaction asserting nothing, recorded like any other, exactly as [chapter 8 promised](08-datomic.md). Even the no-op is on the log.)

Then the handoff, under load: a client hammering the proxy every 50 milliseconds while instance B started and instance A was sent SIGTERM --

```
requests: 280, 200s: 280, non-200s: 0
```

Two hundred eighty requests spanning the entire switchover -- instance starting, health checks converging, the old instance draining and closing its port -- and not one of them failed or saw the maintenance page. The [restart-window section's](34-ci-cd.md) branded 503 is still committed, and now describes only the single-instance topology and genuine whole-box failure: for a pair deploy it has become the page nobody sees.

## Trade-offs & limitations, in one place

The demo is unambiguous; here is the fine print, which is the half of the chapter you were promised.

- **The overlap window is a compatibility contract, and you sign it on every deploy.** For a few seconds, the old and new build serve simultaneously against one database. That is only safe while: the schema change is *additive* ([the going-live migration story](35-going-live.md) gains its constraint -- attributes are added, never repurposed, and the old build must tolerate the new attribute's presence, which Datomic's accretive schema culture makes the default rather than the discipline); the *session shape* is stable, because a cookie sealed by the old build will be opened by the new one seconds later (renaming a session key is a breaking deploy -- ship it as read-both, then retire); and [island endpoints](22-live-preview.md) tolerate one version of skew, since a page rendered by the old build may POST to the new. None of this machinery enforces the contract. It is a *review discipline*, and the honest name for the cost is: every deploy now has a compatibility dimension that stop-the-world deploys never had.
- **Static assets must accumulate, not replace.** A page rendered by the old build references old content-hashed URLs; ship the new static tree *additively* (no `--delete`) or the overlap serves pages whose assets 404. Hashed filenames make coexistence free; garbage-collecting last month's hashes becomes a small chore the operations volume inherits.
- **Two peers cost two heaps.** Each instance carries its own object cache ([sized by `-Xmx`](35-going-live.md), [valued by measurement](32-server-path-measured.md)) -- during every deploy the box must afford both, and the new instance boots with its cache cold, so the first requests it serves are its slowest. Budget memory for two, or accept that deploys briefly double the box's footprint.
- **This is not high availability.** Same box, same transactor, same PostgreSQL: the pair removes the *deploy* window and nothing else. A transactor restart still pauses writes ([the peer rides it out](08-datomic.md)); a box failure still takes everything. If this chapter's machinery has taught the deploy to be invisible, the visible outages that remain are precisely the operations volume's opening chapters.
- **You may not need it.** The honest baseline is [chapter 34's softened window](34-ci-cd.md): a few seconds of branded, self-healing maintenance page, at zero additional moving parts. The pair adds a template unit, a port scheme, idle-detection, and the compatibility contract above -- machinery that must be owned by whoever deploys. Adopt it when deploy frequency times audience makes seconds-per-deploy a real cost; before that, the simpler script is the better engineering.

## The window, closed

Chapter 34 named the downtime, its restart-window section softened it, going live parameterized it, and this chapter closes it -- to the width of a TCP handoff, on the same single box, using two copies of a jar and five lines of proxy config. What remains standing is exactly what this volume has always said it would leave standing: one box, one transactor, one operator, everything on it understood all the way down. The afterword has the rest.
