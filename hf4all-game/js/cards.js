/*
 * Card lookup, card-instance stats and the support/operational analysis (J).
 *
 * A card instance in a stack is { id, side: 'w'|'b', orient: 'heavy'|'light' }.
 * The Crew is { id: 'crew', crew: '<crew card id>', face: 0|1, glory: zoneId|null }.
 */
(function (HF) {
  'use strict';

  const DEF = {};
  const DECK_OF = {};
  for (const deck in HF_CARDS.decks) {
    for (const c of HF_CARDS.decks[deck]) { DEF[c.id] = c; DECK_OF[c.id] = deck; }
  }
  const CREW = {};
  for (const c of HF_CARDS.crews) CREW[c.id] = c;

  const DECKS = ['thruster', 'robonaut', 'refinery', 'generator', 'reactor', 'radiator'];
  const DECK_INFO = {
    thruster: { name: 'Thrusters', short: 'THR', color: '#d9534f' },
    robonaut: { name: 'Robonauts', short: 'ROB', color: '#5b8def' },
    refinery: { name: 'Refineries', short: 'REF', color: '#e0a43a' },
    generator: { name: 'Generators', short: 'GEN', color: '#c86ee0' },
    reactor: { name: 'Reactors', short: 'RCT', color: '#3fb7a6' },
    radiator: { name: 'Radiators', short: 'RAD', color: '#8a9bb0' },
    crew: { name: 'Crew', short: 'CREW', color: '#ff8a3d' }
  };

  /** Does side `s` provide support `type` ('gen'|'reactor') of one of the `wanted` subtypes? */
  function provides(s, type, wanted) {
    const p = s.prov && s.prov[type];
    return !!p && p.some(x => wanted.includes(x));
  }

  const C = {
    DECKS, DECK_INFO, provides,
    def(id) { return DEF[id]; },
    deckOf(id) { return DECK_OF[id]; },
    crew(id) { return CREW[id]; },
    allIds() { return Object.keys(DEF); },

    /** Side stats for a hand card id viewed from a given side (each side is complete in the data). */
    sideStats(id, side) {
      const d = DEF[id];
      const s = Object.assign({}, side === 'b' ? d.b : d.w);
      s.deck = DECK_OF[id];
      s.cardId = id;
      s.sideKey = side;
      return s;
    },

    /** Image path of a card side (extracted by tools/extract_cards.py). */
    image(id, side) { return 'assets/cards/' + id + '-' + (side === 'b' ? 'b' : 'w') + '.jpg'; },
    crewImage(crewId, face) { return 'assets/cards/crew-' + crewId + '-' + face + '.jpg'; },

    /** Stats of a card instance in a stack (resolves Crew and radiator orientation). */
    stats(inst) {
      if (inst.crew) {
        const cs = CREW[inst.crew].sides[inst.face];
        return {
          deck: 'crew', name: cs.faction, faction: cs.faction, mass: cs.mass, rad: cs.rad,
          thrust: cs.thrust, isru: cs.isru, human: true, clout: cs.clout, engine: cs.engine,
          privilege: cs.privilege, spec: null, cardId: 'crew', crewId: inst.crew, face: inst.face
        };
      }
      const s = C.sideStats(inst.id, inst.side);
      if (s.deck === 'radiator') s.orient = inst.orient === 'light' ? 'light' : 'heavy';
      if (s.deck === 'radiator' && inst.orient === 'light' && s.light) {
        s.mass = s.light.mass;
        s.therms = s.light.therms;
        if (s.light.rad != null) s.rad = s.light.rad;
      }
      return s;
    },

    /** ISRU platform kinds of an isru entry ("raygun+missile" -> ['raygun','missile']). */
    platforms(isru) { return isru ? String(isru.k).split('+') : []; },

    massOf(inst) { return C.stats(inst).mass || 0; },
    isHuman(inst) { return !!inst.crew; },
    hasThrust(inst) { const s = C.stats(inst); return !!(s.thrust); },

    gradeName(g) { return { dirt: 'Dirt', water: 'Water', isotope: 'Isotope' }[g] || '-'; },

    /**
     * Support analysis for a stack (J1-J4).
     *   cards  - array of card instances
     *   zone   - heliocentric zone id of the stack
     * Returns { supported: bool[], therms: available therms, reasons: string[][] }.
     * `supported` uses the greatest fixpoint so mutually supporting pulsed
     * generators and reactors (J1e) both count.
     */
    analyze(cards, zone) {
      const n = cards.length;
      const st = cards.map(C.stats);
      const alive = new Array(n).fill(true);
      const reasons = st.map(() => []);
      const neptune = HF.zone(zone) && HF.zone(zone).solar === null;
      st.forEach((s, i) => {
        if (cards[i].nonOp) { alive[i] = false; reasons[i].push('declared non-operational'); }
        if (s.solar && neptune) { alive[i] = false; reasons[i].push('no solar power in the Neptune zone'); }
      });
      let changed = true;
      while (changed) {
        changed = false;
        for (let i = 0; i < n; i++) {
          if (!alive[i]) continue;
          const miss = C.missingSupports(st, alive, i);
          if (miss.length) { alive[i] = false; reasons[i].push('needs ' + miss.join(' + ')); changed = true; }
        }
      }
      let therms = 0;
      st.forEach((s, i) => { if (alive[i] && s.deck === 'radiator') therms += s.therms || 0; });
      return { st, supported: alive, therms, reasons };
    },

    missingSupports(st, alive, i) {
      const r = st[i].req || {};
      const miss = [];
      if (r.gen && r.gen.length && !st.some((s, j) => j !== i && alive[j] && provides(s, 'gen', r.gen))) {
        miss.push(r.gen.join('/') + ' generator');
      }
      if (r.reactor && r.reactor.length && !st.some((s, j) => j !== i && alive[j] && provides(s, 'reactor', r.reactor))) {
        miss.push(r.reactor.join('/') + ' reactor');
      }
      return miss;
    },

    /** Supported cards that can fill requirement `type` ('gen' | 'reactor') of card k (J1a, J1b). */
    providers(an, k, type) {
      const want = (an.st[k].req || {})[type];
      if (!want || !want.length) return [];
      const out = [];
      an.st.forEach((s, j) => {
        if (j !== k && an.supported[j] && provides(s, type, want)) out.push(j);
      });
      return out;
    },

    /**
     * The support chain (J1c) of card i as requirement slots: one provider per
     * requirement of the card and, recursively, of its providers.
     * `choice` maps 'k:type' (k = card index, type = 'gen' | 'reactor') to the
     * provider the player picked; an unpicked slot takes the provider with the
     * best thrust modifier (solarMod = zone's solar modifier), reusing a support
     * already in the chain when possible. A picked provider that cannot fill the
     * slot is replaced by the automatic pick and the slot is marked `invalid`.
     * Returns { chain: card indices, slots: [{ k, type, key, options, pick, depth, chosen, invalid }] }.
     */
    chainSlots(an, i, solarMod, choice) {
      const picked = new Set([i]);
      const slots = [];
      const score = (j) => {
        const s = an.st[j], m = s.mod || {};
        return (m.thrust || 0) + (m.solar && solarMod != null ? solarMod : 0) - (s.req ? 0.1 : 0) + (picked.has(j) ? 100 : 0);
      };
      const visit = (k, depth) => {
        for (const type of ['gen', 'reactor']) {
          if (!((an.st[k].req || {})[type] || []).length) continue;
          const options = C.providers(an, k, type);
          const key = k + ':' + type;
          const want = choice && choice[key] != null ? +choice[key] : null;
          const slot = { k, type, key, options, depth, pick: -1, chosen: want != null, invalid: false };
          if (want != null && options.includes(want)) slot.pick = want;
          else {
            slot.invalid = want != null;
            let bestScore = -Infinity;
            for (const j of options) { const sc = score(j); if (sc > bestScore) { bestScore = sc; slot.pick = j; } }
          }
          slots.push(slot);
          if (slot.pick >= 0 && !picked.has(slot.pick)) { picked.add(slot.pick); visit(slot.pick, depth + 1); }
        }
      };
      visit(i, 0);
      return { chain: [...picked], slots };
    },

    /** Card indices of the support chain of card i (see chainSlots). */
    chain(an, i, solarMod, choice) { return C.chainSlots(an, i, solarMod, choice).chain; },

    chainTherms(an, chain) { return HF.sum(chain, j => (an.st[j].req && an.st[j].req.therms) || 0); },

    /** Is card i Operational for a normal use (supports + therms)? */
    operational(an, i, opts) {
      opts = opts || {};
      if (!an.supported[i]) return false;
      if (opts.ignoreTherms) return true;
      const ch = C.chain(an, i, null);
      return C.chainTherms(an, ch) <= an.therms + (opts.extraTherms || 0);
    },

    statusText(an, i) {
      if (!an.supported[i]) return an.reasons[i].join('; ');
      const ch = C.chain(an, i, null);
      const need = C.chainTherms(an, ch);
      if (need > an.therms) return 'needs ' + need + ' therm' + (need > 1 ? 's' : '') + ' (stack has ' + an.therms + ')';
      return 'Operational';
    },

    /**
     * Effective ISRU rating of card i (I6.1): assaying smelters (Divining Nubots -1),
     * MagBeam when pushed (opts.pushed), and SCOOP cards at aerostat Sites
     * (opts.aerostat, -2). Never below 0.
     */
    isru(an, i, opts) {
      opts = opts || {};
      const s = an.st[i];
      if (!s.isru) return null;
      let r = s.isru.r;
      const colocated = (key) => an.st.some((x, j) => j !== i && x.ab && x.ab[key] && C.operational(an, j));
      if (colocated('assay')) r -= 1;
      if (opts.pushed && s.ab && s.ab.pushIsru) r += s.ab.pushIsru;
      if (opts.aerostat) {
        const scoop = an.st.reduce((m, x, j) => (x.ab && x.ab.scoopIsru && C.operational(an, j) ? Math.min(m, x.ab.scoopIsru) : m), 0);
        r += scoop;
      }
      return { r: Math.max(0, r), k: s.isru.k, kinds: C.platforms(s.isru) };
    }
  };

  HF.cards = C;
})(window.HF = window.HF || {});
