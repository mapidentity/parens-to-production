// Auto-submit a form on input/change events, debounced.
//
// Marker: <form data-live data-target="#preview-region">
// Optional: data-live-delay="300" (default 300ms).
//
// Uses the dispatcher's fetchAndMorph primitive so behaviour stays consistent
// with normal form submission — same target conventions, same focus handling
// (but with ignoreActiveValue so the input the user is typing into doesn't get
// clobbered mid-keystroke). Lifecycle (wire on load + after morph, once) is
// owned by the controller registry; this module only declares the behaviour.

import { fetchAndMorph } from '/js/dispatcher.js';
import { register } from '/js/controllers.js';
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
    // Distinct in-flight key per form: prefer the id, fall back to the
    // action. The parens matter — `'live|' + form.id || action` binds as
    // `('live|' + form.id) || action`, whose left side is always truthy,
    // so the fallback was dead and every id-less live form shared one key
    // (and aborted each other's requests).
    key: 'live|' + (form.id || action),
  });
}

register('live-form', {
  selector: 'form[data-live]',
  connect(form) {
    const delay = parseInt(form.getAttribute('data-live-delay'), 10) || DEFAULT_DELAY_MS;
    const submit = debounce(delay, () => submitForm(form));
    form._liveSubmit = submit;
    form.addEventListener('input', submit);
    form.addEventListener('change', submit);
    // Fire once on connect so the preview reflects current values.
    queueMicrotask(() => submitForm(form));
  },
  disconnect(form) {
    if (!form._liveSubmit) return;
    form.removeEventListener('input', form._liveSubmit);
    form.removeEventListener('change', form._liveSubmit);
    form._liveSubmit = null;
  },
});
