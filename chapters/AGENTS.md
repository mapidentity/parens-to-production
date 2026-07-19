# AGENTS.md — the book's mdbook source (chapters/ = book `src`)

> Every `.md` here is a chapter of *Parens to Production*, rendered by mdbook. The root `book.toml` sets `src = "chapters"`; there is no code here, only prose that documents the companion app's real code. Edit chapters; keep them in lockstep with `SUMMARY.md` and with the source under `/workspace/src`.

## Key files
- `SUMMARY.md` — the table of contents AND the build manifest. `# Part Name` lines are part dividers; each `- [Title](NN-slug.md)` line is one chapter, in reading order. **A `.md` not listed here is not in the build.**
- `NN-slug.md` — one chapter. The `NN` prefix is the displayed chapter number; `01`–`49` are numbered, in `SUMMARY.md` order.
- `README.md` — the `[Introduction]` prefix page (also the dir's readme). `preface.md`, `afterword.md` — the un-numbered prefix/suffix pages.
- `images/` — the 6 captured screenshots referenced as `![alt](images/foo.png)`. Regenerating one is a real procedure (headless Chromium capture), not a quick edit; don't invent new image refs.
- `../book.toml` — global config: navy theme, `smart-punctuation`, search, `fold`. mdbook has **no per-file front matter**; all config is here.

## Conventions / rules (match the existing chapters exactly)
- **First line is `# Title`**, and it must equal the link text in `SUMMARY.md`. Change one, change both.
- **Feature chapters end with `## Trade-offs & limitations, in one place`** (that exact heading) — an honest bulleted list of costs/absences. If you add a feature chapter, add this section.
- **"Drilled or not claimed."** Back every behavioral claim with a run/test/measurement, or name it as reasoned in the existing voice: *"reasoned, not fault-injected"*, *"named here and not drilled"*. Do not upgrade a reasoned claim to a measured one you can't show. The book is deliberately honest that `datomic:mem` makes excision/backup no-ops in the lab — keep that honesty.
- **Best engineering over teachability.** Never simplify a design in prose to make it explainable, and never describe a simpler design than the repo ships. When a listing is abridged, mark elision with `,,,` and point at the real file (*"the complete, runnable file is `src/myapp/web/trace-overlay.js`"*).
- **Prose maps to real code.** Quote real ns/fn/file names that exist under `/workspace/src`, `/workspace/static`, `/workspace/test` — verify before citing; do not invent APIs.
- **Cross-reference by relative link:** `[the morph-dispatcher chapter](15-morph-dispatcher.md)` or `[chapter 15](15-morph-dispatcher.md)`. mdbook rewrites `.md`→`.html`. Prose also says "chapter 15" in running text — both forms must track the real number.
- **Voice:** opinionated, declarative. Dashes are `--` in source (smart-punctuation renders them). AVOID overusing the AI-tells the author flagged — em-dash density, "load-bearing", "not X but Y", "exactly", piled-on intensifiers — but do NOT flatten the voice or swap a precise word for a tic-avoiding synonym that changes the meaning.
- **Fenced code is language-tagged** (` ```clojure ` dominates; also `javascript`, `bash`, `yaml`, `css`). Keep the tag.

## Gotchas
- **Renumbering a chapter is a multi-edit.** The filename encodes the number, so a renumber means: rename the file, update its `SUMMARY.md` line, AND fix every inbound `NN-slug.md` link and every "chapter NN" prose mention across all chapters. Grep both forms before you move anything.
- **A chapter missing from `SUMMARY.md` builds silently to nothing** — no error, it just isn't in the book. Broken relative links likewise 404 rather than fail the build; there is no link-checker in CI. Verify link targets exist after editing.
- **`docs/` (sibling, outside `chapters/`) is planning material and is NOT in the book build.** Don't put chapter content there, and don't cite it from a chapter.
- Local mdbook is `0.4.40`; CI (`.github/workflows/deploy.yml`) pins `0.4.43`. Stick to features both support.

## Running / testing what's here
- Build (from repo root): `mdbook build` → output in `./book`. Live preview: `mdbook serve` (or `mdbook watch`).
- The Clojure gates (`./reformat`, `./lint`, `clojure -X:test`, `compile-strict`) do NOT touch the book — but code you cite must still be real and pass them. When a claim rests on behavior, run the corresponding test/REPL/measurement rather than asserting it.

## See also
- Order & inclusion: `SUMMARY.md`. Global render config: `../book.toml`. Publish path: `../.github/workflows/deploy.yml`.
- The code these chapters document: `/workspace/src/myapp/**`, `/workspace/static/**`, `/workspace/test/myapp/**`. There is no meta-chapter about authoring the book itself; this file is that guidance.
