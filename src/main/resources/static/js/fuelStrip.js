// HF4A fuel strip intervals: indexed by Wet Mass position N, the value is
// the number of black-line fuel steps between WM N and WM N+1. Matches
// FuelStrip.INTERVALS on the backend. Indices 0 and 32 are unused.
export const FUEL_STRIP_INTERVALS = [
    0,                          // 0 (unused)
    9, 6, 4, 3, 3,              //  1↔2 ..  5↔6
    2, 2, 2, 2, 2,              //  6↔7 .. 10↔11
    1, 1, 1, 1, 1, 1, 1, 1, 1,  // 11↔12 .. 19↔20
    1, 1, 1, 1, 1, 1, 1, 1, 1,  // 20↔21 .. 28↔29
    1, 1, 1                     // 29↔30 .. 31↔32
];

// Cumulative step count from Wet Mass 1 up to Wet Mass 32 inclusive.
// Used by the endpoint-fuel-strip chit editor mapping.
export const STRIP_MAX_STEP = 56;

// Mirrors FuelStrip.weightClassModForWetMass on the backend (HF4A H3c).
export function weightClassMod(wetMass) {
    if (wetMass <= 1) return +2;
    if (wetMass === 2) return +1;
    if (wetMass === 3) return 0;
    if (wetMass <= 5) return -1;
    return -2;
}

// HF4A weight class name for a given Wet Mass.
export function weightClassName(wetMass) {
    if (wetMass <= 1) return 'Wisp';
    if (wetMass === 2) return 'Probe';
    if (wetMass === 3) return 'Scout';
    if (wetMass <= 5) return 'Transport';
    return 'Tug';
}

// Express `fuelSteps` remaining above `dryMass` as
//   "<full wet-mass jumps> + <steps into current jump>/<size of current jump>
//    (total steps <fuelSteps>)"
// Example: 1 step above dry mass 4 → "0 + 1/3 (total steps 1)".
export function formatFuelOnStrip(dryMass, fuelSteps) {
    let remaining = fuelSteps;
    let pos = Math.max(1, dryMass | 0);
    let complete = 0;
    while (pos < 32 && remaining >= FUEL_STRIP_INTERVALS[pos]) {
        remaining -= FUEL_STRIP_INTERVALS[pos];
        complete++;
        pos++;
    }
    const size = pos < 32 ? FUEL_STRIP_INTERVALS[pos] : 1;
    return complete + ' + ' + remaining + '/' + size
            + ' (total steps ' + fuelSteps + ')';
}

// Cumulative fuel-step count from Wet Mass 1 up to (and INCLUDING) M.
export function massToStripStep(mass) {
    let s = 0;
    for (let i = 1; i < mass; i++) s += FUEL_STRIP_INTERVALS[i];
    return s;
}

// Step indices on the strip that correspond to integer Wet Mass positions
// (1..32). Useful for snapping the Dry chit during drag — Dry can ONLY
// land on integer-mass positions per HF4A F2.
export const INTEGER_MASS_STEPS = (() => {
    const a = [];
    for (let m = 1; m <= 32; m++) a.push(massToStripStep(m));
    return a;
})();

// Pretty-print a fuel-step distance as "N" or "M+num/den" relative to a
// given dryMass. The denominator is the size of the interval at the wet
// integer mass — see FUEL_STRIP_INTERVALS. Examples:
//   formatFuelSteps(0, 4)   → "0"
//   formatFuelSteps(3, 4)   → "1"        (3 = stepsBetween(4,5))
//   formatFuelSteps(4, 4)   → "1+1/3"    (4 steps from dry=4 → wet between 5..6)
//   formatFuelSteps(56, 1)  → "31"       (full strip)
export function formatFuelSteps(fuelSteps, dryMass) {
    if (fuelSteps <= 0) return '0';
    let remaining = fuelSteps;
    let pos = Math.max(1, dryMass | 0);
    let intPart = 0;
    while (pos < 32 && remaining >= FUEL_STRIP_INTERVALS[pos]) {
        remaining -= FUEL_STRIP_INTERVALS[pos];
        intPart++;
        pos++;
    }
    if (remaining === 0) return String(intPart);
    const den = FUEL_STRIP_INTERVALS[pos];
    return intPart + '+' + remaining + '/' + den;
}

// Parse a fuel-input string ("5", "1+5/6", "+5/6", "0+3/4", with optional
// whitespace) into a fuel-step count given the dryMass context.
//
// Returns { ok: true, fuelSteps: int } on success or
//         { ok: false, error: "human-readable" } on any of:
//   - unparseable text
//   - intPart < 0 or num < 0 or den < 1
//   - num >= den (a "5/3" frac is invalid)
//   - the fraction's denominator doesn't equal INTERVALS at the wet
//     integer mass (e.g. "1+5/6" with dry=2 → wet integer=3, INTERVALS[3]=4
//     ≠ 6 → reject with a message naming the right denominator).
//   - resulting wet step exceeds 56 (off the strip).
export function parseFuelText(text, dryMass) {
    if (typeof text !== 'string') return { ok: false, error: 'invalid input' };
    const t = text.replace(/\s+/g, '');
    if (t === '') return { ok: true, fuelSteps: 0 };

    // Pattern: optional integer, optional "+num/den" fractional. At least
    // one of the two parts must be present.
    //   "5"        → intPart=5, no frac
    //   "1+5/6"    → intPart=1, num=5, den=6
    //   "5/6"      → intPart=0, num=5, den=6
    //   "+5/6"     → intPart=0, num=5, den=6
    const m = t.match(/^(\d*)(?:\+?(\d+)\/(\d+))?$/);
    if (!m || (!m[1] && !m[2])) {
        return { ok: false, error: 'expected a number like "5" or "1+5/6"' };
    }
    const intPart = m[1] ? parseInt(m[1], 10) : 0;
    const num     = m[2] != null ? parseInt(m[2], 10) : 0;
    const den     = m[3] != null ? parseInt(m[3], 10) : 0;

    if (intPart < 0) return { ok: false, error: 'integer part must be ≥ 0' };
    if (m[2] != null) {
        if (den < 1) return { ok: false, error: 'denominator must be ≥ 1' };
        if (num >= den) return { ok: false, error: `numerator ${num} must be < denominator ${den}` };
        const wetIntMass = (dryMass | 0) + intPart;
        if (wetIntMass < 1 || wetIntMass > 31) {
            return { ok: false, error: `wet integer mass ${wetIntMass} out of strip range` };
        }
        const expectedDen = FUEL_STRIP_INTERVALS[wetIntMass];
        if (den !== expectedDen) {
            return { ok: false, error: `the ${wetIntMass}↔${wetIntMass+1} interval has ${expectedDen} sub-steps, not ${den}` };
        }
    }

    // Fold to fuel-steps: stepsBetween(dryMass, dryMass+intPart) + num
    let fuelSteps = 0;
    for (let i = (dryMass | 0); i < (dryMass | 0) + intPart; i++) {
        if (i < 1 || i > 31) return { ok: false, error: 'fuel value walks off the strip' };
        fuelSteps += FUEL_STRIP_INTERVALS[i];
    }
    fuelSteps += num;
    if (fuelSteps > STRIP_MAX_STEP - massToStripStep(dryMass)) {
        return { ok: false, error: 'wet step exceeds the 56-step strip' };
    }
    return { ok: true, fuelSteps };
}
