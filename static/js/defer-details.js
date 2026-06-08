// Deferred content for <details> elements — fetch contents the first time
// the user opens the element.
//
// Marker: <details data-defer-from="/path/to/fragment">
//
// On first `toggle` (open=true), fetches the URL and morphs the response
// into the <details> (preserving the <summary>). After the first fetch,
// the marker attribute is removed so subsequent open/close cycles are
// free.

import { fetchAndMorph } from '/js/dispatcher.js';

function wire(el) {
  const url = el.getAttribute('data-defer-from');
  if (!url) return;

  // Idempotency marker: a JS property (not an attribute, so morph won't
  // touch it). If the data-defer-from attribute reappears later (e.g. a
  // morph brought back the original markup), we re-wire — the previous
  // handler was removed after firing and a fresh toggle should re-fetch.
  const wireKey = '_deferWiredFor';
  if (el[wireKey] === url) return;
  el[wireKey] = url;

  if (!el.id) {
    el.id = 'defer-' + Math.random().toString(36).slice(2, 10);
  }

  const handler = () => {
    if (!el.open) return;
    el.removeEventListener('toggle', handler);
    el.removeAttribute('data-defer-from');
    el[wireKey] = null;
    const target = el.getAttribute('data-defer-target') || ('#' + el.id);
    fetchAndMorph(url, {
      method: 'GET',
      target,
      pushUrl: false,
      focus: false,
      key: 'defer|' + el.id,
    });
  };

  el.addEventListener('toggle', handler);
}

function init(root) {
  const scope = root || document;
  scope.querySelectorAll('details[data-defer-from]').forEach(wire);
}

document.addEventListener('DOMContentLoaded', () => init(document));
document.addEventListener('dispatcher:morphed', () => init(document));
