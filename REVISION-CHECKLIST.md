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

*(Fourth-pass audit, 2026-07-03: every box in this section is DONE — a
full-corpus sweep finds zero instances of any tell below anywhere in
`chapters/`. The work landed across the voice/recap passes without the
bookkeeping; boxes ticked retroactively. The ~18 borderline residues that do
survive are itemized in the fourth pass, § 4.6.)*

Recurring tells to hunt and replace (global):

- [x] `Let's start with` / `Let's walk through` / `Let's look at` → declarative
      lead-ins ("The primitives come first.", "Verification mirrors signing,
      with two extra checks.").
- [x] `A few things to note:` / `Here's what to note:` → prose that says *why*
      the listing is shaped the way it is, not a bulleted restatement.
- [x] Numbered lists that merely re-walk the code just shown → keep only where
      the sequence/order is itself the point; otherwise fold into prose.
- [x] `By the end, you will have…` openers → state the problem, not the deliverable.
- [x] `## What You Now Have` recaps → either cut (see Blocker 3) or compress to a
      one-paragraph essayistic close that lands the chapter's argument.
- [x] Motivational-poster sign-offs ("Start strict. Stay strict.", "You need
      both.") → cut or replace with a substantive last line.

Per chapter:

- [x] **18 — auth-tokens.** Tells at: "Let's start with the low-level building
      blocks" (§Crypto primitives), "Let's walk through the verification logic"
      (§Verifying), "A few things to note" (§Creating a user), "Tests are where
      the design proves itself. Let's walk through the test suite" (§Testing),
      "## What You Now Have". Strong material — just relax the register.
      (Done: now opens "The low-level building blocks come first.")
- [x] **19 — auth-email-flow.** Lift tutorial lead-ins; keep the excellent
      CAS/`SameSite=Lax`/session-integrity passages as-is (already essayistic).
- [x] **20 — e2e-testing.** "By the end, you will have…" opener; "A few things to
      note" (§Deterministic Configuration); "Now for the actual tests"; "Design
      Decisions Worth Noting" (Q&A format → prose); "What You Now Have"; the
      "Unit tests tell you… You need both" sign-off.
- [x] **23 — lighthouse.** Lift the "Hitting 100%" walkthrough out of checklist
      register; keep the "a 100 is a contract" framing (already the right voice).
- [x] **5, 7, 8 (light touch).** These are mostly essayistic but slip into
      tutorial voice in their recap/"Design Decisions" tails — covered under
      Blocker 3 too.

## Blocker 2 — Right-size chapter boundaries

- [x] **Split ch. 7 (Datomic).** The `myapp.time` clock indirection is an
      admitted "orthogonal" concern interleaved mid-chapter (the prose says so).
      Pull storage-model + Datalog + the `java.time`↔`Date` bridge into ch. 7;
      move the clock wrapper (`myapp.time`, `transact*`) into its own short
      chapter or an appendix it can be referenced from. Removes the
      `transact*`-introduced-then-detoured-then-resumed whiplash. (Done — box
      ticked retroactively in the fourth pass: shipped as `07-time-clock.md` +
      `08-datomic.md`; each names the orthogonality and cross-references the
      other.)
- [x] **Split ch. 11 (Hiccup views).** Two chapters wearing one title: (a) the
      layout/view system + XSS output-escaping, and (b) the morph-dispatcher
      progressive-enhancement engine (intercept rules, `fetchAndMorph`, history,
      script re-execution, the `enhance()` contract). Split (b) into its own
      chapter; it's a complete subsystem. (Done — box ticked retroactively in
      the fourth pass: shipped as `13-hiccup-views.md` +
      `14-morph-dispatcher.md`; ch. 13 defers the dispatcher explicitly and
      ch. 14 owns the subsystem end to end.)
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
- [x] **Scaffold three steep drops.** (a) Java NIO `WatchService` in ch. 6 — a
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
- [x] **Reconcile book-vs-repo divergences — OBSOLETE (duplicate).** Same ask,
      same ch. 19 example as the resolved "Book-vs-repo divergences" item above;
      no separate work existed. The systematic version of this idea — the full
      book↔repo diff the item above declared out of scope — is now the fourth
      pass's drift audit (§ 4.3, § 4.7).
- [x] **Renumber chapters to sequential 01–25 — DONE (2026-06-27).** The 06b /
      11b / missing-14 scheme read fine online (mdBook numbers by SUMMARY position,
      not filename) but the author's *numeric prose refs used filename numbers*, so
      several were latently wrong against the displayed numbers (e.g. "(Ch. 7)" for
      Datomic, which displayed as 8). Renamed all 19 affected files with `git mv`,
      rewrote every inter-chapter link target, and fixed the numeric text refs
      (ch.9 diagram, the construction-view cluster's 14/15/16 cross-links, the
      email-flow "(ch. 20)"). Verified: 114 links resolve, SUMMARY is 01..25,
      filename = displayed = referenced number, build clean. NB: chapter-number
      references *elsewhere in this checklist* predate the renumber. (Fourth-pass
      note: a *second* renumber followed on 2026-06-28, when `09-recipe-domain`
      was inserted after Datomic → chapters are now 01–26. Decoding old refs:
      Blocker/Polish/second-pass numbers use the original 06b/11b scheme — old 7
      → now 07+08, old 11 → now 13+14, old 12/13/15 construction cluster → now
      15/16/17, old 18 → 20, old 19 → 21, old 20 → 22, old 23 → 25. Third-pass
      numbers are current-minus-one from unit-testing onward (its 9 → now 10,
      13 → 14, 18 → 19, 20 → 21, 22 → 23, 25 → 26; chapters 1–8 unchanged).
      Completion notes written after a renumber may already use newer numbers —
      read each item in its own context. Fourth-pass items use current filenames
      throughout.)

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

---

## Fourth pass — full-manuscript audit: insertion seams, drift, structure (2026-07-03)

A fleet audit: an independent editorial read of every chapter plus front/back
matter (27 reads), four cross-cutting sweeps (narrative arc, voice, this
checklist itself, cross-references/self-descriptions), and three book-landscape
comparisons — every finding checked against the repo, and the top 30 findings
re-verified adversarially (27 confirmed, 1 partial, 2 refuted as off-target).
The headline: the three passes above LANDED. The voice is unified (zero
proscribed tells anywhere), every internal link, anchor, and image resolves,
SUMMARY matches the tree, and every substantive box above was done — several
done-but-unchecked; ticked retroactively in this pass. Comparative calibration:
the strongest Clojure web manuscript in the field (the slot has been vacant
since 2021) and mid-shelf-or-better in the build-one-real-thing genre across
languages; the differentiators (book-as-repo, applied Datomic, the
construction-view cluster, the production hardening) have no direct precedent.

What remains is below, in priority order. Items marked ⚠ carry one editor's
evidence — sometimes with that editor's own empirical check, noted inline — but
were NOT adversarially re-verified: confirm each against the repo before
editing.

### 4.1 A real repo bug (fix the code first, then the chapter)

- [x] **`requiring-resolve` does not return nil for a missing namespace — it
      throws `FileNotFoundException`** (nil happens only when the ns loads but
      lacks the var). Two production routes rely on the nil myth:
      `routes.clj:203-207` (`/dev/ws`) and `routes.clj:295-302`
      (`/dev/__trace-clear`, whose comment at line 294 states the myth
      verbatim) call it bare, so in a prod build — `dev/` and the trace ns
      absent — a GET to either throws an unhandled exception instead of 404ing,
      and there is no catch-all error middleware to absorb it (§ 4.5). The two
      *guarded* call sites show the fix: `dev-json-handler`
      (routes.clj:128-141, try/catch) and the `wrap-trace` resolution
      (routes.clj:345-349, property-gated + try/catch). Guard both routes,
      correct the routes.clj:294 comment and the "nil in prod / non-storm"
      phrasing in `dev-json-handler`'s docstring, then rewrite ch. 6's three
      statements of the myth (06-live-reload.md:34, 278, 289 —
      "`requiring-resolve` returning nil in production is the same guarantee
      viewed from the calling side") to the true mechanism: structural absence
      plus a guard that treats resolution failure as 404.
      **Done (2026-07-03).** Both routes guarded; the routes.clj comment and
      `dev-json-handler` docstring corrected; the myth rewritten *everywhere*
      the manuscript stated it — ch. 6 (the classpath paragraph, the
      §Wiring-the-route intro, and the listing, which now shows the guarded
      form the repo ships), ch. 13's base-layout bullet, ch. 16's
      §Serving-it sentence + listing comment, ch. 18's "Why
      `requiring-resolve`" box — plus `dev/trace.clj`'s ns docstring. Note
      ch. 6's *base-layout* listing and ch. 17's overlay guard already had the
      try/catch, so the correct form was in the book all along; only the route
      story had the myth. Verified empirically on a prod classpath (no `:dev`):
      the bare resolve throws `FileNotFoundException`, the guarded form yields
      nil → 404. `./lint` 0/0, `compile-strict` OK, 88 tests / 206 assertions
      0 failures, `mdbook build` clean.

### 4.2 Ch. 9 insertion seams (mechanical; a sequential reader hits contradictions)

The 2026-06-28 insertion of `09-recipe-domain` updated links, numbers, and the
root README, but missed prose that describes the book's own shape in words:

- [x] **Ch. 10's coda contradicts the chapter before it.**
      10-unit-testing.md:447 "Six chapters in, before a single feature exists"
      and :471 "no views, no styling, no authentication, no real domain" — one
      chapter after ch. 9 built the domain, rendered the recipe page, and
      tested it with this chapter's own fixtures. Rewrite the coda to own the
      new order, and align its "first movement" boundary with the primer's
      (the two currently disagree). **Done:** the heading is now "The scaffold,
      whole" (no numbered-movement claim left to disagree with the primer),
      the opener credits the domain's deliberate early arrival ("jumped the
      queue by one chapter so the Datomic argument could be spent while it was
      fresh"), and the close says what is true — the recipe model exists and
      is tested; nothing renders it yet.
- [x] **Afterword count.** afterword.md:3 "Twenty-five chapters later" — the
      book has 26. Commit 3793b84 fixed the recap paragraph and the README but
      missed this sentence. Bump it, or rephrase to avoid a count. **Done:**
      "Twenty-six chapters later."
- [x] **The primer's roadmap mis-maps the actual TOC.** 01-primer.md:35 places
      Playwright at the end of the dev-tooling movement, but e2e is ch. 22 and
      sits between auth (20-21) and admin (23) — its three specs ARE the auth
      flows. It also names same-tier peers (i18n, Tailwind) while silently
      skipping unit testing (10), the morph dispatcher (14), the construction
      view (16-17), and progressive enhancement (19) — under-selling two of
      the book's signature SSR-thesis chapters. Fix the mapping; give 14/19 a
      beat of their own. **Done:** movements recut to match the TOC — the data
      movement now carries the clock and closes on the test harness; the third
      movement carries the dispatcher, the inspector, the construction view,
      and the delivery matrix; the fourth opens with progressive enhancement
      and places Playwright with the flows its specs drive ("adds the features
      and proves them"). 10/14/16/17/19 all named.
- [x] **Ch. 8 argues Datomic from someone else's app.** 08-datomic.md:5 "For a
      SaaS that handles financial data, this is not a nice-to-have" — an
      adapted-origin seam in a book that versions recipes, one chapter before
      ch. 9 makes the same argument from the actual domain. Re-ground it in
      the recipe domain, or generalize it honestly ("any SaaS whose data is
      disputed, audited, or versioned"). **Done:** "For a SaaS whose product is
      the history of its data -- ours versions recipes; the same holds for
      finance, for audit, for anything disputed."
- [x] **`chapters/README.md` "What's covered" omits the book's most
      distinctive content** (lines 13-21): the morph dispatcher and the
      construction view are absent from a list that names Tailwind. Nothing
      false; add them. **Done:** added bullets for the construction view,
      recipe versioning from Datomic's history, and the morph dispatcher +
      progressive enhancement; E2E split into its own line.

### 4.3 Book↔repo drift — CONFIRMED (each item adversarially re-verified)

The covenant is "every listing is lifted from a server that runs; where the two
could drift, the book says so." These are the confirmed places it doesn't
currently hold — first the ones where the prose claim becomes *false* as
printed:

- [x] **Ch. 5 — the "fails loudly" claim is made true by code the listing
      elides.** The chapter's `resolve-keys` is single-arity and silently
      generates a random session key in ANY profile; the repo's is
      `[config profile]` and calls `require-prod-key!`
      (src/myapp/config.clj:27, 71, 79) — which is exactly what makes "a
      missing SESSION_KEY fails loudly in production" true. Show the guard
      (small, load-bearing, and it argues the book's own security stance) or
      reconcile the claim. **Done:** the listing now shows `require-prod-key!`
      and the profile-threaded `resolve-keys` verbatim from the repo (minus
      the signing-key branch, which the bullet now discloses arrives with
      ch. 20); the design bullet argues the fail-closed policy (restart
      logouts, multi-instance divergence) instead of asserting it.
- [x] **Ch. 5 — the route tree drops the `#'` the chapter's REPL promise
      depends on.** Chapter shows `["/" {:get handler/home}]`; the repo has
      `#'handler/home` (routes.clj:155), and the repo's own docstring says why
      ("Handler references use #' (vars) so hot-reload picks up changes
      without rebuilding the router"). As printed, the flagship
      evaluate-and-it's-live flow fails for the most common edit. Restore the
      `#'` and give the reason a sentence. **Done:** `#'` restored and the
      paragraph now explains the var-vs-value capture ("One character in
      there is load-bearing…"), forward-linking the live-reload chapter.
- [x] **Ch. 5 — config.edn drift hides the mechanism ch. 26 deploys with.**
      Chapter shows literal prod values (`:base-url :prod "https://…"`); the
      repo uses `#env` tagged readers (`:prod #env "BASE_URL"`, same for
      SMTP), and no chapter anywhere shows the `#env` form. Show it —
      env-driven production config is load-bearing for the deploy chapter.
      **Done:** listing now shows `#env "BASE_URL"` / `#env "SMTP_HOST"` /
      `#env "SMTP_FROM"` under `:prod`, the prose names the policy (dev =
      literals, prod = environment, injected by ch. 26), and the `:smtp`
      growth (`:tls`/`:user`/`:pass`) is disclosed.
- [x] **Ch. 4 — clj-kondo's exit codes are stated wrong, so the lint gate's
      described behavior is inverted** (the script as written fails on
      warnings too — kondo exits 2 for warnings, 3 for errors — while the
      chapter describes a warning-tolerant gate). Fix the description to match
      the correct script; the strictness is the brand. **Done — and the repo's
      own `lint` comments carried the same myth** ("We want warnings to be
      informational"), contradicting the gate one screen below. Verified
      kondo's real codes empirically (warning-only file → rc 2, errors → rc
      3); rewrote chapter prose + listing comments and the repo script's
      comments to the true, strict design: every linter is enabled at
      `:warning` because we fix what it flags, so any finding fails
      (`rc >= 2`). Behavior unchanged; `./lint` still 0/0.
- [x] **Ch. 3 — `createcerts.sh` as printed cannot run against the compose.yml
      as printed.** The printed script `cat`s `openssl.cnf` from its working
      directory while the printed compose mounts it at `/opt/openssl.cnf` with
      `working_dir: /certificates` — under `set -euo pipefail` the init
      container fails. The repo squares it with `SCRIPT_DIR=$(dirname "$0")`
      (certificates/createcerts.sh:3, 36). Align the listing. **Done:**
      listing now carries `SCRIPT_DIR` + `cat "$SCRIPT_DIR/openssl.cnf"`, a
      follow-up paragraph explains why (working dir is the volume; the config
      is mounted beside the script) and discloses the abridgment (`x509
      -text` inspection, JKS/PKCS12 exports live in the repo copy).
- [x] **Ch. 2 — the "second break" misdescribes the book's own islands.**
      02-positioning.md:43 "the platform's own element lifecycle handles setup
      and teardown" — the repo contains zero custom elements; lifecycle is the
      hand-rolled `controllers.js` registry driven by `DOMContentLoaded` +
      `dispatcher:morphed` (built in ch. 18). Same line: "a handful of
      hand-written ES modules … is all the client behavior we need" — plus one
      vendored third-party classic script, `static/idiomorph-0.7.4.js`. Two
      clause-level fixes; the argument survives both. **Done:** now "wired and
      unwired by a one-page controller registry that runs on first load and
      after every morph (built in the morph-dispatcher chapter)" and "a
      handful of hand-written ES modules -- plus one small vendored morphing
      library".
- [x] **Ch. 1 — two first-page overclaims.** 01-primer.md:35 "perfect
      Lighthouse scores enforced in CI" — lighthouserc.js pins accessibility +
      best-practices at 1 but floors performance at 0.95 (error) and leaves
      SEO at warn; ch. 25 is honest about exactly this (the jitter floor, "a
      100 is a contract") — borrow its clause. And 01:23 "a strict
      Content-Security-Policy go[es] on from the first day" — the CSP arrives
      in ch. 24, and ch. 5 itself defers it there; strict *compilation*
      genuinely is day-one, the CSP is not. Reword to what is true — it is
      just as strong. **Done (alongside the § 4.2 roadmap rewrite):** now
      "Lighthouse scores engineered to 100 and gated in CI" (01:35), and
      "Strict compilation goes on from the first day, and when the
      Content-Security-Policy arrives with the asset pipeline it arrives
      already strict -- forbidding `eval` and unauthorized inline scripts
      outright -- and neither is ever relaxed for convenience" (01:23).
- [x] **Flag-or-align sweep for silent elisions in chs. 3-4.** The covenant
      allows elision said out loud; these are silent: ch. 3's Dockerfile walk
      omits packages its own prose cites (`tcpdump`) and the zprint binary
      ch. 4's formatter depends on; ch. 3's Caddyfile listing lacks the
      security-header block ch. 24 adds (caddy/Caddyfile:5-16) — wants the
      "grows in ch. N" note the book uses elsewhere; ch. 4's `.zprintrc` and
      `.clj-kondo/config.edn` listings omit repo entries (`:comment {:wrap?
      false}` + fn-map; the `:discouraged-var` datomic.api/pull block and
      `:lint-as`), and its build.clj listing strips the docstrings the repo
      carries — three sections after the chapter argues every public function
      needs one. One pass: add excerpt notes or align. (Cosmetic, same pass:
      ch. 3's cert subject strings and NSS nicknames differ from the repo's.)
      **Done:** ch. 3 — `tcpdump` added to the printed apt list (its prose
      cites it), an excerpt note names the rest (`strace`/`iotop`/`nethogs`/
      `valgrind`/plumbing) and the zprint layer; Caddyfile lead-in now says
      what ch. 24 adds. Ch. 4 — `.zprintrc` listing gains the generic
      `:comment {:wrap? false}` and `"if" :arg1` rows plus a growth note for
      the app-specific `:fn-map` rows (defview, db/transact*, log/*); the
      kondo-config intro discloses the repo's `:discouraged-var` +
      `:lint-as` blocks; build.clj's def/clean/uber docstrings restored in
      the listing. Cert cosmetics unified in the REPO's favor of coherence:
      the repo's two `-subj` lines were internally inconsistent leftovers
      (`C=CH` with Dutch localities, two different ST/L pairs) — normalized
      both to `/C=NL/ST=Utrecht/L=Amersfoort/O=myapp/...` and aligned the
      chapter (O= and CN now lowercase `myapp`), plus the NSS nickname
      (`myapp Dev CA`) and the `$CERT_FILE` skip message. Verified: `./lint`
      0/0, both shell scripts parse, `mdbook build` clean, suite 88/206
      green.

### 4.4 Structural debts — DONE (2026-07-03, per author decisions)

*(Tenancy CASHED, not softened: ch. 19 gains "Where ownership is enforced" —
the `entid-owned` family in prose, the middleware/handler/data-layer argument,
the fork boundary as authority-vs-visibility — and ch. 1 defines the model
(tenant = user, one schema, isolation by ownership) where the promise is made;
ch. 9's forward pointer is now simply true. Cluster BANKED: ch. 16's N+1
passage restaged truthfully (the screenshot's chip is `all-recipes`' per-card
pulls; the `find-user-by-email` double-lookup is told as the bug the tool
caught and `wrap-current-user`'s current shape fixed), and three feature
chapters now use the tools in-situ — ch. 23 reads the funnel queries' basis-t
in the trace view, ch. 21 watches a replayed link refused at the CAS gate,
ch. 19 watches a reorder transact position-only pairs. Eviction deferral
RESOLVED: `full-navigation?` + the `clear-recordings!` call now live in
ch. 16's `record-page` listing as its fourth helper; ch. 17's back-pointer was
already correct once ch. 16 delivered.)*

- [x] **Cash the multi-tenancy promise — the single highest-leverage fix.**
      Sentence one of both preface and primer promises "a multi-tenant SaaS";
      the manuscript's delivery is one schema attribute and two `entid-owned`
      call sites. Ch. 9 defers the real treatment ("built in full … alongside
      the question of where in the stack ownership should be enforced") to
      ch. 19 — which delivers one docstring and one call inside `reorder!`.
      The repo has the goods (src/myapp/db/core.clj § tenant isolation
      primitives, including the cross-tenant Forbidden guard). Either build
      the `*-owned` family in prose where ch. 9 points and argue the
      enforcement-locus question, or define the claim where it is made
      (tenant = user, shared schema, no orgs) so the promise matches the
      delivery. Do one of the two; today the pointer dangles.
- [x] **Bank the construction-view cluster downstream (do NOT cut or trim
      15-17 — the keep-steer stands).** Chapters 19-26 never once use the
      inspector or construction view on the features they build; "you build
      the rest of the application with the tools already in hand" (01:23) is
      asserted three times (chs. 1, 17, afterword) and dramatized zero times.
      Thread two or three brief in-situ uses through the feature chapters —
      natural fits: ch. 23's cross-database funnel queries read in the trace
      view; ch. 21's CAS verify failure surfaced via `/dev/__last-error`;
      ch. 19's reorder gesture in a region-scoped trace. ⚠ And repair the
      cluster's one concrete payoff first: ch. 16's N+1 example
      (`find-user-by-email` twice on /admin) is reported fixed in the repo
      (`wrap-current-user`'s docstring says the lookup is cached), so "it
      immediately surfaces a real one" will not reproduce for a reader —
      confirm, then either restage it or narrate it as the bug the tool caught
      and the fix it motivated.
- [x] **Resolve the ch. 16 ↔ 17 mutual deferral of the eviction mechanism.**
      Ch. 16 promises the full-navigation eviction (`Sec-Fetch-Mode` check +
      `clear-recordings!` in `record-page`) to ch. 17; ch. 17 points back to
      ch. 16; the code appears in neither, while both chapters' memory-bounding
      argument leans on it. Put it in exactly one.

### 4.5 The afterword's left-out list — DONE (2026-07-03)

*(The 500 handler was BUILT, per author decision: `wrap-errors` in routes.clj,
innermost so the error page exits through the CSP/cache layers;
`:error/server-error` in both locales; a regression test in security_test
(suite now 89 tests / 212 assertions); a passage in ch. 21 extending the
generic-error discipline to unanticipated failures; ch. 5's middleware map
names it. The transactor gets an honest paragraph in ch. 8 ("not just
PostgreSQL... the one piece of production infrastructure the dev loop cannot
rehearse"); the afterword gains the backup-and-restore sentence and now says
"stdout logs".)*

The list (billing, horizontal scale, metrics/tracing, background jobs, real
email) is the right device and buys reviewer trust. A skeptical reviewer of
"production-grade" will find exactly four more, currently unacknowledged:

- [x] **No catch-all exception middleware / styled 500 anywhere in src.** An
      uncaught handler exception falls through to http-kit's default — and
      § 4.1 documents a route that currently throws in prod. The error-page
      view exists; wiring it is a short middleware plus a paragraph. Build it
      (more in character), or add it to the left-out list.
- [x] ⚠ **The Datomic transactor is never mentioned.** Ch. 8 presents the
      Peer + `datomic:sql://` production story as "your app connects directly
      to the storage backend" with no word on the transactor + PostgreSQL
      topology that story requires — while chs. 21/23 and the afterword then
      presuppose one ("Same transactor, same underlying PostgreSQL storage").
      One honest paragraph in ch. 8 or ch. 26.
- [x] **Backup/restore: zero sentences in 26 chapters.** One sentence in the
      afterword's list suffices.
- [x] **"Observability stops at logs" (afterword:19) leans on logging the book
      never builds** — no logging config, no logging discussion in any
      chapter. Ship the minimal config or soften the claim to what exists
      (stdout + CSP reports).

### 4.6 Voice residue — DONE (2026-07-03)

*(Ch. 3's VS Code close rewritten as consequence prose ("Accepting is the
whole procedure... there is no sequence you performed"); the six
code-restating lists folded to argued prose (chs. 8, 11, 20, 22, 23, 26), plus
ch. 21's three-outcomes and ch. 23's data-flow closes; the "Let us…" pair,
the walkthrough connective tissue in chs. 10-11, the bare enumerated-note
labels, the three sign-off tics, and the `project` heading all normalized.
Callout taxonomy: AUTHOR DECISION — the labeled Decision/Trade-off/Lesson
device is declared an idiom of the construction-view arc, where design lessons
are the subject; no house-wide extension. The "Why X?" sections in 18/22
stay, as argument.)*

The register war is won; this is mop-up. Notably the construction-view
chapters are among the cleanest — the thesis chapters carry the house voice
best.

- [x] **Ch. 3's VS Code/Calva close is the largest surviving tutorial block**
      (03-devcontainer.md:484-495): a numbered install sequence plus
      keybinding bullets. The chapter's own argument (zero-memory onboarding;
      the environment lives in committed files) already contains the
      essayistic version — fold the steps into it; keybindings to the repo
      README.
- [x] **Six numbered lists restate the code just shown** — the exact
      proscribed pattern: 08-datomic.md:293, 11-i18n.md:183,
      20-auth-tokens.md:117, 22-e2e-testing.md:254, 23-admin-dashboard.md:616,
      26-ci-cd.md:406 (plus its neighboring stage paraphrases). Keep only
      items carrying load the listing can't (ch. 20's nonce rationale); fold
      the rest. The converted form to imitate is ch. 14's "A few details worth
      pulling out, because they are what make this robust rather than a toy:".
- [x] **Small-tic sweep, one pass:** two "Let us…" survivors (04:92, 18:315);
      walkthrough connective tissue in chs. 10-11 (11:7 "This chapter walks
      through the entire i18n implementation:", 10:95 "Usage in tests:",
      10:312 "The key flags:", 10:347 "You can run it with:"); bare
      enumerated-note labels → the converted idiom ("Two decisions are worth
      dwelling on") at 21:70, 21:364, 25:65, 23:212, 04:197, 03:201;
      single-sentence tics 06:178 "Clean and simple.", 12:135 "To confirm it
      works…", 26:423 "worth every second"; and backtick `project` in the
      16-construction-view.md:490 heading.
- [x] **AUTHOR CALL — settle the callout taxonomy.** All 8 labeled
      "Decision — / Trade-off — / Lesson —" callouts live in chs. 15-17;
      eight other chapters use title-only blockquote asides; chs. 18/22 use
      bold "Why X?" Q&A paragraphs; twelve chapters use none (chs. 7/9 argue
      inline — arguably the purest form). Either declare the labeled device an
      idiom of the construction arc (recommended: cheap, defensible — that arc
      is where design lessons are the subject) or extend it house-wide (ready
      candidates: 07's atom trade, 09's strings-as-diff, 18's design-decisions
      close, 21's three refusals, 24's hash-vs-nonce). Decide either way; keep
      18/22's "Why X?" sections regardless — they are argument, not recap.

### 4.7 Per-chapter technical flags — ALL CONFIRMED AND FIXED (2026-07-03)

A second verification fleet (one read-only agent per flag, each running the
empirical check its item calls for) confirmed all fourteen: 14/14 CONFIRMED,
0 refuted. Highlights of the empirical runs: the ch. 21 scanner failure was
reproduced live against the running app (a cookie-free curl of a fresh verify
URL got a 302 + session; the second GET got the error page); ch. 9's
`:db/retractEntity` semantics were proven with an in-memory Datomic run
against the real schema (the fork's inbound ref is retracted; it becomes an
original); ch. 10's router listing was shown to throw at load and the fixed
form to build; ch. 15's transcript was re-run under the repo's tools.reader
1.5.0 (end-column is 20); ch. 22's suite was run under Playwright's real
default parallelism (2 workers, 6 tests green, no beforeEach anywhere);
ch. 18's CSS was proven byte-identical dev-vs-prod (same tailwind invocation)
while JS is not (esbuild minify); ch. 23's once-per-document module semantics
were demonstrated in node. All fixes below are applied; deviations from the
original item text are noted inline. Verified after: `./lint` 0/0,
`compile-strict` OK, 88 tests / 206 assertions 0 failures, `mdbook build`
clean. Two fixes touched the repo, not just the manuscript: `delete!`'s
docstring in src/myapp/recipe/core.clj carried the same dangling-ref
falsehood as ch. 9 (corrected), and ch. 22's chapter listing now carries the
repo docstring verbatim.

- [x] **Ch. 21 — the verify link is a state-changing GET, and the chapter
      calls it an idempotent read.** 21-auth-email-flow.md:417
      "State-changing operations … are POST. Idempotent reads (sent page,
      verify link, welcome page) are GET" — but `/auth/verify` CAS-consumes
      the nonce, creates users, and sets a session. GET-by-necessity (email
      clients don't POST) is fine; "idempotent" is not. Unconfronted
      consequences: corporate mail scanners and link prefetchers (Outlook
      SafeLinks et al.) that GET the link and burn the one-shot nonce before
      the user clicks — the canonical production failure of magic-link
      auth — and login CSRF via an attacker-supplied verify URL. The fix is
      prose (name the trade-off and the standard mitigations: an interstitial
      confirm-POST, or scanner-tolerant nonce semantics). The audit's most
      consequential technical flag.
- [x] **Ch. 9 — the fork-after-delete account is wrong about Datomic
      semantics.** 09-recipe-domain.md:242 says a deleted recipe's forks keep
      a dangling `:recipe/forked-from` ref that `lineage` tolerates — but
      `:db/retractEntity` retracts *inbound* refs too, so the ref is retracted
      in the same transaction and the fork silently becomes an original. (The
      cycle-guard prose survives; the dangling-ref story doesn't.) High
      confidence; a 3-line REPL check settles it, and the true behavior is
      arguably the nicer fact to write.
- [x] **Ch. 10 — the route-testing listing throws at load against the real
      route tree** (flagging editor ran it): `(ring/router routes/routes)`
      without `{:conflicts nil}` fails on the static-vs-dynamic conflicts
      (`/recipes/new` vs `/recipes/:id`) the repo's own router tolerates
      (routes.clj:356).
- [x] **Ch. 14 — "exactly one listener hangs on `dispatcher:morphed`" is
      falsified by the repo's own toast.js** (spot-verified this pass:
      src/myapp/web/toast.js registers two more `dispatcher:morphed` listeners
      plus two `DOMContentLoaded` handlers — the very hand-rolled pattern the
      chapter says no behavior uses). Either migrate toast.js to the registry
      or amend the claim ("one structural listener; toasts predate the
      registry and show the pattern it replaces").
- [x] **Ch. 22 — the printed `test.beforeEach` does not exist in e2e/**
      (spot-verified: zero hits), and the unscoped `DELETE /test/emails` it
      recommends is what the handler's own docstring calls "only safe in
      serial runs", while two spec files run in parallel workers by default.
      Reconcile the listing and the concurrency story.
- [x] **Ch. 25 — two drifts:** the app-layout listing calls a `bottom-tabs`
      function that exists nowhere in src (spot-verified; views.clj:205-218
      differs throughout), and the semantic-HTML section claims the a11y gate
      enforces `<main>`/`<nav>` landmarks — in the Lighthouse 12.x that
      @lhci/cli 0.15 ships, `landmark-one-main` is weight-0/hidden and no
      scored nav audit exists, so the minScore-1 gate passes a page missing
      both (flagging editor checked the audit weights). Re-ground the section
      in audits the gate actually scores.
- [x] **Ch. 26 — the deploy script violates the privilege model beside it:**
      it runs bare `systemctl stop/start/restart myapp` (26-ci-cd.md:502ff)
      and writes `/opt/myapp/*.jar`, while the prose grants the deploy user
      exactly two sudo rights and write access to the static directory only.
      Add the sudo invocations and the jar-dir ownership to one of the two.
- [x] **Ch. 7 — the `with-redefs` sentence is wrong twice:** it cannot rebind
      a Java static (only Clojure vars — you'd redef a wrapper), and it is not
      thread-local — it mutates root bindings globally (`binding` is the
      thread-local one), so its parallel-test failure mode is the opposite of
      the one stated. The conclusion (own the clock) survives; the supporting
      sentence doesn't.
- [x] **Ch. 11 — the printed home handler shows the shape the repo's docstring
      documents as an infinite-redirect bug** (the repo's `home` is a cond
      over user state; the chapter shows the naive session-check redirect),
      plus translation-file listings that drift beyond trimming. Align.
- [x] **Ch. 13 — "legal documents are written in Markdown" + `legal-page`:**
      neither the function nor any legal Markdown exists in the repo; Markdown
      renders recipe descriptions only, and the terms pages are Hiccup. Cut or
      reconcile.
- [x] **Ch. 15 — the keystone REPL transcript prints a wrong end-column**
      (`:end-column 21` vs actual 20 for the 19-char form; flagging editor ran
      tools.reader 1.5.0). One character, but it is the chapter's
      demonstrate-the-model moment.
- [x] **Ch. 17 — the intro's finished-tool walkthrough describes two
      interactions the shipped overlay lacks** (an icicle header toggle;
      Alt+click an entity id → flow card with a `d/history` link). Trim the
      tour to the tool that ships, or name them as the repo's next
      affordances.
- [x] **Ch. 18 — "byte-identical" is false for JS:** prod esbuild-minifies
      every ESM module; dev serves source. The invariant that holds is
      same-source, same-graph — CSS is byte-identical, JS is not. Reword the
      boxed claim ("One artifact, two deliveries" survives).
- [x] **Ch. 23 — the poller motivation misstates ES module semantics:** a
      module-scope `setInterval` evaluates once per document, so repeated
      morph navigations into /admin cannot stack intervals. The real
      naive-shape defects (one interval polling every page forever; stacking
      from per-morph wiring without disconnect) make the same case truthfully.

### 4.8 Positioning & packaging — DONE (2026-07-03)

*(Ch. 14 gains "Decision — why not htmx?" — scope, CSP, ownership; idiomorph
named as the one piece of that family we vendor; Biff/Kit as the pre-made
answers whose trade is the inverse of ours. Ch. 2 names Biff/Kit as the
Clojure community's converged default, making the book's position mainstream
rather than contrarian. Ch. 26 gains "The port, made concrete" — the whole
GitHub Actions diff in one blockquote, plus the trust-model non-port. Ch. 8
gains the "If you are on PostgreSQL" costing sidebar. And three drawn,
theme-adaptive inline-SVG figures now exist: the timeline slice and the
two-parentings trees in ch. 16, the delivery matrix in ch. 18.)*

- [x] **Name the living competition.** Kit, Biff (SSR+htmx by design), and
      htmx are what every 2026 reader has been told to use first; they deserve
      the same named options-analysis Postgres and React get — a page in ch. 2
      or in ch. 14's dispatcher rationale ("why we build the couple hundred
      lines htmx would hand us" — the book has a real answer: the CSP, the
      controller contract, owning the morph boundary; say it as crisply as
      Datomic-vs-Postgres). Without it, from-scratch reads as NIH to exactly
      the readers most likely to buy the book.
- [x] **Ch. 26 — prove the portability claim.** The chapter says the pipeline
      ports almost unchanged to GitHub Actions; a two-page sidebar or appendix
      showing the ported workflow would neutralize the Forgejo/Podman niche
      objection, and "(GitHub Actions-compatible)" somewhere discoverable
      helps the TOC sell it.
- [x] **Ch. 8/9 — a "PostgreSQL seam" costing.** The book's own method (we
      considered X; here is what Y costs) applied to its riskiest dependency:
      one sidebar naming exactly what a relational reader loses (history,
      as-of, ch. 9's version machinery) and what they would rebuild. Not a
      change of choice — a costing of the alternative.
- [x] **AUTHOR CALL — figures for the hardest machinery.** The book's only
      visuals are screenshots and ASCII trees. Timeline slicing,
      lexical-vs-temporal re-parenting, and the per-edit delivery matrix are
      the three places a drawn figure would carry real load. The
      didactic-padding line is nearby, but a data-flow figure for the tracer
      is arguably load-bearing, not padding.
- [x] **AUTHOR/PUBLISHER CALL — title/subtitle.** "Building a Clojure/Datomic
      SaaS from Scratch" is honest and searchable but hides all three
      differentiators (server-rendered / no framework; hardened from the first
      commit; the book is the repo). Candidate subtitle: "Server-Rendered,
      Framework-Free, and Hardened from the First Commit." **Done (author
      pick: keep the title, add the subtitle):** applied to book.toml's
      `title`, the root README, and chapters/README.md.

### Checklist hygiene — done in this pass

- [x] Ticked the done-but-unchecked boxes above (eleven Blocker-1 items, both
      Blocker-2 splits, second-pass "Scaffold three steep drops"), marked the
      duplicate reconcile item obsolete, and extended the renumber caveat to
      cover the second (ch. 9) renumbering.

Standing steers, reaffirmed so this pass isn't "fixed" against them: no
exercises, the density, and the opinionated voice are identity (second pass);
chs. 15-17 stay at full strength (Blocker 2) — § 4.4 asks that the rest of the
book *use* them, not that they shrink; and incidental code living in the repo
remains the design — § 4.3 exists because the covenant's other half ("where
the two could drift, the book says so") is currently unpaid in the places
listed, not because elision is a defect.
