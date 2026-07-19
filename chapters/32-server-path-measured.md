# The Server Path, Measured: Criterium and a Flamegraph

The book has now made a dozen small performance claims on the server's behalf. [The render is the same work as serving the page -- cheap](22-live-preview.md). [Fifty pulls against a warm peer is not a cost worth engineering around](23-search.md). [The 304 saves the expensive tail](31-conditional-get.md). Each was justified in context; none came with a number. Meanwhile [the construction-view chapters](18-construction-view-overlay.md), which know more about a request than any tool in this book, twice deferred the timing question with the same sentence: *for profiling, use a sampling profiler* -- an IOU written twice and never cashed.

And there is a blind spot hiding in plain sight. [Chapter 33's Lighthouse audits](33-lighthouse.md) are demanding, automated, and in CI, and they measure everything about our output *except the code that produces it*. Lighthouse starts when bytes arrive; the path from Ring request to HTML string -- the Datomic reads, the pulls, [the LCS diffs](09-recipe-domain.md), the Hiccup serialization, the code this book most owns -- has never been on any instrument. This chapter puts it on two, cashes the IOU, and replaces a dozen adjectives with numbers.

## The harness: measure at the handler

```clojure
(ns bench
  "Measure the server render path — the code this book most owns.
  ,,,
  Usage (composes the :dev classpath for config + seed):
    clojure -M:dev:measure -m bench          criterium benchmarks
    clojure -M:dev:measure -m bench flame    flamegraph -> /tmp/clj-async-profiler/results/"
  ,,,)

(defn- setup!
  "Fresh in-memory db + demo seed; returns ids the benchmarks render."
  []
  (db/create-database!)
  (seed/seed!)
  (let [dbv (d/db (db/get-connection))
        recipes (recipe/all-recipes dbv)
        ;; The most expensive recipe page: the deepest fork lineage.
        deepest (apply max-key #(count (recipe/lineage dbv (:recipe/id %))) recipes)]
    {:recipe-id (:recipe/id deepest)}))
```

Three decisions in the harness carry the chapter's method.

**Handler level, not endpoint level.** The benchmark calls `handler/recipe-show` with a Ring map, the exact seam [the smoke tests](11-unit-testing.md) established. Above this line live http-kit, TLS, and the network -- real costs, but *bought* costs, other people's optimized code. Below it lives everything we wrote. When a number here goes bad, the culprit is in this repository; that property is what makes the number actionable.

**The seeded database, worst case first.** [The seed](09-recipe-domain.md) is a designed fixture, and `setup!` exploits that: it benchmarks the recipe with the *deepest fork lineage*, because [the lineage walk](09-recipe-domain.md) is recursive and the detail page also counts versions and lists forks -- the most database work any public render does. Benchmark your worst representative page; the best ones take care of themselves.

**Criterium, not `time`.** This is the chapter's one new dependency and the trade-off box below argues it. `(crit/quick-bench …)` runs warmup rounds until the JIT settles, samples in batches, and reports distribution, not anecdote:

```
== recipe-show (deepest lineage) ==
Evaluation count : 642 in 6 samples of 107 calls.
             Execution time mean : 1.034898 ms
    Execution time std-deviation : 106.015192 µs
   Execution time lower quantile : 950.453991 µs ( 2.5%)
   Execution time upper quantile : 1.170547 ms (97.5%)

== recipes-index (full catalog) ==
Evaluation count : 852 in 6 samples of 142 calls.
             Execution time mean : 806.250336 µs
```

There are the adjectives, retired. The most expensive public page in the application renders in **about a millisecond** -- lineage walk, version count, fork list, markdown, JSON-LD, full HTML serialization included -- and the whole-catalog index in about 0.8 ms. Numbers like these are why [the preview chapter](22-live-preview.md) could afford a render per debounced keystroke, and what [the conditional-GET chapter](31-conditional-get.md)'s 304s are actually saving. They also set the *scale* of future worry: a regression that doubles this page costs a millisecond, and knowing that changes what deserves engineering at all.

> **Trade-off -- taking criterium instead of writing `(time (dotimes …))`.** This book usually builds; here it takes a dependency, and the rubric that decides is the one it always applies: build when the domain is yours, buy when the domain is *measurement of the JVM*, which is deep, adversarial (dead-code elimination will delete your naive benchmark body), and entirely orthogonal to recipes. Criterium is small, stable, dev-classpath-only (`:measure` alias -- it can no more reach production than [the inspector can](16-inspector.md)), and wrong benchmarks are worse than no benchmarks. The same reasoning admitted the profiler below.

## The flamegraph: where the millisecond goes

A mean tells you *whether* to care; a profile tells you *where*. The second mode of the harness wraps two thousand renders in [clj-async-profiler](https://github.com/clojure-goes-fast/clj-async-profiler):

```clojure
;; :itimer (SIGPROF sampling) rather than the default :cpu —
;; perf_events is typically fenced off inside containers, and for
;; a single-process render profile the two answer alike.
(prof/profile
  {:event :itimer}
  (dotimes [_ 2000]
    (handler/recipe-show (show-request recipe-id))))
```

That comment records a scar earned while writing this chapter: the profiler's default engine attaches to the kernel's `perf_events`, which [the devcontainer](03-devcontainer.md) -- like most containers -- fences off, and the attach fails with an error that says nothing about containers. The `:itimer` engine samples on POSIX signal timers instead, needs no kernel favors, and for profiling one process's own CPU time answers the same question. (The `:measure` alias also carries `-XX:+DebugNonSafepoints`, without which the JIT only reports stacks at safepoints and your flamegraph quietly lies about leaf frames. Profiling has more sharp edges than benchmarking; it is the other half of why this chapter buys instead of builds.)

The output is an interactive SVG-in-HTML flamegraph, and summarizing ours -- samples within `recipe-show` stacks, each attributed once, to the deepest layer that owns it:

| where | share of render |
| --- | --- |
| Datomic reads (pull, lineage, version count, forks) | ~29% |
| String machinery outside the renderer (copies, UTF encoding, response assembly) | ~21% |
| Hiccup serialization and escaping | ~20% |
| Markdown rendering | ~3% |
| Everything else (view logic, JVM runtime, GC) | ~26% |

with self-time dominated by `arraycopy` variants and UTF-8/UTF-16 shuffling. Read the shape honestly: deciding what the page says -- the Datomic reads, all of chapter 9's lineage and history machinery -- is under a third of the millisecond, and turning the answer into bytes (Hiccup, markdown, and the string copying around them) is closer to half. **The server spends its millisecond mostly moving bytes, not thinking.** Which is the most reassuring shape a render profile can have: no accidental N+1 lurking in the lineage walk, no pathological hot spot in [the escaping renderer](14-hiccup-views.md), just the irreducible cost of emitting HTML, sitting where [the construction view](17-construction-view.md) said the structure was.

It also disciplines the future. The classic next move -- caching rendered fragments, interning strings, a cleverer serializer -- would be attacking a millisecond that [the conditional-GET chapter already declines to spend](31-conditional-get.md) for the traffic that repeats. The flamegraph is how you *know* that, instead of suspecting it.

## Proof, of a different kind

Every other feature chapter ends with tests; a measurement chapter ends with reproducibility. The harness is committed (`dev/bench.clj`), the alias is committed, and both run against [the same seed every reader has](09-recipe-domain.md):

```
clojure -M:dev:measure -m bench
clojure -M:dev:measure -m bench flame
```

Your numbers will not match this page's -- different hardware, different day -- and that is the point of committing the *instrument* instead of framing the *reading*. The claims that survive machine changes are the structural ones: the ratio of byte-moving to thinking, the absence of a hot spot, the order of magnitude. Those are the chapter's actual findings.

## Trade-offs & limitations, in one place

- **In-memory storage floors the Datomic numbers.** The bench runs `datomic:mem`, like the tests; a production peer reads segments from storage through its object cache, so *cold* reads cost more than any number here. The floor is still the true baseline for the render path itself -- and the peer-cache behavior that determines the rest is measured, against real storage, in [the runtime-legibility chapter](37-runtime-legibility.md): a thousand renders, forty-one storage reads.
- **Two pages, not a suite.** `recipe-show` (worst case) and `recipes-index` (widest read) bound the public surface today; the harness is fifteen lines to extend when a new page earns suspicion. A per-commit benchmark gate in CI is left unbuilt on purpose: benchmark noise in shared runners produces false alarms that teach people to ignore red, and a number this size does not yet warrant defending robotically.
- **`quick-bench`, not `bench`.** Criterium's full mode runs minutes for tighter confidence intervals; the quick mode's intervals are visible in its own output and plenty for millisecond-scale questions. Escalate when a change is within the noise and matters.
- **The profiler samples; it does not trace.** Three-percent slices like markdown are approximate, rare-but-slow events can hide between samples, and allocation profiling (`:event :alloc`) is a different lens one keyword away in the same harness, not needed here. For *why is this one request slow*, [the construction view](17-construction-view.md) remains the right tool; the flamegraph answers *where does the typical millisecond go*.

## The dashboard is complete

With this chapter, every layer of the application is on an instrument somewhere: the browser experience under [Lighthouse](33-lighthouse.md), the correctness under [the unit](11-unit-testing.md) and [end-to-end](27-e2e-testing.md) suites, the security posture under [its tests](28-admin-dashboard.md), and now the server path under a benchmark and a profiler -- with the one honest gap (production storage behavior) named and assigned to the volume that owns it. What remains for this book is to finish hardening the ship itself: [the audits](33-lighthouse.md), and [the pipeline that runs them on every push](34-ci-cd.md).
