// Admin dashboard live stats. ESM enhancer: polls /admin/stats and animates the
// changed values. Loaded globally but INERT off the dashboard — it only starts a
// poller when the [data-stat] cards are present. Idempotent and morph-safe:
// re-inits on dispatcher:morphed and always clears any prior interval first, so
// navigating in/out of /admin never leaks a second poller.
let timer = null;

function poll() {
  fetch('/admin/stats', { credentials: 'same-origin' })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (data) {
      if (!data) return;
      var els = document.querySelectorAll('[data-stat]');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        var key = el.getAttribute('data-stat');
        if (!(key in data)) continue;
        var oldVal = parseInt(el.getAttribute('data-value'), 10);
        var newVal = data[key];
        if (oldVal !== newVal) {
          el.style.setProperty('--stat-value', newVal);
          el.setAttribute('data-value', newVal);
          el.classList.remove('changed-up', 'changed-down');
          void el.offsetWidth;
          var cls = newVal > oldVal ? 'changed-up' : 'changed-down';
          el.classList.add(cls);
          setTimeout(function (e, c) { e.classList.remove(c); }, 3000, el, cls);
        }
      }
    })
    .catch(function () {});
}

function start() {
  if (timer) { clearInterval(timer); timer = null; } // never leak a second poller
  if (document.querySelector('[data-stat]')) {
    timer = setInterval(poll, 20000);
  }
}

start();
document.addEventListener('dispatcher:morphed', start);
