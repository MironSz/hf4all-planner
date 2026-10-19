/*
 * Game state creation and shared state queries.
 *
 * The whole game is one plain JSON object `G` (saved/undone by snapshot):
 *   G.players[i].stacks = { leo, rocket, out1, out2 }
 *     leo    : { cards, glitch }                      (FTs in LEO are the player's Aquas)
 *     rocket : { node, cards, fts, fuel, glitch }     (fuel = Fuel Strip, see fuelstrip.js)
 *     out1/2 : { node, cards, fts, glitch }
 *   G.sites[nodeId] = { claim: playerIdx|null, factory, colony, busted, visited: [playerIdx] }
 */
(function (HF) {
  'use strict';

  const STACKS = ['leo', 'rocket', 'out1', 'out2'];
  const STACK_NAME = { leo: 'LEO', rocket: 'Rocket', out1: 'Outpost 1', out2: 'Outpost 2' };

  const S = {
    STACKS, STACK_NAME,

    newGame(cfg) {
      const G = {
        v: 1,
        rng: (cfg.seed >>> 0) || 1,
        seed: (cfg.seed >>> 0) || 1,
        config: { cycles: cfg.cycles || 4, heroism: cfg.players.length >= 3 },
        year: 1, cyclePos: 0, seniority: cfg.cycles || 4,
        firstPlayer: 0, current: 0, turnNo: 0,
        turn: null, move: null, pending: null,
        anarchy: false,
        players: [], decks: {}, sites: {},
        glory: {}, heroism: {},
        log: [], rolls: [], diceLocked: false, gameOver: null
      };
      for (const d of HF.cards.DECKS) {
        G.decks[d] = HF.rng.shuffle(G, HF_CARDS.decks[d].map(c => c.id));
      }
      cfg.players.forEach((pc, i) => {
        const crew = HF.cards.crew(pc.crew);
        const side = crew.sides[pc.face];
        G.players.push({
          idx: i, name: pc.name || side.faction, color: crew.color,
          crew: pc.crew, face: pc.face, faction: side.faction, privilege: side.privilege, clout: side.clout,
          aquas: 6 + (side.privilege === 'secretaryGeneral' ? 2 : 0),
          hand: [],
          stacks: { leo: { cards: [{ id: 'crew', crew: pc.crew, face: pc.face, glory: null, uid: i + 1 }], glitch: false }, rocket: null, out1: null, out2: null },
          glories: [], heroism: []
        });
      });
      // C8: most clout (A best) becomes the 1st Player.
      let best = 0;
      G.players.forEach((p, i) => { if (p.clout < G.players[best].clout) best = i; });
      G.firstPlayer = best;
      G.current = best;
      for (const z of HF_RULES.zones) G.glory[z.id] = true;
      if (G.config.heroism) for (const h of HF_RULES.heroism) G.heroism[h.id] = null;
      G.turn = S.freshTurn();
      HF.log(G, 'New game: ' + G.players.map(p => p.name + ' (' + p.faction + ')').join(', ') + '. ' +
        G.players[best].name + ' has the most clout and is 1st Player.', { player: best, kind: 'system' });
      HF.log(G, 'Year 1 begins.', { kind: 'system' });
      return G;
    },

    freshTurn() {
      return { op: null, moved: false, mooncable: false, crewDirt: false, felony: false, murder: false, glitchRepairs: 0, opDetail: null };
    },

    /** Hand the turn to player pi (D1). Hand-card trade locks of pi expire now (N2a). */
    beginTurn(G, pi) {
      G.current = pi;
      G.turn = S.freshTurn();
      G.turnNo++;
      if (G.tradeLocks) G.tradeLocks = G.tradeLocks.filter(l => l.owner !== pi);
    },

    player(G, i) { return G.players[i == null ? G.current : i]; },
    color(p) { return HF_RULES.playerColors[p.color]; },
    privilegeActive(G, p, key) { return G.anarchy ? key === 'felonious' : p.privilege === key; },

    stackNode(p, key) {
      if (key === 'leo') return HF.map.LEO;
      return p.stacks[key] ? p.stacks[key].node : null;
    },

    stackFts(p, key) {
      if (key === 'leo') return p.aquas;
      return p.stacks[key] ? p.stacks[key].fts || 0 : 0;
    },

    addFts(p, key, n) {
      if (key === 'leo') { p.aquas += n; HF.check(p.aquas >= 0, 'Not enough Aquas.'); return; }
      const s = p.stacks[key];
      s.fts = (s.fts || 0) + n;
      HF.check(s.fts >= 0, 'Not enough FTs in ' + STACK_NAME[key] + '.');
    },

    /** Cargo mass of a stack: cards + FT beads (F2). */
    cargoMass(p, key) {
      const s = p.stacks[key];
      if (!s) return 0;
      return HF.sum(s.cards, HF.cards.massOf) + (key === 'leo' ? 0 : (s.fts || 0));
    },

    /** Recompute the Rocket's Dry Mass chit after cargo changed (F2b). */
    syncRocketDry(p) {
      const r = p.stacks.rocket;
      if (!r) return;
      const dry = Math.max(1, S.cargoMass(p, 'rocket'));
      if (!r.fuel) r.fuel = HF.FS.create(dry);
      else if (dry !== r.fuel.dry) HF.FS.setDry(r.fuel, dry, 'red');
    },

    /** All stacks (of every player) at a node. */
    stacksAt(G, nodeId) {
      const out = [];
      G.players.forEach((p, pi) => {
        for (const k of STACKS) {
          if (!p.stacks[k] || S.stackNode(p, k) !== nodeId) continue;
          out.push({ p: pi, key: k, stack: p.stacks[k] });
        }
      });
      return out;
    },

    ownStackKeysAt(p, nodeId) { return STACKS.filter(k => p.stacks[k] && S.stackNode(p, k) === nodeId); },

    stackIsEmpty(p, key) {
      const s = p.stacks[key];
      return !s || (s.cards.length === 0 && !(s.fts > 0));
    },

    /** E2e: an empty non-LEO stack loses its Figure. */
    cleanupStacks(G, p) {
      for (const k of ['rocket', 'out1', 'out2']) {
        if (p.stacks[k] && S.stackIsEmpty(p, k)) {
          HF.log(G, p.name + "'s " + STACK_NAME[k] + ' is empty and leaves the map.', { player: p.idx });
          p.stacks[k] = null;
        }
      }
    },

    /**
     * The player controlling a Crew card. A Crew moved into an opponent's
     * stack stays under its owner's control (N3d).
     */
    crewOwner(G, card, fallback) {
      const i = G.players.findIndex(p => p.crew === card.crew);
      return i >= 0 ? i : fallback;
    },

    /** Humans of each player at a node: Crew in any stack (by controller), Colonies, LEO (always manned). */
    humansAt(G, nodeId) {
      const set = new Set();
      G.players.forEach((p, pi) => {
        if (nodeId === HF.map.LEO) set.add(pi);
        for (const k of STACKS) {
          if (!p.stacks[k] || S.stackNode(p, k) !== nodeId) continue;
          for (const c of p.stacks[k].cards) if (c.crew) set.add(S.crewOwner(G, c, pi));
        }
      });
      const site = G.sites[nodeId];
      if (site && site.colony && site.claim != null) set.add(site.claim);
      return set;
    },

    /** Where p's Crew is: { pi: owner of the stack holding it, key, idx, node }. */
    crewLocation(G, p) {
      for (const q of G.players) {
        for (const k of STACKS) {
          const s = q.stacks[k];
          if (!s) continue;
          const i = s.cards.findIndex(c => c.crew && c.crew === p.crew);
          if (i >= 0) return { pi: q.idx, key: k, idx: i, node: S.stackNode(q, k) };
        }
      }
      return null;
    },

    /** Felonies are allowed with the Felonious privilege or during Anarchy (K2e). */
    canFelony(G, p) { return S.privilegeActive(G, p, 'felonious'); },

    /**
     * Can p commit a Felony at a node (glossary "Felony")? It needs the
     * privilege, p's Human on the Space (`aboard`: p's Crew arrives with the
     * moving Rocket) and no defending (enemy) Human there.
     */
    felonyAt(G, p, nodeId, aboard) {
      if (!S.canFelony(G, p)) return { ok: false, reason: 'Felonies need the Felonious privilege or Anarchy' };
      const humans = S.humansAt(G, nodeId);
      if (!humans.has(p.idx) && !aboard) return { ok: false, reason: 'your Human must be on the Space' };
      const enemy = [...humans].find(x => x !== p.idx);
      if (enemy != null) return { ok: false, reason: G.players[enemy].name + "'s Human defends it" };
      return { ok: true, reason: null };
    },

    /** Does the player have the Powersat Ability (privilege, push Factory, or card)? */
    hasPowersat(G, p) {
      if (S.privilegeActive(G, p, 'powersat')) return true;
      // Gained permanently through POWER GIRDLE / IONOSAT (lost during Anarchy like any privilege).
      if (p.powersat && !G.anarchy) return true;
      for (const id in G.sites) {
        const s = G.sites[id];
        if (s.factory && s.claim === p.idx && HF.map.node(id).push) return true;
      }
      for (const k of STACKS) {
        const st = p.stacks[k];
        if (!st) continue;
        const an = HF.cards.analyze(st.cards, HF.map.zoneOf(S.stackNode(p, k)));
        if (an.st.some((s, i) => s.ab && s.ab.powersat && HF.cards.operational(an, i))) return true;
      }
      return false;
    },

    site(G, id) {
      if (!G.sites[id]) G.sites[id] = { claim: null, factory: false, colony: false, busted: false, visited: [] };
      return G.sites[id];
    },

    factoryCount(G, spec) {
      let n = 0;
      for (const id in G.sites) if (G.sites[id].factory && HF.map.node(id).spec === spec) n++;
      return n;
    },

    stockPrice(G, spec) {
      const n = S.factoryCount(G, spec);
      return HF_RULES.stockPrice[Math.min(n, 3)];
    },

    counts(G, pi) {
      let claims = 0, factories = 0, colonies = 0;
      for (const id in G.sites) {
        const s = G.sites[id];
        if (s.claim !== pi) continue;
        claims++;
        if (s.factory) factories++;
        if (s.colony) colonies++;
      }
      return { claims, factories, colonies };
    },

    season(G) { return HF.seasonAt(G.cyclePos); },
    order(G) {
      const n = G.players.length;
      return Array.from({ length: n }, (_, k) => (G.firstPlayer + k) % n);
    },
    nextPlayer(G) {
      const ord = S.order(G);
      const k = ord.indexOf(G.current);
      return k + 1 < ord.length ? ord[k + 1] : null;
    },

    /** Find a card instance by stack and index, with its stats. */
    cardAt(p, key, idx) {
      const s = p.stacks[key];
      return s && s.cards[idx];
    }
  };

  HF.state = S;
})(window.HF = window.HF || {});
