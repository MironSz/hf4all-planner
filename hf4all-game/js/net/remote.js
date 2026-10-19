/*
 * The rules engine as seen by the browser: thin stand-ins for the modules the
 * server runs (HF.actions, HF.ops, HF.movement, HF.events, HF.negotiation).
 *
 *  - A command (e.g. HF.ops.income(G)) called inside HF.app.act(fn) is not
 *    executed here: it is recorded, and act() sends the recorded commands to
 *    the server as one step.
 *  - A query (e.g. HF.movement.options(G)) answers from the view the server
 *    computed with the state (HF.app.V), or - for pure lookups of the state
 *    (trade locks, owned Sites) - directly from the state.
 * No rule is decided in the browser.
 */
(function (HF) {
  'use strict';

  const Remote = { recording: null };
  const V = () => (HF.app && HF.app.V) || {};

  const cmd = (name) => function (G, ...args) {
    if (!Remote.recording) throw new Error('Command ' + name + ' used outside HF.app.act()');
    Remote.recording.push({ cmd: name, args: args.map(a => (a === undefined ? null : a)) });
  };
  const cmds = (prefix, names) => Object.fromEntries(names.map(n => [n, cmd(prefix + '.' + n)]));

  HF.actions = Object.assign(cmds('actions', ['transfer', 'loadFuel', 'ftToFuel', 'fuelToFt', 'jettisonFuel', 'jettisonFts',
    'voluntaryDecommission', 'radiatorToLight', 'phileasFogg', 'dirtRefuel', 'mooncable', 'swapRocket', 'buildColony', 'claimJump',
    'loadGlory', 'glitchRepair', 'murder', 'discardHand', 'discardFactory', 'discardClaim']), {
    dirtRefuelInfo: () => V().dirtRefuel || { ok: false, reason: 'Not available.' },
    murderOptions: () => V().murderOptions || [],
    finaoCost: (G, p) => (HF.state.privilegeActive(G, p, 'openSourceFinao') ? 3 : 4)
  });

  HF.ops = Object.assign(cmds('ops', ['income', 'startAuction', 'resolveAuction', 'sellHand', 'sellBlack', 'boost', 'refuel',
    'prospect', 'industrialize', 'etProduce', 'deliver', 'mineRevival']), {
    canBid: (G, pi) => G.players[pi].hand.length < 4 || HF.state.privilegeActive(G, G.players[pi], 'skunkworks'),
    /** Stock price of a Black-Side card in one of the current player's stacks in LEO (I3b), or null. */
    blackSalePrice(G, card) {
      const p = G.players[G.current];
      for (const k of HF.state.ownStackKeysAt(p, HF_RULES.LEO)) {
        const i = p.stacks[k].cards.indexOf(card);
        if (i >= 0) { const v = (V().blackSalePrices || {})[k + ':' + i]; return v == null ? null : v; }
      }
      return null;
    },
    /** Boost cost is the printed Mass (light radiators: light Mass) - card data, no rule. */
    boostCost: (G, items) => HF.sum(items, it => {
      const s = HF.cards.sideStats(G.players[G.current].hand[it.handIdx], 'w');
      return s.deck === 'radiator' && it.orient === 'light' ? s.light.mass : s.mass;
    }),
    refuelOptions: () => V().refuelOptions || [],
    prospectOptions: () => V().prospectOptions || [],
    industrializeOptions: () => V().industrializeOptions || [],
    mineRevivalOptions: () => V().mineRevivalOptions || [],
    etOptions: () => V().etOptions || [],
    deliveryOptions: () => V().deliveryOptions || [],
    deliveryCost: (node) => 2 * HF.map.zonesFromEarth(node) + (HF.map.node(node).size >= 7 ? 1 : 0),
    isLuna: (id) => /^Luna/i.test(HF.map.node(id).name || ''),
    /** Size-roll modifier, hydration and Luna flag of a prospect target (from the server's view). */
    prospectTarget: (optIndex, siteId) => ((V().prospectTargets || [])[optIndex] || {})[siteId] || { mod: 0, water: HF.map.node(siteId).water, luna: false }
  });

  HF.movement = Object.assign(cmds('movement', ['start', 'step', 'end', 'cancel', 'saveEngine', 'deleteEngine', 'startEngine']), {
    thrustOptions: () => V().thrustOptions || [],
    /** The current player's engines - built ones and supportless thrust triangles - with previews. */
    engines: () => V().engines || [],
    /** A thrust triangle that needs a generator or reactor: it moves only as a built engine. */
    needsSupports: (s) => !!(s && s.thrust && s.req && (((s.req.gen || []).length) || ((s.req.reactor || []).length))),
    options: () => V().moveOptions || [],
    canEnd: () => V().canEnd || { ok: false },
    thrusterAlive: () => V().thrusterAlive !== false,
    /** The default activation preview; other variants come from HF.app.query('movement.plan', ...). */
    plan: (G, idx) => (V().thrustOptions || []).find(o => o.idx === idx) || null
  });

  HF.events = Object.assign(cmds('events', ['endTurn', 'resolveBudgetCut', 'resolveGlitch', 'resolvePadExplosion', 'awardHeroism']), {
    score: (G, pi) => (V().scores || [])[pi] || { claims: 0, factories: 0, colonies: 0, rocket: 0, stock: 0, colonyBonus: 0, glory: 0, heroism: 0, tokens: 0, total: 0 },
    endTurnProblems: () => V().endTurnProblems || []
  });

  HF.negotiation = Object.assign(cmds('negotiation', ['giveAquas', 'giveHandCards', 'giveStackItems', 'giveStack', 'giveSite']), {
    canTrade: () => V().canTrade || { ok: false, reason: 'Not now.' },
    tradeLock: (G, id) => (G.tradeLocks || []).find(l => l.id === id) || null,
    /** Where cards from a stack can go in another player's playmat (same Space, or a free slot there). */
    stackTargets(G, from, key, to) {
      const S = HF.state, a = G.players[from], b = G.players[to];
      const node = S.stackNode(a, key);
      const out = [];
      for (const k of S.STACKS) {
        if (b.stacks[k] && S.stackNode(b, k) === node) out.push({ key: k, label: S.STACK_NAME[k], exists: true });
        else if (!b.stacks[k] && k !== 'leo') out.push({ key: k, label: 'new ' + S.STACK_NAME[k] + ' here', exists: false });
      }
      return out;
    },
    freeSlots: (G, to) => ['rocket', 'out1', 'out2'].filter(k => !G.players[to].stacks[k]),
    ownedSites: (G, pi) => Object.keys(G.sites).filter(id => G.sites[id].claim === pi)
  });

  /** Server view of a stack: card statuses, cooling (therms) and mass. */
  HF.cards.stackViewAt = function (pi, key) {
    const pv = (V().stacks || [])[pi];
    return (pv && pv[key]) || { status: [], ok: [], therms: 0, mass: 0 };
  };

  /** Card status in a stack as decided by the server ("Operational" or why not). */
  HF.cards.statusAt = function (pi, key, i) {
    const pv = (V().stacks || [])[pi];
    const sv = pv && pv[key];
    return sv && sv.status[i] != null ? sv.status[i] : '';
  };

  HF.remote = Remote;
})(window.HF = window.HF || {});
