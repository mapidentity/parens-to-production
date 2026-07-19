// Live viewer presence, via Server-Sent Events. Pure enhancement: without
// JavaScript the count element stays empty, and the page is complete without
// it — presence is a live garnish on a server-rendered page, never load-bearing.
//
// The server pushes a `{"count": N}` frame on every arrival and departure.
// We show it only when someone ELSE is here (count > 1 — the count includes
// this browser), so a solo reader sees nothing rather than "1 person looking".

document.querySelectorAll('[data-viewers-url]').forEach((el) => {
  const source = new EventSource(el.dataset.viewersUrl);

  source.onmessage = (e) => {
    try {
      const { count } = JSON.parse(e.data);
      const others = count - 1;
      el.textContent = others > 0 ? `👀 ${count} people looking now` : '';
      el.hidden = others <= 0;
    } catch {
      /* a malformed frame is not worth a broken page */
    }
  };

  // EventSource reconnects on its own after a drop; nothing to do on error
  // but avoid noise. Close cleanly on navigate so the server deregisters.
  source.onerror = () => {};
  window.addEventListener('pagehide', () => source.close());
});
