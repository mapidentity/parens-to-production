// Confirm-before-submit using a native <dialog> (Layer 1 element, Layer 3 glue).
//
// Marker: <form data-controller="confirm" data-confirm="Delete this? …"
//               data-confirm-ok="Delete" data-confirm-cancel="Cancel">
//
// Intercepts the form's submit, shows a modal <dialog>, and only lets the submit
// proceed (where the dispatcher then enhances it into a morph) if the user
// confirms. Without JS the form submits directly — the destructive action still
// works, just without the extra prompt. One shared dialog serves every form.

import { register } from '/js/controllers.js';

let dialog = null;

function ensureDialog() {
  if (dialog) return dialog;
  dialog = document.createElement('dialog');
  dialog.className = 'confirm-dialog';
  // method="dialog" buttons close the dialog and set returnValue to their value.
  dialog.innerHTML =
    '<form method="dialog">' +
    '<p data-confirm-message></p>' +
    '<div class="confirm-actions">' +
    '<button value="cancel" class="confirm-cancel" data-confirm-cancel></button>' +
    '<button value="ok" class="confirm-ok" data-confirm-ok></button>' +
    '</div></form>';
  document.body.appendChild(dialog);
  return dialog;
}

function ask(message, okLabel, cancelLabel) {
  const dlg = ensureDialog();
  dlg.querySelector('[data-confirm-message]').textContent = message || 'Are you sure?';
  dlg.querySelector('[data-confirm-ok]').textContent = okLabel || 'OK';
  dlg.querySelector('[data-confirm-cancel]').textContent = cancelLabel || 'Cancel';
  return new Promise((resolve) => {
    const onClose = () => { dlg.removeEventListener('close', onClose); resolve(dlg.returnValue === 'ok'); };
    dlg.addEventListener('close', onClose);
    dlg.returnValue = '';
    dlg.showModal();
  });
}

register('confirm', {
  selector: 'form[data-confirm]',
  connect(form) {
    const handler = (e) => {
      // The confirmed re-submit flows straight through (and on to the dispatcher).
      if (form._confirmed) { form._confirmed = false; return; }
      e.preventDefault();
      e.stopPropagation();          // keep the dispatcher off this pre-confirm submit
      const submitter = e.submitter;
      ask(form.getAttribute('data-confirm'),
          form.getAttribute('data-confirm-ok'),
          form.getAttribute('data-confirm-cancel'))
        .then((ok) => {
          if (!ok) return;
          form._confirmed = true;
          form.requestSubmit(submitter);
        });
    };
    form._confirmHandler = handler;
    form.addEventListener('submit', handler);
  },
  disconnect(form) {
    if (form._confirmHandler) form.removeEventListener('submit', form._confirmHandler);
    form._confirmHandler = null;
  },
});
