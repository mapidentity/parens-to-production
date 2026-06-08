// Server-rendered live preview pattern for elements outside any single
// form — used by the asset/lease previews on /assets/new.
//
// Marker: <div id="..." data-preview-action="/..." data-preview-from="<sel>">
//
// Behaviour: on input/change of any element matching `data-preview-from`,
// debounce 300ms then POST a FormData of those inputs to
// `data-preview-action`. Morph the response into the element itself.

import { fetchAndMorph } from '/js/dispatcher.js';
import { debounce, DEFAULT_DELAY_MS } from '/js/util.js';

function gatherInputs(selector) {
  // URL-encoded body — server endpoints for previews parse the standard
  // form encoding, not multipart.
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

function wire(el) {
  if (el._previewWired) return;
  el._previewWired = true;

  const action = el.getAttribute('data-preview-action');
  const fromSel = el.getAttribute('data-preview-from');
  if (!action || !fromSel) return;

  if (!el.id) el.id = 'preview-' + Math.random().toString(36).slice(2, 10);
  const target = '#' + el.id;

  const submit = debounce(DEFAULT_DELAY_MS, () => {
    // The element may have been morphed out by a navigation between
    // keystroke and debounce-fire — abandon the call rather than POST
    // into the void.
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

  document.querySelectorAll(fromSel).forEach((input) => {
    input.addEventListener('input', submit);
    input.addEventListener('change', submit);
  });

  // Initial render once values are present.
  queueMicrotask(submit);
}

function init(root) {
  const scope = root || document;
  scope.querySelectorAll('[data-preview-from][data-preview-action]').forEach(wire);
}

document.addEventListener('DOMContentLoaded', () => init(document));
document.addEventListener('dispatcher:morphed', () => init(document));
