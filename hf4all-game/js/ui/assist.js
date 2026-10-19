/*
 * Turn assistant: context-aware next-step suggestions for the active player,
 * the "since your last turn" digest for the hand-over screen, and the
 * end-turn guard.
 */
(function (HF) {
  'use strict';

  const UI = HF.ui;
  const S = HF.state;
  const MAP = HF.map;
  const FS = HF.FS;
  const O = HF.ops;
  const esc = HF.escape;

  const Assist = {
    /**
     * Suggestions for the active player, most useful first.
     * Each: { icon, text, act (side-panel data-act), data (extra attrs), kind: 'go'|'info'|'warn' }
     */
    suggestions(G) {
      const p = G.players[G.current];
      const out = [];
      const add = (icon, text, act, data, kind) => out.push({ icon, text, act, data: data || '', kind: kind || 'go' });
      if (G.pending || G.gameOver) return out;
      const r = p.stacks.rocket;
      const av = UI.panels.opAvailability(G);

      if (G.move) {
        add('🚀', 'You are moving: click a glowing Space on the map, then End movement.', null, '', 'info');
        return out;
      }
      // Rocket readiness
      if (!r) {
        if (p.stacks.leo.cards.length) add('🛠', 'Build a Rocket: drag cards from LEO onto the "No Rocket" slot of the ship dock.', null, '', 'go');
        else if (p.hand.length) add('🚀', 'Boost Hand cards to LEO (Operation) to start building a Rocket.', 'op', ' data-op="boost"');
      } else if (!G.turn.moved) {
        const fuel = FS.fuelSteps(r.fuel);
        const best = UI.dock.bestThrust(G);
        const unbuilt = r.cards.some(c => HF.movement.needsSupports(HF.cards.stats(c))) && !UI.dock.engines(G).some(e => !e.simple);
        if (unbuilt) add('🔧', 'Build an engine: pick a thruster of your Rocket and the supports it fires with (J1c).', 'engines', '', best ? 'info' : 'warn');
        if (!best && !unbuilt) add('⚠', 'Your Rocket has no engine that can fire - add a thruster and its supports.', 'tab', ' data-tab="mat"', 'warn');
        else if (!fuel && best.fuelCons > 0) {
          const here = S.ownStackKeysAt(p, r.node).some(k => k !== 'rocket' && S.stackFts(p, k) > 0) || r.fts > 0;
          add('⛽', here ? 'Your tanks are empty: load fuel (drop Aquas/FTs on the fuel gauge).' : 'Your tanks are empty and no FTs are here: refuel on a Site or coast.', here ? 'load-fuel' : null, '', 'warn');
        } else add('🚀', 'Move your Rocket: ' + esc(best.name) + ', net thrust ' + best.net + (best.fuelCons > 0 ? ', fuel for ' + HF.plural(Math.floor(best.fuelAvail / best.fuelCons), 'burn') : '') + '.', 'move-prep');
        // A few fuel steps above a weight class boundary: jettisoning them buys +1 net thrust
        // (the server's jettison ladder, from the planner's FuelStrip).
        const need = (HF.app.V || {}).jettisonToLighterClass;
        if (best && r.fuel && need != null && need <= 3 && need <= FS.fuelSteps(r.fuel)) {
          add('⚖', 'Jettisoning ' + HF.plural(need, 'fuel step') + ' drops you to the ' + FS.weightClass(r.fuel.wet - need).name + ' class (+1 net thrust).', 'jettison-fuel', '', 'info');
        }
      }
      // Operation
      if (!G.turn.op) {
        const PHRASE = { industrialize: 'Industrialize your Claim here', prospect: 'Prospect a Site from here', etProduction: 'Run ET Production at a Factory',
          delivery: 'Deliver a Black-Side card from a Factory', refuel: 'Refuel from the Site (Operation)', mineRevival: 'Revive the busted mine here' };
        const strong = ['industrialize', 'prospect', 'etProduction', 'delivery', 'refuel', 'mineRevival'].filter(id => av[id].ok);
        for (const id of strong) {
          const o = HF_RULES.operations.find(x => x.id === id);
          add(UI.panels.opIcon(id), PHRASE[id] + ' (' + o.ref + ').', 'op', ' data-op="' + id + '"');
        }
        if (!strong.length) {
          if (!av.auction.ok && p.hand.length >= 4) add('🃏', 'Auctions are locked (4+ Hand cards): boost, sell or discard first.', 'tab', ' data-tab="hand"', 'info');
          else add('🔨', 'No site operation here: research a patent, boost cards, or take Income.', 'tab', ' data-tab="patents"', 'info');
        }
      }
      // Free actions worth noticing
      const crew = S.crewLocation(G, p);
      if (crew && G.turn.gloryEligible === crew.node && G.glory[MAP.zoneOf(crew.node)]) add('★', 'Take the ' + HF.zone(MAP.zoneOf(crew.node)).name + ' glory chit with your Crew!', 'load-glory');
      for (const k of S.STACKS) if (p.stacks[k] && p.stacks[k].glitch) add('🔧', S.STACK_NAME[k] + ' is glitched - a Human on the same Space can repair it.', 'glitch-repair', ' data-key="' + k + '"', 'warn');
      if (S.season(G) === 'red') {
        const exposed = ['rocket', 'out1', 'out2'].filter(k => p.stacks[k] && Assist.flareExposed(p.stacks[k].node));
        if (exposed.length) add('☀', 'Red season: solar flares may hit your ' + exposed.map(k => S.STACK_NAME[k]).join(', ') + ' (not shielded by LEO, a Site or a planetary belt).', null, '', 'warn');
      }
      if (G.turn.op && (G.turn.moved || !r)) add('✓', 'Done for this year? End your turn.', 'end-turn', '', 'go');
      return out;
    },

    flareExposed(node) {
      const N = MAP.node(node);
      return node !== MAP.LEO && N.t !== 'site' && !MAP.isPlanetBelt(node);
    },

    /** Log lines since the player's previous turn ended (other players, year end, events). */
    digest(G, pi) {
      const p = G.players[pi];
      for (let i = G.log.length - 1; i >= 0; i--) {
        const e = G.log[i];
        if (e.kind === 'system' && e.text === p.name + ' ends the turn.') {
          return G.log.slice(i + 1).filter(x => x.kind !== 'dice' && !/ ends the turn\.$/.test(x.text));
        }
      }
      return [];
    },

    /** Turn-tab block with the suggestions. */
    html(G) {
      if (HF.app.settings.hints === false) return '';
      const list = Assist.suggestions(G).slice(0, 4);
      if (!list.length) return '';
      return '<section class="assist"><div class="as-h">Next steps <button class="as-off" data-act="hints-off" data-tip="Hide the hints (Menu turns them back on)">✕</button></div>' + list.map(s =>
        (s.act ? '<button class="as-item as-' + s.kind + '" data-act="' + s.act + '"' + s.data + '>' : '<div class="as-item as-' + s.kind + '">') +
        '<span class="as-ic">' + s.icon + '</span><span class="as-t">' + s.text + '</span>' + (s.act ? '<span class="as-go">▸</span></button>' : '</div>')).join('') + '</section>';
    },

    /** Ask before ending a turn with the Operation and/or movement unused. */
    guardEndTurn(G, go) {
      const p = G.players[G.current];
      const unused = [];
      if (!G.turn.op) unused.push('your Operation');
      if (p.stacks.rocket && !G.turn.moved && UI.dock.bestThrust(G)) unused.push('your Rocket\'s move');
      if (!unused.length || HF.app.settings.confirmEnd === false) return go();
      UI.modal({
        title: 'End turn?',
        body: '<p>You have not used ' + unused.join(' or ') + ' this year.</p><label class="chk"><input type="checkbox" class="ce-never"> Don\'t ask again</label>',
        buttons: [{ label: 'Keep playing' }, { label: 'End turn ▸', kind: 'primary', onClick: (c, w) => {
          if (w.querySelector('.ce-never').checked) { HF.app.settings.confirmEnd = false; HF.app.saveSettings(); }
          c(); go();
        } }]
      });
    }
  };

  UI.assist = Assist;
})(window.HF = window.HF || {});
