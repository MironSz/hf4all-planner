/*
 * The solar-system graph built from data/map.js.
 *
 * Decorative nodes are mid-route bend points: a Spacecraft can never stop on
 * them, so they are collapsed into "routes" between real Spaces. Each route
 * remembers the lane labels at both ends (Hohmann straight-through vs. Pivot),
 * whether it follows an aerobrake path (one-way, B7e) and the decorative
 * points to draw it through.
 */
(function (HF) {
  'use strict';

  const M = window.HF_MAP;
  const W = M.width, H = M.height;
  const nodes = {};
  const adj = {};

  for (const id in M.nodes) {
    const n = Object.assign({ id }, M.nodes[id]);
    n.px = n.x * W;
    n.py = n.y * H;
    nodes[id] = n;
    adj[id] = [];
  }
  for (const e of M.edges) {
    const [a, b] = e.split(':');
    if (!nodes[a] || !nodes[b] || a === b) continue;
    adj[a].push(b);
    adj[b].push(a);
  }
  const label = (a, b) => (M.labels[a] && M.labels[a][b]) || null;
  const hasLabels = id => !!M.labels[id] && Object.values(M.labels[id]).some(v => v !== '0');

  /** Routes leaving each real Space. */
  const routes = {};
  for (const id in nodes) {
    if (nodes[id].t === 'decorative') continue;
    const list = [];
    for (const first of adj[id]) {
      const via = [];
      let prev = id, cur = first, blocked = false, oneWay = false, dead = false;
      if (label(cur, prev) === '0') blocked = true;
      if (label(prev, cur) === '0') oneWay = true;
      while (nodes[cur].t === 'decorative') {
        via.push(cur);
        const nexts = adj[cur].filter(x => x !== prev);
        if (nexts.length === 0) { dead = true; break; }
        const nxt = nexts[0];
        if (label(nxt, cur) === '0') blocked = true;
        if (label(cur, nxt) === '0') oneWay = true;
        prev = cur;
        cur = nxt;
      }
      if (dead || blocked || cur === id) continue;
      list.push({
        from: id, to: cur, via, first,
        dep: label(id, first),
        arr: label(cur, via.length ? via[via.length - 1] : id),
        oneWay, viaDeco: via.length > 0
      });
    }
    routes[id] = list;
  }

  /* Synodic comet gates (B7h): the site plus its adjacent coloured space. */
  const synodic = {};
  for (const id in nodes) {
    const s = nodes[id].syn;
    if (!s) continue;
    synodic[id] = s;
    const seen = new Set([id]);
    const queue = adj[id].slice();
    queue.forEach(x => seen.add(x));
    while (queue.length) {
      const n = queue.shift();
      if (nodes[n].t !== 'decorative') { if (!synodic[n]) synodic[n] = s; break; }
      for (const h of adj[n]) if (!seen.has(h)) { seen.add(h); queue.push(h); }
    }
  }

  const Map_ = {
    W, H, nodes, adj, routes, label, hasLabels, synodic,
    LEO: HF_RULES.LEO,
    node(id) { return nodes[id]; },
    name(id) {
      const n = nodes[id];
      if (!n) return '?';
      if (n.name) return n.name;
      const kind = { burn: n.land ? (n.land === 0.5 ? 'Half Lander Burn' : 'Lander Burn') : 'Lagrange Burn',
        hohmann: 'Hohmann', lagrange: n.haz ? (n.haz === 'aero' ? 'Aerobrake' : 'Crash Hazard') : 'Lagrange',
        radhaz: 'Radiation Belt', flyby: 'Flyby', venus: 'Venus Flyby' }[n.t] || n.t;
      return kind + ' #' + id;
    },
    isSite(id) { return nodes[id] && nodes[id].t === 'site'; },
    sites() { return Object.values(nodes).filter(n => n.t === 'site'); },
    zoneOf(id) { return nodes[id] ? nodes[id].z : 'earth'; },
    /** Heliocentric zones between a node and Earth (I9). */
    zonesFromEarth(id) { return Math.abs(HF.zoneIndex(Map_.zoneOf(id)) - HF.zoneIndex('earth')); },
    isLanding(id) { return !!(nodes[id] && nodes[id].land); },
    canStop(id) { return nodes[id] && nodes[id].t !== 'decorative' && !nodes[id].land; },
    /** Sol radiation belts (Mercury zone) don't shield against flares (K2d). */
    isPlanetBelt(id) { return nodes[id] && nodes[id].t === 'radhaz' && nodes[id].z !== 'mercury'; },

    /**
     * Spaces Adjacent to `id` (glossary: Adjacent) - reachable without passing
     * another Space, ignoring crash hazards and lander burns. Returns site ids.
     */
    adjacentSites(id) {
      const out = new Set();
      const seen = new Set([id]);
      const stack = adj[id].slice();
      stack.forEach(x => seen.add(x));
      while (stack.length) {
        const n = stack.pop();
        const node = nodes[n];
        const transparent = node.t === 'decorative' || node.land || node.haz === 'crash';
        if (node.t === 'site') out.add(n);
        if (transparent) {
          for (const h of adj[n]) if (!seen.has(h)) { seen.add(h); stack.push(h); }
        }
      }
      out.delete(id);
      return [...out];
    },

    /** Polyline points of a route, in board pixels. */
    routePoints(r) {
      return [r.from].concat(r.via, [r.to]).map(id => [nodes[id].px, nodes[id].py]);
    }
  };

  HF.map = Map_;
})(window.HF = window.HF || {});
