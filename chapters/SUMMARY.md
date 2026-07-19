# Summary

[Introduction](README.md)
[Preface](preface.md)

# The Wager

- [What We Are Building, and Why](01-primer.md)
- [The Frontend's Many Roads, and Why We Take the Server](02-positioning.md)

# Foundations

- [A Reproducible Clojure Dev Environment with Devcontainers](03-devcontainer.md)
- [Strict Compilation: Catching Reflection and Boxed Math from Day One](04-build-hardening.md)
- [Your First Clojure Web Server: Ring, http-kit, and Reitit](05-web-server.md)
- [Live Reload: A File Watcher and WebSocket Browser Refresh](06-live-reload.md)

# Data

- [A Single Source of Time: A Swappable Clock](07-time-clock.md)
- [Datomic for Your SaaS: Schema, Queries, and the java.time Bridge](08-datomic.md)
- [The Recipe Domain: Versions, Diffs, and Forks from Datomic's History](09-recipe-domain.md)
- [Provenance: Annotating the Transaction Itself](10-provenance.md)
- [Testing a Clojure App: Fixtures, Helpers, and Coverage](11-unit-testing.md)

# Pages, and the Loop That Builds Them

- [i18n in Clojure: Multi-Language Support with Accept-Language Detection](12-i18n.md)
- [Styling with Tailwind CSS](13-tailwind-styling.md)
- [Server-Rendered HTML with Hiccup: Views, Layouts, and Escaping](14-hiccup-views.md)
- [The Morph Dispatcher: In-Place Navigation Without a Framework](15-morph-dispatcher.md)
- [A Bidirectional Source Inspector for Server-Rendered Hiccup: From Element to Code and Back](16-inspector.md)
- [The Construction View: Recording a Request with ClojureStorm](17-construction-view.md)
- [The Construction-View Overlay: Projections and the In-Page Tool](18-construction-view-overlay.md)
- [Tightening the Reload Loop: DOM Morphing and CSS Hot-Swap](19-morph-reload.md)

# Features, and Their Proof

- [Progressive Enhancement: A Layered Architecture from SSR to Islands](20-progressive-enhancement.md)
- [Forms That Hold: Server-Side Validation and the Error Re-Render](21-forms-validation.md)
- [Rendering the Future: Live Preview with d/with](22-live-preview.md)
- [Search: The Index the Schema Already Carries](23-search.md)
- [Passwordless Auth Part 1: HMAC-Signed Magic Link Tokens](24-auth-tokens.md)
- [Passwordless Auth Part 2: Magic Link Emails and the Full Login Flow](25-auth-email-flow.md)
- [What Happened While You Were Away: Activity from the Log](26-activity.md)
- [E2E Testing a Clojure Web App with Playwright](27-e2e-testing.md)
- [The Admin Dashboard: Locking Down a Privileged Surface](28-admin-dashboard.md)

# Production Hardening

- [The Production Asset Pipeline: Content Hashing, SRI, Import Maps, and CSP](29-asset-pipeline.md)
- [Legible to Machines: Open Graph, schema.org, and a Sitemap from Datomic](30-machine-legibility.md)
- [The Database Already Knows: Conditional GET from basis-t](31-conditional-get.md)
- [The Server Path, Measured: Criterium and a Flamegraph](32-server-path-measured.md)
- [100% Lighthouse Scores: Automated Performance Audits in CI](33-lighthouse.md)
- [CI/CD for a Clojure SaaS: Forgejo Actions, Podman, and Automated Deployment](34-ci-cd.md)

# Going Live

- [Going Live: The Box Under the Jar](35-going-live.md)
- [Updates with Minimal Downtime: Two of the Jar](36-minimal-downtime.md)

# Operating

- [Legible at Runtime: Metrics, Access Logs, and the Errors That Happen Elsewhere](37-runtime-legibility.md)
- [The System Calls for Help: Alerting Without a Pager Service](38-alerting.md)
- [Backup, Restore, and the Drill](39-backup-restore.md)
- [The Right to Be Forgotten: Excision, Run](40-excision.md)
- [Beyond One Box: The Honest Scaling Audit](41-beyond-one-box.md)

# Defending

- [Detect and Respond: Security Events, fail2ban, and the Levers](42-detect-respond.md)
- [Harden and Patch: The Firewall, the CVE Gate, and the Morning After](43-harden-patch.md)

# Collaboration

- [Proposing Changes Back: The Three-Way Merge](44-three-way-merge.md)

[Afterword: What You Built, and Where It Goes](afterword.md)
