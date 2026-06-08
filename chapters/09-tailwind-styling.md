# Styling with Tailwind CSS

You are about to build the view layer in Hiccup -- vectors of tags and attributes that render to HTML. Those views need to look like something. Before we get into markup, we need a styling system: a way to give every page a consistent look without inventing a parallel vocabulary of class names and a sprawl of CSS files that drift out of sync with the markup they style.

This chapter sets that up. It is deliberately small and self-contained. All it needs is a running web server and the project's `static/` directory. The production story for styles -- content-hashing the stylesheet, immutable caching, the Content-Security-Policy -- belongs to the asset pipeline chapter much later in the book; here we just get Tailwind producing a stylesheet your views can use as you write them.

## Why Tailwind in a server-rendered Clojure app

The problem with styling a server-rendered app is the same one every styled app has: where does the CSS live, and how do you keep it from rotting? The options:

- **Hand-written CSS, organized by a convention like BEM.** You write `.recipe-card__title--featured` in a `.css` file and the matching class in your markup. This works, but it asks you to invent and remember a naming system, and the CSS lives in a separate file from the markup it styles. The two drift: you delete a component, the styles linger; you rename a block, half the selectors go stale. There is no compiler telling you a class is dead.
- **CSS-in-JS.** Not applicable. There is no JavaScript runtime rendering these pages -- the HTML comes out of Clojure -- so a styling approach that lives inside a React component tree has nothing to attach to.
- **A component CSS framework (Bootstrap and friends).** You get pre-built components fast, but you also adopt the framework's look, its class taxonomy, and its opinions. Customizing past the defaults means fighting the framework, and you ship a lot of CSS you never use.

Tailwind wins here because it inverts the problem. Instead of a separate stylesheet full of names you invent, you compose styling from a fixed vocabulary of utility classes written directly in the markup: `[:div.mt-4.flex.items-center ...]`. The styling is co-located with the Hiccup that it styles, so the two cannot drift -- delete the element and its styling goes with it. There is no class name to invent, and no separate CSS file to maintain.

Crucially for this stack, Tailwind does not care where the class names come from. It generates CSS by scanning your source files for class names. It does not need to understand JSX, ERB, or Hiccup -- it scans a directory, finds the utility classes you used, and emits exactly the CSS those classes need and nothing more. That makes it a perfect fit for server-rendered HTML: there is no JavaScript build pipeline for your *styles*, just a binary that reads source and writes one CSS file.

And Tailwind v4 ships as a **standalone CLI** -- a single binary, no Node project, no PostCSS plugin chain, no `tailwind.config.js`. You run it, it produces a CSS file, and the app serves that file. It is a build tool, not a runtime dependency: nothing about Tailwind ends up in the served page except plain CSS.

## The input file: Tailwind v4 with design tokens

Tailwind v4 changed how configuration works. Instead of a `tailwind.config.js` file, everything lives in CSS using `@theme`, `@import`, and `@source` directives. One less JavaScript config file to maintain.

Here is the top of `input.css`:

```css
@import "tailwindcss" source("./src");

/* The dev-only source inspector uses its own injected `.fy-insp-*` styles, never
   Tailwind utilities. Excluding it keeps a false-positive `resize` token (from its
   `addEventListener('resize', ...)`) out of the production bundle -- zero prod footprint. */
@source not "./src/myapp/web/inspector.js";

@view-transition {
  navigation: auto;
}

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

A few things to note:

**`@import "tailwindcss" source("./src")`** -- This tells Tailwind v4 to scan the `./src` directory for utility classes. It will find class names in your `.clj` files, inside Hiccup vectors like `[:div.mt-4 ...]` or `[:div {:class "flex items-center"} ...]`.

**`@source not "./src/myapp/web/inspector.js"`** -- A targeted exclusion. The dev-only source inspector is a JS file under `src/`, and Tailwind's scanner would otherwise see a `resize` substring in its `addEventListener('resize', ...)` and emit a stray `resize` utility. Excluding the file keeps that false positive out of the production stylesheet at zero cost.

**`@font-face` for Geist** -- Geist is a variable font, so a single `.woff2` file covers all weights from 100 to 900. `font-display: swap` shows a fallback immediately, then swaps to Geist once it downloads.

**`@theme` block** -- Design tokens as CSS custom properties. Once declared, you use them directly in utilities: `bg-primary`, `text-text-secondary`, `border-border`. The naming is deliberately semantic -- `surface`, `chrome`, `accent` -- rather than color scales like `indigo-600`. A focused app has a small, fixed palette; you do not need eleven shades of every color.

Below `@theme` the file carries plain CSS that does not map cleanly to utilities: a `.legal-content` block for rendering markdown documents (privacy policy, terms) with proper typography, interaction transitions (press feedback, card hover lift, a `details[open]` chevron rotation), focus-visible outlines for keyboard navigation, and the `.diff-add` / `.diff-del` styles used by the recipe version diff.

## The dev stylesheet

Tailwind reads `input.css`, scans `./src`, and writes a single stylesheet: `static/styles.css`. In development that file is served at a stable, unhashed URL -- `/styles.css` -- straight out of the `static/` directory. The layout links it with one tag in the document head:

```clojure
[:link {:rel "stylesheet" :href (assets/asset "styles.css")}]
```

In development `(assets/asset "styles.css")` resolves to the identity URL `/styles.css`, so the browser fetches the file Tailwind just wrote. That is the entire dev styling loop: edit a view, Tailwind regenerates `static/styles.css`, the browser picks up the new CSS.

`static/styles.css` is a *generated* file, so it is git-ignored -- the sources are `input.css` and the `static/` tree, and the stylesheet is rebuilt from them. You do not commit it.

What keeps the regeneration loop fast and automatic in development -- a long-lived `tailwindcss --watch` process and the file watcher that swaps the stylesheet in the browser -- is part of the hot-reload story, and it is covered in the hot-reload chapters, not here. What turns this same `static/styles.css` into a content-hashed, immutably cached production asset is the asset pipeline chapter, near the end of the book. For now the picture is simple: Tailwind writes one stylesheet, the layout links it, and your views can use utility classes immediately.

## What you have now

The app has a styling system with no JavaScript runtime dependency: the standalone Tailwind v4 CLI as a build tool, semantic design tokens declared in CSS, a variable font with `font-display: swap`, and a single stylesheet served at a stable URL in development. Every view you write from here on can reach for utility classes -- `mt-4`, `flex`, `bg-primary`, `text-text-secondary` -- co-located with the Hiccup markup they style, with no separate CSS file to keep in sync. That is enough to start building views.
