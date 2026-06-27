# Revision Checklist

A development-editor pass on the manuscript, organized as the three blocking
items plus a polish list. Each item is concrete and per-chapter. Check items off
as they land.

The three blockers, in priority order:

1. **Unify the voice** — lift the tutorial-register chapters to the essayistic
   register the best chapters already use.
2. **Right-size chapter boundaries** — split the overloaded chapters; resize the
   construction-view cluster.
3. **Fix code-listing integrity** — no elided load-bearing functions; no
   duplicated full listings.

---

## Blocker 1 — Unify the voice (→ essayistic)

The book has two registers. The **essayistic** one (chs. 2, 13, 15, 16, 17, 22)
argues from consequence, names trade-offs in line, and uses "Decision —" /
"Trade-off —" / "Lesson —" callouts. The **tutorial** one (chs. 18, 19, 20, 23,
parts of 5, 7, 8) hand-holds: "Let's start with…", "Let's walk through…", "A few
things to note:", numbered lists that restate the code just shown, and "What You
Now Have" recaps. Lift the tutorial chapters; the essayistic voice is the more
distinctive and valuable one.

Recurring tells to hunt and replace (global):

- [ ] `Let's start with` / `Let's walk through` / `Let's look at` → declarative
      lead-ins ("The primitives come first.", "Verification mirrors signing,
      with two extra checks.").
- [ ] `A few things to note:` / `Here's what to note:` → prose that says *why*
      the listing is shaped the way it is, not a bulleted restatement.
- [ ] Numbered lists that merely re-walk the code just shown → keep only where
      the sequence/order is itself the point; otherwise fold into prose.
- [ ] `By the end, you will have…` openers → state the problem, not the deliverable.
- [ ] `## What You Now Have` recaps → either cut (see Blocker 3) or compress to a
      one-paragraph essayistic close that lands the chapter's argument.
- [ ] Motivational-poster sign-offs ("Start strict. Stay strict.", "You need
      both.") → cut or replace with a substantive last line.

Per chapter:

- [ ] **18 — auth-tokens.** Tells at: "Let's start with the low-level building
      blocks" (§Crypto primitives), "Let's walk through the verification logic"
      (§Verifying), "A few things to note" (§Creating a user), "Tests are where
      the design proves itself. Let's walk through the test suite" (§Testing),
      "## What You Now Have". Strong material — just relax the register.
- [ ] **19 — auth-email-flow.** Lift tutorial lead-ins; keep the excellent
      CAS/`SameSite=Lax`/session-integrity passages as-is (already essayistic).
- [ ] **20 — e2e-testing.** "By the end, you will have…" opener; "A few things to
      note" (§Deterministic Configuration); "Now for the actual tests"; "Design
      Decisions Worth Noting" (Q&A format → prose); "What You Now Have"; the
      "Unit tests tell you… You need both" sign-off.
- [ ] **23 — lighthouse.** Lift the "Hitting 100%" walkthrough out of checklist
      register; keep the "a 100 is a contract" framing (already the right voice).
- [ ] **5, 7, 8 (light touch).** These are mostly essayistic but slip into
      tutorial voice in their recap/"Design Decisions" tails — covered under
      Blocker 3 too.

## Blocker 2 — Right-size chapter boundaries

- [ ] **Split ch. 7 (Datomic).** The `myapp.time` clock indirection is an
      admitted "orthogonal" concern interleaved mid-chapter (the prose says so).
      Pull storage-model + Datalog + the `java.time`↔`Date` bridge into ch. 7;
      move the clock wrapper (`myapp.time`, `transact*`) into its own short
      chapter or an appendix it can be referenced from. Removes the
      `transact*`-introduced-then-detoured-then-resumed whiplash.
- [ ] **Split ch. 11 (Hiccup views).** Two chapters wearing one title: (a) the
      layout/view system + XSS output-escaping, and (b) the morph-dispatcher
      progressive-enhancement engine (intercept rules, `fetchAndMorph`, history,
      script re-execution, the `enhance()` contract). Split (b) into its own
      chapter; it's a complete subsystem.
- [x] **Reframe the construction-view cluster (12 + 13 + 15) — KEEP it, don't
      cut it.** (Author decision, overriding the earlier "luxury, compress to an
      appendix" suggestion.) The inspector + construction view carries two of the
      book's theses: SSR is not a poorer toolbox than an SPA, and owning your code
      means you can build this tooling yourself. So treat it like every other
      topic — explain the load-bearing how/why (the `/dev/ws` relay, the
      recording, the projections) in prose; only genuinely incidental code
      (styling, DOM-assembly minutiae) lives in the repo. Done: removed the
      apologetic "dev-only luxury / skim the rest" blockquote in ch. 12 and the
      "A luxury, and worth naming as one / build only if" blockquote in ch. 15,
      replacing both with the positive thesis (parity + ownership) while keeping
      the honest "here's when it pays off" note. See memory
      `construction-view-not-a-luxury`.
- [x] **Reduce forward-references to one per concept.** Ch. 5: kept the concrete
      "added in chapter N" pointers but cut the repeated seed/foundation/skeleton
      *flourish* down to one canonical statement (the routes "seed, not the final
      form" line) — removed the "foundation, not the whole"/"mechanism does not
      change" bookends (middleware §), the "skeleton… minimum that runs" closer
      (`start-server!` §), and compressed the recap's "when you need X you add Y"
      re-enumeration. Ch. 24: the app-vs-mdBook caveat was a *double* (opening
      blockquote + close), not a triple — line 382 is the asset-gate detail the
      opening promised, and line 216 is a separate monorepo-path note; compressed
      the closing restatement to a one-clause callback that keeps the actionable
      two-command pre-deploy ritual. (Ch. 11's `base-layout` forward-references
      were already resolved by the ch. 11 split.)

## Blocker 3 — Code-listing integrity

- [~] **Ch. 13 — `build-spans` / Ch. 15 — overlay `,,,` elisions: RECONCILED with
      the construction-view steer, not defects.** Per the author (see memory
      `construction-view-not-a-luxury`): explain the load-bearing *how/why* in
      prose — which both chapters do (ch. 13 walks `build-spans`'s two passes +
      three tricks; ch. 15 shows the highlight selector, the `/dev/ws` relay, the
      flow projection, the eid-match, the morph re-correlation in full) — and
      leave genuinely incidental code (a dense full listing, styling/DOM-assembly
      minutiae) in the repo for the reader to copy. So these are intentional, not
      gaps. Optional nicety only: soften ch. 13's "open it there as you follow
      along" phrasing. Not blocking.
- [x] **Ch. 6 — expand prose under the NIO watcher.** Added three bullets walking
      the genuinely puzzling `java.nio.file` interop: the `Files/find` + `reify`'d
      `BiPredicate` directory walk, the empty `(make-array … 0)` "no varargs"
      shim, and `.context`/`.watchable`/`.resolve` event→path reconstruction.
- [x] **Ch. 20 — de-duplicate the Playwright specs.** The "new user registration"
      test re-walked `registerUser`'s exact body; collapsed it to a single
      `registerUser(...)` call (the helper's inline `expect`s are the assertions),
      with a sentence on why the other tests reuse the helper rather than
      re-walking. Returning-user and logout tests already delegated to the helper.
- [x] **Cut redundant recaps.** Removed ch. 6 "Design Decisions Worth Noting"
      (re-explained `load-file`-vs-`tools.namespace` and `requiring-resolve`
      already covered inline; chapter now closes on "Where This Goes Next").
      Trimmed ch. 8's movement-close from three restatements to two complementary
      ones — kept the runtime ASCII diagram and the done-checklist, cut the
      narrated 6-step sequence that duplicated both. (Ch. 18/20 "What You Now
      Have" were already converted to essayistic closes in the Blocker-1 pass.)

## Polish list (single cleanup pass)

- [x] Cut the unattributed culture reference: "The Algorithm says…" removed from
      ch. 9 (the surrounding 30-lines-vs-library argument stands on its own).
- [x] Cut motivational sign-offs. Ch. 4's "Start strict. Stay strict. Your future
      self will thank you." replaced with a substantive last line (the gate is
      free before the first warning, a cleanup project after the three-hundredth).
      Ch. 20's "You need both." was already handled in the Blocker-1 voice pass.
- [x] De-duplicated the `@source not ".../inspector.js"` explanation in ch. 10 —
      shortened the inline CSS comment to one line; the full "false-positive
      `resize` token" rationale stays in the prose bullet below.
- [x] Normalize section-heading case across chapters. Done as one sweep:
      standardized `##`+ section headings to **sentence case** (~190 headings),
      leaving `#` chapter titles in Title Case — the house style chs. 2/18
      already used. Code spans, acronyms (HTML/XSS/CSP/E2E/SMTP/REPL…), digits
      (`N+1`, Base64, 404), internal-caps (JavaScript, CommonMark, WebSocket),
      and proper nouns (Clojure, Datomic, Reitit, Tailwind, Playwright, …) all
      preserved. Hand-fixed the cases automation can't judge: sentence boundary
      after `?` ("Why a nonce? The replay problem"), and proper names the book
      capitalizes elsewhere (VS Code, Calva, Dockerfile, Forgejo Actions,
      Accept-Language, Content-Security-Policy, Post-Redirect-Get). This also
      collapsed the two divergent "Output (e/E)ncoding…" headings to one form.
- [~] "Accept header is vestigial": after the ch. 11 → 11b split this is no longer
      a within-chapter dup — 11b (dispatcher) explains the header the client sends
      and the server ignores; ch. 11's middleware note covers a *different* point
      (locale is the only negotiated header). Not redundant. (Original checklist
      said "ch. 9" — that was a mis-attribution; the Accept-*Language* header in
      ch. 9 is a separate, legitimately-recurring topic.)

---

## Second pass — publisher's developmental review (2026-06-27)

A fresh read against the question "would an O'Reilly/Manning editor sign and ship
this?" Verdict: yes, with margin-level refinements. The three blockers above are
substance; these are calibration and polish. Priority order:

- [x] **Fix the chapter-count claim.** Root `README.md` said "20 chapters"; the
      book has 25 (the 06b/11b splits + the chapters added since). Corrected to 25.
- [x] **Code-fence audit — RESOLVED, no change needed.** Audited every fenced
      block: 389 openers, of which only 12 are bare. All 12 are intentionally
      non-code — directory trees (ch. 3, 22), ASCII diagrams (ch. 5 middleware
      pyramid, ch. 8 runtime tree, ch. 11 layout tree, ch. 13/15 trace icicles),
      and notation (ch. 12 breadcrumb, ch. 18 token structure, ch. 9 raw header).
      The book never uses a `text` label, so the bare fences are internally
      consistent with each other. The 377 labeled openers all carry a real
      language. Nothing to fix; do **not** mechanically label the diagrams.
- [x] **Audience contract (intro).** Added a "How hard this gets, and how to read
      the hard parts" section to ch. 1 (before the closing line). Names the three
      difficulty spikes by chapter — `java.nio` interop (ch. 6), Datalog's EAV
      facts (ch. 7), and the ClojureStorm construction-view cluster (12/13/15) —
      and gives the reader two handholds (run the repo in place; the hard chapters
      are built to be read twice). Kept positive, not apologetic, per the
      `construction-view-not-a-luxury` steer: "none of it is filler... where a
      chapter gets hard it is because the problem is."
- [ ] **Scaffold three steep drops.** (a) Java NIO `WatchService` in ch. 6 — a
      minimal example before the full watcher (note: partly addressed by the
      Blocker-3 NIO bullets; confirm it's enough). (b) One literal
      `[entity attr value]` fact in ch. 7 before the first Datalog query.
      (c) Promote the "compiler welds metadata onto the value" insight earlier in
      ch. 12 — it currently arrives as "Fact two" but it is the keystone.
      Status: (a) RESOLVED — the Blocker-3 NIO bullets (ch. 6, "the first three are
      the `java.nio.file` interop a reader new to it is most likely to stall on")
      already demarcate the incidental difficulty as plumbing, the prose states the
      watcher's shape up front, and the new ch. 1 contract warns of the NIO spike. A
      shape-first pseudocode skeleton would be the didactic padding the book avoids;
      not adding it. (b) DONE — the abstract EAV prose (line 205) was already strong
      (literal `[42 :user/email …]` facts shown), but the *first query* wasn't
      bridged to it and `:in $ ?email` went unexplained; added a paragraph reading
      the `:where` pattern positionally against the fact shape and explaining
      `:in`/`$`. (c) DONE — the insight was already well-built (a section titled
      "The insight…", two stacked facts, a synthesis blockquote, the "position *is*
      part of the value" punchline), but it built claim-last, so a reader waded
      through both facts not knowing the destination. Changed the section's lead
      line to state the keystone as a thesis up front ("a Hiccup element's source
      position can ride on the runtime value itself — no separate index"), so Facts
      one/two now read as proof of a stated claim and it bookends with the punchline.
- [x] **"When not to use this" notes — RESOLVED, already present.** Both chapters
      already carry the honest pay-off/cost treatment the earlier
      `construction-view-not-a-luxury` work added: ch. 12 line 12 ("earns its keep
      in proportion to how much view code you have…") + a startup-cost note (543);
      ch. 15 line 7 is a whole "What this investment buys" blockquote naming the
      cheap alternative ("a `println` and a REPL… often enough") and exactly when
      the tool wins, + a first-hit-cost note (521). Adding an explicit "skip this
      if…" section would re-introduce the apologetic register the steer removed.
      No change.
- [x] **Book-vs-repo divergences — RESOLVED, already handled.** The case the review
      flagged (ch. 19 inline session checks vs. the repo's middleware) is already a
      thorough callout at line 413 ("Handler-gated here, middleware-gated in the
      repo"), ending "prefer [the middleware] if you have built it; the inline form
      here is the minimal equivalent." "the repo" is referenced across ~10 chapters,
      so the pattern is book-wide. No change. (A full book↔repo diff is out of scope
      for a polish pass.)
- [x] **Flag the single-instance rate-limiter (ch. 19) — DONE, smaller than the
      review implied.** The chapter was already strong: a dedicated `### Rate
      limiting beyond one instance` section, the per-replica failure mode named, a
      real Redis `allow?` implementation, and Postgres/Datomic alternatives — the
      "needs code to cement it" critique was overstated. The only soft spot was
      framing (read as optional scaling polish). Sharpened the consequence
      sentence: the limit "silently multiplies by the number of replicas... a
      horizontally-scaled deploy on the in-process counter has no working rate
      limit," making the shared store "a prerequisite for going multi-instance,
      not a later optimization."
- [ ] **Reconcile book-vs-repo divergences.** Where the prose shows one shape and
      the repo another (e.g. ch. 19 inline session checks vs. the repo's
      middleware), add a one-line "the repo factors this into X — see <path>".
- [x] **Renumber chapters to sequential 01–25 — DONE (2026-06-27).** The 06b /
      11b / missing-14 scheme read fine online (mdBook numbers by SUMMARY position,
      not filename) but the author's *numeric prose refs used filename numbers*, so
      several were latently wrong against the displayed numbers (e.g. "(Ch. 7)" for
      Datomic, which displayed as 8). Renamed all 19 affected files with `git mv`,
      rewrote every inter-chapter link target, and fixed the numeric text refs
      (ch.9 diagram, the construction-view cluster's 14/15/16 cross-links, the
      email-flow "(ch. 20)"). Verified: 114 links resolve, SUMMARY is 01..25,
      filename = displayed = referenced number, build clean. NB: chapter-number
      references *elsewhere in this checklist* predate the renumber.

Not changing (consistent with the book's thesis, flagged so a later pass doesn't
"fix" them): no end-of-chapter exercises or summaries, the high density, and the
opinionated voice. These are the book's identity, not defects.

---

## Third pass — publisher fidelity + recap sweep (2026-06-27)

An independent O'Reilly/Manning-style read focused on the book's signature
covenant ("every listing lifted from a server that runs; where the two drift,
the book says so") where it had broken, plus the residual recap/sign-off
sections the voice pass missed. Verified after each change: `./lint` 0/0,
`compile-strict` OK, full suite 87 tests / 205 assertions 0 failures, links
resolve, `mdbook build` clean.

Fidelity reconciliations (book ↔ repo):

- [x] **Ch. 13 — rewrote "Scripts inside a morph" to the shipped design.** The
      `executeScripts` listing and the `enhance()`/`data-enhanced`/`DOMContentLoaded`
      pattern contradicted the repo (`dispatcher.js` deliberately does NOT
      re-execute morphed scripts; behavior attaches via the `controllers.js`
      registry's `connect`/`disconnect`, which forbids the old pattern) and the
      book's own CSP. Now matches; forward-references ch. 18 for the registry;
      fixed the stale "idempotent `enhance`" line in the close.
- [x] **Ch. 8 — made the phantom test suite real.** `myapp.db.core-test` was
      presented as the repo's suite but did not exist. Added
      `test/myapp/db/core_test.clj` verbatim from the listings (passes: 5 tests,
      12 assertions) and cut the chapter's "What you now have" recap.
- [x] **Ch. 9 — `*load-tests*` binding made real + in-file-tests reframed.** Added
      `#'clojure.test/*load-tests* false` to `compile-strict`'s `:bindings` in
      `build.clj` (real hygiene, makes the chapter's claim true); reframed §"In-file
      tests" so it no longer claims the repo ships them; corrected the
      `test_helpers` ns disclosure (the file grows an `auth.core` require, not just
      the analytics one).
- [x] **Ch. 22 — corrected the "how the repo factors this" box.** It claimed
      `wrap-admin` reads `(:user-email request)`; the repo reads the resolved
      `:admin?` flag, with the case-fold in `admin-email?`/`wrap-current-user`.
- [x] **Ch. 18 — speculation-rules listing.** Added the `/terms/*` and
      `/partials/*` exclusions to match `assets.clj` and the chapter's own prose.
- [x] **Ch. 3 — three fixes.** "Calva is the only required extension" → noted
      Joyride is also installed; added an excerpt note for the omitted `browser`/X11
      compose service; fixed the `enUS.UTF-8` locale typo in the Dockerfile (repo).

Technical fix:

- [x] **Rate limiter mislabel (ch. 20 + `ratelimit.clj`).** The code is a
      sliding-window log (per-hit timestamps pruned to a trailing window), not a
      "fixed-window" counter with boundary burst. Corrected the docstring and the
      chapter; noted the Redis alternative *is* a true fixed window.

Recap sweep (remove bulleted "What you now have" recaps + motivational sign-offs;
keep genuinely essayistic/forward-looking closes):

- [x] Cut in chs. **4, 5, 8, 9, 10, 11, 17, 19, 20, 22, 23**. Ch. **25**'s triple
      close ("What you now have" + "Looking back" + "Now go build something")
      collapsed to one essayistic ending that lands the own-your-code thesis. Ch. 9
      kept its first-movement diagram (a deliberate movement boundary, not a recap).

Construction-view cluster trim (chs. 14–16):

- [x] Cut the redundant bulleted "Design decisions worth noting" and "What you now
      have" feature-recaps in chs. **14** and **16** (each restated body/inline
      material), keeping the essayistic closing paragraph and the substantive
      "Trade-offs & limitations" / "Keeping production clean" sections. Relabeled
      ch. **15**'s "What you now have" (it is a prose+diagram close, not a recap).
      Trimmed the ch. 16 opening "What this investment buys" callout: kept the
      pay-off/cost framing (the cheap `println`+REPL alternative, when the tool
      wins), compressed the thrice-stated REPL-to-the-browser thesis to a
      back-reference to ch. 14.

Re-checked and deliberately NOT changed (verified against the repo, agent
over-call): ch. 3's Playwright listing — the repo installs both Playwright
browsers (`Dockerfile`) and Chrome, and `playwright.config.js` uses chromium, so
the listing is correct. Ch. 7's "grep rather than a linter rule" — accurate: the
time functions are Java statics clj-kondo can't see; its `:discouraged-var` rule
targets a different concern (`datomic.api/pull`).
