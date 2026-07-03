# A Reproducible Clojure Dev Environment with Devcontainers

## Why reproducible environments matter

"It works on my machine" is the most expensive sentence in software development. Not because it is hard to fix in the moment, but because it drains time from every new contributor, every OS upgrade, and every debugging session where the root cause turns out to be a missing system dependency.

For a Clojure/Datomic SaaS, the problem compounds. You need a JDK, the Clojure CLI, Node.js (for CSS tooling and browser testing), a mail server for transactional email testing, TLS certificates so your local environment mirrors production, and a reverse proxy to tie it all together. Setting this up manually once is tedious. Keeping it consistent across machines and months of drift is a losing battle.

Several tools address this, and they are worth putting side by side before committing to one:

- A **setup script** (`./install.sh` plus a README) is the zero-dependency option. It is transparent, but it *documents* the steps rather than *enforcing* them: it drifts the moment a machine already has a different JDK, and it has nothing to say about the services the app talks to -- the mail server, the TLS proxy.
- **Nix** (or `devenv`) is the most rigorous answer, reproducible down to the hash. It is also a second language and toolchain for the team to learn, and it pins the *toolchain* -- you still need something to run Mailpit and a proxy alongside the app.
- A **VM** (Vagrant) reproduces a whole machine, but it is heavy, slow to boot, and awkward on Apple Silicon.
- **Docker Compose on its own** pins the services and their images, but not the *editor* side -- the REPL, the extensions, the in-container shell where you actually work.

A **devcontainer** is Docker Compose plus that editor integration, defined as code in the repository. It pins the toolchain *and* the services, *and* it drops your editor inside the same container the app runs in -- and that last part is why we choose it for a REPL-driven Clojure workflow specifically. The nREPL, the file watcher, and the app all live in one place, so "connect your editor" and "run the app" are the same environment rather than two that have to be kept in step. The cost is a hard dependency on Docker and on an editor that speaks the devcontainer protocol (VS Code, or the JetBrains and `devcontainer`-CLI clients); a team already all-in on Nix has a defensible different answer. We take devcontainers because they reproduce the *whole* topology -- tooling, services, networking, and editor -- from one definition.

A complete devcontainer setup for a Clojure SaaS application has six parts:

- A Dockerfile with Java, Clojure, and Node.js
- A `devcontainer.json` that wires it into VS Code with Calva
- A `compose.yml` that orchestrates your app container alongside supporting services
- A Caddy reverse proxy providing local HTTPS with auto-generated TLS certificates
- A Mailpit instance for testing transactional emails
- Everything accessible via `.lan` domains, just like production uses real domains

## Project layout

Here is how the devcontainer-related files are organized in the repository:

```
myapp/
  .devcontainer/
    Dockerfile
    devcontainer.json
    importcerts.sh
  caddy/
    Caddyfile
  certificates/
    createcerts.sh
    openssl.cnf
  compose.yml
```

The `.devcontainer/` directory is the standard location that VS Code looks for. The `compose.yml` lives at the project root because it defines the full development topology, not just the devcontainer itself.

## The Dockerfile

The Dockerfile is the foundation. It builds a single image that contains everything you need to develop, test, and debug a Clojure application.

We start from Microsoft's devcontainer base image, which includes utilities that make the VS Code Remote Containers extension work smoothly (the `vscode` user, sudo, common shell configuration):

```dockerfile
FROM mcr.microsoft.com/devcontainers/base:trixie
ARG DEBIAN_FRONTEND=noninteractive
```

### Locale configuration

Setting up UTF-8 locales properly matters more than you might expect. Clojure applications deal with text constantly, and locale mismatches cause subtle bugs in string handling, sorting, and file I/O:

```dockerfile
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

RUN sed -i -e 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen && \
    dpkg-reconfigure --frontend=noninteractive locales && \
    update-locale LANG=en_US.UTF-8
```

### System tools

Next comes a layer of system utilities. These are not strictly necessary for the application itself, but they make the devcontainer a productive place to live. When you are debugging a network issue at 10pm, you want `tcpdump` and `netcat` already installed, not fighting `apt-get` in a container with no internet:

```dockerfile
RUN apt-get update && apt-get install -y \
    bat \
    btop \
    build-essential \
    ca-certificates \
    curl \
    fd-find \
    fzf \
    git \
    gnupg \
    htop \
    httpie \
    inotify-tools \
    jq \
    libnss3-tools \
    netcat-openbsd \
    ripgrep \
    rlwrap \
    tcpdump \
    tmux \
    unzip \
    wget \
    yq \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
```

A handful of these earn their place for reasons that recur later in the book: `ripgrep` and `fd-find` make searching a large codebase fast, `inotify-tools` backs the file-watching the live-reload chapter depends on, `libnss3-tools` provides the `certutil` that imports our development CA into Chromium's certificate store, and `rlwrap` gives the Clojure REPL readline support. The rest are the ordinary comforts of a shell you have to live in -- the kind you only miss when they are absent at the wrong moment. (The listing is an excerpt: the repository's list runs longer with more of the same species -- `strace`, `iotop`, `nethogs`, `valgrind`, locale and apt plumbing -- and a later layer installs the `zprint` binary that [the build-hardening chapter](04-build-hardening.md)'s formatter calls. `.devcontainer/Dockerfile` is the full inventory.)

### Java (Eclipse Temurin)

Clojure runs on the JVM, so we need a JDK. Eclipse Temurin is the continuation of AdoptOpenJDK under the Eclipse Foundation (renamed in 2021) and provides production-quality builds:

```dockerfile
RUN wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor | tee /etc/apt/trusted.gpg.d/adoptium.gpg > /dev/null \
    && echo "deb https://packages.adoptium.net/artifactory/deb \
       $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
    | tee /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends temurin-25-jdk \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# The package lays the JDK down at /usr/lib/jvm/temurin-25-jdk-<arch>, where
# <arch> is Docker's TARGETARCH (amd64 or arm64). Using it keeps JAVA_HOME
# correct whether the image builds on an Intel runner or an Apple Silicon laptop.
ARG TARGETARCH
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-${TARGETARCH}
```

We install from the Adoptium APT repository rather than downloading a tarball. This way the JDK integrates properly with the system (alternatives, man pages) and gets security updates through `apt-get upgrade`. The `JAVA_HOME` path resolves through `TARGETARCH` rather than a hard-coded `amd64`, so the same Dockerfile builds correctly on arm64 -- which matters precisely because Apple Silicon is one of the reasons we reached for a container in the first place.

### Clojure CLI and tooling

With Java in place, we install the Clojure CLI along with two essential development tools -- Babashka (a fast Clojure scripting runtime) and clj-kondo (a linter):

```dockerfile
# Babashka - fast Clojure scripting
RUN curl -s https://raw.githubusercontent.com/babashka/babashka/master/install \
    | bash

# clj-kondo - static analysis
RUN curl -s https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo \
    | bash

# Clojure CLI
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh \
    && chmod +x linux-install.sh \
    && ./linux-install.sh \
    && clojure -M -e '(println "Clojure installed")'
```

That last line -- `clojure -M -e '(println "Clojure installed")'` -- is not just a smoke test. It forces the Clojure CLI to download its core dependencies during the image build, so the first `clojure` invocation inside the container does not hit the network.

### Node.js

Node.js is needed for CSS tooling (Tailwind CSS), browser testing (Playwright), and other frontend build tasks. We install it via `nvm` so we can pin to the LTS version:

```dockerfile
ENV NVM_DIR=/usr/local/nvm
RUN mkdir -p "$NVM_DIR" \
    && curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash \
    && . "$NVM_DIR/nvm.sh" \
    && nvm install --lts \
    && nvm alias default lts/* \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/node" /usr/local/bin/node \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/npm"  /usr/local/bin/npm \
    && ln -s "$NVM_DIR/versions/node/$(nvm version)/bin/npx"  /usr/local/bin/npx \
    && npm install -g @playwright/test tailwindcss @tailwindcss/cli \
    && playwright install --with-deps

ENV NODE_PATH=/usr/local/lib/node_modules
```

The symlinks from `/usr/local/bin/` are important. `nvm` manages Node through shell functions that only work in login shells, but many tools (VS Code tasks, CI scripts, `make`) run in non-login shells. The symlinks ensure `node`, `npm`, and `npx` are available everywhere.

The `playwright install --with-deps` line downloads browser binaries (Chromium, Firefox, WebKit) and their system dependencies. This is a large download, but doing it at build time means browser tests run instantly during development.

### A note on pinning

One honesty is owed here, because this chapter argues for reproducibility and these installs don't fully deliver it. The JDK comes from apt, Babashka and clj-kondo from their `master` install scripts, the Clojure CLI from `latest`, Node from `--lts`, and -- later in `compose.yml` -- Mailpit from an image `:latest` tag. Each pulls *a* current version rather than *a specific* one, so two builds months apart can drift. That is the single place this chapter relaxes the principle it spends its length defending, and it does so deliberately, to keep the listings short and the moving parts legible. To close the gap for an image you can rebuild bit-for-bit, pin every one: pass `--version` to the Babashka and clj-kondo install scripts, install the Clojure CLI and Node at named versions, and tag `axllent/mailpit` with a release instead of `:latest`. The rule is to pin; we are naming the spot where the example chooses brevity over it rather than letting you discover the drift yourself.

## devcontainer.json

The `devcontainer.json` file tells VS Code how to build and connect to the development container:

```json
{
  "name": "myapp",
  "dockerComposeFile": ["../compose.yml"],
  "service": "myapp",
  "workspaceFolder": "/workspace",
  "customizations": {
    "vscode": {
      "extensions": [
        "betterthantomorrow.calva",
        "betterthantomorrow.joyride"
      ]
    }
  },
  "remoteUser": "root"
}
```

Five of its fields carry decisions:

**`dockerComposeFile` points to `compose.yml`** at the project root, not a standalone Dockerfile. This is important. When you use Compose, VS Code starts the entire service topology -- your app container plus Caddy, Mailpit, and any other services -- in one operation. You do not need to remember to start supporting services separately.

**`service` names which container VS Code attaches to.** The other containers run alongside but VS Code's terminal, file explorer, and extensions all operate inside this one.

**`workspaceFolder` is `/workspace`**, where the project source gets mounted.

**Calva is the load-bearing extension.** Calva provides Clojure language support, REPL integration, structural editing (paredit), and inline evaluation. It connects to your running application's nREPL server, giving you the ability to evaluate code in the context of your live application, not just a standalone REPL. The repo also installs **Joyride** alongside it -- VS Code scripted in ClojureScript -- but Calva is the one this book leans on.

**`remoteUser` is `root`.** In a devcontainer that is throwaway by nature, running as root avoids permission headaches with mounted volumes and installed tools. This is a development environment, not production.

## compose.yml

The `compose.yml` defines the full development topology. Here is the structure with each service explained:

### The application container

```yaml
services:
  myapp:
    build:
      context: ./.devcontainer
      dockerfile: Dockerfile
    hostname: myapp
    depends_on:
      certificates:
        condition: service_completed_successfully
    entrypoint: ["/bin/bash"]
    command: ["-c", "/workspace/.devcontainer/importcerts.sh && tail -f /dev/null"]
    environment:
      - CA_CERTS=/certificates/rootCA.crt
    volumes:
      - .:/workspace:cached
      - certificates:/certificates
    networks:
      default:
      myapp-network:
```

The app container waits for the `certificates` service to complete (more on that below), then runs `importcerts.sh` to trust the development CA, and finally `tail -f /dev/null` to keep the container running. VS Code attaches to this container and takes over from there.

The `:cached` mount flag on the workspace volume tells Docker to optimize for read performance on the host side. This makes file access noticeably faster on macOS, where Docker's filesystem bridging adds latency.

The container joins two networks: `default` (for inter-service communication) and `myapp-network` (a dedicated bridge network). This separation keeps the service topology clean and allows the Caddy reverse proxy to reach the application.

### TLS certificate generation

Local HTTPS is not a nicety here; it is what keeps a whole class of bugs from hiding until deploy. An environment that runs plain HTTP under a production that runs HTTPS defers every secure-cookie, CORS, mixed-content, and redirect-behavior bug to the one place they are most expensive to find -- after the thing has shipped. Running real TLS locally moves those failures onto the machine where you can still see them.

The certificate setup has three parts.

**First, a one-shot init container generates a root CA and per-host certificates:**

```yaml
  certificates:
    image: eclipse-temurin:25-jre-alpine
    entrypoint: ["/bin/ash"]
    command: "/opt/createcerts.sh"
    working_dir: /certificates
    volumes:
      - certificates:/certificates
      - ./certificates/createcerts.sh:/opt/createcerts.sh:ro
      - ./certificates/openssl.cnf:/opt/openssl.cnf:ro
```

This container runs once, creates the certificates in a shared Docker volume, and exits. The script is idempotent -- if the root CA key already exists, it skips everything:

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(dirname "$0")

HOSTS='myapp.lan mailpit.lan'

if [ -e "rootCA.key" ]; then
    echo "Certificates already created, skipping."
    exit 0
fi

echo "Creating root CA"
openssl genrsa -out rootCA.key 4096
openssl req -x509 -new -nodes -key rootCA.key -sha256 -days 825 \
    -out rootCA.crt \
    -subj "/C=NL/ST=Utrecht/L=Amersfoort/O=myapp/OU=IT/CN=myapp development CA" \
    -addext "basicConstraints = CA:TRUE" \
    -addext "keyUsage = keyCertSign, cRLSign"

for CN in $HOSTS; do
    echo "Creating certificate for $CN"
    mkdir -p "$CN"

    openssl req -new \
        -newkey rsa:2048 -nodes -keyout "$CN/$CN.key" \
        -out "$CN/$CN.csr" \
        -subj "/C=NL/ST=Utrecht/L=Amersfoort/O=myapp/OU=IT/CN=$CN" \
        -addext "subjectAltName = DNS:$CN"

    # Build the SAN extension file without process substitution: this script
    # runs under Alpine's /bin/ash (busybox), which does not support <(...).
    ext="$CN/ext.cnf"
    cat "$SCRIPT_DIR/openssl.cnf" > "$ext"
    printf '\nDNS.1 = %s\n' "$CN" >> "$ext"

    openssl x509 -req -in "$CN/$CN.csr" \
        -CA rootCA.crt -CAkey rootCA.key -CAcreateserial \
        -out "$CN/$CN.crt" -days 500 -sha256 \
        -extfile "$ext"

    openssl verify -CAfile rootCA.crt -verify_hostname $CN "$CN/$CN.crt"
done
```

One line ties the script to the compose service above: `SCRIPT_DIR`. The container's working directory is the certificates *volume*, but `openssl.cnf` is mounted beside the script in `/opt` -- so the config must be read from the script's own directory, not the current one. (The listing is lightly abridged: the repository's copy also dumps each certificate with `x509 -text` for inspection and exports JKS/PKCS12 keystores alongside the PEMs.)

The OpenSSL configuration for the SAN (Subject Alternative Name) extension is minimal:

```ini
basicConstraints       = CA:FALSE
authorityKeyIdentifier = keyid:always, issuer:always
keyUsage               = nonRepudiation, digitalSignature, keyEncipherment, dataEncipherment
subjectAltName         = @alt_names

[ alt_names ]
```

The `DNS.1` entry is appended dynamically by the script for each host.

**Second, the app container imports the root CA into every trust store it needs:**

```bash
#!/bin/bash
set -euo pipefail

CERT_FILE="/certificates/rootCA.crt"

if [ ! -f "$CERT_FILE" ]; then
    echo "No root CA certificate found at $CERT_FILE, skipping import."
    exit 0
fi

# Java applications need the CA in the JVM trust store
echo "Importing root CA into Java trust store..."
keytool -import -file "$CERT_FILE" -alias development -noprompt \
    -trustcacerts -keystore "$JAVA_HOME/lib/security/cacerts" \
    -storepass changeit || true

# System-level trust (curl, wget, etc.)
echo "Importing root CA into system CA store..."
cp "$CERT_FILE" /usr/local/share/ca-certificates/myapp-dev.crt
update-ca-certificates

# Chromium uses NSS, not the system store
echo "Importing root CA into NSS database (Chromium)..."
rm -rf "$HOME/.pki/nssdb"
mkdir -p "$HOME/.pki/nssdb"
certutil -d "sql:$HOME/.pki/nssdb" -N --empty-password
certutil -d "sql:$HOME/.pki/nssdb" -A -t 'C,,' -n 'myapp Dev CA' \
    -i "$CERT_FILE" -f /dev/null

echo "All certificates imported successfully."
```

Three trust stores, three import mechanisms. The Java trust store (`cacerts`) is needed for any HTTP client calls the Clojure application makes (calling external APIs, OIDC discovery). The system CA store covers command-line tools. The NSS database is what Chromium (and therefore Playwright) uses. Missing any one of these causes hard-to-diagnose TLS failures.

**Third, the certificates are stored in a named Docker volume:**

```yaml
volumes:
  certificates:
    driver: local
```

This means certificates survive container restarts. They are only regenerated if you explicitly delete the volume (`docker volume rm`). No waiting for certificate generation on every restart.

### Caddy reverse proxy

Caddy serves as the ingress layer, providing HTTPS termination and routing to backend services:

```yaml
  ingress:
    image: docker.io/library/caddy:2-alpine
    hostname: ingress
    depends_on:
      certificates:
        condition: service_completed_successfully
    volumes:
      - ./caddy/Caddyfile:/etc/caddy/Caddyfile:ro
      - ./myapp/static:/static:ro
      - certificates:/certs
    expose:
      - "80"
      - "443"
    networks:
      default:
        aliases:
          - "myapp.lan"
          - "mailpit.lan"
      myapp-network:
```

The `aliases` on the default network are the key detail. They make `myapp.lan` and `mailpit.lan` resolve to the Caddy container from within the Docker network. Your Clojure application can call `https://myapp.lan` and it works -- the request goes to Caddy, which terminates TLS and proxies to the app container.

We use `expose` (not `ports`) because we access services through the Docker network, not from the host. This avoids port conflicts with anything else running on your machine.

The Caddyfile itself is straightforward -- shown here as this chapter creates it; [the asset-pipeline chapter](24-asset-pipeline.md) later adds a block of request-invariant security headers (HSTS, `nosniff`, COOP/CORP) and an immutable-cache rule for the vendored morphing library:

```caddyfile
myapp.lan {
    tls /certs/myapp.lan/myapp.lan.crt /certs/myapp.lan/myapp.lan.key
    encode zstd gzip

    root * /static
    @static file
    handle @static {
        # Hashed filenames (styles.<hash>.css) are immutable
        @hashed path_regexp \.([a-f0-9]{8})\.(css|js)$
        header @hashed Cache-Control "public, max-age=31536000, immutable"

        # Static assets that rarely change
        @assets path *.svg *.png *.jpg *.woff2
        header @assets Cache-Control "public, max-age=604800"

        file_server
    }

    handle {
        reverse_proxy myapp:3000
    }
}

mailpit.lan {
    tls /certs/mailpit.lan/mailpit.lan.crt /certs/mailpit.lan/mailpit.lan.key
    reverse_proxy mailpit:8025
}
```

There are two routing strategies in the `myapp.lan` block. Requests that match a file in `/static` are served directly by Caddy with appropriate cache headers. Everything else is proxied to the Clojure application on port 3000. This mirrors a common production pattern where static assets are served by a CDN or web server, not the application.

The cache headers distinguish between two kinds of static assets:

- **Hashed filenames** like `styles.a1b2c3d4.css` get a one-year cache with `immutable`. The hash in the filename changes when the content changes, so aggressive caching is safe.
- **Other assets** (images, fonts) get a one-week cache. These change less frequently but their filenames do not include content hashes.

The Mailpit block is simpler -- just TLS termination and a straight proxy to the Mailpit web UI.

### Mailpit for email testing

Every SaaS application sends email: signup confirmations, password resets, invoice notifications. You need to test this locally without actually sending email to real addresses.

Mailpit is an SMTP server and web UI that captures outgoing email. Your application sends to `mailpit:1025` (SMTP), and you view the results at `https://mailpit.lan` (through Caddy):

```yaml
  mailpit:
    image: axllent/mailpit:latest
    container_name: myapp-mailpit
    expose:
      - "1025"  # SMTP
      - "8025"  # Web interface
    networks:
      - myapp-network
    healthcheck:
      test: ["CMD-SHELL", "/mailpit readyz"]
      interval: 10s
      timeout: 5s
      retries: 5
```

In your Clojure application configuration, you point the SMTP host to `mailpit` and port to `1025`. Every email your application sends shows up in the Mailpit web UI instantly, with full HTML rendering, headers, and attachment inspection.

The health check ensures that services depending on Mailpit (indirectly, through your application) do not start sending emails before Mailpit is ready to receive them.

### Networks

```yaml
networks:
  myapp-network:
    driver: bridge
```

A dedicated bridge network keeps inter-service communication clean. All services that need to talk to each other join this network. Docker's built-in DNS resolves service names to container IPs automatically, so `mailpit:1025` and `myapp:3000` just work.

The listings above are the load-bearing services. The repo's `compose.yml` carries one more the book does not walk: a containerized **`browser`** service wired to the host X server (a `DISPLAY` environment variable and a `/tmp/.X11-unix` mount, mirrored on the app container) so a real Chromium can render the running app from inside the topology -- useful for the construction-view tooling later in the book. It is orthogonal to the dev loop here; see the file for the exact wiring.

## VS Code and Calva integration

When you open this project in VS Code, the Remote Containers extension detects `.devcontainer/devcontainer.json` and offers to reopen the project in a container. Accepting is the whole procedure. Everything that follows -- the image build (cached after the first time), the Compose topology coming up, the certificate init container completing, the CA import, the attach, Calva installing itself inside the container -- happens because the checked-in files say so, in the order their dependencies dictate.

Start the REPL (`clojure -M:dev:repl`; [the web-server chapter](05-web-server.md) adds the `(start!)` that brings the app up inside it) and Calva connects to its nREPL server. That connection is the center of Clojure development, and the reason this chapter cares so much about reproducibility: with a live application attached to the editor, evaluation replaces restart. Put the cursor on an expression and it evaluates *in the running app*; redefine a function and the next request uses it; inspect a value where it actually lives. The keystrokes are for Calva's documentation to teach -- the capability is what this book leans on, starting with the live-reload loop of [chapter 6](06-live-reload.md), which exists to carry exactly this immediacy from the editor into the browser.

## What you have, and what it buys

What all of this purchases is a single answer to the question every contributor otherwise answers differently: *what does it take to run this?* Open the project and the pieces assemble in order on their own -- the `certificates` container mints the CA and per-host certificates and exits, the app container trusts them and waits, Caddy picks them up and serves HTTPS on `myapp.lan` and `mailpit.lan`, Mailpit comes up on SMTP, and VS Code attaches with Calva connected to the running REPL. None of it is a sequence you have to remember, because all of it is defined in files checked into the repository. The next time you -- or anyone else -- open the project, the environment is identical down to the JDK, the Node version, the certificates, and the proxy.

That is the gap a setup script never closes. "Which version of Java do I need?", "I can't get the certificates to work" -- the questions a README leaves to the reader, the definition answers in advance. And because it mirrors production from the first commit, the bugs that only surface under HTTPS, real TLS, and a reverse proxy surface here, on your machine, rather than after deployment. That is the foundation the rest of the book rests on: Datomic, the application's structure, authentication, the asset pipeline, and the deploy are each built on an environment that already looks like the one it will ship into.
