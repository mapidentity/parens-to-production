// Dev-only: reload the page when the dev WebSocket signals a code change, and
// show a soft "this page may be stale" banner when a source file fails to
// reload. Only emitted when myapp is running in dev mode (hot-reload namespace
// resolvable). Production never sees this script.
// Open the dev WebSocket lazily. A prerendered page (via the Speculation Rules
// prerender) is not permitted to hold a WebSocket and would be discarded if it
// tried — so defer the connection until the page is actually activated. On a
// normal load document.prerendering is false and we connect immediately.
function connectDevReload() {
  const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/dev/ws');
  ws.onmessage = function (event) {
    const data = JSON.parse(event.data);
    if (data.type === 'reload') {
      if (data.morphable) { morphReload(); } else { hardReload(); }
    } else if (data.type === 'css') {
      swapStylesheet();                    // CSS rebuilt — swap the <link>, no reload
    } else if (data.type === 'reload-error') {
      showStaleWarning(data.file, data.error);
    }
  };
  ws.onopen = function () { console.log('Dev reload WebSocket connected'); };
  ws.onerror = function (error) { console.log('WebSocket error:', error); };
}

if (document.prerendering) {
  document.addEventListener('prerenderingchange', connectDevReload, { once: true });
} else {
  connectDevReload();
}

// A view-ns edit: morph the whole <body> in place via the dispatcher
// (state-preserving — keeps scroll, focus, open <details>). The target is the
// body, not <main>, because view namespaces also own the page chrome — nav,
// layout, footer — and a <main>-only morph would leave a chrome edit invisibly
// stale. The one thing a body morph must not do is remove the dev tooling the
// server's HTML never contained — the inspector's UI, the construction-view
// overlay — so every client-injected dev node carries data-myapp-overlay and
// the morph is told to leave those alone. Falls back to a full reload on any
// failure, and clears a prior stale banner since the morph won't navigate it away.
function morphReload() {
  var bar = document.getElementById('myapp-stale-warning'); if (bar) bar.remove();
  import('/js/dispatcher.js')
    .then(function (m) {
      return m.fetchAndMorph(location.pathname + location.search,
        { target: 'body', replaceUrl: true, focus: false, ignoreActiveValue: true,
          morphCallbacks: {
            beforeNodeRemoved: function (node) {
              return !(node.nodeType === 1 && node.hasAttribute('data-myapp-overlay'));
            },
            // A user-opened <details> reflects `open` onto the live node, but the
            // server re-render has it closed — idiomorph would strip `open` and
            // collapse the panel. Veto that one removal so an expanded <details>
            // survives a view-edit morph (idiomorph has no built-in handling for it).
            beforeAttributeUpdated: function (attr, node, mutationType) {
              return !(mutationType === 'remove' && attr === 'open' && node.tagName === 'DETAILS');
            },
          } });
    })
    .catch(function () { window.location.reload(); });
}

// A non-view .clj or a .js edit: a module is a re-executing singleton, so a full
// reload is required. Stash scroll so the reload doesn't lose your place.
function hardReload() {
  try { sessionStorage.setItem('myapp-dev-scroll', String(window.scrollY)); } catch (e) {}
  window.location.reload();
}

// A .css rebuild (Tailwind --watch): swap the stylesheet href with a cache-bust so
// the browser refetches the rebuilt file — no reload, no flash.
function swapStylesheet() {
  var link = document.querySelector('link[rel="stylesheet"]');
  if (!link) return;
  var base = (link.getAttribute('href') || '').split('?')[0];
  link.setAttribute('href', base + '?v=' + Date.now());
}

// Restore scroll after a dev hard reload (stashed by hardReload before reloading).
try {
  var savedScroll = sessionStorage.getItem('myapp-dev-scroll');
  if (savedScroll !== null) {
    sessionStorage.removeItem('myapp-dev-scroll');
    window.addEventListener('load', function () { window.scrollTo(0, parseInt(savedScroll, 10) || 0); });
  }
} catch (e) {}

// A source file failed to (re)load on the server, so this page didn't refresh
// and may not reflect the latest edit. We can't be sure it uses the broken file
// (could be an unrelated reload), so the wording is deliberately soft. The next
// successful reload navigates the page and removes this banner automatically.
function showStaleWarning(file, error) {
  var id = 'myapp-stale-warning';
  var bar = document.getElementById(id);
  if (!bar) {
    bar = document.createElement('div');
    bar.id = id;
    bar.style.cssText =
      'position:fixed;top:0;left:0;right:0;z-index:2147483647;' +
      'background:#7f1d1d;color:#fee2e2;font:12px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;' +
      'padding:7px 12px;box-shadow:0 1px 6px rgba(0,0,0,.4);display:flex;gap:10px;align-items:baseline';
    (document.body || document.documentElement).appendChild(bar);
  }
  var name = (file || 'a source file').replace(/^.*\/src\//, '');
  bar.innerHTML = '';
  var msg = document.createElement('span');
  msg.style.flex = '1';
  msg.textContent = '⚠ ' + name + ' failed to reload — this page may be stale. Fix the error and save.';
  if (error) msg.title = error;
  var detail = document.createElement('code');
  detail.style.cssText = 'opacity:.85;max-width:45%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap';
  detail.textContent = error ? String(error).split('\n')[0] : '';
  if (error) detail.title = error;
  var close = document.createElement('button');
  close.textContent = '×';
  close.setAttribute('aria-label', 'dismiss');
  close.style.cssText = 'background:none;border:0;color:inherit;cursor:pointer;font:16px/1 ui-monospace,monospace';
  close.onclick = function () { bar.remove(); };
  bar.appendChild(msg);
  bar.appendChild(detail);
  bar.appendChild(close);
}
