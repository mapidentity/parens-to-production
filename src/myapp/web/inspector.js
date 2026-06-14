// Dev-only source inspector overlay (loaded only in dev, beside dev-reload.js).
// Bidirectional, over the existing /dev/ws socket.
//
// FORWARD (element → code): enable it (badge bottom-left, Alt+Shift+I, Esc to
// exit). Hover highlights the nearest source-tagged element and shows a
// breadcrumb of its tagged ancestors. A component instance folds to one crumb —
// the name plus two glyphs, λ (its definition) and () (this call site) — since
// it carries both data-myapp-src (defn) and data-myapp-callsite (invocation).
// Alt+wheel walks the ancestor chain without moving the mouse; a click opens the
// CURRENT selection via {type:"open"} to the server (which pushes to the editor).
//
// REVERSE (code → element): with the inspector engaged, the server pushes
// {type:"highlight", component, file, defn-lines, element, callsite} as the
// editor cursor moves; handleHighlight frames the component and strong-
// highlights the element (callsite → element → component-root precedence). The
// inspect toggle is the single on/off for both directions.
//
// Source tags come from the dev loader instrumenting view fns (myapp.web.inspector).
(function () {
  'use strict';
  if (window.__myappInspectorLoaded) return; // body script: one init per full load
  window.__myappInspectorLoaded = true;

  var STORE = 'myapp-inspector';
  var enabled = false;
  try { enabled = localStorage.getItem(STORE) === '1'; } catch (e) {}

  // ---- styles (self-contained; no Tailwind dependency) ----
  var style = document.createElement('style');
  style.textContent =
    '.fy-insp-box{position:fixed;z-index:2147483646;pointer-events:none;' +
      'background:rgba(99,102,241,.12);border:1px solid rgba(99,102,241,.9);border-radius:2px;display:none}' +
    '.fy-insp-label{position:fixed;z-index:2147483647;pointer-events:auto;' +
      'font:11px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;background:#1e1b4b;color:#e0e7ff;' +
      'padding:3px 7px;border-radius:4px;box-shadow:0 2px 8px rgba(0,0,0,.35);max-width:92vw;' +
      'display:none;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
    '.fy-insp-crumb{cursor:pointer;opacity:.65}' +
    '.fy-insp-crumb:hover{opacity:1;text-decoration:underline}' +
    '.fy-insp-crumb.leaf{opacity:1;font-weight:600;color:#fff}' +
    '.fy-insp-sep{opacity:.4;margin:0 3px}' +
    '.fy-insp-loc{opacity:.55;margin-left:8px}' +
    '.fy-insp-cname{opacity:.8;margin-right:1px}' +
    '.fy-insp-cname.active{opacity:1;color:#fff;font-weight:600}' +
    '.fy-insp-glyph{margin-left:3px;font-weight:600}' +
    '.fy-insp-badge{position:fixed;left:10px;bottom:10px;z-index:2147483647;pointer-events:auto;' +
      'cursor:pointer;font:11px/1 ui-monospace,monospace;background:#312e81;color:#c7d2fe;' +
      'padding:5px 8px;border-radius:6px;box-shadow:0 1px 4px rgba(0,0,0,.3);opacity:.5;user-select:none}' +
    '.fy-insp-badge:hover{opacity:.85}' +
    '.fy-insp-badge.on{background:#6366f1;color:#fff;opacity:1}' +
    // .disconnected after .on so it wins: red whenever the dev socket is down.
    '.fy-insp-badge.disconnected{background:#b91c1c;color:#fee2e2;opacity:1}' +
    '.fy-insp-toast{position:fixed;left:10px;bottom:44px;z-index:2147483647;pointer-events:none;color:#fff;' +
      'font:11px/1.4 ui-monospace,monospace;padding:4px 8px;border-radius:4px;opacity:0;transition:opacity .15s}' +
    '.fy-insp-toast.show{opacity:1}' +
    // While inspecting, a click does something different (jump to source), so
    // signal it with a magnifying-glass cursor everywhere — except our own UI.
    'html.fy-insp-on,html.fy-insp-on *{cursor:zoom-in !important}' +
    'html.fy-insp-on .fy-insp-badge,html.fy-insp-on .fy-insp-crumb{cursor:pointer !important}' +
    // Reverse highlight (editor cursor → element), shown only while inspecting;
    // soft frame on every component instance, strong box + pulse on the element.
    '.fy-insp-zoom{position:fixed;z-index:2147483640;pointer-events:none;' +
      'border:2px solid rgba(99,102,241,.55);border-radius:3px;display:none}' +
    '.fy-insp-hl{position:fixed;z-index:2147483644;pointer-events:none;' +
      'background:rgba(16,185,129,.14);border:2px solid rgba(16,185,129,.95);border-radius:2px;display:none;' +
      'transition:background-color .45s ease-out}' +
    // After the arrival flash, the fill fades out and only the border remains —
    // so the green stops tinting the element you're actively editing.
    '.fy-insp-hl.settled{background:rgba(16,185,129,0)}' +
    '@keyframes fy-insp-pulse{0%{box-shadow:0 0 0 0 rgba(16,185,129,.55)}100%{box-shadow:0 0 0 9px rgba(16,185,129,0)}}' +
    '.fy-insp-hl.pulse{animation:fy-insp-pulse .55s ease-out}';
  document.head.appendChild(style);

  var box = el('div', 'fy-insp-box');
  var label = el('div', 'fy-insp-label');
  var badge = el('div', 'fy-insp-badge');
  var toast = el('div', 'fy-insp-toast');
  [box, label, badge, toast].forEach(function (n) { document.body.appendChild(n); });

  function el(tag, cls) { var n = document.createElement(tag); n.className = cls; return n; }
  function ours(t) { return t === box || t === badge || label.contains(t) || badge.contains(t); }

  // ---- dev WebSocket: send open requests, receive results ----
  var ws = null, wsAttempt = 0, wsConnected = false, wsGen = 0;
  // Bounded backoff (1.5s → 30s), like the editor agent, so a down dev server
  // doesn't reconnect-spin; reset on a successful open. The browser's WebSocket
  // fires close/error reliably, so reconnect is purely event-driven — no
  // heartbeat (the badge tracks wsConnected via those same events).
  function scheduleReconnect() {
    var delay = Math.min(30000, 1500 * Math.pow(2, Math.min(wsAttempt, 4)));
    wsAttempt++;
    setTimeout(connect, delay);
  }
  function connect() {
    var gen = ++wsGen;          // invalidates any superseded socket's handlers
    var sock;
    try {
      sock = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/dev/ws');
    } catch (e) { ws = null; wsConnected = false; apply(); scheduleReconnect(); return; } // sync failure still retries
    ws = sock;
    sock.addEventListener('open', function () {
      if (gen !== wsGen) return;
      wsAttempt = 0; wsConnected = true;
      apply();
    });
    sock.addEventListener('message', function (e) {
      if (gen !== wsGen) return;
      try {
        var m = JSON.parse(e.data);
        if (m && m.type === 'open-result') {
          flash(m.ok ? ('→ ' + m.src + ':' + m.line) : ('open failed: ' + (m.error || '')), m.ok);
        } else if (m && m.type === 'highlight') {
          handleHighlight(m);
        } else if (m && m.type === 'connected') {
          clearReverse();    // fresh stream after (re)connect — drop any stale boxes
          revTargetKey = null; revSettled = false; clearTimeout(revSettleTimer);
        }
      } catch (err) {
        // Don't swallow silently — a throw in handleHighlight would otherwise kill
        // the reverse inspector with no trace.
        try { console.warn('[myapp inspector] message handling failed:', err); } catch (_) {}
      }
    });
    sock.addEventListener('close', function () { if (gen !== wsGen) return; ws = null; wsConnected = false; apply(); scheduleReconnect(); });
    sock.addEventListener('error', function () { try { sock.close(); } catch (_) {} });
  }
  // data-myapp-src is "file:line:col" (col may be absent). Paths carry no colon,
  // so peel numeric line/col off the right; the remainder is the file.
  function parseSrc(srcAttr) {
    var p = srcAttr.split(':');
    var col, line;
    if (p.length >= 3 && /^\d+$/.test(p[p.length - 1]) && /^\d+$/.test(p[p.length - 2])) {
      col = parseInt(p.pop(), 10);
      line = parseInt(p.pop(), 10);
    } else if (p.length >= 2 && /^\d+$/.test(p[p.length - 1])) {
      line = parseInt(p.pop(), 10);
    }
    return { file: p.join(':'), line: line || 1, col: col };
  }
  function sendOpen(srcAttr) {
    var loc = parseSrc(srcAttr);
    if (ws && ws.readyState === 1) {
      ws.send(JSON.stringify({ type: 'open', src: loc.file, line: loc.line, col: loc.col }));
      flash('opening ' + loc.file + ':' + loc.line + (loc.col ? ':' + loc.col : ''), true);
    } else {
      flash('dev socket not connected', false);
    }
  }

  var toastTimer = null;
  function flash(msg, ok) {
    toast.textContent = msg;
    toast.style.background = ok ? '#065f46' : '#7f1d1d';
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toast.classList.remove('show'); }, 1800);
  }

  // ---- DOM-tree tracing ----
  // Walk ancestors carrying data-myapp-src, innermost-first, into breadcrumb
  // STEPS {node, src, name, kind}. A component instance (a node with a
  // data-myapp-callsite) expands into TWO steps — the component definition and,
  // just outside it, its call site — so you can navigate / Alt+wheel-select
  // either one. Both steps point at the same DOM node (same highlight box); they
  // differ only in where a click navigates (defn vs invocation).
  function chain(node) {
    var out = [], cur = node;
    while (cur) {
      if (cur.nodeType === 1 && cur.getAttribute && cur.getAttribute('data-myapp-src')) {
        var nm = shortName(cur);
        var callSrc = cur.getAttribute('data-myapp-callsite');
        out.push({ node: cur, src: cur.getAttribute('data-myapp-src'), name: nm, kind: callSrc ? 'defn' : 'element' });
        if (callSrc) out.push({ node: cur, src: callSrc, name: nm, kind: 'callsite' });
      }
      cur = cur.parentElement;
    }
    return out;
  }
  function shortName(node) {
    var n = node.getAttribute('data-myapp-name') || '?';
    var slash = n.indexOf('/');
    return slash >= 0 ? n.slice(slash + 1) : n; // drop the ns prefix for the label
  }

  function positionBox(node) {
    var r = node.getBoundingClientRect();
    box.style.left = r.left + 'px';
    box.style.top = r.top + 'px';
    box.style.width = r.width + 'px';
    box.style.height = r.height + 'px';
    box.style.display = 'block';
  }

  function crumbTitle(step) {
    return (step.kind === 'callsite' ? 'call site → '
          : step.kind === 'defn' ? 'component def → ' : '') + step.src;
  }
  function makeCrumb(text, step, sel, extra) {
    var c = el('span', 'fy-insp-crumb' + (extra ? ' ' + extra : '') + (step === sel ? ' leaf' : ''));
    c.textContent = text;
    c.title = crumbTitle(step);
    c.addEventListener('click', function (ev) { ev.preventDefault(); ev.stopPropagation(); sendOpen(step.src); });
    c.addEventListener('mouseenter', function () { positionBox(step.node); });
    return c;
  }
  function renderLabel(steps, sel) {
    label.innerHTML = '';
    var disp = steps.slice().reverse(); // outermost → innermost
    var first = true;
    function sep() {
      if (!first) { var s = el('span', 'fy-insp-sep'); s.textContent = '▸'; label.appendChild(s); }
      first = false;
    }
    for (var i = 0; i < disp.length; i++) {
      var step = disp[i], next = disp[i + 1];
      if (next && next.node === step.node) {
        // The two steps of one component instance (its definition + its call site)
        // share a node — fold them: name once, plus two selectable glyphs.
        var defnStep = step.kind === 'defn' ? step : next;
        var callStep = step.kind === 'callsite' ? step : next;
        sep();
        // Highlight the (shared) component name whenever either glyph is selected.
        var active = (sel === defnStep || sel === callStep);
        var nm = el('span', 'fy-insp-cname' + (active ? ' active' : ''));
        nm.textContent = step.name;
        (function (node) { nm.addEventListener('mouseenter', function () { positionBox(node); }); })(step.node);
        label.appendChild(nm);
        // Order outer→inner to match the rest of the trail and the Alt+wheel
        // direction: () is the call site (in the caller, outer); λ is the
        // definition (inner). Wheeling outward then moves the highlight leftward.
        label.appendChild(makeCrumb('()', callStep, sel, 'fy-insp-glyph'));  // → call site (outer)
        label.appendChild(makeCrumb('λ', defnStep, sel, 'fy-insp-glyph'));   // → component definition (inner)
        i++; // consumed `next`
      } else {
        sep();
        label.appendChild(makeCrumb(step.name, step, sel));
      }
    }
    var loc = el('span', 'fy-insp-loc');
    loc.textContent = sel.src || '';
    label.appendChild(loc);

    var r = sel.node.getBoundingClientRect();
    label.style.display = 'block';
    var top = r.top - label.offsetHeight - 4;
    label.style.left = Math.max(2, Math.min(r.left, window.innerWidth - label.offsetWidth - 2)) + 'px';
    label.style.top = (top < 2 ? r.bottom + 4 : top) + 'px';
  }

  function hide() { box.style.display = 'none'; label.style.display = 'none'; }

  // ---- interaction ----
  var pending = null, rafId = 0;
  var hoverChain = [], hoverIdx = 0; // ancestor chain (innermost-first) + selected depth
  function onMove(e) {
    // While Alt is held the dev is wheel-walking the ancestor chain — freeze the
    // selection so pointer motion (or jitter during scroll) can't reset it.
    if (!enabled || ours(e.target) || e.altKey) return; // over our own UI → don't recompute (keeps crumbs clickable)
    pending = e.target;
    if (rafId) return;
    rafId = requestAnimationFrame(function () {
      rafId = 0;
      var leaf = pending && pending.closest ? pending.closest('[data-myapp-src]') : null;
      hoverChain = leaf ? chain(leaf) : [];
      if (!hoverChain.length) { hide(); return; }
      hoverIdx = 0; // moving the mouse resets to the most-nested step
      positionBox(hoverChain[0].node);
      renderLabel(hoverChain, hoverChain[0]);
    });
  }
  // Alt+wheel walks the ancestor chain without moving the mouse: up = outward
  // (toward the component), down = inward. Normal scroll is untouched (no Alt).
  function onWheel(e) {
    // Alt only (not Alt+Ctrl, so pinch-zoom is never intercepted), and only on a
    // real vertical delta (ignore horizontal/zero-delta gestures).
    if (!enabled || !e.altKey || e.ctrlKey || !e.deltaY || !hoverChain.length) return;
    e.preventDefault();
    hoverIdx = Math.max(0, Math.min(hoverChain.length - 1, hoverIdx + (e.deltaY < 0 ? 1 : -1)));
    var step = hoverChain[hoverIdx];
    positionBox(step.node);
    renderLabel(hoverChain, step);
  }
  function onClick(e) {
    if (!enabled || ours(e.target)) return; // crumbs/badge handle their own clicks
    e.preventDefault();
    e.stopPropagation(); // inspect mode swallows the app's click
    // Open the CURRENT selection's source — the step the Alt+wheel walk landed on
    // (defn or call site), not whatever happens to be under the pointer.
    if (hoverChain.length) {
      sendOpen(hoverChain[hoverIdx].src);
    } else {
      var leaf = e.target.closest ? e.target.closest('[data-myapp-src]') : null;
      if (leaf) sendOpen(leaf.getAttribute('data-myapp-src'));
    }
  }

  function apply() {
    // The badge doubles as a dev-socket status light: blue when connected
    // (and brighter when actively inspecting), red when the socket is down.
    badge.classList.toggle('on', enabled);
    badge.classList.toggle('disconnected', !wsConnected);
    badge.textContent = (enabled ? '⌖ inspecting' : '⌖ inspect')
                      + (wsConnected ? '' : ' · ws disconnected');
    document.documentElement.classList.toggle('fy-insp-on', enabled);
    if (!enabled) {
      // Drop the stale forward selection AND any reverse-highlight boxes — the
      // settled border-only state would otherwise linger after leaving inspect
      // mode. Reset the target key so the next editor-cursor event re-lights
      // the same element cleanly (fresh pulse) instead of staying quiet.
      hide(); hoverChain = []; hoverIdx = 0;
      clearReverse(); revTargetKey = null; revSettled = false; clearTimeout(revSettleTimer);
    }
  }
  // Ask the editor (via the server) to re-emit its current cursor, so the
  // reverse highlight reappears without a manual cursor move — used when enabling
  // inspect and after a morph swaps the DOM out from under the live boxes.
  function requestResend() {
    try { if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'request-resend' })); } catch (e) {}
  }
  function setEnabled(v) {
    enabled = v;
    try { localStorage.setItem(STORE, v ? '1' : '0'); } catch (e) {}
    apply();
    // Turning inspect ON: pull in the current cursor's highlight now (apply()
    // cleared the boxes when turning OFF). A fresh page load is already covered
    // by the server's on-connect resend, so only the live toggle needs this.
    if (v) requestResend();
  }

  badge.addEventListener('click', function (e) { e.stopPropagation(); setEnabled(!enabled); });
  document.addEventListener('mousemove', onMove, true);
  document.addEventListener('click', onClick, true);
  document.addEventListener('wheel', onWheel, { passive: false, capture: true });
  document.addEventListener('keydown', function (e) {
    if (e.altKey && e.shiftKey && (e.key === 'I' || e.key === 'i')) { e.preventDefault(); setEnabled(!enabled); }
    else if (e.key === 'Escape' && enabled) setEnabled(false);
  });
  // Realign lazily: hide on scroll/resize, recompute on the next mousemove.
  window.addEventListener('scroll', function () { if (enabled) hide(); }, true);
  window.addEventListener('resize', function () { if (enabled) hide(); });

  // ---- reverse highlight: editor cursor → on-screen element ----
  // Driven by {type:"highlight", component:"ns/fn", element:"file:line:col", seq}
  // pushed from the server (which resolved the cursor via the source index).
  // Gated by the same "inspect" toggle as the forward direction — the badge is
  // the single on/off for ALL inspector visuals, so the page stays untouched
  // until you engage it: handleHighlight drops pushes while disabled, apply()
  // clears the boxes on the way out, and enabling re-requests the cursor.
  var zoomBoxes = [];          // soft frames (component)
  var hlBoxes = [];            // strong boxes (element/callsite target)
  var revFrameNodes = [];      // nodes the frame tracks (for reposition)
  var revFrameBBox = false;    // frame = one bounding box (span mode) vs per-node
  var revElNodes = [];         // element-target nodes (for reposition)
  var revTargetKey = null;     // identity of the lit element — re-highlighting the
                               // SAME one (cursor moving while you edit) stays quiet
  var revSettleTimer = 0, revSettled = false; // fill flashes on arrival, then fades
                               // to border-only after a beat so it stops tinting edits

  // Attribute-value escape: inside [a="…"] only \\ and " are special, so do NOT
  // use CSS.escape here (it would escape the /, ., : in our keys and break it).
  function attrEsc(s) { return String(s).replace(/[\\"]/g, '\\$&'); }
  function bySel(attr, val) {
    return val ? Array.prototype.slice.call(
      document.querySelectorAll('[' + attr + '="' + attrEsc(val) + '"]')) : [];
  }
  function grow(pool, cls, n) {
    while (pool.length < n) { var b = el('div', cls); document.body.appendChild(b); pool.push(b); }
    return pool;
  }
  function place(boxEl, r) {
    var s = boxEl.style;
    s.left = r.left + 'px'; s.top = r.top + 'px';
    s.width = r.width + 'px'; s.height = r.height + 'px'; s.display = 'block';
  }
  function frameEach(boxes, nodes) {
    for (var i = 0; i < boxes.length; i++) {
      if (i < nodes.length) {
        var r = nodes[i].getBoundingClientRect();
        if (r.width || r.height) place(boxes[i], r); else boxes[i].style.display = 'none';
      } else boxes[i].style.display = 'none';
    }
  }
  function bboxOf(nodes) {
    var l = Infinity, t = Infinity, r = -Infinity, b = -Infinity;
    nodes.forEach(function (n) {
      var x = n.getBoundingClientRect();
      if (x.width || x.height) { l = Math.min(l, x.left); t = Math.min(t, x.top); r = Math.max(r, x.right); b = Math.max(b, x.bottom); }
    });
    return isFinite(l) ? { left: l, top: t, width: r - l, height: b - t } : null;
  }
  // All on-screen nodes whose source line is within the defn's [start,end] span —
  // used to frame components that have no single DOM root (layouts/fragments).
  function spanNodes(file, lines) {
    if (!file || !lines) return [];
    var out = [], all = document.querySelectorAll('[data-myapp-src]');
    for (var i = 0; i < all.length; i++) {
      var loc = parseSrc(all[i].getAttribute('data-myapp-src'));
      if (loc.file === file && loc.line >= lines[0] && loc.line <= lines[1]) out.push(all[i]);
    }
    return out;
  }
  function drawFrame() {
    if (!revFrameNodes.length) return;
    if (revFrameBBox) {
      grow(zoomBoxes, 'fy-insp-zoom', 1);
      var bb = bboxOf(revFrameNodes);
      if (bb) place(zoomBoxes[0], bb);
      for (var i = 1; i < zoomBoxes.length; i++) zoomBoxes[i].style.display = 'none';
    } else {
      grow(zoomBoxes, 'fy-insp-zoom', revFrameNodes.length);
      frameEach(zoomBoxes, revFrameNodes);
    }
  }
  function drawEl() {
    if (!revElNodes.length) return; // nothing active — skip per-scroll churn
    grow(hlBoxes, 'fy-insp-hl', revElNodes.length);
    frameEach(hlBoxes, revElNodes);
  }
  function clearReverse() {
    zoomBoxes.forEach(function (b) { b.style.display = 'none'; });
    hlBoxes.forEach(function (b) { b.style.display = 'none'; });
    revFrameNodes = []; revElNodes = []; revFrameBBox = false;
  }
  // Toggle the green FILL on the active element boxes (the border stays either
  // way). Adding `.settled` rides the CSS transition, fading the wash out.
  function applySettled(on) {
    for (var i = 0; i < revElNodes.length && i < hlBoxes.length; i++) {
      hlBoxes[i].classList.toggle('settled', on);
    }
  }
  function repositionReverse() { drawFrame(); drawEl(); }
  function handleHighlight(m) {
    // Reverse highlight follows the inspect toggle: while disabled, drop the
    // editor-cursor pushes entirely (apply() already cleared any boxes on the
    // way out, and enabling re-requests the cursor — see setEnabled).
    if (!enabled) return;
    // No drop-stale guard: highlights arrive over an ordered WS (already in
    // order), so a seq guard buys nothing and risks stalling if the server's
    // counter resets on a hot-reload. Each highlight simply replaces the last.
    clearReverse();
    if (!m.component) return;
    var compNodes = bySel('data-myapp-name', m.component);
    // Element target (strong), DOM-as-truth precedence:
    //   callsite (this invocation's output) → element literal → component root.
    var elNodes = bySel('data-myapp-callsite', m.callsite), elKey = m.callsite;
    if (!elNodes.length) { elNodes = bySel('data-myapp-src', m.element); elKey = m.element; }
    if (!elNodes.length) { elNodes = compNodes; elKey = m.component; }
    // Component frame (soft): per-instance roots if the component has them, else a
    // single bounding box over its source-span members (root-less layouts).
    if (compNodes.length) { revFrameNodes = compNodes; revFrameBBox = false; }
    else { revFrameNodes = spanNodes(m.file, m['defn-lines']); revFrameBBox = true; }
    revElNodes = elNodes;
    if (!revFrameNodes.length && !revElNodes.length) return;
    // Same element as last time? Then the cursor is just moving WITHIN it (you're
    // editing) — keep it quiet: no pulse, no re-flash, no re-scroll, and hold the
    // border-only state if we'd already settled there.
    var key = (elKey || '') + '|' + (m.component || '');
    var sameTarget = (key === revTargetKey);
    revTargetKey = key;
    drawFrame();
    var focus = elNodes[0] || revFrameNodes[0];
    if (!sameTarget && focus && focus.scrollIntoView) {
      var fr = focus.getBoundingClientRect();
      if (fr.width || fr.height) focus.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
    }
    // A smooth scroll spans many frames; the capturing scroll listener keeps the
    // boxes glued to the nodes — this rAF does the initial placement.
    requestAnimationFrame(function () {
      drawFrame(); drawEl();
      if (sameTarget) { applySettled(revSettled); return; } // hold the fade state, no pulse
      // New target: flash the fill + pulse to locate at a glance, then fade the
      // fill out (border stays) so it stops tinting your live edits.
      revSettled = false;
      applySettled(false);
      hlBoxes.slice(0, revElNodes.length).forEach(function (b) {
        b.classList.remove('pulse'); void b.offsetWidth; b.classList.add('pulse');
      });
      clearTimeout(revSettleTimer);
      revSettleTimer = setTimeout(function () { revSettled = true; applySettled(true); }, 500);
    });
  }
  window.addEventListener('scroll', repositionReverse, true);
  window.addEventListener('resize', repositionReverse);

  connect();
  apply();

  // After a dev morph (the dispatcher swapped <main>), the reverse-highlight boxes
  // track nodes that were just replaced. Clear them and — if inspecting — ask the
  // server to re-emit the editor's cursor so the highlight reappears against the
  // fresh DOM; the WS stays open across a morph, so the on-connect resend never
  // fires on its own.
  document.addEventListener('dispatcher:morphed', function () {
    clearReverse();
    revTargetKey = null; revSettled = false; clearTimeout(revSettleTimer);
    if (enabled) requestResend(); // nothing to re-light while inspect is off
  });
})();
