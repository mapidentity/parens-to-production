// Drag-to-reorder a list, persisted to the server (Layer 3 island).
//
// Pointer Events (mouse + touch + pen) track the drag; the *motion* belongs to
// CSS and the View Transitions API, not to JavaScript:
//   - the lifted row follows the pointer through a CSS custom property (--dy),
//     composed with the lift transform in the .dragging rule;
//   - siblings glide aside via a CSS `transition: transform` as JS sets/clears
//     their displacement;
//   - the drop settles via a same-document View Transition, which FLIPs every
//     row (each carries a view-transition-name) from its dragged position to its
//     final slot.
// JS computes only "which pointer position maps to which index". There is no
// hand-written animation.
//
// True zero-JS dragging is not possible (pointer tracking, drag state, and DOM
// reordering are inherently scripted) — but this list still reorders without JS
// via the server-rendered up/down buttons, and renders in its saved order for
// everyone. The drag is the enhancement; the order is the baseline.
//
// Marker: <ul data-controller="sortable" data-sortable-url="/recipes/reorder">
//   each child carries data-id and style="view-transition-name: …" and contains
//   an element with [data-drag-handle] to grab.

import { register } from '/js/controllers.js';

const THRESHOLD = 4;   // px of movement before a press becomes a drag (taps don't lift)

function rowsOf(container) {
  return [...container.querySelectorAll(':scope > [data-id]')];
}

function reducedMotion() {
  return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

register('sortable', {
  connect(container) {
    let state = null;   // active gesture, or null

    function begin() {
      state.started = true;
      container.classList.add('is-sorting');
      state.item.classList.add('dragging');
      // row pitch (height + gap) — the distance each displaced sibling travels
      const r = state.rects;
      state.step = r.length < 2 ? 0
        : (state.from < r.length - 1
            ? r[state.from + 1].top - r[state.from].top
            : r[state.from].top - r[state.from - 1].top);
    }

    function onPointerMove(e) {
      if (!state) return;
      const dy = e.clientY - state.startY;
      if (!state.started) {
        if (Math.abs(dy) < THRESHOLD) return;
        begin();
      }
      state.item.style.setProperty('--dy', dy + 'px');

      // Target index from the dragged row's center vs. the ORIGINAL midpoints
      // (captured once at the start, so the calc never feeds back on itself).
      const r = state.rects;
      const center = r[state.from].top + r[state.from].height / 2 + dy;
      let to = state.from;
      for (let i = 0; i < r.length; i++) {
        if (i === state.from) continue;
        const mid = r[i].top + r[i].height / 2;
        if (i > state.from && center > mid) to = i;                    // passed downward
        else if (i < state.from && center < mid && to >= state.from) to = i;  // passed upward
      }
      state.to = to;

      // Displace siblings to open the gap; the CSS transition animates the slide.
      state.rows.forEach((el, i) => {
        if (el === state.item) return;
        let shift = 0;
        if (state.from < to && i > state.from && i <= to) shift = -state.step;
        else if (state.from > to && i >= to && i < state.from) shift = state.step;
        el.style.transform = shift ? `translateY(${shift}px)` : '';
      });
    }

    function settle(s) {
      const { rows, item, to, from } = s;
      rows.forEach((el) => { el.style.transform = ''; });
      item.style.removeProperty('--dy');
      item.classList.remove('dragging');
      container.classList.remove('is-sorting');
      if (to !== from) {
        const ref = to > from ? (rows[to + 1] || null) : rows[to];
        container.insertBefore(item, ref);
      }
    }

    function onPointerEnd(e) {
      if (!state) return;
      const s = state;
      try { s.handle.releasePointerCapture(e.pointerId); } catch (_) {}
      s.handle.removeEventListener('pointermove', onPointerMove);
      s.handle.removeEventListener('pointerup', onPointerEnd);
      s.handle.removeEventListener('pointercancel', onPointerEnd);
      if (e.type === 'pointercancel') s.to = s.from;   // abandoned — revert
      const moved = s.started && s.to !== s.from;

      if (!s.started) { state = null; return; }        // it was a tap, not a drag

      // The drop settle: animate the dragged row from the pointer to its slot
      // (and any last sibling glide) via a View Transition. The rows' shared
      // view-transition-names make the browser FLIP each into place.
      if (moved && document.startViewTransition && !reducedMotion()) {
        document.startViewTransition(() => settle(s));
      } else {
        settle(s);
      }
      state = null;

      if (moved) {
        // The optimistic reorder above is authoritative BY CONSTRUCTION: the
        // server stores exactly the order we send and renders it back the same.
        // So persist with a fire-and-forget POST and do NOT morph — morphing the
        // list while the drop View Transition is still animating would mutate
        // view-transition-named elements mid-flight and leave a ghost snapshot.
        // (The captured state, not the live DOM, holds the final order, since
        // settle() runs inside the async View Transition callback.)
        const ids = s.rows.map((el) => el.getAttribute('data-id'));
        ids.splice(s.to, 0, ids.splice(s.from, 1)[0]);
        fetch(container.getAttribute('data-sortable-url'), {
          method: 'POST',
          body: new URLSearchParams({ ids: ids.join(',') }),
          credentials: 'same-origin',
          redirect: 'manual',          // we don't need the redirect target; just persist
          headers: { 'Accept': 'text/html' },
        }).catch(() => {});
      }
    }

    function onPointerDown(e) {
      if (e.button != null && e.button !== 0) return;          // primary button only
      const handle = e.target.closest('[data-drag-handle]');
      if (!handle || !container.contains(handle)) return;
      const item = handle.closest('[data-id]');
      if (!item || item.parentElement !== container) return;
      e.preventDefault();
      const rows = rowsOf(container);
      state = {
        handle, item, rows,
        from: rows.indexOf(item),
        to: rows.indexOf(item),
        startY: e.clientY,
        rects: rows.map((el) => el.getBoundingClientRect()),
        started: false,
        step: 0,
      };
      // Attach listeners BEFORE capturing, so a failed capture can never leave
      // the gesture half-wired. Capture keeps events flowing if the pointer
      // leaves the handle; it's a best-effort enhancement.
      handle.addEventListener('pointermove', onPointerMove);
      handle.addEventListener('pointerup', onPointerEnd);
      handle.addEventListener('pointercancel', onPointerEnd);
      try { handle.setPointerCapture(e.pointerId); } catch (_) {}
    }

    container.addEventListener('pointerdown', onPointerDown);
    container._sortableCleanup = () => container.removeEventListener('pointerdown', onPointerDown);
  },
  disconnect(container) {
    if (container._sortableCleanup) container._sortableCleanup();
    container._sortableCleanup = null;
  },
});
