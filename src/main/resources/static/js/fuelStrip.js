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
