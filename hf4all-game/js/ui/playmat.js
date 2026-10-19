/*
 * The playmat: a full-screen "as on the table" view of one player's mat - the
 * original Fuel Strip (the planner's rulebook cut-out, UI.fuelStrip) with the Dry /
 * Wet Mass chits, the four stack slots (LEO with its Aquas and glories, Rocket,
 * Outpost 1, Outpost 2) and the Hand, all with the original graphics when
 * assets/gfx is present (drawn fallbacks otherwise).
 *
 * Cards and FT beads are dragged between Colocated stacks (G1 cargo transfer, via
 * UI.dnd); Hand cards are dragged onto LEO (boost), Sell (Free Market) or Discard.
 * It also opens during a movement, read-only (no free actions mid-move, H1c), with
 * the ghost Wet chit of the fuel the move will burn.
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const S = HF.state;
  const MAP = HF.map;
  const FS = HF.FS;
  const esc = HF.escape;
  const PM_DATA = () => window.HF_PLAYMAT;
  const pct = v => (v * 100).toFixed(3) + '%';

  const PM = {
    isOpen: false,
    pi: null,

    el() { return document.getElementById('playmat'); },

    open(pi) {
      const G = HF.app.G;
      if (!G) return;
      PM.isOpen = true;
      PM.pi = pi == null ? null : pi;
      UI.tip(null);
      HF.app.render();
    },

    close() {
      PM.isOpen = false;
      const el = PM.el();
      if (el) { el.className = 'pm hidden'; el.innerHTML = ''; }
      UI.tip(null);
    },

    toggle() { if (PM.isOpen) PM.close(); else PM.open(); },

    /* ---------------- render ---------------- */
    render(G) {
      const el = PM.el();
      if (!el) return;
      if (!PM.isOpen) return;
      const pi = PM.pi != null && G.players[PM.pi] ? PM.pi : G.current;
      const p = G.players[pi];
      const mine = pi === G.current && !G.gameOver;
      const dnd = mine && !G.pending && !G.move;
      el.className = 'pm' + (UI.gfx.on() ? ' pm-gfx' : '');
      el.style.setProperty('--pc', S.color(p));
      const keep = el.querySelector('.pm-preview') ? el.querySelector('.pm-preview').innerHTML : '';
      el.innerHTML =
        '<div class="pm-head">' +
        '<div class="pm-players">' + S.order(G).map(q => '<button class="chip' + (q === pi ? ' on' : '') + '" data-pm="player" data-p="' + q + '">' +
          UI.gfx.logoHtml(G.players[q], 18) + ' ' + esc(G.players[q].name) + (q === G.current ? ' <span class="tag">active</span>' : '') + '</button>').join('') + '</div>' +
        '<div class="pm-hint muted small">' + (dnd ? 'Drag cards and FT beads between stacks on the same Space (free action) · drag Hand cards onto LEO (boost), Sell or Discard' :
          mine && G.move ? 'Movement in progress: the mat is read-only until the move ends (H1c) · P or Esc returns to the map.' :
          mine ? 'Resolve the pending decision first.' : 'Viewing ' + esc(p.name) + '’s mat (open information) - only the active player can act.') + '</div>' +
        '<button class="btn sm" data-pm="close" data-tip="Back to the map (P / Esc)">✕ Map</button></div>' +
        '<div class="pm-top">' + PM.strip(G, p, dnd) + PM.info(G, p, mine, dnd) + '</div>' +
        '<div class="pm-slots">' + ['leo', 'rocket', 'out1', 'out2'].map(k => PM.slot(G, p, k, dnd)).join('') + PM.handSlot(G, p, dnd) + '</div>';
      if (keep) el.querySelector('.pm-preview').innerHTML = keep;
    },

    /* ---------------- Fuel Strip ---------------- */
    strip(G, p, dnd) {
      const r = p.stacks.rocket;
      const fuel = r ? (r.fuel || FS.create(Math.max(1, S.cargoMass(p, 'rocket')))) : null;
      const moving = G.move && p.idx === G.current;
      const best = p.idx === G.current && r && !G.gameOver ? UI.dock.bestThrust(G) : null;
      const net = moving ? G.move.net : best ? best.net : null;
      const cap = r ? UI.fuelSummary(fuel) + (net != null ? ' · net thrust <b>' + net + '</b>' + (best && !moving ? ' (' + esc(best.name) + ')' : '') : '') : 'No Rocket - the chits wait in Chit Storage.';
      const inner = '<div class="pm-strip-orig ds-gauge">' + UI.fuelStrip(fuel, { color: S.color(p), pending: moving ? G.move.spent : 0 }) + '</div>';
      return '<section class="pm-strip' + (dnd && r ? ' dnd-zone' : '') + '" data-key="rocket" data-fuel="1" data-only="ft">' + inner +
        '<div class="pm-cap">' + cap + (dnd && r ? ' · <span class="muted">drop FT beads here to load fuel</span>' : '') + '</div></section>';
    },

    /* ---------------- info panel ---------------- */
    info(G, p, mine, dnd) {
      const sc = HF.events.score(G, p.idx);
      const priv = HF_RULES.privileges[p.privilege];
      const r = p.stacks.rocket;
      let h = '<aside class="pm-info"><div class="pm-who">' + UI.gfx.logoHtml(p, 56) + '<div><div class="pm-name">' + esc(p.name) +
        (p.idx === G.firstPlayer ? ' ' + UI.gfx.icon('firstplayer.png', '<span class="first">1st</span>', 'first-ico') : '') + '</div>' +
        '<div class="muted">' + esc(p.faction) + '</div><div class="pm-priv" data-tip="' + esc(priv.text) + '">✦ ' + esc(priv.name) + '</div></div>' +
        '<div class="pm-score"><span data-tip="Aquas in LEO"><i class="aqua"></i> ' + p.aquas + '</span><span data-tip="Victory points">' + UI.gfx.icon('star-gold.png', '★', 'vp-ico') + ' ' + sc.total + '</span>' +
        '<span data-tip="Hand cards">🂠 ' + p.hand.length + '</span></div></div>';
      if (mine && r && G.move) {
        const m = G.move;
        h += '<div class="pm-sec"><div class="pm-sec-h">Movement</div><div class="small">' + esc(m.name) + ' · net thrust <b>' + m.net + '</b> · burns <b>' + m.burnsUsed + '</b> / ' + m.burnLimit +
          (m.bonus ? ' · bonus burns <b>' + m.bonus + '</b>' : '') + ' · fuel to burn <b>' + HF.fmtNum(m.spent) + '</b> step' + (m.spent === 1 ? '' : 's') + '</div></div>';
      }
      if (mine && r && !G.turn.moved && !G.move) {
        const th = UI.dock.thrusters(G);
        h += '<div class="pm-sec"><div class="pm-sec-h">Engines <button class="btn xs" data-pm="engines" data-tip="Engine builder: a thrust triangle with the supports you choose (J1c)">🔧 Build…</button></div>' +
          (th.length ? '<div class="ds-thr">' + th.map(o => '<button class="thr-chip" data-pm="move" data-tip="' +
          esc('Move with ' + (o.engineName || o.name) + ' (H)') + '"><span class="tc-n">' + esc(o.name) + '</span><b>' + o.net + '</b><span class="tc-b">' + (o.fuelCons > 0 ? Math.floor(o.fuelAvail / o.fuelCons) + '×' : '∞') + '</span></button>').join('') + '</div>' :
          '<span class="muted small">no Operational thrust triangle</span>') + '</div>';
      }
      if (mine && r && dnd) {
        h += '<div class="pm-sec"><div class="pm-sec-h">Fuel (G1-G2)</div><div class="pm-btns">' +
          [['load', 'Load fuel…'], ['ft2fuel', 'FT → Fuel…'], ['fuel2ft', 'Fuel → FT…'], ['jettison', 'Jettison…'], ['fogg', 'Phileas Fogg…'], ['swap', 'Swap…']]
            .map(([f, l]) => '<button class="btn xs" data-pm="fuel" data-f="' + f + '">' + l + '</button>').join('') + '</div></div>';
      }
      h += '<div class="pm-preview"><div class="muted small">Point at a card to see it here.</div></div></aside>';
      return h;
    },

    /** Large card preview for the hovered card. */
    preview(t) {
      const box = document.querySelector('#playmat .pm-preview');
      if (!box) return;
      const G = HF.app.G;
      let s, status = null;
      if (t.dataset.hi != null) s = HF.cards.sideStats(G.players[+t.dataset.p].hand[+t.dataset.hi], HF.app.ui.handBlack ? 'b' : 'w');
      else {
        const p = G.players[+t.dataset.p];
        const st = p.stacks[t.dataset.key];
        if (!st || !st.cards[+t.dataset.i]) return;
        s = HF.cards.stats(st.cards[+t.dataset.i]);
        status = HF.cards.statusAt(+t.dataset.p, t.dataset.key, +t.dataset.i);
      }
      box.innerHTML = UI.cardFull(s, { status, statusOk: status === 'Operational' });
    },

    /* ---------------- stack slots ---------------- */
    /** Slot background (the printed playmat slot); `extra` goes on top of it in its own coordinates. */
    slotBg(key, empty, extra) {
      const D = PM_DATA();
      if (!UI.gfx.on() || !D) return '<div class="slot-bg plain' + (empty ? ' empty' : '') + '" style="aspect-ratio:338/517"><b>' + (key === 'hand' ? 'HAND' : S.STACK_NAME[key].toUpperCase()) + '</b></div>';
      const sd = D.slots[key];
      return '<div class="slot-bg" style="aspect-ratio:' + sd.w + '/' + sd.ih + '">' +
        '<img class="' + (empty ? 'empty' : '') + '" src="assets/gfx/' + sd.img + '" alt="" draggable="false" style="width:' + pct(sd.iw / sd.w) + ';left:' + pct(-sd.x / sd.w) + '">' + (extra || '') + '</div>';
    },

    /** Grid cell style for a slot: title space and card column width (fractions of the slot width). */
    slotGeom(key) {
      const D = PM_DATA();
      const sd = D && UI.gfx.on() ? D.slots[key] : { w: 338, card: 338, top: 70 };
      return { pad: pct((sd.top || 70) / sd.w), body: pct(sd.card / sd.w), flex: sd.w };
    },

    cardHtml(G, p, inst, key, i, dnd) {
      const s = HF.cards.stats(inst);
      const art = UI.artSrc(s);
      const status = HF.cards.statusAt(p.idx, key, i);
      const info = HF.cards.DECK_INFO[s.deck];
      const gl = inst.glory ? UI.gfx.glory(inst.glory) : null;
      return '<div class="pc' + (dnd ? ' dnd-card' : '') + (s.orient === 'heavy' ? ' rot' : '') + ' has-alt"' + (dnd ? ' draggable="true"' : '') +
        ' data-pm="card" data-p="' + p.idx + '" data-key="' + key + '" data-i="' + i + '" style="--deck:' + info.color + '">' +
        PM.faces(s, art) +
        '<i class="pc-dot ' + (status === 'Operational' ? 'ok' : 'bad') + '" data-tip="' + esc(status) + '"></i>' +
        (inst.glory ? (gl ? '<img class="pc-glory" src="' + gl + '" alt="" data-tip="Carries the ' + esc(HF.zone(inst.glory).name) + ' glory chit">' : '<i class="pc-glory txt">★</i>') : '') +
        '</div>';
    },

    /** Art (or drawn fallback) of a card and, hidden until Alt is held, of its other side. */
    faces(s, art) {
      const info = HF.cards.DECK_INFO[s.deck];
      const fb = (x, cls) => '<div class="pc-fb' + cls + '"><span class="pc-m">' + (x.mass ?? 0) + '</span><b>' + esc(x.name) + '</b><span>' + info.name.replace(/s$/, '') + '</span></div>';
      const o = UI.otherSide(s);
      const oart = o && UI.artSrc(o);
      return (art ? '<img class="pc-art front-side" src="' + art + '" alt="" draggable="false" onerror="this.remove()">' : '') + fb(s, ' front-side') +
        (oart ? '<img class="pc-art alt-side" src="' + oart + '" alt="" draggable="false" onerror="this.remove()">' : '') + (o ? fb(o, ' alt-side') : '');
    },

    beads(p, key, n, drag) {
      if (!n) return '';
      const src = UI.gfx.src('aqua-bead.png');
      const shown = Math.min(n, key === 'leo' ? 16 : 10);
      let b = '';
      for (let i = 0; i < shown; i++) b += src ? '<img src="' + src + '" alt="" draggable="false">' : '<i class="aqua"></i>';
      const jettison = drag && key !== 'leo';
      return '<div class="pm-beads' + (drag ? ' dnd-ft' : '') + '"' + (drag ? ' draggable="true"' : '') + (jettison ? ' data-pm="beads"' : '') + ' data-p="' + p.idx + '" data-key="' + key + '" data-n="' + n + '" data-tip="' +
        esc(n + (key === 'leo' ? ' Aquas' : ' FT') + (drag ? ' - drag onto another stack, or onto the Fuel Strip to load fuel' : '') + (jettison ? '; click to jettison FTs (G1g)' : '')) + '">' + b +
        '<span class="pm-bn">' + n + '</span></div>';
    },

    slot(G, p, k, dnd) {
      const s = p.stacks[k];
      const g = PM.slotGeom(k);
      const node = S.stackNode(p, k);
      let body = '', foot = '', extra = '';
      if (s) {
        const sv = HF.cards.stackViewAt(p.idx, k);
        body = s.cards.map((c, i) => PM.cardHtml(G, p, c, k, i, dnd)).join('');
        const piece = k === 'leo' ? null : UI.gfx.piece(k === 'rocket' ? 'rocket' : k, p);
        foot = (piece ? '<img class="pm-piece ' + k + '" src="' + piece + '" alt="">' : '') +
          '<span>' + (k === 'leo' ? 'Low Earth Orbit' : 'at <a data-pm="goto" data-node="' + node + '">' + esc(MAP.name(node)) + '</a>') + '</span>' +
          '<span class="muted small">mass ' + S.cargoMass(p, k) + ' · ❄' + sv.therms + '</span>' + (s.glitch ? '<span class="glitch">GLITCH</span>' : '') +
          (k !== 'leo' ? PM.beads(p, k, s.fts || 0, dnd) : '');
        if (k === 'leo') {
          const D = PM_DATA();
          const sd = D && D.slots.leo;
          if (UI.gfx.on() && sd) {
            const a = sd.aquas, gl = sd.glories;
            extra = '<div class="pm-aquas" style="left:' + pct(a[0] / sd.w) + ';top:' + pct(a[1] / sd.ih) + ';width:' + pct((a[2] - a[0]) / sd.w) + ';height:' + pct((a[3] - a[1]) / sd.ih) + '">' +
              PM.beads(p, 'leo', p.aquas, dnd) + '</div>' +
              '<div class="pm-glories" style="left:' + pct(gl[0] / sd.w) + ';top:' + pct(gl[1] / sd.ih) + ';width:' + pct((gl[2] - gl[0]) / sd.w) + ';height:' + pct((gl[3] - gl[1]) / sd.ih) + '">' +
              p.glories.map(x => '<img src="' + UI.gfx.gloryVp(x.vp) + '" alt="" data-tip="' + esc(HF.zone(x.zone).name + ' glory in LEO: ' + x.vp + ' VP') + '">').join('') + '</div>';
          } else {
            foot += PM.beads(p, 'leo', p.aquas, dnd) + (p.glories.length ? '<span class="small">' + p.glories.map(x => '★ ' + HF.zone(x.zone).name + ' ' + x.vp).join(' · ') + '</span>' : '');
          }
        }
      } else {
        body = '<div class="pm-empty">' + (dnd ? 'Not in play - drag cards here to create it' : 'Not in play') + '</div>';
        foot = '<span class="muted small">no ' + S.STACK_NAME[k] + '</span>';
      }
      const n = s ? s.cards.length : 0;
      return '<section class="pm-slot' + (dnd ? ' dnd-zone' : '') + (s ? '' : ' off') + '" data-key="' + k + '"' + (s ? '' : ' data-new="1"') +
        ' style="--flex:' + g.flex + ';--ov:' + (n > 6 ? 0.86 : n > 4 ? 0.83 : 0.8) + '">' +
        '<div class="slot-main">' + PM.slotBg(k, !s, extra) +
        '<div class="slot-body" style="width:' + g.body + ';padding-top:' + g.pad + '">' + body + '</div></div>' +
        '<div class="pm-foot">' + foot + '</div></section>';
    },

    handSlot(G, p, dnd) {
      const g = PM.slotGeom('hand');
      const black = HF.app.ui.handBlack;
      const av = UI.panels.opAvailability(G);
      const body = p.hand.map((id, i) => {
        const s = HF.cards.sideStats(id, black ? 'b' : 'w');
        const art = UI.artSrc(s);
        const info = HF.cards.DECK_INFO[s.deck];
        return '<div class="pc hand has-alt' + (dnd ? ' dnd-hand' : '') + '"' + (dnd ? ' draggable="true"' : '') + ' data-pm="hand" data-p="' + p.idx + '" data-hi="' + i + '" style="--deck:' + info.color + '">' +
          PM.faces(s, art) + '</div>';
      }).join('') || '<div class="pm-empty">Empty Hand - win patents in Research Auctions</div>';
      const locked = p.hand.length >= 4 && !S.privilegeActive(G, p, 'skunkworks');
      return '<section class="pm-slot hand' + (dnd ? ' dnd-zone' : '') + '" data-key="hand" style="--flex:' + g.flex + ';--ov:' + (p.hand.length > 5 ? 0.86 : 0.8) + '">' +
        '<div class="slot-main">' + PM.slotBg('hand', false) + '<div class="slot-body" style="width:' + g.body + ';padding-top:' + g.pad + '">' + body + '</div></div>' +
        '<div class="pm-foot"><span>' + p.hand.length + ' card' + (p.hand.length === 1 ? '' : 's') + (locked ? ' · <span class="warn" data-tip="Academia hand limit (I2a)">auction-locked</span>' : '') + '</span>' +
        '<label class="toggle small"><input type="checkbox" data-pm="black" ' + (black ? 'checked' : '') + '> Black-Sides</label></div>' +
        (dnd ? '<div class="pm-targets">' +
          '<div class="pm-target dnd-zone" data-key="sell" data-tip="' + esc(av.freeMarket.ok ? 'Drop a Hand card: sell it for 3 Aquas (Free Market, your Operation)' : av.freeMarket.why || 'Operation already used') + '">💱 Sell 3</div>' +
          '<div class="pm-target dnd-zone" data-key="discard" data-tip="Drop a Hand card: discard it to the bottom of its deck (G6, free action)">🗑 Discard</div></div>' : '') +
        '</section>';
    },

    /* ---------------- input ---------------- */
    onClick(e) {
      const t = e.target.closest('[data-pm]');
      if (!t) return;
      const App = HF.app;
      const G = App.G;
      const D = UI.dialogs;
      const a = t.dataset.pm;
      if (a === 'close') return PM.close();
      if (a === 'player') { PM.pi = +t.dataset.p; return App.render(); }
      if (a === 'goto') { PM.close(); UI.board.centerOn(t.dataset.node); App.ui.selectedNode = t.dataset.node; return App.render(); }
      if (a === 'move') { PM.close(); return D.movePrep(); }
      if (a === 'engines') return D.engineBuilder(false);
      if (a === 'fuel') {
        const f = { load: D.loadFuel, ft2fuel: D.ftToFuel, fuel2ft: D.fuelToFt, jettison: D.jettisonFuel, fogg: D.fogg, swap: D.swap }[t.dataset.f];
        return f && f.call(D);
      }
      if (a === 'card') return D.cardActions(+t.dataset.p, t.dataset.key, +t.dataset.i);
      if (a === 'beads') return D.jettisonFts(t.dataset.key);
      if (a === 'hand') return PM.handActions(+t.dataset.p, +t.dataset.hi);
    },

    onChange(e) {
      if (e.target.dataset.pm === 'black') { HF.app.ui.handBlack = e.target.checked; HF.app.render(); }
    },

    onOver(e) {
      const t = e.target.closest('.pc');
      if (t && t !== PM.hovered) { PM.hovered = t; PM.preview(t); }
    },

    /** Hand card: both sides plus Boost / Sell / Discard. */
    handActions(pi, i) {
      const App = HF.app;
      const G = App.G;
      const p = G.players[pi];
      const id = p.hand[i];
      const w = HF.cards.sideStats(id, 'w'), b = HF.cards.sideStats(id, 'b');
      const mine = pi === G.current && !G.move && !G.pending && !G.gameOver;
      const av = UI.panels.opAvailability(G);
      const buttons = [{ label: 'Close' }];
      if (mine) {
        buttons.unshift({ label: '🗑 Discard (G6)', kind: 'danger', onClick: c => { c(); App.act(g => HF.actions.discardHand(g, i)); } });
        buttons.unshift({ label: '💱 Sell for 3 Aquas', disabled: !av.freeMarket.ok, onClick: c => { c(); App.act(g => HF.ops.sellHand(g, i)); } });
        buttons.unshift({ label: '🚀 Boost to LEO…', kind: 'primary', disabled: !av.boost.ok, onClick: c => { c(); UI.dialogs.op_boost([i]); } });
      }
      UI.modal({
        title: esc(w.name), wide: true, buttons,
        body: '<div class="card-pair">' + UI.cardFull(w) + UI.cardFull(b) + '</div>' +
          '<p class="hint">' + esc(p.name) + '’s Hand. Boost costs Aquas equal to the White-Side Mass (I4) and is your Operation; selling on the Free Market (I3) also uses the Operation; ' +
          'discarding (G6) is a free action.' + (mine && !av.boost.ok ? ' Boost: ' + esc(av.boost.why || '') : '') + '</p>'
      });
    },

    bind(el) {
      el.addEventListener('click', PM.onClick);
      el.addEventListener('change', PM.onChange);
      el.addEventListener('mouseover', PM.onOver);
    }
  };

  UI.playmat = PM;
})(window.HF = window.HF || {});
