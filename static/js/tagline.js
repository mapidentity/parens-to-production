// Landing-page tagline rotator (Layer 3 island).
//
// The landing page renders a fixed default tagline server-side, so the page
// itself stays deterministic and cacheable/prerenderable. This island fetches a
// random tagline once on connect and swaps it in. It is purely cosmetic and
// degrades cleanly: with JS off (or the fetch failing) the default tagline stays.
//
// A plain fetch is the narrowest correct mechanism here — a single text swap
// needs none of the dispatcher's navigation machinery (history, title, morph,
// view transitions), so we don't reach for fetchAndMorph.
//
// Marker: <p data-controller="tagline" data-tagline-url="/partials/tagline">

import { register } from '/js/controllers.js';

register('tagline', {
  connect(el) {
    const url = el.getAttribute('data-tagline-url');
    if (!url) return;
    fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'text/plain' } })
      .then((res) => (res.ok ? res.text() : null))
      .then((text) => {
        // The element may have been morphed away between request and response.
        if (text && document.contains(el)) el.textContent = text.trim();
      })
      .catch(() => {});
  },
});
