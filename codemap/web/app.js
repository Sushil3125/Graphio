/* codemap frontend — vanilla JS, no build step.
 * Pure logic (indexing, subgraph selection, path finding, search, ELK graph construction) lives at the top
 * and is exported for node tests. The DOM app boots only in a browser.
 */
(function () {
  'use strict';

  // =====================================================================================
  // Constants
  // =====================================================================================
  const NODE_W = 250, NODE_H = 64;
  const STUB_W = 120, STUB_H = 30;
  const EXT_W = 210, EXT_H = 30;
  const MAX_NODES = 150;
  const PER_NODE_OUT = 12;   // callees shown per node before a "+N more" stub
  const PER_NODE_IN = 10;    // callers shown per node before a "+N more" stub
  const PAGE = 25;           // extra callers/callees revealed per click on a "+N" stub
  const ALL_KINDS = ['calls', 'depends', 'instantiates', 'inherits', 'includes_router', 'routes', 'references', 'implements'];
  // Path finding follows anything that transfers control: calls, Depends, callbacks, interface -> impl,
  // and app factory -> included router modules -> route handlers.
  const PATH_KINDS = ['calls', 'depends', 'instantiates', 'references', 'implements', 'includes_router', 'routes'];

  const ELK_OPTIONS = {
    'elk.algorithm': 'layered',
    'elk.direction': 'RIGHT',
    'elk.edgeRouting': 'ORTHOGONAL',
    'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
    'elk.layered.nodePlacement.bk.fixedAlignment': 'BALANCED',
    'elk.layered.spacing.nodeNodeBetweenLayers': '84',
    'elk.layered.spacing.edgeNodeBetweenLayers': '22',
    'elk.layered.spacing.edgeEdgeBetweenLayers': '12',
    'elk.spacing.nodeNode': '22',
    'elk.spacing.edgeEdge': '10',
    'elk.spacing.edgeNode': '16',
    'elk.spacing.componentComponent': '48',
    'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
    'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
    'elk.layered.cycleBreaking.strategy': 'MODEL_ORDER',
    'elk.layered.thoroughness': '7',
    'elk.separateConnectedComponents': 'true',
    'elk.padding': '[top=40,left=40,bottom=40,right=40]',
  };

  // =====================================================================================
  // Pure helpers
  // =====================================================================================
  function nodeTitle(n) {
    if (!n) return '?';
    if (n.kind === 'module') return shortModule(n.module || n.name);
    const q = (n.qualname || n.name || '').replace(/\.<locals>\./g, ' › ');
    return q;
  }

  /** Keep the distinguishing tail of a dotted module path: gis_api.modules.maps.api.router -> …maps.api.router */
  function shortModule(m, max) {
    max = max || 24;
    if (!m || m.length <= max) return m || '';
    const segs = m.split('.');
    let out = segs.pop();
    while (segs.length && out.length + segs[segs.length - 1].length + 1 <= max - 1) out = segs.pop() + '.' + out;
    return '…' + out;
  }

  function shortPath(p, max) {
    max = max || 40;
    if (!p) return '';
    if (p.length <= max) return p;
    return '…' + p.slice(p.length - max + 1);
  }

  /** Visual category used for colours: entrypoint type wins over kind. */
  function visualKind(n) {
    if (!n) return 'function';
    const ep = n.entrypoint;
    if (ep) {
      if (ep.type === 'http') return 'http';
      if (ep.type === 'websocket') return 'websocket';
      if (ep.type === 'celery_task') return 'celery_task';
      return 'entry';
    }
    return n.kind || 'function';
  }

  function kindLabel(n) {
    const ep = n.entrypoint;
    if (ep) {
      return ({ http: 'route', websocket: 'websocket', celery_task: 'task', main: '__main__', app_factory: 'app factory', cli: 'cli', hook: 'hook' })[ep.type] || ep.type;
    }
    return n.kind;
  }

  /** Build lookup tables for a graph. */
  function indexGraph(graph) {
    const byId = new Map();
    const out = new Map();
    const inc = new Map();
    const byModule = new Map();
    for (const n of graph.nodes || []) {
      byId.set(n.id, n);
      out.set(n.id, []);
      inc.set(n.id, []);
      let arr = byModule.get(n.module);
      if (!arr) byModule.set(n.module, (arr = []));
      arr.push(n);
    }
    for (const e of graph.edges || []) {
      if (!byId.has(e.source) || !byId.has(e.target)) continue;
      out.get(e.source).push(e);
      inc.get(e.target).push(e);
    }
    return { graph, byId, out, inc, byModule };
  }

  /** Children of a class (methods) or module (top-level defs) for the details panel. */
  function membersOf(idx, n) {
    const list = idx.byModule.get(n.module) || [];
    if (n.kind === 'class') {
      const pre = n.qualname + '.';
      return list.filter(m => m.id !== n.id && m.qualname.startsWith(pre) && m.qualname.indexOf('.', pre.length) < 0);
    }
    if (n.kind === 'module') {
      return list.filter(m => m.kind !== 'module' && m.qualname.indexOf('.') < 0);
    }
    return [];
  }

  /** Module-level aggregated graph ("collapse by module"). */
  function buildModuleGraph(graph) {
    const idx = graph._idx || indexGraph(graph);
    const nodes = [];
    const modNodeId = new Map();
    for (const [mod, list] of idx.byModule) {
      let mn = list.find(n => n.kind === 'module');
      const count = list.filter(n => n.kind !== 'module').length;
      const eps = list.filter(n => n.entrypoint).length;
      const id = mn ? mn.id : mod + ':' + mod;
      const file = mn ? mn.file : (list[0] && list[0].file) || '';
      nodes.push({
        id, kind: 'module', name: mod.split('.').pop(), qualname: mod, module: mod, file, line: 1, end_line: 1,
        signature: '', doc: mn ? mn.doc : '', decorators: [], is_async: false, class_name: null,
        package: mod.split('.').slice(0, -1).join('.'), loc: 0, entrypoint: null, _defs: count, _eps: eps,
      });
      modNodeId.set(mod, id);
    }
    const agg = new Map();
    for (const e of graph.edges || []) {
      const s = idx.byId.get(e.source), t = idx.byId.get(e.target);
      if (!s || !t || s.module === t.module) continue;
      const key = s.module + '\u0000' + t.module;
      let a = agg.get(key);
      if (!a) {
        a = { source: modNodeId.get(s.module), target: modNodeId.get(t.module), kind: e.kind === 'includes_router' ? 'includes_router' : 'calls', file: e.file, line: e.line, count: 0 };
        agg.set(key, a);
      }
      a.count++;
    }
    nodes.sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
    return { root: graph.root, project: graph.project, version: graph.version, nodes, edges: Array.from(agg.values()), external_calls: {}, errors: [], stats: {} };
  }

  function edgeAllowed(idx, e, opts) {
    if (opts.kinds && !opts.kinds.has(e.kind)) return false;
    if (opts.hideModules) {
      const s = idx.byId.get(e.source), t = idx.byId.get(e.target);
      if ((s && s.kind === 'module' && s.id !== opts.focus) || (t && t.kind === 'module' && t.id !== opts.focus)) return false;
    }
    return true;
  }

  function sortEdgesByLine(a, b) { return (a.line || 0) - (b.line || 0) || (a.target < b.target ? -1 : 1); }

  /**
   * Select a focused subgraph: callers (left) up to inDepth, callees (right) up to outDepth.
   * opts: { focus, inDepth, outDepth, kinds:Set, hideModules, expanded:Set, unlimited:Set, unlimitedIn:Set,
   *         extraOut:Map id->n, extraIn:Map id->n (paged "+N" expansion), maxNodes, perNodeOut, perNodeIn, showExternals }
   * Stubs get stub.capped=true when the global node cap was reached, i.e. clicking them cannot show more.
   * Returns { nodes:[{id, ref, depth, canExpand, stub, ext}], edges:[{id, source, target, kind, file, line, count}], focus, truncated }
   */
  function buildSubgraph(idx, opts) {
    const focus = opts.focus;
    if (!idx.byId.has(focus)) return { nodes: [], edges: [], focus, truncated: false };
    const maxNodes = opts.maxNodes || MAX_NODES;
    const perOut = opts.perNodeOut || PER_NODE_OUT;
    const perIn = opts.perNodeIn || PER_NODE_IN;
    const expanded = opts.expanded || new Set();
    const unlimited = opts.unlimited || new Set();
    const unlimitedIn = opts.unlimitedIn || new Set();
    const extraOut = opts.extraOut || new Map();
    const extraIn = opts.extraIn || new Map();
    const inFree = id => unlimitedIn.has(id) || extraIn.has(id);  // explicitly expanded: bypass the caller budget
    const inDepth = Math.max(0, opts.inDepth | 0);
    const outDepth = Math.max(0, opts.outDepth | 0);

    const included = new Map(); // id -> info
    const order = [];
    let truncated = false;
    const add = (id, depth) => {
      if (included.has(id)) return true;
      if (included.size >= maxNodes) { truncated = true; return false; }
      const info = { id, ref: idx.byId.get(id), depth, processedOut: false, processedIn: false, hiddenOut: 0, hiddenIn: 0 };
      included.set(id, info);
      order.push(info);
      return true;
    };
    add(focus, 0);

    // Callers first, with a budget so callees always get room.
    const inBudget = Math.floor(maxNodes * 0.4);
    let q = [[focus, 0]];
    while (q.length && inDepth > 0) {
      const nq = [];
      for (const [id, d] of q) {
        if (d >= inDepth) continue;
        const info = included.get(id);
        if (included.size >= inBudget + 1 && !inFree(id)) { truncated = true; continue; }
        info.processedIn = true;
        const seen = new Set();
        const edges = idx.inc.get(id).filter(e => edgeAllowed(idx, e, opts)).sort((a, b) => a.source < b.source ? -1 : 1);
        const cap = unlimitedIn.has(id) ? Infinity : perIn + (extraIn.get(id) || 0);
        for (const e of edges) {
          if (seen.has(e.source) || e.source === id) continue;
          seen.add(e.source);
          if (seen.size > cap || (!included.has(e.source) && included.size >= inBudget + 1 && !inFree(id))) { info.hiddenIn++; continue; }
          const existed = included.has(e.source);
          if (!add(e.source, -(d + 1))) { info.hiddenIn++; continue; }
          if (!existed) nq.push([e.source, d + 1]);
        }
      }
      q = nq;
    }

    // Callees (BFS). Expanded nodes get one extra level regardless of depth.
    q = [[focus, 0]];
    while (q.length) {
      const nq = [];
      for (const [id, d] of q) {
        const info = included.get(id);
        if (!(d < outDepth || expanded.has(id))) continue;
        if (info.processedOut) continue;
        if (included.size >= maxNodes) { truncated = true; continue; }  // leave as expandable frontier
        info.processedOut = true;
        const seen = new Set();
        const edges = idx.out.get(id).filter(e => edgeAllowed(idx, e, opts)).sort(sortEdgesByLine);
        const cap = unlimited.has(id) ? Infinity : perOut + (extraOut.get(id) || 0);
        for (const e of edges) {
          if (seen.has(e.target) || e.target === id) continue;
          seen.add(e.target);
          if (seen.size > cap) { info.hiddenOut++; continue; }
          const existed = included.has(e.target);
          if (!add(e.target, d + 1)) { info.hiddenOut++; continue; }
          if (!existed) nq.push([e.target, d + 1]);
          else {
            const other = included.get(e.target);
            if (!other.processedOut && expanded.has(e.target)) nq.push([e.target, d + 1]);
          }
        }
      }
      q = nq;
    }

    // Edges among included nodes (dedup by source|target|kind).
    const edgeMap = new Map();
    for (const info of order) {
      for (const e of idx.out.get(info.id)) {
        if (!included.has(e.target) || e.target === e.source) continue;
        if (!edgeAllowed(idx, e, opts)) continue;
        const key = e.source + '\u0000' + e.target + '\u0000' + e.kind;
        const ex = edgeMap.get(key);
        if (ex) { ex.count += (e.count || 1); continue; }
        edgeMap.set(key, { id: 'e' + edgeMap.size, source: e.source, target: e.target, kind: e.kind, file: e.file, line: e.line, count: e.count || 1 });
      }
    }
    const edges = Array.from(edgeMap.values());

    // Expandability for frontier nodes, stubs for capped nodes.
    const nodes = [];
    const stubs = [];
    const full = included.size >= maxNodes;
    for (const info of order) {
      let canExpand = 0;
      if (!info.processedOut) {
        const tg = new Set();
        for (const e of idx.out.get(info.id)) {
          if (e.target !== info.id && !included.has(e.target) && edgeAllowed(idx, e, opts)) tg.add(e.target);
        }
        canExpand = tg.size;
      }
      nodes.push({ id: info.id, ref: info.ref, depth: info.depth, canExpand, expanded: expanded.has(info.id) && info.depth !== 0 });
      if (info.hiddenOut > 0) {
        const sid = 'more:' + info.id;
        stubs.push({ id: sid, ref: null, depth: info.depth + 1, stub: { parent: info.id, count: info.hiddenOut, dir: 'out', capped: full } });
        edges.push({ id: 'e' + edges.length + 's', source: info.id, target: sid, kind: 'more', count: 1 });
      }
      if (info.hiddenIn > 0) {
        const sid = 'morein:' + info.id;
        stubs.push({ id: sid, ref: null, depth: info.depth - 1, stub: { parent: info.id, count: info.hiddenIn, dir: 'in', capped: full } });
        edges.push({ id: 'e' + edges.length + 's', source: sid, target: info.id, kind: 'more', count: 1 });
      }
    }
    // Unresolved/external calls of the focus as grey leaves.
    if (opts.showExternals && idx.graph.external_calls) {
      const ext = (idx.graph.external_calls[focus] || []).slice(0, 12);
      for (const name of ext) {
        const xid = 'ext:' + name;
        stubs.push({ id: xid, ref: null, depth: 1, ext: name });
        edges.push({ id: 'e' + edges.length + 'x', source: focus, target: xid, kind: 'ext', count: 1 });
      }
    }
    // Model order = callers (deepest first) → focus → callees by depth; stubs after real nodes of the same depth.
    // ELK's MODEL_ORDER cycle breaking then reverses only true back-edges (recursion), keeping callers on the left.
    const all = nodes.concat(stubs).map((n, i) => [n, i]);
    all.sort((a, b) => a[0].depth - b[0].depth || (a[0].ref ? 0 : 1) - (b[0].ref ? 0 : 1) || a[1] - b[1]);
    return { nodes: all.map(x => x[0]), edges, focus, truncated };
  }

  /** BFS helper. Returns Map id -> distance. */
  function bfs(start, nextFn, limit) {
    const dist = new Map([[start, 0]]);
    let frontier = [start];
    while (frontier.length) {
      const nf = [];
      for (const id of frontier) {
        const d = dist.get(id);
        if (limit != null && d >= limit) continue;
        for (const m of nextFn(id)) {
          if (!dist.has(m)) { dist.set(m, d + 1); nf.push(m); }
        }
      }
      frontier = nf;
    }
    return dist;
  }

  /**
   * Find up to k short paths from a to b over call-like edges. Paths of length <= shortest+slack.
   * Returns array of arrays of node ids (sorted by length).
   */
  function findPaths(idx, a, b, opts) {
    opts = opts || {};
    const kinds = opts.kinds || new Set(PATH_KINDS);
    const k = opts.k || 6;
    const slack = opts.slack == null ? 1 : opts.slack;
    const maxLen = opts.maxLen || 14;
    if (!idx.byId.has(a) || !idx.byId.has(b)) return [];
    if (a === b) return [[a]];
    const fwd = id => idx.out.get(id).filter(e => kinds.has(e.kind)).map(e => e.target);
    const bwd = id => idx.inc.get(id).filter(e => kinds.has(e.kind)).map(e => e.source);
    const dA = bfs(a, fwd, maxLen);
    if (!dA.has(b)) return [];
    const L = dA.get(b);
    const dB = bfs(b, bwd, L + slack);
    const limit = L + slack;
    const paths = [];
    const path = [a];
    const onPath = new Set([a]);
    let steps = 0;
    const succ = new Map();
    const getSucc = id => {
      let s = succ.get(id);
      if (!s) {
        s = Array.from(new Set(fwd(id))).filter(m => dB.has(m));
        s.sort((x, y) => dB.get(x) - dB.get(y) || (x < y ? -1 : 1));
        succ.set(id, s);
      }
      return s;
    };
    (function dfs(cur) {
      if (paths.length >= k * 4 || ++steps > 50000) return;
      if (cur === b) { paths.push(path.slice()); return; }
      for (const m of getSucc(cur)) {
        if (onPath.has(m)) continue;
        if (path.length - 1 + 1 + dB.get(m) > limit) continue;
        path.push(m); onPath.add(m);
        dfs(m);
        path.pop(); onPath.delete(m);
        if (paths.length >= k * 4) return;
      }
    })(a);
    paths.sort((x, y) => x.length - y.length);
    return paths.slice(0, k);
  }

  /** Subgraph made of the union of paths. */
  function subgraphFromPaths(idx, paths, opts) {
    const ids = [];
    const seen = new Set();
    for (const p of paths) for (const id of p) if (!seen.has(id)) { seen.add(id); ids.push(id); }
    const pairSet = new Set();
    for (const p of paths) for (let i = 0; i + 1 < p.length; i++) pairSet.add(p[i] + '\u0000' + p[i + 1]);
    const kinds = (opts && opts.kinds) || new Set(PATH_KINDS);
    const edges = [];
    const done = new Set();
    for (const id of ids) {
      for (const e of idx.out.get(id)) {
        const key = e.source + '\u0000' + e.target;
        if (!pairSet.has(key) || !kinds.has(e.kind)) continue;
        const dk = key + '\u0000' + e.kind;
        if (done.has(dk)) continue;
        done.add(dk);
        edges.push({ id: 'p' + edges.length, source: e.source, target: e.target, kind: e.kind, file: e.file, line: e.line, count: 1 });
      }
    }
    return { nodes: ids.map(id => ({ id, ref: idx.byId.get(id), depth: 0, canExpand: 0 })), edges, focus: paths.length ? paths[0][0] : null, truncated: false };
  }

  // ---------- search ----------
  function fuzzyScore(q, text) {
    if (!text) return 0;
    const t = text.toLowerCase();
    if (t === q) return 1000;
    if (t.startsWith(q)) return 800 - Math.min(100, t.length - q.length);
    const i = t.indexOf(q);
    if (i >= 0) {
      // word-boundary bonus
      const prev = t[i - 1];
      const wb = prev === '_' || prev === '.' || prev === '/' || prev === ' ' || prev === ':';
      return (wb ? 650 : 520) - Math.min(120, i) - Math.min(60, t.length / 4);
    }
    // subsequence
    let ti = 0, score = 0, streak = 0, first = -1;
    for (let qi = 0; qi < q.length; qi++) {
      const c = q[qi];
      if (c === ' ') continue;
      const found = t.indexOf(c, ti);
      if (found < 0) return 0;
      if (first < 0) first = found;
      streak = found === ti ? streak + 1 : 0;
      score += 8 + streak * 6;
      const prev = t[found - 1];
      if (found === 0 || prev === '_' || prev === '.' || prev === '/') score += 10;
      ti = found + 1;
    }
    return Math.max(1, Math.min(400, score) - Math.min(80, first) - Math.min(60, t.length / 5));
  }

  function searchNodes(idx, query, limit) {
    limit = limit || 40;
    const q = (query || '').trim().toLowerCase();
    if (!q) return [];
    const res = [];
    for (const n of idx.graph.nodes) {
      let s = fuzzyScore(q, n.name);
      s = Math.max(s, fuzzyScore(q, n.qualname) * 0.95);
      if (n.entrypoint) {
        s = Math.max(s, fuzzyScore(q, n.entrypoint.label || '') * 1.0, fuzzyScore(q, n.entrypoint.path || '') * 0.98);
      }
      if (s < 300) s = Math.max(s, fuzzyScore(q, n.module) * 0.6, fuzzyScore(q, n.file) * 0.5);
      if (s <= 0) continue;
      if (n.is_test) s -= 30;
      if (n.entrypoint) s += 25;
      if (n.kind === 'module') s -= 10;
      if (n.qualname && n.qualname.indexOf('<locals>') >= 0) s -= 20;
      res.push({ node: n, score: s });
    }
    res.sort((a, b) => b.score - a.score || (a.node.id < b.node.id ? -1 : 1));
    return res.slice(0, limit).map(r => r.node);
  }

  // ---------- ELK ----------
  function nodeSize(sn) {
    if (sn.stub) return [STUB_W, STUB_H];
    if (sn.ext != null) return [EXT_W, EXT_H];
    return [NODE_W, NODE_H];
  }

  function buildElkGraph(sub) {
    const children = sub.nodes.map(sn => {
      const [w, h] = nodeSize(sn);
      return {
        id: sn.id, width: w, height: h,
        layoutOptions: { 'elk.portConstraints': 'FIXED_POS' },
        ports: [
          { id: sn.id + '#in', x: -1, y: h / 2 - 1, width: 1, height: 2, layoutOptions: { 'elk.port.side': 'WEST' } },
          { id: sn.id + '#out', x: w, y: h / 2 - 1, width: 1, height: 2, layoutOptions: { 'elk.port.side': 'EAST' } },
        ],
      };
    });
    const ids = new Set(sub.nodes.map(n => n.id));
    const edges = sub.edges.filter(e => ids.has(e.source) && ids.has(e.target) && e.source !== e.target).map(e => ({
      id: e.id, sources: [e.source + '#out'], targets: [e.target + '#in'],
    }));
    return { id: 'root', layoutOptions: Object.assign({}, ELK_OPTIONS), children, edges };
  }

  /** Extract a flat {nodes: Map id->{x,y,w,h}, edges: Map id->[points]} from ELK's result. */
  function readLayout(res) {
    const nodes = new Map();
    let W = 0, H = 0;
    for (const c of res.children || []) {
      nodes.set(c.id, { x: c.x, y: c.y, w: c.width, h: c.height });
      W = Math.max(W, c.x + c.width); H = Math.max(H, c.y + c.height);
    }
    const edges = new Map();
    for (const e of res.edges || []) {
      const pts = [];
      for (const s of e.sections || []) {
        if (!pts.length) pts.push(s.startPoint);
        for (const bp of s.bendPoints || []) pts.push(bp);
        pts.push(s.endPoint);
      }
      edges.set(e.id, pts);
    }
    return { nodes, edges, width: Math.max(W + 40, res.width || 0), height: Math.max(H + 40, res.height || 0) };
  }

  function roundedPath(pts, r) {
    r = r == null ? 7 : r;
    if (!pts || pts.length < 2) return '';
    let d = 'M' + pts[0].x.toFixed(1) + ',' + pts[0].y.toFixed(1);
    for (let i = 1; i < pts.length - 1; i++) {
      const p0 = pts[i - 1], p1 = pts[i], p2 = pts[i + 1];
      const d1 = Math.hypot(p1.x - p0.x, p1.y - p0.y), d2 = Math.hypot(p2.x - p1.x, p2.y - p1.y);
      const rr = Math.min(r, d1 / 2, d2 / 2);
      if (rr < 0.5) { d += ' L' + p1.x.toFixed(1) + ',' + p1.y.toFixed(1); continue; }
      const ax = p1.x - (p1.x - p0.x) / d1 * rr, ay = p1.y - (p1.y - p0.y) / d1 * rr;
      const bx = p1.x + (p2.x - p1.x) / d2 * rr, by = p1.y + (p2.y - p1.y) / d2 * rr;
      d += ' L' + ax.toFixed(1) + ',' + ay.toFixed(1) + ' Q' + p1.x.toFixed(1) + ',' + p1.y.toFixed(1) + ' ' + bx.toFixed(1) + ',' + by.toFixed(1);
    }
    const last = pts[pts.length - 1];
    d += ' L' + last.x.toFixed(1) + ',' + last.y.toFixed(1);
    return d;
  }

  // ---------- URL hash state ----------
  function parseHash(hash) {
    const h = (hash || '').replace(/^#/, '');
    const out = {};
    for (const part of h.split('&')) {
      if (!part) continue;
      const i = part.indexOf('=');
      const k = i < 0 ? part : part.slice(0, i);
      const v = i < 0 ? '' : part.slice(i + 1);
      try { out[decodeURIComponent(k)] = decodeURIComponent(v); } catch (_) { out[k] = v; }
    }
    const st = {};
    if (out.from && out.to) { st.mode = 'path'; st.from = out.from; st.to = out.to; }
    else if (out.focus) { st.mode = 'focus'; st.focus = out.focus; }
    if (out.in != null && out.in !== '') st.in = clamp(parseInt(out.in, 10), 0, 3);
    if (out.out != null && out.out !== '') st.out = clamp(parseInt(out.out, 10), 1, 6);
    if (out.view === 'modules') st.view = 'modules';
    return st;
  }

  function stateToHash(st) {
    const parts = [];
    const enc = encodeURIComponent;
    if (st.mode === 'path') { parts.push('from=' + enc(st.from).replace(/%3A/g, ':'), 'to=' + enc(st.to).replace(/%3A/g, ':')); }
    else if (st.focus) parts.push('focus=' + enc(st.focus).replace(/%3A/g, ':'));
    if (st.mode !== 'path') { parts.push('in=' + st.in, 'out=' + st.out); }
    if (st.view === 'modules') parts.push('view=modules');
    return '#' + parts.join('&');
  }

  function clamp(v, lo, hi) { v = isNaN(v) ? lo : v; return Math.max(lo, Math.min(hi, v)); }

  // ---------- python highlighting ----------
  const PY_KW = 'def|class|return|if|elif|else|for|while|in|not|and|or|is|None|True|False|import|from|as|with|try|except|finally|raise|async|await|yield|lambda|pass|break|continue|global|nonlocal|del|assert|match|case';
  const PY_RE = new RegExp(
    '(#[^\\n]*)' +                                                     // 1 comment
    '|([rRbBuUfF]{0,2}(?:"""[\\s\\S]*?"""|\'\'\'[\\s\\S]*?\'\'\'|"(?:\\\\.|[^"\\\\\\n])*"|\'(?:\\\\.|[^\'\\\\\\n])*\'))' + // 2 string
    '|(^[ \\t]*@[\\w.]+)' +                                            // 3 decorator
    '|\\b(def|class)(\\s+)([A-Za-z_]\\w*)' +                           // 4,5,6 def name
    '|\\b(' + PY_KW + ')\\b' +                                         // 7 keyword
    '|\\b(self|cls)\\b' +                                              // 8
    '|\\b(\\d+(?:\\.\\d+)?)\\b', 'gm');                                // 9 number

  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  }

  function highlightPython(src) {
    let out = '', last = 0, m;
    PY_RE.lastIndex = 0;
    while ((m = PY_RE.exec(src))) {
      out += escapeHtml(src.slice(last, m.index));
      if (m[1]) out += '<span class="tk-com">' + escapeHtml(m[1]) + '</span>';
      else if (m[2]) out += '<span class="tk-str">' + escapeHtml(m[2]) + '</span>';
      else if (m[3]) out += '<span class="tk-dec">' + escapeHtml(m[3]) + '</span>';
      else if (m[4]) out += '<span class="tk-kw">' + m[4] + '</span>' + m[5] + '<span class="tk-def">' + escapeHtml(m[6]) + '</span>';
      else if (m[7]) out += '<span class="tk-kw">' + m[7] + '</span>';
      else if (m[8]) out += '<span class="tk-self">' + m[8] + '</span>';
      else if (m[9]) out += '<span class="tk-num">' + m[9] + '</span>';
      else out += escapeHtml(m[0]);
      last = PY_RE.lastIndex;
      if (m[0].length === 0) PY_RE.lastIndex++;
    }
    return out + escapeHtml(src.slice(last));
  }

  const CM = {
    NODE_W, NODE_H, MAX_NODES, ELK_OPTIONS, ALL_KINDS, PATH_KINDS, PAGE,
    nodeTitle, shortModule, shortPath, visualKind, kindLabel, indexGraph, membersOf, buildModuleGraph, buildSubgraph,
    findPaths, subgraphFromPaths, fuzzyScore, searchNodes, buildElkGraph, readLayout, roundedPath,
    parseHash, stateToHash, highlightPython, escapeHtml,
  };

  if (typeof module !== 'undefined' && module.exports) { module.exports = CM; return; }
  if (typeof window === 'undefined') return;
  window.CodeMap = CM;

  // =====================================================================================
  // Browser app
  // =====================================================================================
  const $ = sel => document.querySelector(sel);
  const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
  const esc = escapeHtml;

  const S = {
    graph: null, idx: null, modGraph: null, modIdx: null,
    version: 0,
    state: { mode: 'none', focus: null, in: 1, out: 2, view: 'fn' },
    history: [], hpos: -1,
    selected: null,
    expanded: new Set(), unlimited: new Set(), unlimitedIn: new Set(),
    extraIn: new Map(), extraOut: new Map(),   // paged "+N" stub expansion per node
    lost: null,          // {ids, files, msg}: focused/path nodes missing after a live rebuild; last view kept greyed
    expectHpos: null,    // history index we asked the browser to go to (history.go)
    _userMoved: false,   // user panned/zoomed since the last automatic recenter
    detailsFor: null, detailsShowAll: new Set(),
    kinds: new Set(ALL_KINDS), hideModules: false, showExternals: false,
    cam: { x: 40, y: 40, k: 1 },
    layout: null, sub: null,
    renderToken: 0,
    nodeEls: new Map(),
    pendingFlash: null,
    sourceCache: new Map(),
  };
  const elk = new ELK();

  // ---------- data loading ----------
  async function fetchGraph() {
    const r = await fetch('api/graph', { cache: 'no-store' });
    if (!r.ok) throw new Error('GET /api/graph -> ' + r.status);
    return r.json();
  }

  async function fetchStatus() {
    const r = await fetch('api/status', { cache: 'no-store' });
    if (!r.ok) throw new Error('GET /api/status -> ' + r.status);
    return r.json();
  }

  // ---------- analysis progress ----------
  // The PyCharm plugin answers api/status with a "state" (idle | waiting_for_index | building | ready | error) and
  // serves an empty graph until the first build is done. The standalone Python server has no "state" (it only
  // serves the graph once it is built), so none of this triggers there.
  const PROGRESS_STATES = new Set(['idle', 'waiting_for_index', 'building', 'error']);
  let progressOn = false;

  function needsProgress(s) {
    return !!s && typeof s.state === 'string' && PROGRESS_STATES.has(s.state) && !(S.graph && S.graph.nodes.length);
  }

  function showProgress(s) {
    const box = $('#progress'), bar = $('#progress-bar');
    const total = s.total || 0, done = Math.min(s.done || 0, total);
    let title, msg = '';
    if (s.state === 'waiting_for_index') {
      title = 'Waiting for PyCharm to finish indexing…';
      msg = 'The call graph is built from PyCharm\'s code model, which needs the index.';
    } else if (s.state === 'building') {
      title = total ? 'Analysing ' + done + ' / ' + total + ' files…' : 'Analysing…';
    } else if (s.state === 'error') {
      title = 'Analysis failed';
    } else {
      title = 'Starting analysis…';
    }
    if (s.message && (s.state === 'error' || s.state === 'building' || s.state === 'waiting_for_index')) msg = s.message;
    box.classList.toggle('err', s.state === 'error');
    $('#progress-title').textContent = title;
    $('#progress-msg').textContent = msg;
    $('#progress-msg').classList.toggle('hidden', !msg);
    const determinate = s.state === 'building' && total > 0;
    bar.parentElement.classList.toggle('indeterminate', !determinate && s.state !== 'error');
    bar.parentElement.classList.toggle('hidden', s.state === 'error');
    bar.style.width = determinate ? Math.round(100 * done / total) + '%' : '';
    $('#progress-hint').textContent = s.state === 'error'
      ? 'Fix the problem (details in idea.log) or change Settings | Tools | Code Map; this page retries by itself.'
      : 'The map opens by itself when the analysis is done.';
    document.title = 'codemap · ' + title;
    box.classList.remove('hidden');
    progressOn = true;
  }

  function hideProgress() {
    if (!progressOn) return;
    progressOn = false;
    $('#progress').classList.add('hidden');
    if (S.graph) document.title = 'codemap · ' + (S.graph.project || '');
  }

  /** Polls api/status every second (2 s after an error) until the first graph is ready, then loads it. */
  async function watchProgress(s) {
    while (needsProgress(s)) {
      showProgress(s);
      await new Promise(res => setTimeout(res, s.state === 'error' ? 2000 : 1000));
      if (S.graph && S.graph.nodes.length) break;  // an SSE "graph" event loaded it meanwhile
      try { s = await fetchStatus(); } catch (_) { s = { state: 'idle', message: 'IDE not reachable, retrying…' }; }
      if (s.state === 'ready' || (s.version && s.version !== S.version)) {
        await reloadGraph();
        if (!(S.graph && S.graph.nodes.length) && s.state === 'ready') break;  // really no Python code
      }
    }
    hideProgress();
  }

  function setGraph(g) {
    S.graph = g;
    S.idx = indexGraph(g);
    g._idx = S.idx;
    S.modGraph = null; S.modIdx = null;
    S.version = g.version || 0;
    S.sourceCache.clear();
    $('#project').textContent = g.project || '';
    document.title = 'codemap · ' + (g.project || '');
    const st = g.stats || {};
    $('#sidebar-foot').textContent = (st.files != null ? st.files + ' files · ' : '') + g.nodes.length + ' nodes · ' + g.edges.length + ' edges' +
      ((g.errors && g.errors.length) ? ' · ' + g.errors.length + ' parse errors' : '');
    $('#sidebar-foot').title = (g.errors || []).map(e => e.file + ': ' + e.message).join('\n');
    buildEntrypoints();
    buildModuleTree();
    const noPy = !g.nodes.length;
    $('#empty-nopy').classList.toggle('hidden', !noPy);
    $('#empty-help').classList.toggle('hidden', noPy);
    $('#empty-root').textContent = g.root || g.project || 'this project';
    if (g.nodes.length) hideProgress();
    setLive(true);
  }

  function activeIdx() {
    if (S.state.view === 'modules') {
      if (!S.modIdx) { S.modGraph = buildModuleGraph(S.graph); S.modIdx = indexGraph(S.modGraph); }
      return S.modIdx;
    }
    return S.idx;
  }

  function moduleIdOf(id) {
    const n = S.idx.byId.get(id);
    if (!n) return id;
    const list = S.idx.byModule.get(n.module) || [];
    const mn = list.find(m => m.kind === 'module');
    return mn ? mn.id : n.module + ':' + n.module;
  }

  // ---------- navigation ----------
  function sameState(a, b) {
    return a && b && a.mode === b.mode && a.focus === b.focus && a.from === b.from && a.to === b.to && a.view === b.view && a.in === b.in && a.out === b.out;
  }

  function idxFor(view) {
    if (view === 'modules') {
      if (!S.modIdx) { S.modGraph = buildModuleGraph(S.graph); S.modIdx = indexGraph(S.modGraph); }
      return S.modIdx;
    }
    return S.idx;
  }
  function isLost(id) { return !!(S.lost && S.lost.ids.includes(id)); }
  function pathKinds() { return new Set(PATH_KINDS.filter(k => S.kinds.has(k))); }

  /** Why a navigation target cannot be shown (HTML message), or null if it can. */
  function invalidReason(st) {
    if (st.mode === 'focus' && st.focus) {
      if (!idxFor(st.view).byId.has(st.focus) && !isLost(st.focus)) return 'Node not found: <code>' + esc(st.focus) + '</code>';
    } else if (st.mode === 'path') {
      const a = S.idx.byId.get(st.from), b = S.idx.byId.get(st.to);
      if (!a || !b) return (isLost(st.from) || isLost(st.to)) ? null : 'Path endpoint not found: <code>' + esc(!a ? st.from : st.to) + '</code>';
      if (!findPaths(S.idx, st.from, st.to, { kinds: pathKinds() }).length) {
        return 'No call path found from <code>' + esc(nodeTitle(a)) + '</code> to <code>' + esc(nodeTitle(b)) + '</code> over the enabled edge kinds';
      }
    }
    return null;
  }

  function showBanner(html) {
    const b = $('#banner');
    if (!b) return;
    b.innerHTML = '<span class="btxt">' + html + '</span><button class="icon-btn" title="Dismiss">×</button>';
    b.querySelector('button').onclick = () => b.classList.add('hidden');
    b.classList.remove('hidden');
  }
  function hideBanner() { const b = $('#banner'); if (b) b.classList.add('hidden'); }
  function setStale(on, msg) {
    $('#world').classList.toggle('stale', !!on);
    if (on) showBanner(msg); else hideBanner();
  }

  /** Returns false (and changes nothing: no history entry, previous view kept) if the target can't be shown. */
  function navigate(partial, opts) {
    opts = opts || {};
    const st = Object.assign({}, S.state, partial);
    if (st.mode === 'focus') { delete st.from; delete st.to; }
    if (st.mode === 'path') { st.focus = st.from; }
    const bad = invalidReason(st);
    if (bad) {
      const hadView = !!S.layout;
      toast(bad, 'warn', 6000);
      showBanner(bad + (hadView ? ' — still showing the previous view.' : ''));
      const cur = stateToHash(S.state);
      if (S.state.mode !== 'none' && location.hash !== cur) history.replaceState(null, '', cur);
      if (!hadView) $('#empty').classList.remove('hidden');
      return false;
    }
    hideBanner();
    const focusChanged = st.focus !== S.state.focus || st.mode !== S.state.mode || st.view !== S.state.view || st.from !== S.state.from || st.to !== S.state.to;
    if (focusChanged) { S.expanded.clear(); S.unlimited.clear(); S.unlimitedIn.clear(); S.extraIn.clear(); S.extraOut.clear(); }
    S.state = st;
    let pushed = false;
    if (opts.push !== false && !sameState(S.history[S.hpos], st)) {
      S.history = S.history.slice(0, S.hpos + 1);
      S.history.push(Object.assign({}, st));
      if (S.history.length > 200) S.history.shift();
      S.hpos = S.history.length - 1;
      pushed = true;
    } else if (opts.push === false && S.history[S.hpos]) {
      S.history[S.hpos] = Object.assign({}, st);  // e.g. depth stepper: keep the entry in step with the URL
    }
    // One browser history entry per in-app history entry, so mouse/browser Back & Forward work.
    const h = stateToHash(st);
    if (location.hash !== h) {
      // a typed/pasted hash already created its own browser entry: just normalise it
      if (pushed && !opts.replaceUrl && !sameHash(location.hash, h)) history.pushState(null, '', h);
      else history.replaceState(null, '', h);
    }
    updateChrome();
    if (focusChanged && st.focus && st.mode === 'focus') select(st.view === 'modules' ? st.focus : st.focus, true);
    render({ recenter: focusChanged || opts.recenter });
  }

  function focusNode(id, extra) {
    const p = Object.assign({ mode: 'focus', focus: id }, extra || {});
    navigate(p);
  }

  /** In-app Back/Forward/breadcrumbs go through the browser history so both stay in sync (see hashchange). */
  function goHistory(delta) {
    const np = S.hpos + delta;
    if (!delta || np < 0 || np >= S.history.length) return;
    S.expectHpos = np;
    history.go(delta);
    // Fallback if the browser history is out of step and no hashchange arrives.
    setTimeout(() => {
      if (S.expectHpos === np && S.hpos !== np) { S.expectHpos = null; gotoHistoryEntry(np); }
    }, 400);
  }

  function gotoHistoryEntry(j) {
    const prev = S.hpos;
    S.hpos = j;
    if (navigate(Object.assign({}, S.history[j]), { push: false, recenter: true }) === false) { S.hpos = prev; updateChrome(); }
  }

  function sameHash(a, b) {
    const n = x => { try { return decodeURIComponent(x || '').replace(/^#/, ''); } catch (_) { return x; } };
    return n(a) === n(b);
  }

  function onHashChange() {
    if (!S.idx || progressOn) return;  // during the first build the hash is applied once the graph is there
    const h = location.hash;
    const want = S.expectHpos;
    S.expectHpos = null;
    if (sameHash(h, stateToHash(S.state))) return;
    for (const c of [want, S.hpos - 1, S.hpos + 1]) {
      if (c != null && c >= 0 && c < S.history.length && sameHash(stateToHash(S.history[c]), h)) { gotoHistoryEntry(c); return; }
    }
    applyHash();  // typed/pasted hash, or plugin "Show in Code Map"
  }

  function applyHash(opts) {
    const st = parseHash(location.hash);
    if (!st.mode) return false;
    const p = { mode: st.mode, view: st.view || 'fn' };
    if (st.mode === 'path') { p.from = st.from; p.to = st.to; }
    else p.focus = st.focus;
    if (st.in != null) p.in = st.in;
    if (st.out != null) p.out = st.out;
    // In module view, a function id from the plugin maps to its module node.
    if (p.view === 'modules' && p.focus && S.idx.byId.has(p.focus)) p.focus = moduleIdOf(p.focus);
    return navigate(p, opts) !== false;
  }

  function updateChrome() {
    const st = S.state;
    $('#in-depth').textContent = st.in;
    $('#out-depth').textContent = st.out;
    $('#btn-back').disabled = S.hpos <= 0;
    $('#btn-fwd').disabled = S.hpos >= S.history.length - 1;
    $('#btn-modview').classList.toggle('on', st.view === 'modules');
    $('#empty').classList.toggle('hidden', !!(st.focus || st.mode === 'path'));
    // breadcrumb: last few distinct history entries up to hpos
    const bc = $('#breadcrumb');
    bc.innerHTML = '';
    const crumbs = [];
    for (let i = S.hpos; i >= 0 && crumbs.length < 5; i--) {
      const h = S.history[i];
      const key = h.mode + '|' + h.focus + '|' + h.to + '|' + h.view;
      if (crumbs.some(c => c.key === key)) continue;
      crumbs.unshift({ key, i, h });
    }
    crumbs.forEach((c, j) => {
      if (j) bc.appendChild(el('span', 'sep', '›'));
      const idx = c.h.view === 'modules' ? (S.modIdx || S.idx) : S.idx;
      const nameOf = id => { const n = idx.byId.get(id) || S.idx.byId.get(id); return n ? (n.kind === 'module' ? n.module : n.name) : String(id).split(':').pop(); };
      const label = c.h.mode === 'path' ? nameOf(c.h.from) + ' ⇢ ' + nameOf(c.h.to) : nameOf(c.h.focus) + (c.h.view === 'modules' ? ' (modules)' : '');
      const b = el('button', 'crumb' + (c.i === S.hpos ? ' cur' : ''));
      b.textContent = label;
      b.title = c.h.mode === 'path' ? c.h.from + ' → ' + c.h.to : c.h.focus;
      b.onclick = () => goHistory(c.i - S.hpos);
      bc.appendChild(b);
    });
    document.querySelectorAll('.item.cur').forEach(x => x.classList.remove('cur'));
    if (st.focus) document.querySelectorAll('.item[data-id="' + CSS.escape(st.focus) + '"]').forEach(x => x.classList.add('cur'));
  }

  // ---------- rendering ----------
  function currentSubgraph() {
    const st = S.state;
    const idx = activeIdx();
    if (st.mode === 'path') {
      const kinds = pathKinds();
      const paths = findPaths(S.idx, st.from, st.to, { kinds });
      const sub = subgraphFromPaths(S.idx, paths, { kinds });
      sub.paths = paths;
      sub.pathEnds = [st.from, st.to];
      return sub;
    }
    if (!st.focus) return null;
    return buildSubgraph(idx, {
      focus: st.focus, inDepth: st.in, outDepth: st.out, kinds: S.kinds, hideModules: S.hideModules && st.view !== 'modules',
      expanded: S.expanded, unlimited: S.unlimited, unlimitedIn: S.unlimitedIn, extraIn: S.extraIn, extraOut: S.extraOut,
      showExternals: S.showExternals && st.view !== 'modules',
    });
  }

  async function render(opts) {
    opts = opts || {};
    const token = ++S.renderToken;
    if (!S.idx) return;
    const sub = currentSubgraph();
    if (!sub) { clearCanvas(); return; }
    if (!sub.nodes.length) {
      let msg;
      if (S.lost) msg = S.lost.msg;
      else if (S.state.mode === 'path') {
        const a = S.idx.byId.get(S.state.from), b = S.idx.byId.get(S.state.to);
        msg = 'No call path found from <code>' + esc(a ? nodeTitle(a) : S.state.from) + '</code> to <code>' + esc(b ? nodeTitle(b) : S.state.to) + '</code> over the enabled edge kinds';
      } else msg = 'Node not found: <code>' + esc(S.state.focus) + '</code>';
      if (S.layout) { setStale(true, msg + (S.lost ? '' : ' — showing the previous view.')); return; }  // keep last good view, greyed
      if (!opts.fromReload) toast(msg, 'warn');
      showBanner(msg);
      clearCanvas();
      $('#empty').classList.remove('hidden');
      return;
    }
    const busyTimer = setTimeout(() => $('#busy').classList.remove('hidden'), 150);
    let res;
    try {
      res = await elk.layout(buildElkGraph(sub));
    } catch (err) {
      console.error(err);
      toast('Layout failed: ' + esc(err && err.message || err), 'err');
      return;
    } finally {
      clearTimeout(busyTimer);
      $('#busy').classList.add('hidden');
    }
    if (token !== S.renderToken) return;
    const lay = readLayout(res);
    S.sub = sub; S.layout = lay;
    if (S.lost) S.lost = null;
    setStale(false);
    draw(sub, lay);
    const real = sub.nodes.filter(n => n.ref).length;
    $('#stats').textContent = (sub.paths ? sub.paths.length + ' path' + (sub.paths.length === 1 ? '' : 's') + ' · ' : '') + real + ' nodes · ' + sub.edges.filter(e => e.kind !== 'more' && e.kind !== 'ext').length + ' edges' + (sub.truncated ? ' · capped at ' + MAX_NODES : '');
    if (opts.recenter) { S._userMoved = false; recenter(true); }
    else if (opts.center) centerOn(opts.center, S.cam.k, true);
    if (S.pendingFlash) { flashFiles(S.pendingFlash); S.pendingFlash = null; }
  }

  function clearCanvas() {
    S.sub = null; S.layout = null;
    for (const e of S.nodeEls.values()) e.remove();
    S.nodeEls.clear();
    $('#edge-layer').innerHTML = '';
    $('#stats').textContent = '';
  }

  function nodeClass(sn) {
    if (sn.stub) return 'node stub' + (sn.stub.capped ? ' capped' : '');
    if (sn.ext != null) return 'node ext';
    const n = sn.ref;
    let c = 'node k-' + visualKind(n);
    const st = S.state;
    if (st.mode === 'path' ? (S.sub && S.sub.pathEnds && S.sub.pathEnds.includes(sn.id)) : sn.id === st.focus) c += ' focus';
    if (sn.id === S.selected) c += ' sel';
    return c;
  }

  function nodeInner(sn) {
    if (sn.stub) {
      const what = sn.stub.dir === 'in' ? ' callers' : ' more';
      const tip = sn.stub.capped
        ? 'Node cap (' + MAX_NODES + ') reached: click to list them in the details panel'
        : 'Click to show ' + Math.min(PAGE, sn.stub.count) + ' more';
      return '<span title="' + esc(tip) + '">+' + sn.stub.count + what + (sn.stub.capped ? ' ⋯' : '') + '</span>';
    }
    if (sn.ext != null) return '<span class="title">' + esc(sn.ext) + '</span>';
    const n = sn.ref;
    const badges = [];
    if (n.entrypoint && n.entrypoint.label) badges.push('<span class="badge ep" title="' + esc(n.entrypoint.label) + '">' + esc(n.entrypoint.label) + '</span>');
    if (n.is_async) badges.push('<span class="badge async">async</span>');
    if (n._defs != null) badges.push('<span class="badge">' + n._defs + ' defs</span>');
    if (n._eps) badges.push('<span class="badge ep">' + n._eps + ' entry</span>');
    const title = nodeTitle(n);
    const sub = n.file + (n.kind === 'module' ? '' : ':' + n.line);
    let h = '<div class="nh"><span class="kind">' + esc(kindLabel(n)) + '</span><span class="badges">' + badges.join('') + '</span></div>' +
      '<div class="nb"><div class="title" title="' + esc(n.qualname || title) + '">' + esc(title) + '</div>' +
      '<div class="path" title="' + esc(sub) + '">' + esc(shortPath(sub, 38)) + '</div></div>' +
      '<span class="port in"></span><span class="port out"></span>';
    if (sn.canExpand > 0) h += '<button class="expand" title="Expand ' + sn.canExpand + ' callee(s) in place">▸ ' + sn.canExpand + '</button>';
    else if (sn.expanded) h += '<button class="expand collapse" title="Collapse">◂</button>';
    return h;
  }

  function draw(sub, lay) {
    const nodesEl = $('#nodes');
    highlightNode(null, false);
    $('#tooltip').classList.add('hidden');
    for (const e of S.nodeEls.values()) e._hov = false;
    const keep = new Set();
    for (const sn of sub.nodes) {
      const p = lay.nodes.get(sn.id);
      if (!p) continue;
      keep.add(sn.id);
      let e = S.nodeEls.get(sn.id);
      const isNew = !e;
      if (isNew) {
        e = el('div');
        e.dataset.id = sn.id;
        S.nodeEls.set(sn.id, e);
        nodesEl.appendChild(e);
      }
      e.className = nodeClass(sn) + (isNew ? ' entering' : '');
      e.innerHTML = nodeInner(sn);
      e._sn = sn;
      if (isNew) {
        e.style.transition = 'none';
        e.style.transform = 'translate(' + p.x + 'px,' + p.y + 'px)';
        void e.offsetWidth;
        e.style.transition = '';
      } else {
        e.style.transform = 'translate(' + p.x + 'px,' + p.y + 'px)';
      }
    }
    for (const [id, e] of S.nodeEls) if (!keep.has(id)) { e.remove(); S.nodeEls.delete(id); }

    // edges
    const svg = $('#edges');
    svg.setAttribute('width', lay.width);
    svg.setAttribute('height', lay.height);
    const layer = $('#edge-layer');
    let html = '';
    for (const e of sub.edges) {
      const pts = lay.edges.get(e.id);
      if (!pts || pts.length < 2) continue;
      const d = roundedPath(pts);
      html += '<g data-e="' + esc(e.id) + '" data-s="' + esc(e.source) + '" data-t="' + esc(e.target) + '">' +
        '<path class="edge k-' + esc(e.kind) + '" d="' + d + '" marker-end="url(#arrow)"/>' +
        (e.kind === 'more' || e.kind === 'ext' ? '' : '<path class="edge-hit" d="' + d + '"/>') + '</g>';
    }
    layer.innerHTML = html;
    layer.classList.remove('fadein'); void layer.getBoundingClientRect(); layer.classList.add('fadein');
    S.edgeById = new Map(sub.edges.map(e => [e.id, e]));
  }

  function highlightNode(id, on) {
    const world = $('#world');
    world.classList.toggle('hovering', on);
    document.querySelectorAll('.node.hl-n').forEach(x => x.classList.remove('hl-n'));
    document.querySelectorAll('#edge-layer .edge.hl, #edge-layer .edge.hl-out').forEach(x => { x.classList.remove('hl', 'hl-out'); x.setAttribute('marker-end', 'url(#arrow)'); });
    if (!on) return;
    const nel = S.nodeEls.get(id);
    if (nel) nel.classList.add('hl-n');
    document.querySelectorAll('#edge-layer g').forEach(g => {
      const s = g.dataset.s, t = g.dataset.t;
      if (s !== id && t !== id) return;
      const p = g.firstChild;
      const outgoing = s === id;
      p.classList.add(outgoing ? 'hl-out' : 'hl');
      p.setAttribute('marker-end', outgoing ? 'url(#arrow-out)' : 'url(#arrow-hl)');
      const other = S.nodeEls.get(outgoing ? t : s);
      if (other) other.classList.add('hl-n');
    });
  }

  // ---------- camera ----------
  function applyCam() {
    const c = S.cam;
    $('#world').style.transform = 'translate(' + c.x + 'px,' + c.y + 'px) scale(' + c.k + ')';
    $('#z-val').textContent = Math.round(c.k * 100) + '%';
  }

  let camAnim = null;
  function animateCam(target, ms) {
    ms = ms == null ? 380 : ms;
    if (camAnim) cancelAnimationFrame(camAnim);
    const from = Object.assign({}, S.cam);
    const t0 = performance.now();
    const ease = t => 1 - Math.pow(1 - t, 3);
    const step = now => {
      const t = Math.min(1, (now - t0) / ms), e = ease(t);
      S.cam.x = from.x + (target.x - from.x) * e;
      S.cam.y = from.y + (target.y - from.y) * e;
      S.cam.k = from.k + (target.k - from.k) * e;
      applyCam();
      camAnim = t < 1 ? requestAnimationFrame(step) : null;
    };
    camAnim = requestAnimationFrame(step);
  }

  function fitCam(animate) {
    if (!S.layout) return;
    const r = $('#canvas').getBoundingClientRect();
    const lay = S.layout;
    const k = clamp(Math.min((r.width - 40) / lay.width, (r.height - 70) / lay.height), 0.15, 1.15);
    const target = { k, x: (r.width - lay.width * k) / 2, y: Math.max(10, (r.height - 40 - lay.height * k) / 2) };
    animate ? animateCam(target) : (Object.assign(S.cam, target), applyCam());
  }

  function centerOn(id, k, animate) {
    if (!S.layout) return;
    const p = S.layout.nodes.get(id);
    if (!p) return fitCam(animate);
    const r = $('#canvas').getBoundingClientRect();
    k = k || S.cam.k;
    const target = { k, x: r.width / 2 - (p.x + p.w / 2) * k, y: (r.height - 30) / 2 - (p.y + p.h / 2) * k };
    animate ? animateCam(target) : (Object.assign(S.cam, target), applyCam());
  }

  /** After a re-focus: fit if everything is readable, else center the focus at a readable zoom. */
  function recenter(animate) {
    if (!S.layout) return;
    const r = $('#canvas').getBoundingClientRect();
    const lay = S.layout;
    const kfit = Math.min((r.width - 40) / lay.width, (r.height - 70) / lay.height);
    if (kfit >= 0.62 || S.state.mode === 'path') fitCam(animate);
    else centerOn(S.state.focus, 0.85, animate);
  }

  function zoomAt(factor, cx, cy) {
    const c = S.cam;
    const k = clamp(c.k * factor, 0.08, 2.5);
    const f = k / c.k;
    c.x = cx - (cx - c.x) * f;
    c.y = cy - (cy - c.y) * f;
    c.k = k;
    applyCam();
  }

  function setupCanvas() {
    const canvas = $('#canvas');
    let drag = null;
    canvas.addEventListener('pointerdown', ev => {
      if (ev.button !== 0 && ev.button !== 1) return;
      if (ev.target.closest('.node, .canvas-tools, .legend, .empty-card')) return;
      if (camAnim) { cancelAnimationFrame(camAnim); camAnim = null; }
      drag = { x: ev.clientX, y: ev.clientY, cx: S.cam.x, cy: S.cam.y, moved: false };
      canvas.setPointerCapture(ev.pointerId);
      canvas.classList.add('panning');
    });
    canvas.addEventListener('pointermove', ev => {
      if (!drag) return;
      const dx = ev.clientX - drag.x, dy = ev.clientY - drag.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) { drag.moved = true; S._userMoved = true; }
      S.cam.x = drag.cx + dx; S.cam.y = drag.cy + dy;
      applyCam();
    });
    const end = () => {
      if (drag && !drag.moved && S.selected) { /* click on empty background: keep selection */ }
      drag = null; canvas.classList.remove('panning');
    };
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);
    canvas.addEventListener('wheel', ev => {
      ev.preventDefault();
      if (camAnim) { cancelAnimationFrame(camAnim); camAnim = null; }
      S._userMoved = true;
      const r = canvas.getBoundingClientRect();
      // Trackpad two-finger scroll (no ctrl, small deltas incl. horizontal) pans; mouse wheel / pinch zooms.
      const isPinch = ev.ctrlKey;
      const isTrackpadPan = !isPinch && ev.deltaMode === 0 && Math.abs(ev.deltaX) > 0;
      if (isTrackpadPan || (ev.shiftKey && !isPinch)) {
        S.cam.x -= ev.deltaX || (ev.shiftKey ? ev.deltaY : 0);
        S.cam.y -= ev.shiftKey ? 0 : ev.deltaY;
        applyCam();
        return;
      }
      const dy = ev.deltaMode === 1 ? ev.deltaY * 33 : ev.deltaY;
      zoomAt(Math.exp(-dy * (isPinch ? 0.01 : 0.0016)), ev.clientX - r.left, ev.clientY - r.top);
    }, { passive: false });

    // node interactions (delegated)
    const nodesEl = $('#nodes');
    nodesEl.addEventListener('click', ev => {
      const ne = ev.target.closest('.node');
      if (!ne) return;
      const sn = ne._sn;
      if (!sn) return;
      if (ev.target.closest('.expand')) {
        ev.stopPropagation();
        if (sn.expanded && !sn.canExpand) S.expanded.delete(sn.id); else S.expanded.add(sn.id);
        render();
        return;
      }
      if (sn.stub) {
        const { parent, dir, capped } = sn.stub;
        if (capped) {
          // Expanding would exceed the node cap: show the complete list in the details panel instead.
          select(parent);
          const sec = document.querySelector('#details-body [data-sec="' + (dir === 'in' ? 'Called by' : 'Calls') + '"]');
          if (sec) sec.scrollIntoView({ block: 'start' });
          toast('The view is at the ' + MAX_NODES + '-node cap; all ' + (dir === 'in' ? 'callers' : 'callees') + ' are listed in the details panel', null, 4500);
          return;
        }
        const m = dir === 'in' ? S.extraIn : S.extraOut;
        m.set(parent, (m.get(parent) || 0) + PAGE);
        render({ center: parent });
        return;
      }
      if (sn.ext != null) { toast('Unresolved call <code>' + esc(sn.ext) + '</code> (external or dynamic; not in the graph)'); return; }
      if (ev.ctrlKey || ev.metaKey) { openInEditor(sn.ref.file, sn.ref.line); return; }
      select(sn.id);
    });
    nodesEl.addEventListener('dblclick', ev => {
      const ne = ev.target.closest('.node');
      if (!ne || !ne._sn || !ne._sn.ref || ev.target.closest('.expand')) return;
      if (ev.ctrlKey || ev.metaKey) return;
      focusNode(ne._sn.id);
    });
    nodesEl.addEventListener('mouseover', ev => {
      const ne = ev.target.closest('.node');
      if (!ne || !ne._sn || ne._hov) return;
      ne._hov = true;
      highlightNode(ne._sn.id, true);
    });
    nodesEl.addEventListener('mouseout', ev => {
      const ne = ev.target.closest('.node');
      if (!ne || (ev.relatedTarget && ne.contains(ev.relatedTarget))) return;
      ne._hov = false;
      highlightNode(null, false);
    });

    // edge tooltips
    const layer = $('#edge-layer');
    const tip = $('#tooltip');
    layer.addEventListener('mousemove', ev => {
      const g = ev.target.closest('g[data-e]');
      if (!g) return;
      const e = S.edgeById && S.edgeById.get(g.dataset.e);
      if (!e) return;
      const s = (S.state.view === 'modules' ? activeIdx() : S.idx).byId.get(e.source);
      const t = (S.state.view === 'modules' ? activeIdx() : S.idx).byId.get(e.target);
      tip.innerHTML = '<b>' + esc(e.kind) + '</b>' + (e.count > 1 ? ' ×' + e.count : '') + '<br>' + esc(s ? nodeTitle(s) : e.source) + ' → ' + esc(t ? nodeTitle(t) : e.target) +
        (e.file ? '<br><span class="muted">' + esc(e.file) + ':' + e.line + '</span>' : '');
      tip.style.left = (ev.clientX + 14) + 'px';
      tip.style.top = (ev.clientY + 12) + 'px';
      tip.classList.remove('hidden');
      g.firstChild.classList.add('hl');
      g.firstChild.setAttribute('marker-end', 'url(#arrow-hl)');
    });
    layer.addEventListener('mouseout', ev => {
      tip.classList.add('hidden');
      const g = ev.target.closest('g[data-e]');
      if (g && !$('#world').classList.contains('hovering')) { g.firstChild.classList.remove('hl'); g.firstChild.setAttribute('marker-end', 'url(#arrow)'); }
    });
    layer.addEventListener('click', ev => {
      const g = ev.target.closest('g[data-e]');
      const e = g && S.edgeById && S.edgeById.get(g.dataset.e);
      if (e && e.file && (ev.ctrlKey || ev.metaKey)) openInEditor(e.file, e.line);
    });

    $('#z-in').onclick = () => { S._userMoved = true; const r = canvas.getBoundingClientRect(); zoomAt(1.2, r.width / 2, r.height / 2); };
    $('#z-out').onclick = () => { S._userMoved = true; const r = canvas.getBoundingClientRect(); zoomAt(1 / 1.2, r.width / 2, r.height / 2); };
    $('#z-fit').onclick = () => { S._userMoved = false; fitCam(true); };
    $('#z-focus').onclick = () => { S._userMoved = true; centerOn(S.selected || S.state.focus, Math.max(S.cam.k, 0.85), true); };
    window.addEventListener('resize', () => { if (S.layout && !S._userMoved) recenter(false); });
  }

  // ---------- selection & details ----------
  function select(id, quiet) {
    S.selected = id;
    for (const [nid, e] of S.nodeEls) e.classList.toggle('sel', nid === id);
    showDetails(id);
    if (!quiet) { /* nothing else */ }
  }

  function nodeItem(n, extraRight, edgeKind) {
    const it = el('div', 'item');
    it.dataset.id = n.id;
    const vk = visualKind(n);
    it.innerHTML = '<span class="kdot" style="background:var(--k-' + (vk === 'celery_task' ? 'task' : vk) + ')"></span>' +
      '<span class="ilabel" title="' + esc(n.id) + '">' + esc(nodeTitle(n)) + '</span>' +
      (edgeKind && edgeKind !== 'calls' ? '<span class="ek">' + esc(edgeKind) + '</span>' : '') +
      (extraRight ? '<span class="isub">' + esc(extraRight) + '</span>' : '');
    it.title = 'Click: focus · Ctrl+click: open in PyCharm\n' + n.file + ':' + n.line;
    it.onclick = ev => {
      if (ev.ctrlKey || ev.metaKey) { openInEditor(n.file, n.line); return; }
      if (S.state.view === 'modules' && n.kind !== 'module') navigate({ view: 'fn', mode: 'focus', focus: n.id });
      else focusNode(n.id);
    };
    return it;
  }

  /** opts.keepScroll: live-reload rebuild — keep the reader's place (scroll position, "Show all" lists). */
  function showDetails(id, opts) {
    opts = opts || {};
    const panel = $('#details');
    const body = $('#details-body');
    const idx = S.state.view === 'modules' ? activeIdx() : S.idx;
    const n = idx.byId.get(id) || S.idx.byId.get(id);
    if (!n) { panel.classList.add('collapsed'); return; }
    panel.classList.remove('collapsed');
    const keep = opts.keepScroll && S.detailsFor === id ? body.scrollTop : null;
    if (S.detailsFor !== id || !opts.keepScroll) { S.detailsFor = id; S.detailsShowAll = new Set(); }
    body.innerHTML = '';
    const vk = visualKind(n);
    const kc = 'var(--k-' + (vk === 'celery_task' ? 'task' : vk) + ')';
    const head = el('div');
    head.innerHTML = '<span class="d-kind" style="--kc:' + kc + '">' + esc(kindLabel(n)) + (n.entrypoint ? ' · ' + esc(n.kind) : '') + (n.is_async ? ' · async' : '') + '</span>' +
      '<div class="d-title">' + esc(nodeTitle(n)) + '</div>' +
      '<div class="d-qual">' + esc(n.module) + '</div>';
    body.appendChild(head);
    const loc = el('a', 'd-link');
    loc.textContent = n.file + ':' + n.line + (n.end_line && n.end_line !== n.line ? '–' + n.end_line : '');
    loc.title = 'Open in PyCharm';
    loc.onclick = () => openInEditor(n.file, n.line);
    body.appendChild(loc);

    const acts = el('div', 'd-actions');
    const mk = (label, fn, cls) => { const b = el('button', 'tbtn' + (cls ? ' ' + cls : '')); b.textContent = label; b.onclick = fn; acts.appendChild(b); };
    mk('Open in PyCharm', () => openInEditor(n.file, n.line), 'primary');
    if (S.state.focus !== n.id || S.state.mode !== 'focus') mk('Focus', () => focusNode(n.id));
    if (S.state.view !== 'modules') {
      if (n.entrypoint) mk('Flow from here', () => focusNode(n.id, { in: 0 }));
      mk('Path from here…', () => openPathPop(n.id, null));
      mk('Path to here…', () => openPathPop(null, n.id));
      if (n.kind !== 'module') mk('Module view', () => navigate({ view: 'modules', mode: 'focus', focus: moduleIdOf(n.id) }));
    } else {
      mk('Function view', () => {
        const list = S.idx.byModule.get(n.module) || [];
        const mn = list.find(m => m.kind === 'module');
        navigate({ view: 'fn', mode: 'focus', focus: mn ? mn.id : (list[0] && list[0].id) });
      });
    }
    mk('Copy id', () => { copyText(n.id); toast('Copied <code>' + esc(n.id) + '</code>'); });
    body.appendChild(acts);

    const sec = (title, count) => {
      const s = el('div', 'd-sec');
      s.dataset.sec = title;
      s.appendChild(el('h4', null, esc(title) + (count != null ? ' <span class="count">' + count + '</span>' : '')));
      body.appendChild(s);
      return s;
    };
    if (n.entrypoint) {
      const s = sec('Entrypoint');
      const ep = n.entrypoint;
      const box = el('div', 'ep-box');
      box.innerHTML = (ep.method ? '<span class="method m-' + methodClass(ep.method) + '" style="font:600 10px var(--mono);padding:1px 5px;border-radius:4px">' + esc(ep.method) + '</span>' : '<span class="muted">' + esc(ep.type) + '</span>') +
        '<span>' + esc(ep.path || ep.label || '') + '</span>';
      s.appendChild(box);
    }
    if (n.signature) { const s = sec('Signature'); s.appendChild(el('div', 'code', esc(n.name + n.signature))); }
    if (n.doc) { const s = sec('Docstring'); s.appendChild(el('div', 'doc', esc(n.doc))); }
    if (n.decorators && n.decorators.length) {
      const s = sec('Decorators');
      const c = el('div', 'chips');
      for (const d of n.decorators) c.appendChild(el('span', 'chip', '@' + esc(d)));
      s.appendChild(c);
    }

    const listSec = (title, edges, pick, sideKey) => {
      const uniq = new Map();
      for (const e of edges) { const o = pick(e); if (o && !uniq.has(o.id)) uniq.set(o.id, { n: o, e }); }
      if (!uniq.size) return;
      const s = sec(title, uniq.size);
      const l = el('div', 'list');
      let shown = 0;
      const all = Array.from(uniq.values());
      const renderSome = lim => {
        for (; shown < Math.min(lim, all.length); shown++) {
          const { n: o, e } = all[shown];
          l.appendChild(nodeItem(o, e.line ? ':' + e.line : o.file.split('/').pop(), e.kind));
        }
      };
      const showAll = S.detailsShowAll.has(title);
      renderSome(showAll ? all.length : 40);
      s.appendChild(l);
      if (all.length > 40 && !showAll) {
        const more = el('button', 'tbtn', 'Show all ' + all.length);
        more.style.marginTop = '4px';
        more.onclick = () => { S.detailsShowAll.add(title); renderSome(all.length); more.remove(); };
        s.appendChild(more);
      }
    };
    if (S.state.view === 'modules') {
      const mods = idx;
      listSec('Imported by / used by', mods.inc.get(n.id) || [], e => mods.byId.get(e.source));
      listSec('Uses', mods.out.get(n.id) || [], e => mods.byId.get(e.target));
      const defs = (S.idx.byModule.get(n.module) || []).filter(m => m.kind !== 'module' && m.qualname.indexOf('.') < 0);
      if (defs.length) {
        const s = sec('Definitions', defs.length);
        const l = el('div', 'list');
        defs.slice(0, 200).forEach(m => l.appendChild(nodeItem(m, ':' + m.line)));
        s.appendChild(l);
      }
    } else {
      listSec('Called by', S.idx.inc.get(n.id) || [], e => S.idx.byId.get(e.source));
      listSec('Calls', (S.idx.out.get(n.id) || []).slice().sort(sortEdgesByLine), e => S.idx.byId.get(e.target));
      const mem = membersOf(S.idx, n);
      if (mem.length) {
        const s = sec(n.kind === 'class' ? 'Methods' : 'Definitions', mem.length);
        const l = el('div', 'list');
        mem.slice(0, 300).forEach(m => l.appendChild(nodeItem(m, ':' + m.line)));
        s.appendChild(l);
      }
      const ext = (S.graph.external_calls || {})[n.id];
      if (ext && ext.length) {
        const s = sec('Unresolved / external calls', ext.length);
        const c = el('div', 'chips');
        for (const x of ext) c.appendChild(el('span', 'chip', esc(x)));
        s.appendChild(c);
      }
    }
    if (n.file && n.kind !== 'module' || (n.kind === 'module' && S.state.view !== 'modules')) {
      const s = sec('Source');
      const holder = el('div', null, '<span class="muted">loading…</span>');
      s.appendChild(holder);
      const p = loadSource(n, holder);
      if (keep != null) p.then(() => { if (S.detailsFor === id) body.scrollTop = keep; });
    }
    body.scrollTop = keep != null ? keep : 0;
  }

  function methodClass(m) { m = (m || '').toUpperCase(); return ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(m) ? m : 'OTHER'; }

  async function loadSource(n, holder) {
    const start = n.line || 1;
    let end = n.end_line || start;
    if (n.kind === 'module') end = start + 60;
    end = Math.min(end, start + 160);
    const key = n.file + ':' + start + ':' + end + ':' + S.version;
    try {
      let data = S.sourceCache.get(key);
      if (!data) {
        const r = await fetch('api/source?file=' + encodeURIComponent(n.file) + '&start=' + start + '&end=' + end, { cache: 'no-store' });
        if (!r.ok) throw new Error(r.status + ' ' + r.statusText);
        data = await r.json();
        S.sourceCache.set(key, data);
      }
      const lines = data.lines || [];
      const s0 = data.start || start;
      const gut = lines.map((_, i) => s0 + i).join('\n');
      const truncated = (n.end_line || start) > end;
      holder.innerHTML = '<div class="src"><div class="gut">' + gut + '</div><pre>' + highlightPython(lines.join('\n')) + '</pre></div>' +
        (truncated ? '<div class="muted" style="margin-top:4px;font-size:11px">… truncated (' + (n.end_line - end) + ' more lines)</div>' : '');
      holder.querySelector('.src').ondblclick = () => openInEditor(n.file, n.line);
    } catch (err) {
      holder.innerHTML = '<span class="muted">source unavailable (' + esc(err.message) + ')</span>';
    }
  }

  // ---------- open in editor ----------
  async function openInEditor(file, line) {
    line = line || 1;
    // JCEF bridge, if the PyCharm plugin injected one.
    try {
      if (window.codemapIde && typeof window.codemapIde.open === 'function') {
        const root = S.graph && S.graph.root;
        const abs = root && !file.startsWith('/') ? root.replace(/\/+$/, '') + '/' + file : file;
        window.codemapIde.open(abs, line);
        return;
      }
      if (window.codemapBridge && typeof window.codemapBridge.open === 'function') { window.codemapBridge.open(file, line); return; }
      if (typeof window.codemapOpen === 'function') { window.codemapOpen(file, line); return; }
    } catch (_) { /* fall through */ }
    try {
      const r = await fetch('api/open', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ file, line }) });
      const j = await r.json().catch(() => ({}));
      if (j.ok) toast('Opened <code>' + esc(file) + ':' + line + '</code>' + (j.via ? ' <span class="muted">via ' + esc(j.via) + '</span>' : ''));
      else toast('Could not open in PyCharm' + (j.error ? ' (' + esc(j.error) + ')' : '') + ' — <code>' + esc(file) + ':' + line + '</code>', 'warn', 6000);
    } catch (err) {
      toast('Could not reach codemap server — <code>' + esc(file) + ':' + line + '</code>', 'err', 6000);
    }
  }

  function copyText(t) {
    if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(t).catch(() => {});
    else { const ta = el('textarea'); ta.value = t; document.body.appendChild(ta); ta.select(); try { document.execCommand('copy'); } catch (_) {} ta.remove(); }
  }

  // ---------- toast ----------
  function toast(html, kind, ms) {
    const t = el('div', 'toast' + (kind ? ' ' + kind : ''), html);
    $('#toasts').appendChild(t);
    setTimeout(() => { t.style.transition = 'opacity .3s'; t.style.opacity = '0'; setTimeout(() => t.remove(), 320); }, ms || 3200);
  }

  // ---------- sidebar: search ----------
  function setupSearchBox(input, resultsEl, onPick, opts) {
    opts = opts || {};
    let items = [], active = 0;
    const renderRes = () => {
      const q = input.value;
      items = q.trim() ? searchNodes(S.idx, q, opts.limit || 40) : [];
      if (!q.trim()) { resultsEl.classList.add('hidden'); return; }
      resultsEl.innerHTML = '';
      if (!items.length) { resultsEl.appendChild(el('div', 'res-empty', 'No matches')); resultsEl.classList.remove('hidden'); return; }
      active = Math.min(active, items.length - 1);
      items.forEach((n, i) => {
        const r = el('div', 'res' + (i === active ? ' active' : ''));
        const vk = visualKind(n);
        r.innerHTML = '<span class="rk" style="background:var(--k-' + (vk === 'celery_task' ? 'task' : vk) + ')"></span>' +
          '<div class="rmain"><div class="rname">' + markMatch(nodeTitle(n), q) + '</div><div class="rsub">' + esc(n.file + ':' + n.line) + '</div></div>' +
          (n.entrypoint ? '<span class="rbadge">' + esc(n.entrypoint.label || n.entrypoint.type) + '</span>' : '<span class="rbadge" style="color:var(--muted);border-color:var(--border)">' + esc(n.kind) + '</span>');
        r.onmousedown = ev => { ev.preventDefault(); pick(i, ev); };
        resultsEl.appendChild(r);
      });
      resultsEl.classList.remove('hidden');
    };
    const pick = (i, ev) => {
      const n = items[i];
      if (!n) return;
      resultsEl.classList.add('hidden');
      onPick(n, ev || {});
    };
    input.addEventListener('input', () => { active = 0; renderRes(); });
    input.addEventListener('focus', () => { if (input.value.trim()) renderRes(); });
    input.addEventListener('blur', () => setTimeout(() => resultsEl.classList.add('hidden'), 120));
    input.addEventListener('keydown', ev => {
      if (ev.key === 'ArrowDown') { active = Math.min(items.length - 1, active + 1); ev.preventDefault(); hl(); }
      else if (ev.key === 'ArrowUp') { active = Math.max(0, active - 1); ev.preventDefault(); hl(); }
      else if (ev.key === 'Enter') { ev.preventDefault(); pick(active, ev); }
      else if (ev.key === 'Escape') { resultsEl.classList.add('hidden'); if (opts.blurOnEsc !== false) input.blur(); }
    });
    const hl = () => {
      [...resultsEl.children].forEach((c, i) => c.classList.toggle('active', i === active));
      const a = resultsEl.children[active];
      if (a && a.scrollIntoView) a.scrollIntoView({ block: 'nearest' });
    };
  }

  function markMatch(text, q) {
    q = q.trim().toLowerCase();
    const t = text.toLowerCase();
    const i = t.indexOf(q);
    if (q && i >= 0) return esc(text.slice(0, i)) + '<mark>' + esc(text.slice(i, i + q.length)) + '</mark>' + esc(text.slice(i + q.length));
    // subsequence marking
    let out = '', qi = 0;
    for (let j = 0; j < text.length; j++) {
      if (qi < q.length && t[j] === q[qi]) { out += '<mark>' + esc(text[j]) + '</mark>'; qi++; }
      else out += esc(text[j]);
    }
    return out;
  }

  // ---------- sidebar: entrypoints ----------
  const EP_GROUPS = [['http', 'HTTP routes'], ['websocket', 'WebSockets'], ['celery_task', 'Celery tasks'], ['main', '__main__ scripts'], ['app_factory', 'App factories'], ['cli', 'CLI commands'], ['hook', 'Framework hooks']];

  function buildEntrypoints() {
    const pane = $('#tab-entry');
    const filter = ($('#list-filter').value || '').trim().toLowerCase();
    const openState = new Set([...pane.querySelectorAll('details[open]')].map(d => d.dataset.key));
    const firstBuild = !pane.dataset.built;
    pane.dataset.built = '1';
    pane.innerHTML = '';
    const allEps = S.graph.nodes.filter(n => n.entrypoint);
    const eps = allEps.filter(n => !n.is_test);          // test-only routes/fixtures are grouped separately
    const testEps = allEps.filter(n => n.is_test);
    $('#ep-count').textContent = eps.length;
    const match = n => !filter || (n.entrypoint.label || '').toLowerCase().includes(filter) || n.name.toLowerCase().includes(filter) || n.file.toLowerCase().includes(filter);
    let any = false;
    for (const [type, title] of EP_GROUPS.concat([['__other', 'Other']])) {
      const list = eps.filter(n => (type === '__other' ? !EP_GROUPS.some(g => g[0] === n.entrypoint.type) : n.entrypoint.type === type) && match(n));
      if (!list.length) continue;
      any = true;
      const d = el('details', 'grp');
      d.dataset.key = type;
      d.open = firstBuild ? true : (filter ? true : openState.has(type));
      d.appendChild(el('summary', null, esc(title) + '<span class="count">' + list.length + '</span>'));
      const box = el('div', 'kids');
      if (type === 'http' || type === 'websocket') {
        const groups = new Map();
        for (const n of list) {
          const seg = routeGroup(n.entrypoint.path || n.entrypoint.label || '/');
          if (!groups.has(seg)) groups.set(seg, []);
          groups.get(seg).push(n);
        }
        const keys = [...groups.keys()].sort();
        for (const k of keys) {
          const g = groups.get(k).sort((a, b) => (a.entrypoint.path || '').localeCompare(b.entrypoint.path || '') || (a.entrypoint.method || '').localeCompare(b.entrypoint.method || ''));
          const sd = el('details', 'grp');
          sd.dataset.key = type + ':' + k;
          sd.open = !!filter || openState.has(sd.dataset.key);
          sd.appendChild(el('summary', null, esc(k) + '<span class="count">' + g.length + '</span>'));
          const items = el('div', 'items');
          for (const n of g) items.appendChild(epItem(n));
          sd.appendChild(items);
          box.appendChild(sd);
        }
      } else {
        list.sort((a, b) => (a.entrypoint.label || a.name).localeCompare(b.entrypoint.label || b.name));
        for (const n of list) box.appendChild(epItem(n));
      }
      d.appendChild(box);
      pane.appendChild(d);
    }
    const tl = testEps.filter(match).sort((a, b) => a.file.localeCompare(b.file) || (a.entrypoint.label || '').localeCompare(b.entrypoint.label || ''));
    if (tl.length) {
      any = true;
      const d = el('details', 'grp tests');
      d.dataset.key = '__tests';
      d.open = openState.has('__tests');
      d.appendChild(el('summary', null, 'Test code <span class="muted">(hidden by default)</span><span class="count">' + tl.length + '</span>'));
      const box = el('div', 'kids');
      for (const n of tl) box.appendChild(epItem(n));
      d.appendChild(box);
      pane.appendChild(d);
    }
    if (!any) pane.appendChild(el('div', 'res-empty', filter ? 'No entrypoints match' : 'No entrypoints detected'));
    buildEmptyPicks(eps);
    updateChrome();
  }

  /** Group key for a route: path up to the first "meaningful" segment (skips api, v1, {params}). */
  function routeGroup(p) {
    p = String(p).replace(/^[A-Z]+\s+/, '');
    const segs = p.split('/').filter(Boolean);
    const out = [];
    for (const sgm of segs) {
      out.push(sgm);
      if (!/^(api|v\d+|\{.*\})$/i.test(sgm)) break;
    }
    return '/' + out.join('/');
  }

  function epItem(n) {
    const it = el('div', 'item');
    it.dataset.id = n.id;
    const ep = n.entrypoint;
    if (ep.method) {
      it.innerHTML = '<span class="method m-' + methodClass(ep.method) + '">' + esc(ep.method) + '</span><span class="ilabel" title="' + esc(ep.label) + '">' + esc(ep.path || ep.label) + '</span><span class="isub">' + esc(n.name) + '</span>';
    } else {
      const vk = visualKind(n);
      it.innerHTML = '<span class="kdot" style="background:var(--k-' + (vk === 'celery_task' ? 'task' : vk) + ')"></span><span class="ilabel" title="' + esc(ep.label || n.name) + '">' + esc((ep.label || n.name).replace(/^python -m /, '')) + '</span><span class="isub">' + esc(n.file.split('/').pop()) + '</span>';
    }
    it.title = n.qualname + '\n' + n.file + ':' + n.line + '\nClick: flow from this entrypoint · Ctrl+click: open';
    it.onclick = ev => {
      if (ev.ctrlKey || ev.metaKey) return openInEditor(n.file, n.line);
      navigate({ view: 'fn', mode: 'focus', focus: n.id, in: 0, out: Math.max(S.state.out, 2) });
    };
    return it;
  }

  function buildEmptyPicks(eps) {
    const box = $('#empty-picks');
    box.innerHTML = '';
    const picks = eps.filter(n => n.entrypoint.type !== 'http').slice(0, 4).concat(eps.filter(n => n.entrypoint.type === 'http').slice(0, 6));
    for (const n of picks) box.appendChild(epItem(n));
  }

  // ---------- sidebar: module tree ----------
  function buildModuleTree() {
    const pane = $('#tab-tree');
    const filter = ($('#list-filter').value || '').trim().toLowerCase();
    const openState = new Set([...pane.querySelectorAll('details[open]')].map(d => d.dataset.key));
    pane.innerHTML = '';
    const mods = [...S.idx.byModule.keys()].sort();
    $('#mod-count').textContent = mods.length;
    const tree = { kids: new Map(), mod: null, path: '' };
    for (const m of mods) {
      if (filter && !m.toLowerCase().includes(filter) && !(S.idx.byModule.get(m) || []).some(n => n.name.toLowerCase().includes(filter))) continue;
      let cur = tree;
      const parts = m.split('.');
      parts.forEach((p, i) => {
        if (!cur.kids.has(p)) cur.kids.set(p, { kids: new Map(), mod: null, path: parts.slice(0, i + 1).join('.') });
        cur = cur.kids.get(p);
      });
      cur.mod = m;
    }
    const renderLevel = (node, container, depth) => {
      const keys = [...node.kids.keys()].sort((a, b) => {
        const A = node.kids.get(a), B = node.kids.get(b);
        return (B.kids.size > 0) - (A.kids.size > 0) || a.localeCompare(b);
      });
      for (const k of keys) {
        let child = node.kids.get(k);
        let label = k;
        // compress single-child package chains: a.b.c
        while (!child.mod && child.kids.size === 1) {
          const [nk, nv] = [...child.kids.entries()][0];
          label += '.' + nk; child = nv;
        }
        const d = el('details', 'grp' + (child.kids.size ? '' : ' tree-mod'));
        d.dataset.key = child.path;
        d.open = !!filter || openState.has(child.path) || (depth === 0 && keys.length === 1);
        const defs = child.mod ? (S.idx.byModule.get(child.mod) || []).filter(n => n.kind !== 'module').length : 0;
        d.appendChild(el('summary', null, (child.kids.size ? '📦 ' : '') + esc(label) + (child.mod && !child.kids.size ? '<span class="count">' + defs + '</span>' : '')));
        const box = el('div', 'kids');
        d.appendChild(box);
        let built = false;
        const build = () => {
          if (built) return; built = true;
          if (child.mod) {
            const list = S.idx.byModule.get(child.mod) || [];
            const mn = list.find(n => n.kind === 'module');
            if (mn) box.appendChild(nodeItem(mn, 'module'));
            const top = list.filter(n => n.kind !== 'module' && n.qualname.indexOf('<locals>') < 0).sort((a, b) => a.line - b.line);
            for (const n of top) {
              if (filter && !n.name.toLowerCase().includes(filter) && !child.mod.toLowerCase().includes(filter)) continue;
              const it = nodeItem(n, ':' + n.line);
              if (n.kind === 'method') it.style.paddingLeft = '20px';
              box.appendChild(it);
            }
          }
          if (child.kids.size) renderLevel(child, box, depth + 1);
        };
        if (d.open) build();
        d.addEventListener('toggle', () => { if (d.open) build(); });
        container.appendChild(d);
      }
    };
    renderLevel(tree, pane, 0);
    if (!pane.children.length) pane.appendChild(el('div', 'res-empty', 'No modules match'));
  }

  // ---------- path finder popover ----------
  const pathSel = { from: null, to: null };
  function openPathPop(from, to) {
    const pop = $('#path-pop');
    pop.classList.remove('hidden');
    if (from !== undefined && from !== null) pathSel.from = from;
    else if (!pathSel.from && to == null) pathSel.from = S.selected || S.state.focus;
    if (to !== undefined && to !== null) pathSel.to = to;
    if (from == null && to != null && !pathSel.from) pathSel.from = S.state.focus;
    const show = (inp, id) => { const n = id && S.idx.byId.get(id); inp.value = n ? nodeTitle(n) : ''; inp.title = id || ''; };
    show($('#path-from'), pathSel.from);
    show($('#path-to'), pathSel.to);
    (pathSel.from ? $('#path-to') : $('#path-from')).focus();
  }
  function setupPathPop() {
    setupSearchBox($('#path-from'), $('#path-from-res'), n => { pathSel.from = n.id; $('#path-from').value = nodeTitle(n); $('#path-to').focus(); }, { limit: 20, blurOnEsc: false });
    setupSearchBox($('#path-to'), $('#path-to-res'), n => { pathSel.to = n.id; $('#path-to').value = nodeTitle(n); $('#path-go').focus(); }, { limit: 20, blurOnEsc: false });
    $('#btn-path').onclick = () => $('#path-pop').classList.contains('hidden') ? openPathPop() : $('#path-pop').classList.add('hidden');
    $('#path-cancel').onclick = () => $('#path-pop').classList.add('hidden');
    $('#path-swap').onclick = () => { const t = pathSel.from; pathSel.from = pathSel.to; pathSel.to = t; openPathPop(pathSel.from, pathSel.to); };
    $('#path-go').onclick = () => {
      if (!pathSel.from || !pathSel.to) { toast('Pick both ends of the path', 'warn'); return; }
      $('#path-pop').classList.add('hidden');
      navigate({ mode: 'path', from: pathSel.from, to: pathSel.to, view: 'fn' });
    };
    $('#path-pop').addEventListener('keydown', ev => { if (ev.key === 'Escape') $('#path-pop').classList.add('hidden'); });
  }

  // ---------- live updates ----------
  function setLive(on, bump) {
    const l = $('#live');
    l.classList.toggle('on', !!on);
    l.classList.toggle('off', !on);
    l.querySelector('.txt').textContent = on ? (polling ? 'polling v' : 'live v') + S.version : 'offline';
    l.title = on ? (polling ? 'Live updates via polling (event stream unavailable)' : 'Live updates') : 'Not connected';
    if (bump) { l.classList.add('bump'); setTimeout(() => l.classList.remove('bump'), 1500); }
  }

  let reloading = false, reloadAgain = false;
  async function reloadGraph(changed) {
    if (reloading) { reloadAgain = true; return; }
    reloading = true;
    try {
      const g = await fetchGraph();
      if (g.version === S.version && S.graph) return;
      const st = S.state;
      const needIds = st.mode === 'path' ? [st.from, st.to] : (st.focus ? [st.focus] : []);
      let oldFiles = [];
      if (S.lost) oldFiles = S.lost.files;
      else if (S.idx) {
        const oldIdx = activeIdx();
        oldFiles = needIds.map(id => { const n = oldIdx.byId.get(id) || S.idx.byId.get(id); return n && n.file; }).filter(Boolean);
      }
      setGraph(g);
      setLive(true, true);
      const idx = st.mode === 'path' ? S.idx : activeIdx();
      const missing = needIds.filter(id => !idx.byId.has(id));
      if (missing.length) {
        // Keep focus, URL and the last layout (greyed); re-render automatically once the node is back
        // (typically a transient syntax error while typing, or rename-and-rename-back).
        const broken = (g.errors || []).filter(e => oldFiles.includes(e.file));
        const what = st.mode === 'path' ? 'Path endpoint' : 'Focused node';
        const msg = broken.length
          ? '<code>' + esc(broken[0].file) + '</code> does not parse (' + esc(String(broken[0].message).slice(0, 120)) + '): showing the last good view, which refreshes when the file is fixed.'
          : what + ' <code>' + esc(missing[0]) + '</code> no longer exists (renamed or deleted?): showing the last view, which comes back automatically if it reappears.';
        if (!S.lost) toast(msg, 'warn', 6000);
        S.lost = { ids: missing, files: oldFiles, msg };
        if (S.layout) setStale(true, msg); else showBanner(msg);
        return;
      }
      if (changed && changed.length) S.pendingFlash = changed;
      if (S.selected && (idx.byId.has(S.selected) || S.idx.byId.has(S.selected))) showDetails(S.selected, { keepScroll: true });
      await render({ fromReload: true });
      if (changed && changed.length) toast('Updated: ' + changed.slice(0, 3).map(f => '<code>' + esc(f) + '</code>').join(', ') + (changed.length > 3 ? ' +' + (changed.length - 3) + ' more' : ''), null, 2500);
    } catch (err) {
      console.error(err);
    } finally {
      reloading = false;
      if (reloadAgain) { reloadAgain = false; reloadGraph(); }
    }
  }

  function flashFiles(files) {
    const fs = new Set(files);
    for (const [, e] of S.nodeEls) {
      const n = e._sn && e._sn.ref;
      if (n && fs.has(n.file)) { e.classList.remove('flash'); void e.offsetWidth; e.classList.add('flash'); }
    }
  }

  // Fallback when the event stream fails: poll api/status every 2 s, re-fetch the graph when the version changes.
  let polling = false, pollTimer = null;
  function startPolling() {
    if (polling) return;
    polling = true;
    const tick = async () => {
      if (!polling) return;
      try {
        const s = await fetchStatus();
        if (!polling) return;
        setLive(true);
        if (s.version != null && s.version !== S.version) await reloadGraph();
      } catch (_) {
        if (polling) setLive(false);
      }
      if (polling) pollTimer = setTimeout(tick, 2000);
    };
    pollTimer = setTimeout(tick, 2000);
  }
  function stopPolling() {
    polling = false;
    clearTimeout(pollTimer);
    pollTimer = null;
  }

  function setupEvents() {
    if (!window.EventSource) { startPolling(); return; }
    let es, retryTimer = null;
    const connect = () => {
      clearTimeout(retryTimer);
      es = new EventSource('api/events');
      es.addEventListener('graph', ev => {
        let d = {};
        try { d = JSON.parse(ev.data); } catch (_) { /* ignore */ }
        if (d.version == null || d.version !== S.version) reloadGraph(d.changed || []);
      });
      es.onopen = () => { stopPolling(); setLive(true); reloadGraph(); };
      es.onerror = () => {
        startPolling();
        setLive(false);
        // The browser retries by itself unless the stream is CLOSED (HTTP error / wrong type): retry later then.
        if (es.readyState === EventSource.CLOSED) retryTimer = setTimeout(connect, 30000);
      };
    };
    connect();
  }

  // ---------- top bar / keyboard ----------
  function setupChrome() {
    document.querySelectorAll('.stepper button').forEach(b => {
      b.onclick = () => {
        const which = b.dataset.step, d = parseInt(b.dataset.d, 10);
        const p = {};
        if (which === 'in') p.in = clamp(S.state.in + d, 0, 3);
        else p.out = clamp(S.state.out + d, 1, 6);
        if ((p.in != null && p.in === S.state.in) || (p.out != null && p.out === S.state.out)) return;
        navigate(p, { push: false, recenter: false });  // replaces the current history entry + URL
      };
    });
    $('#btn-back').onclick = () => goHistory(-1);
    $('#btn-fwd').onclick = () => goHistory(1);
    $('#btn-modview').onclick = () => {
      if (S.state.view === 'modules') {
        const n = activeIdx().byId.get(S.state.focus);
        let f = S.state.focus;
        if (n) { const list = S.idx.byModule.get(n.module) || []; const mn = list.find(m => m.kind === 'module'); f = mn ? mn.id : f; }
        navigate({ view: 'fn', mode: 'focus', focus: f });
      } else {
        const base = S.selected || S.state.focus;
        if (!base) { toast('Focus something first, then switch to the modules view'); return; }
        navigate({ view: 'modules', mode: 'focus', focus: moduleIdOf(base) });
      }
    };
    $('#btn-view').onclick = ev => { ev.stopPropagation(); $('#view-menu').classList.toggle('hidden'); };
    document.addEventListener('click', ev => {
      if (!ev.target.closest('#view-menu, #btn-view')) $('#view-menu').classList.add('hidden');
      if (!ev.target.closest('#path-pop, #btn-path, #details')) $('#path-pop').classList.add('hidden');
    });
    document.querySelectorAll('#view-menu input[data-kind]').forEach(cb => {
      cb.onchange = () => { cb.checked ? S.kinds.add(cb.dataset.kind) : S.kinds.delete(cb.dataset.kind); render(); };
    });
    $('#opt-hide-modules').onchange = ev => { S.hideModules = ev.target.checked; render(); };
    $('#opt-externals').onchange = ev => { S.showExternals = ev.target.checked; render(); };
    $('#details-close').onclick = () => { $('#details').classList.add('collapsed'); S.selected = null; for (const e of S.nodeEls.values()) e.classList.remove('sel'); };

    document.querySelectorAll('.tab').forEach(t => {
      t.onclick = () => {
        document.querySelectorAll('.tab').forEach(x => x.classList.toggle('active', x === t));
        $('#tab-entry').classList.toggle('hidden', t.dataset.tab !== 'entry');
        $('#tab-tree').classList.toggle('hidden', t.dataset.tab !== 'tree');
      };
    });
    let ft = null;
    $('#list-filter').addEventListener('input', () => { clearTimeout(ft); ft = setTimeout(() => { buildEntrypoints(); buildModuleTree(); }, 120); });

    setupSearchBox($('#search'), $('#search-results'), (n, ev) => {
      if (ev.ctrlKey || ev.metaKey) { openInEditor(n.file, n.line); return; }
      $('#search').value = '';
      $('#search').blur();
      navigate({ view: 'fn', mode: 'focus', focus: n.id, in: n.entrypoint ? 0 : Math.max(1, S.state.in) });
    });

    window.addEventListener('hashchange', onHashChange);
    document.addEventListener('keydown', ev => {
      const tag = (ev.target.tagName || '').toLowerCase();
      const typing = tag === 'input' || tag === 'textarea';
      if (ev.altKey && ev.key === 'ArrowLeft') { goHistory(-1); ev.preventDefault(); return; }
      if (ev.altKey && ev.key === 'ArrowRight') { goHistory(1); ev.preventDefault(); return; }
      if (typing) return;
      if (ev.key === '/' || (ev.key === 'k' && (ev.ctrlKey || ev.metaKey))) { ev.preventDefault(); $('#search').focus(); $('#search').select(); }
      else if (ev.key === 'f' || ev.key === 'F') { S._userMoved = false; fitCam(true); }
      else if (ev.key === 'c' || ev.key === 'C') { S._userMoved = true; centerOn(S.selected || S.state.focus, Math.max(S.cam.k, 0.85), true); }
      else if (ev.key === '+' || ev.key === '=') { S._userMoved = true; const r = $('#canvas').getBoundingClientRect(); zoomAt(1.2, r.width / 2, r.height / 2); }
      else if (ev.key === '-') { S._userMoved = true; const r = $('#canvas').getBoundingClientRect(); zoomAt(1 / 1.2, r.width / 2, r.height / 2); }
      else if (ev.key === 'Enter' && S.selected && S.selected !== S.state.focus) focusNode(S.selected);
      else if (ev.key === 'Escape') { $('#path-pop').classList.add('hidden'); $('#view-menu').classList.add('hidden'); }
    });
  }

  // ---------- boot ----------
  async function boot() {
    setupCanvas();
    setupChrome();
    setupPathPop();
    applyCam();
    try {
      setGraph(await fetchGraph());
    } catch (err) {
      setLive(false);
      toast('Failed to load graph: ' + esc(err.message), 'err', 10000);
      return;
    }
    let s = null;
    if (!S.graph.nodes.length) {
      try { s = await fetchStatus(); } catch (_) { /* offline: keep the empty map */ }
    }
    if (needsProgress(s)) {
      // First build still running (PyCharm plugin): show progress, apply #focus=… once the graph is there.
      setupEvents();
      await watchProgress(s);
      if (!applyHash({ replaceUrl: true })) updateChrome();
      return;
    }
    if (!applyHash({ replaceUrl: true })) updateChrome();
    setupEvents();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
