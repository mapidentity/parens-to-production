// Playwright configuration for e2e auth flow tests.
// The webServer config auto-starts the Clojure e2e server before tests run.

module.exports = {
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:9876',
    locale: 'en-US',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        browserName: 'chromium',
        launchOptions: {
          args: ['--no-sandbox', '--disable-dev-shm-usage'],
        },
      },
    },
  ],
  webServer: {
    command: 'clojure -X:test myapp.e2e-server/start!',
    url: 'http://localhost:9876/health',
    // 5 min: locally the server is up in ~6s, but CI runs the e2e step
    // in a fresh container with its own JVM (the prior :coverage step
    // doesn't share JVM/classloader). Loading Datomic peer + compiling
    // every required Clojure namespace from source on a constrained CI
    // runner can push past 2 min, especially under memory pressure.
    // 120s was bumping into that ceiling intermittently.
    timeout: 300_000,
    reuseExistingServer: !process.env.CI,
    // Pipe server stdout/stderr to the Playwright reporter so any
    // startup error is visible in CI logs alongside the timeout message.
    stdout: 'pipe',
    stderr: 'pipe',
  },
};
