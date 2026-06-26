// Deferred content for <details> elements — fetch contents the first time the
// user opens the element.
//
// Marker: <details data-defer-from="/path/to/fragment">
//
// On first `toggle` (open=true), fetches the URL and morphs the response into
// the <details> (preserving the <summary>). After the first fetch the marker
// attribute is removed, so the element stops matching and the registry
// disconnects it; a later morph that restores the marker reconnects it for a
// fresh fetch. Lifecycle is owned by the controller registry.

import { fetchAndMorph } from '/js/dispatcher.js';
import { register } from '/js/controllers.js';

register('defer-details', {
  selector: 'details[data-defer-from]',
  connect(el) {
    const url = el.getAttribute('data-defer-from');
    if (!url) return;
    if (!el.id) el.id = 'defer-' + Math.random().toString(36).slice(2, 10);

    const handler = () => {
      if (!el.open) return;
      el.removeEventListener('toggle', handler);
      el._deferHandler = null;
      el.removeAttribute('data-defer-from');
      const target = el.getAttribute('data-defer-target') || ('#' + el.id);
      fetchAndMorph(url, {
        method: 'GET',
        target,
        pushUrl: false,
        focus: false,
        key: 'defer|' + el.id,
      });
    };
    el._deferHandler = handler;
    el.addEventListener('toggle', handler);
  },
  disconnect(el) {
    if (!el._deferHandler) return;
    el.removeEventListener('toggle', el._deferHandler);
    el._deferHandler = null;
  },
});
