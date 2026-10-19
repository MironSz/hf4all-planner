/* Shared helpers and the global HF namespace. Loaded first. */
(function (HF) {
  'use strict';

  HF.version = '0.1.0';

  /** Thrown by rule checks; the UI shows `message` as a toast. */
  class RuleError extends Error {
    constructor(message) { super(message); this.name = 'RuleError'; }
  }
  HF.RuleError = RuleError;
  HF.fail = function (message) { throw new RuleError(message); };
  HF.check = function (cond, message) { if (!cond) throw new RuleError(message); };

  HF.clone = function (v) { return JSON.parse(JSON.stringify(v)); };

  /** Seeded RNG (mulberry32). The state lives in the game so dice are replayable. */
  HF.rng = {
    next(G) {
      let t = (G.rng = (G.rng + 0x6D2B79F5) >>> 0);
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    },
    int(G, n) { return Math.floor(HF.rng.next(G) * n); },
    shuffle(G, arr) {
      for (let i = arr.length - 1; i > 0; i--) {
        const j = HF.rng.int(G, i + 1);
        [arr[i], arr[j]] = [arr[j], arr[i]];
      }
      return arr;
    },
    /**
     * Roll 1d6. Every roll is logged and queued in G.rolls for the dice
     * overlay, and locks undo (dice are final).
     */
    d6(G, label, playerIdx) {
      const v = 1 + HF.rng.int(G, 6);
      G.rollSeq = (G.rollSeq || 0) + 1;
      G.rolls.push({ label, value: v, player: playerIdx == null ? null : playerIdx, seq: G.rollSeq });
      G.diceLocked = true;
      HF.log(G, (label || 'Roll') + ': rolled ' + v, { kind: 'dice', player: playerIdx });
      return v;
    }
  };

  /** Append to the game log. */
  HF.log = function (G, text, opts) {
    opts = opts || {};
    G.log.push({ year: G.year, player: opts.player == null ? G.current : opts.player, text, kind: opts.kind || 'info' });
    if (G.log.length > 800) G.log.splice(0, G.log.length - 800);
  };

  /** Format a fuel number: 0.5 -> "½", 0.1 -> "1/10", 1.5 -> "1½". */
  HF.fmtNum = function (x) {
    if (x == null) return '';
    const whole = Math.floor(x + 1e-9);
    const frac = x - whole;
    const table = [[0.5, '½'], [0.25, '¼'], [0.75, '¾'], [1 / 3, '⅓'], [2 / 3, '⅔'], [0.1, '⅒']];
    if (frac < 1e-6) return String(whole);
    for (const [v, s] of table) {
      if (Math.abs(frac - v) < 1e-6) return (whole ? whole : '') + s;
    }
    return x.toFixed(2).replace(/0+$/, '');
  };

  HF.escape = function (s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  };

  HF.sum = function (arr, f) { let s = 0; for (const x of arr) s += f ? f(x) : x; return s; };

  HF.zoneIndex = function (zoneId) { return HF_RULES.zones.findIndex(z => z.id === zoneId); };
  HF.zone = function (zoneId) { return HF_RULES.zones.find(z => z.id === zoneId); };

  HF.seasonAt = function (pos) { return HF_RULES.cycle.spaces[pos].season; };

  HF.cap = function (s) { return s ? s.charAt(0).toUpperCase() + s.slice(1) : s; };

  HF.plural = function (n, one, many) { return n + ' ' + (n === 1 ? one : (many || one + 's')); };
})(window.HF = window.HF || {});
