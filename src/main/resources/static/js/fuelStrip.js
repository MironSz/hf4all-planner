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

// Mirrors FuelStrip.weightClassModForStep on the backend (HF4A H3c).
// Class boundaries are defined on FUEL-STRIP STEP positions, not just
// integer Wet Mass — the same integer mass can fall in two classes when
// the chit is at a fractional position. Heaviest position still in each
// class:
//   step  8 (= 1+8/9)  → Wisp upper bound
//   step 20 (= 4+1/3)  → Probe upper bound
//   step 29 (= 8)      → Scout upper bound
//   step 40 (= 16)     → Transport upper bound
//   step 56 (= 32)     → Tug upper bound
export function weightClassModForStep(step) {
    if (step <=  8) return +2;
    if (step <= 20) return +1;
    if (step <= 29) return  0;
    if (step <= 40) return -1;
    return -2;
}

export function weightClassNameForStep(step) {
    if (step <=  8) return 'Wisp';
    if (step <= 20) return 'Probe';
    if (step <= 29) return 'Scout';
    if (step <= 40) return 'Transport';
    return 'Tug';
}

// Convenience: weight class for a chit AT exact integer Wet Mass.
// Equivalent to {@code weightClassModForStep(massToStripStep(M))}.
export function weightClassMod(wetMass) {
    return weightClassModForStep(massToStripStep(wetMass));
}

export function weightClassName(wetMass) {
    return weightClassNameForStep(massToStripStep(wetMass));
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

// ---- Fractional fuel consumption (HF4A H5b) --------------------------------
// Burns of a fractional thruster (e.g. 1/2, 1/10) are summed exactly over a
// movement and rounded up only when it ends. The server does that math; the
// UI parses the engine's Fuel/burn text into an exact fraction and shows the
// exact pre-rounding amounts next to the rounded ones.

// Mirror EngineSpec.MAX_FUEL_CONSUMPTION (the whole strip — a dearer burn
// could never be paid) and EngineSpec.MAX_FUEL_CONSUMPTION_DEN.
export const MAX_FUEL_CONSUMPTION = STRIP_MAX_STEP;
export const MAX_FUEL_CONSUMPTION_DEN = 100;

function gcd(a, b) {
    a = Math.abs(a);
    b = Math.abs(b);
    while (b) [a, b] = [b, a % b];
    return a;
}

// Lowest terms with a positive denominator.
export function reduceFraction(num, den) {
    const g = gcd(num, den) || 1;
    const sign = den < 0 ? -1 : 1;
    return { num: sign * num / g, den: sign * den / g };
}

// Canonical text of a fraction: "2", "1/2", "3/2". Used in share links and
// wherever an exact amount is displayed.
export function formatFraction(num, den) {
    const f = reduceFraction(num, den);
    return f.den === 1 ? String(f.num) : f.num + '/' + f.den;
}

// Parse an engine's Fuel/burn text into an exact reduced fraction. Accepts
//   "2"                   whole fuel steps
//   "1/2", "3/2"          fraction
//   "1+1/2", "1 1/2"      mixed number
//   "0.5", ".25", "0,5"   decimal (comma allowed), converted exactly: 0.1 → 1/10
// Returns { ok: true, num, den } or { ok: false, error: "human-readable" }.
export function parseFuelConsumption(text) {
    const t = (text == null ? '' : String(text)).trim().replace(/,/g, '.');
    if (t === '') return { ok: false, error: 'enter fuel steps per burn, e.g. "2", "1/2" or "0.5"' };

    const tooManyDecimals = { ok: false, error: 'too many decimal places; use a fraction such as 1/3' };
    let num, den, m, decimal = false;
    if ((m = t.match(/^(\d+)$/))) {
        num = parseInt(m[1], 10);
        den = 1;
    } else if ((m = t.match(/^(\d*)\.(\d+)$/))) {
        if (m[2].length > 6) return tooManyDecimals;
        den = 10 ** m[2].length;
        num = parseInt(m[1] || '0', 10) * den + parseInt(m[2], 10);
        decimal = true;
    } else if ((m = t.match(/^(\d+)\s*\/\s*(\d+)$/))) {
        num = parseInt(m[1], 10);
        den = parseInt(m[2], 10);
    } else if ((m = t.match(/^(\d+)(?:\s*\+\s*|\s+)(\d+)\s*\/\s*(\d+)$/))) {
        const whole = parseInt(m[1], 10);
        const part  = parseInt(m[2], 10);
        den = parseInt(m[3], 10);
        if (den >= 1 && part >= den) {
            return { ok: false, error: `numerator ${part} must be < denominator ${den} in a mixed number` };
        }
        num = whole * den + part;
    } else {
        return { ok: false, error: 'expected e.g. "2", "1/2", "1+1/2" or "0.5"' };
    }
    if (den < 1) return { ok: false, error: 'denominator must be at least 1' };

    const f = reduceFraction(num, den);
    if (f.den > MAX_FUEL_CONSUMPTION_DEN) {
        if (decimal) return tooManyDecimals;
        return { ok: false, error: `denominator ${f.den} is too large (max ${MAX_FUEL_CONSUMPTION_DEN})` };
    }
    if (f.num > MAX_FUEL_CONSUMPTION * f.den) {
        return { ok: false, error: `at most ${MAX_FUEL_CONSUMPTION} fuel steps per burn` };
    }
    return { ok: true, num: f.num, den: f.den };
}

// Exact fuel spent at a route-tree node before the end-of-move rounding, as
// {num, den}. The server sends it next to the rounded fuelSpent; nodes that
// lack it (a tree cached from an older server) fall back to the rounded value.
export function exactFuelSpent(pn) {
    if (pn && pn.fuelSpentDen > 0 && typeof pn.fuelSpentNum === 'number') {
        return { num: pn.fuelSpentNum, den: pn.fuelSpentDen };
    }
    return { num: (pn && pn.fuelSpent) || 0, den: 1 };
}

// Exact fuel spent between two route-tree nodes (to − from), reduced.
export function exactFuelSpentBetween(from, to) {
    const a = exactFuelSpent(from);
    const b = exactFuelSpent(to);
    return reduceFraction(b.num * a.den - a.num * b.den, a.den * b.den);
}

// " (3/2)" when an exact fuel amount is fractional, "" when it is whole —
// appended to the rounded figure wherever the UI reports spent fuel.
export function exactFuelSuffix(f) {
    return f.den === 1 ? '' : ' (' + formatFraction(f.num, f.den) + ')';
}
