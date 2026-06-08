// Auto-submit a form on input/change events, debounced.
//
// Marker: <form data-live data-target="#preview-region">
// Optional: data-live-delay="300" (default 300ms).
//
// Uses the dispatcher's fetchAndMorph primitive so behaviour stays consistent
// with normal form submission — same target conventions, same focus handling
// (but with ignoreActiveValue so the input the user is typing into doesn't
// get clobbered mid-keystroke).

import { fetchAndMorph } from '/js/dispatcher.js';
import { debounce, DEFAULT_DELAY_MS } from '/js/util.js';

function submitForm(form) {
  const target = form.getAttribute('data-target') || 'main';
  const action = form.action;
  const method = (form.method || 'POST').toUpperCase();
  const formData = new FormData(form);
  const body = form.enctype === 'multipart/form-data'
    ? formData
    : new URLSearchParams(formData);
  fetchAndMorph(action, {
    method,
    body,
    target,
    pushUrl: false,
    focus: false,
    ignoreActiveValue: true,
    key: 'live|' + form.id || action,
  });
}

function wire(form) {
  if (form._liveWired) return;
  form._liveWired = true;

  const delay = parseInt(form.getAttribute('data-live-delay'), 10) || DEFAULT_DELAY_MS;
  const submit = debounce(delay, () => submitForm(form));

  form.addEventListener('input', submit);
  form.addEventListener('change', submit);

  // Fire once on init so the preview reflects current values.
  queueMicrotask(() => submitForm(form));
}

function init(root) {
  const scope = root || document;
  scope.querySelectorAll('form[data-live]').forEach(wire);
}

document.addEventListener('DOMContentLoaded', () => init(document));
document.addEventListener('dispatcher:morphed', (e) => init(document));
