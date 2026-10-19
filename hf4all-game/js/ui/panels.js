/* Right-hand side panel: Turn, Hand, Patents, Players, Log, Help tabs; the Mat button opens the playmat. */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const S = HF.state;
  const MAP = HF.map;
  const O = HF.ops;
  const esc = HF.escape;
  const FS = HF.FS;

  const TABS = [
    ['turn', 'Turn', '▶'], ['mat', 'Mat', '🛰'], ['hand', 'Hand', '🂠'], ['patents', 'Patents', '🔬'], ['players', 'Players', '👥'], ['log', 'Log', '📜'], ['help', 'Help', '❔']
  ];

  const P = {
    render(G) {
      const root = document.getElementById('side');
      if (!root) return;
      const ui = HF.app.ui;
      const cur = G.players[G.current];
      const tabs = '<nav class="tabs">' + TABS.map(([k, l, ic], i) => {
        let badge = '';
        if (k === 'hand') badge = '<span class="tab-badge">' + cur.hand.length + '</span>';
        const title = k === 'mat' ? 'Your playmat (' + (i + 1) + ' / P)' : l + ' (' + (i + 1) + ')';
        return '<button class="tab' + (ui.tab === k ? ' on' : '') + '" data-act="tab" data-tab="' + k + '" title="' + title + '"><span class="tab-ic">' + ic + '</span>' + l + badge + '</button>';
      }).join('') + '</nav>';
      let body = '';
      if (ui.tab === 'mat' || typeof P[ui.tab] !== 'function') ui.tab = 'turn';
      try {
        body = P[ui.tab](G);
      } catch (ex) { console.error(ex); body = '<p class="err">Render error: ' + esc(ex.message) + '</p>'; }
      root.innerHTML = tabs + '<div class="tab-body">' + body + '</div>';
    },

    /* ---------------- Turn ---------------- */
    turn(G) {
      if (G.gameOver) return P.finished(G);
      const p = G.players[G.current];
      const sc = HF.events.score(G, p.idx);
      const priv = HF_RULES.privileges[p.privilege];
      let h = '<section class="turn-head" style="--pc:' + S.color(p) + '">' +
        '<div class="th-name">' + UI.gfx.logoHtml(p, 28) + ' <b>' + esc(p.name) + '</b> <span class="muted">' + esc(p.faction) + '</span></div>' +
        '<div class="th-priv' + (G.anarchy ? ' struck' : '') + '" data-tip="' + esc(priv.text) + '">✦ ' + esc(priv.name) + '</div>' +
        (G.anarchy ? '<div class="th-priv anarchy" data-tip="Anarchy (K2e): privileges replaced by Felonious until season blue ends">☠ Anarchy: Felonious</div>' : '') +
        '<div class="th-stats"><span data-tip="Aquas (water in LEO = money)"><i class="aqua"></i> ' + p.aquas + '</span>' +
        '<span data-tip="Hand cards (academia limit: auctions need fewer than 4)">🂠 ' + p.hand.length + '</span>' +
        '<span data-tip="Current victory points">★ ' + sc.total + ' VP</span></div></section>';
      h += UI.assist.html(G);

      // Movement
      const r = p.stacks.rocket;
      let mv;
      if (G.move) mv = '<span class="state live">moving…</span>';
      else if (G.turn.moved) mv = '<span class="state done">✓ moved</span>';
      else if (!r) mv = '<span class="state na">no Rocket</span>';
      else mv = '<button class="btn primary sm" data-act="move-prep">Move Rocket ▸</button>';
      h += '<section class="step"><div class="step-h"><span class="step-n">1</span> Movement <small>(H) once per turn</small>' + mv + '</div>';
      if (r) h += '<div class="step-sub">Rocket at <a data-act="goto" data-node="' + r.node + '">' + esc(MAP.name(r.node)) + '</a> · ' + UI.fuelSummary(r.fuel) + '</div>';
      else h += '<div class="step-sub">Create a Rocket by dragging cards out of LEO in the dock or the playmat (or click a card ▸ Move to…).</div>';
      h += '</section>';

      // Operation
      h += '<section class="step"><div class="step-h"><span class="step-n">2</span> Operation <small>(I) one per turn</small>' +
        (G.turn.op ? '<span class="state done">✓ ' + esc(HF_RULES.operations.find(o => o.id === G.turn.op).name) + '</span>' : '') + '</div>';
      if (!G.turn.op) {
        const av = P.opAvailability(G);
        h += '<div class="op-grid">' + HF_RULES.operations.filter(o => !o.card || ((HF.app.V && HF.app.V.ops) || {})[o.id]).map(o => {
          const a = av[o.id];
          return '<button class="op-btn' + (a.ok ? '' : ' off') + '" data-act="op" data-op="' + o.id + '" data-tip="' + esc('<b>' + o.name + ' (' + o.ref + ')</b><br>' + o.text + (a.ok ? '' : '<br><i>' + a.why + '</i>')) + '">' +
            '<span class="op-ic">' + P.opIcon(o.id) + '</span><span>' + o.name + '</span></button>';
        }).join('') + '</div>';
      }
      h += '</section>';

      // Free actions
      h += '<section class="step"><div class="step-h"><span class="step-n">∞</span> Free actions <small>(G) any time outside move/op</small></div>';
      h += '<div class="free-grid">' + P.freeActions(G).map(f =>
        '<button class="btn sm' + (f.ok ? '' : ' off') + '" data-act="' + f.act + '"' + (f.data || '') + ' data-tip="' + esc(f.tip + (f.ok ? '' : '<br><i>' + (f.why || '') + '</i>')) + '">' + f.label + '</button>').join('') + '</div></section>';

      const probs = HF.events.endTurnProblems(G);
      h += '<button class="btn end-turn' + (probs.length ? ' off' : '') + '" data-act="end-turn" data-tip="' + esc(probs.join(' ') || 'Pass to the next player') + '">End turn ▸ ' +
        (S.nextPlayer(G) != null ? esc(G.players[S.nextPlayer(G)].name) : 'end of year ' + G.year) + '</button>';
      h += P.recentLog(G);
      return h;
    },

    finished(G) {
      const go = G.gameOver;
      return '<section class="step"><div class="step-h">🏆 Game over</div><p>' + esc(G.players[go.ranking[0]].name) + ' wins with <b>' +
        go.scores[go.ranking[0]].total + ' VP</b>.</p><ol>' + go.ranking.map(pi => '<li>' + UI.pname(G.players[pi]) + ' — ' + go.scores[pi].total + ' VP</li>').join('') +
        '</ol><div class="btn-row"><button class="btn primary sm" data-act="final-scores">Final scores</button><button class="btn sm" data-act="new-game">New game</button></div></section>' +
        P.recentLog(G);
    },

    opIcon(id) {
      return { income: '💧', auction: '🔨', freeMarket: '💱', boost: '🚀', refuel: '⛽', prospect: '⛏',
        industrialize: '🏭', etProduction: '⚙', delivery: '📦', mineRevival: '🐜' }[id];
    },

    /** The nine Operations with the reason when one is not possible - decided by the server (view.ops). */
    opAvailability() {
      const ops = (HF.app.V && HF.app.V.ops) || {};
      const res = {};
      for (const o of HF_RULES.operations) res[o.id] = ops[o.id] || { ok: false, why: 'Not available.' };
      return res;
    },

    /** Contextual free actions (G); whether each is possible comes from the server (view.free). */
    freeActions(G) {
      const p = G.players[G.current];
      const free = (HF.app.V && HF.app.V.free) || {};
      const av = (id) => free[id] || { ok: false, why: 'Not available.' };
      const list = [];
      const add = (act, label, tip, a, data) => list.push({ act, label, tip, ok: a.ok, why: a.why, data });
      add('tab', 'Cargo & fuel…', 'Transfer cards/FTs, load fuel, internal tankage, jettison (playmat).', { ok: true }, ' data-tab="mat"');
      add('dirt-refuel', 'Dirt refuel', 'G1c: on a Site with a Factory or an Operational ISRU card, refuel an activated dirt thruster.', av('dirtRefuel'));
      if (free.mooncable) add('mooncable', 'Mooncable', HF_RULES.privileges.mooncable.text, free.mooncable);
      add('load-glory', '★ Take glory', 'G5: a Human landing on a never-visited Site may take that zone\'s glory chit.', av('loadGlory'));
      add('build-colony', '⌂ Build colony', 'G3: decommission your Crew at your Factory to place a Colony dome.', av('buildColony'));
      if (free.claimJump) {
        add('claim-jump', '☠ Claim jump', 'G4 Felony: replace an opponent\'s Claim where your Crew stands (no Factory, no defending Human).', free.claimJump);
        add('murder', '☠ Murder/Suicide', 'Felony, once per Turn: decommission your Crew (it returns to your LEO stack; a glory chit it carries scores its low side), or an opponent\'s Crew riding in your stack (N3d).', av('murder'));
      }
      if (free.jellybots) add('jellybots', '🏭 Industrialize (Jellybots)', 'JELLYBOTS (Solid Flame): industrializing on the Space of your Operational Solid Flame is a free action.', free.jellybots);
      for (const k of S.STACKS) {
        if (free['glitchRepair:' + k]) add('glitch-repair', 'Repair glitch: ' + S.STACK_NAME[k], 'G7: a Human on the same Space removes the Glitch.', free['glitchRepair:' + k], ' data-key="' + k + '"');
      }
      if (G.config.heroism) {
        const lastYear = G.year >= G.config.cycles * 12;
        add('heroism', '🎖 Heroism vote', 'L c: nominate a player for a heroism chit (2 VP), any time before the last year.',
          { ok: !lastYear && Object.values(G.heroism).some(v => v === null), why: lastYear ? 'Not in the last year of the game (L c).' : 'All heroism chits are awarded.' });
      }
      return list;
    },

    recentLog(G) {
      const items = G.log.slice(-6).reverse();
      return '<section class="recent"><div class="recent-h">Recent <a data-act="tab" data-tab="log">full log ▸</a></div>' + items.map(P.logLine(G)).join('') + '</section>';
    },

    logLine(G) {
      return e => {
        const p = e.player != null ? G.players[e.player] : null;
        return '<div class="log-line k-' + e.kind + '">' + (p ? UI.dot(p) : '<span class="pdot sys"></span>') + '<span class="ly">Y' + e.year + '</span> ' + esc(e.text) + '</div>';
      };
    },

    /* ---------------- Hand ---------------- */
    hand(G) {
      const p = G.players[G.current];
      const ui = HF.app.ui;
      let h = '<div class="hand-tools"><span>' + esc(p.name) + ' holds <b>' + p.hand.length + '</b> card' + (p.hand.length === 1 ? '' : 's') +
        (p.hand.length >= 4 && !S.privilegeActive(G, p, 'skunkworks') ? ' <span class="warn" data-tip="Academia hand limit (I2a)">auction-locked</span>' : '') + '</span>' +
        '<label class="toggle"><input type="checkbox" data-act="hand-black" ' + (ui.handBlack ? 'checked' : '') + '> show Black-Sides</label></div>';
      if (!p.hand.length) return h + '<p class="hint">Your Hand is empty. Win patents in Research Auctions (Patents tab) - decommissioned cards also return here.</p>';
      h += '<div class="card-grid">' + p.hand.map((id, i) => {
        const s = HF.cards.sideStats(id, ui.handBlack ? 'b' : 'w');
        const lock = HF.negotiation.tradeLock(G, id);
        return UI.cardFull(s, { footer: '<div class="card-actions">' +
          (lock ? '<span class="tag" data-tip="' + esc('Traded from ' + G.players[lock.owner].name + '’s Hand: cannot be traded again before their next Turn (N2a)') + '">🔒</span>' : '') +
          '<button class="btn xs" data-act="card-detail" data-id="' + id + '">Both sides</button>' +
          '<button class="btn xs" data-act="discard" data-i="' + i + '" data-tip="G6: discard to the bottom of its deck (free action)">Discard</button></div>' });
      }).join('') + '</div>';
      return h;
    },

    /* ---------------- Patents ---------------- */
    patents(G) {
      const p = G.players[G.current];
      const canOp = !G.turn.op && !G.move && !G.pending && !G.gameOver;
      let h = '<p class="hint">The front of each deck’s top card is always visible (I2f). Auctioning it is your Operation for the turn.</p><div class="card-grid">';
      for (const d of HF.cards.DECKS) {
        const deck = G.decks[d];
        const info = HF.cards.DECK_INFO[d];
        h += '<div class="deck-col"><div class="deck-h" style="--deck:' + info.color + '">' + info.name + ' <span class="muted">' + deck.length + ' left</span></div>';
        if (deck.length) {
          const can = canOp && O.canBid(G, p.idx);
          h += UI.cardFull(HF.cards.sideStats(deck[0], 'w'), { footer: '<div class="card-actions"><button class="btn xs" data-act="card-detail" data-id="' + deck[0] + '">Both sides</button>' +
            '<button class="btn xs primary' + (can ? '' : ' off') + '" data-act="auction" data-deck="' + d + '"' +
            (can ? '' : ' data-tip="Needs your Operation for the turn and fewer than 4 Hand cards (I2a)."') + '>Auction</button></div>' });
        } else h += '<p class="muted">Empty</p>';
        h += '</div>';
      }
      return h + '</div>';
    },

    /* ---------------- Players ---------------- */
    players(G) {
      let h = '<div class="btn-row"><button class="btn sm primary" data-act="negotiate" data-tip="Negotiation (N): Aquas, Hand cards, stack cargo, stacks and Claims">🤝 Negotiate a deal…</button>' +
        '<span class="muted small">at any time, even on an opponent’s Turn (not during movement)</span></div>';
      h += P.vpRace(G);
      h += '<div class="player-cards">';
      for (const pi of S.order(G)) {
        const p = G.players[pi];
        const sc = HF.events.score(G, pi);
        const c = S.counts(G, pi);
        h += '<section class="pcard' + (pi === G.current ? ' current' : '') + '" style="--pc:' + S.color(p) + '">' +
          '<div class="pc-h">' + UI.gfx.logoHtml(p, 24) + ' <b>' + esc(p.name) + '</b> <span class="muted">' + esc(p.faction) + '</span>' +
          (pi === G.firstPlayer ? '<span data-tip="1st Player (Sol secretary-general)">' + UI.gfx.icon('firstplayer.png', '<span class="first">1st</span>', 'first-ico') + '</span>' : '') + '<span class="vp">' + sc.total + ' VP</span></div>' +
          '<div class="pc-priv">' + esc(HF_RULES.privileges[p.privilege].name) + '</div>' +
          '<div class="pc-stats"><span><i class="aqua"></i> ' + p.aquas + '</span><span>🂠 ' + p.hand.length + '</span>' +
          '<span data-tip="Claims">● ' + c.claims + '</span><span data-tip="Factories">■ ' + c.factories + '</span><span data-tip="Colonies">⌂ ' + c.colonies + '</span></div>' +
          '<div class="pc-vp">tokens ' + sc.tokens + ' · stock ' + sc.stock + ' · colonies +' + sc.colonyBonus + ' · glory ' + sc.glory + ' · heroism ' + sc.heroism + '</div>' +
          '<div class="pc-stacks">' + ['rocket', 'out1', 'out2'].filter(k => p.stacks[k]).map(k =>
            '<a data-act="goto" data-node="' + p.stacks[k].node + '">' + S.STACK_NAME[k] + ': ' + esc(MAP.name(p.stacks[k].node)) + '</a>').join('') + '</div>' +
          '<div class="btn-row"><button class="btn xs" data-act="mat-player" data-p="' + pi + '">Playmat</button>' +
          (pi !== G.current ? '<button class="btn xs" data-act="negotiate" data-p="' + pi + '" data-tip="' + esc('Deal between ' + G.players[G.current].name + ' and ' + p.name) + '">🤝 Deal…</button>' : '') + '</div></section>';
      }
      h += '</div>';
      h += '<section><div class="sec-h">Exploitation tracks <small class="muted">(stock price → free market & endgame VP per Factory)</small></div><div class="expl">';
      for (const t of Object.keys(HF_RULES.spectralTypes)) {
        const n = S.factoryCount(G, t);
        h += '<div class="expl-col"><div class="expl-t" data-tip="' + esc(HF_RULES.spectralTypes[t]) + '">' + t + '</div>' + HF_RULES.stockPrice.map((v, i) =>
          '<div class="expl-cell' + (Math.min(n, 3) === i ? ' on' : '') + '">' + v + '</div>').join('') + '<div class="muted small">' + n + ' fact.</div></div>';
      }
      h += '</div></section>';
      h += '<section><div class="sec-h">Glory chits</div><div class="glory-row">' + HF_RULES.zones.map(z =>
        '<span class="glory-chip' + (G.glory[z.id] ? '' : ' taken') + (UI.gfx.on() ? ' gimg' : '') + '" data-tip="' + esc(z.name + ': ' + (G.glory[z.id] ? 'still on the map' : 'taken') + ' - ' + z.gloryVp + ' VP in LEO') + '">' +
          (UI.gfx.on() ? '<img src="' + UI.gfx.glory(z.id) + '" alt="">' : z.symbol + ' ' + z.gloryVp) + '</span>').join('') + '</div></section>';
      if (G.config.heroism) {
        h += '<section><div class="sec-h">Heroism chits</div>' + HF_RULES.heroism.map(x =>
          '<div class="hero-row"><span>🎖 ' + esc(x.name) + '</span>' + (G.heroism[x.id] === null ? '<span class="muted">unawarded</span>' : UI.pname(G.players[G.heroism[x.id]])) + '</div>').join('') + '</section>';
      }
      return h;
    },

    /** Stacked VP bars per player (what the score is made of) + game clock. */
    vpRace(G) {
      const parts = [['tokens', 'Claims, Factories, Colonies, Rocket', '#8fb3ff'], ['stock', 'Factory stock value', '#ffb547'], ['colonyBonus', 'Colony bonus', '#42d17a'],
        ['glory', 'Glory', '#ffd34d'], ['heroism', 'Heroism', '#ff8ad8']];
      const scores = G.players.map((p, pi) => ({ pi, s: HF.events.score(G, pi) })).sort((a, b) => b.s.total - a.s.total || G.players[b.pi].aquas - G.players[a.pi].aquas);
      const max = Math.max(10, ...scores.map(x => x.s.total));
      const total = G.config.cycles * 12;
      let h = '<section class="vprace"><div class="sec-h">Victory points <small class="muted">(ties: most Aquas)</small></div>';
      h += scores.map((x, rank) => {
        const p = G.players[x.pi];
        return '<div class="vr-row' + (x.pi === G.current ? ' current' : '') + '" style="--pc:' + S.color(p) + '"><span class="vr-rank">' + (rank + 1) + '</span>' +
          '<span class="vr-name">' + UI.dot(p) + ' ' + esc(p.name) + '</span><div class="vr-bar">' + parts.map(([k, l, c]) => x.s[k] ? '<i style="width:' + (x.s[k] / max * 100).toFixed(1) + '%;background:' + c + '" data-tip="' + esc(l + ': ' + x.s[k] + ' VP') + '"></i>' : '').join('') +
          '</div><b class="vr-total">' + x.s.total + '</b></div>';
      }).join('');
      h += '<div class="vr-legend">' + parts.map(([, l, c]) => '<span><i style="background:' + c + '"></i>' + l + '</span>').join('') + '</div>';
      h += '<div class="vr-clock" data-tip="The game ends when the last Seniority Disk is taken (at a seniority threshold), or after ' + total + ' years"><span>Year ' + G.year + ' / ' + total + '</span>' +
        '<div class="vr-clock-bar"><i style="width:' + Math.min(100, G.year / total * 100).toFixed(1) + '%"></i></div><span>' + G.seniority + ' Seniority Disk' + (G.seniority === 1 ? '' : 's') + ' left</span></div></section>';
      return h;
    },

    /* ---------------- Log ---------------- */
    log(G) {
      const f = HF.app.ui.logFilter || 'all';
      const items = G.log.filter(e => f === 'all' || (f === 'dice' && e.kind === 'dice') || (f === 'event' && (e.kind === 'event' || e.kind === 'system')) ||
        (f === 'me' && e.player === G.current)).slice().reverse();
      return '<div class="chips">' + [['all', 'All'], ['dice', 'Dice'], ['event', 'Events'], ['me', 'Current player']].map(([k, l]) =>
        '<button class="chip' + (f === k ? ' on' : '') + '" data-act="log-filter" data-f="' + k + '">' + l + '</button>').join('') + '</div>' +
        '<div class="log-full">' + items.map(P.logLine(G)).join('') + '</div>';
    },

    /* ---------------- Rules ---------------- */
    rules() {
      return '<div class="rules-ref">' +
        '<h3>Year (D)</h3><ol><li>Each player, from the 1st Player clockwise, takes a Turn: <b>move</b> the Rocket once, perform <b>one Operation</b>, and any number of <b>free actions</b> (not during a move or an operation).</li>' +
        '<li>The Sunspot Cube advances. Crossing an <b>event</b> space rolls an Event; crossing the <b>seniority</b> threshold removes a Seniority Disk - the last one ends the game.</li></ol>' +
        '<h3>Movement (H)</h3><ul><li>Activate one Operational thrust triangle. <b>Net thrust</b> = base + afterburn (+1) + weight class + solar + push + supports. It is the <b>burn limit</b>.</li>' +
        '<li>Entering a magenta <b>Burn</b> space costs 1 burn (fuel steps = fuel consumption). <b>Lander burns</b>: no stopping, no bonus burns, half-landers cost half fuel.</li>' +
        '<li>Turning at a <b>Hohmann</b> is a Pivot: 2 burns. Turning at any marked space is free. Stopping and leaving next turn (loiter) is free. No U-turns.</li>' +
        '<li><b>Flybys</b> give Bonus Burns (no fuel, no limit). <b>Radiation belts</b>: Belt Roll 1d6 − net thrust (+2 in red); cards with rad-hardness below it are lost.</li>' +
        '<li><b>Hazards</b>: roll 1d6, on a 1 the whole stack is lost - or pay FINAO (4 Aquas).</li>' +
        '<li><b>Landing/liftoff</b> on a Site: net thrust &gt; Size, or aerobrake path (landing only), or factory-assist (Hazard Roll unless colonised/Powersat).</li></ul>' +
        '<h3>Operations (I)</h3><ul>' + HF_RULES.operations.map(o => '<li><b>' + o.name + '</b> (' + o.ref + '): ' + o.text + '</li>').join('') + '</ul>' +
        '<h3>Supports (J)</h3><ul><li>Cards list required generators (⚡E electric / ⚡P pulsed), reactors (☢S/B/X) and cooling Therms (❄). A card is <b>Operational</b> when Operational supports of that kind are in the same stack and the stack’s radiators give enough Therms.</li>' +
        '<li>Wrench supports in the activated thruster’s chain modify thrust / fuel consumption. You pick one support per requirement when preparing a move (J1c).</li></ul>' +
        '<h3>Negotiation (N) & Felonies</h3><ul><li>Any two players may trade Aquas, Hand cards, stack cargo, whole stacks and Claims at any time except during movement (🤝 Deal). ' +
        'A traded Hand card cannot be traded on before its previous holder’s next Turn; a Crew in an opponent’s stack stays under its owner’s control.</li>' +
        '<li>Felonies need the Felonious privilege or Anarchy: claim jump, Murder/Suicide (once per Turn) and Factory Hijack - using an opponent’s Factory without permission while your Human is there and no enemy Human is.</li></ul>' +
        '<h3>Events (K2)</h3><table class="ev"><tr><td>1-2</td><td>Inspiration: top card of each deck to the bottom</td></tr><tr><td>3</td><td>Glitch: each player glitches their biggest unmanned stack</td></tr>' +
        '<tr><td>4</td><td>Pad explosion: each player loses their heaviest White-Side card in LEO</td></tr><tr><td>5-6</td><td>Blue: Anarchy · Yellow: Budget cuts · Red: Solar flare</td></tr></table>' +
        '<h3>Scoring (M2)</h3><ul><li>1 VP per Claim disk, Factory cube, Colony dome and Rocket figure on the map.</li><li>Each Factory: its stock price (8 / 5 / 4).</li>' +
        '<li>Colony on astrobiology +1, submarine +2. Glory chits as shown, heroism 2 VP. Ties: most Aquas.</li></ul>' +
        '<p class="hint">Card data transcribed from the official Vassal module; some printed abilities are display-only (see README). How to use the app: the Help tab.</p></div>';
    },

    /* ---------------- Help: how to use the app ---------------- */
    help() {
      const kb = (k) => k.split(' / ').map(x => '<kbd>' + esc(x) + '</kbd>').join(' / ');
      return '<div class="rules-ref help-ref">' +
        '<p class="hint">How to use this table. The game rules themselves: <a data-act="rules-summary">rules summary</a> (also in the Menu).</p>' +
        '<h3>❶ Your turn</h3><ul>' +
        '<li>The <b>Turn</b> tab is your checklist: <b>1 Movement</b> (once, optional), <b>2 Operation</b> (exactly one), <b>free actions</b> (any number), then <b>End turn</b>.</li>' +
        '<li><b>Next steps</b> at the top suggests what you can do right now; click a suggestion to do it.</li>' +
        '<li>Greyed-out buttons say why they are not possible - hover or click them.</li>' +
        '<li>Between turns a <b>hand-over</b> screen tells the next player what happened since their last turn.</li></ul>' +
        '<h3>❷ Moving the Rocket</h3><ul>' +
        '<li><b>Move ▸</b> (or ' + kb('M') + ') picks the thrust triangle: net thrust, fuel per burn, the support chain and afterburn.</li>' +
        '<li>Glowing Spaces on the map are legal steps; badges show the cost (<b>B</b> burns, <b>b</b> bonus burns, ✦ bonus pivot, ☠ hazard, ☢ radiation belt, <b>+n</b> flyby, ⬇ landing). Hover a Space to see your burns and fuel <i>after</i> that step; click it to move.</li>' +
        '<li>The bar under the map counts burns and fuel. ' + kb('Enter') + ' ends the move, ' + kb('Backspace') + ' steps back, ' + kb('Esc') + ' cancels before the first step. Hazards ask whether to pay FINAO or roll.</li></ul>' +
        '<h3>❸ Cards, cargo and fuel</h3><ul>' +
        '<li>The <b>dock</b> under the map shows your Rocket with its Fuel Strip, the stacks on the same Space and your Hand. <b>Drag</b> cards and FT chips between stacks (cargo transfer), onto the <b>New outpost</b> slot, or onto the Fuel Strip to load fuel. ' + kb('D') + ' shows or hides it - also while moving.</li>' +
        '<li><b>Playmat</b> (' + kb('P') + ', or the <b>Mat</b> button) shows a playmat as on the table: Fuel Strip with the Dry and Wet chits, all four stacks wherever they are, the Hand and the fuel actions; drag cards there too, drop Hand cards on LEO to boost them, click FT beads to jettison them. The player chips at its top show the other players\' mats. During a move it opens read-only.</li>' +
        '<li>Hover a card to see it large; <b>hold ' + kb('Alt') + ' to see the other side</b> of every card (Black-Side / White-Side, the Crew\'s other faction). Click a card for its actions (move, decommission, rotate a radiator).</li>' +
        '<li>A green dot on a card means Operational; hover the card for the reason when it is not.</li></ul>' +
        '<h3>❹ Operations and auctions</h3><ul>' +
        '<li>Pick the Operation in the Turn tab. Patents: the <b>Patents</b> tab shows each deck\'s top card - <b>Auction</b> it.</li>' +
        '<li>In a <b>Research Auction</b> the device goes around the table: each player in turn <b>Raises</b>, <b>Matches</b>, <b>Holds</b> or <b>Passes</b>; ties are settled automatically or by the initiator.</li></ul>' +
        '<h3>❺ Between players</h3><ul>' +
        '<li><b>🤝 Deal</b> (' + kb('N') + ') carries out a negotiated deal at any time: Aquas, Hand cards, cargo, whole stacks, Claims.</li>' +
        '<li>The <b>Players</b> tab shows the VP race, every faction, the exploitation tracks and the chits; click a name in the top bar to see that player\'s mat.</li></ul>' +
        '<h3>❻ The map</h3><ul>' +
        '<li>Drag to pan, wheel to zoom, the minimap (top right) to jump; ' + kb('F') + ' fits the whole map, ' + kb('C') + ' centres on the current player.</li>' +
        '<li>Hover a Space for its type and what is there; click a Site to inspect it (size, type, hydration, owner, stacks). The ∷ button colours every Space by type.</li></ul>' +
        '<h3>❼ Undo, saving, settings</h3><ul>' +
        '<li><b>Undo</b> (' + kb('Ctrl+Z') + ') works within the turn until a die is rolled - dice are final.</li>' +
        '<li>The game server keeps every game: the title screen lists them under <i>Continue a game</i>. <b>Save</b> downloads a file, <i>Menu → Load game file</i> opens one.</li>' +
        '<li><b>Menu</b>: hand-over screen, hints, the end-turn confirmation, original game graphics, card library, rules summary.</li></ul>' +
        '<h3>⌨ Keyboard</h3><table class="keys">' + HF.app.SHORTCUTS.map(([k, d]) => '<tr><td>' + kb(k) + '</td><td>' + esc(d) + '</td></tr>').join('') + '</table>' +
        '</div>';
    }
  };

  UI.panels = P;
})(window.HF = window.HF || {});
