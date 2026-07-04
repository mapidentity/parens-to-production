// Toast notifications. Triggers come from:
// - URL ?toast=key params (server redirects after a successful save).
// - Server-rendered toasts with [data-toast-ttl] (auto-dismissed below).
(function () {
  function showToast(msg) {
    var c = document.getElementById('toast-container');
    if (!c) return;
    var t = document.createElement('div');
    t.className = 'px-4 py-2 bg-chrome text-white rounded-lg shadow-lg text-sm font-medium transform translate-y-2 opacity-0 transition-all duration-300';
    t.textContent = msg;
    c.appendChild(t);
    requestAnimationFrame(function () {
      t.style.opacity = '1';
      t.style.transform = 'translateY(0)';
    });
    setTimeout(function () {
      t.style.opacity = '0';
      t.style.transform = 'translateY(8px)';
      setTimeout(function () { t.remove(); }, 300);
    }, 2000);
  }

  // Server-rendered toasts: auto-dismiss after data-toast-ttl ms.
  // Used by the undo-toast pattern — the server includes a toast div
  // in the response and we kick off the fade-out timer here. Idempotent
  // via data-toast-init so a re-init pass after a morph doesn't restart
  // the timer.
  function autoDismiss(t) {
    var ttl = parseInt(t.getAttribute('data-toast-ttl'), 10) || 5000;
    setTimeout(function () {
      t.style.transition = 'opacity 300ms ease-out';
      t.style.opacity = '0';
      setTimeout(function () { if (t.parentNode) t.remove(); }, 320);
    }, ttl);
  }

  function processToasts() {
    var nodes = document.querySelectorAll('[data-toast-ttl]:not([data-toast-init])');
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      n.setAttribute('data-toast-init', 'true');
      autoDismiss(n);
    }
  }

  document.addEventListener('DOMContentLoaded', processToasts);
  document.addEventListener('dispatcher:morphed', processToasts);

  // URL-param-driven toasts: fired on full-page redirects after a save.
  // The handler appends ?toast=key and we look up the message text. Cleans
  // the param out of the address bar afterwards so a refresh doesn't
  // re-fire. Keys map to messages here so the i18n choice stays simple
  // (the handler decides which key, the page just renders).
  var TOASTS = {
    'recipe-saved': 'Recipe saved ✓',
    'recipe-published': 'New version published ✓',
    'fork-created': 'Fork created ✓',
    'recipe-deleted': 'Recipe deleted',
    'version-restored': 'Version restored ✓'
  };

  function processToastParam() {
    try {
      var params = new URLSearchParams(window.location.search);
      var key = params.get('toast');
      if (key && TOASTS[key]) {
        showToast(TOASTS[key]);
        params.delete('toast');
        var qs = params.toString();
        var url = window.location.pathname + (qs ? '?' + qs : '') + window.location.hash;
        window.history.replaceState({}, '', url);
      }
    } catch (_) { /* malformed URL — silently skip */ }
  }

  document.addEventListener('DOMContentLoaded', processToastParam);
  // Also process after a dispatcher morph — the URL may now carry a
  // ?toast=... param from a server redirect after a form submit.
  document.addEventListener('dispatcher:morphed', processToastParam);
})();
