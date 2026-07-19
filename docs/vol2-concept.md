> **Archived — planning brief, not a chapter.** This is the June 2026 concept
> brief for a possible *Volume 2: Operating a Clojure/Datomic SaaS*, preserved
> from the (now-deleted) `volume-2` branch. Most of it shipped: its proposed
> chapters became this book's Going Live, Operating, Defending, and Long Run
> parts — backup/restore → ch.39, immutability-vs-erasure → ch.40, observability
> → ch.37, alerting → ch.38, the scaling audit → ch.41, durable jobs → ch.47,
> security operations → ch.42–43, zero-downtime deploys → ch.36. What remains
> unbuilt is named here as future work — chiefly the §6 failure-and-load lab
> that would turn the afterword's "transactor failover pair, unverified" into
> "drilled." Kept for that idea; the stale chapter drafts on the branch were
> superseded by the current chapters and discarded.

---

# Parens *in* Production — Volume 2 Concept

> Working title: **Parens in Production: Operating a Clojure/Datomic SaaS**
> Companion to **Parens to Production** (Volume 1). Forked from the `refinement`
> branch; this document is the concept brief, not yet chapters.

---

## 1. The one-line pitch

Volume 1 took an empty directory to an automated deployment. Volume 2 takes that
same running application and answers the question the first book deliberately
left open: **what does it take to *keep* it running** — backed up, observable,
scalable, and legally and operationally sound — when real users and real data
are on it.

The preposition is the whole thesis. *To* production is the journey there. *In*
production is living there.

---

## 2. Why a second volume, not a longer first one

Volume 1 closes on a complete arc — environment → data → rendering → tooling →
features → hardening → ship — and its afterword is a real ending. Operations is
a different reader *moment* (you ask about transactor failover only once you have
something to fail over) and a different reader *mindset* (operator, not builder).
Bolting twelve ops chapters onto an already-25-chapter book would break its tight
thesis and shift its center of gravity. So: a sibling volume, sharing the same
running application and the same repository, written in the same voice.

The two volumes together form one canon: **own your whole stack, cradle to
grave.** Each makes the other more valuable.

---

## 3. The thesis carries over — with one sharpened edge

Volume 1's stance: *the best build, not the easiest explanation; show your work
so every choice can be re-made; the server is the authority.* All of that holds.

Volume 2 adds one operating principle, and it is the spine of the book:

> **Lead with the problems where being on Clojure and Datomic changes the
> answer.** Operations is a crowded field; most of it is provider-agnostic SRE
> that a hundred good books already cover. This book earns its place only where
> the stack makes the answer *different* — and it is honest enough to treat the
> generic concerns briefly and point outward, exactly as Volume 1 treated the
> roads it declined.

That principle is also the book's defense against its single biggest risk
(dilution into a generic SRE book — see §7).

---

## 4. Target reader

Someone who has shipped a Clojure/Datomic application (this one, or their own)
and now has to operate it: the solo founder who is also the on-call engineer, the
small team without a dedicated SRE, the consultant standing up a client's first
production Datomic system. They can read Clojure fluently, have met Volume 1's
architecture (or a system shaped like it), and are now asking day-2 questions
they cannot find good Clojure/Datomic-specific answers to anywhere.

---

## 5. Proposed contents

Grouped into movements. The **Diff** column rates how Clojure/Datomic-specific
the answer is — 🟢 high (a genuinely different answer; lead with these), 🟡 medium
(generic problem, stack-flavored solution), 🔴 low (generic; cover the *shape*
and the seam in our codebase, then point outward). The ratio of 🟢 to 🔴 is the
quality control for the whole book.

### Movement I — Don't lose the data

| # | Chapter | Diff | Why it earns its place |
|---|---------|------|------------------------|
| 1 | **Backup, restore, and the restore *drill*** | 🟢 | Datomic-specific (`backup-db`/restore, basis points, verifying a restore rather than assuming one). The most jarring omission in Vol 1; it anchors Vol 2. The chapter's thesis: *a backup you have never restored is a rumor.* |
| 2 | **Immutability vs. the right to be forgotten** | 🟢 | *The headline chapter.* Datomic never forgets; GDPR/CCPA say you must. `:db/excise`, what it costs, what it breaks (history, `as-of`, indexes), and the architectural choices that keep erasable data out of the immutable log in the first place. A hard, Datomic-specific problem almost nobody writes about well — tailor-made for "show the trade-off." |
| 3 | **Schema and data evolution, live** | 🟡 | Additive-by-default schema, attribute renames/retirement, backfills over an immutable log, migrations that run while serving. |

### Movement II — See what it's doing

| # | Chapter | Diff | Why it earns its place |
|---|---------|------|------------------------|
| 4 | **Structured logging for an operator, not a developer** | 🟡 | Promote Vol 1's logback setup to correlation IDs, request context, retention, aggregation — and *what to actually log* in a server-authoritative app. |
| 5 | **Metrics and tracing without a framework** | 🟡 | OpenTelemetry from a Clojure peer; what to instrument; transactor/query latency. **Names the trap explicitly:** Vol 1's "construction view" is a *dev-time* tracer — this is production observability, and the two are not the same tool. |
| 6 | **What a healthy Datomic peer looks like** | 🟢 | Peer cache hit rates, datom counts, transactor queue depth, GC behavior under a long-lived peer. Stack-specific signals a generic dashboard won't tell you to watch. |
| 7 | **Alerting, SLOs, and the runbook** | 🔴 | Mostly generic; kept tight. The deliverable is *our* app's runbook, not a survey of paging tools. |

### Movement III — Take the load

| # | Chapter | Diff | Why it earns its place |
|---|---------|------|------------------------|
| 8 | **Scaling reads: the peer model** | 🟢 | The genuinely *different* scaling story — read scaling via additional peers, valcache/memcached, query caching — not "add app nodes + shard Postgres." |
| 9 | **The single writer: transactor HA and failover** | 🟢 | Datomic's single-writer architecture, standby transactor, failover behavior, what the app sees during one. |
| 10 | **Multi-tenancy under contention** | 🟢 | Builds directly on Vol 1's tenant-isolation layer: noisy neighbors, per-tenant quotas, and promoting Vol 1's *in-process* rate limiter (the flagged seam) to something node-shared. |
| 11 | **Load testing and failure injection** | 🟡 | Doubles as the book's *proof harness* (§6): a compose'd transactor you can kill, a load generator, captured-real dashboards. |

### Movement IV — Run the business

| # | Chapter | Diff | Why it earns its place |
|---|---------|------|------------------------|
| 12 | **Background jobs and the transactional outbox** | 🟡 | Scheduling (Chime/Quartz), idempotent workers, an outbox built *over Datomic* so a job and its trigger commit together. |
| 13 | **Real email and its deliverability** | 🔴 | Auth depends on mail arriving, so it can't be skipped — provider, SPF/DKIM/DMARC, bounce/complaint handling. Covered briefly; point outward. |
| 14 | **Billing and subscriptions** | 🔴 | Stripe integration *shape*, webhooks as facts in Datomic, proration/dunning/tax named but delegated. Its own book; we cover the seam. |

### Movement V — Keep it safe and shippable

| # | Chapter | Diff | Why it earns its place |
|---|---------|------|------------------------|
| 15 | **Security operations** | 🟡 | Rotating Vol 1's signing/session keys without logging everyone out, dependency/CVE scanning, audit logging (a natural fit for an append-only log), incident response. |
| 16 | **Zero-downtime deploys** | 🟡 | Blue-green over Vol 1's Podman/Forgejo pipeline, draining connections, deploying a schema change and the code that needs it without a window. |

That is a full book with deliberate slack: the 🔴 chapters (7, 13, 14) can each
compress to "here is the shape, here is the seam in our codebase, go deep
elsewhere." If the 🔴 material ever grows past a third of the book, we have
written someone else's book and must cut.

---

## 6. The fidelity problem, and the lab that solves it

Volume 1's superpower is that *every listing runs and every screenshot is real.*
That is easy for a renderer and hard for "what happens when the transactor fails
over." Much of operations is runtime behavior you cannot capture in a static
repo, and that is the thing most likely to make Volume 2 feel thinner than
Volume 1 if left unaddressed.

The mitigation is also a feature: **build a failure-and-load lab into the
companion repo.**

- A `compose`/`podman` topology with a transactor, a standby, and the app — where
  killing a container is a documented step, not a thought experiment.
- A committed load-generation harness, so throughput and latency claims are
  *demonstrated*, the way Vol 1 demonstrated rendering.
- Captured, real observability artifacts — dashboards, traces, a backup/restore
  transcript — committed alongside the prose, so the operate-side claims are
  evidenced exactly like the build-side ones.

**This is the gating decision for the whole volume.** Settle the lab's shape
before writing chapters; it determines whether Vol 2 can honor Vol 1's contract.

---

## 7. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| **Dilution into a generic SRE book** (the big one). | The §3 principle, enforced by the §5 Diff ratio. Lead with 🟢; compress 🔴; cut if 🔴 exceeds a third. |
| **Book↔repo fidelity is harder for ops.** | The §6 lab. Treat it as a precondition, not an afterthought. |
| **Narrower audience than Vol 1.** | True — but the niche is a *vacuum*. There is essentially no good book on operating Datomic in production. Scarcity + Vol 1's demonstrated quality bar is the bet. |
| **Ops content dates faster** (tool versions, provider UIs). | Favor architecture and signals over vendor click-paths; pin the few hard versions; keep vendor specifics in the 🔴 chapters that are explicitly "point outward." |
| **Datomic licensing/edition drift** affects scaling/HA claims. | State the edition assumptions per chapter up front, as Vol 1's ch.8 "Which Datomic is this?" box already does. |

---

## 8. What Volume 2 deliberately leaves out

In Volume 1's tradition of naming its own edges:

- **Kubernetes / large-scale orchestration.** The book stays at the scale a small
  team actually runs — single transactor with a standby, a handful of peers. The
  jump to fleet orchestration is real work and another book.
- **Multi-region / global latency.** Named as the frontier beyond our scale, not
  built.
- **A data warehouse / analytics pipeline.** Vol 1's in-app funnel analytics is
  the ceiling; ETL to a warehouse is out of scope.
- **Becoming a generic SRE reference.** On purpose. The 🔴 chapters point you to
  the people who do that better.

---

## 9. Open decisions before drafting

1. **The lab topology (§6)** — the precondition. Single-host compose, or a
   small multi-host setup? This bounds every scaling/HA chapter.
2. **Datomic edition baseline** — Pro on-prem (matching Vol 1) throughout, with
   Cloud noted as a variant? Or split treatment? Affects ch.1, 8, 9.
3. **Chapter count target** — 16 as above, or trim to a tighter ~12 by folding
   the 🔴 chapters into a single "running the business" chapter?
4. **Billing depth** — integration-shape-only (recommended), or a fuller Stripe
   chapter that risks the dilution in §7?
5. **Repo strategy** — same repository as Vol 1 (one app, two books) or a Vol 2
   branch/overlay? Same-repo is truer to the "one running app" promise but grows
   the tree.

---

## 10. Recommendation

Proceed, on three conditions: **lead with the 🟢 chapters** (backup/restore and
immutability-vs-erasure are the proof the voice carries into production),
**settle the lab in §6 before drafting** (it is what keeps Vol 2 as grounded as
Vol 1), and **hold the 🔴 line** (compress the generic, point outward, cut if it
spreads). Get those right and Volume 2 is not a lesser companion but the other
half of a two-volume canon nobody else has written.
