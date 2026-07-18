// Dispatcher: progressive enhancement for <a> clicks and <form> submits.
//
// Reads `href` from links and `action`/`method` from forms — no DSL, no
// per-element configuration. Fetches the response, idiomorphs <main>
// (or `data-target`), and updates history for navigation.
//
// What the HTML already declares stays the source of truth. JS only
// speeds it up; if JS fails to load, every interaction still works via
// native form submission and full-page navigation.

// Idiomorph is loaded as a classic <script> ahead of this module and
// exposes a global `Idiomorph`. We use it via window.Idiomorph rather
// than importing — the library predates ESM and exporting it as a
// module would require a custom wrapper.

// Stable identifier so links share an AbortController per destination.
let inflight = new Map();

// Navigation pending indicator (Layer 1). A counter, not a boolean, so
// overlapping navigations never clear it early; CSS keyed on
// `html.is-navigating` paints a thin top progress bar. Live-preview updates
// (ignoreActiveValue) opt out — a per-keystroke bar would be visual noise.
let pendingNav = 0;
let navWatchdog = null;
const NAV_WATCHDOG_MS = 8000;
function setNavigating(on) {
  pendingNav = Math.max(0, pendingNav + (on ? 1 : -1));
  const active = pendingNav > 0;
  document.documentElement.classList.toggle('is-navigating', active);
  // Safety net, decoupled from fetchAndMorph's control flow: if a navigation
  // somehow never settles (e.g. a View Transition's updateCallbackDone parks),
  // the bar must not stay up forever. Force-clear after a generous timeout — by
  // then the navigation has either completed or visibly failed.
  if (navWatchdog) { clearTimeout(navWatchdog); navWatchdog = null; }
  if (active) {
    navWatchdog = setTimeout(() => {
      pendingNav = 0;
      document.documentElement.classList.remove('is-navigating');
    }, NAV_WATCHDOG_MS);
  }
}

function sameOrigin(url) {
  try {
    const u = new URL(url, window.location.href);
    return u.origin === window.location.origin;
  } catch {
    return false;
  }
}

function isFragmentOnly(a) {
  const href = a.getAttribute('href');
  if (!href) return true;
  if (href.startsWith('#')) return true;
  try {
    const u = new URL(href, window.location.href);
    return u.pathname === window.location.pathname
        && u.search === window.location.search
        && u.hash !== '';
  } catch {
    return false;
  }
}

function shouldEnhanceLink(a, e) {
  if (a.hasAttribute('data-no-enhance')) return false;
  if (a.getAttribute('target') === '_blank') return false;
  if (a.hasAttribute('download')) return false;
  if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return false;
  if (e.button !== undefined && e.button !== 0) return false;
  const href = a.getAttribute('href');
  if (!href) return false;
  if (!sameOrigin(href)) return false;
  if (isFragmentOnly(a)) return false;
  return true;
}

function shouldEnhanceForm(form) {
  if (form.hasAttribute('data-no-enhance')) return false;
  if (!form.action) return false;
  if (!sameOrigin(form.action)) return false;
  return true;
}

function morph(targetEl, newDocOrEl, opts) {
  const options = {
    morphStyle: 'innerHTML',
    callbacks: {},
    ...opts,
  };
  window.Idiomorph.morph(targetEl, newDocOrEl, options);
  // We deliberately do NOT re-execute <script> tags found in a morphed fragment.
  // All per-page behaviour attaches through the controller registry (data-
  // controller markers, re-scanned on `dispatcher:morphed`), so a fragment never
  // needs to carry an inline script — and the strict, no-nonce CSP authorises
  // only the inline scripts hashed at boot, never an arbitrary injected one. The
  // policy enumerates exactly what may run; injecting scripts into fragments is a
  // door we keep shut by construction. Inline scripts live once in <head>/<body>.
}

function pickResponseFragment(htmlString, selector) {
  // Parse the response HTML once; pull out either the target subtree or
  // <main>, whichever the caller asked for.
  const parser = new DOMParser();
  const doc = parser.parseFromString(htmlString, 'text/html');
  if (selector) {
    const el = doc.querySelector(selector);
    if (el) return { fragmentEl: el, parsedDoc: doc };
  }
  const mainEl = doc.querySelector('main');
  if (mainEl) return { fragmentEl: mainEl, parsedDoc: doc };
  // No <main>? Morph the whole body as a last resort.
  return { fragmentEl: doc.body, parsedDoc: doc };
}

function focusMain() {
  // After a navigation morph, move focus to <main> so screen readers
  // re-announce. Inputs that currently have focus stay focused — this
  // only runs when nothing in <main> claimed focus.
  const main = document.querySelector('main');
  if (!main) return;
  if (document.activeElement && main.contains(document.activeElement)) return;
  if (!main.hasAttribute('tabindex')) main.setAttribute('tabindex', '-1');
  main.focus({ preventScroll: true });
}

function updateTitle(parsedDoc) {
  const t = parsedDoc.querySelector('title');
  if (t && t.textContent) document.title = t.textContent;
}

function abortInflight(key) {
  const ctrl = inflight.get(key);
  if (ctrl) ctrl.abort();
  const next = new AbortController();
  inflight.set(key, next);
  return next.signal;
}

/**
 * Core primitive — fetch a URL, morph the response into the page.
 * @param {string} url
 * @param {object} opts
 *   method:      'GET' | 'POST'   (default 'GET')
 *   body:        FormData | URLSearchParams | string | null
 *   target:      CSS selector string; default 'main'
 *   pushUrl:     boolean — push history entry to fetched URL
 *   replaceUrl:  boolean — replace current history entry (used for popstate)
 *   key:         string for in-flight dedupe (default = url)
 *   focus:       boolean — move focus to <main> after morph (default = pushUrl)
 *   ignoreActiveValue: boolean — pass through to idiomorph
 *   morphCallbacks: idiomorph callbacks object — pass through to the morph
 */
export async function fetchAndMorph(url, opts = {}) {
  const {
    method = 'GET',
    body = null,
    target = 'main',
    pushUrl = false,
    replaceUrl = false,
    key,
    focus,
    ignoreActiveValue = false,
    morphCallbacks,
  } = opts;

  // Show the pending bar for real navigations/updates, but not for live-typing
  // previews (ignoreActiveValue), where a per-keystroke flash would be noise.
  const showPending = !ignoreActiveValue;
  if (showPending) setNavigating(true);
  try {
    const abortKey = key || (target + '|' + url);
    const signal = abortInflight(abortKey);

    let res;
    try {
      res = await fetch(url, {
        method,
        body,
        credentials: 'same-origin',
        redirect: 'follow',
        signal,
        headers: { 'Accept': 'text/html' },
      });
    } catch (err) {
      if (err.name === 'AbortError') return;
      // Network error. A GET falls back to a real navigation so the user is
      // never stranded on a dead link. A POST deliberately does nothing: a
      // native nav can't carry the body, and re-issuing the request risks a
      // duplicate write (the error may have struck after the server processed
      // it), so we leave the form and its typed-in values on screen for a retry.
      if (method === 'GET') window.location.assign(url);
      return;
    }

    const finalUrl = res.url || url;
    // Dev-only: the construction-view tracer stamps every HTML response with an
    // X-Myapp-Trace header. Tag the morphed region with it so the overlay can show
    // the construction of THIS partial update, not just the initial page load.
    // Absent in prod (header not set) → no-op.
    const traceId = res.headers.get('X-Myapp-Trace');
    const html = await res.text();

    // 4xx/5xx with HTML body — morph in place so server-rendered errors
    // appear. The server is the source of truth; client doesn't interpret.
    const { fragmentEl, parsedDoc } = pickResponseFragment(html, target);
    const targetEl = document.querySelector(target);
    if (!targetEl) {
      // Target disappeared — usually a stale debounced/late call after
      // the page already morphed away. Bail silently rather than forcing
      // a full nav (which would clobber the user's current location).
      return;
    }

    // Cross-layout navigation: if the response's <main data-layout> tag
    // differs from the live page's, the chrome structure is different
    // (e.g. public-layout → app-layout). An in-place morph would leave
    // the wrong chrome around; full reload is the honest fix.
    if (target === 'main') {
      const liveLayout = document.querySelector('main[data-layout]');
      const newLayout = parsedDoc.querySelector('main[data-layout]');
      if (liveLayout && newLayout
          && liveLayout.dataset.layout !== newLayout.dataset.layout) {
        window.location.assign(finalUrl);
        return;
      }
    }

    // Apply the server's new markup. Wrapping the mutation in a View Transition
    // lets the browser animate the change — a cross-fade of the root by default,
    // and shared-element FLIP for anything carrying a `view-transition-name`
    // (e.g. dashboard cards that move on reorder). This is Layer 2 of the
    // progressive-enhancement stack and is pure enhancement: where the API is
    // absent the same mutation just applies instantly. We skip it for live/typing
    // updates (ignoreActiveValue), where per-keystroke animation would be noise,
    // and when the user has asked for reduced motion.
    const applyMorph = () => {
      morph(targetEl, fragmentEl,
        { morphStyle: 'innerHTML', ignoreActiveValue,
          ...(morphCallbacks ? { callbacks: morphCallbacks } : {}) });
      // morphed region carries its own trace-id (see note above); dev-only.
      if (traceId) targetEl.setAttribute('data-myapp-trace-id', traceId);
    };
    const animate = !!document.startViewTransition
      && !ignoreActiveValue
      && !(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    if (animate) {
      await document.startViewTransition(applyMorph).updateCallbackDone;
    } else {
      applyMorph();
    }

    updateTitle(parsedDoc);

    // A non-GET morph that surfaced server-side field errors: move focus to
    // the first invalid control. Its aria-describedby points at the error
    // line, so screen readers announce the problem, and keyboard users land
    // exactly where the fix starts. GETs are exempt — stealing focus on
    // ordinary navigation would be worse than the disease.
    if (method !== 'GET') {
      const firstInvalid = targetEl.querySelector('[aria-invalid="true"]');
      if (firstInvalid) firstInvalid.focus();
    }

    // History bookkeeping. When fetch followed a 302, finalUrl reflects
    // the redirect target — we want that in the address bar, not the
    // originally-requested URL. Normalise both to absolute URLs before
    // comparing, otherwise a relative `url` vs an absolute `finalUrl`
    // would always look different even when they point to the same place.
    const absUrl = new URL(url, window.location.href).href;
    if (pushUrl) {
      history.pushState({ dispatcher: true }, '', finalUrl);
    } else if (replaceUrl) {
      history.replaceState({ dispatcher: true }, '', finalUrl);
    } else if (method !== 'GET' && finalUrl !== absUrl) {
      // POST followed by a server-side redirect (PRG pattern). Reflect
      // the new location in the address bar so a refresh doesn't re-POST.
      history.pushState({ dispatcher: true }, '', finalUrl);
    }

    const shouldFocus = focus === undefined ? pushUrl : focus;
    if (shouldFocus) focusMain();

    // Hook for modules / future features.
    document.dispatchEvent(new CustomEvent('dispatcher:morphed', {
      detail: { url: finalUrl, target, method, traceId },
    }));
  } finally {
    if (showPending) setNavigating(false);
  }
}

function onClick(e) {
  // Allow inline onclick handlers (event.stopPropagation, etc.) to short-circuit.
  if (e.defaultPrevented) return;
  const a = e.target.closest('a[href]');
  if (!a) return;
  if (!shouldEnhanceLink(a, e)) return;
  e.preventDefault();
  const href = a.getAttribute('href');
  const target = a.getAttribute('data-target') || 'main';
  fetchAndMorph(href, { method: 'GET', target, pushUrl: true });
}

function onSubmit(e) {
  // Honor inline onsubmit (e.g. return confirm('...')).
  if (e.defaultPrevented) return;
  const form = e.target;
  if (!(form instanceof HTMLFormElement)) return;
  if (!shouldEnhanceForm(form)) return;

  // Discover the actual submit element (HTML5 formaction/formmethod).
  const submitter = e.submitter;
  const action = (submitter && submitter.getAttribute('formaction')) || form.action;
  const method = ((submitter && submitter.getAttribute('formmethod'))
                  || form.method || 'GET').toUpperCase();
  const target = (submitter && submitter.getAttribute('data-target'))
                 || form.getAttribute('data-target')
                 || 'main';

  e.preventDefault();

  const formData = new FormData(form, submitter);
  if (method === 'GET') {
    const url = new URL(action, window.location.href);
    for (const [k, v] of formData.entries()) {
      url.searchParams.append(k, v);
    }
    fetchAndMorph(url.pathname + url.search, { method: 'GET', target, pushUrl: true });
  } else {
    // Match native form-submission encoding: URL-encoded by default,
    // multipart only when the form explicitly declares it (file uploads).
    // FormData-as-fetch-body sends multipart unconditionally; the server
    // only parses multipart on routes that opt in.
    const enctype = (submitter && submitter.getAttribute('formenctype')) || form.enctype;
    const body = enctype === 'multipart/form-data' ? formData : new URLSearchParams(formData);
    fetchAndMorph(action, {
      method,
      body,
      target,
      pushUrl: false,
    });
  }
}

function onPopState(e) {
  // Re-fetch the URL the browser is now showing and morph <main>.
  // Skip if it's not one of our own entries — but in practice every
  // entry on this page came from us, so the check is defensive.
  fetchAndMorph(window.location.pathname + window.location.search, {
    method: 'GET',
    target: 'main',
    replaceUrl: false,
    pushUrl: false,
    focus: true,
  });
}

// Click in capture phase so we run before child handlers that call
// stopPropagation (e.g. chip buttons inside <summary> blocks). Submit
// in bubble phase so an inline `onsubmit="return confirm(...)"` has a
// chance to set defaultPrevented before we read it.
document.addEventListener('click', onClick, true);
document.addEventListener('submit', onSubmit);
window.addEventListener('popstate', onPopState);
