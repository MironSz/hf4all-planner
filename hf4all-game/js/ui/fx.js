/*
 * Comic-book explosions on the map when cards are lost against their owner's
 * will (hazard roll, radiation, solar flare, glitch, pad explosion...): the
 * server lists them per command batch in events.booms.
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const MAP = HF.map;
  const S = HF.state;
  const esc = HF.escape;

  // Look by cause: the words shouted and the colours of the burst (outer → core).
  const STYLES = {
    fire: { words: ['KABOOM!', 'KA-BLAM!', 'BOOM!'], c: ['#e0221b', '#ff7a00', '#ffd600', '#fff6c2'], ink: '#ffe600', shadow: '#e0221b', bits: ['#e0221b', '#ff9f1c', '#c7cbd1', '#6b7280'] },
    rad: { words: ['ZAP!', 'FZZAP!'], c: ['#167a1d', '#5fd31f', '#e8ff3a', '#ffffff'], ink: '#d4ff3a', shadow: '#167a1d', bits: ['#5fd31f', '#e8ff3a', '#c7cbd1', '☢'] },
    flare: { words: ['FRZZT!', 'SIZZLE!'], c: ['#ff4d00', '#ffa200', '#fff07a', '#ffffff'], ink: '#fff07a', shadow: '#ff4d00', bits: ['#ffa200', '#fff07a', '#c7cbd1', '☀'] },
    glitch: { words: ['BZZT!', 'KRZZK!'], c: ['#1d4ed8', '#38bdf8', '#d6f3ff', '#ffffff'], ink: '#bff0ff', shadow: '#1d4ed8', bits: ['#38bdf8', '#d6f3ff', '#c7cbd1', '⚡'] }
  };
  const CAUSE = {
    hazard: ['fire', ['KABOOM!', 'KA-BLAM!', 'CRASH!']],
    'pad explosion': ['fire', ['BOOM!', 'KA-BOOM!']],
    'sail burn-up': ['fire', ['FWOOSH!']],
    'kreutz sungrazer': ['fire', ['WHOOSH!']],
    'radiation belt': ['rad', null],
    'Project Valkyrie radiation': ['rad', null],
    'solar flare': ['flare', null],
    glitch: ['glitch', null]
  };
  const DURATION = 1700;

  const pick = a => a[Math.floor(Math.random() * a.length)];
  const rnd = (a, b) => a + Math.random() * (b - a);

  /** A jagged starburst polygon (radius 100 around 0,0). */
  const burst = (spikes, outer, inner) => {
    const pts = [];
    for (let i = 0; i < spikes * 2; i++) {
      const r = i % 2 ? inner * rnd(0.85, 1.1) : outer * rnd(0.82, 1.08);
      const a = (i / (spikes * 2)) * Math.PI * 2 + rnd(-0.06, 0.06);
      pts.push((Math.cos(a) * r).toFixed(1) + ',' + (Math.sin(a) * r).toFixed(1));
    }
    return pts.join(' ');
  };

  const Fx = {
    /** Group the lost cards by stack and place, then blow each place up; done() when the show is over. */
    booms(list, done) {
      const G = HF.app.G;
      const groups = [];
      for (const b of list || []) {
        if (!b || !b.node || !MAP.node(b.node)) continue;
        let gr = groups.find(x => x.player === b.player && x.key === b.key && x.node === b.node);
        if (!gr) groups.push(gr = { player: b.player, key: b.key, node: b.node, why: b.why, cards: [] });
        gr.cards.push(b.card);
      }
      if (!groups.length || !UI.board.el) { if (done) done(); return; }
      // A single blast off-screen: bring it into view first.
      if (groups.length === 1 && !Fx.visible(groups[0].node)) UI.board.centerOn(groups[0].node);
      groups.forEach((gr, i) => setTimeout(() => Fx.boom(gr, G), i * 280));
      setTimeout(() => { if (done) done(); }, DURATION + (groups.length - 1) * 280);
    },

    layer() {
      const host = UI.board.el;
      let el = host.querySelector('.fx-layer');
      if (!el) { el = document.createElement('div'); el.className = 'fx-layer'; host.appendChild(el); }
      return el;
    },

    /** Screen position of a Space inside the board element. */
    where(node) {
      const n = MAP.node(node);
      const v = UI.board.view;
      const vr = UI.board.vp().getBoundingClientRect(), hr = UI.board.el.getBoundingClientRect();
      return { x: vr.left - hr.left + n.px * v.s + v.x, y: vr.top - hr.top + n.py * v.s + v.y, w: vr.width, h: vr.height - UI.board.padB() };
    },

    visible(node) {
      const p = Fx.where(node);
      return p.x > 40 && p.y > 40 && p.x < p.w - 40 && p.y < p.h - 40;
    },

    /** One explosion: flash, starburst, a shouted word, flying debris, smoke, a caption and a shaking map. */
    boom(gr, G) {
      const [styleId, words] = CAUSE[gr.why] || ['fire', null];
      const st = STYLES[styleId];
      const p = Fx.where(gr.node);
      const k = Math.min(1.8, 1 + 0.18 * (gr.cards.length - 1));
      const el = document.createElement('div');
      el.className = 'boom boom-' + styleId;
      el.style.left = p.x.toFixed(1) + 'px';
      el.style.top = p.y.toFixed(1) + 'px';
      el.style.setProperty('--k', k.toFixed(2));
      let h = '<i class="b-flash"></i>';
      for (let i = 0; i < 6; i++) {
        const a = rnd(0, Math.PI * 2), d = rnd(40, 95) * k;
        h += '<i class="b-smoke" style="--dx:' + (Math.cos(a) * d).toFixed(0) + 'px;--dy:' + (Math.sin(a) * d - 40).toFixed(0) + 'px;--sc:' + rnd(1.6, 2.6).toFixed(2) +
          ';animation-delay:' + rnd(0.08, 0.3).toFixed(2) + 's"></i>';
      }
      h += '<svg class="b-burst" viewBox="-110 -110 220 220">' +
        '<polygon points="' + burst(13, 104, 62) + '" fill="' + st.c[0] + '" stroke="#16130f" stroke-width="7" stroke-linejoin="round"/>' +
        '<polygon points="' + burst(11, 80, 48) + '" fill="' + st.c[1] + '"/>' +
        '<polygon points="' + burst(9, 56, 34) + '" fill="' + st.c[2] + '"/>' +
        '<circle r="20" fill="' + st.c[3] + '"/></svg>';
      for (let i = 0; i < 14; i++) {
        const a = rnd(0, Math.PI * 2), d = rnd(90, 190) * k;
        const bit = pick(st.bits);
        const style = '--dx:' + (Math.cos(a) * d).toFixed(0) + 'px;--dy:' + (Math.sin(a) * d + rnd(20, 60)).toFixed(0) + 'px;--rot:' + rnd(-540, 540).toFixed(0) + 'deg';
        h += bit.length === 1 ? '<i class="b-deb sym" style="' + style + '">' + bit + '</i>'
          : '<i class="b-deb" style="' + style + ';--c:' + bit + ';width:' + rnd(8, 18).toFixed(0) + 'px;height:' + rnd(6, 11).toFixed(0) + 'px"></i>';
      }
      h += '<b class="b-word" style="--ink:' + st.ink + ';--sh:' + st.shadow + '">' + pick(words || st.words) + '</b>';
      const who = G && G.players[gr.player];
      const names = gr.cards.length > 3 ? gr.cards.slice(0, 3).join(', ') + ' +' + (gr.cards.length - 3) : gr.cards.join(', ');
      h += '<span class="b-cap">' + (who ? UI.dot(who) + esc(who.name) + ' · ' : '') + esc(S.STACK_NAME[gr.key] || gr.key) + ': ' + esc(names) + ' lost</span>';
      el.innerHTML = h;
      Fx.layer().appendChild(el);
      const vp = UI.board.vp();
      vp.classList.remove('fx-shake');
      void vp.offsetWidth; // restart the shake
      vp.classList.add('fx-shake');
      setTimeout(() => { el.remove(); vp.classList.remove('fx-shake'); }, DURATION + 300);
    },

    /** Try it from the console: HF.ui.fx.demo('hazard'). */
    demo(why) {
      const G = HF.app.G;
      const p = G.players[G.current];
      const node = (p.stacks.rocket && p.stacks.rocket.node) || MAP.LEO;
      Fx.booms([{ player: p.idx, key: 'rocket', node, card: 'Demo Thruster', why: why || 'hazard' }, { player: p.idx, key: 'rocket', node, card: 'Demo Reactor', why: why || 'hazard' }]);
    }
  };

  UI.fx = Fx;
})(window.HF);
