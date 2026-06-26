// Controller registry — the island layer (Layer 3 of the progressive-
// enhancement stack).
//
// A "controller" is a small behaviour attached to elements that match a
// selector. The registry owns the LIFECYCLE that every enhancer used to
// re-implement by hand: find matching elements on first load AND after every
// morph, attach exactly once (idempotent), and detach when the element leaves
// the DOM or stops matching. Behaviours just declare connect()/disconnect();
// they never touch DOMContentLoaded, `dispatcher:morphed`, or _wired flags.
//
//   import { register } from '/js/controllers.js';
//   register('sortable', {
//     connect(el)    { /* attach listeners, start timers … */ },
//     disconnect(el) { /* tear them down */ },
//   });
//
// By default a controller matches `[data-controller~="name"]`, the Stimulus-
// style marker (space-separated, so one element can host several). A behaviour
// with an established marker of its own can override `selector` instead of
// forcing an HTML churn — the unified piece is the lifecycle, not the
// attribute. Matching on the selector (not merely DOM membership) means a
// controller also disconnects when its marker is removed and reconnects if a
// morph brings the marker back on a surviving element.

const registry = new Map();   // name -> { selector, connect, disconnect }
const elState = new Map();    // el -> Set(names currently connected on it)

function connectEl(el, name) {
  const ctrl = registry.get(name);
  if (!ctrl) return;
  let set = elState.get(el);
  if (set && set.has(name)) return;          // already connected — idempotent
  if (!set) { set = new Set(); elState.set(el, set); }
  set.add(name);
  try { ctrl.connect && ctrl.connect(el); }
  catch (err) { console.error('controller "' + name + '" connect failed', err); }
}

function disconnectEl(el, name) {
  const set = elState.get(el);
  if (!set || !set.has(name)) return;
  set.delete(name);
  if (set.size === 0) elState.delete(el);
  const ctrl = registry.get(name);
  try { ctrl && ctrl.disconnect && ctrl.disconnect(el); }
  catch (err) { console.error('controller "' + name + '" disconnect failed', err); }
}

// Reconcile the live DOM against the connected set. Idempotent and cheap, so we
// can run it on every morph: idiomorph reuses surviving elements in place, so
// most stay connected and only true newcomers/leavers change.
function scan() {
  for (const [name, ctrl] of registry) {
    document.querySelectorAll(ctrl.selector).forEach((el) => connectEl(el, name));
  }
  for (const [el, set] of elState) {
    for (const name of [...set]) {
      const ctrl = registry.get(name);
      if (!document.contains(el) || !ctrl || !el.matches(ctrl.selector)) {
        disconnectEl(el, name);
      }
    }
  }
}

export function register(name, controller) {
  registry.set(name, {
    selector: controller.selector || '[data-controller~="' + name + '"]',
    connect: controller.connect,
    disconnect: controller.disconnect,
  });
  // A controller module may import-load after DOMContentLoaded; wire any
  // instances already on the page right now.
  if (document.readyState !== 'loading') scan();
}

document.addEventListener('DOMContentLoaded', scan);
// Every in-page update flows through the dispatcher, which fires this once the
// new markup is in place — the single hook the whole island layer hangs on.
document.addEventListener('dispatcher:morphed', scan);
