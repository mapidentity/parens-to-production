// DEV-ONLY: the construction-view overlay (runtime half of the inspector).
//
// Reads <html data-myapp-trace-id> (stamped by trace/wrap-trace under :storm),
// fetches the recorded Trace for THIS page, and renders the call tree + DB ops.
//   - Toggle the panel: rides the inspector's toggle (corner badge or Alt+Shift+I),
//     opening on its `myapp:inspect` event — no key of its own.
//   - Hover a tree node → highlight the SPECIFIC rendered instance it produced.
//   - Click a node's name → open its source in the editor (via /dev/ws → Joyride);
//     click the row → expand its args/return; ▸/▾ collapses children.
//   - Hover a PAGE element (panel open) → box it + reveal/flash its tree node.
//   - Alt+click any element → a flow card tracing its value to the query behind it.
// Inert without a trace-id (i.e. not under ClojureStorm).
(function () {
  "use strict";
  var traceId = document.documentElement.getAttribute("data-myapp-trace-id");
  if (!traceId) return;

  var trace = null, panel = null, bodyEl = null, detailPaneEl = null, open = false, hlBox = null, flowEl = null;
  var traces = {};   // id -> trace JSON. The page can be composed of several morphed regions, each built by its own request; `trace`/`traceId` is the active one shown.
  var hlKey = null, wantOpen = false;            // {name, instance} currently boxed; deferred-open intent
  var rowById = {}, childWrapById = {}, triById = {}, parentById = {};
  var ws = null, wsTimer = null;
  var popped = false, popWin = null, popTimer = null, popBtn = null, icicleEl = null, toggleBtn = null;
  var threadedRows = [], icicleCellById = {}, pageHoverId = null, pageHoverPrevOpacity = "";
  var selectedId = null, selectedElPath = null, selBroadcasting = false, selGuardUntil = 0;   // selected frame + element-path within it
  var temporal = false;   // tree order: false = lexical (source structure), true = temporal (runtime/execution order)
  var repeatSet = {};     // query forms that ran ≥2× this request (N+1 markers)
  // the active tree's children/roots — lexical re-parents lazy frames to where they're
  // written; temporal keeps them where they actually ran (during render).
  function childrenOf(s) { return (temporal ? s["children-rt"] : s.children) || []; }
  function rootsOf() { return (temporal ? trace["roots-rt"] : trace.roots) || []; }
  // docked = a fixed full-height overlay on the right; reused by re-dock.
  var DOCK_CSS = "position:fixed;top:0;right:0;width:480px;height:100vh;z-index:2147483645;" +
    "background:#0b0f19;color:#cbd5e1;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;" +
    "box-shadow:-4px 0 24px rgba(0,0,0,.55);flex-direction:column;";

  function el(tag, css, text) {
    var n = document.createElement(tag);
    if (css) n.style.cssText = css;
    if (text != null) n.textContent = text;
    return n;
  }
  function cssEsc(s) { return String(s).replace(/[\\"]/g, "\\$&"); }

  // ---- /dev/ws: "open in editor" (same protocol as the inspector) ----
  function connectWs() {
    try { ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/dev/ws"); }
    catch (e) { ws = null; return; }
    ws.addEventListener("message", function (e) {
      try { var m = JSON.parse(e.data); if (m && m.type === "open-result") toast(m.ok ? ("→ " + m.src + ":" + m.line) : ("open failed: " + (m.error || ""))); } catch (_) {}
    });
    ws.addEventListener("close", function () { ws = null; clearTimeout(wsTimer); wsTimer = setTimeout(connectWs, 2000); });
    ws.addEventListener("error", function () { try { ws.close(); } catch (_) {} });
  }
  // data-myapp-src / :src is "file:line:col" (col optional); peel numerics off the right.
  function parseSrc(s) {
    var p = String(s).split(":"), col, line;
    if (p.length >= 3 && /^\d+$/.test(p[p.length - 1]) && /^\d+$/.test(p[p.length - 2])) { col = +p.pop(); line = +p.pop(); }
    else if (p.length >= 2 && /^\d+$/.test(p[p.length - 1])) { line = +p.pop(); }
    return { file: p.join(":"), line: line || 1, col: col };
  }
  function openSrc(src) {
    if (!src) { toast("no source location"); return; }
    var loc = parseSrc(src);
    if (ws && ws.readyState === 1) { ws.send(JSON.stringify({ type: "open", src: loc.file, line: loc.line, col: loc.col })); toast("opening " + loc.file + ":" + loc.line); }
    else toast("dev socket not connected");
  }
  var toastEl = null, toastT = null;
  function toast(msg) {
    if (!toastEl) { toastEl = el("div", "position:fixed;left:12px;bottom:46px;z-index:2147483647;pointer-events:none;" +
      "background:#065f46;color:#fff;font:11px ui-monospace,monospace;padding:4px 8px;border-radius:4px;opacity:0;transition:opacity .15s;");
      toastEl.setAttribute("data-myapp-overlay", "1"); document.body.appendChild(toastEl); }
    toastEl.textContent = msg; toastEl.style.opacity = "1";
    clearTimeout(toastT); toastT = setTimeout(function () { toastEl.style.opacity = "0"; }, 1800);
  }

  // ---- highlight box (per-instance when an index is given) ----
  function box() {
    if (!hlBox) {
      hlBox = el("div", "position:fixed;z-index:2147483646;pointer-events:none;" +
        "border:2px solid #34d399;background:rgba(52,211,153,.12);border-radius:3px;display:none;");
      document.body.appendChild(hlBox);
    }
    return hlBox;
  }
  function highlight(name, instance) {
    var nodes = name ? [].slice.call(document.querySelectorAll('[data-myapp-name="' + cssEsc(name) + '"]')) : [];
    if (instance != null && nodes[instance]) nodes = [nodes[instance]];   // the SPECIFIC instance
    var t = null, l = null, r = null, b = null;
    nodes.forEach(function (nd) {
      var k = nd.getBoundingClientRect();
      if (k.width === 0 && k.height === 0) return;
      t = t === null ? k.top : Math.min(t, k.top); l = l === null ? k.left : Math.min(l, k.left);
      r = r === null ? k.right : Math.max(r, k.right); b = b === null ? k.bottom : Math.max(b, k.bottom);
    });
    if (t === null) { hlKey = null; box().style.display = "none"; return null; }
    hlKey = { name: name, instance: instance };
    var bx = box();
    bx.style.display = "block"; bx.style.top = t + "px"; bx.style.left = l + "px";
    bx.style.width = (r - l) + "px"; bx.style.height = (b - t) + "px";
    return nodes[0];
  }
  // box a specific DOM node (used when hovering a produced-markup node)
  function highlightNode(node) {
    if (!node) { box().style.display = "none"; return; }
    var k = node.getBoundingClientRect();
    if (k.width === 0 && k.height === 0) { box().style.display = "none"; return; }
    hlKey = null;
    var bx = box();
    bx.style.display = "block"; bx.style.top = k.top + "px"; bx.style.left = k.left + "px";
    bx.style.width = k.width + "px"; bx.style.height = k.height + "px";
  }

  function vref(v) {
    if (!v) return "";
    switch (v.kind) {
      case "nil": return "nil";
      case "db": return "db@t" + (v["basis-t"] != null ? v["basis-t"] : "?");
      case "scalar": return v.preview;
      case "hiccup": return "<" + v.tag + ">";          // a rendered element
      case "map": return "{" + (v.eid != null ? "#" + v.eid + " " : "") + v.n + " keys}";
      case "coll": return v.n + " items";
      case "seq": return "(lazy)";
      default: return v.preview || v.type || "?";
    }
  }
  // preattentive type encoding: hue per kind (Bertin/Munzner — type is categorical)
  var VCOLOR = { map: "#a78bfa", coll: "#60a5fa", set: "#22d3ee", seq: "#64748b",
                 hiccup: "#34d399", db: "#fbbf24", scalar: "#cbd5e1", nil: "#475569", opaque: "#94a3b8" };
  function vglyph(v) {
    var s = el("span", "color:" + (VCOLOR[v && v.kind] || "#cbd5e1") + ";white-space:nowrap;", vref(v));
    if (v && v.eid != null) s.title = "entity #" + v.eid;
    return s;
  }

  // ---- tree ----
  function dbopRow(op, depth) {
    var row = el("div", "padding:0 6px 0 " + (10 + depth * 12) + "px;color:#fbbf24;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;");
    row.appendChild(el("span", "opacity:.7;", "⛁ "));
    row.appendChild(el("span", "", op.form));
    row.appendChild(el("span", "color:#9ca3af;margin-left:6px;", "→ " + vref(op.result) + (op["basis-t"] != null ? "  @t" + op["basis-t"] : "")));
    return row;
  }
  // arg→ret transformation badge: <from> → <to> (+N −N ~N key diff)
  function morphBadge(m) {
    var b = el("span", "");
    b.appendChild(vglyph(m.from));
    b.appendChild(el("span", "color:#6b7280;margin:0 5px;", "→"));
    b.appendChild(vglyph(m.to));
    if (m.diff) {
      var parts = [];
      if (m.diff.added && m.diff.added.length) parts.push("+" + m.diff.added.length);
      if (m.diff.removed && m.diff.removed.length) parts.push("−" + m.diff.removed.length);
      if (m.diff.changed && m.diff.changed.length) parts.push("~" + m.diff.changed.length);
      if (parts.length) { var dd = el("span", "color:#fbbf24;margin-left:8px;", parts.join(" ") + " keys");
        dd.title = "added " + (m.diff.added || []).join(", ") + "\nremoved " + (m.diff.removed || []).join(", ") + "\nchanged " + (m.diff.changed || []).join(", "); b.appendChild(dd); }
    }
    return b;
  }
  // identity threading: highlight every frame a value flows through
  function clearThread() { threadedRows.forEach(function (r) { r.style.borderLeft = ""; }); threadedRows = []; applyDOI(null, false); }
  function threadValue(frame, slot) {
    clearThread();
    fetch("/dev/__value-threads/" + encodeURIComponent(traceId) + "?frame=" + frame + "&slot=" + encodeURIComponent(slot), { headers: { Accept: "application/json" } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d || !d.frames) return;
        var set = {};
        d.frames.forEach(function (f) { var row = rowById[f.frame]; if (row) { revealRow(f.frame); row.style.borderLeft = "2px solid #a78bfa"; threadedRows.push(row); } set[f.frame] = true; for (var p = parentById[f.frame]; p != null; p = parentById[p]) set[p] = true; });
        applyDOI(set, true);   // focus+context: keep the value's path lit, gently dim everything it never touches
        if (d.frames[0] && rowById[d.frames[0].frame]) rowById[d.frames[0].frame].scrollIntoView({ block: "center" });
        toast("value flows through " + d.frames.length + " frames");
      }).catch(function () {});
  }
  function renderTable(t, depth) {
    var tbl = el("table", "margin:2px 0 2px " + (depth * 12) + "px;border-collapse:collapse;font-size:10px;");
    var hr = el("tr");
    (t.columns || []).forEach(function (c) { var th = el("th", "text-align:left;color:#94a3b8;padding:1px 8px 1px 0;border-bottom:1px solid #334155;"); th.textContent = c; hr.appendChild(th); });
    tbl.appendChild(hr);
    (t.rows || []).forEach(function (rw) { var tr = el("tr"); rw.forEach(function (cell) { var td = el("td", "padding:1px 8px 1px 0;"); td.appendChild(vglyph(cell)); tr.appendChild(td); }); tbl.appendChild(tr); });
    return tbl;
  }
  function fetchExpand(frame, slot, path, container, depth) {
    container.appendChild(el("div", "color:#6b7280;padding-left:" + (depth * 12 + 14) + "px;", "…"));
    fetch("/dev/__value/" + encodeURIComponent(traceId) + "?frame=" + frame + "&slot=" + encodeURIComponent(slot) + "&path=" + path.join(","), { headers: { Accept: "application/json" } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        container.textContent = "";
        if (!d) { container.appendChild(el("div", "color:#f87171;padding-left:14px;", "expand failed")); return; }
        if (d.table) { container.appendChild(renderTable(d.table, depth + 1)); return; }
        (d.children || []).forEach(function (c) { container.appendChild(valueNode(frame, slot, path.concat([c.i]), c.val, c.k, depth + 1)); });
        if (d.truncated) container.appendChild(el("div", "color:#6b7280;padding-left:" + ((depth + 1) * 12 + 12) + "px;", "… (first 50)"));
      }).catch(function () { container.textContent = ""; });
  }
  // one navigable value (datafy/nav-style): glyph + ▸ lazy-expand + ⌖ thread
  function valueNode(frame, slot, path, vr, keyLabel, depth) {
    var wrap = el("div");
    var row = el("div", "padding:1px 0 1px " + (depth * 12) + "px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;");
    var expandable = vr && ["map", "coll", "set", "seq", "hiccup"].indexOf(vr.kind) >= 0 && (vr.n == null || vr.n > 0);
    var tri = el("span", "display:inline-block;width:11px;color:#6b7280;" + (expandable ? "cursor:pointer;" : ""), expandable ? "▸" : " ");
    row.appendChild(tri);
    if (keyLabel != null) row.appendChild(el("span", "color:#94a3b8;", keyLabel + ": "));
    row.appendChild(vglyph(vr));
    if (!path.length) { var th = el("span", "cursor:pointer;color:#a78bfa;margin-left:8px;font-size:10px;", "⌖"); th.title = "thread: highlight every frame this value flows through"; th.addEventListener("click", function (e) { e.stopPropagation(); threadValue(frame, slot); }); row.appendChild(th); }
    wrap.appendChild(row);
    var kids = el("div"); kids.style.display = "none"; wrap.appendChild(kids);
    var loaded = false;
    if (expandable) tri.addEventListener("click", function (e) {
      e.stopPropagation();
      if (kids.style.display === "none") { kids.style.display = "block"; tri.textContent = "▾"; if (!loaded) { loaded = true; fetchExpand(frame, slot, path, kids, depth); } }
      else { kids.style.display = "none"; tri.textContent = "▸"; }
    });
    return wrap;
  }
  function srcLink(glyph, label, src, color) {
    var o = el("div", "color:" + color + ";cursor:pointer;margin-bottom:3px;", glyph + " " + label + "  " + src);
    o.title = "open " + src;
    o.addEventListener("click", function (e) { e.stopPropagation(); openSrc(src); });
    return o;
  }
  // append the selected frame's detail rows to a container (used by the bottom pane)
  function fillDetails(d, s) {
    // error first — if this frame threw, it's the most important thing
    if (s.threw) {
      var er = el("div", "margin:2px 0 6px;padding:4px 8px;background:#3f1d1d;border-left:2px solid #f87171;border-radius:3px;");
      er.appendChild(el("div", "color:#fca5a5;font-weight:600;", "⚠ threw  " + s.threw.type));
      if (s.threw.msg) er.appendChild(el("div", "color:#fecaca;font-size:11px;white-space:pre-wrap;word-break:break-word;margin-top:1px;", s.threw.msg));
      if (s.threw.form) er.appendChild(el("div", "color:#fca5a5;font-size:10px;margin-top:2px;", "throwing form: " + s.threw.form));
      if (s.threw.at) { var ta = el("div", "color:#fca5a5;cursor:pointer;font-size:10px;", "↗ threw at " + s.threw.at + (s["call-src"] && s["call-src"] !== s.threw.at ? "  (written at " + s["call-src"] + ")" : "")); ta.addEventListener("click", function (e) { e.stopPropagation(); openSrc(s.threw.at); }); er.appendChild(ta); }
      d.appendChild(er);
    }
    // call site first (the logical flow of execution), definition second
    if (s["call-src"]) {
      d.appendChild(srcLink("↳", "called at", s["call-src"], "#34d399"));
      if (s["call-form"]) d.appendChild(el("div", "color:#6b7280;font-size:10px;margin:-2px 0 4px 14px;white-space:pre-wrap;", s["call-form"]));
    }
    if (s.src) d.appendChild(srcLink("ƒ", "defined at", s.src, "#22d3ee"));
    // lazily-realized frame: written here, but actually RAN later during render
    if (s.lazy && s["realized-by"]) {
      var lr = el("div", "color:#c084fc;cursor:pointer;margin-bottom:3px;", "⟿ realized during render — ran under " + s["realized-by"]);
      lr.title = "this frame is written here but evaluated lazily (a (for …) seq forced during HTML serialization). Click to jump to where it actually ran.";
      lr.addEventListener("click", function (e) { e.stopPropagation(); if (s["realized-by-id"] != null) setSelected(s["realized-by-id"], true); });
      d.appendChild(lr);
    }
    // data source: the DB read that produced the entity this frame RECEIVED (its
    // input came from somewhere — often an upstream query, not this frame's own).
    if ((s.args || []).some(function (a) { return a && a.eid != null; })) {
      var ds = el("div", "margin:6px 0 2px;"); ds.appendChild(el("span", "color:#94a3b8;", "data source"));
      var dsH = el("div", "color:#6b7280;margin-top:2px;", "…"); ds.appendChild(dsH); d.appendChild(ds);
      var sfid = s.id;
      fetch("/dev/__source/" + encodeURIComponent(traceId) + "?frame=" + sfid, { headers: { Accept: "application/json" } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          if (selectedId !== sfid) return;
          dsH.textContent = "";
          if (!j || j.eid == null) { dsH.appendChild(el("span", "color:#6b7280;", "(no entity arg)")); return; }
          if (!(j.reads || []).length) { dsH.appendChild(el("span", "color:#6b7280;", "entity #" + j.eid + " — no in-request query produced it (likely a prior write)")); return; }
          dsH.appendChild(el("div", "color:#6b7280;font-size:10px;margin-bottom:2px;", "entity #" + j.eid + " produced by:"));
          j.reads.forEach(function (op) {
            var q = el("div", "margin:0 0 4px 4px;padding:3px 6px;background:#111827;border-left:2px solid #0e7490;border-radius:3px;");
            var hd = el("div", ""); hd.appendChild(el("span", "color:#22d3ee;opacity:.85;", "⛁ " + op.op)); hd.appendChild(el("span", "color:#9ca3af;margin-left:8px;font-size:10px;", "→ " + vref(op.result) + (op["basis-t"] != null ? "  @t" + op["basis-t"] : ""))); q.appendChild(hd);
            q.appendChild(el("div", "color:#cbd5e1;white-space:pre-wrap;word-break:break-word;font-size:11px;margin-top:1px;", op.form));
            dsH.appendChild(q);
          });
        }).catch(function () { dsH.textContent = ""; });
    }
    if (s.morph) { var mr = el("div", "margin:2px 0 4px;"); mr.appendChild(el("span", "color:#94a3b8;", "transforms  ")); mr.appendChild(morphBadge(s.morph)); d.appendChild(mr); }
    // queries this frame ran — the full datalog, basis-t and result
    if ((s["db-ops"] || []).length) {
      d.appendChild(el("div", "color:#94a3b8;margin:6px 0 2px;", (s["db-ops"].length) + " quer" + (s["db-ops"].length > 1 ? "ies" : "y")));
      s["db-ops"].forEach(function (op) {
        var q = el("div", "margin:0 0 4px 4px;padding:3px 6px;background:#111827;border-left:2px solid #b45309;border-radius:3px;");
        var hd = el("div", ""); hd.appendChild(el("span", "color:#fbbf24;opacity:.7;", "⛁ " + op.op)); hd.appendChild(el("span", "color:#9ca3af;margin-left:8px;font-size:10px;", "→ " + vref(op.result) + (op["basis-t"] != null ? "  @t" + op["basis-t"] : ""))); q.appendChild(hd);
        q.appendChild(el("div", "color:#cbd5e1;white-space:pre-wrap;word-break:break-word;font-size:11px;margin-top:1px;", op.form));
        d.appendChild(q);
      });
    }
    (s.args || []).forEach(function (a, i) { d.appendChild(valueNode(s.id, "arg" + i, [], a, "arg" + i, 0)); });
    if (s.ret && s.ret.kind !== "nil") d.appendChild(valueNode(s.id, "ret", [], s.ret, "ret", 0));
    // not rendered: when-family conditionals that were falsy → their body skipped
    // ("why is this section empty?"). Lazy — only the selected frame's exprs.
    (function () {
      var nfid = s.id, nr = el("div", "margin-top:6px;display:none;"), hd = el("span", "color:#94a3b8;", "");
      nr.appendChild(hd); var body = el("div", "margin-top:2px;"); nr.appendChild(body); d.appendChild(nr);
      fetch("/dev/__branches/" + encodeURIComponent(traceId) + "?frame=" + nfid, { headers: { Accept: "application/json" } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          if (selectedId !== nfid || !j || !(j.skipped || []).length) return;
          hd.textContent = "not rendered (" + j.skipped.length + ")"; nr.style.display = "block";
          j.skipped.forEach(function (sk) {
            var row = el("div", "margin:1px 0 2px 4px;color:#6b7280;cursor:pointer;font-size:11px;white-space:pre-wrap;word-break:break-word;", "∅ " + sk.form + "  → falsy");
            row.title = "this (" + sk.op + " …) was falsy, so its body didn't render" + (sk.line ? (" — line " + sk.line) : "");
            if (sk.line && s.src) { var loc = s.src.replace(/:\d+:\d+$/, "") + ":" + sk.line + ":1"; row.addEventListener("click", function (e) { e.stopPropagation(); openSrc(loc); }); }
            body.appendChild(row);
          });
        }).catch(function () {});
    })();
    // produced markup: the Hiccup tree this render component became, aligned with
    // the inspector — the node matching the current selection is highlighted.
    if (s.ret && s.ret.kind === "hiccup") {
      var h = el("div", "margin-top:6px;");
      h.appendChild(el("span", "color:#94a3b8;", "produced markup"));
      var holder = el("div", "color:#6b7280;margin-top:2px;", "…");
      h.appendChild(holder); d.appendChild(h);
      var fid = s.id;
      fetch("/dev/__hiccup/" + encodeURIComponent(traceId) + "?frame=" + fid + "&slot=ret", { headers: { Accept: "application/json" } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { if (selectedId !== fid) return;   // selection moved while fetching — drop
          holder.textContent = "";
          if (j && j.tree) { var target = (selectedElPath && selectedElPath.length) ? markupNodeAtPath(j.tree, selectedElPath) : null; holder.appendChild(hiccupNode(j.tree, 0, s.name, target, { root: j.tree, frame: s })); }
          else holder.appendChild(el("span", "color:#6b7280;", "(no markup)")); })
        .catch(function () { holder.textContent = ""; });
    }
  }
  // the persistent details pane at the bottom — re-rendered whenever selection changes
  function renderDetails(id) {
    if (!detailPaneEl) return;
    detailPaneEl.textContent = "";
    var s = (id != null && trace) ? trace.spans[id] : null;
    if (!s) { detailPaneEl.appendChild(el("div", "color:#6b7280;padding:6px 2px;", "Select a frame — click a row, an element on the page, or a node in the markup — to see how it was built.")); return; }
    var hd = el("div", "color:#38bdf8;font-weight:600;margin-bottom:4px;border-bottom:1px solid #1f2937;padding-bottom:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;");
    hd.appendChild(el("span", "", s.short + (s.instance != null ? "  #" + s.instance : "")));
    hd.appendChild(el("span", "color:#6b7280;font-weight:400;font-size:10px;margin-left:6px;", s.ns));
    detailPaneEl.appendChild(hd);
    fillDetails(detailPaneEl, s);
  }
  // the element-child path from a component root down to a clicked descendant —
  // a list of element indices, so we can find the same node in the markup tree.
  function domPath(comp, leaf) {
    var path = [], n = leaf;
    while (n && n !== comp) {
      var par = n.parentElement; if (!par) return null;
      path.unshift([].slice.call(par.children).indexOf(n));
      n = par;
    }
    return (n === comp) ? path : null;
  }
  // markup el-children, flattening seq wrappers so indices line up with DOM .children
  function markupChildEls(node) {
    var out = [];
    (node.children || []).forEach(function (c) { if (c.kind === "el") out.push(c); else if (c.kind === "seq") out = out.concat(markupChildEls(c)); });
    return out;
  }
  function markupNodeAtPath(root, path) {
    var n = root;
    for (var i = 0; i < path.length; i++) { var ks = markupChildEls(n); n = ks[path[i]]; if (!n) return null; }
    return n;
  }
  // inverse: the element-child path from the markup root down to `target`
  function markupPathOf(root, target) {
    if (root === target) return [];
    var ks = markupChildEls(root);
    for (var i = 0; i < ks.length; i++) { var p = markupPathOf(ks[i], target); if (p) return [i].concat(p); }
    return null;
  }
  // the DOM node at an element-child path under a root element
  function domAtPath(rootEl, path) {
    var n = rootEl;
    for (var i = 0; i < path.length; i++) { n = n && n.children[path[i]]; if (!n) return null; }
    return n;
  }
  // the live DOM root of a frame instance (k-th element with that component name)
  function frameRootEl(frame) {
    if (!frame) return null;
    var nodes = document.querySelectorAll('[data-myapp-name="' + cssEsc(frame.name) + '"]');
    return nodes[frame.instance != null ? frame.instance : 0] || null;
  }
  // render the produced-markup tree (tag + class + text), matching the inspector.
  // `selComp` = selected component (root highlight); `target` = the exact markup
  // node for the currently-selected element (precise highlight).
  // selecting a markup node → find its live DOM element (by structural path within
  // the displayed frame's instance) and select that element, same as clicking it on
  // the page: editor cursor + inspector box + markup highlight.
  function selectFromMarkup(ctx, n) {
    if (!ctx || !ctx.frame) return;
    var path = markupPathOf(ctx.root, n) || [];
    var domEl = domAtPath(frameRootEl(ctx.frame), path);
    var src = (domEl && domEl.getAttribute("data-myapp-src")) || n.src || ctx.frame["call-src"] || ctx.frame.src;
    if (src) openSrc(src);
    setSelected(ctx.frame.id, true, { node: domEl || frameRootEl(ctx.frame), path: path });
  }
  function hiccupNode(n, depth, selComp, target, ctx) {
    var wrap = el("div");
    if (n.kind === "seq") { (n.children || []).forEach(function (c) { wrap.appendChild(hiccupNode(c, depth, selComp, target, ctx)); }); return wrap; }
    // precise element match wins; else fall back to the component root
    var isSel = n.kind === "el" && (target ? n === target : (selComp && n.comp === selComp));
    var row = el("div", "padding:1px 0 1px " + (depth * 12) + "px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;" + (isSel ? "background:#0e2a3a;border-radius:3px;" : ""));
    if (n.kind === "el") {
      var kids = n.children || [];
      var tri = el("span", "display:inline-block;width:11px;color:#6b7280;" + (kids.length ? "cursor:pointer;" : ""), kids.length ? "▾" : " ");
      row.appendChild(tri);
      var tag = el("span", "color:" + (n.comp ? "#34d399" : "#7dd3fc") + ";cursor:pointer;", "<" + n.tag + ">");
      tag.title = n.sel + (n.comp ? ("  ·  " + n.comp) : "") + "  —  select";
      // clicking ANY markup element selects it (maps back to its live DOM node)
      tag.addEventListener("click", function (e) { e.stopPropagation(); selectFromMarkup(ctx, n); });
      tag.addEventListener("mouseenter", function () { var de = domAtPath(frameRootEl(ctx && ctx.frame), markupPathOf(ctx && ctx.root, n) || []); if (de) highlightNode(de); });
      row.appendChild(tag);
      if (n.class) row.appendChild(el("span", "color:#475569;margin-left:5px;font-size:10px;", "." + n.class.split(" ")[0] + (n.class.indexOf(" ") > 0 ? "…" : "")));
      if (n.val != null) row.appendChild(el("span", "color:#fbbf24;margin-left:6px;font-size:10px;", "= " + n.val));
      wrap.appendChild(row);
      var kc = el("div");
      kids.forEach(function (c) { kc.appendChild(hiccupNode(c, depth + 1, selComp, target, ctx)); });
      wrap.appendChild(kc);
      if (kids.length) tri.addEventListener("click", function (e) { e.stopPropagation(); kc.style.display = kc.style.display === "none" ? "block" : "none"; tri.textContent = kc.style.display === "none" ? "▸" : "▾"; });
      if (isSel) requestAnimationFrame(function () { try { row.scrollIntoView({ block: "nearest" }); } catch (e) {} });
    } else if (n.kind === "text") {
      row.appendChild(el("span", "display:inline-block;width:11px;", " "));
      row.appendChild(el("span", "color:#fbbf24;", '"' + n.text + '"'));
      wrap.appendChild(row);
    } else {
      row.appendChild(el("span", "color:#9ca3af;", n.preview || "·")); wrap.appendChild(row);
    }
    return wrap;
  }
  function renderNode(id, depth) {
    var s = trace.spans[id]; if (!s) return null;
    var wrap = el("div");
    var kids = childrenOf(s);
    var row = el("div", "padding:1px 6px 1px " + (6 + depth * 12) + "px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;border-radius:3px;cursor:default;");
    // disclosure
    var tri = el("span", "display:inline-block;width:12px;color:#6b7280;cursor:pointer;", kids.length ? "▾" : "·");
    if (kids.length) tri.addEventListener("click", function (e) {
      e.stopPropagation();
      var cw = childWrapById[id], c = cw.style.display === "none";
      cw.style.display = c ? "block" : "none"; tri.textContent = c ? "▾" : "▸";
    });
    row.appendChild(tri);
    // name → CALL SITE (where it's invoked — the logical flow), falling back to the
    // definition when there's no recorded caller (roots). The ns label → DEFINITION.
    var callSrc = s["call-src"], defSrc = s.src;
    var nameSpan = el("span", "color:#e5e7eb;cursor:pointer;", s.short);
    nameSpan.title = callSrc ? ("open call site  " + callSrc + (s["call-form"] ? ("\n" + s["call-form"]) : "") + (defSrc ? ("\n(ns → definition " + defSrc + ")") : ""))
                             : (defSrc ? ("open " + defSrc) : s.name);
    // navigating to code syncs the selection: an on-screen frame becomes the
    // selection; an off-screen one (middleware/handler/db — no element) clears it.
    nameSpan.addEventListener("click", function (e) { e.stopPropagation(); openSrc(callSrc || defSrc); setSelected(s.instance != null ? s.id : null, true); });
    row.appendChild(nameSpan);
    if (s.ret && s.ret.kind !== "nil") { var rg = el("span", "font-size:10px;margin-left:6px;"); rg.appendChild(el("span", "color:#6b7280;", "→ ")); rg.appendChild(vglyph(s.ret)); row.appendChild(rg); }
    if (s.instance != null) row.appendChild(el("span", "color:#475569;font-size:10px;margin-left:5px;", "#" + s.instance));
    if (s.lazy) { var lz = el("span", "color:#c084fc;font-size:10px;margin-left:6px;cursor:help;", "⟿ lazy"); lz.title = "realized lazily during render — ran under " + (s["realized-by"] || "serialization") + ", not inline here"; row.appendChild(lz); }
    var rep = (s["db-ops"] || []).filter(function (op) { return repeatSet[op.form]; });
    if (rep.length) { var rb = el("span", "color:#fb923c;font-size:10px;margin-left:6px;cursor:help;", "⟳×" + repeatSet[rep[0].form]); rb.title = "this query ran " + repeatSet[rep[0].form] + "× this request — possible N+1"; row.appendChild(rb); }
    if (s.threw) { var tb = el("span", "color:#f87171;font-size:10px;margin-left:6px;cursor:help;", "⚠ threw"); tb.title = s.threw.type + (s.threw.msg ? (": " + s.threw.msg) : ""); row.appendChild(tb); }
    var nsSpan = el("span", "color:#6b7280;font-size:10px;margin-left:8px;" + (defSrc ? "cursor:pointer;" : ""), s.ns);
    if (defSrc) { nsSpan.title = "open definition  " + defSrc; nsSpan.addEventListener("click", function (e) { e.stopPropagation(); openSrc(defSrc); setSelected(s.instance != null ? s.id : null, true); }); }
    row.appendChild(nsSpan);
    // hovering a row previews the element via the INSPECTOR's highlight (so it
    // matches selection visually), restored on leave — see myapp:peek in inspector.js
    row.addEventListener("mouseenter", function () { row.style.background = "#1f2937"; try { document.dispatchEvent(new CustomEvent("myapp:peek", { detail: { name: s.name, idx: s.instance == null ? 0 : s.instance } })); } catch (e) {} });
    row.addEventListener("mouseleave", function () { row.style.background = ""; try { document.dispatchEvent(new CustomEvent("myapp:peek", { detail: {} })); } catch (e) {} });
    // clicking a row selects the frame → bottom details pane + inspector highlight + editor cursor
    row.addEventListener("click", function (e) { e.stopPropagation(); openSrc(s["call-src"] || s.src); setSelected(s.id, true); });
    wrap.appendChild(row);
    rowById[id] = row;
    (s["db-ops"] || []).forEach(function (op) { wrap.appendChild(dbopRow(op, depth + 1)); });
    var childWrap = el("div");
    childWrapById[id] = childWrap; triById[id] = tri;
    kids.forEach(function (c) { parentById[c] = id; var cn = renderNode(c, depth + 1); if (cn) childWrap.appendChild(cn); });
    wrap.appendChild(childWrap);
    return wrap;
  }

  // reveal a tree row (expand its collapsed ancestors), scroll the panel to it, flash it
  function revealRow(id) {
    for (var p = parentById[id]; p != null; p = parentById[p]) {
      if (childWrapById[p]) { childWrapById[p].style.display = "block"; if (triById[p]) triById[p].textContent = "▾"; }
    }
    var row = rowById[id];
    if (row) {
      row.scrollIntoView({ block: "nearest" });   // nearest: only scrolls the panel when off-view (no jump on every select)
      var prev = row.style.background;
      row.style.background = "#374151";
      setTimeout(function () { row.style.background = prev; }, 600);
    }
  }
  // find the span id for a (name, instance) — the join the inspector hover uses
  function findSpan(name, instance) {
    var ss = trace.spans;
    for (var k in ss) if (ss[k].name === name && ss[k].instance === instance) return k;
    return null;
  }

  // detach: dragging the header pops the docked panel into a floating window
  function makeDraggable(pane, handle) {
    var sx, sy, sl, st, dragging = false;
    function mv(e) { if (!dragging) return; pane.style.left = (sl + e.clientX - sx) + "px"; pane.style.top = Math.max(0, st + e.clientY - sy) + "px"; }
    function up() { dragging = false; document.removeEventListener("mousemove", mv, true); document.removeEventListener("mouseup", up, true); }
    handle.addEventListener("mousedown", function (e) {
      dragging = true;
      if (popped || (e.target.getAttribute && e.target.getAttribute("data-nodrag"))) return;
      var r = pane.getBoundingClientRect();
      pane.style.right = "auto"; pane.style.height = Math.min(r.height, Math.round(window.innerHeight * 0.8)) + "px";
      pane.style.borderRadius = "8px"; pane.style.left = r.left + "px"; pane.style.top = r.top + "px";
      sx = e.clientX; sy = e.clientY; sl = r.left; st = r.top;
      e.preventDefault();
      document.addEventListener("mousemove", mv, true);
      document.addEventListener("mouseup", up, true);
    });
  }

  // pop out into a SEPARATE same-origin window so it never overlaps the app;
  // our handlers still query `document` (the opener), so hovering a node in the
  // popout highlights the element in the main window.
  function dockStyles() { panel.style.cssText = DOCK_CSS + "display:flex;"; }
  function redock() {
    if (!popped) return;
    popped = false;
    try { document.adoptNode(panel); } catch (e) {}
    document.body.appendChild(panel);
    dockStyles();
    if (popBtn) popBtn.textContent = "⤢";
    if (popWin && !popWin.closed) { try { popWin.close(); } catch (e) {} }
    popWin = null; clearInterval(popTimer);
  }
  function popOut() {
    if (popped) { if (popWin && !popWin.closed) popWin.focus(); return; }
    var w; try { w = window.open("", "myapp-construction-view", "popup,width=520,height=900"); } catch (e) { w = null; }
    if (!w) { toast("popup blocked — drag the header to detach instead"); return; }
    popWin = w; popped = true;
    try { w.document.title = "⛶ construction view"; w.document.body.style.cssText = "margin:0;background:#0b0f19;"; w.document.adoptNode(panel); } catch (e) {}
    w.document.body.appendChild(panel);
    panel.style.cssText = "position:static;width:100%;height:100vh;box-sizing:border-box;" +
      "background:#0b0f19;color:#cbd5e1;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;display:flex;flex-direction:column;";
    if (popBtn) popBtn.textContent = "⤓";
    popTimer = setInterval(function () { if (!popWin || popWin.closed) redock(); }, 500);   // re-dock when closed
  }

  // ---- icicle overview (flame-graph cousin: width ∝ subtree size, color ∝ layer) ----
  function nsColor(ns) {
    ns = ns || "";
    if (ns.indexOf("web.routes") >= 0) return "#0e7490";   // middleware (teal)
    if (ns.indexOf("web.handler") >= 0) return "#1d4ed8";  // handler (blue)
    if (ns.indexOf("db.core") >= 0) return "#b45309";       // db (amber)
    if (ns.indexOf("recipe.core") >= 0 || ns.indexOf("auth.core") >= 0 || ns.indexOf("admin.core") >= 0 || ns.indexOf("analytics") >= 0) return "#15803d"; // domain (green)
    if (ns.indexOf("views") >= 0) return "#7c3aed";        // views (purple)
    if (ns.indexOf("web.assets") >= 0 || ns.indexOf("config") >= 0) return "#475569";
    if (ns.indexOf("i18n") >= 0) return "#be185d";
    return "#334155";
  }
  function subtreeSize(id, cache) {
    if (cache[id] != null) return cache[id];
    var s = trace.spans[id], n = 1;
    if (s) childrenOf(s).forEach(function (c) { n += subtreeSize(c, cache); });
    return (cache[id] = n);
  }
  function peekFrame(s) { try { document.dispatchEvent(new CustomEvent("myapp:peek", { detail: { name: s.name, idx: s.instance == null ? 0 : s.instance } })); } catch (e) {} }
  function peekClear() { try { document.dispatchEvent(new CustomEvent("myapp:peek", { detail: {} })); } catch (e) {} }
  // Icicle = a flame-graph of THIS request's call tree. One cell per frame; a cell's
  // width ∝ the work under it (subtree size) and its color ∝ the layer. It's a map:
  // hover a cell to highlight the element on the page, click to select the frame.
  function buildIcicle() {
    icicleCellById = {};
    var cont = el("div", "position:relative;flex:none;overflow:auto;border-bottom:1px solid #1f2937;background:#0b0f19;");
    cont.title = "Call-tree map for this request — each cell is a frame; width ∝ work (subtree size), color ∝ layer (teal middleware · blue handler · green domain · purple views · amber db). Hover → highlight on the page; click → select the frame.";
    cont.addEventListener("click", function () { clearThread(); });
    var roots = rootsOf(); if (!roots.length) { cont.style.height = "0px"; return cont; }
    var cache = {}, rowH = 11, maxD = 0;
    function place(id, x, w, depth) {
      if (w <= 0 || depth > 60) return;
      var s = trace.spans[id]; if (!s) return;
      if (depth > maxD) maxD = depth;
      var cell = el("div", "position:absolute;box-sizing:border-box;left:" + x + "%;width:" + w + "%;top:" + (depth * rowH) + "px;height:" + (rowH - 1) + "px;" +
        "background:" + nsColor(s.ns) + ";border-right:1px solid #0b0f19;overflow:hidden;white-space:nowrap;font-size:9px;line-height:" + (rowH - 1) + "px;color:#e5e7eb;cursor:pointer;");
      if (w > 8) cell.textContent = " " + s.short;
      cell.title = s.name + (s.instance != null ? "  #" + s.instance : "") + "  ·  " + s.ns + "  — click to select";
      cell.addEventListener("mouseenter", function () { peekFrame(s); });
      cell.addEventListener("mouseleave", function () { peekClear(); });
      cell.addEventListener("click", function (e) {
        e.stopPropagation(); openSrc(s["call-src"] || s.src); setSelected(id, true);
        // Icicle focus: light the clicked frame's subtree + ancestor path and dim
        // the other overview cells. dimRows stays false — the tree keeps full
        // contrast; only value-threading (which isolates a flow) dims rows too.
        var set = {};
        (function sub(x) { set[x] = true; var sp = trace.spans[x]; if (sp) childrenOf(sp).forEach(sub); })(id);
        for (var p = parentById[id]; p != null; p = parentById[p]) set[p] = true;
        applyDOI(set, false);
      });
      icicleCellById[id] = cell; cont.appendChild(cell);
      var kids = childrenOf(s), sizes = kids.map(function (c) { return subtreeSize(c, cache); });
      var sum = sizes.reduce(function (a, b) { return a + b; }, 0), cx = x;
      kids.forEach(function (c, i) { var cw = sum > 0 ? w * sizes[i] / sum : 0; place(c, cx, cw, depth + 1); cx += cw; });
    }
    var rs = roots.map(function (r) { return subtreeSize(r, cache); });
    var sr = rs.reduce(function (a, b) { return a + b; }, 0), rx = 0;
    roots.forEach(function (r, i) { var rw = sr > 0 ? 100 * rs[i] / sr : 0; place(r, rx, rw, 0); rx += rw; });
    cont.style.height = Math.min((maxD + 1) * rowH + 2, 240) + "px";   // size to content (full depth), capped → scroll if very deep
    return cont;
  }
  // ---- degree-of-interest focus+context (icicle clicks + value-threading) ----
  // dim the icicle cells outside `set`; only dim the TREE ROWS too when dimRows is
  // set (value-threading isolates a flow; an icicle click keeps the tree fully
  // readable). The page-hovered row is never dimmed so inspect-sync stays visible.
  function applyDOI(set, dimRows) {
    Object.keys(trace.spans).forEach(function (id) {
      var on = !set || set[id];
      var row = rowById[id]; if (row && id !== pageHoverId) row.style.opacity = (dimRows && !on) ? "0.5" : "1";
      var cell = icicleCellById[id]; if (cell) cell.style.opacity = on ? "1" : "0.35";
    });
  }

  function buildPanel() {
    rowById = {}; childWrapById = {}; triById = {}; parentById = {};
    panel = el("div", DOCK_CSS + "display:none;");
    panel.setAttribute("data-myapp-overlay", "1");   // inspect-mode must NOT swallow clicks here
    var head = el("div", "padding:8px 10px;border-bottom:1px solid #1f2937;background:#111827;cursor:move;");
    popBtn = el("span", "float:right;cursor:pointer;color:#9ca3af;font-size:14px;padding:0 2px;", popped ? "⤓" : "⤢");
    popBtn.title = "Pop out into a separate window";
    popBtn.setAttribute("data-nodrag", "1");
    popBtn.addEventListener("click", function (e) { e.stopPropagation(); if (popped) redock(); else popOut(); });
    head.appendChild(popBtn);
    // Note: recording is auto-managed per page (off between page loads by design —
    // see dev/trace.clj), so a false "recording?" here is normal, not an error.
    var r = trace.request || {};
    var ms = r.ms != null ? (r.ms / (r["ms-scale"] || 1)).toFixed(1) + "ms" : "";
    head.appendChild(el("div", "color:#34d399;font-weight:600;", "⛶ construction view  ·  drag to detach"));
    head.appendChild(el("div", "color:#9ca3af;font-size:11px;", (r.method || "GET") + " " + (r.uri || "") + "  ·  " + (r.status || "") + "  ·  " + ms + "  ·  " + (trace["span-count"] || 0) + " frames"));
    // N+1: queries that ran ≥2× this request — a real inefficiency signal
    if ((trace.repeats || []).length) {
      var rn = trace.repeats.length, label = function (open) { return "⚠ " + rn + " repeated quer" + (rn > 1 ? "ies" : "y") + " (possible N+1) " + (open ? "▾" : "▸"); };
      var warn = el("div", "color:#fbbf24;font-size:11px;margin-top:3px;cursor:pointer;", label(false));
      var list = el("div", "display:none;");
      trace.repeats.forEach(function (rp) {
        var item = el("div", "margin:2px 0 3px 6px;padding:2px 6px;background:#111827;border-left:2px solid #b45309;border-radius:3px;");
        item.appendChild(el("div", "color:#fbbf24;font-size:10px;", "⛁ " + rp.op + " ×" + rp.count));
        item.appendChild(el("div", "color:#cbd5e1;white-space:pre-wrap;word-break:break-word;font-size:10px;", rp.form));
        var chips = el("div", "margin-top:2px;");
        (rp.where || []).forEach(function (wf) { var c = el("span", "cursor:pointer;color:#93c5fd;font-size:10px;border:1px solid #334155;border-radius:3px;padding:0 4px;margin-right:4px;", wf.short); c.setAttribute("data-nodrag", "1"); c.addEventListener("click", function (e) { e.stopPropagation(); setSelected(wf.span, true); }); chips.appendChild(c); });
        item.appendChild(chips); list.appendChild(item);
      });
      warn.setAttribute("data-nodrag", "1");
      warn.addEventListener("click", function (e) { e.stopPropagation(); var open = list.style.display === "none"; list.style.display = open ? "block" : "none"; warn.textContent = label(open); });
      head.appendChild(warn); head.appendChild(list);
    }
    head.appendChild(el("div", "color:#6b7280;font-size:10px;", "row→select (details below) · name→call site · ns→def · ▸collapse · hover↔page"));
    var orderRow = el("div", "margin-top:4px;");
    toggleBtn = el("span", "cursor:pointer;font-size:10px;color:#93c5fd;border:1px solid #334155;border-radius:3px;padding:1px 6px;", temporal ? "⇅ temporal order" : "⇅ lexical order");
    toggleBtn.title = "Tree order — lexical: nested where the code is written (lazy frames re-parented to their owner). temporal: where it actually ran (lazy frames under the render that realized them).";
    toggleBtn.setAttribute("data-nodrag", "1");
    toggleBtn.addEventListener("click", function (e) { e.stopPropagation(); setOrder(!temporal); });
    orderRow.appendChild(toggleBtn);
    head.appendChild(orderRow);
    panel.appendChild(head);
    repeatSet = {}; (trace.repeats || []).forEach(function (rp) { repeatSet[rp.form] = rp.count; });
    makeDraggable(panel, head);
    icicleEl = buildIcicle();                 // flame-graph overview: width ∝ subtree, color ∝ layer
    panel.appendChild(icicleEl);
    bodyEl = el("div", "overflow:auto;flex:1 1 auto;min-height:0;padding:6px 2px;");
    panel.appendChild(bodyEl);
    // draggable divider — resize the details pane taller/shorter
    var resizer = el("div", "flex:none;height:6px;cursor:ns-resize;background:#1f2937;border-top:1px solid #0b0f19;");
    resizer.title = "drag to resize the details pane";
    resizer.setAttribute("data-nodrag", "1");
    resizer.addEventListener("mousedown", function (e) {
      e.preventDefault(); e.stopPropagation();
      function mv(ev) { var pr = panel.getBoundingClientRect(); var h = Math.max(46, Math.min(pr.height - 140, pr.bottom - ev.clientY)); detailPaneEl.style.flex = "0 0 auto"; detailPaneEl.style.height = h + "px"; }
      function up() { document.removeEventListener("mousemove", mv, true); document.removeEventListener("mouseup", up, true); }
      document.addEventListener("mousemove", mv, true); document.addEventListener("mouseup", up, true);
    });
    panel.appendChild(resizer);
    // persistent details pane — tracks the current selection. Default ≈ 1/3 height.
    detailPaneEl = el("div", "flex:0 0 33%;min-height:46px;overflow:auto;padding:6px 8px;background:#0d1320;");
    panel.appendChild(detailPaneEl);
    renderTree();
    renderDetails(selectedId);
    document.body.appendChild(panel);
  }
  // (re)render the tree body in the active order, rebuilding the row indexes
  function renderTree() {
    rowById = {}; childWrapById = {}; triById = {}; parentById = {};
    bodyEl.textContent = "";
    var roots = rootsOf();
    roots.forEach(function (id) { var n = renderNode(id, 0); if (n) bodyEl.appendChild(n); });
    if (!roots.length) bodyEl.appendChild(el("div", "color:#6b7280;padding:10px;", "No frames recorded."));
    if (selectedId != null && rowById[selectedId]) rowById[selectedId].style.boxShadow = "inset 3px 0 0 #38bdf8";   // re-apply selection marker
  }
  // flip lexical ⇄ temporal: rebuild the icicle + tree; focus/thread reset (fresh render)
  function setOrder(t) {
    if (temporal === t || !panel) return;
    temporal = t;
    if (toggleBtn) toggleBtn.textContent = temporal ? "⇅ temporal order" : "⇅ lexical order";
    threadedRows = [];
    var fresh = buildIcicle();
    if (icicleEl && icicleEl.parentNode) icicleEl.parentNode.replaceChild(fresh, icicleEl);
    icicleEl = fresh;
    renderTree();
  }
  function setOpen(v) {
    if (!trace) { wantOpen = v; return; }   // trace not fetched yet — remember the intent
    if (!panel) buildPanel();
    if (!v && popped) redock();             // closing while popped-out → bring it back first
    open = v; panel.style.display = open ? "flex" : "none";
    if (!open) box().style.display = "none";
  }

  // ---- flow card (Alt+click an element) ----
  function showFlow(f, x, y) {
    if (flowEl) flowEl.remove();
    flowEl = el("div", "position:fixed;left:" + Math.min(x, window.innerWidth - 440) + "px;top:" + Math.min(y, window.innerHeight - 320) + "px;width:420px;max-height:60vh;overflow:auto;z-index:2147483647;" +
      "background:#0b0f19;color:#cbd5e1;border:1px solid #34d399;border-radius:8px;padding:10px 12px;font:12px/1.6 ui-monospace,Menlo,monospace;box-shadow:0 8px 28px rgba(0,0,0,.6);");
    flowEl.setAttribute("data-myapp-overlay", "1");   // inspect-mode must NOT swallow clicks here
    var close = el("div", "float:right;cursor:pointer;color:#6b7280;", "✕");
    close.addEventListener("click", function () { flowEl.remove(); flowEl = null; });
    flowEl.appendChild(close);
    if (!f) { flowEl.appendChild(el("div", "color:#9ca3af;", "No recorded frame for this element.")); document.body.appendChild(flowEl); return; }
    if (f.ambiguous) {
      flowEl.appendChild(el("div", "color:#fbbf24;font-weight:600;", "↳ " + ((f.component && f.component.short) || f.component || f.name || "?") + "  —  " + f.instances + " instances, couldn't resolve which one (render reordering)"));
      document.body.appendChild(flowEl); return;
    }
    flowEl.appendChild(el("div", "color:#34d399;font-weight:600;", "↳ " + f.span.short + "  #" + (f.instance + 1) + "/" + f.instances));
    flowEl.appendChild(el("div", "color:#6b7280;font-size:10px;margin-bottom:6px;", (f.path || []).map(function (p) { return p.short; }).join("  ›  ")));
    if (f.value) {
      var vr = el("div", "margin:4px 0;");
      vr.appendChild(el("span", "color:#9ca3af;", "value  "));
      vr.appendChild(el("span", "color:#e5e7eb;", f.value.form + "  → "));
      vr.appendChild(el("span", "color:#60a5fa;", vref(f.value.value)));
      flowEl.appendChild(vr);
    }
    flowEl.appendChild(el("div", "color:#9ca3af;margin-top:6px;", "produced by"));
    (f.reads || []).filter(function (r) { return r.source; }).forEach(function (r) {
      var row = el("div", "color:#fbbf24;padding-left:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;");
      row.appendChild(el("span", "", "⛁ " + r.form));
      row.appendChild(el("span", "color:#9ca3af;margin-left:6px;", "→ " + vref(r.result) + (r["basis-t"] != null ? "  @t" + r["basis-t"] : "")));
      flowEl.appendChild(row);
    });
    if (!(f.reads || []).some(function (r) { return r.source; })) flowEl.appendChild(el("div", "color:#6b7280;padding-left:8px;", "(no in-request query produced this entity — likely a prior write)"));
    if (f.pivot && f.pivot.eid != null) flowEl.appendChild(el("div", "color:#6b7280;margin-top:6px;font-size:10px;", "entity #" + f.pivot.eid + " — to see why it holds this value, check its write history (d/history)"));
    document.body.appendChild(flowEl);
  }

  function compInfo(targetEl) {
    var comp = targetEl.closest('[data-myapp-name^="myapp"]');
    if (!comp) return null;
    var name = comp.getAttribute("data-myapp-name");
    var idx = [].slice.call(document.querySelectorAll('[data-myapp-name="' + cssEsc(name) + '"]')).indexOf(comp);
    return { comp: comp, name: name, idx: idx };
  }
  // page element hover → box it + reveal its tree node (DOM → tree). ONE row at a
  // time: clear the previously hovered row so a mouse sweep doesn't accumulate
  // stuck highlights (each mouseover within a component re-fires for the same id).
  function clearPageHover() {
    if (pageHoverId != null && rowById[pageHoverId]) {
      var r = rowById[pageHoverId]; r.style.background = ""; r.style.opacity = pageHoverPrevOpacity;   // restore any DOI dim
    }
    pageHoverId = null; pageHoverPrevOpacity = "";
  }
  function pageReveal(id) {
    if (pageHoverId === id) return;                         // same component — nothing to do
    clearPageHover();
    pageHoverId = id;
    var row = rowById[id]; if (!row) return;
    for (var p = parentById[id]; p != null; p = parentById[p]) { if (childWrapById[p]) { childWrapById[p].style.display = "block"; if (triById[p]) triById[p].textContent = "▾"; } }
    pageHoverPrevOpacity = row.style.opacity || "";
    row.style.background = "#1f2937";
    row.style.opacity = "1";                                // always clearly visible, even under a DOI dim
    row.scrollIntoView({ block: "nearest" });               // nearest, not center — no jarring scroll on every move
  }
  // ---- selection: the clicked element "stays selected"; its row keeps a marker
  // and the page box / tree return to it when the mouse leaves the page (e.g. over
  // the panel). Kept in sync with the inspector's reverse-highlight + editor cursor
  // through the `myapp:select` event, so inspector, trace and editor agree.
  function markSelected(id, elPath) {
    var newId = (id != null && trace && trace.spans[id]) ? id : null;
    var p = elPath || null, pStr = p ? p.join(",") : "", curStr = selectedElPath ? selectedElPath.join(",") : "";
    if (newId === selectedId && pStr === curStr) return;     // truly unchanged
    if (newId !== selectedId) {
      if (selectedId != null && rowById[selectedId]) rowById[selectedId].style.boxShadow = "";
      selectedId = newId;
      if (selectedId != null && rowById[selectedId]) { rowById[selectedId].style.boxShadow = "inset 3px 0 0 #38bdf8"; revealRow(selectedId); }
    }
    selectedElPath = p;                                      // which element within the frame (for the markup highlight)
    renderDetails(selectedId);                               // the bottom pane follows the selection
  }
  // el = {node, path} for an element-level selection (clicked DOM element), or null/undefined
  function setSelected(id, broadcast, el) {
    // a user selection is about to openSrc; the editor's reverse-highlight for that
    // location echoes back (resolving to the CALL SITE's component, e.g. the parent)
    // — guard a short window so the echo can't steal the selection we just made.
    if (broadcast) selGuardUntil = Date.now() + 800;
    markSelected(id, el && el.path);
    if (broadcast) {
      var s = (id != null && trace) ? trace.spans[id] : null;
      selBroadcasting = true;
      try { document.dispatchEvent(new CustomEvent("myapp:select", { detail: s ? { name: s.name, idx: s.instance == null ? 0 : s.instance, node: el && el.node } : { name: null } })); } catch (e) {}
      selBroadcasting = false;
    }
  }
  // mouse over the trace window → drop the transient hover and show the selection
  function revertToSelected() {
    clearPageHover();
    if (selectedId != null && trace.spans[selectedId]) { var s = trace.spans[selectedId]; highlight(s.name, s.instance); }
    else highlight(null);
  }
  function onPageHover(e) {
    if (!open || !trace) return;
    if (panel && panel.contains(e.target)) { if (pageHoverId != null) revertToSelected(); return; }  // over the panel → back to selection
    var ci = compInfo(e.target);
    if (!ci) return;                                        // off a component — keep the last reveal (don't clear/flicker)
    highlight(ci.name, ci.idx);
    if (regionTraceId(e.target) === traceId) { var id = findSpan(ci.name, ci.idx); if (id) pageReveal(id); }  // reveal only within the active region's trace
  }
  function onAltClick(e) {
    if (!trace || !inspectEnabled()) return;
    var ci = compInfo(e.target);
    if (!ci) return;
    if (!e.altKey) {                                        // plain click → select the specific element (inspector still jumps to code)
      var leaf = e.target.closest("[data-myapp-src]") || ci.comp;
      var elInfo = { node: leaf, path: domPath(ci.comp, leaf) };
      var sel = function () { setSelected(findSpan(ci.name, ci.idx), true, elInfo); };
      var rid = regionTraceId(e.target);                    // element may live in a morphed region with its own trace
      if (rid && rid !== traceId) ensureTrace(rid, function (ok) { if (ok) setActiveTrace(rid); sel(); });
      else sel();
      return;
    }
    e.preventDefault(); e.stopImmediatePropagation();
    var srcEl = e.target.closest("[data-myapp-src]");
    var src = srcEl ? srcEl.getAttribute("data-myapp-src") : "";
    var q = "?name=" + encodeURIComponent(ci.name) + "&idx=" + ci.idx + "&src=" + encodeURIComponent(src);
    fetch("/dev/__flow/" + encodeURIComponent(traceId) + q, { headers: { Accept: "application/json" } })
      .then(function (resp) { if (!resp.ok) { console.error("[construction-view] flow fetch failed:", resp.status); return null; } return resp.json(); })
      .then(function (f) { highlight(ci.name, ci.idx); showFlow(f, e.clientX + 8, e.clientY + 8); })
      .catch(function (err) { console.error("[construction-view]", err); });
  }

  // ---- ride the inspector's toggle (one switch for both; no separate button) ----
  function inspectEnabled() { try { return localStorage.getItem("myapp-inspector") === "1"; } catch (e) { return false; } }
  document.addEventListener("myapp:inspect", function (e) { setOpen(!!(e.detail && e.detail.enabled)); });
  // editor cursor / inspector reverse-highlight → mirror the selection here
  document.addEventListener("myapp:select", function (e) {
    if (selBroadcasting || !trace) return;                  // ignore our own echo
    if (Date.now() < selGuardUntil) return;                 // ignore the openSrc reverse-highlight echo right after a click
    var d = e.detail || {};
    if (!d.name) { markSelected(null); return; }
    // a component-level reverse-highlight (no element node) that just echoes our own
    // precise element click — keep the finer selection instead of downgrading it
    if (selectedElPath && selectedId != null && trace.spans[selectedId] && trace.spans[selectedId].name === d.name) return;
    var id = findSpan(d.name, d.idx == null ? 0 : d.idx);
    if (id == null) for (var k in trace.spans) if (trace.spans[k].name === d.name) { id = k; break; }   // any instance
    markSelected(id);
  });
  document.addEventListener("click", onAltClick, true);
  document.addEventListener("mouseover", onPageHover, true);
  var rafPending = false;
  function onViewportChange() { if (rafPending) return; rafPending = true; requestAnimationFrame(function () { rafPending = false; if (hlKey) highlight(hlKey.name, hlKey.instance); }); }
  window.addEventListener("scroll", onViewportChange, true);
  window.addEventListener("resize", onViewportChange);
  window.addEventListener("beforeunload", function () { if (popWin && !popWin.closed) { try { popWin.close(); } catch (e) {} } });

  // fetch a trace into the cache (once), then cb(ok)
  function ensureTrace(id, cb) {
    if (!id) { if (cb) cb(false); return; }
    if (traces[id]) { if (cb) cb(true); return; }
    fetch("/dev/__trace/" + encodeURIComponent(id), { headers: { Accept: "application/json" } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) { if (j && j.spans) { traces[id] = j; if (cb) cb(true); } else { if (cb) cb(false); } })
      .catch(function () { if (cb) cb(false); });
  }
  // make a cached trace the active one shown in the panel (rebuilds it)
  function activate(id) {
    traceId = id; trace = traces[id]; selectedId = null; selectedElPath = null; temporal = false;
    if (panel) { panel.remove(); panel = null; }
    buildPanel(); open = true; panel.style.display = "flex";
  }
  function setActiveTrace(id) { if (traces[id] && id !== traceId) { activate(id); return true; } return false; }
  // nearest morphed-region trace-id for a DOM element (default: the active trace)
  function regionTraceId(elm) { var n = elm && elm.closest ? elm.closest("[data-myapp-trace-id]") : null; return n ? n.getAttribute("data-myapp-trace-id") : traceId; }
  // load an arbitrary stored trace and show it (e.g. a prior errored request)
  function loadTrace(id) {
    ensureTrace(id, function (ok) {
      if (!ok) return;
      activate(id);
      for (var k in trace.spans) if (trace.spans[k].threw) { setSelected(k, false); break; }   // focus the throw
    });
  }
  // a partial update / navigation / dev hot-reload morphed in a region — its
  // construction is a different request's trace. Switch the panel to it (and so a
  // dev-reload morph no longer leaves the overlay showing the pre-edit trace).
  document.addEventListener("dispatcher:morphed", function (e) {
    var id = (e.detail && e.detail.traceId) || regionTraceId(document.querySelector((e.detail && e.detail.target) || "main"));
    if (!id) return;
    ensureTrace(id, function (ok) {
      if (!ok) return;
      if (open) setActiveTrace(id);            // inspecting → show the just-rendered construction
      else { traceId = id; trace = traces[id]; }  // closed → cache so the next open is fresh
    });
  });
  // a prior request 500'd — surface it (the error page had no overlay of its own)
  function showErrorBanner(le) {
    if (!le || !le.id || le.id === traceId) return;
    var b = el("div", "position:fixed;left:10px;bottom:70px;z-index:2147483647;max-width:380px;background:#3f1d1d;color:#fecaca;border:1px solid #f87171;border-radius:6px;padding:6px 10px;font:11px/1.4 ui-monospace,monospace;cursor:pointer;box-shadow:0 6px 20px rgba(0,0,0,.55);");
    b.setAttribute("data-myapp-overlay", "1");
    b.textContent = "⚠ last request 500'd: " + (le.uri || "") + "  (" + (le.ex || "error") + ") — view trace";
    b.addEventListener("click", function () { b.remove(); loadTrace(le.id); });
    document.body.appendChild(b);
  }
  function start() {
    connectWs();
    fetch("/dev/__trace/" + encodeURIComponent(traceId), { headers: { Accept: "application/json" } })
      .then(function (resp) { if (!resp.ok) { console.error("[construction-view] trace fetch failed:", resp.status); return null; } return resp.json(); })
      .then(function (j) {
        if (j && j.spans) { trace = j; traces[traceId] = j; if (wantOpen || inspectEnabled()) setOpen(true); }    // open if the inspector is already on
        else if (j && j.error) console.error("[construction-view] trace error:", j.error);
      })
      .catch(function (e) { console.error("[construction-view]", e); });
    fetch("/dev/__last-error", { headers: { Accept: "application/json" } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (le) { if (le && le.id) showErrorBanner(le); })
      .catch(function () {});
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else start();
})();
