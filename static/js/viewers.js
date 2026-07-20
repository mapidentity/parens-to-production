// Live viewer presence, via Server-Sent Events. Pure enhancement: without
// JavaScript the count element stays empty, and the page is complete without
// it — presence is a live garnish on a server-rendered page, never load-bearing.
//
// The server pushes a `{"count": N}` frame on every arrival and departure.
// We show it only when someone ELSE is here (count > 1 — the count includes
// this browser), so a solo reader sees nothing rather than "1 person looking".
//
// A controller, not a load-time loop — because the dispatcher navigates by
// MORPHING, a recipe page can arrive and leave without a page load. The
// registry's lifecycle is what closes the old stream on the way out
// (disconnect) and opens the right one on the way in (connect); the original
// load-time querySelectorAll wired only the first page, so under morph
// navigation it leaked the EventSource of every recipe visited and never
// subscribed the next one. The label text comes from the server
// (data-viewers-label, an i18n string) — an island renders state, it does
// not own copy.

import { register } from '/js/controllers.js';

const streams = new WeakMap();

register('viewers', {
  selector: '[data-viewers-url]',

  connect(el) {
    const source = new EventSource(el.dataset.viewersUrl);
    const label = el.dataset.viewersLabel || '%s';

    source.onmessage = (e) => {
      try {
        const { count } = JSON.parse(e.data);
        const others = count - 1;
        el.textContent = others > 0 ? label.replace('%s', count) : '';
        el.hidden = others <= 0;
      } catch {
        /* a malformed frame is not worth a broken page */
      }
    };

    // EventSource reconnects on its own after a drop; nothing to do on error
    // but avoid noise. `pagehide` covers a full-load navigation away; the
    // morph navigation path is covered by disconnect() below.
    source.onerror = () => {};
    const onPagehide = () => source.close();
    window.addEventListener('pagehide', onPagehide);
    streams.set(el, { source, onPagehide });
  },

  disconnect(el) {
    const s = streams.get(el);
    if (!s) return;
    streams.delete(el);
    s.source.close();
    window.removeEventListener('pagehide', s.onPagehide);
    el.textContent = '';
    el.hidden = true;
  },
});
