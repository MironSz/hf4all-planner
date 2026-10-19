/* Dialogs for movement, cargo, operations, events and game end. */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const S = HF.state;
  const MAP = HF.map;
  const O = HF.ops;
  const A = HF.actions;
  const Mv = HF.movement;
  const FS = HF.FS;
  const esc = HF.escape;

  const act = (fn, after) => HF.app.act(fn, after);
  const G = () => HF.app.G;
  const me = () => G().players[G().current];
  const sel = (root, s) => root.querySelector(s);

  const D = {
    /* ================= Movement ================= */
    /**
     * Prepare movement (H2): only the player's engines - the ones built in the
     * engine builder and the thrust triangles that need no supports.
     */
    movePrep() {
      const g = G();
      const p = me();
      const r = p.stacks.rocket;
      if (!r) return UI.toast('You have no Rocket.', 'bad');
      const engines = Mv.engines(g);
      const an = { st: r.cards.map(HF.cards.stats) };
      const V = HF.app.V || {};
      const others = g.players.filter(q => q.idx !== p.idx && (V.powersat || [])[q.idx]);
      const keyOf = e => e.id || ('card' + e.idx);
      const state = { pick: null, ab: {}, push: false };
      // A negotiated push changes the numbers: those previews are asked from the server.
      const pushed = {};
      const planOf = e => {
        if (!state.push) return state.ab[keyOf(e)] && e.planAb ? e.planAb : e.plan;
        return pushed[keyOf(e) + (state.ab[keyOf(e)] ? '+ab' : '')] || null;
      };
      let root = null;
      const fetchPushed = () => {
        if (!state.push) return;
        const need = engines.filter(e => !planOf(e));
        Promise.all(need.map(e => {
          const k = keyOf(e) + (state.ab[keyOf(e)] ? '+ab' : '');
          return HF.app.query('movement.plan', [e.idx, !!state.ab[keyOf(e)], { push: true, chain: e.chain || null }]).then(pl => { pushed[k] = pl; });
        })).then(() => { if (root && root.isConnected) draw(); }).catch(err => UI.toast(err.message, 'bad'));
      };
      const unbuilt = an.st.map((s, i) => ({ s, i })).filter(x => Mv.needsSupports(x.s) && !engines.some(e => e.idx === x.i));
      const draw = () => {
        const rows = engines.map(e => {
          const k = keyOf(e);
          const pl = planOf(e) || e.plan;
          const ready = !!planOf(e);
          if (state.pick == null && pl.ok && ready) state.pick = k;
          return '<label class="thr-row' + (pl.ok ? '' : ' bad') + (state.pick === k ? ' on' : '') + (ready ? '' : ' plan-loading') + '">' +
            '<input type="radio" name="thr" value="' + k + '" ' + (state.pick === k ? 'checked' : '') + (pl.ok && ready ? '' : ' disabled') + '>' +
            '<div class="thr-card">' + UI.thrustSvg(an.st[e.idx].thrust, 46) + '</div>' +
            '<div class="thr-info"><div class="thr-name">' + esc(pl.name) + (pl.chainNames && pl.chainNames.length ? ' <span class="muted">+ ' + esc(pl.chainNames.join(' + ')) + '</span>' : '') +
            (e.simple ? ' <span class="tag" data-tip="Needs no generator or reactor: always available">no supports</span>'
              : ' <button type="button" class="icon-btn eng-del" data-del="' + e.id + '" data-tip="Delete this engine">🗑</button>') + '</div>' +
            '<table class="breakdown">' + pl.parts.map(x => '<tr><td>' + esc(x.label) + '</td><td>' + (x.value > 0 ? '+' : '') + x.value + '</td></tr>').join('') +
            '<tr class="tot"><td>Net thrust (burn limit)</td><td>' + pl.net + '</td></tr></table>' +
            '<div class="thr-foot">Fuel ' + HF.fmtNum(pl.fuelCons) + ' steps/burn' + (pl.fuelMul !== 1 ? ' (×' + HF.fmtNum(pl.fuelMul) + ')' : '') + ' · ' + pl.fuelAvail + ' steps available' +
            (pl.fuelCons > 0 ? ' ≈ ' + Math.floor(pl.fuelAvail / pl.fuelCons) + ' burns' : '') + (pl.gradeOk ? '' : ' · <span class="bad">fuel grade too low</span>') +
            (pl.thermsNeeded ? ' · cooling ' + pl.thermsNeeded + '/' + pl.thermsHave + ' ❄' : '') +
            (pl.coastOnly ? ' · <span class="warn">net &lt; 1: coasting only</span>' : '') + (pl.pivots ? ' · ' + pl.pivots + ' bonus pivot' : '') + '</div>' +
            (pl.canAfterburn ? '<label class="ab"><input type="checkbox" data-ab="' + k + '" ' + (state.ab[k] ? 'checked' : '') + '> Afterburn (+1 thrust for ' + pl.abSteps + ' fuel steps, +1 Therm)</label>' : '') +
            (pl.ok ? '' : '<div class="why">✖ ' + esc(pl.reason) + '</div>') + '</div></label>';
        }).join('');
        root.querySelector('.modal-body').innerHTML = '<p class="hint">Choose the engine for this move (H2). Net thrust is fixed for the whole move (H3). ' +
          'Build engines - a thrust triangle with its chosen supports (J1c) - in the engine builder; they stay with the Rocket.</p>' +
          (rows || '<p class="warn">No engine yet: build one from the thrust-triangle cards in your Rocket.</p>') +
          // Right under the list: build another engine from the cards in the Rocket.
          '<div class="eng-new"><button type="button" class="btn sm" data-build data-tip="Engine builder: a thrust triangle with the supports you choose (J1c)">🔧 Construct new engine</button>' +
          (unbuilt.length ? '<span class="hint">Not built yet: ' + unbuilt.map(x => esc(x.s.name)).join(', ') + '</span>' : '') + '</div>' +
          (others.length ? '<label class="chk perm"><input type="checkbox" data-push ' + (state.push ? 'checked' : '') + '><span>' + others.map(q => UI.dot(q) + '<b>' + esc(q.name) + '</b>').join(' / ') +
            ' <b>gives permission</b>: a negotiated push from ' + (others.length > 1 ? 'a' : 'their') + ' Powersat (N4a)</span></label>' : '');
      };
      UI.modal({
        title: 'Prepare movement', wide: true, body: '',
        buttons: [{ label: 'Cancel' }, {
          label: 'Start movement ▸', kind: 'primary', onClick: (c) => {
            const e = engines.find(x => keyOf(x) === state.pick);
            if (!e) return UI.toast('Choose an engine that can fire.', 'bad');
            const ab = !!state.ab[state.pick];
            c();
            act(g2 => (e.simple ? Mv.start(g2, e.idx, ab, { push: state.push }) : Mv.startEngine(g2, e.id, ab, { push: state.push })), () => UI.board.centerOnPlayer());
          }
        }],
        onMount(r0, close) {
          root = r0;
          draw();
          root.addEventListener('click', e => {
            if (e.target.closest('[data-build]')) { e.preventDefault(); close(); return D.engineBuilder(true); }
            const b = e.target.closest('[data-del]');
            if (!b) return;
            e.preventDefault();
            close();
            act(g2 => Mv.deleteEngine(g2, b.dataset.del), () => D.movePrep());
          });
          root.addEventListener('change', e => {
            const t = e.target;
            if (t.name === 'thr') state.pick = t.value;
            if (t.dataset.ab != null) state.ab[t.dataset.ab] = t.checked;
            if (t.hasAttribute('data-push')) state.push = t.checked;
            const e0 = engines.find(x => keyOf(x) === state.pick);
            if (e0 && !(planOf(e0) && planOf(e0).ok)) state.pick = null;
            draw();
            fetchPushed();
          });
        }
      });
    },

    /**
     * Engine builder (J1c): pick a thrust-triangle card of the Rocket and, one by
     * one, the support for each of its requirements; the saved engine stays with the
     * Rocket until one of its cards leaves it. `back`: return to Prepare movement.
     */
    engineBuilder(back) {
      const g = G();
      const p = me();
      const r = p.stacks.rocket;
      if (!r) return UI.toast('You have no Rocket.', 'bad');
      const an = { st: r.cards.map(HF.cards.stats) };
      const cands = an.st.map((s, i) => ({ s, i })).filter(x => Mv.needsSupports(x.s));
      const simple = an.st.filter(s => s.thrust && !Mv.needsSupports(s)).map(s => s.name);
      const built = Mv.engines(g).filter(e => !e.simple);
      const solar = HF.zone(MAP.zoneOf(r.node)).solar;
      const state = { pick: cands.length ? cands[0].i : null, chain: {}, plan: null, seq: 0 };
      let root = null;
      const complete = pl => pl && pl.slots && pl.slots.every(sl => sl.chosen && !sl.invalid && sl.pick >= 0);
      const dup = pl => complete(pl) && built.some(e => e.idx === state.pick && e.chain && Object.keys(e.chain).length === pl.slots.length &&
        pl.slots.every(sl => e.chain[sl.key] === sl.pick));
      const fetch = () => {
        if (state.pick == null) return draw();
        const seq = ++state.seq;
        HF.app.query('movement.plan', [state.pick, false, { chain: state.chain }]).then(pl => {
          if (seq !== state.seq) return;
          // A requirement only one card can meet needs no click.
          const auto = (pl.slots || []).filter(sl => !sl.chosen && sl.options.length === 1);
          if (auto.length) { auto.forEach(sl => { state.chain[sl.key] = sl.options[0]; }); return fetch(); }
          state.plan = pl;
          if (root && root.isConnected) draw();
        }).catch(err => UI.toast(err.message, 'bad'));
      };
      const draw = () => {
        const pl = state.plan;
        let h = '<p class="hint">An engine is a thrust triangle with the supports you choose for it (J1c): one card per requirement, ' +
          'and the supports of those supports. It stays with the Rocket, ready in Prepare movement, until one of its cards leaves the Rocket.</p>';
        if (simple.length) h += '<p class="hint">Always available, nothing to build: ' + simple.map(esc).join(', ') + '.</p>';
        if (built.length) h += '<p class="hint">Built: ' + built.map(e => esc(e.name)).join('; ') + '.</p>';
        if (!cands.length) {
          h += '<p class="warn">No card in your Rocket needs supports.</p>';
        } else {
          h += '<div class="eb-cards">' + cands.map(x => '<label class="opt-row"><input type="radio" name="ebt" value="' + x.i + '"' + (x.i === state.pick ? ' checked' : '') + '> ' +
            UI.thrustSvg(x.s.thrust, 30) + ' <b>' + esc(x.s.name) + '</b></label>').join('') + '</div>';
          if (!pl) h += '<p class="muted">Loading…</p>';
          else {
            h += D.chainPicker(an, state.pick, pl.slots, solar, true) +
              '<table class="breakdown">' + pl.parts.map(x => '<tr><td>' + esc(x.label) + '</td><td>' + (x.value > 0 ? '+' : '') + x.value + '</td></tr>').join('') +
              '<tr class="tot"><td>Net thrust now (burn limit)</td><td>' + pl.net + '</td></tr></table>' +
              '<div class="thr-foot">Fuel ' + HF.fmtNum(pl.fuelCons) + ' steps/burn' + (pl.fuelMul !== 1 ? ' (×' + HF.fmtNum(pl.fuelMul) + ')' : '') +
              (pl.thermsNeeded ? ' · cooling ' + pl.thermsNeeded + '/' + pl.thermsHave + ' ❄' : '') + '</div>' +
              (dup(pl) ? '<p class="hint">✓ This engine is already built.</p>' : complete(pl) ? '' : '<p class="warn">Choose a support for every requirement to save the engine.</p>');
          }
        }
        root.querySelector('.modal-body').innerHTML = h;
        const save = root.querySelector('.eb-save');
        if (save) save.disabled = !complete(pl) || dup(pl);
      };
      UI.modal({
        title: '🔧 Engine builder', wide: true, body: '',
        buttons: [{ label: back ? '◂ Back' : 'Close', onClick: c => { c(); if (back) D.movePrep(); } }, {
          label: 'Save engine', kind: 'primary eb-save', onClick: (c) => {
            if (!complete(state.plan)) return UI.toast('Choose a support for every requirement (J1c).', 'bad');
            if (dup(state.plan)) return UI.toast('That engine is already built.', 'bad');
            const i = state.pick, chain = Object.assign({}, state.chain);
            c();
            act(g2 => Mv.saveEngine(g2, i, chain), () => (back ? D.movePrep() : D.engineBuilder(false)));
          }
        }],
        onMount(r0) {
          root = r0;
          draw();
          fetch();
          root.addEventListener('change', e => {
            const t = e.target;
            if (t.name === 'ebt') { state.pick = +t.value; state.chain = {}; state.plan = null; draw(); fetch(); }
            if (t.dataset.slot != null) {
              if (t.value === '') delete state.chain[t.dataset.slot];
              else state.chain[t.dataset.slot] = +t.value;
              // Supports further down the chain depend on this choice: choose them again.
              const sl = (state.plan.slots || []).find(x => x.key === t.dataset.slot);
              if (sl) (state.plan.slots || []).filter(x => x.depth > sl.depth).forEach(x => { delete state.chain[x.key]; });
              fetch();
            }
          });
        }
      });
    },

    /** The support chain of thruster i as one picker per requirement (J1b, J1c). */
    chainPicker(an, i, slots, solar, explicit) {
      if (!slots || !slots.length) return '';
      const note = (j) => {
        const s = an.st[j], m = s.mod || {}, req = s.req || {};
        const bits = [];
        if (m.thrust) bits.push((m.thrust > 0 ? '+' : '') + m.thrust + ' thrust');
        if (m.solar || s.solar) bits.push(solar == null ? 'solar: dead here' : 'solar ' + (solar > 0 ? '+' : '') + solar);
        if (m.fuelMul) bits.push('fuel ×' + HF.fmtNum(m.fuelMul));
        if (req.therms) bits.push('❄' + req.therms);
        if ((req.gen || []).length) bits.push('needs ' + req.gen.join('/') + ' gen.');
        if ((req.reactor || []).length) bits.push('needs ' + req.reactor.join('/') + ' reactor');
        return s.name + (bits.length ? ' (' + bits.join(', ') + ')' : '');
      };
      return '<div class="chain"><div class="chain-h">Support chain (J1c)</div>' + slots.map(sl => {
        const what = esc(an.st[sl.k].name) + ' needs a ' + (sl.type === 'gen' ? 'generator' : 'reactor') + ': ';
        let pick;
        if (sl.pick < 0 || !sl.options.length) pick = '<span class="bad">none Operational</span>';
        else if (sl.options.length === 1 && (!explicit || sl.chosen)) pick = '<b>' + esc(note(sl.options[0])) + '</b>';
        else {
          pick = '<select data-chain="' + i + '" data-slot="' + sl.key + '"' + (explicit && !sl.chosen ? ' class="eb-open"' : '') + '>' +
            (explicit && !sl.chosen ? '<option value="" selected>- choose -</option>' : '') + sl.options.map(j =>
            '<option value="' + j + '"' + (j === sl.pick && (!explicit || sl.chosen) ? ' selected' : '') + '>' + esc(note(j)) + '</option>').join('') + '</select>';
        }
        return '<div class="chain-slot" style="--d:' + sl.depth + '">' + what + pick + '</div>';
      }).join('') + '</div>';
    },

    /**
     * Click on a move option: an opponent's Factory asks for permission or a
     * hijack, hazards ask about FINAO first.
     */
    stepTo(nodeId) {
      const g = G();
      const opt = Mv.options(g).find(o => o.to === nodeId);
      if (!opt) return false;
      if (!opt.legal) { UI.toast('Cannot move to ' + MAP.name(nodeId) + ': ' + opt.reason, 'bad'); return true; }
      const needsRoll = opt.hazard || ((opt.factoryLiftoff || opt.factoryLanding) && opt.hazardRoll);
      const owner = opt.factoryOwner != null ? g.players[opt.factoryOwner] : null;
      if (!needsRoll && !owner) { act(g2 => Mv.step(g2, nodeId, {})); return true; }
      // null when the opponent's Factory is used without a ticked permission or hijack.
      const choices = (w, finao) => {
        const c = { finao };
        if (owner) {
          const use = D.permUse(w, 'fu', 0);
          if (!use) { UI.toast('Tick “' + owner.name + ' gives permission” (or a Factory Hijack).', 'bad'); return null; }
          c[use] = true;
        }
        return c;
      };
      const factory = owner ? '<div class="fac-use"><p>This factory-assisted ' + (opt.factoryLanding ? 'landing' : 'liftoff') + ' uses <b>' + esc(owner.name) + '’s Factory</b> on ' +
        esc(MAP.name(opt.factoryLanding ? nodeId : g.move.node)) + ' (H6c).</p>' +
        D.permBox(opt, 'fu', 0, 'N5', ' - agree any price in the 🤝 Deal screen') + '</div>' : '';
      if (!needsRoll) {
        UI.modal({
          title: '🏭 Opponent’s Factory', body: factory,
          buttons: [{ label: 'Cancel' }, { label: 'Move ▸', kind: 'primary', onClick: (c, w) => { const ch = choices(w, false); if (!ch) return; c(); act(g2 => Mv.step(g2, nodeId, ch)); } }]
        });
        return true;
      }
      const cost = A.finaoCost(g, me());
      const what = opt.hazard ? (opt.hazard === 'aero' ? 'an Aerobrake Hazard' : 'a Crash Hazard') : 'a factory-assisted ' + (opt.factoryLanding ? 'landing' : 'liftoff');
      UI.modal({
        title: '⚠ Hazard Roll (H7)',
        body: factory + '<p>Moving to <b>' + esc(MAP.name(nodeId)) + '</b> means ' + what + '. Roll 1d6: on a <b>1</b> every card and FT in your Rocket is lost.</p>' +
          (opt.flags.includes('Sails burn up!') ? '<p class="warn">Your Sails are decommissioned on entering the aerobrake regardless of the roll.</p>' : '') +
          '<p>You may instead pay <b>FINAO</b> (' + cost + ' Aquas, you have ' + me().aquas + ') to succeed automatically.</p>',
        buttons: [{ label: 'Cancel' },
          { label: 'Pay FINAO (' + cost + ')', disabled: me().aquas < cost, onClick: (c, w) => { const ch = choices(w, true); if (!ch) return; c(); act(g2 => Mv.step(g2, nodeId, ch)); } },
          { label: 'Roll the die', kind: 'primary', onClick: (c, w) => { const ch = choices(w, false); if (!ch) return; c(); act(g2 => Mv.step(g2, nodeId, ch)); } }]
      });
      return true;
    },

    /**
     * Using an opponent's Factory (o.owner / o.factoryOwner): the owner's permission is an
     * orange tick box, unticked every time; the Factory Hijack (Felony) is the alternative.
     */
    permBox(o, cls, i, ref, note) {
      const ownerIdx = o.owner != null ? o.owner : o.factoryOwner;
      if (ownerIdx == null) return '';
      const owner = G().players[ownerIdx];
      return '<div class="perm-box" data-i="' + i + '">' +
        '<label class="chk perm"><input type="checkbox" class="' + cls + '-perm" data-i="' + i + '"><span>' + UI.dot(owner) + '<b>' + esc(owner.name) + ' gives permission</b> (' + ref + ')' + (note || '') + '</span></label>' +
        '<label class="chk' + (o.hijack.ok ? '' : ' off') + '"><input type="checkbox" class="' + cls + '-hijack" data-i="' + i + '"' + (o.hijack.ok ? '' : ' disabled') + '> ☠ Factory Hijack instead (Felony, no permission)' +
        (o.hijack.ok ? '' : ' <span class="muted">- ' + esc(o.hijack.reason) + '</span>') + '</label></div>';
    },

    /** 'permission' / 'hijack' as ticked in a permBox, or null. */
    permUse(w, cls, i) {
      const on = k => { const x = w.querySelector('.' + cls + '-' + k + '[data-i="' + i + '"]'); return !!(x && x.checked); };
      return on('perm') ? 'permission' : on('hijack') ? 'hijack' : null;
    },

    /** An operation option at an opponent's Factory without a ticked permission or hijack: warn and stay. */
    permMissing(o, use) {
      if (o.owner == null || use) return false;
      UI.toast('Tick “' + G().players[o.owner].name + ' gives permission” (or a Factory Hijack).', 'bad');
      return true;
    },

    /** Murder/Suicide (Felony, once per Turn). */
    murder() {
      const g = G();
      const opts = A.murderOptions(g);
      if (!opts.length) return UI.toast('No Human under your control to decommission.', 'bad');
      UI.modal({
        title: '☠ Murder / Suicide (Felony)', wide: true,
        body: '<p>Once per Turn, a Felony lets you voluntarily decommission a Human under your control: it returns to its owner’s LEO stack (E7c). ' +
          'A glory chit it carries goes to the owner’s playmat low side up. Suicide is the same as Murder except no other Human needs to be present.</p>' +
          opts.map((o, i) => {
            const q = g.players[o.pi];
            const card = q.stacks[o.key].cards[o.idx];
            return '<label class="opt-row' + (o.ok ? '' : ' bad') + '"><input type="radio" name="mu" value="' + i + '"' + (o.ok ? '' : ' disabled') + (o.ok && !opts.slice(0, i).some(x => x.ok) ? ' checked' : '') + '> ' +
              '<b>' + o.kind + ':</b> ' + UI.cardMini(card) + ' in ' + esc(q.name) + '’s ' + S.STACK_NAME[o.key] + ' at ' + esc(MAP.name(o.node)) +
              (o.ok ? '' : ' <span class="muted">- ' + esc(o.reason) + '</span>') + '</label>';
          }).join(''),
        buttons: [{ label: 'Cancel' }, { label: 'Commit the Felony', kind: 'danger', onClick: (c, w) => {
          const r = sel(w, 'input[name=mu]:checked');
          if (!r) return UI.toast('Nothing possible.', 'bad');
          const o = opts[+r.value];
          c(); act(g2 => A.murder(g2, o.pi, o.key, o.idx));
        } }]
      });
    },

    /* ================= Cargo ================= */
    transfer(fromKey, preselect) {
      const g = G();
      const p = me();
      const src = p.stacks[fromKey];
      const node = S.stackNode(p, fromKey);
      const targets = S.STACKS.filter(k => k !== fromKey && ((p.stacks[k] && S.stackNode(p, k) === node) || (!p.stacks[k] && k !== 'leo')));
      if (!targets.length) return UI.toast('No other stack here and no free Rocket/Outpost slot.', 'bad');
      const ftsMax = S.stackFts(p, fromKey);
      const body = '<div class="form-row"><label>Destination</label><select class="t-to">' + targets.map(k =>
        '<option value="' + k + '">' + S.STACK_NAME[k] + (p.stacks[k] ? '' : ' (create here)') + '</option>').join('') + '</select></div>' +
        '<div class="form-row"><label>Cards</label><div class="t-cards">' + (src.cards.length ? src.cards.map((c, i) =>
          '<label class="chk"><input type="checkbox" value="' + i + '" ' + (preselect != null && preselect === i ? 'checked' : '') + '> ' + UI.cardMini(c) + '</label>').join('') : '<span class="muted">none</span>') + '</div></div>' +
        '<div class="form-row"><label>' + (fromKey === 'leo' ? 'Aquas' : 'FTs') + ' (0-' + ftsMax + ')</label><input type="number" class="t-fts" min="0" max="' + ftsMax + '" value="0"></div>' +
        '<div class="form-row t-fuel-row"><label></label><label class="chk"><input type="checkbox" class="t-asfuel"> load these as Wet Mass <b>Fuel</b> on the Fuel Strip (G1b) instead of cargo FT beads</label></div>' +
        '<p class="hint">Cargo transfer (G1) between Colocated stacks. A new Rocket starts with empty tanks; Dry Mass = cards + FT beads.</p>';
      UI.modal({
        title: 'Transfer from ' + S.STACK_NAME[fromKey] + ' at ' + esc(MAP.name(node)), body, wide: true,
        buttons: [{ label: 'Cancel' }, {
          label: 'Transfer', kind: 'primary', onClick: (c, w) => {
            const to = sel(w, '.t-to').value;
            const cards = UI.$$('.t-cards input:checked', w).map(x => +x.value);
            const n = Math.max(0, Math.min(ftsMax, +sel(w, '.t-fts').value || 0));
            const asFuel = to === 'rocket' && sel(w, '.t-asfuel').checked;
            c();
            act(g2 => {
              if (cards.length || (n && !asFuel)) A.transfer(g2, fromKey, to, cards, asFuel ? 0 : n);
              if (asFuel && n) A.loadFuel(g2, fromKey, n);
            });
          }
        }],
        onMount(root) {
          const upd = () => { sel(root, '.t-fuel-row').style.display = sel(root, '.t-to').value === 'rocket' ? '' : 'none'; };
          sel(root, '.t-to').addEventListener('change', upd);
          upd();
        }
      });
    },

    cardActions(pi, key, idx) {
      const g = G();
      const p = g.players[pi];
      const c = p.stacks[key].cards[idx];
      const st = HF.cards.stats(c);
      const status = HF.cards.statusAt(pi, key, idx);
      const mine = pi === g.current && !g.move && !g.pending;
      const buttons = [{ label: 'Close' }];
      if (mine) {
        buttons.unshift({ label: 'Move to…', onClick: cl => { cl(); D.transfer(key, idx); } });
        if (!c.crew) buttons.unshift({ label: 'Decommission to Hand', kind: 'danger', onClick: cl => { cl(); act(g2 => A.voluntaryDecommission(g2, key, idx)); } });
        if (st.deck === 'radiator' && c.orient !== 'light') buttons.unshift({ label: 'Rotate to light', onClick: cl => { cl(); act(g2 => A.radiatorToLight(g2, key, idx)); } });
        const fuel = key === 'rocket' && p.stacks.rocket.fuel;
        if (st.deck === 'radiator' && c.orient !== 'light' && fuel && fuel.grade === 'dirt') {
          buttons.unshift({ label: 'Fogg: rotate, keep Wet', onClick: cl => { cl(); act(g2 => A.radiatorToLight(g2, key, idx, true)); } });
        }
      }
      UI.modal({ title: esc(st.name), body: '<div class="card-pair">' + UI.cardFull(st, { status, statusOk: status === 'Operational' }) + '</div>' +
        '<p class="hint">' + esc(p.name) + ' · ' + S.STACK_NAME[key] + ' at ' + esc(MAP.name(S.stackNode(p, key))) + '</p>', buttons });
    },

    loadFuel() {
      const p = me();
      const r = p.stacks.rocket;
      const srcs = S.STACKS.filter(k => k !== 'rocket' && p.stacks[k] && S.stackNode(p, k) === r.node && S.stackFts(p, k) > 0);
      if (r.fts) srcs.push('rocket');
      if (!srcs.length) return UI.toast('No FTs or Aquas are Colocated with your Rocket.', 'bad');
      const room = FS.MAX_MASS - FS.stepToPos(r.fuel ? r.fuel.wet : FS.massToStep(r.fuel ? r.fuel.dry : 1)).mass;
      UI.modal({
        title: 'Load fuel (G1b)',
        body: '<div class="form-row"><label>From</label><select class="lf-src">' + srcs.map(k => '<option value="' + k + '">' + (k === 'rocket' ? 'Rocket cargo FTs (' + r.fts + ')' : S.STACK_NAME[k] + ' (' + S.stackFts(p, k) + (k === 'leo' ? ' Aquas' : ' FT') + ')') + '</option>').join('') + '</select></div>' +
          '<div class="form-row"><label>Tanks</label><input type="number" class="lf-n" min="1" value="1"></div><p class="hint">Each tank moves the Wet Mass chit one step right along the red line; room left on the strip: ' + room + ' tanks.</p>' +
          '<div class="lf-preview"></div>',
        buttons: [{ label: 'Cancel' }, { label: 'Load', kind: 'primary', onClick: (c, w) => {
          const k = sel(w, '.lf-src').value, n = +sel(w, '.lf-n').value;
          c(); act(g2 => (k === 'rocket' ? A.ftToFuel(g2, n) : A.loadFuel(g2, k, n)));
        } }],
        onMount(root) {
          const upd = () => {
            const n = +sel(root, '.lf-n').value || 0;
            const f = Object.assign({}, r.fuel || FS.create(Math.max(1, S.cargoMass(p, 'rocket'))));
            try { FS.addFuel(f, n, 'water'); sel(root, '.lf-preview').innerHTML = UI.fuelStrip(f, { color: S.color(p) }) + '<p>' + UI.fuelSummary(f) + '</p>'; }
            catch (e) { sel(root, '.lf-preview').innerHTML = '<p class="bad">' + esc(e.message) + '</p>'; }
          };
          sel(root, '.lf-n').addEventListener('input', upd);
          upd();
        }
      });
    },

    ftToFuel() {
      const r = me().stacks.rocket;
      if (!r.fts) return UI.toast('Your Rocket carries no FT beads.', 'bad');
      UI.askNumber('FT → Fuel (G2a)', '<p>Turn FT beads carried as cargo into Wet Mass Fuel. Dry Mass drops, Wet Mass stays.</p>', 1, r.fts, r.fts, 'Convert', n => act(g2 => A.ftToFuel(g2, n)));
    },

    fuelToFt() {
      const r = me().stacks.rocket;
      if (!r.fuel) return UI.toast('No fuel.', 'bad');
      let max = 0;
      while (r.fuel.wet >= FS.massToStep(r.fuel.dry + max + 1) && r.fuel.dry + max + 1 <= FS.MAX_MASS) max++;
      if (!max) return UI.toast('Not enough fuel for a whole FT.', 'bad');
      UI.askNumber('Fuel → FT (G2b)', '<p>Turn fuel into FT beads carried as cargo (not possible with dirt fuel).</p>', 1, max, max, 'Convert', n => act(g2 => A.fuelToFt(g2, n)));
    },

    jettisonFuel() {
      const r = me().stacks.rocket;
      const steps = FS.fuelSteps(r.fuel);
      if (!steps) return UI.toast('No fuel to jettison.', 'bad');
      UI.modal({
        title: 'Jettison fuel (G1f)', body: '<p>Dump fuel steps to space, e.g. to reach a lighter weight class before moving.</p>' +
          '<div class="num-row"><input type="range" min="1" max="' + steps + '" value="1" class="num-range"><input type="number" min="1" max="' + steps + '" value="1" class="num-input"></div><div class="jf-prev"></div>',
        buttons: [{ label: 'Cancel' }, { label: 'Jettison', kind: 'danger', onClick: (c, w) => { const n = +sel(w, '.num-input').value; c(); act(g2 => A.jettisonFuel(g2, n)); } }],
        onMount(root) {
          const rng = sel(root, '.num-range'), num = sel(root, '.num-input');
          const upd = () => {
            const f = Object.assign({}, r.fuel); f.wet -= +num.value;
            sel(root, '.jf-prev').innerHTML = UI.fuelStrip(f, { color: S.color(me()) }) + '<p>' + UI.fuelSummary(f) + '</p>';
          };
          rng.addEventListener('input', () => { num.value = rng.value; upd(); });
          num.addEventListener('input', () => { rng.value = num.value; upd(); });
          upd();
        }
      });
    },

    jettisonFts(key) {
      const n = S.stackFts(me(), key);
      UI.askNumber('Jettison FTs (G1g)', '<p>Discard FT beads from ' + S.STACK_NAME[key] + ' to the Pool.</p>', 1, n, 1, 'Jettison', v => act(g2 => A.jettisonFts(g2, key, v)));
    },

    fogg() {
      const r = me().stacks.rocket;
      const list = r.cards.map((c, i) => ({ c, i })).filter(x => !x.c.crew);
      if (!list.length) return UI.toast('Nothing to feed into the hopper.', 'bad');
      UI.modal({
        title: 'Phileas Fogg refuel (G2c)', body: '<p>Decommission Rocket cards to your Hand: Dry Mass drops by their mass, Wet Mass stays, and all fuel becomes <b>dirt</b> grade. ' +
          'Once the Wet Mass chit is gray, a heavy radiator can also be rotated to light this way (card menu: <i>Fogg: rotate, keep Wet</i>).</p>' +
          '<div class="t-cards">' + list.map(x => '<label class="chk"><input type="checkbox" value="' + x.i + '"> ' + UI.cardMini(x.c) + '</label>').join('') + '</div>',
        buttons: [{ label: 'Cancel' }, { label: 'Feed the hopper', kind: 'danger', onClick: (c, w) => {
          const idxs = UI.$$('.t-cards input:checked', w).map(x => +x.value); c(); act(g2 => A.phileasFogg(g2, idxs));
        } }]
      });
    },

    swap() {
      UI.modal({
        title: 'Swap Rocket and Outpost (G1e)', body: '<p>The Rocket figure and an outpost chit trade places (and stacks). Fuel left on the strip is lost - convert it to FTs first if you want to keep it.</p>',
        buttons: [{ label: 'Cancel' }, { label: 'With Outpost 1', onClick: c => { c(); act(g2 => A.swapRocket(g2, 'out1')); } },
          { label: 'With Outpost 2', onClick: c => { c(); act(g2 => A.swapRocket(g2, 'out2')); } }]
      });
    },

    dirtRefuel() {
      const info = A.dirtRefuelInfo(G());
      if (!info.ok) return UI.toast(info.reason, 'bad');
      if (info.crewOnly) return act(g2 => A.dirtRefuel(g2, 1));
      UI.askNumber('Dirt refuel (G1c)', '<p>Scoop regolith as dirt-grade fuel (' + info.source + '). Mixing makes all fuel dirt grade.</p>', 1, 20, 4, 'Refuel', n => act(g2 => A.dirtRefuel(g2, n)));
    },

    heroism() {
      const g = G();
      const free = HF_RULES.heroism.filter(h => g.heroism[h.id] === null);
      UI.modal({
        title: '🎖 Heroism nomination (L c)',
        body: '<p>Nominate a player for an accomplishment. Players vote starting with the 1st Player; the majority (1st Player breaks ties) awards the chit permanently.</p>' +
          '<div class="form-row"><label>Chit</label><select class="h-c">' + free.map(h => '<option value="' + h.id + '">' + esc(h.name) + '</option>').join('') + '</select></div>' +
          '<div class="form-row"><label>Nominee</label><select class="h-p">' + g.players.map(p => '<option value="' + p.idx + '">' + esc(p.name) + '</option>').join('') + '</select></div>',
        buttons: [{ label: 'Vote fails' }, { label: 'Majority agrees', kind: 'primary', onClick: (c, w) => {
          const ch = sel(w, '.h-c').value, pi = +sel(w, '.h-p').value; c(); act(g2 => HF.events.awardHeroism(g2, ch, pi));
        } }]
      });
    },

    /* ================= Operations ================= */
    op(id) {
      const fn = D['op_' + id];
      if (fn) fn();
    },

    op_income() { act(g => O.income(g)); },

    op_auction() {
      const g = G();
      // One row of patent decks, one of the three support decks.
      const row = decks => '<div class="card-grid deck-row">' + decks.filter(d => g.decks[d].length).map(d =>
        '<div class="deck-col pick" data-deck="' + d + '">' + UI.cardFull(HF.cards.sideStats(g.decks[d][0], 'w'), { footer: '<div class="card-actions"><button class="btn xs primary" data-deck="' + d + '">Auction this</button></div>' }) + '</div>').join('') + '</div>';
      UI.modal({
        title: 'Research auction (I2): choose a deck', wide: true,
        body: row(['thruster', 'robonaut', 'refinery']) + row(['generator', 'reactor', 'radiator']),
        onMount(root, close) {
          root.addEventListener('click', e => {
            const b = e.target.closest('button[data-deck]');
            if (b) { close(); act(g2 => O.startAuction(g2, b.dataset.deck)); }
          });
        }
      });
    },

    auctionPending() {
      const g = G();
      const pd = g.pending;
      const order = S.order(g);
      const start = order.indexOf(pd.initiator);
      const seq = order.slice(start).concat(order.slice(0, start));
      const rows = seq.map(pi => {
        const p = g.players[pi];
        const ok = O.canBid(g, pi);
        return '<tr><td>' + UI.pname(p) + (pi === pd.initiator ? ' <span class="tag">initiator</span>' : '') +
          (S.privilegeActive(g, p, 'marketeer') ? ' <span class="tag" data-tip="Marketeer wins ties">marketeer</span>' : '') + '</td>' +
          '<td>' + p.aquas + '</td><td>' + (ok ? '<input type="number" class="bid" data-p="' + pi + '" min="0" max="' + p.aquas + '" placeholder="' + (pi === pd.initiator ? '0+' : 'pass') + '"' + (pi === pd.initiator ? ' value="0"' : '') + '>' :
          '<span class="muted" data-tip="Academia hand limit (4+ cards)">cannot bid</span>') + '</td></tr>';
      }).join('');
      UI.modal({
        title: '🔨 Research auction', wide: true, dismissable: false,
        body: '<div class="auction"><div>' + UI.auction.lotHtml(g, pd, '<button class="btn xs" data-detail>See Black-Side</button>') + '</div><div>' +
          '<p>Bid in Aquas, going around the table starting with the initiator; continue until nobody raises. Enter each player’s <b>final</b> bid (empty = pass).</p>' +
          '<table class="bids"><tr><th>Player</th><th>Aquas</th><th>Final bid</th></tr>' + rows + '</table>' +
          '<div class="form-row tie-row"><label>If tied (initiator chooses)</label><select class="tie">' + seq.map(pi => '<option value="' + pi + '">' + esc(g.players[pi].name) + '</option>').join('') + '</select></div>' +
          '<p class="hint">Winner pays the Pool if they are the initiator, otherwise pays the initiator.</p></div></div>',
        buttons: [{ label: 'Resolve auction', kind: 'primary', onClick: (c, w) => {
          const bids = {};
          UI.$$('.bid', w).forEach(i => { if (i.value !== '') bids[i.dataset.p] = +i.value; });
          const tie = sel(w, '.tie').value;
          c();
          act(g2 => O.resolveAuction(g2, bids, tie));
        } }],
        onMount(root) { root.querySelector('[data-detail]').addEventListener('click', () => UI.showCard(pd.card)); }
      });
    },

    op_freeMarket() {
      const g = G();
      const p = me();
      const hand = p.hand.map((id, i) => '<div class="fm-item">' + UI.cardFull(HF.cards.sideStats(id, 'w'), { footer: '<div class="card-actions"><button class="btn xs primary" data-hand="' + i + '">Sell for 3</button></div>' }) + '</div>').join('');
      const black = [];
      S.ownStackKeysAt(p, HF_RULES.LEO).forEach(k => p.stacks[k].cards.forEach((c, i) => {
        const price = O.blackSalePrice(g, c);
        if (price != null) black.push({ c, k, i, price });
      }));
      UI.modal({
        title: '💱 Free market (I3)', wide: true,
        body: (black.length ? '<h3>Black-Side cards in your Bank - your stacks in LEO (stock price)</h3><div class="card-grid">' + black.map(x =>
          UI.cardFull(HF.cards.stats(x.c), { footer: '<div class="card-actions"><span class="muted small">' + S.STACK_NAME[x.k] + '</span> <button class="btn xs primary" data-k="' + x.k + '" data-leo="' + x.i + '">Sell for ' + x.price + '</button></div>' })).join('') + '</div>' : '') +
          '<h3>Hand cards (3 Aquas, card goes to the bottom of its deck)</h3><div class="card-grid">' + (hand || '<p class="muted">Hand is empty.</p>') + '</div>',
        onMount(root, close) {
          root.addEventListener('click', e => {
            const b = e.target.closest('button');
            if (!b) return;
            if (b.dataset.hand != null) { close(); act(g2 => O.sellHand(g2, +b.dataset.hand)); }
            if (b.dataset.leo != null) { close(); act(g2 => O.sellBlack(g2, b.dataset.k, +b.dataset.leo)); }
          });
        }
      });
    },

    /** pre: Hand indices to tick (e.g. a card dropped on LEO in the playmat). */
    op_boost(pre) {
      const p = me();
      UI.modal({
        title: '🚀 Boost to LEO (I4)', wide: true,
        body: '<p>Pay Aquas equal to the total Mass of the White-Side cards you launch. You have <b>' + p.aquas + '</b> Aquas.</p>' +
          '<div class="boost-list">' + p.hand.map((id, i) => {
            const s = HF.cards.sideStats(id, 'w');
            return '<label class="boost-row"><input type="checkbox" class="b-c" value="' + i + '"' + (pre && pre.includes(i) ? ' checked' : '') + '> ' +
              '<span class="mc-mass">' + s.mass + '</span> <b>' + esc(s.name) + '</b> <span class="muted">' + HF.cards.DECK_INFO[s.deck].name + '</span>' +
              (s.deck === 'radiator' ? ' <select class="b-o" data-i="' + i + '"><option value="heavy">heavy (' + s.mass + 'M, ❄' + s.therms + ')</option><option value="light">light (' + s.light.mass + 'M, ❄' + s.light.therms + ')</option></select>' : '') + '</label>';
          }).join('') + '</div><p class="boost-total"></p>',
        buttons: [{ label: 'Cancel' }, { label: 'Boost', kind: 'primary', id: 'boost-go', onClick: (c, w) => {
          const items = D.boostItems(w);
          c(); act(g2 => O.boost(g2, items));
        } }],
        onMount(root) {
          const upd = () => {
            const items = D.boostItems(root);
            const cost = items.length ? O.boostCost(G(), items) : 0;
            sel(root, '.boost-total').innerHTML = 'Total cost: <b>' + cost + '</b> Aquas' + (cost > p.aquas ? ' <span class="bad">(not enough)</span>' : '');
            const btn = root.parentElement.querySelector('#boost-go');
            if (btn) btn.disabled = !items.length || cost > p.aquas;
          };
          root.addEventListener('change', upd);
          upd();
        }
      });
    },
    boostItems(root) {
      return UI.$$('.b-c:checked', root).map(x => {
        const o = root.querySelector('.b-o[data-i="' + x.value + '"]');
        return { handIdx: +x.value, orient: o ? o.value : 'heavy' };
      });
    },

    targetSelect(p, node, cls) {
      const own = S.ownStackKeysAt(p, node);
      const opts = own.map(k => '<option value="' + k + '">' + S.STACK_NAME[k] + '</option>');
      if (!p.stacks.out1 || !p.stacks.out2) opts.push('<option value="new">new Outpost here</option>');
      return '<select class="' + cls + '">' + opts.join('') + '</select>';
    },

    op_refuel() {
      const p = me();
      const opts = O.refuelOptions(G());
      UI.modal({
        title: '⛽ Site refuel (I5)', wide: true,
        body: '<p>Produces FTs at a Site (a Glitch Trigger). Load them as fuel afterwards with a free action.</p>' +
          opts.map((o, i) => '<label class="opt-row"><input type="radio" name="rf" value="' + i + '" ' + (i === 0 ? 'checked' : '') + '> <b>' + esc(o.label) + '</b> at ' + esc(MAP.name(o.node)) + ' → <b>' + o.fts + ' FT</b>' +
            ' · into ' + D.targetSelect(p, o.node, 'rf-t').replace('<select', '<select data-i="' + i + '"') + '</label>' + D.permBox(o, 'rf', i, 'N6')).join('') +
          (opts.some(o => o.kind === 'airEater') ? '<label class="chk"><input type="checkbox" class="rf-finao"> Pay FINAO for the air-eater Hazard Roll (' + A.finaoCost(G(), p) + ' Aquas)</label>' : ''),
        buttons: [{ label: 'Cancel' }, { label: 'Refuel', kind: 'primary', onClick: (c, w) => {
          const i = +sel(w, 'input[name=rf]:checked').value;
          const t = w.querySelector('.rf-t[data-i="' + i + '"]').value;
          const finao = !!(sel(w, '.rf-finao') && sel(w, '.rf-finao').checked);
          const use = D.permUse(w, 'rf', i);
          if (D.permMissing(opts[i], use)) return;
          c(); act(g2 => O.refuel(g2, i, t, finao, use));
        } }]
      });
    },

    op_prospect() {
      const g = G();
      const opts = O.prospectOptions(g);
      const odds = (i, id) => {
        const s = MAP.node(id).size;
        if (s >= 6) return 'automatic';
        const mod = O.prospectTarget(i, id).mod;
        const pct = Math.round(100 * Math.max(0, Math.min(6, s - mod)) / 6) + '%';
        return pct + (mod ? ' (roll ' + (mod > 0 ? '+' : '') + mod + ')' : '');
      };
      const draw = (root, i) => {
        const o = opts[i];
        sel(root, '.pr-sites').innerHTML = o.targets.map((id, k) => '<label class="chk"><input type="' + (o.raygun ? 'checkbox' : 'radio') + '" name="prs" value="' + id + '" ' + (k === 0 ? 'checked' : '') + '> ' +
          '<b>' + esc(MAP.name(id)) + '</b> size ' + MAP.node(id).size + ' ' + MAP.node(id).spec + ' 💧' + O.prospectTarget(i, id).water + ' — success ' + odds(i, id) +
          (O.isLuna(id) && g.firstPlayer !== g.current ? ' <span class="perm-tag">Luna: 1st Player’s permission</span>' : '') + '</label>').join('') +
          (o.targets.some(id => O.isLuna(id)) && g.firstPlayer !== g.current ? '<label class="chk perm"><input type="checkbox" class="pr-luna"><span>' + UI.dot(g.players[g.firstPlayer]) +
            '<b>' + esc(g.players[g.firstPlayer].name) + ' gives permission</b> (1st Player) to prospect Luna (I6.1)</span></label>' +
            (S.privilegeActive(g, me(), 'felonious') ? '<p class="hint">Without permission, prospecting Luna is a Felony: your Human on the Site and no enemy Human there.</p>' : '') : '') +
          (o.reroll && o.raygun && o.targets.length > 1 ? '<div class="form-row"><label data-tip="Nanites: all rolls first, then one failed Site is re-rolled (I6a).">Nanites re-roll first</label><select class="pr-reroll">' +
            '<option value="">the failed Site with the best odds</option>' + o.targets.map(id => '<option value="' + id + '">' + esc(MAP.name(id)) + '</option>').join('') + '</select></div>' : '');
      };
      const mine = Object.keys(g.sites).filter(id => g.sites[id].claim === g.current);
      const spare = mine.filter(id => !g.sites[id].factory);
      UI.modal({
        title: '⛏ Prospect (I6)', wide: true,
        body: '<p>Choose the ISRU card and the Site(s). Roll ≤ Size to Claim (Size 6+ automatic); a failure Busts the Site. Raygun platforms may prospect Adjacent Sites too.</p>' +
          '<div class="pr-opts">' + opts.map((o, i) => '<label class="opt-row"><input type="radio" name="pro" value="' + i + '" ' + (i === 0 ? 'checked' : '') + '> <b>' + esc(o.name) + '</b> (' + o.kind + ', ISRU ' + o.isru + ') in ' + S.STACK_NAME[o.key] + ' at ' + esc(MAP.name(o.node)) + '</label>').join('') + '</div>' +
          '<div class="sec-h">Sites</div><div class="pr-sites"></div>' +
          (mine.length >= 9 ? '<div class="form-row"><label data-tip="I6.4: with no disk left, a success may take the disk from one of your unindustrialized Claims.">No Claim disk left</label><select class="pr-vacate">' +
            '<option value="">keep my Claims (a success is wasted)</option>' + spare.map(id => '<option value="' + id + '">take it from ' + esc(MAP.name(id)) + '</option>').join('') + '</select></div>' : ''),
        buttons: [{ label: 'Cancel' }, { label: 'Prospect', kind: 'primary', onClick: (c, w) => {
          const i = +sel(w, 'input[name=pro]:checked').value;
          const sites = UI.$$('input[name=prs]:checked', w).map(x => x.value);
          const luna = !!(sel(w, '.pr-luna') && sel(w, '.pr-luna').checked);
          const vacate = sel(w, '.pr-vacate') && sel(w, '.pr-vacate').value ? [sel(w, '.pr-vacate').value] : null;
          const reroll = sel(w, '.pr-reroll') && sel(w, '.pr-reroll').value ? [sel(w, '.pr-reroll').value] : null;
          c(); act(g2 => O.prospect(g2, i, sites, luna, vacate, reroll));
        } }],
        onMount(root) {
          draw(root, 0);
          root.addEventListener('change', e => { if (e.target.name === 'pro') draw(root, +e.target.value); });
        }
      });
    },

    /** @param freeOnly only the JELLYBOTS (Solid Flame) options, industrialized as a free action */
    op_industrialize(freeOnly) {
      const g = G();
      const opts = O.industrializeOptions(g);
      const shown = opts.map((o, i) => i).filter(i => !freeOnly || opts[i].free);
      const p = me();
      const draw = (root, i) => {
        const o = opts[i];
        const s = p.stacks[o.key];
        const roboOpts = o.robos.map(j => '<option value="' + j + '">' + esc(o.an.st[j].name) + '</option>').join('') +
          (o.arcology.length ? '<option value="-1">none - ARCOLOGY refinery works alone here</option>' : '');
        sel(root, '.in-pick').innerHTML = '<div class="form-row"><label>Robonaut</label><select class="in-r">' + roboOpts + '</select></div>' +
          '<div class="form-row"><label>Refinery</label><select class="in-f">' + o.refs.map(j => '<option value="' + j + '">' + esc(o.an.st[j].name) + '</option>').join('') + '</select></div><div class="in-prev"></div>';
        const prev = () => {
          const rI = +sel(root, '.in-r').value, fI = +sel(root, '.in-f').value;
          const set = new Set((rI >= 0 ? HF.cards.chain(o.an, rI, null) : []).concat(HF.cards.chain(o.an, fI, null)));
          sel(root, '.in-prev').innerHTML = '<p>These cards will be decommissioned (radiators are not needed, I7g):</p>' +
            [...set].filter(j => o.an.st[j].deck !== 'radiator').map(j => UI.cardMini(s.cards[j])).join('');
        };
        root.querySelector('.in-pick').addEventListener('change', prev);
        prev();
      };
      UI.modal({
        title: freeOnly ? '🏭 Industrialize — JELLYBOTS free action' : '🏭 Industrialize (I7)', wide: true,
        body: '<p>Decommission an Operational robonaut and refinery (with their support chains) on your Claim to build a Factory. Its type = the Site’s spectral type.</p>' +
          shown.map((i, k) => { const o = opts[i]; return '<label class="opt-row"><input type="radio" name="ino" value="' + i + '" ' + (k === 0 ? 'checked' : '') + '> ' + esc(MAP.name(o.node)) + ' (' + MAP.node(o.node).spec + ', stock price now ' + S.stockPrice(g, MAP.node(o.node).spec) + ') — ' + S.STACK_NAME[o.key] +
            (o.free ? ' — <b>free action</b> (JELLYBOTS)' : '') + '</label>'; }).join('') +
          '<div class="in-pick"></div>',
        buttons: [{ label: 'Cancel' }, { label: 'Build Factory', kind: 'primary', onClick: (c, w) => {
          const i = +sel(w, 'input[name=ino]:checked').value;
          const rI = +sel(w, '.in-r').value, fI = +sel(w, '.in-f').value;
          c(); act(g2 => O.industrialize(g2, i, rI, fI));
        } }],
        onMount(root) {
          draw(root, shown[0]);
          root.addEventListener('change', e => { if (e.target.name === 'ino') draw(root, +e.target.value); });
        }
      });
    },

    /** MINE REVIVAL (Termite Nest): a card Operation. */
    op_mineRevival() {
      const g = G();
      const opts = O.mineRevivalOptions();
      const mine = Object.keys(g.sites).filter(id => g.sites[id].claim === g.current);
      const spare = mine.filter(id => !g.sites[id].factory);
      UI.modal({
        title: '🐜 Mine Revival (Termite Nest)',
        body: '<p>MINE REVIVAL: as your Operation, remove the busted disk from a Site of Size 2+ where your Operational Termite Nest stands and place your Claim there.</p>' +
          opts.map((o, i) => '<label class="opt-row"><input type="radio" name="mro" value="' + i + '" ' + (i === 0 ? 'checked' : '') + '> <b>' + esc(MAP.name(o.node)) + '</b> size ' + MAP.node(o.node).size + ' ' + MAP.node(o.node).spec + ' — ' + S.STACK_NAME[o.key] + '</label>').join('') +
          (mine.length >= 9 ? '<div class="form-row"><label data-tip="I6.4: with no disk left, take the disk from one of your unindustrialized Claims.">No Claim disk left</label><select class="mr-vacate">' +
            spare.map(id => '<option value="' + id + '">take it from ' + esc(MAP.name(id)) + '</option>').join('') + '</select></div>' : ''),
        buttons: [{ label: 'Cancel' }, { label: 'Revive the mine', kind: 'primary', onClick: (c, w) => {
          const i = +sel(w, 'input[name=mro]:checked').value;
          const vacate = sel(w, '.mr-vacate') && sel(w, '.mr-vacate').value ? [sel(w, '.mr-vacate').value] : null;
          c(); act(g2 => O.mineRevival(g2, i, vacate));
        } }]
      });
    },

    op_etProduction() {
      const g = G();
      const p = me();
      const opts = O.etOptions(g).filter(o => o.cards.length);
      const draw = (root, k) => {
        const o = opts[k];
        sel(root, '.et-cards').innerHTML = '<div class="card-grid">' + o.cards.map((x, j) => '<label class="pickable"><input type="radio" name="etc" value="' + x.i + '" ' + (j === 0 ? 'checked' : '') + '>' +
          UI.cardFull(HF.cards.sideStats(x.cid, 'b')) + '</label>').join('') + '</div>' +
          '<div class="form-row"><label>Place into</label>' + D.targetSelect(p, o.node, 'et-t') + '</div>' +
          '<div class="form-row"><label>Radiator orientation</label><select class="et-o"><option value="heavy">heavy</option><option value="light">light</option></select></div>';
      };
      UI.modal({
        title: '⚙ ET production (I8)', wide: true,
        body: '<p>Bring a Hand card into play Black-Side up at a Factory; its spectral type must match the Factory’s. An opponent’s Factory needs their permission, ' +
          'or a Factory Hijack (Felony: your Human there, no enemy Human; I8d, N6). The card comes into play in an Outpost; choosing the Rocket moves it there by cargo transfer ' +
          '(a Glitch Roll if the Rocket is glitched, G1h).</p>' +
          opts.map((o, i) => '<label class="opt-row"><input type="radio" name="eto" value="' + i + '" ' + (i === 0 ? 'checked' : '') + '> ' +
            (o.owner != null ? UI.dot(g.players[o.owner]) + '<span><b>' + esc(g.players[o.owner].name) + '’s</b> ' : '<span>Your ') + 'Factory on ' + esc(MAP.name(o.node)) + ' (type ' + o.spec + ')</span>' +
            '</label>' + D.permBox(o, 'et', i, 'N6')).join('') +
          '<div class="et-cards"></div>',
        buttons: [{ label: 'Cancel' }, { label: 'Produce', kind: 'primary', onClick: (c, w) => {
          const k = +sel(w, 'input[name=eto]:checked').value;
          const o = opts[k];
          const hi = +sel(w, 'input[name=etc]:checked').value;
          const t = sel(w, '.et-t').value, or = sel(w, '.et-o').value;
          const use = D.permUse(w, 'et', k);
          if (D.permMissing(o, use)) return;
          c(); act(g2 => O.etProduce(g2, o.node, hi, t, or, use));
        } }],
        onMount(root) {
          draw(root, 0);
          root.addEventListener('change', e => { if (e.target.name === 'eto') draw(root, +e.target.value); });
        }
      });
    },

    op_delivery() {
      const opts = O.deliveryOptions(G());
      UI.modal({
        title: '📦 Delivery (I9)', wide: true,
        body: '<p>Send a Black-Side card from a stack at a Factory to your LEO stack (where it can be sold at stock price). Cost: 2 FTs per Heliocentric Zone from Earth, +1 if the Site is Size 7+.</p>' +
          opts.map((o, i) => '<label class="opt-row' + (o.affordable ? '' : ' bad') + '"><input type="radio" name="dl" value="' + i + '" ' + (o.affordable ? '' : 'disabled') + (o.affordable && !opts.slice(0, i).some(x => x.affordable) ? ' checked' : '') + '> <b>' + esc(o.name) + '</b> from ' +
            esc(MAP.name(o.node)) + ' — cost <b>' + o.cost + ' FT</b> (stack has ' + (me().stacks[o.key].fts || 0) + ')</label>').join(''),
        buttons: [{ label: 'Cancel' }, { label: 'Deliver', kind: 'primary', onClick: (c, w) => {
          const r = sel(w, 'input[name=dl]:checked');
          if (!r) return UI.toast('Nothing affordable.', 'bad');
          c(); act(g2 => O.deliver(g2, +r.value));
        } }]
      });
    },

    /* ================= Pending decisions & events ================= */
    budgetCuts() {
      const g = G();
      const p = g.players[g.pending.queue[0]];
      UI.modal({
        title: '✂ Budget cuts: ' + esc(p.name), wide: true, dismissable: false,
        body: '<p>' + UI.pname(p) + ' must discard one Hand card of their choice to the bottom of its deck (K2f).</p><div class="card-grid">' +
          p.hand.map((id, i) => UI.cardFull(HF.cards.sideStats(id, 'w'), { footer: '<div class="card-actions"><button class="btn xs danger" data-i="' + i + '">Discard this</button></div>' })).join('') + '</div>',
        onMount(root, close) {
          root.addEventListener('click', e => {
            const b = e.target.closest('button[data-i]');
            if (b) { close(); act(g2 => HF.events.resolveBudgetCut(g2, +b.dataset.i)); }
          });
        }
      });
    },

    glitchChoice() {
      const g = G();
      const p = g.players[g.pending.queue[0]];
      UI.modal({
        title: '⚠ Glitch: ' + esc(p.name) + ' chooses', dismissable: false,
        body: '<p>' + UI.pname(p) + ' has several stacks with the most cards and no Humans on their Space. Choose the one that gets the Glitch (K2b).</p>' +
          '<div class="btn-col">' + g.pending.stacks.map(k => {
            const s = p.stacks[k];
            return '<button class="btn" data-k="' + k + '">' + S.STACK_NAME[k] + ' at ' + esc(MAP.name(s.node)) + ' · ' + s.cards.length + ' card' + (s.cards.length === 1 ? '' : 's') +
              (s.fts ? ' · ' + s.fts + ' FT' : '') + '</button>';
          }).join('') + '</div>',
        onMount(root, close) {
          root.addEventListener('click', e => {
            const b = e.target.closest('button[data-k]');
            if (b) { close(); act(g2 => HF.events.resolveGlitch(g2, b.dataset.k)); }
          });
        }
      });
    },

    padChoice() {
      const g = G();
      const p = g.players[g.pending.queue[0]];
      UI.modal({
        title: '💥 Pad explosion: ' + esc(p.name) + ' chooses', wide: true, dismissable: false,
        body: '<p>' + UI.pname(p) + ' has several cards with the highest Mass in LEO. Choose the one that is decommissioned to the Hand (K2c).</p><div class="card-grid">' +
          g.pending.cards.map(ref => {
            const [k, i] = ref.split(':');
            const c = p.stacks[k].cards[+i];
            return UI.cardFull(HF.cards.stats(c), { footer: '<div class="card-actions"><span class="muted small">' + S.STACK_NAME[k] + '</span> <button class="btn xs danger" data-ref="' + ref + '">Lose this</button></div>' });
          }).join('') + '</div>',
        onMount(root, close) {
          root.addEventListener('click', e => {
            const b = e.target.closest('button[data-ref]');
            if (b) { close(); act(g2 => HF.events.resolvePadExplosion(g2, b.dataset.ref)); }
          });
        }
      });
    },

    eventPopup(ev, after) {
      UI.modal({
        title: '☀ Sunspot event: ' + esc(ev.name), cls: 'event-modal',
        body: '<div class="event-box">' + UI.dieHtml(ev.roll, 'tumble') + '<div><h3>' + esc(ev.name) + '</h3><p>' + esc(ev.text) + '</p></div></div>',
        buttons: [{ label: 'OK', kind: 'primary' }], onClose: after
      });
    },

    handover(g, after) {
      const p = g.players[g.current];
      const r = p.stacks.rocket;
      const news = UI.assist.digest(g, g.current);
      const shown = news.slice(-12);
      const line = UI.panels.logLine(g);
      UI.modal({
        title: 'Year ' + g.year + ' · ' + HF.cap(S.season(g)) + ' season', cls: 'handover', dismissable: false,
        body: '<div class="handover-box" style="--pc:' + S.color(p) + '">' + UI.gfx.logoHtml(p, 56) + '<div><div class="ho-name">' + esc(p.name) + '</div><div class="ho-fac">' + esc(p.faction) +
          ' · ' + esc(HF_RULES.privileges[p.privilege].name) + '</div></div>' +
          '<div class="ho-stats"><span><i class="aqua"></i> ' + p.aquas + '</span><span>🂠 ' + p.hand.length + '</span><span>★ ' + HF.events.score(g, g.current).total + ' VP</span>' +
          '<span class="muted">' + (r ? 'Rocket at ' + esc(HF.map.name(r.node)) : 'no Rocket') + '</span></div></div>' +
          (news.length ? '<div class="ho-news"><div class="ho-news-h">Since your last turn' + (news.length > shown.length ? ' <span class="muted">(last ' + shown.length + ' of ' + news.length + ')</span>' : '') + '</div>' +
            shown.map(line).join('') + '</div>' : '') +
          '<p class="hint">Pass the device to ' + esc(p.name) + '.</p>',
        buttons: [{ label: 'Start turn ▸', kind: 'primary', onClick: c => { c(); if (after) after(); } }]
      });
    },

    gameOver() {
      const g = G();
      const go = g.gameOver;
      const rows = go.ranking.map((pi, rank) => {
        const s = go.scores[pi];
        const p = g.players[pi];
        return '<tr' + (rank === 0 ? ' class="winner"' : '') + '><td>' + (rank + 1) + '</td><td>' + UI.pname(p) + '</td><td>' + s.claims + '</td><td>' + s.factories + '</td><td>' + s.colonies + '</td><td>' + s.rocket +
          '</td><td>' + s.stock + '</td><td>' + s.colonyBonus + '</td><td>' + s.glory + '</td><td>' + s.heroism + '</td><td><b>' + s.total + '</b></td><td>' + s.aquas + '</td></tr>';
      }).join('');
      UI.modal({
        title: '🏆 Game over', wide: true,
        body: '<h3>' + esc(g.players[go.ranking[0]].name) + ' wins!</h3><table class="score"><tr><th>#</th><th>Player</th><th>Claims</th><th>Factories</th><th>Colonies</th><th>Rocket</th>' +
          '<th>Stock</th><th>Colony bonus</th><th>Glory</th><th>Heroism</th><th>Total</th><th>Aquas (tiebreak)</th></tr>' + rows + '</table>',
        buttons: [{ label: 'Look at the board' }, { label: 'New game', kind: 'primary', onClick: c => { c(); HF.app.showSetup(); } }]
      });
    }
  };

  UI.dialogs = D;
})(window.HF = window.HF || {});

/* A permission and a Factory Hijack exclude each other. */
document.addEventListener('change', e => {
  const box = e.target.closest && e.target.closest('.perm-box');
  if (box && e.target.checked) box.querySelectorAll('input[type=checkbox]').forEach(x => { if (x !== e.target) x.checked = false; });
});
