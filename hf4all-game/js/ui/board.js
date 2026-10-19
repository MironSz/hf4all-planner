/*
 * The map: the printed board image with an SVG overlay in board pixels
 * (5400 x 3619). Pan with drag, zoom with the wheel / buttons.
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const MAP = HF.map;
  const S = HF.state;
  const esc = HF.escape;
  const W = MAP.W, H = MAP.H;

  const ROCKET = 'M0,-34 C11,-24 13,-8 13,8 L21,22 L9,19 L7,26 L-7,26 L-9,19 L-21,22 L-13,8 C-13,-8 -11,-24 0,-34 Z';
  const TYPE_COLOR = { burn: '#ff2fa0', hohmann: '#ffb070', lagrange: '#ffd27a', site: '#6cf', radhaz: '#ffe600', flyby: '#7cff8a', venus: '#7cff8a' };

  const B = {
    el: null, view: { s: 0.2, x: 0, y: 0 }, layers: {}, hover: null,

    mount(container) {
      container.innerHTML =
        '<div class="board-viewport">' +
        '<div class="board-world" style="width:' + W + 'px;height:' + H + 'px">' +
        '<img class="board-img" src="assets/board.jpg" width="' + W + '" height="' + H + '" alt="High Frontier 4 All map" draggable="false">' +
        '<svg class="board-svg" viewBox="0 0 ' + W + ' ' + H + '" width="' + W + '" height="' + H + '">' +
        '<g class="l-spaces"></g><g class="l-sites"></g><g class="l-chits"></g><g class="l-path"></g><g class="l-figs"></g>' +
        '<g class="l-opts"></g><g class="l-sel"></g><g class="l-hits"></g></svg>' +
        '</div></div>' +
        '<div class="board-tools">' +
        '<button class="icon-btn" data-z="in" data-tip="Zoom in">+</button>' +
        '<button class="icon-btn" data-z="out" data-tip="Zoom out">−</button>' +
        '<button class="icon-btn" data-z="fit" data-tip="Whole map">⤢</button>' +
        '<button class="icon-btn" data-z="me" data-tip="Center on the current player">◎</button>' +
        '<button class="icon-btn" data-z="earth" data-tip="Center on Earth / LEO">⊕</button>' +
        '<button class="icon-btn toggle" data-z="spaces" data-tip="Highlight all Spaces by type">∷</button>' +
        '</div>' +
        '<div class="board-legend"></div>';
      B.el = container;
      const svg = container.querySelector('.board-svg');
      ['spaces', 'sites', 'chits', 'path', 'figs', 'opts', 'sel', 'hits'].forEach(k => { B.layers[k] = svg.querySelector('.l-' + k); });
      B.drawHits();
      B.bindPanZoom();
      container.querySelector('.board-tools').addEventListener('click', e => {
        const b = e.target.closest('button');
        if (!b) return;
        const z = b.dataset.z;
        if (z === 'in') B.zoomBy(1.4);
        else if (z === 'out') B.zoomBy(1 / 1.4);
        else if (z === 'fit') B.fit();
        else if (z === 'me') B.centerOnPlayer();
        else if (z === 'earth') B.centerOn(MAP.LEO, 0.45);
        else if (z === 'spaces') { HF.app.ui.showSpaces = !HF.app.ui.showSpaces; b.classList.toggle('on', HF.app.ui.showSpaces); B.drawSpaces(); B.renderLegend(HF.app.G); }
      });
      requestAnimationFrame(() => { B.centerOn(MAP.LEO, 0.32); });
      window.addEventListener('resize', () => B.apply());
    },

    vp() { return B.el.querySelector('.board-viewport'); },

    /** Figures grow when zoomed out so they stay readable (1 at 0.45+, up to 2.2). */
    figScale() { return Math.max(1, Math.min(2.2, 0.45 / B.view.s)); },

    /** Height covered by the ship dock / movement HUD at the bottom of the board. */
    padB() {
      const main = B.el && B.el.closest('.main');
      return main ? parseFloat(getComputedStyle(main).getPropertyValue('--dock-h')) || 0 : 0;
    },

    /** Keep the board covering the viewport (no black void past its edges); the board may scroll up above the dock. */
    clamp() {
      const r = B.vp().getBoundingClientRect();
      const pad = B.padB();
      const v = B.view, w = W * v.s, h = H * v.s;
      v.x = w <= r.width ? (r.width - w) / 2 : Math.min(0, Math.max(r.width - w, v.x));
      v.y = h <= r.height - pad ? (r.height - pad - h) / 2 : Math.min(0, Math.max(r.height - pad - h, v.y));
    },

    apply() {
      B.clamp();
      const w = B.el.querySelector('.board-world');
      if (w) w.style.transform = 'translate(' + B.view.x + 'px,' + B.view.y + 'px) scale(' + B.view.s + ')';
      if (UI.minimap) { const r = B.vp().getBoundingClientRect(); UI.minimap.view(B.view, r.width, r.height); }
      clearTimeout(B.rescaleT);
      B.rescaleT = setTimeout(B.rescaleFigs, 120);
    },

    minScale() {
      const r = B.vp().getBoundingClientRect();
      return Math.min(r.width / W, r.height / H) * 0.9;
    },

    zoomAt(factor, cx, cy) {
      const v = B.view;
      const ns = Math.max(B.minScale(), Math.min(2.5, v.s * factor));
      v.x = cx - (cx - v.x) * (ns / v.s);
      v.y = cy - (cy - v.y) * (ns / v.s);
      v.s = ns;
      B.apply();
    },

    /** Re-draw the figures when their zoom-dependent size changed noticeably. */
    rescaleFigs() {
      const k = Math.round(B.figScale() * 10);
      if (k !== B.figK && HF.app.G) { B.figK = k; B.render(HF.app.G); }
    },

    zoomBy(f) { const r = B.vp().getBoundingClientRect(); B.zoomAt(f, r.width / 2, r.height / 2); },

    /** Pan by screen pixels (arrow keys): positive dx / dy show more of the right / bottom. */
    panBy(dx, dy) { B.view.x -= dx; B.view.y -= dy; B.apply(); },

    fit() {
      const r = B.vp().getBoundingClientRect();
      const s = Math.min(r.width / W, r.height / H);
      B.view = { s, x: (r.width - W * s) / 2, y: (r.height - H * s) / 2 };
      B.apply();
    },

    centerOn(nodeId, scale) {
      const n = MAP.node(nodeId);
      if (!n) return;
      const r = B.vp().getBoundingClientRect();
      const s = scale || Math.max(B.view.s, 0.35);
      B.view = { s, x: r.width / 2 - n.px * s, y: (r.height - B.padB()) / 2 - n.py * s };
      B.apply();
    },

    /** Center the view on a board-pixel point, keeping the zoom (minimap). */
    centerOnPoint(x, y) {
      const r = B.vp().getBoundingClientRect();
      const s = B.view.s;
      B.view = { s, x: r.width / 2 - x * s, y: (r.height - B.padB()) / 2 - y * s };
      B.apply();
    },

    centerOnPlayer() {
      const G = HF.app.G;
      if (!G) return;
      const p = G.players[G.current];
      const node = (G.move && G.move.node) || (p.stacks.rocket && p.stacks.rocket.node) || MAP.LEO;
      B.centerOn(node);
    },

    bindPanZoom() {
      const vp = B.vp();
      vp.addEventListener('wheel', e => {
        e.preventDefault();
        const r = vp.getBoundingClientRect();
        B.zoomAt(e.deltaY < 0 ? 1.18 : 1 / 1.18, e.clientX - r.left, e.clientY - r.top);
      }, { passive: false });
      let drag = null;
      vp.addEventListener('pointerdown', e => {
        if (e.button !== 0) return;
        drag = { x: e.clientX, y: e.clientY, vx: B.view.x, vy: B.view.y, moved: false, target: e.target };
      });
      window.addEventListener('pointermove', e => {
        if (drag) {
          const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
          if (!drag.moved && Math.hypot(dx, dy) > 5) { drag.moved = true; vp.classList.add('dragging'); }
          if (drag.moved) { B.view.x = drag.vx + dx; B.view.y = drag.vy + dy; B.apply(); UI.tip(null); }
        }
      });
      window.addEventListener('pointerup', e => {
        if (!drag) return;
        const d = drag;
        drag = null;
        vp.classList.remove('dragging');
        if (!d.moved) {
          const hit = d.target.closest && d.target.closest('[data-node]');
          if (hit) HF.app.onNodeClick(hit.getAttribute('data-node'));
          else HF.app.onNodeClick(null);
        }
      });
      const svg = B.el.querySelector('.board-svg');
      svg.addEventListener('mousemove', e => {
        const hit = e.target.closest && e.target.closest('[data-node]');
        const id = hit ? hit.getAttribute('data-node') : null;
        if (id !== B.hover) { B.hover = id; B.drawHover(); HF.app.hudPreview(id); }
        if (id) UI.tip(B.nodeTip(id), e.clientX, e.clientY);
        else UI.tip(null);
      });
      svg.addEventListener('mouseleave', () => { B.hover = null; B.drawHover(); HF.app.hudPreview(null); UI.tip(null); });
    },

    /** Invisible hit targets on every real Space. */
    drawHits() {
      let s = '';
      for (const id in MAP.nodes) {
        const n = MAP.nodes[id];
        if (n.t === 'decorative') continue;
        s += '<circle data-node="' + id + '" cx="' + n.px.toFixed(1) + '" cy="' + n.py.toFixed(1) + '" r="' + (n.t === 'site' ? 52 : 26) + '" class="hit"/>';
      }
      B.layers.hits.innerHTML = s;
    },

    drawSpaces() {
      if (!HF.app.ui.showSpaces) { B.layers.spaces.innerHTML = ''; return; }
      let s = '';
      for (const id in MAP.nodes) {
        const n = MAP.nodes[id];
        if (n.t === 'decorative') continue;
        const col = n.haz ? '#ff5555' : TYPE_COLOR[n.t] || '#fff';
        s += '<circle cx="' + n.px.toFixed(1) + '" cy="' + n.py.toFixed(1) + '" r="' + (n.t === 'site' ? 46 : 20) + '" fill="none" stroke="' + col + '" stroke-width="7" opacity="0.9"/>';
      }
      B.layers.spaces.innerHTML = s;
    },

    nodeTip(id) {
      const G = HF.app.G;
      const n = MAP.node(id);
      const z = HF.zone(n.z);
      let h = '<div class="tip-title">' + esc(MAP.name(id)) + '</div>';
      const kind = [];
      if (n.t === 'site') {
        h += '<div class="tip-site"><span class="sz">Size ' + n.size + '</span> <span class="sp">' + n.spec + ' ' + (HF_RULES.spectralTypes[n.spec] || '') + '</span> <span class="hy">' + '💧'.repeat(n.water) + (n.water ? '' : 'dry') + '</span></div>';
        if (n.atmo) kind.push('atmospheric');
        if (n.astro) kind.push('astrobiology');
        if (n.sub) kind.push('submarine');
        if (n.push) kind.push('push (Powersat Factory)');
        if (n.syn) kind.push('synodic comet - ' + n.syn + ' season');
      } else {
        if (n.t === 'hohmann') kind.push('Hohmann: turning here is a Pivot (2 burns)');
        if (n.t === 'burn') kind.push(n.land ? (n.land === 0.5 ? 'half lander burn' : 'lander burn') + ' - cannot stop' + (n.treq ? ', a powered landing below needs thrust ≥ ' + n.treq : '') : 'Lagrange burn (1 burn)');
        if (n.t === 'lagrange') kind.push('Lagrange: free turns');
        if (n.t === 'radhaz') kind.push('Radiation Belt: Belt Roll');
        if (n.fly) kind.push('flyby +' + n.fly + ' Bonus Burns' + (n.t === 'venus' ? ' (season blue only)' : ''));
        if (n.oberth) kind.push('Solar Oberth flyby');
      }
      if (n.haz) kind.push(n.haz === 'aero' ? 'Aerobrake Hazard' : 'Crash Hazard');
      if (MAP.synodic[id] && n.t !== 'site') kind.push('synodic gate (' + MAP.synodic[id] + ')');
      h += '<div class="tip-kind">' + kind.join(' · ') + '</div>';
      h += '<div class="tip-zone">' + z.symbol + ' ' + z.name + ' zone · solar ' + (z.solar == null ? 'n/a' : (z.solar > 0 ? '+' : '') + z.solar) + '</div>';
      if (G) {
        const site = G.sites[id];
        if (site && (site.claim != null || site.busted)) {
          if (site.busted) h += '<div class="tip-own bad">Busted</div>';
          else {
            const o = G.players[site.claim];
            h += '<div class="tip-own">' + UI.dot(o) + ' ' + esc(o.name) + ': ' + (site.colony ? 'Colony' : site.factory ? 'Factory' : 'Claim') + '</div>';
          }
        }
        const st = S.stacksAt(G, id);
        if (st.length) {
          h += '<div class="tip-stacks">' + st.map(x => {
            const p = G.players[x.p];
            return UI.dot(p) + ' ' + esc(p.name) + ' ' + S.STACK_NAME[x.key] + ' (' + x.stack.cards.length + ' cards' + (x.stack.fts ? ', ' + x.stack.fts + ' FT' : '') + ')';
          }).join('<br>') + '</div>';
        }
        const opt = HF.app.moveOptionFor && HF.app.moveOptionFor(id);
        if (opt) h += '<div class="tip-move ' + (opt.legal ? 'ok' : 'bad') + '">' + (opt.legal ? '<b>Move here:</b> ' + (opt.flags.join('; ') || 'free') : '<b>Cannot move here:</b> ' + esc(opt.reason)) + '</div>';
      }
      return h;
    },

    drawHover() {
      const id = B.hover;
      const sel = HF.app.ui.selectedNode;
      let s = '';
      if (sel && MAP.node(sel)) {
        const n = MAP.node(sel);
        s += '<circle cx="' + n.px + '" cy="' + n.py + '" r="' + (n.t === 'site' ? 70 : 40) + '" class="sel-ring"/>';
      }
      if (id) {
        const n = MAP.node(id);
        s += '<circle cx="' + n.px + '" cy="' + n.py + '" r="' + (n.t === 'site' ? 62 : 34) + '" class="hover-ring"/>';
      }
      B.layers.sel.innerHTML = s;
    },

    render(G) {
      if (!B.el) return;
      const colorOf = pi => S.color(G.players[pi]);
      const GX = UI.gfx;
      const img = (src, x, y, w, h, extra) => '<image href="' + src + '" x="' + (x - w / 2).toFixed(1) + '" y="' + (y - h / 2).toFixed(1) + '" width="' + w + '" height="' + h + '"' + (extra || '') + '/>';
      // A Claim with its Factory / Colony stacked on the disk, centred on (x, y) as one piece.
      const claimSvg = (site, x, y) => {
        const o = G.players[site.claim];
        if (GX.on()) {
          // The cube stands in the middle of the disk and the dome on the cube's top face,
          // as stacked on the table; the whole stack is centred on the Space.
          y += site.factory && site.colony ? 13 : site.factory ? 3 : 0;
          // The Vassal pieces are translucent: drawing each twice makes them read like solid wood on the map.
          const solid = (src, px, py, w, h) => img(src, px, py, w, h) + img(src, px, py, w, h);
          let g = '<g class="claim gfx">' + solid(GX.piece('claim', o), x, y, 48, 48);
          if (site.factory) g += solid(GX.piece('factory', o), x, y - 10, 36, 41);
          if (site.colony) g += solid(GX.piece('colony', o), x, y - (site.factory ? 33.5 : 8.5), 40, 33);
          return g + '</g>';
        }
        y += site.factory && site.colony ? 10 : site.factory ? 2 : 0;
        const c = colorOf(site.claim);
        let g = '<g class="claim"><ellipse cx="' + x + '" cy="' + (y + 8) + '" rx="24" ry="10" fill="#000" opacity=".5"/>' +
          '<ellipse cx="' + x + '" cy="' + y + '" rx="24" ry="12" fill="' + c + '" stroke="#111" stroke-width="4"/>';
        if (site.factory) g += '<rect x="' + (x - 15) + '" y="' + (y - 22) + '" width="30" height="30" rx="3" fill="' + c + '" stroke="#111" stroke-width="4"/>';
        if (site.colony) g += '<path d="M' + (x - 17) + ',' + (y + (site.factory ? -22 : 2)) + ' a17,17 0 0 1 34,0 z" fill="' + c + '" stroke="#111" stroke-width="4"/>';
        return g + '</g>';
      };
      // A Busted Site (I6b): a translucent red disk in both graphics modes (the module's disk is blue).
      const bustedSvg = (x, y) => '<g class="busted" data-tip="Busted"><circle cx="' + x + '" cy="' + y + '" r="23"/>' +
        '<circle class="busted-rim" cx="' + x + '" cy="' + y + '" r="15"/></g>';

      // Glory & heroism chits still on the board.
      let c = '';
      for (const z of HF_RULES.zones) {
        if (!G.glory[z.id]) continue;
        const gtip = esc(z.name + ' glory: 1 VP when taken by a Human, ' + z.gloryVp + ' VP if brought back to LEO');
        c += GX.on() ? '<g class="chit glory gfx" data-tip="' + gtip + '">' + img(GX.glory(z.id), z.glory[0], z.glory[1], 88, 88) + '</g>'
          : '<g class="chit glory" data-tip="' + gtip + '">' +
          '<circle cx="' + z.glory[0] + '" cy="' + z.glory[1] + '" r="40"/><text x="' + z.glory[0] + '" y="' + (z.glory[1] + 14) + '">' + z.gloryVp + '</text></g>';
      }
      if (G.config.heroism) {
        for (const h of HF_RULES.heroism) {
          if (G.heroism[h.id] !== null) continue;
          // The medal printed on the map is the chit: only its tooltip is added.
          c += '<circle class="hero-tip" cx="' + h.pos[0] + '" cy="' + h.pos[1] + '" r="58" data-tip="' + esc('Heroism: ' + h.name + ' (2 VP, awarded by vote)') + '"/>';
        }
      }
      B.layers.chits.innerHTML = c;

      // Everything standing on a Space (a Claim with its Factory / Colony, a Busted marker, Rockets,
      // Outposts) is drawn exactly on the Space's position from the planner map (its hex centre);
      // only when several share a Space are they spread around it.
      const byNode = {};
      const put = (id, it) => (byNode[id] = byNode[id] || []).push(it);
      for (const id in G.sites) {
        const site = G.sites[id];
        if (site.claim != null) put(id, { k: 'claim', site });
        if (site.busted) put(id, { k: 'busted', site });
      }
      G.players.forEach((p, pi) => {
        for (const k of ['rocket', 'out1', 'out2']) {
          const st = p.stacks[k];
          if (st) put(st.node, { pi, k, st });
        }
      });
      let s = '', f = '';
      for (const id in byNode) {
        const n = MAP.node(id);
        const list = byNode[id];
        const many = list.length;
        const wide = list.some(it => it.k !== 'rocket');  // Outposts and Claims are wider than a Rocket
        const rr = (many === 2 ? (wide ? 40 : 26) : (wide ? 46 : 32)) * B.figScale();
        list.forEach((it, i) => {
          let x = n.px, y = n.py;
          if (many > 1) {
            // Two side by side; three or more in a ring starting at the top.
            const ang = many === 2 ? (i === 0 ? Math.PI : 0) : (-Math.PI / 2 + i * 2 * Math.PI / many);
            x += Math.cos(ang) * rr;
            y += Math.sin(ang) * rr;
          }
          if (it.k === 'claim') { s += claimSvg(it.site, x, y); return; }
          if (it.k === 'busted') { s += bustedSvg(x, y); return; }
          const col = colorOf(it.pi);
          const cur = it.pi === G.current ? ' current' : '';
          const p = G.players[it.pi];
          const tip = esc(p.name + ' - ' + S.STACK_NAME[it.k] + ': ' + it.st.cards.map(cc => HF.cards.stats(cc).name).join(', ') + (it.st.fts ? ' + ' + it.st.fts + ' FT' : '') + (it.st.glitch ? ' [GLITCH]' : ''));
          const ini = esc((p.name.trim()[0] || '?').toUpperCase());
          const badge = (bx, by) => '<circle cx="' + bx + '" cy="' + by + '" r="11" fill="' + col + '" stroke="#111" stroke-width="3"/><text x="' + bx + '" y="' + (by + 6) + '" class="fig-ini sm">' + ini + '</text>';
          if (GX.on()) {
            const glitch = it.st.glitch ? '<circle cx="' + (it.k === 'rocket' ? 14 : 16) + '" cy="' + (it.k === 'rocket' ? -22 : -30) + '" r="9" class="glitch-dot"/>' : '';
            // The rocket image has empty space under the figure (visible part: the top 137 of 190 px);
            // y 10.9 puts the middle of the visible figure on the Space, the initial badge on its foot.
            f += it.k === 'rocket'
              ? '<g class="fig rocket gfx' + cur + '" transform="translate(' + x.toFixed(1) + ',' + y.toFixed(1) + ') scale(' + (1.25 * B.figScale()).toFixed(3) + ')" data-tip="' + tip + '" data-node="' + id + '">' +
                (cur ? '<circle r="44" class="fig-halo" stroke="' + col + '"/>' : '') + img(GX.piece('rocket', p), 0, 10.9, 22, 78) + badge(14, 22) + glitch + '</g>'
              : '<g class="fig outpost gfx' + cur + '" transform="translate(' + x.toFixed(1) + ',' + y.toFixed(1) + ') scale(' + B.figScale().toFixed(3) + ')" data-tip="' + tip + '" data-node="' + id + '">' +
                img(GX.piece(it.k === 'out1' ? 'outpost1' : 'outpost2', p), 0, 0, 66, 30) + badge(36, -14) + glitch + '</g>';
          } else if (it.k === 'rocket') {
            f += '<g class="fig rocket' + cur + '" transform="translate(' + x.toFixed(1) + ',' + y.toFixed(1) + ') scale(' + (1.25 * B.figScale()).toFixed(3) + ')" data-tip="' + tip + '" data-node="' + id + '">' +
              (cur ? '<circle r="44" class="fig-halo" stroke="' + col + '"/>' : '') +
              '<path d="' + ROCKET + '" fill="' + col + '" stroke="#111" stroke-width="3.5"/>' +
              '<text y="8" class="fig-ini">' + ini + '</text>' + (it.st.glitch ? '<circle cx="14" cy="-24" r="9" class="glitch-dot"/>' : '') + '</g>';
          } else {
            f += '<g class="fig outpost' + cur + '" transform="translate(' + x.toFixed(1) + ',' + y.toFixed(1) + ') scale(' + B.figScale().toFixed(3) + ')" data-tip="' + tip + '" data-node="' + id + '">' +
              '<rect x="-26" y="-16" width="52" height="32" rx="5" fill="' + col + '" stroke="#111" stroke-width="4"/>' +
              '<text x="0" y="9" class="fig-num">' + ini + it.k.slice(-1) + '</text>' + (it.st.glitch ? '<circle cx="24" cy="-14" r="8" class="glitch-dot"/>' : '') + '</g>';
          }
        });
      }
      B.layers.sites.innerHTML = s;
      B.layers.figs.innerHTML = f;
      B.renderMove(G);
      B.drawHover();
      B.renderLegend(G);
    },

    renderMove(G) {
      let path = '', opts = '';
      if (G.move) {
        const col = S.color(G.players[G.current]);
        for (const r of G.move.routes) {
          const pts = [r.from].concat(r.via, [r.to]).map(id => MAP.node(id).px.toFixed(1) + ',' + MAP.node(id).py.toFixed(1)).join(' ');
          path += '<polyline points="' + pts + '" class="move-path" stroke="' + col + '"/>';
        }
        const start = MAP.node(G.move.start);
        path += '<circle cx="' + start.px + '" cy="' + start.py + '" r="18" class="move-start" stroke="' + col + '"/>';
        // Step numbers on the Spaces already entered.
        G.move.routes.forEach((r, i) => {
          const n = MAP.node(r.to);
          path += '<g class="step-no" transform="translate(' + n.px.toFixed(1) + ',' + n.py.toFixed(1) + ')"><circle r="19" fill="' + col + '"/><text y="8">' + (i + 1) + '</text></g>';
        });
        for (const o of HF.movement.options(G)) {
          const n = MAP.node(o.to);
          const r = n.t === 'site' ? 60 : 32;
          if (o.legal) {
            const pts = [o.route.from].concat(o.route.via, [o.route.to]).map(id => MAP.node(id).px.toFixed(1) + ',' + MAP.node(id).py.toFixed(1)).join(' ');
            opts += '<polyline points="' + pts + '" class="opt-route"/>';
            opts += '<circle cx="' + n.px + '" cy="' + n.py + '" r="' + r + '" class="opt-ring' + (o.cantStop ? ' nostop' : '') + (o.hazard || n.t === 'radhaz' ? ' risky' : '') + '" data-node="' + o.to + '"/>';
            const badge = B.optBadge(o);
            if (badge) {
              opts += '<g class="opt-badge" transform="translate(' + (n.px + r * 0.75) + ',' + (n.py - r * 0.75) + ')"><rect x="-4" y="-26" width="' + (badge.length * 17 + 16) + '" height="36" rx="10"/><text x="4" y="0">' + esc(badge) + '</text></g>';
            }
          } else {
            opts += '<circle cx="' + n.px + '" cy="' + n.py + '" r="' + (r * 0.6) + '" class="opt-bad" data-node="' + o.to + '"/>';
          }
        }
      }
      B.layers.path.innerHTML = path;
      B.layers.opts.innerHTML = opts;
    },

    optBadge(o) {
      const parts = [];
      if (o.burns) parts.push(o.burns + 'B');
      if (o.bonusUsed) parts.push(o.bonusUsed + 'b');
      if (o.pivotUsed) parts.push('✦');
      if (o.hazard) parts.push('☠');
      const n = MAP.node(o.to);
      if (n.t === 'radhaz') parts.push('☢');
      if (n.fly && (n.t !== 'venus' || HF.state.season(HF.app.G) === 'blue')) parts.push('+' + n.fly);
      if (o.endsMove) parts.push('⬇');
      return parts.join(' ');
    },

    renderLegend(G) {
      const el = B.el.querySelector('.board-legend');
      if (!G.move && HF.app.ui.showSpaces) {
        el.style.display = 'block';
        el.innerHTML = '<b>Spaces</b>: ' + [['#ff2fa0', 'Burn / lander'], ['#ffb070', 'Hohmann (pivot = 2 burns)'], ['#ffd27a', 'Lagrange (free turns)'], ['#ff5555', 'Hazard'],
          ['#ffe600', 'Radiation belt'], ['#7cff8a', 'Flyby'], ['#6cf', 'Site']].map(([c, l]) => '<span class="lg" style="border-color:' + c + '"></span> ' + l).join(' · ');
        return;
      }
      if (!G.move) { el.innerHTML = ''; el.style.display = 'none'; return; }
      el.style.display = 'block';
      el.innerHTML = '<b>Move options</b>: <span class="lg ok"></span> legal · <span class="lg nostop"></span> lander (no stop) · <span class="lg risky"></span> roll · badges: <b>B</b> burns, <b>b</b> bonus burns, ✦ bonus pivot, ☠ hazard, ☢ belt, +n flyby, ⬇ landing';
    }
  };

  UI.board = B;
})(window.HF = window.HF || {});
