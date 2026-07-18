// Client-side error capture: the last telemetry gap. Server exceptions land
// in the operator's log (wrap-errors); CSP violations report themselves
// (/csp-report); but an exception inside an island used to die in the
// visitor's console, invisible to everyone who could fix it. This module
// forwards it to the same kind of sink.
//
// Listed FIRST among the modules on purpose: module execution follows
// document order, so these listeners are attached before any other island
// has run a line.

const LIMIT = 5; // per page load — an error loop must not become a beacon loop
let sent = 0;

function report(payload) {
  if (sent >= LIMIT) return;
  sent += 1;
  const body = JSON.stringify(payload);
  // sendBeacon survives page unload and never blocks the page; fetch with
  // keepalive is the fallback where beacons are unavailable.
  if (!(navigator.sendBeacon && navigator.sendBeacon('/client-error', body))) {
    fetch('/client-error', { method: 'POST', body, keepalive: true }).catch(() => {});
  }
}

window.addEventListener('error', (e) => {
  report({
    kind: 'error',
    message: String(e.message || '').slice(0, 500),
    source: String(e.filename || '').slice(0, 300),
    line: e.lineno || 0,
    col: e.colno || 0,
    stack: String((e.error && e.error.stack) || '').slice(0, 2000),
    url: location.pathname,
  });
});

window.addEventListener('unhandledrejection', (e) => {
  const r = e.reason;
  report({
    kind: 'unhandledrejection',
    message: String((r && r.message) || r || '').slice(0, 500),
    stack: String((r && r.stack) || '').slice(0, 2000),
    url: location.pathname,
  });
});
