# Building a Clojure/Datomic SaaS from Scratch

**Server-rendered, framework-free, and hardened from the first commit.**

A hands-on, chapter-by-chapter guide to building a production-grade SaaS
application in Clojure and Datomic. From a reproducible development
environment all the way to automated CI/CD deployment.

Each chapter is self-contained but builds on the last. Use the sidebar to
navigate, or the arrow keys to move between chapters. Press **`s`** or click
the magnifier to search the full text.

## What's covered

- A reproducible **devcontainer** dev environment (Docker, Caddy, Mailpit, TLS)
- **Strict compilation** to catch reflection and boxed math early
- A **web server** with Ring, http-kit, and Reitit
- **Live reload**, **DOM-morphing hot reload**, and a **Hiccup source inspector**
- A ClojureStorm-powered **construction view**: one request's full execution,
  recorded and projected onto the page it rendered
- **Datomic** schema and queries, **i18n**, and **Tailwind** styling
- **Recipe versioning** -- versions, diffs, and forks read straight from
  Datomic's history
- **Server-rendered views** with Hiccup, a **morph dispatcher** for in-place
  navigation, and **progressive enhancement** from SSR to islands
- **E2E testing** with Playwright
- **Passwordless auth** with single-use, HMAC-signed magic links
- An **admin dashboard**, a hardened **asset pipeline** (hashing, SRI, CSP),
  **Lighthouse** audits, and **CI/CD**

Start with [the primer](01-primer.md) for what we are building and why, or jump
to any topic from the table of contents.
