# What We Are Building, and Why

This is a book about building a real web application -- a multi-tenant SaaS -- in Clojure and Datomic, server-rendered, from an empty directory to automated deployment. Not a toy, and not a survey of every library that exists. One application, built the way it would actually be built if correctness, security, and the daily experience of working on it all mattered.

This opening chapter does three things: it tells you what the application *is*, it explains the single principle that drives every technical decision in the book, and it shows you the shape every chapter takes so you know how to read them.

## The application: a recipe versioning site

The running example is a **recipe versioning site** -- think "Git for recipes." Anyone can browse recipes. A signed-in user can fork one, tweak the ingredients and steps, and publish their own version. The app shows the **diff** between any two versions, the **lineage** of a recipe ("this carbonara descends from four earlier versions"), and a **point-in-time view** of how a recipe looked at any moment in its history.

The domain was chosen on purpose, not for novelty. Two of its properties make it an honest test of the stack:

- **It is history-shaped.** Versions, forks, diffs, and "as of last Tuesday" are not bolted-on features; they are the product. That makes it a natural fit for Datomic, a database that treats time as a first-class dimension and never overwrites the past. We get edit history from `d/history` and point-in-time reads from `d/as-of` -- not from a hand-rolled audit table.
- **It is content-heavy and read-mostly.** Recipes are mostly text, mostly read, and benefit from being indexable and fast to first paint. That is exactly the workload server-side rendering is best at, and it lets us justify *not* reaching for a single-page-app framework.

If your real domain is invoicing or scheduling or analytics, the domain details will differ, but the spine -- a server that renders HTML, a database that remembers everything, passwordless auth, internationalization, a hardened asset pipeline, audited deployment -- transfers directly.

## The principle: the best build, not the easiest explanation

Every choice in this book is made to produce the **best server-rendered web application we know how to build** -- the most correct, the most secure, the fastest, the most pleasant to maintain. That is the only tiebreaker. When a simpler approach would be easier to *explain* but produce a worse application, we take the harder approach and explain it properly.

This matters because it is the opposite of how most tutorials are written. A tutorial optimizes for the reader's first twenty minutes: it picks whatever is quickest to demonstrate, defers the hard parts, and quietly ships choices you would have to undo in production. This book optimizes for the application you are left holding at the end. Sometimes that means a chapter is harder than it would strictly need to be to "work on my machine," because *working on my machine* was never the goal.

A few consequences you will see throughout:

- We turn on strict compilation and a strict Content-Security-Policy on day one, and never relax them for convenience.
- We render HTML on the server and progressively enhance it, rather than shipping a client framework -- and where we *do* add client behavior, it is a few small ES modules under a policy that forbids `eval`.
- We use Datomic's immutability as a feature, not a constraint to work around.
- We build the developer experience -- live reload, a source inspector that maps rendered HTML back to the Clojure that produced it -- as real infrastructure, early, so it pays off across every later chapter.

That last point shapes the order of the book: **developer affordances come as early as their dependencies allow**, so you are building the rest of the application with the tools already in hand.

## How each chapter is built: problem, options, choice

Every chapter addresses one problem -- one feature, one piece of infrastructure, one decision. And every chapter is built the same way, because that structure *is* the argument the book is making:

1. **The problem.** What are we actually trying to do, and why does it matter? Stated before any code.
2. **The options considered.** The real alternatives -- including the naive one you would reach for first, and the one a different engineer would defend. Each with its genuine trade-offs, not a strawman.
3. **The choice, and why.** Which option we take, what it costs, and the reasoning that makes that cost worth paying.

This is deliberate. A decision you can see the alternatives behind is a decision you can *re-make* when your constraints differ from ours. If you are building on a different database, or you must support a client-heavy UI, or your security posture is stricter or looser than ours, the chapter that shows its work tells you exactly which assumption to revisit. A tutorial that only shows the final answer leaves you guessing.

So when you read "we considered X, but chose Y," that is not throat-clearing -- it is the most reusable part of the chapter.

## The journey

The book proceeds in roughly five movements. You can read straight through; each chapter assumes the ones before it.

- **Foundations.** A reproducible dev environment, strict compilation that catches reflection and boxed math from the first commit, your first Ring/http-kit/reitit web server, and -- right away -- live reload, so the feedback loop is tight before there is much to build.
- **Data and rendering.** Datomic schema and the `java.time` bridge; internationalization wired in from the start; styling with Tailwind; server-rendered HTML with Hiccup and the escaping renderer that is our first line of defense against XSS.
- **The development loop, sharpened.** A source inspector that turns a click on the page into a jump to the Clojure that rendered it, and an upgrade of live reload into a per-edit *delivery matrix* -- morphing the live DOM for view edits, hot-swapping CSS, full reloads only where they are actually required. Then end-to-end testing with Playwright.
- **Features.** Passwordless authentication with HMAC-signed, single-use magic links; the full email login flow with sessions and a terms gate; and an admin dashboard.
- **Production hardening.** The asset pipeline -- content hashing, Subresource Integrity, an import map, and a strict Content-Security-Policy; perfect Lighthouse scores enforced in CI; and CI/CD with Forgejo Actions, Podman, and automated deployment.

## What you should already know

This book assumes you can read basic Clojure -- the level of *Clojure for the Brave and True*: functions, the core data structures, `let`/`->`/`->>`, basic protocols and macros, and enough Java interop not to flinch at `(.method obj)`. It assumes basic HTML, CSS, and JavaScript.

It does **not** assume you have built web applications in Clojure before. Ring, reitit, Datomic, Hiccup, the `java.time` bridge, content-hashed assets, import maps, DOM morphing, Content-Security-Policy, and the deployment tooling are all introduced as we reach them -- each as its own problem, with its own options and its own justified choice.

Let us start by making the environment itself reproducible.
