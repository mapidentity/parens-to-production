# Building a Clojure/Datomic SaaS from Scratch

This repository is **two things at once**:

1. **An online book** — 25 chapters (in [`chapters/`](chapters/)) that walk through
   building a production-grade SaaS in Clojure and Datomic, from a reproducible
   dev environment to automated CI/CD. Built with [mdBook](https://rust-lang.github.io/mdBook/)
   and published to GitHub Pages.
2. **The finished, runnable app** the book describes — so you can check out the
   repo, build it, and tinker as you read.

📖 **Read the book online:** _https://mapidentity.github.io/parens-to-production/_

---

## Demo

The dev source inspector in action: click a
rendered element and your editor opens the exact Hiccup that made it; put your
cursor on a view and the matching element lights up in the browser.

https://github.com/user-attachments/assets/5e2302ab-0a62-41b2-ba2c-b069366a606f

---

## The demo app: a recipe versioning site

The companion app is **"Git for recipes."** Users fork recipes, tweak them, and
the app renders **diffs between versions**, **lineage trees** ("this carbonara
descends from 4 ancestors"), and **point-in-time views** of any past version.

It's a deliberately small but complete SaaS that exercises everything the book
teaches — and it leans on Datomic's immutability for its headline features:

| Feature | How it works |
| --- | --- |
| Version history | Every edit is a Datomic transaction; `d/history` enumerates them |
| Point-in-time view | `d/as-of` reconstructs a recipe at any past basis point |
| Version diff | Pull the recipe at two points and line-diff (LCS) the ingredients/steps |
| Fork lineage | A fork is a new entity with a `:recipe/forked-from` ref; walk it to the root |
| Passwordless auth | HMAC-signed magic-link tokens, one-shot via a nonce CAS |
| i18n, admin, SSR views | Hiccup server-rendering, Accept-Language locale, an admin funnel dashboard |

The interesting code lives in [`src/myapp/recipe/core.clj`](src/myapp/recipe/core.clj).

---

## Prerequisites

The book's [devcontainer chapter](chapters/03-devcontainer.md) sets all of this up
reproducibly with a devcontainer (Docker + Caddy + Mailpit). To run it directly
you need:

- **JDK 21+** and the **[Clojure CLI](https://clojure.org/guides/install_clojure)**
- **Node.js 20+** (for the Tailwind build and Playwright e2e tests)
- The **[Tailwind CSS](https://tailwindcss.com/) v4 standalone CLI** on your `PATH` (`tailwindcss`)

Datomic Pro's Peer library is a normal Maven dependency and runs **in-process
against an in-memory database** (`datomic:mem://`) — no transactor or Postgres
needed for local development.

---

## Quick start

```sh
# 1. Build the stylesheet (Tailwind scans src/ for classes)
tailwindcss -i input.css -o static/styles.css --minify

# 2. Start a REPL with the dev tooling
clojure -M:dev:repl
```

Then, connected to the REPL (or from your editor):

```clojure
(start!)   ; start the server + file watcher on http://localhost:3000
(fresh!)   ; wipe the in-memory DB and load the demo recipes
```

Open <http://localhost:3000/recipes> and explore. Sign-in is passwordless: enter
any email, and because dev uses a stubbed/Mailpit mail server, grab the magic
link from the dev mail UI (or the REPL logs).

The dev admin user is `admin@myapp.lan` — sign in with that address to see the
admin dashboard at `/admin`.

### Run without a REPL

```sh
clojure -M -e "(require 'myapp.core)(myapp.core/start-server!)@(promise)"
```

---

## Tests & checks

```sh
clojure -X:test                  # unit + integration tests (in-memory Datomic)
clojure -T:build compile-strict  # AOT compile; fails on reflection / boxed-math warnings (ch.4)
./lint                           # clj-kondo + the "read time only through myapp.time" guard
npx playwright test              # end-to-end browser tests (auto-starts the e2e server)
```

The `:coverage` alias (`clojure -M:coverage`) runs the tests under Cloverage.

---

## Layout

```
chapters/            the book (Markdown) + SUMMARY.md (table of contents)
book.toml            mdBook config
src/myapp/           the application
  core.clj             server lifecycle
  config.clj           Aero config + key management
  db/                  Datomic access + java.time bridge + tenant isolation
  recipe/core.clj      the recipe-versioning domain (forks, history, diff)
  auth/                passwordless magic-link auth
  web/                 routes, handlers, Hiccup views, the dev source inspector
  admin/, analytics/   admin dashboard + the signup funnel
  i18n*                en/nl translations
dev/                 REPL helpers, live/hot reload, the inspector loader, demo seed data
test/myapp/          tests
e2e/                 Playwright specs
.devcontainer/, caddy/, certificates/, compose.yml   the dev environment (ch.3)
```

---

## Working on the book

```sh
mdbook serve --open   # live-reloading preview of the book
mdbook build          # one-off build into ./book
```

Add a chapter by dropping a `.md` file in `chapters/` and adding a line to
[`chapters/SUMMARY.md`](chapters/SUMMARY.md). Every push to `main` rebuilds and
deploys the book to GitHub Pages via
[`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) — see that file's
header for the one-time Pages setup (Settings → Pages → Source: GitHub Actions).
