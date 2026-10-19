/*
 * The ship dock: a strip along the bottom of the board that always shows the
 * active player's Rocket (location, weight class, fuel, best thrust, cards as
 * card-art thumbnails), the stacks Colocated with it, the Hand and quick
 * actions. Cards and FTs are moved by drag and drop (shared with the playmat).
 * During a movement it stays available, collapsed by default and read-only.
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const S = HF.state;
  const MAP = HF.map;
  const FS = HF.FS;
  const esc = HF.escape;

  /** Card-art thumbnail of a stack card; draggable when it belongs to the active player. */
  UI.cardThumb = function (inst, opts) {
    opts = opts || {};
    const s = HF.cards.stats(inst);
    const art = UI.artSrc(s);
    const info = HF.cards.DECK_INFO[s.deck];
    const t = s.thrust ? s.thrust.t + '·' + HF.fmtNum(s.thrust.f) : '';
    const o = UI.otherSide(s);
    const oart = o && UI.artSrc(o);
    return '<div class="thumb' + (opts.drag ? ' dnd-card' : '') + (s.orient === 'heavy' ? ' rot' : '') + (o ? ' has-alt' : '') + (opts.cls ? ' ' + opts.cls : '') + '"' +
      (opts.drag ? ' draggable="true"' : '') + ' style="--deck:' + info.color + '" ' + (opts.attrs || '') +
      ' data-tip="' + esc((art ? UI.artTip(s) : '') + '<b>' + esc(s.name) + '</b>' + (opts.status ? '<br>' + esc(opts.status) : '')) + '">' +
      (art ? '<img class="front-side" src="' + art + '" alt="" draggable="false" onerror="this.remove()">' : '') +
      '<div class="thumb-fb front-side"><b>' + esc(s.name) + '</b><span>' + (s.mass ?? 0) + 'M ' + t + '</span></div>' +
      (oart ? '<img class="alt-side" src="' + oart + '" alt="" draggable="false" onerror="this.remove()">' : '') +
      (o ? '<div class="thumb-fb alt-side"><b>' + esc(o.name) + '</b><span>' + (o.mass ?? 0) + 'M</span></div>' : '') +
      (opts.status != null ? '<i class="thumb-dot ' + (opts.statusOk ? 'ok' : 'bad') + '"></i>' : '') +
      (inst.glory ? '<i class="thumb-glory" title="Carries a glory chit">★</i>' : '') +
      '</div>';
  };

  /** Draggable FT chip ("3 FT" / "5 Aquas"). */
  UI.ftChip = function (pi, key, n, drag) {
    if (!n) return '';
    return '<div class="ft-chip' + (drag ? ' dnd-ft' : '') + '"' + (drag ? ' draggable="true"' : '') + ' data-p="' + pi + '" data-key="' + key + '" data-n="' + n + '"' +
      ' data-tip="' + esc(drag ? 'Drag onto another stack to move FTs, or onto the Fuel Strip to load them as fuel' : '') + '"><i class="aqua"></i> ' + n + (key === 'leo' ? ' Aquas' : ' FT') + '</div>';
  };

  const Dock = {
    collapsed: false,
    /** Collapsed state while moving: every movement starts with the dock collapsed. */
    moveCollapsed: true,
    wasMoving: false,

    isCollapsed(G) { return G && G.move ? Dock.moveCollapsed : Dock.collapsed; },

    /** Show / hide the dock body (D); while moving this does not change the saved preference. */
    toggle() {
      const App = HF.app;
      if (App.G && App.G.move) Dock.moveCollapsed = !Dock.moveCollapsed;
      else { Dock.collapsed = !Dock.collapsed; App.settings.dockCollapsed = Dock.collapsed; App.saveSettings(); }
      App.render();
    },

    render(G) {
      const el = document.getElementById('dock');
      if (!el) return;
      if (G.gameOver) { el.className = 'dock hidden'; el.innerHTML = ''; return; }
      const moving = !!G.move;
      if (moving && !Dock.wasMoving) Dock.moveCollapsed = true;
      Dock.wasMoving = moving;
      const collapsed = Dock.isCollapsed(G);
      const act = !moving && !G.pending;
      const p = G.players[G.current];
      el.className = 'dock' + (collapsed ? ' collapsed' : '');
      el.style.setProperty('--pc', S.color(p));
      const r = p.stacks.rocket;
      const node = r ? r.node : MAP.LEO;
      const head = '<div class="dock-head"><button class="icon-btn dock-toggle" data-d="toggle" data-tip="' + (collapsed ? 'Show' : 'Hide') + ' the ship dock (D)">' + (collapsed ? '▴' : '▾') + '</button>' +
        UI.gfx.logoHtml(p, 20) + '<b>' + esc(p.name) + '</b><span class="muted">' + esc(p.faction) + '</span>' +
        '<span class="dock-pill"><i class="aqua"></i> ' + p.aquas + '</span><span class="dock-pill" data-tip="Victory points">★ ' + HF.events.score(G, p.idx).total + '</span>' +
        (r ? '<span class="dock-loc">Rocket at <a data-d="goto" data-node="' + r.node + '">' + esc(MAP.name(r.node)) + '</a></span>' : '<span class="dock-loc muted">no Rocket yet</span>') + '</div>';
      if (collapsed) { el.innerHTML = head; return; }

      let h = head + '<div class="dock-body">';
      // Rocket summary
      if (r) {
        const fuel = r.fuel || FS.create(Math.max(1, S.cargoMass(p, 'rocket')));
        const wc = FS.weightClass(fuel.wet);
        const best = Dock.bestThrust(G);
        const m = G.move;
        const unbuilt = r.cards.some(c => HF.movement.needsSupports(HF.cards.stats(c))) && !Dock.engines(G).some(e => !e.simple);
        h += '<section class="dock-sum' + (act ? ' dnd-zone' : '') + '" data-key="rocket" data-fuel="1">' +
          // The original Fuel Strip, large: drop FTs / Aquas on it to load them as fuel. The Rocket's
          // numbers, engines and buttons fill the empty corner under the strip's curve.
          '<div class="ds-gauge ds-strip" data-tip="' + (act ? 'Fuel Strip: drop FTs or Aquas here to load them as fuel' : 'Fuel Strip') + '">' +
          UI.fuelStrip(fuel, { color: S.color(p), pending: moving ? m.spent : 0 }) +
          '<div class="ds-over">' +
          '<div class="ds-row"><b class="wc wc-' + wc.name.toLowerCase() + '">' + wc.name + ' ' + (wc.mod > 0 ? '+' : '') + wc.mod + '</b>' +
          '<span class="ds-k">Dry</span><b>' + fuel.dry + '</b><span class="ds-k">Wet</span><b>' + FS.label(fuel.wet) + '</b>' +
          '<span class="ds-fuel"><b>' + FS.fuelSteps(fuel) + '</b> steps' + (fuel.grade ? ' ' + HF.cards.gradeName(fuel.grade) : '') + '</span></div>' +
          (moving ? '<div class="ds-move">Moving with <b>' + esc(m.name) + '</b> · net <b>' + m.net + '</b> · burns <b>' + m.burnsUsed + '</b>/' + m.burnLimit +
              ' · fuel to burn <b>' + HF.fmtNum(m.spent) + '</b></div>' :
            '<div class="ds-thr">' + Dock.thrusters(G).map(o => '<button class="thr-chip" data-d="move" data-tip="' + esc('<b>' + esc(o.engineName || o.name) + '</b><br>net thrust ' + o.net + ' (= burn limit) · ' +
              HF.fmtNum(o.fuelCons) + ' fuel steps per burn') + '"><span class="tc-n">' + esc(o.name) + '</span>' +
              '<b>' + o.net + '</b><span class="tc-b">' + (o.fuelCons > 0 ? Math.floor(o.fuelAvail / o.fuelCons) + '×' : '∞') + '</span></button>').join('') +
              (unbuilt ? '<button class="thr-chip build" data-d="engines" data-tip="Your thrusters that need supports move only as engines you build">🔧 Build an engine</button>'
                : best ? '' : '<span class="muted small">no engine can fire</span>') + '</div>') +
          '<div class="ds-btns">' +
          (moving ? '' : (!G.turn.moved ? '<button class="btn primary xs" data-d="move" data-tip="Choose an engine and move (M)">Move ▸</button>' : '<span class="state done">✓ moved</span>') +
            '<button class="btn xs" data-d="load" data-tip="Load FTs / Aquas as fuel">Load fuel</button>' +
            '<button class="btn xs" data-d="engines" data-tip="Engine builder: a thrust triangle with the supports you choose (J1c)">🔧</button>') +
          '<button class="btn xs" data-d="mat" data-tip="Your playmat as on the table (P)">Playmat ▸</button></div></div></div></section>';
      } else {
        h += '<section class="dock-sum empty dnd-zone" data-key="rocket" data-new="1"><div class="dock-cta"><b>No Rocket</b><span>Drag cards here from a stack to build one (E5).</span>' +
          '<span class="muted small">It starts with empty tanks: then drop Aquas on its fuel gauge.</span></div></section>';
      }
      // Rocket cards
      if (r) {
        const sv = HF.cards.stackViewAt(p.idx, 'rocket');
        h += '<section class="dock-stack dnd-zone" data-key="rocket"><div class="dk-h">Rocket <span class="muted">mass ' + S.cargoMass(p, 'rocket') + ' · ❄' + sv.therms + '</span>' +
          (r.glitch ? ' <span class="glitch">GLITCH</span>' : '') + '</div><div class="thumbs">' +
          r.cards.map((c, i) => { const st = HF.cards.statusAt(p.idx, 'rocket', i); return UI.cardThumb(c, { drag: act, status: st, statusOk: st === 'Operational', attrs: 'data-d="card" data-p="' + p.idx + '" data-key="rocket" data-i="' + i + '"' }); }).join('') +
          UI.ftChip(p.idx, 'rocket', r.fts, act) + '</div></section>';
      }
      // Other own stacks on the same Space (where cargo can move to/from)
      for (const k of S.ownStackKeysAt(p, node)) {
        if (k === 'rocket') continue;
        const s = p.stacks[k];
        h += '<section class="dock-stack dnd-zone" data-key="' + k + '"><div class="dk-h">' + S.STACK_NAME[k] + ' <span class="muted">here</span></div><div class="thumbs">' +
          s.cards.map((c, i) => { const st = HF.cards.statusAt(p.idx, k, i); return UI.cardThumb(c, { drag: act, status: st, statusOk: st === 'Operational', attrs: 'data-d="card" data-p="' + p.idx + '" data-key="' + k + '" data-i="' + i + '"' }); }).join('') +
          UI.ftChip(p.idx, k, S.stackFts(p, k), act) + (s.cards.length || S.stackFts(p, k) ? '' : '<span class="muted small">empty</span>') + '</div></section>';
      }
      // Free Outpost slots as drop targets (new Outpost on this Space)
      const free = ['out1', 'out2'].filter(k => !p.stacks[k]);
      if (free.length && act) h += '<section class="dock-new dnd-zone" data-key="' + free[0] + '" data-new="1" data-tip="Drop cards or FTs here to leave them in a new ' + S.STACK_NAME[free[0]] + ' on this Space (E6)"><span>＋</span>New outpost</section>';
      // Hand
      h += '<section class="dock-hand" data-d="hand" data-tip="Hand cards (open information). Click to open the Hand tab.">' +
        '<div class="dk-h">Hand <span class="muted">' + p.hand.length + (p.hand.length >= 4 && !S.privilegeActive(G, p, 'skunkworks') ? ' · auction-locked' : '') + '</span></div><div class="fan">' +
        p.hand.slice(0, 8).map((id, i) => {
          const s = HF.cards.sideStats(id, 'w');
          const art = UI.artSrc(s);
          const b = HF.cards.sideStats(id, 'b');
          const bart = UI.artSrc(b);
          return '<div class="fan-card has-alt" style="--i:' + i + ';--n:' + Math.min(8, p.hand.length) + '" data-tip="' + esc(UI.artTip(s) + '<b>' + esc(s.name) + '</b>') + '">' +
            (art ? '<img class="front-side" src="' + art + '" alt="" draggable="false" onerror="this.remove()">' : '<span class="front-side">' + esc(s.name) + '</span>') +
            (bart ? '<img class="alt-side" src="' + bart + '" alt="" draggable="false" onerror="this.remove()">' : '<span class="alt-side">' + esc(b.name) + '</span>') + '</div>';
        }).join('') + (p.hand.length ? '' : '<span class="muted small">empty</span>') + '</div></section>';
      h += '</div>';
      el.innerHTML = h;
    },

    /** Operational thrust triangles, strongest first (max 3). */
    /** The current player's engines (built ones and supportless cards). */
    engines(G) { return HF.movement.engines(G); },

    /** Engines that can fire now, strongest first (max 3), as their activation previews. */
    thrusters(G) {
      return Dock.engines(G).filter(e => e.plan.ok).map(e => Object.assign({}, e.plan, { engineName: e.name, engineId: e.id }))
        .sort((a, b) => b.net - a.net).slice(0, 3);
    },

    bestThrust(G) { return Dock.thrusters(G)[0] || null; },

    onClick(e) {
      const b = e.target.closest('[data-d]');
      if (!b) return;
      const d = b.dataset.d;
      const App = HF.app;
      if (d === 'toggle') Dock.toggle();
      if (d === 'goto') UI.board.centerOn(b.dataset.node);
      if (d === 'move') UI.dialogs.movePrep();
      if (d === 'load') UI.dialogs.loadFuel();
      if (d === 'engines') UI.dialogs.engineBuilder(false);
      if (d === 'mat') UI.playmat.open();
      if (d === 'hand') { App.ui.tab = 'hand'; App.render(); }
      if (d === 'card') UI.dialogs.cardActions(+b.dataset.p, b.dataset.key, +b.dataset.i);
    }
  };

  /* ------------------------------------------------------------------
   * Drag and drop of stack cards and FT chips (dock + playmat).
   * ------------------------------------------------------------------ */
  const DnD = {
    src: null,

    bind(root) {
      root.addEventListener('dragstart', e => {
        const c = e.target.closest('.dnd-card, .dnd-ft, .dnd-hand');
        if (!c) return;
        const G = HF.app.G;
        if (+c.dataset.p !== G.current || G.move || G.pending || G.gameOver) { e.preventDefault(); return; }
        DnD.src = c.classList.contains('dnd-hand') ? { hand: true, i: +c.dataset.hi }
          : { key: c.dataset.key, i: c.dataset.i != null ? +c.dataset.i : null, n: c.dataset.n != null ? +c.dataset.n : 0, ft: c.classList.contains('dnd-ft') };
        e.dataTransfer.effectAllowed = 'move';
        try { e.dataTransfer.setData('text/plain', 'hf4a'); } catch (err) { /* IE */ }
        c.classList.add('dragging');
        DnD.mark(true);
        UI.tip(null);
      });
      root.addEventListener('dragend', e => {
        const c = e.target.closest('.dnd-card, .dnd-ft, .dnd-hand');
        if (c) c.classList.remove('dragging');
        DnD.mark(false);
        DnD.src = null;
      });
      root.addEventListener('dragover', e => {
        const z = e.target.closest('.dnd-zone');
        if (!z || !DnD.src || z.classList.contains('drop-no')) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        root.querySelectorAll('.drop-hover').forEach(x => x.classList.remove('drop-hover'));
        z.classList.add('drop-hover');
      });
      root.addEventListener('dragleave', e => {
        const z = e.target.closest('.dnd-zone');
        if (z && !z.contains(e.relatedTarget)) z.classList.remove('drop-hover');
      });
      root.addEventListener('drop', e => {
        const z = e.target.closest('.dnd-zone');
        if (!z || !DnD.src) return;
        e.preventDefault();
        const src = DnD.src;
        DnD.mark(false);
        DnD.src = null;
        DnD.drop(src, z.dataset.key, !!z.dataset.fuel && !!e.target.closest('.ds-gauge'));
      });
    },

    /** Can the dragged source go into drop zone z? */
    zoneOk(z) {
      const G = HF.app.G;
      const p = G.players[G.current];
      const src = DnD.src;
      const k = z.dataset.key;
      const av = UI.panels.opAvailability(G);
      if (src.hand) {
        if (k === 'leo') return av.boost.ok;
        if (k === 'sell') return av.freeMarket.ok;
        return k === 'discard';
      }
      if (k === 'discard' || k === 'sell') return false;
      const from = S.stackNode(p, src.key);
      if (k === 'hand') return !src.ft && !p.stacks[src.key].cards[src.i].crew;
      if (z.dataset.only === 'ft' && !src.ft) return false;
      if (z.dataset.fuel && src.ft && p.stacks.rocket && S.stackNode(p, 'rocket') === from) return true;
      const to = p.stacks[k] ? S.stackNode(p, k) : from;
      return k !== src.key && to === from && !(k === 'leo' && from !== MAP.LEO);
    },

    /** Highlight drop zones: colocated stacks, empty slots, and the hand targets. */
    mark(on) {
      document.querySelectorAll('.dnd-zone').forEach(z => {
        z.classList.remove('drop-ok', 'drop-no', 'drop-hover');
        if (!on || !DnD.src) return;
        z.classList.add(DnD.zoneOk(z) ? 'drop-ok' : 'drop-no');
      });
    },

    drop(src, to, asFuel) {
      const App = HF.app;
      const A = HF.actions;
      const G = App.G;
      const p = G.players[G.current];
      if (src.hand) {
        const id = p.hand[src.i];
        const name = esc(HF.cards.def(id).w.name);
        if (to === 'leo') return UI.dialogs.op_boost([src.i]);
        if (to === 'sell') return UI.confirm('Free Market (I3)', '<p>Sell <b>' + name + '</b> for 3 Aquas? This is your Operation for the turn.</p>', 'Sell', () => App.act(g => HF.ops.sellHand(g, src.i)));
        if (to === 'discard') return UI.confirm('Discard (G6)', '<p>Discard <b>' + name + '</b> to the bottom of its deck (free action)?</p>', 'Discard', () => App.act(g => A.discardHand(g, src.i)), 'danger');
        return;
      }
      if (to === 'hand') {
        const c = p.stacks[src.key].cards[src.i];
        return UI.confirm('Decommission to Hand', '<p>Take <b>' + esc(HF.cards.stats(c).name) + '</b> out of ' + S.STACK_NAME[src.key] + ' back into your Hand (voluntary decommission)?</p>',
          'Decommission', () => App.act(g => A.voluntaryDecommission(g, src.key, src.i)), 'danger');
      }
      if (src.ft) {
        const max = S.stackFts(p, src.key);
        if (asFuel && to === 'rocket') {
          return UI.askNumber('Load fuel (G1b)', '<p>Load ' + (src.key === 'leo' ? 'Aquas' : 'FTs') + ' from ' + S.STACK_NAME[src.key] + ' as Wet Mass Fuel.</p>', 1, max, Math.min(max, 3), 'Load',
            n => App.act(g => (src.key === 'rocket' ? A.ftToFuel(g, n) : A.loadFuel(g, src.key, n))));
        }
        if (to === src.key) return;
        return UI.askNumber('Move FTs (G1)', '<p>Move ' + (src.key === 'leo' ? 'Aquas' : 'FTs') + ' from ' + S.STACK_NAME[src.key] + ' to ' + S.STACK_NAME[to] + ' as cargo.</p>', 1, max, max, 'Move',
          n => App.act(g => A.transfer(g, src.key, to, [], n)));
      }
      if (to === src.key) return;
      App.act(g => A.transfer(g, src.key, to, [src.i], 0));
    }
  };

  UI.dock = Dock;
  UI.dnd = DnD;
})(window.HF = window.HF || {});
