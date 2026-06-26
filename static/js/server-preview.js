// Server-rendered live preview pattern for elements outside any single form —
// used by previews whose inputs live elsewhere on the page.
//
// Marker: <div id="..." data-preview-action="/..." data-preview-from="<sel>">
//
// On input/change of any element matching `data-preview-from`, debounce 300ms
// then POST a FormData of those inputs to `data-preview-action` and morph the
// response into the element itself. Lifecycle is owned by the controller
// registry; this module declares only the behaviour.

import { fetchAndMorph } from '/js/dispatcher.js';
import { register } from '/js/controllers.js';
import { debounce, DEFAULT_DELAY_MS } from '/js/util.js';

function gatherInputs(selector) {
  // URL-encoded body — preview endpoints parse the standard form encoding.
  const body = new URLSearchParams();
  document.querySelectorAll(selector).forEach((el) => {
    if (!el.name) return;
    if (el.type === 'checkbox' || el.type === 'radio') {
      if (el.checked) body.append(el.name, el.value);
    } else {
      body.append(el.name, el.value);
    }
  });
  return body;
}

register('server-preview', {
  selector: '[data-preview-from][data-preview-action]',
  connect(el) {
    const action = el.getAttribute('data-preview-action');
    const fromSel = el.getAttribute('data-preview-from');
    if (!action || !fromSel) return;
    if (!el.id) el.id = 'preview-' + Math.random().toString(36).slice(2, 10);
    const target = '#' + el.id;

    const submit = debounce(DEFAULT_DELAY_MS, () => {
      // The element may have been morphed away between keystroke and fire.
      if (!document.contains(el)) return;
      fetchAndMorph(action, {
        method: 'POST',
        body: gatherInputs(fromSel),
        target,
        pushUrl: false,
        focus: false,
        ignoreActiveValue: true,
        key: 'preview|' + el.id,
      });
    });

    const inputs = [...document.querySelectorAll(fromSel)];
    inputs.forEach((input) => {
      input.addEventListener('input', submit);
      input.addEventListener('change', submit);
    });
    el._previewTeardown = () => inputs.forEach((input) => {
      input.removeEventListener('input', submit);
      input.removeEventListener('change', submit);
    });

    // Initial render once values are present.
    queueMicrotask(submit);
  },
  disconnect(el) {
    if (!el._previewTeardown) return;
    el._previewTeardown();
    el._previewTeardown = null;
  },
});
