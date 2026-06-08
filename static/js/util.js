// Shared ESM utilities for the Tier-A modules (dispatcher.js and its
// consumers). Plain ES module served by Caddy at /js/util.js.

export const DEFAULT_DELAY_MS = 300;

// Returns a debounced wrapper of `fn`: each call resets a timer, and `fn` only
// fires `ms` after the last call. Used to coalesce input/keystroke bursts.
export function debounce(ms, fn) {
  let timer = null;
  return function (...args) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => { timer = null; fn(...args); }, ms);
  };
}
