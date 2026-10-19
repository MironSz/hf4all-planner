/*
 * Negotiation screen (N): two players at the table strike a deal and carry
 * out the transfers - Aquas (N1), Hand cards (N2), stack cargo, whole stacks
 * and Sites (N3). Opened from the top bar and the Players tab; it stays open
 * so a deal with several parts can be carried out item by item (each one is
 * a separate, undoable step).
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const S = HF.state;
  const MAP = HF.map;
  const esc = HF.escape;

  const G = () => HF.app.G;

  const NG = {
    open(from, to) {
      const N = HF.negotiation;
      const n = G().players.length;
      if (n < 2) return UI.toast('Negotiation needs at least two players.', 'info');
      const st = { a: from != null ? from : G().current, b: null, key: null };
      st.b = to != null && to !== st.a ? to : (st.a + 1) % n;

      const draw = (root) => {
        const g = G();
        const a = g.players[st.a], b = g.players[st.b];
        const can = N.canTrade(g);
        const dis = can.ok ? '' : ' disabled';
        const keys = S.STACKS.filter(k => a.stacks[k]);
        if (!keys.includes(st.key)) st.key = keys[0];
        const chips = (cls, on) => g.players.map(q => '<button class="chip' + (q.idx === on ? ' on' : '') + '" data-' + cls + '="' + q.idx + '">' + UI.dot(q) + esc(q.name) + '</button>').join('');

        let h = '<div class="ng-parties"><div class="ng-party"><span class="ng-lbl">From</span><div class="chips">' + chips('ng-a', st.a) + '</div></div>' +
          '<button class="btn sm ng-swap" data-ng="swap" data-tip="Swap giver and receiver">⇄</button>' +
          '<div class="ng-party"><span class="ng-lbl">To</span><div class="chips">' + chips('ng-b', st.b) + '</div></div></div>' +
          '<p class="hint">Deals can be struck at any time, even on an opponent’s Turn; promises that cannot be carried out this Turn are not binding (N). ' +
          'Carry out each part of the deal below - every transfer is logged and can be undone until a die is rolled.</p>' +
          (can.ok ? '' : '<p class="ng-paused">⏸ ' + esc(can.reason) + '</p>');

        // N1 Aquas
        h += '<section class="ng-sec"><div class="sec-h">💧 Aquas <small class="muted">N1 · without restriction</small></div>' +
          '<div class="ng-line">' + UI.pname(a) + ' has <b>' + a.aquas + '</b> Aquas. Pay <input type="number" class="ng-aq" min="1" max="' + a.aquas + '" value="' + Math.min(1, a.aquas) + '"> ' +
          '<button class="btn sm primary" data-ng="aquas"' + (a.aquas ? dis : ' disabled') + '>Pay ' + esc(b.name) + ' ▸</button></div></section>';

        // N2 Hand cards
        const after = b.hand.length;
        h += '<section class="ng-sec"><div class="sec-h">🂠 Hand cards <small class="muted">N2 · a traded card cannot be traded on before its previous holder’s next Turn</small></div>';
        if (a.hand.length) {
          h += '<div class="ng-cards">' + a.hand.map((id, i) => {
            const lock = N.tradeLock(g, id);
            return '<label class="chk' + (lock ? ' off' : '') + '"><input type="checkbox" class="ng-h" value="' + i + '"' + (lock ? ' disabled' : '') + '> ' +
              UI.cardMini({ id, side: 'w' }) +
              (lock ? '<span class="tag" data-tip="' + esc('Traded from ' + g.players[lock.owner].name + '’s Hand: locked until the start of their next Turn (N2a)') + '">🔒 ' + esc(g.players[lock.owner].name) + '</span>' : '') + '</label>';
          }).join('') + '</div><div class="ng-line"><button class="btn sm primary" data-ng="hand"' + dis + '>Give selected to ' + esc(b.name) + ' ▸</button>' +
            '<span class="muted small">' + esc(b.name) + ' holds ' + HF.plural(after, 'card') + (after >= 3 ? ' - 4 or more block research auctions (I2a)' : '') + '</span></div>';
        } else h += '<p class="muted small">' + esc(a.name) + '’s Hand is empty.</p>';
        h += '</section>';

        // N3 stack cargo and whole stacks
        h += '<section class="ng-sec"><div class="sec-h">🚀 Stacks <small class="muted">N3 · cards and FTs go to ' + esc(b.name) + '’s playmat on the same Space</small></div>';
        if (st.key) {
          const s = a.stacks[st.key];
          const node = S.stackNode(a, st.key);
          const fts = S.stackFts(a, st.key);
          const targets = N.stackTargets(g, st.a, st.key, st.b);
          h += '<div class="chips">' + keys.map(k => '<button class="chip' + (k === st.key ? ' on' : '') + '" data-ng-key="' + k + '">' + S.STACK_NAME[k] + ' · ' + esc(MAP.name(S.stackNode(a, k))) + '</button>').join('') + '</div>';
          h += '<div class="ng-cards">' + (s.cards.length ? s.cards.map((c, i) => {
            const owner = c.crew ? g.players[S.crewOwner(g, c, st.a)] : null;
            return '<label class="chk"><input type="checkbox" class="ng-c" value="' + i + '"> ' + UI.cardMini(c) +
              (owner ? '<span class="tag" data-tip="A Crew moved into an opponent’s stack stays under its owner’s control (N3d)">' + esc(owner.name) + '’s Human</span>' : '') + '</label>';
          }).join('') : '<p class="muted small">no cards</p>') + '</div>';
          h += '<div class="ng-line">' + (fts ? (st.key === 'leo' ? 'Aquas' : 'FTs') + ' <input type="number" class="ng-ft" min="0" max="' + fts + '" value="0"> <span class="muted small">of ' + fts + '</span> · ' : '') +
            'into <select class="ng-to">' + targets.map(t => '<option value="' + t.key + '">' + esc(b.name) + '’s ' + esc(t.label) + '</option>').join('') + '</select> ' +
            '<button class="btn sm primary" data-ng="cargo"' + (targets.length ? dis : ' disabled') + '>Give ▸</button></div>';
          if (st.key !== 'leo') {
            const free = N.freeSlots(g, st.b);
            h += '<div class="ng-line ng-whole">Hand over the whole ' + S.STACK_NAME[st.key] + ' at ' + esc(MAP.name(node)) + ' (its Figure changes colour) as ' +
              (free.length ? '<select class="ng-slot">' + free.map(k => '<option value="' + k + '"' + (k === st.key ? ' selected' : '') + '>' + esc(b.name) + '’s ' + S.STACK_NAME[k] + '</option>').join('') + '</select> ' +
                '<button class="btn sm" data-ng="stack"' + dis + '>Hand over ▸</button>' +
                (st.key === 'rocket' ? ' <span class="muted small">(as an Outpost its Fuel is lost)</span>' : '')
                : '<span class="muted">- ' + esc(b.name) + ' has no free Rocket / Outpost slot (E3)</span>') + '</div>';
          }
        }
        h += '</section>';

        // N3 Sites
        const sites = N.ownedSites(g, st.a);
        const cb = S.counts(g, st.b);
        h += '<section class="ng-sec"><div class="sec-h">● Claims & Factories <small class="muted">N3 · the Claim disk changes colour with its Factory cube and Colony dome</small></div>';
        h += sites.length ? '<div class="ng-sites">' + sites.map(id => {
          const site = g.sites[id];
          const N0 = MAP.node(id);
          return '<div class="ng-site"><a data-ng-goto="' + id + '">' + esc(MAP.name(id)) + '</a> <span class="muted small">size ' + N0.size + ' ' + N0.spec + '</span>' +
            (site.factory ? ' <span class="tag">Factory</span>' : '') + (site.colony ? ' <span class="tag">Colony</span>' : '') +
            '<button class="btn xs" data-ng="site" data-site="' + id + '"' + dis + '>Give to ' + esc(b.name) + '</button></div>';
        }).join('') + '</div><p class="muted small">' + esc(b.name) + ' uses ' + cb.claims + '/9 Claim disks and ' + cb.factories + '/7 Factory cubes (N3b).</p>'
          : '<p class="muted small">' + esc(a.name) + ' owns no Claims.</p>';
        h += '</section>';
        root.querySelector('.modal-body').innerHTML = h;
      };

      const run = (root, fn) => { HF.app.act(fn).then(() => { if (root.isConnected) draw(root); }); };

      UI.modal({
        title: '🤝 Negotiation (N)', wide: true, cls: 'ng-modal', body: '',
        buttons: [{ label: 'Done', kind: 'primary' }],
        onMount(root) {
          draw(root);
          root.addEventListener('click', e => {
            const t = e.target.closest('button, a');
            if (!t || !root.contains(t)) return;
            const d = t.dataset;
            if (d.ngA != null) { const v = +d.ngA; if (v === st.b) st.b = st.a; st.a = v; st.key = null; return draw(root); }
            if (d.ngB != null) { const v = +d.ngB; if (v === st.a) st.a = st.b; st.b = v; return draw(root); }
            if (d.ngKey) { st.key = d.ngKey; return draw(root); }
            if (d.ngGoto) { UI.board.centerOn(d.ngGoto); return; }
            const N = HF.negotiation;
            const val = (s) => root.querySelector(s);
            switch (d.ng) {
              case 'swap': [st.a, st.b] = [st.b, st.a]; st.key = null; draw(root); break;
              case 'aquas': run(root, g => N.giveAquas(g, st.a, st.b, +val('.ng-aq').value || 0)); break;
              case 'hand': run(root, g => N.giveHandCards(g, st.a, st.b, UI.$$('.ng-h:checked', root).map(x => +x.value))); break;
              case 'cargo': {
                const cards = UI.$$('.ng-c:checked', root).map(x => +x.value);
                const fts = val('.ng-ft') ? +val('.ng-ft').value || 0 : 0;
                run(root, g => N.giveStackItems(g, st.a, st.key, cards, fts, st.b, val('.ng-to').value));
                break;
              }
              case 'stack': run(root, g => N.giveStack(g, st.a, st.key, st.b, val('.ng-slot').value)); break;
              case 'site': run(root, g => N.giveSite(g, st.a, d.site, st.b)); break;
            }
          });
        }
      });
    }
  };

  UI.negotiation = NG;
})(window.HF = window.HF || {});
