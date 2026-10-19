/*
 * The HF4A Fuel Strip (F1-F4). A Rocket's tankage is
 *   { dry: integer Dry Mass, wet: Wet Mass chit position in black-line steps
 *     (0 = mass 1, 56 = mass 32), grade: 'water'|'dirt'|'isotope'|null }
 * Intervals between integer masses and the weight classes come from the
 * planner's FuelStrip.java, served by the game server as data/fuelrules.js.
 */
(function (HF) {
  'use strict';

  const R = window.HF_FUEL_RULES;
  const INTERVALS = R.intervals;
  const MAX_MASS = R.maxMass;
  const MAX_DRY_TO_MOVE = R.maxDryToMove;
  const STEP = [0, 0];
  for (let m = 2; m <= MAX_MASS; m++) STEP[m] = STEP[m - 1] + INTERVALS[m - 1];
  const MAX_STEP = STEP[MAX_MASS];

  const CLASSES = R.classes;

  const FS = {
    INTERVALS, MAX_MASS, MAX_STEP, MAX_DRY_TO_MOVE, CLASSES,

    massToStep(m) { return STEP[Math.max(1, Math.min(MAX_MASS, m))]; },

    /** Integer mass label at/below a step, steps into the next interval, interval size. */
    stepToPos(step) {
      let m = 1;
      while (m < MAX_MASS && STEP[m + 1] <= step) m++;
      return { mass: m, into: step - STEP[m], size: INTERVALS[m] || 1 };
    },

    weightClass(step) { return CLASSES.find(c => step <= c.top); },

    fuelSteps(f) { return f ? Math.max(0, f.wet - STEP[f.dry]) : 0; },

    /** "4⅓" style label for a chit position. */
    label(step) {
      const p = FS.stepToPos(step);
      if (!p.into) return String(p.mass);
      return p.mass + '+' + p.into + '/' + p.size;
    },

    create(dry) {
      HF.check(dry <= MAX_MASS, 'Dry Mass ' + dry + ' does not fit on the Fuel Strip (max 32).');
      const d = Math.max(1, dry);
      return { dry: d, wet: STEP[d], grade: null };
    },

    /** Move the Wet chit `n` steps right along the dashed red line (one per FT/Mass). */
    redShift(step, n) {
      const p = FS.stepToPos(step);
      const mass = Math.max(1, Math.min(MAX_MASS, p.mass + n));
      const size = INTERVALS[mass] || 0;
      const into = size ? Math.min(p.into, size - 1) : 0;
      return STEP[mass] + into;
    },

    /** Add `n` FTs of `grade` as Wet Mass Fuel (G1b), handling fuel mixing (F4d). */
    addFuel(f, n, grade) {
      HF.check(STEP[f.dry] <= f.wet, 'Corrupt fuel strip');
      const p = FS.stepToPos(f.wet);
      HF.check(p.mass + n <= MAX_MASS, 'The Wet Mass chit cannot pass 32 on the Fuel Strip.');
      const had = FS.fuelSteps(f) > 0;
      f.wet = FS.redShift(f.wet, n);
      f.grade = had ? FS.lowerGrade(f.grade, grade) : grade;
    },

    /** Change Dry Mass. mode 'red': cargo added/removed, Wet moves the same along red (F2b).
     *  mode 'fixed': internal tankage / Phileas Fogg, Wet stays (F3c). */
    setDry(f, newDry, mode) {
      newDry = Math.max(1, newDry);
      HF.check(newDry <= MAX_MASS, 'Dry Mass would exceed 32.');
      if (mode === 'red') {
        const delta = newDry - f.dry;
        f.wet = FS.redShift(f.wet, delta);
        if (f.wet < STEP[newDry]) f.wet = STEP[newDry];
      } else {
        HF.check(f.wet >= STEP[newDry], 'The Wet Mass chit can never be left of the Dry Mass chit.');
      }
      f.dry = newDry;
    },

    /** Expend fuel steps along the black line (H5a). */
    burn(f, steps) {
      HF.check(f.wet - steps >= STEP[f.dry] - 1e-9, 'Insufficient Fuel.');
      f.wet -= steps;
    },

    gradeRank(g) { return { dirt: 0, water: 1, isotope: 2 }[g] ?? -1; },
    lowerGrade(a, b) { if (!a) return b; if (!b) return a; return FS.gradeRank(a) <= FS.gradeRank(b) ? a : b; },
    /** A thruster of grade `need` can burn fuel of grade `have` (F4c). */
    gradeOk(need, have) { return !need || FS.gradeRank(have) >= FS.gradeRank(need); },

    /** Wet Mass (integer label) needed to afford `steps` of fuel above `dry`. */
    requiredWet(dry, steps) {
      let m = Math.max(1, dry), acc = 0;
      while (acc < steps && m < MAX_MASS) { acc += INTERVALS[m]; m++; }
      return acc >= steps ? m : null;
    }
  };

  HF.FS = FS;
})(window.HF = window.HF || {});
