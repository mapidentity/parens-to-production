# A Reproducible Clojure Dev Environment with Devcontainers


---

## Why Reproducible Environments Matter

"It works on my machine" is the most expensive sentence in software development. Not because it is hard to fix in the moment, but because it drains time from every new contributor, every OS upgrade, and every debugging session where the root cause turns out to be a missing system dependency.

For a Clojure/Datomic SaaS, the problem compounds. You need a JDK, the Clojure CLI, Node.js (for CSS tooling and browser testing), a mail server for transactional email testing, TLS certificates so your local environment mirrors production, and a reverse proxy to tie it all together. Setting this up manually once is tedious. Keeping it consistent across machines and months of drift is a losing battle.

Devcontainers solve this. You define your entire development environment -- tooling, services, networking -- as code in your repository. Open the project in VS Code, and the environment builds itself. Every time, the same way.

This chapter walks through a complete devcontainer setup for a Clojure SaaS application. By the end, you will have:

- A Dockerfile with Java, Clojure, and Node.js
- A `devcontainer.json` that wires it into VS Code with Calva
- A `compose.yml` that orchestrates your app container alongside supporting services
- A Caddy reverse proxy providing local HTTPS with auto-generated TLS certificates
- A Mailpit instance for testing transactional emails
- Everything accessible via `.lan` domains, just like production uses real domains

Let's build it.

---

## Project Layout

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

---

## The Dockerfile

The Dockerfile is the foundation. It builds a single image that contains everything you need to develop, test, and debug a Clojure application.

We start from Microsoft's devcontainer base image, which includes utilities that make the VS Code Remote Containers extension work smoothly (the `vscode` user, sudo, common shell configuration):

```dockerfile
FROM mcr.microsoft.com/devcontainers/base:trixie
ARG DEBIAN_FRONTEND=noninteractive
```

### Locale Configuration

Setting up UTF-8 locales properly matters more than you might expect. Clojure applications deal with text constantly, and locale mismatches cause subtle bugs in string handling, sorting, and file I/O:

```dockerfile
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

RUN sed -i -e 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen && \
    dpkg-reconfigure --frontend=noninteractive locales && \
    update-locale LANG=en_US.UTF-8
```

### System Tools

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
    tmux \
    unzip \
    wget \
    yq \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
```

A few highlights: `ripgrep` and `fd-find` make searching large codebases fast. `inotify-tools` is needed for file-watching (hot reload). `libnss3-tools` provides `certutil`, which we will use later to import our development CA into Chromium's certificate store. `rlwrap` gives readline support to the Clojure REPL.

### Java (Eclipse Temurin)

Clojure runs on the JVM, so we need a JDK. Eclipse Temurin is the community-driven successor to AdoptOpenJDK and provides production-quality builds:

```dockerfile
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
```

We install from the Adoptium APT repository rather than downloading a tarball. This way the JDK integrates properly with the system (alternatives, man pages) and gets security updates through `apt-get upgrade`.

### Clojure CLI and Tooling

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

---

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
        "betterthantomorrow.calva"
      ]
    }
  },
  "remoteUser": "root"
}
```

A few things to note:

**`dockerComposeFile` points to `compose.yml`** at the project root, not a standalone Dockerfile. This is important. When you use Compose, VS Code starts the entire service topology -- your app container plus Caddy, Mailpit, and any other services -- in one operation. You do not need to remember to start supporting services separately.

**`service` names which container VS Code attaches to.** The other containers run alongside but VS Code's terminal, file explorer, and extensions all operate inside this one.

**`workspaceFolder` is `/workspace`**, where the project source gets mounted.

**Calva is the only required extension.** Calva provides Clojure language support, REPL integration, structural editing (paredit), and inline evaluation. It connects to your running application's nREPL server, giving you the ability to evaluate code in the context of your live application, not just a standalone REPL.

**`remoteUser` is `root`.** In a devcontainer that is throwaway by nature, running as root avoids permission headaches with mounted volumes and installed tools. This is a development environment, not production.

---

## compose.yml

The `compose.yml` defines the full development topology. Here is the structure with each service explained:

### The Application Container

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

### TLS Certificate Generation

Local HTTPS matters. If your development environment uses plain HTTP while production uses HTTPS, you will hit bugs related to secure cookies, CORS, mixed content, and redirect behavior that only appear after deployment. Better to catch them during development.

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
#!/bin/env bash
set -euo pipefail

HOSTS='myapp.lan mailpit.lan'

if [ -e "rootCA.key" ]; then
    echo "Certificates already created, skipping."
    exit 0
fi

echo "Creating root CA"
openssl genrsa -out rootCA.key 4096
openssl req -x509 -new -nodes -key rootCA.key -sha256 -days 825 \
    -out rootCA.crt \
    -subj "/C=NL/ST=Utrecht/L=Amersfoort/O=MyApp/OU=IT/CN=MyApp development CA" \
    -addext "basicConstraints = CA:TRUE" \
    -addext "keyUsage = keyCertSign, cRLSign"

for CN in $HOSTS; do
    echo "Creating certificate for $CN"
    mkdir -p "$CN"

    openssl req -new \
        -newkey rsa:2048 -nodes -keyout "$CN/$CN.key" \
        -out "$CN/$CN.csr" \
        -subj "/C=NL/ST=Utrecht/L=Amersfoort/O=MyApp/OU=IT/CN=$CN" \
        -addext "subjectAltName = DNS:$CN"

    openssl x509 -req -in "$CN/$CN.csr" \
        -CA rootCA.crt -CAkey rootCA.key -CAcreateserial \
        -out "$CN/$CN.crt" -days 500 -sha256 \
        -extfile <(cat openssl.cnf <(printf "\nDNS.1 = $CN"))

    openssl verify -CAfile rootCA.crt -verify_hostname $CN "$CN/$CN.crt"
done
```

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
    echo "No root CA certificate found, skipping import."
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
certutil -d "sql:$HOME/.pki/nssdb" -A -t 'C,,' -n 'MyApp Dev CA' \
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

### Caddy Reverse Proxy

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

The Caddyfile itself is straightforward:

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

### Mailpit for Email Testing

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

---

## VS Code and Calva Integration

When you open this project in VS Code, the Remote Containers extension detects `.devcontainer/devcontainer.json` and offers to reopen the project in a container. Accept, and VS Code will:

1. Build the Docker image from the Dockerfile (first time only, then cached)
2. Start all services defined in `compose.yml`
3. Wait for the certificate init container to complete
4. Import the development CA into the app container's trust stores
5. Attach VS Code to the app container
6. Install the Calva extension inside the container

From there, you start your Clojure application (typically `clojure -M:dev` or however your project is configured), and Calva connects to the nREPL server. Once connected, you can:

- **Evaluate expressions inline** -- put your cursor on an expression and hit `Ctrl+Enter` (or `Cmd+Enter` on macOS) to evaluate it in the running application
- **Load files** -- save a file and it gets loaded into the running REPL automatically (with a file watcher)
- **Inspect values** -- evaluate expressions and see results inline in your editor
- **Navigate code** -- jump to definitions, find references, all powered by clj-kondo's static analysis

This is the core of Clojure development: a live, running application that you modify interactively through your editor.

---

## Putting It All Together

Here is what happens when you open the project for the first time:

1. VS Code reads `.devcontainer/devcontainer.json`
2. Docker Compose builds and starts all services
3. The `certificates` init container generates a root CA and per-host TLS certificates, then exits
4. The app container starts, runs `importcerts.sh` to trust the CA, and waits
5. Caddy picks up the certificates and begins serving HTTPS on `myapp.lan` and `mailpit.lan`
6. Mailpit starts accepting SMTP on port 1025
7. VS Code attaches to the app container and installs Calva

From this point, you start your Clojure application and begin developing. The environment provides:

- **`https://myapp.lan`** -- your application, served through Caddy with TLS, static file serving, and compression
- **`https://mailpit.lan`** -- the Mailpit web UI, showing every email your application sends
- **A fully configured JDK, Clojure CLI, and Node.js** -- ready for application development, CSS builds, and browser testing
- **Calva in VS Code** -- connected to your running application's REPL for interactive development

And because all of this is defined in files checked into your repository, the next time you (or anyone else) opens the project, they get exactly the same environment. No setup guide to follow. No "which version of Java do I need?" No "I can't get the certificates to work." It just works.

---

## What Comes Next

This chapter covered the development environment -- the foundation everything else builds on. The chapters that follow build on this foundation: setting up Datomic, structuring the Clojure application, implementing authentication, and deploying to production.

But for now, you have something valuable: a reproducible, fully-featured development environment that mirrors production from day one. Every hour invested in getting this right pays dividends for the lifetime of the project.
