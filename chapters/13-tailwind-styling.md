# Styling with Tailwind CSS

You are about to build the view layer in Hiccup -- vectors of tags and attributes that render to HTML. Those views need to look like something. Before we get into markup, we need a styling system: a way to give every page a consistent look without inventing a parallel vocabulary of class names and a sprawl of CSS files that drift out of sync with the markup they style.

This chapter sets that up. It is small and self-contained. All it needs is a running web server and the project's `static/` directory. The production story for styles -- content-hashing the stylesheet, immutable caching, the Content-Security-Policy -- belongs to [the asset pipeline chapter](29-asset-pipeline.md); here Tailwind produces a stylesheet your views can use as you write them.

## Why Tailwind in a server-rendered Clojure app

The problem with styling a server-rendered app is the same one every styled app has: where does the CSS live, and how do you keep it from rotting? The options:

- **Hand-written CSS, organized by a convention like BEM.** You write `.recipe-card__title--featured` in a `.css` file and the matching class in your markup. (In a Clojure shop you might author that CSS as data with [Garden](https://github.com/noprompt/garden) rather than writing a `.css` file by hand -- nicer to write, but the same trade-offs apply, since the styles still live apart from the markup.) This works, but it asks you to invent and remember a naming system, and the CSS lives in a separate file from the markup it styles. The two drift: you delete a component, the styles linger; you rename a block, half the selectors go stale. There is no compiler telling you a class is dead.
- **CSS-in-JS.** Not applicable. There is no JavaScript runtime rendering these pages (the HTML comes out of Clojure), so a styling approach that lives inside a React component tree has nothing to attach to.
- **A component CSS framework (Bootstrap and friends).** You get pre-built components fast, but you also adopt the framework's look, its class taxonomy, and its opinions. Customizing past the defaults means fighting the framework, and you ship a lot of CSS you never use.

Tailwind wins here because it inverts the problem. Instead of a separate stylesheet full of names you invent, you compose styling from a fixed vocabulary of utility classes written directly in the markup: `[:div.mt-4.flex.items-center ...]`. The styling is co-located with the Hiccup that it styles, so the two cannot drift -- delete the element and its styling goes with it. There is no class name to invent, and no separate CSS file to maintain.

Crucially for this stack, Tailwind does not care where the class names come from. It generates CSS by scanning your source files for class names. It does not need to understand JSX, ERB, or Hiccup -- it scans a directory, finds the utility classes you used, and emits the CSS those classes need and nothing more. That makes it a perfect fit for server-rendered HTML: there is no JavaScript build pipeline for your *styles*, just one CLI that reads source and writes one CSS file.

That scanner is also where Tailwind's own cost lives, and it deserves naming alongside the costs above. The scan is textual: a utility exists in the output only if its class name appears somewhere in the source as a complete literal string. Build one dynamically and the scanner cannot see it. `(str "bg-" (if urgent? "negative" "positive"))` reads like ordinary Clojure, but Tailwind emits nothing for it, the element silently renders unstyled, and no compiler or test flags the gap. The discipline that follows is to branch between whole names, never assemble fragments: `(if urgent? "bg-negative" "bg-positive")` works, because both literals sit in the source for the scanner to find. (Tailwind v4 can force a name into the output with `@source inline(...)`, but a one-line coding rule is cheaper than maintaining an exception list.)

The whole tool is also a **command-line compiler**: no PostCSS plugin chain, no `tailwind.config.js`, no runtime dependency. You run the CLI, it produces a CSS file, and the app serves that file; nothing about Tailwind ends up in the served page except plain CSS. How the CLI reaches your PATH is a packaging detail with two answers. Tailwind publishes a standalone binary for machines without Node, but the devcontainer from [the devcontainer chapter](03-devcontainer.md) already carries Node, so it takes the ordinary route instead: `npm install -g tailwindcss @tailwindcss/cli`, in the same layer that installs Playwright, with the `tailwindcss` script symlinked into `/usr/local/bin`. Same tool, same flags, either way; outside the container, pick whichever install is easiest on your machine.

## The input file: Tailwind v4 with design tokens

Tailwind v4 changed how configuration works. Instead of a `tailwind.config.js` file, everything lives in CSS using `@theme`, `@import`, and `@source` directives. One less JavaScript config file to maintain.

Here is the top of `input.css`:

```css
@import "tailwindcss" source("./src");

/* Keep the dev-only inspector's JS out of Tailwind's content scan (see note below). */
@source not "./src/myapp/web/inspector.js";

@view-transition {
  navigation: auto;
}

/* (two html-level scroll rules elided; see input.css) */

@font-face {
  font-family: "Geist";
  src: url("/fonts/GeistVF.woff2") format("woff2");
  font-weight: 100 900;
  font-style: normal;
  font-display: swap;
}

@theme {
  --font-sans: "Geist", ui-sans-serif, system-ui, -apple-system, sans-serif;

  --color-primary: #4338ca;
  --color-primary-vivid: #4f46e5;
  --color-chrome: #292524;
  --color-accent: #d97706;
  --color-surface: #ffffff;
  --color-surface-subtle: #fafaf9;
  --color-border: #e7e5e4;
  --color-text-primary: #0f172a;
  --color-text-secondary: #78716c;
  --color-positive: #047857;
  --color-negative: #e11d48;
  --color-warning: #92400e;
}
```

Working down the file:

**`@import "tailwindcss" source("./src")`** -- This tells Tailwind v4 to scan the `./src` directory for utility classes. It will find class names in your `.clj` files, inside Hiccup vectors like `[:div.mt-4 ...]` or `[:div {:class "flex items-center"} ...]`.

**`@source not "./src/myapp/web/inspector.js"`** (with a matching line for `trace-overlay.js`) -- A targeted exclusion. The dev-only source inspector and the construction-view overlay are JS files under `src/`, and Tailwind's scanner would otherwise extract the token `resize` from their `addEventListener('resize', ...)` calls and emit a stray `resize` utility. Both files carry that token, so both must be excluded (dropping only one still leaks the utility), and since these dev-only scripts never hold real Tailwind classes, excluding them costs nothing in production.

**`@view-transition { navigation: auto }`** -- Opts the site into the browser's native cross-document view transitions, so a full server-rendered navigation cross-fades instead of flashing white. This is the no-JavaScript baseline of the app's page-transition story: it works on a plain link click with no script at all. The morph dispatcher ([chapter 15](15-morph-dispatcher.md)) layers smoother in-place updates on top, and the full stylesheet adds `::view-transition` rules and a `prefers-reduced-motion` guard. Where a browser does not support it, the directive is simply ignored and navigation behaves as before.

**`@font-face` for Geist** -- Geist is a variable font, so a single `.woff2` file covers all weights from 100 to 900. `font-display: swap` shows a fallback immediately, then swaps to Geist once it downloads.

**`@theme` block** -- Design tokens as CSS custom properties. Once declared, you use them directly in utilities: `bg-primary`, `text-text-secondary`, `border-border`. The naming is deliberately semantic (`surface`, `chrome`, `accent`) rather than color scales like `indigo-600`. A focused app has a small, fixed palette; you do not need eleven shades of every color.

Below `@theme` the file carries plain CSS that does not map cleanly to utilities: a `.legal-content` block for rendering markdown content (recipe descriptions today; named for the legal pages it could also serve) with proper typography, interaction transitions (press feedback, card hover lift, a `details[open]` chevron rotation), focus-visible outlines for keyboard navigation, and the `.diff-add` / `.diff-del` styles used by the recipe version diff. A slice of it shows the division of labor, because the claim is narrower than "Tailwind for everything": it is Tailwind for the *utility-shaped* styling, and a small amount of hand-written CSS for the rest:

```css
/* Press feedback -- one rule, applied by selector rather than repeated
   on every clickable element in the markup. */
button, a, [role="button"] { transition: transform 100ms ease-out, color 150ms ease-out; }
button:active, a:active, [role="button"]:active { transform: scale(0.97); }

/* Visible focus rings for keyboard navigation, keyed to the theme token. */
button:focus-visible, a:focus-visible, select:focus-visible, input:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

/* Recipe version diff -- semantic classes the diff renderer emits. */
.diff-add { background-color: #ecfdf5; color: var(--color-positive); }
.diff-del { background-color: #fff1f2; color: var(--color-negative); text-decoration: line-through; }
```

Each of these belongs outside the utility system for a concrete reason. The press/focus rules are *cross-cutting*: they apply to every button and link on the site, so a selector states them once where a utility class would repeat the same string on hundreds of elements (and silently miss the next one). The `.diff-add`/`.diff-del` classes are *semantic*: the diff renderer emits `class="diff-add"` from the server, and a name that says what the row *is* outlives whatever colors we paint it. And note both still reach into the `@theme` tokens (`var(--color-primary)`, `var(--color-positive)`), so even the hand-written CSS shares one palette with the utilities. The token block is the source of truth; the utilities and the plain CSS are two ways of spending it.

It is worth admitting what the semantic classes cost, because it is the very thing this chapter docked BEM for: `.diff-add` lives in a file separate from the markup that emits it, so delete the diff renderer and the rules linger, with no compiler to flag them. The trade is justified only because the set is tiny, cross-cutting, and genuinely semantic -- the narrow cases where a hand-written class earns its keep. Each such class is a drift risk taken on with eyes open, not a licence to grow a parallel stylesheet beside the utilities.

## Extending the tokens, and the responsive prefixes

The views chapter leans on two more mechanisms without stopping to explain either, so they belong here.

The first is **adding a token**. There is no config file to touch and no plugin to register -- you declare a custom property in `@theme`, and Tailwind generates the matching utilities for you. Add a brand color:

```css
@theme {
  --color-brand: #7c3aed;
  --spacing-gutter: 1.5rem;
}
```

and `bg-brand`, `text-brand`, and `border-brand` exist immediately, as do `p-gutter`, `gap-gutter`, and `mt-gutter`. The utility *family* follows from the token's prefix: a `--color-*` token feeds every color utility, a `--spacing-*` token feeds padding, margin, and gap. That is the whole extension model -- name a value once, use it everywhere, and the dead-class problem from the BEM option never appears because the utility only exists while the token does.

The second is **responsive prefixes**. Any utility can be gated behind a breakpoint by prefixing it with `sm:`, `md:`, `lg:`, and so on; the prefixed form is the same utility wrapped in a `min-width` media query (40rem for `sm:`). A grid that stacks on phones and spreads to two columns on wider screens is one element. This is the browse page's recipe grid, from `recipes-index` in the views namespace:

```clojure
[:div.grid.gap-4.sm:grid-cols-2
 (for [r recipes]
   (recipe-card locale r))]
```

The bare `grid` stacks the cards in a single column; `sm:grid-cols-2` takes over once the viewport crosses the `sm` breakpoint. There is nothing new to learn per breakpoint: the same vocabulary, conditionally applied. The app spends the mechanism sparingly -- eight `sm:` gates in the whole source tree, at the places where phone and desktop differ: the recipe grids, the admin dashboard's stat tiles, wider gutters on the main column, and labels that appear only when there is room for them.

## The dev stylesheet

First, generate the stylesheet once so there is something to serve. The `tailwindcss` CLI takes an input file and an output path:

```bash
tailwindcss -i input.css -o static/styles.css
```

That scans `./src`, finds the utility classes you have used, and writes a single `static/styles.css`. In development that file is served at a stable, unhashed URL -- `/styles.css` -- straight out of the `static/` directory, so the layout links it with one tag in the document head:

```clojure
[:link {:rel "stylesheet" :href "/styles.css"}]
```

That plain `/styles.css` is all dev needs. (The asset pipeline chapter swaps this href for `(assets/asset "styles.css")`, which returns `/styles.css` unchanged in dev but a content-hashed URL in production.) That is the entire dev styling loop: edit a view, regenerate `static/styles.css`, the browser picks up the new CSS.

The proof is observable two ways: start the server and the font and colors apply, or `grep` the output for a token you used (`grep bg-primary static/styles.css`) and see that Tailwind emitted it.

`static/styles.css` is a *generated* file, so it is git-ignored: the sources are `input.css` and the class names scanned out of `./src`, and the stylesheet is rebuilt from them. You do not commit it.

What keeps the regeneration loop fast and automatic in development (a long-lived `tailwindcss --watch` process and the file watcher that swaps the stylesheet in the browser) is part of the hot-reload story, and it is covered in the hot-reload chapters, not here. What turns this same `static/styles.css` into a content-hashed, immutably cached production asset is the asset pipeline chapter, near the end of the book. For now the picture is simple: Tailwind writes one stylesheet, the layout links it, and your views can use utility classes immediately.
