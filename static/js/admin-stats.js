// Admin dashboard live stats. Polls /admin/stats and animates changed values.
// INERT off the dashboard — a controller only connects where the [data-stat]
// cards exist. A single shared poller serves all cards (started on the first
// connect, stopped when the last card disconnects), so navigating in and out of
// /admin never leaks a second interval. Lifecycle owned by the registry.

import { register } from '/js/controllers.js';

let timer = null;
let liveCards = 0;

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

register('live-stats', {
  selector: '[data-stat]',
  connect() {
    liveCards++;
    if (!timer) timer = setInterval(poll, 20000);
  },
  disconnect() {
    liveCards = Math.max(0, liveCards - 1);
    if (liveCards === 0 && timer) { clearInterval(timer); timer = null; }
  },
});
