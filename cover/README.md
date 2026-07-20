# Cover art

Two finished cover directions for *Parens to Production*. The `.svg` is the
master (vector, edit this); the `.png` is a 2400×3600 render (2:3, ~book
proportion). On-brand palette: slate-navy ground `#0b1220`–`#18243f`, warm
off-white `#fafaf9`, indigo `#4f46e5`, amber `#f59e0b`/`#d97706` — the same
indigo/amber the app and the mdBook site use.

- **`parens-to-production--lineage.*`** — the version-lineage direction. A
  fork-and-merge DAG (the app's own `:recipe/forked-from` graph) climbs to an
  amber *current* version that ships up into the title. Says the book's thesis —
  "the database already knows" — and could be no other book. Most distinctive.
- **`parens-to-production--monolith.*`** — **the cover.** The title as one
  justified mass (both words tracked to a common measure), held inside a pair of
  parentheses (the Lisp pun made structural) centered on the title's optical
  middle, crowned by the fork-mark, footed by `;; drilled or not claimed`. Clean,
  austere, strongest at thumbnail.

Both are self-contained SVG (system fonts, no external refs). For print, set a
licensed display face in place of the Helvetica/Arial stack and re-render at the
printer's dpi — the vector scales losslessly.

## Re-render to PNG

The SVG renders in any browser as-is. To rasterize headless (as these PNGs
were), load the SVG in a page at a 1200×1800 viewport and screenshot at 2×; a
swiftshader-flagged Chromium works where GPU rasterization is unavailable.
