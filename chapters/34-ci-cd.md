# CI/CD for a Clojure SaaS: Forgejo Actions, Podman, and Automated Deployment

Over the preceding chapters we have built a Clojure/Datomic SaaS piece by piece: strict compilation, server-rendered HTML, passwordless authentication, Datomic modeling, an asset pipeline, end-to-end tests, Lighthouse audits, and more. Each piece works on its own, but without a pipeline that ties them together, shipping is a manual checklist that grows longer with every feature. Forget one step and a broken build makes it to production. Forget two and your users notice.

This final chapter wires everything into a single automated pipeline. Push to main, and the system formats, lints, builds the static assets and verifies their integrity, runs tests with coverage, executes end-to-end tests in a real browser, audits performance with Lighthouse, builds an uberjar, and deploys -- all without human intervention. That is the goal.

> **A note on what is actually wired up in the companion repository.** The pipeline in this chapter is the *application* deployment pipeline -- the one you would run for the SaaS itself. The companion repository for this book runs a *different*, much smaller CI workflow: a single GitHub Actions job that builds these chapters with mdBook and publishes the result to GitHub Pages. The application pipeline below (uberjar, Tailwind, esbuild, asset verification, Caddy, SSH deploy) is taught here in full, but it is not the workflow that ships this book's prose. Where it matters -- the asset-integrity gate especially -- we will be explicit about whether a step runs continuously or as a local pre-deploy check. Treat the application pipeline as the design you would adopt the day you stand up your own forge runner; the asset-integrity gate is useful *today*, even run by hand before a deploy.

## Why Forgejo Actions

Forgejo is a self-hosted Git forge (a fork of Gitea) that includes a built-in CI/CD system called Forgejo Actions. The workflow syntax is compatible with GitHub Actions, which means you get the benefit of a familiar YAML-based pipeline definition without depending on GitHub's infrastructure. For a self-hosted SaaS where you already run your own Git server, this is a natural fit. No external CI service to pay for, no secrets leaving your network, and the CI runner lives on the same infrastructure as everything else.

The cost is worth naming, because it is real. A host runner you own is one more machine to patch, secure, and keep online, and you forgo the elastic, zero-maintenance capacity that a hosted service like GitHub Actions gives you for nothing. The trade pays off precisely when you *already* self-host the forge -- the runner is then marginal infrastructure rather than new infrastructure. Leaning on the forge's built-in Actions is part of what keeps it marginal: the alternative for a self-hoster is a dedicated CI system alongside the forge -- Woodpecker for something lightweight, Jenkins for the kitchen sink -- each another service to deploy, secure, and keep running, for a pipeline the forge already drives. If you do not, hosted CI is the lower-effort answer, and the workflow below ports to it almost unchanged, since the syntax is shared.

> **The port, made concrete.** "Almost unchanged" is checkable, so here is the whole diff for running this pipeline on GitHub Actions. Change `runs-on: host` to `runs-on: ubuntu-latest`. Swap Podman for the runner's Docker (`podman run` becomes `docker run`, and the `:Z` volume suffix -- an SELinux label -- simply goes away). Replace the host-mounted `.m2`/`.gitlibs` cache volumes with `actions/cache` steps keyed on `deps.edn`, since a hosted runner keeps nothing between jobs. Move the deploy key -- which the self-hosted runner keeps pre-installed as a file -- into the repository's encrypted secrets, with a step that writes it back to a key file before the `scp`. Everything else -- `on:`, `jobs:`, `steps:`, the container-per-step discipline, the gate order -- is identical, because Forgejo Actions speaks GitHub's dialect by design. What does *not* port is the trust model: your source, secrets, and deploy key now transit GitHub's infrastructure, which is exactly the trade the self-hosted runner exists to refuse. Neither answer is wrong; they price privacy and maintenance differently.

The key difference from GitHub Actions is the runner model. Instead of ephemeral VMs, Forgejo Actions can run directly on the host machine. We use `runs-on: host` because our pipeline runs each step inside a Podman container anyway -- the host is just the orchestrator. This gives us full control over caching, networking, and the container runtime.

## The CI container image

Every step that actually exercises the code runs inside a purpose-built container: formatting, linting, dependency scanning, the asset build and its verification, tests, Lighthouse, and the uberjar. The few that do not -- checkout, creating the cache directories, building the CI image itself, the `test -f` jar check, and the deploy -- run on the host, where a container would add nothing. This keeps the CI environment reproducible and isolated from whatever is installed on the host. Here is the Dockerfile:

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
    && apt-get install -y --no-install-recommends temurin-25-jdk \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
# TARGETARCH is amd64 or arm64; threading it through keeps JAVA_HOME correct on
# Intel runners and Apple Silicon alike. This note sits on its own lines rather
# than trailing the ENV line, because Docker would fold a trailing `# ...` into
# the value of JAVA_HOME.
ARG TARGETARCH
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-${TARGETARCH}

# Install Clojure CLI
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && chmod +x linux-install.sh \
    && ./linux-install.sh \
    && rm linux-install.sh \
    && clojure -M -e '(println "Clojure installed")'

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

**Versions: pinned where it counts, latest where it helps.** The major pieces are pinned: Adoptium's Temurin 25 JDK, the zprint binary at 1.3.0, nvm at v0.40.3. Several tools track the latest release instead: the Clojure CLI (`releases/latest`), Babashka and clj-kondo (their `master` install scripts), Node (`nvm install --lts`), and the global npm tools (`@playwright/test`, `@lhci/cli`, `tailwindcss`). That is a freshness-versus-reproducibility trade: it keeps the toolchain current, at the cost of two builds months apart not being guaranteed bit-for-bit identical. The drift is narrower than that sounds, and the reason is layer caching. The workflow rebuilds the image on every push (the `Build CI image` step below), but Podman re-executes a `RUN` layer only when its instruction text or a preceding layer changes; a `curl ... releases/latest` line is not re-fetched on an unchanged Dockerfile, the cached layer is reused byte-for-byte. So the `latest`- and `master`-tracking tools are effectively frozen at whenever their layer was last built, and they move only when you bust the cache: edit that line, prune the cache, or build `--no-cache`. If you need the stronger guarantee, pin those too: exact versions on the npm installs, a fixed Clojure CLI version, tagged (not `master`) install scripts, and a digest-pinned base image. That pinning is also a *supply-chain* control, not only a reproducibility one: an unpinned `curl … | bash` from `master` runs whatever that script says the day the layer is built, and an unpinned base image ships whatever vulnerabilities have landed in it since you last looked. Digest-pin the base image, prefer tagged installers over `master`, and pair both with the dependency scan below, so a known-vulnerable transitive dependency fails the build rather than riding it to production.

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

      - name: Scan dependencies for CVEs
        run: >
          podman run --rm
          -v ${{ github.workspace }}:/workspace:Z $CACHE_VOLS
          -w /workspace/myapp
          $CI_IMAGE
          bash -c 'clojure -M:nvd "" "$(clojure -Spath)"'

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

> **A note on the `myapp/` path prefix.** This workflow assumes a *monorepo* layout where the application lives in a `myapp/` subdirectory -- hence `-w /workspace/myapp`, `myapp/target/myapp.jar`, and the `myapp/**` path filters, which usefully scope CI to the app and ignore changes elsewhere in the repo. The book's companion repository is flat, though -- the app *is* the repo root -- so there the working directory is `/workspace`, the jar is `target/myapp.jar`, and the path filters name the app's own globs (`src/**`, `test/**`, `deps.edn`, `input.css`, `static/**`) rather than one directory. One path is *not* a plain prefix swap: the asset pipeline's output directory is itself named `myapp/static/` (see [the asset-pipeline chapter](29-asset-pipeline.md)), so under `-w /workspace/myapp` the built tree lands at `myapp/myapp/static/` and the static `scp` must point there -- whereas in the flat repo it is simply `myapp/static/` at the root, exactly as the deploy steps below write it. Match the paths to your own layout rather than copying the prefixes blindly.

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

> **Supply-chain scanning: fail on a known-vulnerable dependency.** The pipeline lints your code and audits your assets, but a transitive dependency carrying a published CVE would sail through both untouched. The `Scan dependencies for CVEs` step closes that gap with [nvd-clojure](https://github.com/rm-hull/nvd-clojure), which walks the project's full classpath and checks every artifact against the National Vulnerability Database. It rides in as an alias, the same way `:build` and `:coverage` do:
>
> ```clojure
> ;; deps.edn, under :aliases
> :nvd {:replace-deps {nvd-clojure/nvd-clojure {:mvn/version "5.3.0"}}
>       :main-opts    ["-m" "nvd.task.check"]}
> ```
>
> `:replace-deps` matters: it keeps nvd-clojure's own dependency tree out of the classpath you are scanning, so the tool never reports on itself. The step then runs `clojure -M:nvd "" "$(clojure -Spath)"` -- the empty first argument selects nvd-clojure's default config file, `$(clojure -Spath)` hands `nvd.task.check` the application's classpath, and it fails the build when any dependency scores at or above the CVSS threshold you configure. Two caveats worth stating rather than hiding: the first run downloads the NVD data feed, so cache it between runs or supply an `NVD_API_KEY` to lift the anonymous rate limit; and the NVD carries false positives, so you will keep a small suppression file for advisories that do not apply to how you use the dependency. Neither is a reason to skip it -- both are cheaper than learning about a vulnerable dependency from an incident instead of from your own pipeline.

The stages, in order.

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

`CI_IMAGE` is the tag for the CI container image. The `Build CI image` step runs `podman build` on every push, but Podman's layer cache means the expensive `RUN` steps re-execute only when the Dockerfile changes, so the rebuild is normally a fast no-op that keeps the image in step with the committed Dockerfile without reinstalling the toolchain each time. That same cache is what pins the `latest`- and `master`-tracking tools between rebuilds, as the image section noted.

`APP_HOST` comes from a Forgejo repository variable (configured in Settings -> Actions -> Variables). This keeps the production hostname out of the workflow file. If you move to a different server, you change the variable, not the pipeline.

`CACHE_VOLS` is the key to fast builds. These are Podman bind mounts that map host directories into the container.

### Cache volumes

The two `CACHE_VOLS` mounts defined above carry the whole speed story. Clojure's dependency resolution downloads JARs to `~/.m2` (the Maven local repository) and Git-based deps to `~/.gitlibs`. Without caching, every CI run downloads the entire dependency tree from scratch. For a project with dozens of dependencies, that is minutes of network I/O.

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

Docker would work here too, and the honest cost of choosing Podman is the smaller ecosystem and the occasional rootless-networking quirk you have to look up. What we buy for that is the absence of a daemon and of mandatory root -- the better fit for a single self-hosted runner. A team already standardized on Docker tooling would reasonably keep it; the pipeline does not depend on the choice.

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

One gap is worth naming here, because an earlier chapter leaned on it. The local `./lint` script pairs clj-kondo with a grep for raw clock calls (`Instant/now`, `System/currentTimeMillis`, and the rest) that enforces the read-time-through-`myapp.time` discipline from [the clock chapter](07-time-clock.md), since clj-kondo's `:discouraged-var` cannot see Java static methods. This pipeline step runs only `clj-kondo --lint src test`, so that grep stays a local pre-commit check. To enforce the time discipline in the pipeline too, call `./lint` here in place of the bare `clj-kondo` invocation; it is a one-line swap.

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

These two steps are where the asset pipeline from [the asset-pipeline chapter](29-asset-pipeline.md) (the `assets` and `verify-assets` build tasks) becomes a deployment gate. They mount `$CACHE_VOLS` because both run under `clojure -T:build`, which needs resolved dependencies.

`clojure -T:build assets` produces the served tree. It runs Tailwind CSS once over `input.css` to emit the minified stylesheet, content-hashes it to `styles.<hash>.css`; runs esbuild over each ESM module under `static/js/` to minify it (no bundling, absolute imports preserved) and content-hash it; minifies the vendored idiomorph source into `idiomorph-0.7.4.min.js` with a sourcemap (its version lives in the filename, so it is not content-hashed); copies fonts, SVGs and the error pages through unchanged; and finally writes `asset-manifest.edn` -- a map of `{:assets {logical-name url} :sri {url sri}}` that the running application reads at boot to resolve each logical asset name to its hashed URL and Subresource-Integrity token. The whole tree, plus the manifest, lands under `myapp/static/` (which is gitignored -- only the *sources* under `static/` are committed).

`clojure -T:build verify-assets` is the integrity gate. It does **not** re-run Tailwind or esbuild, and it does not compare against committed bytes. It asserts three invariants about the tree `assets` just produced:

1. An `asset-manifest.edn` exists (if not, it tells you to run `clojure -T:build assets` first).
2. Every URL named in the manifest points at a file that actually exists on disk.
3. Every content-hashed filename matches the SHA-256 of its own bytes -- so a filename can never lie about its contents. A `styles.2c7c3332.css` whose bytes hash to something other than `2c7c3332` fails the gate.

That third invariant is the critical one. Because Caddy serves content-hashed assets with `Cache-Control: public, max-age=31536000, immutable`, a wrong hash is not a cosmetic bug -- it is a year-long cache poisoning. `verify-assets` catches a corrupted or hand-edited build artifact before it can be deployed under an immutable URL. Run it locally right before you ship; if you stand up an application CI runner, it slots in immediately after the `assets` step, exactly as shown above.

Asset integrity lives entirely in `build.clj` as `verify-assets`, covering CSS, JavaScript, and the manifest in one pass.

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

The `:coverage` alias is the same command the commit gate runs locally; here it runs as the neutral referee -- a fresh container, no local state, the same 50% coverage floor. A failing test or a drop below the floor exits non-zero, and the pipeline stops at this step rather than shipping past it.

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

This is where the Chromium browser inside the CI image does its work. Playwright launches a headless browser, starts the application, and runs through user flows: clicking buttons, filling forms, verifying page content. The `-e CI=true` environment variable flips exactly one setting in `playwright.config.js`: `reuseExistingServer` becomes false, so the run always starts its own fresh server instead of attaching to one a developer left running locally. The webServer's 300-second startup timeout is set unconditionally, sized for a cold container rather than toggled by the flag ([the end-to-end-testing chapter](27-e2e-testing.md) walks through that config).

These tests catch an entire class of bugs that unit tests miss: broken routes, missing templates, JavaScript errors, form submissions that silently fail. They are slower than unit tests, and they are the first gate that fails when the app breaks in ways only a browser can see.

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

Lighthouse CI starts the application, loads key pages in Chromium, and measures performance, accessibility, best practices, and SEO. The `lhci autorun` command reads its configuration from a `lighthouserc.js` file in the project root, which defines which URLs to audit and what score thresholds to enforce ([the Lighthouse chapter](33-lighthouse.md) builds that config and defends the thresholds).

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

> **What the `push`-only guard does not buy.** The `if: github.event_name == 'push'` condition keeps a pull request from *deploying*, but not from *running code on your runner*. Every step here executes on the host as root, and the deploy key lives on that host as a plain file (`/root/.ssh/deploy_ed25519`). A pull request that edits the workflow, the Dockerfile, or any test it triggers is untrusted code with read access to that key: it can copy `deploy_ed25519` out in an ordinary step and walk away with production SSH access, no deploy step required. This is the sharp edge of the self-hosted-runner trade the chapter opened on -- you own the runner, so you own the blast radius of everything it runs. The fixes are real work: gate pull-request runs behind approval for authors outside a trusted set (Forgejo, like GitHub, supports this), keep the deploy key on a deploy-only runner that pull-request jobs never schedule onto, or move it into a secret only `push`-triggered jobs can read. The `StrictHostKeyChecking` gap flagged earlier is the smaller of the two trust holes; this is the larger.

The deployment itself is straightforward:

1. **Static files** are copied via `scp` from `myapp/static/` -- the *built* tree that `clojure -T:build assets` produced and `verify-assets` just signed off on -- to the directory the reverse proxy serves. That tree contains the content-hashed stylesheet and ESM modules, the version-pinned `idiomorph-0.7.4.min.js` (and its sourcemap), the passed-through fonts and SVGs, and `asset-manifest.edn`. The manifest must travel with the assets: the running application reads it at boot to map each logical asset name to its hashed URL and SRI token, so deploying the hashed files without the manifest would leave the app unable to resolve them.

2. **The uberjar** is copied to `/tmp/` on the server, then a deploy script swaps it into place and restarts the application: stop the running process, move the new JAR into position, start the new process, and roll back to the previous JAR if the new one does not come up healthy. It is a brief hard restart, a few seconds of downtime rather than zero-downtime, and the script below names that cost rather than hiding it.

That script (`/etc/scripts/deploy-myapp.sh`) is the single highest-stakes line in the whole pipeline -- the one that actually touches production -- so it is worth seeing rather than describing. It is not in the companion repository, which deploys this book rather than the app, but here is the shape it takes, made concrete:

```bash
#!/usr/bin/env bash
# /etc/scripts/deploy-myapp.sh — run on the app server, handed the freshly-uploaded jar.
set -euo pipefail

new_jar="$1"
target=/opt/myapp/myapp.jar
health=http://127.0.0.1:3000/health   # the app's own readiness endpoint

# Refuse a missing or truncated upload before we stop anything.
[ -s "$new_jar" ] || { echo "no jar at $new_jar" >&2; exit 1; }

cp -f "$target" "$target.prev" 2>/dev/null || true  # keep the last-good jar to roll back to

sudo systemctl stop myapp             # the brief outage window opens here
mv -f "$new_jar" "$target"            # service is stopped, so the swap need not be atomic
sudo systemctl start myapp            # spawns the process; does NOT wait for it to serve

# `systemctl start` returns the instant the process is spawned, not when the JVM
# is answering requests. Poll the real health endpoint until it returns 200,
# giving the app up to 30s to bind the port and finish booting.
for _ in $(seq 1 30); do
  if curl -fsS "$health" >/dev/null 2>&1; then
    exit 0                            # the app answered — the deploy succeeded
  fi
  sleep 1
done

# No healthy response in the window: the new jar booted and died (bad config,
# schema mismatch, port clash) or never came up. Restore the previous jar,
# restart, and fail the deploy loudly rather than leaving a crash-loop behind.
echo "myapp did not become healthy — rolling back to previous jar" >&2
[ -s "$target.prev" ] && mv -f "$target.prev" "$target"
sudo systemctl restart myapp
exit 1
```

It is deliberately small: the `systemd` unit owns restart-on-failure, logging, and the JVM flags, so the script's only job is the swap and the check that the swap actually worked. Because the service is stopped during the swap, the `mv` need not be atomic: nothing is serving that could observe a half-written file. Two honesty points are worth naming plainly. First, this is **not** zero-downtime: `stop` → `mv` → `start` is a hard restart, so requests in flight during the swap would be dropped and the site is unreachable for the second or two the JVM takes to boot. True zero-downtime (a second app node drained behind the proxy, or a blue-green pair) is the horizontal-scale work the afterword flags as out of scope; for a single-node SaaS a few seconds of off-hours restart is usually an acceptable trade -- named rather than hidden, and then softened as far as one node allows in [the restart-window section below](#the-restart-window-softened).

Second, and this is the subtler trap the script exists to close: `systemctl start` returns the instant the process is *spawned*, not when the application is *serving*. A jar that boots and then dies three seconds later -- a bad config value, a schema mismatch, a port already bound -- would sail straight past a bare `systemctl is-active` check: the unit reads `active` for that instant, the deploy reports green, and the crash-loop surfaces only as a silent outage under the unit's own restart-on-failure. So the script does not ask whether the process spawned. It polls the app's own `/health` endpoint (the same endpoint the e2e server waits on in [the end-to-end-testing chapter](27-e2e-testing.md)) until it answers `200`, and only a real response counts. No healthy answer inside the 30-second window means the deploy failed: restore the previous jar (kept as `myapp.jar.prev`), restart, and exit non-zero. "The deploy succeeded" is thereby defined as *the service answered a request*, not merely *the process started*. The `deploy` user is granted exactly three passwordless `sudo` rights -- `/usr/bin/systemctl stop myapp`, `start myapp`, and `restart myapp`, the third existing only for the rollback line -- and nothing else; the health poll is an ordinary unprivileged HTTP request, so it needs no privilege at all.

### The restart window, softened

Having named the downtime, the honest next move is to spend the two smallest tools that exist on making it painless -- one at each end of the window. The window has two failure surfaces: the requests *in flight* when `systemctl stop` lands, and the requests that *arrive* while the JVM is away. Neither needs a second node to fix; they need an application that exits well and a proxy that fails well.

**Exiting well.** `systemctl stop` delivers SIGTERM -- as does `podman stop`, as does every orchestrator's shutdown -- and the JVM's response to SIGTERM is to run its shutdown hooks and exit. Without one, the process simply dies: http-kit's listening socket closes mid-request, and whoever was halfway through a form submission gets a connection reset as their reward for deploying-hour timing. The fix is one hook in `myapp.core`, installed by `-main`:

```clojure
(defn- install-shutdown-hook!
  "Register a JVM shutdown hook that drains the server before exit.
  `systemctl stop` (like every container runtime's stop) delivers
  SIGTERM, which the JVM turns into an orderly shutdown-hook run. The
  one-second drain budget is generous next to the app's millisecond
  renders (see dev/bench.clj). Installed only by -main: in the REPL and
  the dev loop the server lifecycle is driven interactively, and a hook
  per reload would pile up."
  []
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread. ^Runnable (fn [] (stop-server! 1000)) "myapp-drain")))
```

`stop-server!` grew a `drain-ms` arity for this: http-kit's stop function takes a timeout, stops *accepting* connections immediately, and gives requests already being handled up to that long to complete before their connections close. The budget is where [the measurement chapter](32-server-path-measured.md) quietly pays off: a render costs about a millisecond, so one second of drain covers in-flight work a thousand times over -- a fact, not a guess, which is the difference between a drain budget and a superstition. `systemctl stop` itself waits for the process to exit (its own stop timeout defaults to 90 seconds), so the drain adds at most one second to the window in exchange for never dropping a request that had already started. The hook lives in `-main` and only there: the REPL and [the dev loop](06-live-reload.md) drive the server lifecycle interactively, and re-registering a hook per dev restart would accumulate them.

**Failing well.** During `stop` → `mv` → `start`, the proxy's dials to `myapp:3000` are refused, and Caddy's default answer is a bare white `502 Bad Gateway` -- which reads as *broken*, not *busy*, and is the single most alarming thing a returning visitor can be shown for what is actually four seconds of scheduled restart. The Caddyfile closes that with its error handler:

```
handle_errors {
	@upstream-down expression `{err.status_code} in [502, 503, 504]`
	handle @upstream-down {
		root * /static
		rewrite * /error/503.html
		header Cache-Control "no-store"
		header Retry-After "5"
		file_server {
			status 503
		}
	}
}
```

Every line is a small protocol decision. `handle_errors` fires only on errors *Caddy itself* generates -- a refused dial, a proxy timeout -- never on status codes the application returns, so an app-rendered 404 or [a validation 422](21-forms-validation.md) passes through untouched and the maintenance page can never mask a real bug. The status is forced to `503`, not passed through as `502`, because 503 is HTTP's word for *temporarily* down, and paired with `Retry-After` it tells crawlers to come back later rather than record an outage -- protecting [the SEO surface](30-machine-legibility.md) on every deploy. And `no-store` keeps the one page that must never be cached out of every cache.

The page itself (`static/error/503.html`, copied through the asset pipeline unhashed) is deliberately self-contained: inline styles, an inline SVG, no reference to any other asset -- a page for the moment the application is away should depend on nothing the application resolves. Its one active ingredient is `<meta http-equiv="refresh" content="5">`: the browser retries on its own, and the visitor who hit the window watches a branded "back in a moment" card replace itself with the real page the moment the JVM answers the health poll's same question. No JavaScript, no countdown theater. (One static file cannot negotiate `Accept-Language`, so the page speaks the product's default locale -- the same default [the i18n chapter](12-i18n.md) falls back to. A per-language error page would be a Caddy `header_regexp` away; one language for four seconds was judged enough.)

Both halves were verified against the real thing rather than the documentation: a Caddy with the companion repository's config and a dead upstream serves the branded page with `503`, `Retry-After: 5`, and `no-store`; with a live upstream, its responses -- including its error responses -- pass through byte-for-byte; and a SIGTERM to the running jar prints the drain, closes the port, and exits. What this section does *not* buy is zero-downtime: a request arriving inside the window still sees the maintenance card, and eliminating the window itself is the two-node, drain-behind-the-proxy work the afterword scopes out. The single-node deploy now drops nothing, alarms no one, and heals itself. Zero-*surprise* downtime is the honest name for it.

Static assets are served by Caddy, not by the JVM. In the companion repository's Caddyfile, the proxy applies `Cache-Control: public, max-age=31536000, immutable` to any content-hashed filename (the `\.([a-f0-9]{8})\.(css|js)$` pattern) and to the version-pinned `idiomorph-*.min.js`, and it sets the long-lived security headers (HSTS, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, and the `Cross-Origin-Opener-Policy`/`Cross-Origin-Resource-Policy` isolation pair). It does **not** set a Content-Security-Policy: the per-document CSP carries the SHA-256 hashes of the application's inline scripts and import map, so the *application* must own it. This is why immutable caching and asset integrity are two sides of one coin -- once a hashed file is out, browsers may cache it for a year, so `verify-assets` is the last chance to catch a hash that does not match its bytes.

The SSH key (`deploy_ed25519`) is pre-installed on the CI runner and grants access to a `deploy` user on the application server. This user has limited permissions -- it owns `/opt/myapp`, so the jar swap itself needs no privilege; beyond that it can write to the static directory and run the deploy script under the three `systemctl` sudo rights named above, nothing else.

## The pipeline order

The stages are ordered from fastest to slowest, and from cheapest to most expensive:

1. **Format check** -- Seconds. No dependencies needed. Catches formatting issues before anything else runs.
2. **Lint** -- Seconds. Static analysis only. Catches code quality issues early.
3. **Scan dependencies for CVEs** -- Seconds once the NVD feed is cached (minutes on the first run, which downloads it). A static classpath check; catches a known-vulnerable transitive dependency before it ships.
4. **Build assets** -- Seconds. Runs Tailwind once and esbuild per module to produce the hashed, minified served tree plus the manifest.
5. **Verify asset integrity** -- A couple of seconds, almost all of it JVM startup. No new tools, just hashing: every content-hashed filename must match its bytes and every manifest target must exist.
6. **Coverage** -- Tens of seconds. Runs the test suite. First stage that executes application code.
7. **E2E tests** -- Minutes. Launches a browser and runs user flows. Expensive but catches integration bugs.
8. **Lighthouse** -- Minutes. Launches a browser and audits performance. Catches regressions.
9. **Uberjar** -- Tens of seconds. Compiles everything with strict flags. Produces the deployable artifact.
10. **Deploy** -- Seconds. Copies files and restarts the app.

If formatting is wrong, you find out in seconds, not after waiting for the entire test suite and build to run. This fast feedback loop makes CI less painful -- developers fix issues quickly because the pipeline tells them quickly.

## Where this leaves us

The whole pipeline runs inside Podman containers built from a single Dockerfile, with host-mounted cache volumes for fast dependency resolution: pull requests get the full quality-gate treatment without deploying, and only pushes to main reach production. The standards are enforced every time rather than remembered -- save the one still-local check, the time-discipline grep, which joins them the moment you call `./lint` in place of the bare `clj-kondo` step.

A closing note on scope, since the chapter opened on it: the workflow above is the *application* pipeline, not the mdBook-to-Pages job that actually ships these chapters. The practical takeaway needs no runner of your own, though -- `clojure -T:build assets` followed by `clojure -T:build verify-assets` is a two-command pre-deploy ritual you can run by hand: build the tree, prove its integrity, then ship it.

That ritual is the whole book in miniature. Every part of this stack is small enough for one person to build from first principles, hold in their head, and audit before it ships -- a single Dockerfile, a single pipeline definition, a shell script and SSH for deployment. That was the wager the first chapter made: that an application you own completely, with no framework's recovery machinery to maintain, is not the harder path but the simpler one, once you have built the few pieces it asks of you. Every moving part is still a part that can break at 2 AM with no one else to call -- but each one here is yours, transparent, and within a single person's reach to debug. The stack is complete, from the reproducible environment it is built in to the pipeline that ships it. That was the point.
