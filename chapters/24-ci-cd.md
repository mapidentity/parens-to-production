# CI/CD for a Clojure SaaS: Forgejo Actions, Podman, and Automated Deployment

Over the preceding chapters we have built a Clojure/Datomic SaaS piece by piece: strict compilation, server-rendered HTML, passwordless authentication, Datomic modeling, an asset pipeline, end-to-end tests, Lighthouse audits, and more. Each piece works on its own, but without a pipeline that ties them together, shipping is a manual checklist that grows longer with every feature. Forget one step and a broken build makes it to production. Forget two and your users notice.

This final chapter wires everything into a single automated pipeline. Push to main, and the system formats, lints, builds the static assets and verifies their integrity, runs tests with coverage, executes end-to-end tests in a real browser, audits performance with Lighthouse, builds an uberjar, and deploys -- all without human intervention. That is the goal. Let's build it.

> **A note on what is actually wired up in the companion repository.** The pipeline in this chapter is the *application* deployment pipeline -- the one you would run for the SaaS itself. The companion repository for this book runs a *different*, much smaller CI workflow: a single GitHub Actions job that builds these chapters with mdBook and publishes the result to GitHub Pages. The application pipeline below (uberjar, Tailwind, esbuild, asset verification, Caddy, SSH deploy) is taught here in full, but it is not the workflow that ships this book's prose. Where it matters -- the asset-integrity gate especially -- we will be explicit about whether a step runs continuously or as a local pre-deploy check. Treat the application pipeline as the design you would adopt the day you stand up your own forge runner; the asset-integrity gate is useful *today*, even run by hand before a deploy.

## Why Forgejo Actions

Forgejo is a self-hosted Git forge (a fork of Gitea) that includes a built-in CI/CD system called Forgejo Actions. The workflow syntax is compatible with GitHub Actions, which means you get the benefit of a familiar YAML-based pipeline definition without depending on GitHub's infrastructure. For a self-hosted SaaS where you already run your own Git server, this is a natural fit. No external CI service to pay for, no secrets leaving your network, and the CI runner lives on the same infrastructure as everything else.

The key difference from GitHub Actions is the runner model. Instead of ephemeral VMs, Forgejo Actions can run directly on the host machine. We use `runs-on: host` because our pipeline runs each step inside a Podman container anyway -- the host is just the orchestrator. This gives us full control over caching, networking, and the container runtime.

## The CI container image

Every CI step (except checkout and deploy) runs inside a purpose-built container. This ensures the CI environment is reproducible and isolated from whatever is installed on the host. Here is the Dockerfile:

```dockerfile
# CI build image for Forgejo Actions
# Contains only what's needed to lint, test, and build the uberjar.

FROM docker.io/library/debian:trixie

ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    wget \
    git \
    gpg \
    ca-certificates \
    openssh-client \
    rlwrap \
    locales \
    unzip \
    && sed -i -e 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen \
    && dpkg-reconfigure --frontend=noninteractive locales \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Install Java
RUN wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor | tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null \
    && echo "deb https://packages.adoptium.net/artifactory/deb \
       $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
       | tee /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends temurin-25-jdk rlwrap \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64

# Install Clojure CLI
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && chmod +x linux-install.sh \
    && ./linux-install.sh \
    && rm linux-install.sh \
    && clojure -M -e '(println "Clojure installed")'

# Install babashka
RUN curl -s https://raw.githubusercontent.com/babashka/babashka/master/install | bash

# Install zprint formatter
RUN curl -sL https://github.com/kkinnear/zprint/releases/download/1.3.0/zprintl-1.3.0 \
    -o /usr/local/bin/zprint \
    && chmod +x /usr/local/bin/zprint

# Install clj-kondo linter
RUN curl -sL https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo \
    | bash

# Install Node.js, Playwright, Lighthouse CI, and Tailwind CSS
ENV NVM_DIR=/usr/local/nvm
RUN mkdir -p "$NVM_DIR" \
    && curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash \
    && . "$NVM_DIR/nvm.sh" \
    && nvm install --lts \
    && nvm alias default lts/* \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/node" /usr/local/bin/node \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/npm"  /usr/local/bin/npm \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/npx"  /usr/local/bin/npx \
    && npm install -g @playwright/test @lhci/cli tailwindcss @tailwindcss/cli \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/playwright"  /usr/local/bin/playwright \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/lhci"  /usr/local/bin/lhci \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/tailwindcss" /usr/local/bin/tailwindcss \
    && playwright install --with-deps \
    && ln -s "$(find /root/.cache/ms-playwright -name chrome \
       -type f -path '*/chrome-linux64/*' | head -1)" /usr/local/bin/chromium
ENV NODE_PATH=/usr/local/lib/node_modules
```

A few design choices worth explaining.

**Debian Trixie as the base.** We need a recent enough base to support current versions of Java, Node, and Chromium. Debian is stable, well-understood, and produces reasonably small images.

**Everything is pinned or versioned.** Java comes from Adoptium's Temurin 25 JDK. The zprint binary is pinned to 1.3.0. Clojure CLI pulls the latest stable release. The goal is reproducibility -- you want the same CI results whether you run the pipeline today or three months from now.

**Playwright with Chromium.** The `playwright install --with-deps` command downloads Chromium and all its system dependencies (X11 libraries, fonts, etc.) into the container. The final `ln -s` creates a `/usr/local/bin/chromium` symlink so Lighthouse CI can find the browser without extra configuration.

**Symlinks for global tools.** NVM installs Node into a deeply nested versioned directory. The symlinks make `node`, `npm`, `npx`, `playwright`, `lhci`, and `tailwindcss` available on the default PATH. Without these, every `podman run` command would need to source NVM first.

**Why not a multi-stage build?** The CI image is a build tool, not a production artifact. We want everything in one layer so the container starts fast with no copying between stages. Image size matters less here than build speed.

## The pipeline

Here is the complete `ci.yml` workflow:

```yaml
name: CI

on:
  push:
    branches: [main]
    paths:
      - 'myapp/**'
      - '.forgejo/**'
  pull_request:
    branches: [main]
    paths:
      - 'myapp/**'
      - '.forgejo/**'

env:
  CI_IMAGE: myapp-ci:latest
  APP_HOST: ${{ vars.APP_HOST }}
  CACHE_VOLS: >-
    -v /var/cache/ci/m2:/root/.m2:Z
    -v /var/cache/ci/gitlibs:/root/.gitlibs:Z

jobs:
  build-and-deploy:
    runs-on: host

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Create cache dirs
        run: mkdir -p /var/cache/ci/m2 /var/cache/ci/gitlibs

      - name: Build CI image
        run: podman build -t $CI_IMAGE -f .forgejo/ci.Dockerfile .

      - name: Check formatting
        run: >
          podman run --rm
          -v ${{ github.workspace }}:/workspace:Z
          -w /workspace/myapp
          $CI_IMAGE
          bash -O globstar -c "zprint '{:search-config? true}' -c src/**/*.clj test/**/*.clj"

      - name: Lint with clj-kondo
        run: >
          podman run --rm
          -v ${{ github.workspace }}:/workspace:Z
          -w /workspace/myapp
          $CI_IMAGE
          clj-kondo --lint src test

      - name: Build static assets
        run: >
          podman run --rm
          -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
          -w /workspace/myapp
          $CI_IMAGE
          clojure -T:build assets

      - name: Verify asset integrity
        run: >
          podman run --rm
          -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
          -w /workspace/myapp
          $CI_IMAGE
          clojure -T:build verify-assets

      - name: Run tests with coverage
        run: >
          podman run --rm
          -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
          -w /workspace/myapp
          $CI_IMAGE
          clojure -M:coverage

      - name: Run e2e tests
        run: >
          podman run --rm
          -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
          -w /workspace/myapp
          -e CI=true
          $CI_IMAGE
          playwright test --config playwright.config.js

      - name: Run Lighthouse CI
        run: >
          podman run --rm
          -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
          -w /workspace/myapp
          $CI_IMAGE
          lhci autorun

      - name: Build uberjar
        run: >
          podman run --rm
          -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
          -w /workspace/myapp
          $CI_IMAGE
          clojure -T:build uber

      - name: Verify jar exists
        run: test -f myapp/target/myapp.jar
```

> **A note on the `myapp/` path prefix.** This workflow assumes a *monorepo* layout where the application lives in a `myapp/` subdirectory (hence `-w /workspace/myapp`, `myapp/target/myapp.jar`, and the `myapp/**` path filters above). The book's companion repository is flat -- the app *is* the repo root -- so there the build writes `target/myapp.jar` and the path filters drop the `myapp/` segment. Adjust the prefix to match your own layout; the steps are otherwise identical.

The deploy steps continue in the same job:

```yaml
      - name: Deploy static files
        if: github.ref == 'refs/heads/main' && github.event_name == 'push'
        env:
          SSH_OPTS: -o StrictHostKeyChecking=no -i /root/.ssh/deploy_ed25519
        run: |
          scp -r $SSH_OPTS myapp/static/* deploy@$APP_HOST:/mnt/data/static/

      - name: Deploy app
        if: github.ref == 'refs/heads/main' && github.event_name == 'push'
        env:
          SSH_OPTS: -o StrictHostKeyChecking=no -i /root/.ssh/deploy_ed25519
        run: |
          scp $SSH_OPTS myapp/target/myapp.jar deploy@$APP_HOST:/tmp/myapp.jar
          ssh $SSH_OPTS deploy@$APP_HOST '/etc/scripts/deploy-myapp.sh /tmp/myapp.jar'
```

> **One insecure default to close before production.** `StrictHostKeyChecking=no` disables SSH host-key verification on the deploy connection, which leaves it open to a man-in-the-middle on the runner-to-host path. It is the convenient choice for a first green pipeline, but it is the one place this otherwise-hardened workflow trusts the network. The fix is to pin the host's key: capture it once with `ssh-keyscan $APP_HOST` (verified out-of-band), store it as a secret, write it to `~/.ssh/known_hosts` in the job, and drop the flag (or use `StrictHostKeyChecking=yes` with that `known_hosts` in place). Treat the `no` above as a placeholder, not the recommendation.

Let's walk through every stage.

## Stage by stage

### Triggers and path filtering

```yaml
on:
  push:
    branches: [main]
    paths:
      - 'myapp/**'
      - '.forgejo/**'
  pull_request:
    branches: [main]
    paths:
      - 'myapp/**'
      - '.forgejo/**'
```

The pipeline triggers on pushes to main and on pull requests targeting main, but only when files under `myapp/` or `.forgejo/` change. If you edit infrastructure configs, documentation, or feature specs, the CI pipeline does not run. This is important in a monorepo -- you do not want to spend ten minutes building and testing the application because someone updated a README in a different directory.

### Environment variables

```yaml
env:
  CI_IMAGE: myapp-ci:latest
  APP_HOST: ${{ vars.APP_HOST }}
  CACHE_VOLS: >-
    -v /var/cache/ci/m2:/root/.m2:Z
    -v /var/cache/ci/gitlibs:/root/.gitlibs:Z
```

Three variables defined at the workflow level so every step can use them.

`CI_IMAGE` is the tag for the CI container image. It is built fresh from the Dockerfile on every run to ensure the CI environment always matches the Dockerfile in the repository.

`APP_HOST` comes from a Forgejo repository variable (configured in Settings -> Actions -> Variables). This keeps the production hostname out of the workflow file. If you move to a different server, you change the variable, not the pipeline.

`CACHE_VOLS` is the key to fast builds. These are Podman bind mounts that map host directories into the container. Let's look at this more closely.

### Cache volumes

```yaml
CACHE_VOLS: >-
  -v /var/cache/ci/m2:/root/.m2:Z
  -v /var/cache/ci/gitlibs:/root/.gitlibs:Z
```

Clojure's dependency resolution downloads JARs to `~/.m2` (the Maven local repository) and Git-based deps to `~/.gitlibs`. Without caching, every CI run downloads the entire dependency tree from scratch. For a project with dozens of dependencies, that is minutes of network I/O.

By mounting host directories into the container, dependencies persist across runs. The first build downloads everything; subsequent builds resolve from the local cache in seconds. The `:Z` suffix is a SELinux relabeling flag that Podman needs on systems with SELinux enabled (like Fedora or RHEL-based hosts). It tells Podman to relabel the mount so the container process can read and write to it.

The `Create cache dirs` step ensures these host directories exist before any container tries to mount them:

```yaml
- name: Create cache dirs
  run: mkdir -p /var/cache/ci/m2 /var/cache/ci/gitlibs
```

### Podman, not Docker

Every containerized step uses `podman run` instead of `docker run`. Podman is a daemonless container runtime -- there is no background daemon process managing containers. Each container is a child process of the calling shell. This matters for CI because:

1. **No daemon dependency.** If a Docker daemon crashes, all containers stop. With Podman, each container is independent.
2. **Rootless capable.** Podman can run containers without root privileges, though in our CI setup we run as root for simplicity.
3. **CLI-compatible with Docker.** The command-line interface is nearly identical, so if you already know Docker, you know Podman.

The pattern for every CI step is the same:

```yaml
podman run --rm
  -v ${{ github.workspace }}:/workspace:Z    # mount the checkout
  $CACHE_VOLS                                 # mount dependency caches
  -w /workspace/myapp                         # set working directory
  $CI_IMAGE                                   # use the CI image
  <command>                                   # run the tool
```

`--rm` removes the container after it exits. The workspace is mounted at `/workspace` inside the container, and the working directory is set to the application subdirectory. This means every tool sees the same file layout as a developer working locally.

### Check formatting

```yaml
- name: Check formatting
  run: >
    podman run --rm
    -v ${{ github.workspace }}:/workspace:Z
    -w /workspace/myapp
    $CI_IMAGE
    bash -O globstar -c "zprint '{:search-config? true}' -c src/**/*.clj test/**/*.clj"
```

This runs zprint in check mode (`-c`) across all Clojure files in `src/` and `test/`. The `-c` flag means zprint reads each file, formats it in memory, and compares the result to the original. If they differ, the file is not formatted correctly and the step fails with a non-zero exit code.

The `bash -O globstar` enables recursive globbing (`**`), which is not on by default in bash. The `{:search-config? true}` option tells zprint to find the `.zprintrc` configuration file by walking up the directory tree from each file.

Note that this step does not mount the cache volumes. Formatting does not need Maven dependencies -- it only needs the source files and the zprint binary.

### Lint with clj-kondo

```yaml
- name: Lint with clj-kondo
  run: >
    podman run --rm
    -v ${{ github.workspace }}:/workspace:Z
    -w /workspace/myapp
    $CI_IMAGE
    clj-kondo --lint src test
```

Static analysis across all source and test files. clj-kondo reads the `.clj-kondo/config.edn` from the project root for linter configuration (covered in detail in [the strict-compilation chapter](04-build-hardening.md)). Like formatting, this step does not need the cache volumes -- clj-kondo analyzes source code directly without resolving dependencies.

### Build static assets and verify their integrity

```yaml
- name: Build static assets
  run: >
    podman run --rm
    -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
    -w /workspace/myapp
    $CI_IMAGE
    clojure -T:build assets

- name: Verify asset integrity
  run: >
    podman run --rm
    -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
    -w /workspace/myapp
    $CI_IMAGE
    clojure -T:build verify-assets
```

These two steps are where the asset pipeline from earlier chapters (the `assets` and `verify-assets` build tasks) becomes a deployment gate. They mount `$CACHE_VOLS` because both run under `clojure -T:build`, which needs resolved dependencies.

`clojure -T:build assets` produces the served tree. It runs Tailwind CSS once over `input.css` to emit the minified stylesheet, content-hashes it to `styles.<hash>.css`; runs esbuild over each ESM module under `static/js/` to minify it (no bundling, absolute imports preserved) and content-hash it; minifies the vendored idiomorph source into `idiomorph-0.7.4.min.js` with a sourcemap (its version lives in the filename, so it is not content-hashed); copies fonts, SVGs and the error pages through unchanged; and finally writes `asset-manifest.edn` -- a map of `{:assets {logical-name url} :sri {url sri}}` that the running application reads at boot to resolve each logical asset name to its hashed URL and Subresource-Integrity token. The whole tree, plus the manifest, lands under `myapp/static/` (which is gitignored -- only the *sources* under `static/` are committed).

`clojure -T:build verify-assets` is the integrity gate. It does **not** re-run Tailwind or esbuild, and it does not compare against committed bytes. It asserts three invariants about the tree `assets` just produced:

1. An `asset-manifest.edn` exists (if not, it tells you to run `clojure -T:build assets` first).
2. Every URL named in the manifest points at a file that actually exists on disk.
3. Every content-hashed filename matches the SHA-256 of its own bytes -- so a filename can never lie about its contents. A `styles.2c7c3332.css` whose bytes hash to something other than `2c7c3332` fails the gate.

That third invariant is the critical one. Because Caddy serves content-hashed assets with `Cache-Control: public, max-age=31536000, immutable`, a wrong hash is not a cosmetic bug -- it is a year-long cache poisoning. `verify-assets` catches a corrupted or hand-edited build artifact before it can be deployed under an immutable URL. Run it locally right before you ship; if you stand up an application CI runner, it slots in immediately after the `assets` step, exactly as shown above.

There is no separate `scripts/verify-css-hash.bb` or `verify-css` task; asset integrity lives entirely in `build.clj` as `verify-assets`, covering CSS, JavaScript and the manifest in one pass.

### Run tests with coverage

```yaml
- name: Run tests with coverage
  run: >
    podman run --rm
    -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
    -w /workspace/myapp
    $CI_IMAGE
    clojure -M:coverage
```

Runs the test suite with code coverage tracking via the `:coverage` alias. This executes all unit and integration tests and produces a coverage report. A failing test means a non-zero exit code, which fails the pipeline step.

### Run end-to-end tests

```yaml
- name: Run e2e tests
  run: >
    podman run --rm
    -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
    -w /workspace/myapp
    -e CI=true
    $CI_IMAGE
    playwright test --config playwright.config.js
```

This is where the Chromium browser inside the CI image earns its keep. Playwright launches a headless browser, starts the application, and runs through user flows -- clicking buttons, filling forms, verifying page content. The `-e CI=true` environment variable tells the test configuration to adjust timeouts and other settings for CI (where things may be slightly slower than on a developer machine).

These tests catch an entire class of bugs that unit tests miss: broken routes, missing templates, JavaScript errors, form submissions that silently fail. They are slower than unit tests but worth every second.

### Run Lighthouse CI

```yaml
- name: Run Lighthouse CI
  run: >
    podman run --rm
    -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
    -w /workspace/myapp
    $CI_IMAGE
    lhci autorun
```

Lighthouse CI starts the application, loads key pages in Chromium, and measures performance, accessibility, best practices, and SEO. The `lhci autorun` command reads its configuration from a `lighthouserc.js` file in the project root, which defines which URLs to audit and what score thresholds to enforce.

If performance drops below the threshold -- maybe someone added a render-blocking resource or a large unoptimized image -- the pipeline fails. This catches performance regressions before they reach users, automatically and on every push.

### Build the uberjar

```yaml
- name: Build uberjar
  run: >
    podman run --rm
    -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
    -w /workspace/myapp
    $CI_IMAGE
    clojure -T:build uber

- name: Verify jar exists
  run: test -f myapp/target/myapp.jar
```

The uberjar build compiles all Clojure source with strict compilation flags (`*warn-on-reflection*` and `*unchecked-math* :warn-on-boxed`), copies resources, and packages everything into a single JAR file. As covered in [the strict-compilation chapter](04-build-hardening.md), the build fails if any reflection or boxed math warnings are detected in application code.

The `Verify jar exists` step is a simple sanity check that runs on the host (not in a container). If the uberjar build succeeded but somehow did not produce the expected file, this catches it.

### Conditional deployment

```yaml
- name: Deploy static files
  if: github.ref == 'refs/heads/main' && github.event_name == 'push'
  env:
    SSH_OPTS: -o StrictHostKeyChecking=no -i /root/.ssh/deploy_ed25519
  run: |
    scp -r $SSH_OPTS myapp/static/* deploy@$APP_HOST:/mnt/data/static/

- name: Deploy app
  if: github.ref == 'refs/heads/main' && github.event_name == 'push'
  env:
    SSH_OPTS: -o StrictHostKeyChecking=no -i /root/.ssh/deploy_ed25519
  run: |
    scp $SSH_OPTS myapp/target/myapp.jar deploy@$APP_HOST:/tmp/myapp.jar
    ssh $SSH_OPTS deploy@$APP_HOST '/etc/scripts/deploy-myapp.sh /tmp/myapp.jar'
```

Deployment only happens when two conditions are met: the branch is `main` and the event is a `push` (not a pull request). Pull requests run the full pipeline -- format, lint, test, build -- but stop short of deploying. This gives you confidence that a PR is ready to merge without actually touching production.

The deployment itself is straightforward:

1. **Static files** are copied via `scp` from `myapp/static/` -- the *built* tree that `clojure -T:build assets` produced and `verify-assets` just signed off on -- to the directory the reverse proxy serves. That tree contains the content-hashed stylesheet and ESM modules, the version-pinned `idiomorph-0.7.4.min.js` (and its sourcemap), the passed-through fonts and SVGs, and `asset-manifest.edn`. The manifest must travel with the assets: the running application reads it at boot to map each logical asset name to its hashed URL and SRI token, so deploying the hashed files without the manifest would leave the app unable to resolve them.

2. **The uberjar** is copied to `/tmp/` on the server, then a deploy script moves it into place and restarts the application. The deploy script handles the atomic swap: stop the running process, move the new JAR into position, start the new process. This keeps downtime to a few seconds.

Static assets are served by Caddy, not by the JVM. In the companion repository's Caddyfile, the proxy applies `Cache-Control: public, max-age=31536000, immutable` to any content-hashed filename (the `\.([a-f0-9]{8})\.(css|js)$` pattern) and to the version-pinned `idiomorph-*.min.js`, and it sets the long-lived security headers (HSTS, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, and the `Cross-Origin-Opener-Policy`/`Cross-Origin-Resource-Policy` isolation pair). It deliberately does **not** set a Content-Security-Policy: the per-document CSP carries the SHA-256 hashes of the application's inline scripts and import map, so the *application* must own it. This is why immutable caching and asset integrity are two sides of one coin -- once a hashed file is out, browsers may cache it for a year, so `verify-assets` is the last chance to catch a hash that does not match its bytes.

The SSH key (`deploy_ed25519`) is pre-installed on the CI runner and grants access to a `deploy` user on the application server. This user has limited permissions -- it can write to the static directory and execute the deploy script, nothing else.

## The pipeline order

The stages are deliberately ordered from fastest to slowest, and from cheapest to most expensive:

1. **Format check** -- Seconds. No dependencies needed. Catches formatting issues before anything else runs.
2. **Lint** -- Seconds. Static analysis only. Catches code quality issues early.
3. **Build assets** -- Seconds. Runs Tailwind once and esbuild per module to produce the hashed, minified served tree plus the manifest.
4. **Verify asset integrity** -- A fraction of a second. No tools, just hashing: every content-hashed filename must match its bytes and every manifest target must exist.
5. **Coverage** -- Tens of seconds. Runs the test suite. First stage that executes application code.
6. **E2E tests** -- Minutes. Launches a browser and runs user flows. Expensive but catches integration bugs.
7. **Lighthouse** -- Minutes. Launches a browser and audits performance. Catches regressions.
8. **Uberjar** -- Tens of seconds. Compiles everything with strict flags. Produces the deployable artifact.
9. **Deploy** -- Seconds. Copies files and restarts the app.

If formatting is wrong, you find out in seconds, not after waiting for the entire test suite and build to run. This fast feedback loop makes CI less painful -- developers fix issues quickly because the pipeline tells them quickly.

## What you now have

Once this pipeline is wired up to your forge runner, every push to main triggers a fully automated sequence:

- **Code quality gates**: formatting with zprint, static analysis with clj-kondo
- **Asset integrity**: the `assets` build produces the hashed, minified served tree and manifest; `verify-assets` proves every content-hashed filename matches its bytes and every manifest target exists
- **Correctness verification**: unit tests with coverage, end-to-end browser tests
- **Performance assurance**: Lighthouse CI audits with score thresholds
- **Build integrity**: strict AOT compilation that rejects reflection and boxed math warnings
- **Automated deployment**: conditional deployment of the built static tree (with its manifest) and the uberjar via SSH

The entire pipeline runs inside Podman containers built from a single Dockerfile, using host-mounted cache volumes for fast dependency resolution. Pull requests get the full quality gate treatment without deploying. Only pushes to main go to production.

There is nothing to remember. No manual checklist. No "did I run the tests?" anxiety before deploying. The pipeline enforces the standards every time, whether it is Monday morning or Friday evening.

A closing note on scope, briefly, since the chapter opened on it: the workflow above is the *application* pipeline, not the mdBook-to-Pages job that actually ships these chapters. The practical takeaway needs no runner of your own, though -- `clojure -T:build assets` followed by `clojure -T:build verify-assets` is a two-command pre-deploy ritual you can run by hand: build the tree, prove its integrity, then ship it.

## Looking back

This is the final chapter of "Building a Clojure/Datomic SaaS from Scratch." Over the course of this book, we have assembled a complete application stack from first principles:

We started with a Clojure project structure and strict compilation. We added server-side rendering with escaping Hiccup. We modeled our domain in Datomic. We built passwordless authentication. We built a content-hashed asset pipeline -- Tailwind, esbuild, an import map and Subresource Integrity -- behind a strict Content-Security-Policy. We wrote tests at every level -- unit, integration, and end-to-end. We measured performance with Lighthouse. And now we have tied everything together with continuous integration and automated deployment.

Every piece was built to be understood by one person. No magic frameworks, no hidden abstractions, no build tools that require a dedicated team to maintain. A single Dockerfile for CI. A single YAML file for the pipeline. SSH and a shell script for deployment.

That simplicity is the point. A solo operator building a SaaS does not have the luxury of complexity. Every moving part is a part that can break at 2 AM with no one else to call. The system we have built is not sophisticated -- it is simple, transparent, and entirely within one person's ability to understand, debug, and maintain.

Whether you read straight through or jumped to the topics that interested you, we hope this book demonstrated that building a production SaaS with Clojure and Datomic is not only feasible but genuinely enjoyable. The tools are mature. The ecosystem is stable. And the language rewards you with a codebase that stays manageable as it grows.

Now go build something.
