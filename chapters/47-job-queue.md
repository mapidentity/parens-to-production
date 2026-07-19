# The Queue the Database Already Is: Durable Jobs Without a Broker

The book already has background jobs. The [watchdog](38-alerting.md), the [backup](39-backup-restore.md), the [static GC](36-minimal-downtime.md), the [backup verifier](46-watching-the-watchers.md) — all work that runs off the request path, on a schedule, without a user waiting. But they are *box-shaped* jobs: a systemd timer fires, a script runs, and if it fails the unit goes red and emails you. They are triggered by a clock, not by anything that happens in the domain.

This chapter builds the first *domain-shaped* job, the one the [afterword](afterword.md) said would finally earn a queue: when someone proposes a change to your recipe, you get an email. It sounds trivial, and the email part is. What earns the queue is everything around it — the work is triggered by a domain *event*, it must survive a crash, it must retry when the mail relay is briefly down, and it must not run on the request thread. Those four requirements are exactly what a job queue is *for*, and meeting them is where the interesting decision lives.

## The decision: don't buy a broker

The reflex is to reach for infrastructure — Redis with a worker library, RabbitMQ, SQS, Sidekiq's Clojure cousins. Each is a *second stateful system* to run, back up, monitor, and secure, and this book has spent forty-six chapters refusing exactly that reflex. [Analytics](28-admin-dashboard.md) did not need a second database. [Search](23-search.md) did not need Elasticsearch. [Rate limiting](24-auth-tokens.md) did not need a cache tier. The through-line — *the database already knows* — applies once more, and more cleanly than anywhere else: **a job is just a durable fact about work to be done, and durable facts are what Datomic is.**

So a job is an ordinary entity:

```clojure
{:job/id (random-uuid)
 :job/kind :proposal-notification
 :job/status :pending          ; → :running → :done | :failed
 :job/payload "{:proposal-id #uuid \"…\"}"
 :job/attempts 0
 :job/run-after <instant>       ; moved forward on each retry (backoff)
 :job/created-at <instant>}
```

Enqueuing it is a transaction — which means it can be *the same* transaction as the event that spawned it. When a proposal is opened, the proposal and its notification job commit together or not at all:

```clojure
@(db/transact* conn
  [{:proposal/id pid  ,,,  :proposal/status :open}
   (jobs/enqueue-tx :proposal-notification {:proposal-id pid})])
```

That atomicity is a property a bolted-on broker cannot give you without a distributed transaction: with Redis-beside-Postgres, the write to the domain and the enqueue to the broker are two systems, and a crash between them leaves either a proposal with no notification or a notification for a proposal that was rolled back. Here there is one write. The job exists exactly when the proposal does.

## The worker: poll, claim, run

A single background thread — the same lifecycle-managed shape as the [presence reaper](45-live-presence.md) and the [mailer](25-auth-email-flow.md), started and stopped with the server — polls every couple of seconds for jobs whose time has come:

```clojure
(defn- due-eids [db limit]
  (->> (d/q '[:find ?e ?ra :in $ ?now :where
              [?e :job/status :pending]
              [?e :job/run-after ?ra]
              [(<= ?ra ?now)]]
         db (now-date))
       (sort-by second) (map first) (take limit)))
```

Then it *claims* each due job before running it — and the claim is the crux of the whole design:

```clojure
;; CAS :pending → :running. If it fails, someone else got there first.
[[:db.fn/cas eid :job/status :pending :running]
 {:db/id eid :job/claimed-at (time/now)}]
```

That `:db.fn/cas` is [the recipe editor's optimistic-concurrency primitive](21-forms-validation.md), pointed at a job's status. It runs *on the transactor*, so it is atomic across every peer: two workers cannot both claim the same job, because exactly one CAS from `:pending` wins and the other gets a `:db.error/cas-failed` it reads as "not mine." And this is where the queue quietly collects on a debt from two chapters ago. The [pair deploy](36-minimal-downtime.md) runs two app instances, and [chapter 46 was honest](46-watching-the-watchers.md) that their in-memory state forks — the rate limiter counts twice, presence splits. A job queue built on an in-memory list would have the same disease, doubling every notification. Built on Datomic, it simply does not: the two workers coordinate through the transactor's CAS, because the queue's state is *durable and shared*, not a per-process atom. The design that was a liability for the rate limiter is a non-issue for jobs, for free, because we put the state where state belongs.

The rest is bookkeeping the log makes easy. On success, CAS `:running → :done`. On a throw, record a failed attempt and either retry with exponential backoff or, past a cap, give up:

```clojure
(if (>= n max-attempts)
  (cas-status! conn eid :running :failed  [{:db/id eid :job/attempts n :job/last-error msg}])
  (cas-status! conn eid :running :pending [{:db/id eid :job/attempts n
                                            :job/run-after (.plusSeconds (time/now) (backoff-seconds n))
                                            :job/last-error msg}]))
```

One more failure mode the durable model forces you to face honestly: a worker can claim a job, start running it, and then *die* — the process is killed mid-deploy, the box loses power. The job is now stuck `:running` forever, with no worker attached. So each poll also reclaims the abandoned: a `:running` job whose `:job/claimed-at` is older than a visibility timeout is CAS'd back to `:pending` for another worker to pick up. That reclaim is why the semantics are honestly **at-least-once**: a worker might send the email and die before recording `:done`, so the reclaimed job sends it again. A notification tolerates a rare duplicate; a handler that must not repeat a side effect has to make itself idempotent. Exactly-once is a fiction, and the queue does not sell it.

## The handler, and the two kinds of async

The handler is small, and it lives with the domain it serves — a `defmethod` on the job multimethod, next to the proposal code, so the queue stays generic:

```clojure
(defmethod jobs/run-job :proposal-notification
  [conn {:keys [proposal-id]} _kind]
  (let [,,, to (owner-email-for proposal-id)]
    (when to
      (let [{:keys [error]} (email/send-notification! to subject body)]
        (when (= :FAIL error)
          (throw (ex-info "notification send failed" {:to to})))))))  ; → the worker retries
```

It sends synchronously and *throws* on failure, because here the throw is the point: the job queue is the durable retry layer, so a failed send should propagate and let the worker back off and try again. That is the deliberate contrast with [the login email](25-auth-email-flow.md). Both are "send an email off the request thread," but they are different needs, and they get different machinery. The login link runs on an in-memory bounded mailer — fire-and-forget, dropped on restart, no retry — because a lost login email is *recovered by the user re-requesting it*: the retry is a human pressing a button. A "someone proposed a change" notification has no such human; if it is lost, it is *gone*, and the owner never learns. So it earns durability and retries, and the login link does not. Matching the mechanism to whether a lost message can be re-asked for is the actual engineering judgment; reaching for one queue to rule them all would be worse on both.

## Making a background failure visible

A job that fails permanently is the quietest failure in the system: no user saw an error, no page 500'd, the effect simply never happened. So the [metrics endpoint](37-runtime-legibility.md) carries the queue's depth, and a rising `jobs{status="failed"}` is the signal a handler is broken — the same "make the invisible visible" discipline [the resilience pass](46-watching-the-watchers.md) applied everywhere else. The whole history is queryable too: because jobs are Datomic entities, `d/history` shows every attempt of every job, with its timestamp and error, at no extra cost — the audit trail is a side effect of the storage choice, exactly as it was for [recipe versions](09-recipe-domain.md).

## Trade-offs & limitations, in one place

- **It is a poll queue, so it has poll latency.** A job runs within one poll interval (a couple of seconds) of becoming due, not the instant it is enqueued. For notifications and digests that is invisible; for work that must start in single-digit milliseconds, a poll queue is the wrong tool, and you would push rather than poll.
- **At-least-once, not exactly-once.** The reclaim that makes the queue crash-safe is the same mechanism that can run a job twice. Handlers that must not repeat a side effect have to be idempotent; the honest default is to design them so a duplicate is harmless.
- **One transactor is the throughput ceiling.** Every enqueue, claim, and completion is a transaction, so the queue's rate is bounded by [the single transactor](35-going-live.md) that bounds every write in this system. That is ample for domain events — proposals, digests, the occasional webhook — and nowhere near a firehose. The day you are enqueuing thousands of jobs a second is the day a purpose-built broker finally earns its second-system cost, priced the way [the scaling audit](41-beyond-one-box.md) prices the second box: named here, not pretended away.
- **The notification defaults to English.** A job has no request, so no `Accept-Language`; a per-user locale preference would be a small, worthwhile refinement, and it is a seam, not a wall.

## The wager, once more

The pattern this chapter is really about is not "how to write a worker loop" — those are a dime a dozen. It is that the honest inventory of what a durable job needs — atomic enqueue, single-claim under concurrency, retry, crash-safe reclaim, an audit trail, observability — turned out to be a *list of things Datomic already does*, so the queue is a schema, a query, a CAS, and a background thread this book has now written three times. The reflex reaches for a broker because "job queue" sounds like infrastructure. Owning your stack means asking, one more time, whether the infrastructure you already run does the job — and here, as with analytics and search and rate limiting before it, it does. The first domain-shaped job earned a queue. It did not earn a second system to run it.
