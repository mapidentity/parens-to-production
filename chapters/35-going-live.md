# Going Live: The Box Under the Jar

[The CI/CD chapter](34-ci-cd.md) ends with an `scp` and a `systemctl restart` -- against a host this book never built. That was not an oversight so much as a series of honest sentences pointing at a box that did not exist: [the Datomic chapter](08-datomic.md) named the transactor as "a piece of production infrastructure the dev loop cannot rehearse" and stopped there; ch.34 told you the systemd unit "owns restart-on-failure, logging, and the JVM flags" and never showed it; the `:prod` profile reads ten environment variables that no chapter ever inventoried; the pipeline ships static files to `/mnt/data/static` while every Caddyfile in the book roots at `/static`. Each debt was individually defensible. Together they meant the book's deploy chapter deployed to a promise.

This chapter pays the debts. Everything it adds is committed under `ops/` -- two systemd units, the transactor config, the production Caddyfile, the environment template, the deploy script -- and the claims a repository checkout can prove were *run*, not described: at the end of this chapter, the application boots its real `:prod` profile against a real PostgreSQL-backed Datomic, is killed, boots again, and still has its data.

## The environment, refused into existence

Start with the smallest debt, because fixing it produces the inventory everything else needs. [The web-server chapter](05-web-server.md) established a doctrine for the two crypto keys: dev generates a fallback, `:prod` refuses to start without the real thing, and the refusal is a *named* message. But the doctrine stopped at the keys. Every other `#env` under `:prod` -- the database URIs, the base URL, the SMTP settings -- resolved to `nil` when unset, and each `nil` failed somewhere worse than boot:

```clojure
(def ^:private prod-required
  "Config paths that must resolve in :prod, with the env var supplying each.
  The comment on each entry is what its nil would otherwise become — none
  of them fails as legibly as a boot refusal."
  [[[:database-uri] "DATABASE_URI"] ; bare NPE inside the Peer at create-database!
   [[:analytics-database-uri] "ANALYTICS_DATABASE_URI"] ; the same NPE, analytics side
   [[:admin-email] "ADMIN_EMAIL"] ; admin gate silently locks out everyone
   [[:base-url] "BASE_URL"] ; login emails carry broken relative links
   [[:smtp :host] "SMTP_HOST"] ; login emails silently never send
   [[:smtp :from] "SMTP_FROM"]]) ; first sign-in throws building the message
```

Read the comments as the bug reports they would have become. The two worst are the quiet ones: an app booted without `SMTP_HOST` passes every health check and can never sign anyone in, because [the magic-link endpoint deliberately reveals nothing](25-auth-email-flow.md) -- the visitor sees "Check your email," the send fails into a log line, and the outage is invisible from both sides. `require-prod-config!` closes the whole class at once:

```clojure
(defn- require-prod-config!
  "Refuse to start when :prod is missing required configuration.

  Aero's #env yields nil for an unset variable, and every nil in
  `prod-required` fails somewhere worse than boot: an NPE in the Peer, a
  sign-in that silently sends nothing behind the don't-reveal confirmation
  page, an email whose link doesn't resolve. Boot is the one place they
  can all fail loudly, next to the table that explains them."
  [profile config]
  (when (= profile :prod)
    (when-let [missing (seq
                         (for [[path env-var] prod-required
                               :when (nil? (get-in config path))]
                           env-var))]
      (throw
        (ex-info
          (str (str/join ", " missing) " env var(s) are required in :prod — refusing to start.")
          {:profile profile
           :missing (vec missing)})))
    ,,,)
  config)
```

One refusal names *every* missing variable, so a fresh host converges in one round trip instead of a guessing game. And the table doubles as the operator's checklist, which is why the repo ships it in executable form twice: once in `myapp.config`, and once as `ops/env.example` -- the template for the file the box actually reads:

```
# Template for /etc/myapp/env — root:root, chmod 0600.
DATABASE_URI=datomic:sql://myapp?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic
ANALYTICS_DATABASE_URI=datomic:sql://myapp-analytics?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic
BASE_URL=https://myapp.example.com
ADMIN_EMAIL=you@example.com
# Exactly 16 bytes (AES-128):     openssl rand -hex 8
SESSION_KEY=changeme16bytes!
# At least 32 bytes (HMAC-SHA256): openssl rand -hex 32
SIGNING_KEY=changeme-with-at-least-32-bytes-of-entropy
,,,
```

That file is the box's secret store: one file, owned by root, mode `0600`, handed to exactly one process by systemd's `EnvironmentFile=`. A secrets manager is a fleet's problem; a single box's problem is file permissions, and pretending otherwise would be machinery without a threat model to justify it.

## The database's other half

`DATABASE_URI` points at `datomic:sql://` -- and [chapter 8](08-datomic.md) already told you what that implies: PostgreSQL holding the data, and a transactor, one small JVM, serializing every write. What it could not show you then is how little there is to build. Here is the *entire* schema Datomic asks of PostgreSQL:

```sql
CREATE TABLE datomic_kvs
(
 id text NOT NULL,
 rev integer,
 map text,
 val bytea,
 CONSTRAINT pk_id PRIMARY KEY (id )
);
```

Four columns. Datomic uses a SQL database as a key-value shelf for immutable storage segments -- all the structure you have spent thirty-four chapters querying (indexes, history, basis-t) lives in those `bytea` blobs and in the peer, not in tables. This is worth internalizing for two operational reasons. It is why "which SQL database" barely matters (the storage protocol asks nothing interesting of it), and it is why backing up this application is *ordinary PostgreSQL backup* -- one table, plus [the afterword's named option](afterword.md) of Datomic's own `backup-db`. The mechanics stay with the operations volume; the *what* is no longer mysterious.

Provisioning is three scripts that ship inside the Datomic Pro distribution (`bin/sql/postgres-db.sql`, `postgres-table.sql`, `postgres-user.sql`): create a `datomic` database, that table, and a `datomic` role. Change the role's stock password; it lands next to the URI in exactly two root-owned files. The transactor's own config is a dozen lines, trimmed from the distribution's sample and committed as `ops/transactor.properties`:

```
protocol=sql
host=localhost
port=4334
sql-url=jdbc:postgresql://localhost:5432/datomic
sql-user=datomic
sql-password=datomic
sql-driver-class=org.postgresql.Driver
,,,
```

Two facts about this topology do a lot of quiet work. First, the version handshake: the peer library in `deps.edn` and the transactor distribution must match (both `1.0.7491` here) -- a version-skewed pair is the classic first-boot failure, and pinning both in committed files is the whole defense. Second, *both* application databases live in this one storage. `myapp` and `myapp-analytics` are two databases to the peer but two key prefixes to the shelf, served by the same transactor -- which is the moment [the admin chapter's](28-admin-dashboard.md) "zero new infrastructure" claim for the analytics split stops being a promise and becomes a line in a properties file.

## The units, finally shown

Ch.34 asserted that systemd owns restart, logging, and the JVM flags. Here is the ownership, in full -- `ops/myapp.service`:

```ini
[Unit]
Description=MyApp — Clojure/Datomic SaaS
# The peer rides out a transactor blip on its own, but a FIRST boot on a
# fresh host needs the transactor listening before create-database! runs.
After=network-online.target myapp-transactor.service
Wants=network-online.target myapp-transactor.service

[Service]
User=myapp
Group=myapp
WorkingDirectory=/opt/myapp
Environment=MYAPP_PROFILE=prod
# Everything the :prod profile requires, in one root-owned 0600 file
# (see env.example). Boot refuses to start with any value missing.
EnvironmentFile=/etc/myapp/env
# Half the heap becomes the peer's object cache unless object-cache-max
# says otherwise — dev/bench.clj is where that cache's value was measured.
# ExitOnOutOfMemoryError turns heap exhaustion into a death Restart= can
# heal instead of a half-alive process nothing notices.
ExecStart=/usr/bin/java -Xmx1g -XX:+ExitOnOutOfMemoryError -jar /opt/myapp/myapp.jar
Restart=on-failure
RestartSec=2
# stop delivers SIGTERM; the app drains in-flight requests for up to a
# second (its shutdown hook) — 15s of patience before systemd escalates.
TimeoutStopSec=15
# This unit owns nothing outside its own directories.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full

[Install]
WantedBy=multi-user.target
```

Almost every line closes a loop opened elsewhere in the book. `After=myapp-transactor.service` exists for *first* boot only -- thereafter [the peer reconnects through blips on its own](08-datomic.md), and ordering is a courtesy, not a crutch. The `-Xmx1g` is not a superstition: the peer gives half its heap to the object cache that [the measurement chapter](32-server-path-measured.md) showed serving reads, so heap size *is* read-cache size, and raising it is a measured decision, not a reflex. `ExitOnOutOfMemoryError` pairs with `Restart=on-failure` to prefer a two-second death-and-rebirth over the far worse mode -- a heap-starved JVM that answers health checks and serves nothing well. And `TimeoutStopSec=15` is [the restart-window section's](34-ci-cd.md) drain hook, seen from the other side: the app asks for one second; systemd grants fifteen before escalating to SIGKILL; nobody ever waits on the default ninety. The transactor's unit (`ops/myapp-transactor.service`) is the same shape minus the ceremony -- `ExecStart=/opt/datomic/bin/transactor`, restart-on-failure, after PostgreSQL.

Logging is the unit's third ownership, and it is two pieces. journald captures stdout -- retention, rotation, and the 3am interface are the supervisor's, which is the entire logging runbook this box needs: `journalctl -u myapp -S -15min` for the app, `-u myapp-transactor` for the writer. What goes *into* stdout is the app's half, and it is one committed file, `resources/logback.xml`:

```xml
<appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
  ,,,
</appender>

<!-- The Datomic peer narrates its internals at DEBUG/INFO: index merges,
     fulltext bookkeeping, a metrics line every minute. Indispensable when
     debugging the peer; noise that buries the application's own WARN/ERROR
     signal the rest of the time. -->
<logger name="datomic" level="WARN"/>

<root level="INFO">
  <appender-ref ref="STDOUT"/>
</root>
```

That file earns its place with a confession: it did not exist until this chapter's audit. `logback-classic` had been on the classpath since chapter 5, and logback without configuration defaults to *everything at DEBUG* -- so the jar would have shipped narrating the peer's index merges over the one `ERROR` line that mattered, the [500-boundary's logged stack trace](25-auth-email-flow.md). A log configuration is not polish; it is the difference between a signal and a haystack, and it is eleven lines.

## The front door

The production Caddyfile (`ops/Caddyfile`) is structurally [the dev block you have been running since chapter 3](03-devcontainer.md) -- same security headers, same cache tiers, same [maintenance-page error handler](34-ci-cd.md) -- with two differences that *are* the going-live story:

```
myapp.example.com {
	encode zstd gzip
	,,,
	root * /mnt/data/static
	,,,
	handle {
		reverse_proxy 127.0.0.1:3000
	}
	,,,
}
```

The upstream is `127.0.0.1:3000`, because [the `:prod` profile binds loopback](05-web-server.md) and the proxy shares the box -- the compose hostname `myapp:3000` was always the dev topology's spelling. The root is `/mnt/data/static`, which finally closes the loop with [the pipeline's](34-ci-cd.md) `scp` target: CI ships the built tree there, Caddy serves it from there, and the two facts now live in the same repository instead of two chapters' assumptions.

And one line is missing, which is the headline: there is no `tls` directive. The devcontainer needed [a local CA, generated certificates, and three separate trust stores](03-devcontainer.md) to rehearse HTTPS; production needs *nothing*, because a real domain with public DNS gets its certificate from Let's Encrypt automatically -- issuance and renewal both, Caddy's default behavior for any site it can prove it serves. The rehearsal was the hard part. Production TLS is the absence of configuration.

## First boot, in order

The whole box, assembled once:

1. PostgreSQL, stock install. Run the three `bin/sql/postgres-*.sql` scripts; change the role password.
2. Unpack the Datomic Pro distribution (version matching `deps.edn`'s peer) at `/opt/datomic`; install `/etc/myapp/transactor.properties`; enable `myapp-transactor.service` and wait for its log line, which is literally `System started`.
3. Write `/etc/myapp/env` from `ops/env.example`, `chmod 0600`.
4. Create `/opt/myapp`, install the unit, `systemctl enable --now myapp` -- first boot runs `create-database!` for both databases and transacts the schema.
5. Caddy with `ops/Caddyfile`; DNS A record; ports 80/443 open. Certificates arrive on their own.
6. Point [the pipeline's](34-ci-cd.md) deploy step at the box. From here on, every deploy is the script you have already read.

Step 4 quietly settles a question the book has never had to answer on a durable database: migrations. Boot *always* transacts the schema, and [chapter 8 showed](08-datomic.md) that re-transacting installed attributes is an idempotent no-op -- so "the deploy carries the schema" is the entire migration mechanism, and its discipline is Datomic's own: schema grows by accretion, attributes are added and never repurposed. What is state on this box, for the day you back it up, is now a two-item list: the PostgreSQL data directory, and `/etc/myapp`. The jar and the static tree are pipeline output; the box holds nothing else worth keeping.

## Proof, from a repo checkout

Every mechanism above was run against this repository before being written down, and the sequence is repeatable from a checkout plus the two stock installs. PostgreSQL 17 and the stock scripts; transactor `1.0.7491` to `System started`; then the application booted with `MYAPP_PROFILE=prod` and the full environment -- through the real refusal gate, against the real storage:

```
$ curl -s http://127.0.0.1:3999/health
{"status":"ok","basis-t":1030,"analytics-basis-t":1000}
```

Both databases, one storage, one transactor -- the health endpoint (grown honest in this same pass: it now proves both connections rather than hard-coding `"ok"`) reads a basis point from each. The seed populated the catalog; SIGTERM drained and stopped the process; a second boot against the same URIs found eight recipes where `datomic:mem` would have found none, re-transacted the schema into a no-op, and `seed-if-empty!` declined to run. And the negative case, because a guard that is never seen firing is a guard you are taking on faith:

```
$ MYAPP_PROFILE=prod clojure -M -e "(require 'myapp.config)(myapp.config/load-config :prod)"
DATABASE_URI, SMTP_HOST env var(s) are required in :prod — refusing to start.
```

## Trade-offs & limitations, in one place

- **It is one box.** Every process named here is a single point of failure, deliberately: this chapter removes the *mystery* from the deployment, not the SPOF. [The next chapter](36-minimal-downtime.md) removes the deploy window; removing the box itself is the operations volume's horizontal-scale story.
- **The transactor's only supervisor is `Restart=on-failure`.** No metrics, no alerting, no capacity signal -- [ch.32's flagged question](32-server-path-measured.md) of peer-cache behavior under real storage remains open and measured-elsewhere. Systemd keeps it alive; nothing yet tells you it is unwell.
- **Storage credentials sit in two `0600` files.** Argued above as the single-box answer; the moment there are two boxes, the argument expires and a secrets manager stops being machinery.
- **`/health` proves reads, not writes.** Its own comment says so: there is no side-effect-free probe for the transactor's liveness, so a health-checked box can still be write-dead. The deploy script's poll inherits this blind spot knowingly.
- **PostgreSQL is stock.** No tuning, no replication, no vacuum discipline -- defensible precisely because Datomic asks so little of it, and owed a real treatment alongside backup/restore in the operations volume.
- **ACME is asserted, not proven.** Certificate issuance needs public DNS and reachable ports -- the one claim in this chapter a container demonstrably cannot verify. It is also Caddy's most-exercised default; the risk is honest but small.

## The sentence ch.34 could not finish

"Stop, swap, start" now names every noun it touches: the unit that stops, the file the environment loads from, the transactor behind the URI, the root the proxy serves. The deploy chapter's few seconds of downtime were softened there and are fully parameterized here -- but they are still a window, opened on every single deploy, because one box runs one jar at a time. It does not have to. The box you just built can afford to run two.
