package com.hf4all.planner.model;

/**
 * The HF4A Fuel Strip: a non-linear scale that maps Wet Mass positions
 * (1..32) to cumulative fuel-step distances on the "black line".
 *
 * <p>Each Wet Mass position {@code N} sits at a varying number of fuel
 * steps above its neighbour {@code N+1}. The intervals follow a rocket-
 * equation-shaped curve: large near the dry end, small near the wet end.
 * The values below are taken directly from the printed playmat in the
 * HF4A Core Rules (verified against the worked examples on pp. 14–16).
 *
 * <p>Two key conversions live here:
 * <ul>
 *   <li>{@link #stepsBetween} — how many fuel steps lie between two Wet
 *       Mass positions on the black line (the "burnable" distance).</li>
 *   <li>{@link #wetMassAt} — given a Dry Mass and the current chit
 *       position measured in fuel steps from Dry Mass, return the
 *       integer Wet Mass the chit sits at (rounded down to the nearest
 *       labelled mass; partial fractional progress into the next
 *       interval is exposed via {@link #stepsIntoInterval}).</li>
 * </ul>
 *
 * <p>Weight class is derived from the integer Wet Mass per HF4A rule H3c
 * (see {@link #weightClassMod}). The class snapshot at the start of each
 * movement determines the engine's net thrust for that movement.
 */
public final class FuelStrip {

    /** Smallest legal Dry Mass; rule F2a treats Dry Mass `0' as `1'. */
    public static final int MIN_DRY_MASS = 1;
    /** Maximum Dry Mass position on the strip (rule F2a). */
    public static final int MAX_DRY_MASS = 23;
    /** Maximum Wet Mass position on the strip (rule F3a). */
    public static final int MAX_WET_MASS = 32;

    /**
     * {@code INTERVALS[N]} = number of fuel steps between Wet Mass
     * position {@code N} and {@code N+1}. Index 0 unused; index 32
     * unused (no position above MAX_WET_MASS).
     */
    private static final int[] INTERVALS = new int[MAX_WET_MASS + 1];
    static {
        // Low-mass region (rocket-equation steep): big intervals.
        INTERVALS[1]  = 9;  // 1 ↔ 2
        INTERVALS[2]  = 6;  // 2 ↔ 3
        INTERVALS[3]  = 4;  // 3 ↔ 4
        INTERVALS[4]  = 3;  // 4 ↔ 5
        INTERVALS[5]  = 3;  // 5 ↔ 6
        // Mid region: 2 steps per mass.
        INTERVALS[6]  = 2;
        INTERVALS[7]  = 2;
        INTERVALS[8]  = 2;
        INTERVALS[9]  = 2;
        INTERVALS[10] = 2;
        // High region: 1 step per mass, all the way to MAX_WET_MASS.
        for (int i = 11; i <= MAX_WET_MASS - 1; i++) {
            INTERVALS[i] = 1;
        }
    }

    private FuelStrip() {}

    // -------------------------------------------------------------------------
    // Strip geometry
    // -------------------------------------------------------------------------

    /**
     * Fuel-step distance on the black line between two Wet Mass positions.
     * Order-independent: {@code stepsBetween(a,b) == stepsBetween(b,a)}.
     *
     * @throws IllegalArgumentException if either position is outside [1, 32]
     */
    public static int stepsBetween(int a, int b) {
        checkInRange(a);
        checkInRange(b);
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        int sum = 0;
        for (int i = lo; i < hi; i++) sum += INTERVALS[i];
        return sum;
    }

    /**
     * Fuel-step distance from {@code dryMass} up to a given starting fuel-load
     * (number of water tanks). This is the initial {@code fuelStepsRemaining}
     * for a request specified as {@code (dryMass, fuel)} where {@code fuel} is
     * the count of tanks loaded (red-line steps).
     *
     * @throws IllegalArgumentException if dryMass + fuel exceeds {@link #MAX_WET_MASS}
     */
    public static int initialFuelSteps(int dryMass, int fuel) {
        if (dryMass < MIN_DRY_MASS || dryMass > MAX_DRY_MASS) {
            throw new IllegalArgumentException("dryMass out of range: " + dryMass);
        }
        if (fuel < 0) throw new IllegalArgumentException("fuel must be >= 0");
        int wet = dryMass + fuel;
        if (wet > MAX_WET_MASS) {
            throw new IllegalArgumentException(
                    "wetMass " + wet + " exceeds MAX_WET_MASS " + MAX_WET_MASS);
        }
        return stepsBetween(dryMass, wet);
    }

    /**
     * Validates a request specified directly in fuel-strip steps. Enforces
     * the strip's geometry: the Wet chit (at {@code dryStep + fuelSteps})
     * must land inside [0, 56].
     *
     * @throws IllegalArgumentException if either bound is violated
     */
    public static void validateFuelSteps(int dryMass, int fuelSteps) {
        if (dryMass < MIN_DRY_MASS || dryMass > MAX_DRY_MASS) {
            throw new IllegalArgumentException("dryMass out of range: " + dryMass);
        }
        if (fuelSteps < 0) throw new IllegalArgumentException("fuelSteps must be >= 0");
        int dryStep = stepsBetween(MIN_DRY_MASS, dryMass);
        int wetStep = dryStep + fuelSteps;
        int totalSteps = stepsBetween(MIN_DRY_MASS, MAX_WET_MASS);
        if (wetStep > totalSteps) {
            throw new IllegalArgumentException(
                    "wetStep " + wetStep + " exceeds total fuel-strip steps " + totalSteps
                  + " (dryMass=" + dryMass + ", fuelSteps=" + fuelSteps + ")");
        }
    }

    /**
     * Integer Wet Mass position the chit sits at (or just past), given the
     * Dry Mass and the chit's current fuel-step distance above Dry Mass.
     *
     * <p>If {@code fuelStepsRemaining} falls strictly inside an interval
     * (mid-burn fractional state), this returns the LOWER of the two
     * surrounding mass positions — i.e. the chit hasn't crossed the next
     * label yet. The fractional progress can be recovered with
     * {@link #stepsIntoInterval}.
     */
    public static int wetMassAt(int dryMass, int fuelStepsRemaining) {
        if (dryMass < MIN_DRY_MASS || dryMass > MAX_WET_MASS) {
            throw new IllegalArgumentException("dryMass out of range: " + dryMass);
        }
        if (fuelStepsRemaining < 0) {
            throw new IllegalArgumentException("fuelStepsRemaining must be >= 0");
        }
        int pos = dryMass;
        int walked = 0;
        while (pos < MAX_WET_MASS) {
            int interval = INTERVALS[pos];
            if (walked + interval > fuelStepsRemaining) break;
            walked += interval;
            pos++;
        }
        return pos;
    }

    /**
     * Fuel steps already walked into the interval above the current Wet Mass
     * label (0 if the chit sits exactly on a label).
     */
    public static int stepsIntoInterval(int dryMass, int fuelStepsRemaining) {
        int wet = wetMassAt(dryMass, fuelStepsRemaining);
        return fuelStepsRemaining - stepsBetween(dryMass, wet);
    }

    // -------------------------------------------------------------------------
    // Weight class
    // -------------------------------------------------------------------------

    // Class boundaries are defined in FUEL-STRIP STEP positions (HF4A H3c
     // playmat bands), not integer Wet Mass alone — the same integer mass
     // can span two classes when fractional positions cross a boundary.
     // Heaviest position still in each class:
     //
     //   step  8 (= WM 1+8/9)  → Wisp upper bound
     //   step 20 (= WM 4+1/3)  → Probe upper bound
     //   step 29 (= WM 8)      → Scout upper bound
     //   step 40 (= WM 16)     → Transport upper bound
     //   step 56 (= WM 32)     → Tug upper bound (= MAX_WET_MASS)
     //
    /** HF4A weight class modifier for a fuel-strip step position
     *  (0 = WM 1, 56 = WM 32). The chit's actual position on the strip
     *  determines its class — fractional positions are honoured. */
    public static int weightClassModForStep(int stepFromMass1) {
        if (stepFromMass1 <=  8) return +2; // Wisp      (WM ≤ 1+8/9)
        if (stepFromMass1 <= 20) return +1; // Probe     (WM ≤ 4+1/3)
        if (stepFromMass1 <= 29) return  0; // Scout     (WM ≤ 8)
        if (stepFromMass1 <= 40) return -1; // Transport (WM ≤ 16)
        return -2;                          // Tug       (WM ≤ 32)
    }

    /**
     * HF4A weight class modifier for a chit sitting at exact integer
     * Wet Mass. Equivalent to {@code weightClassModForStep(stepsBetween(1, wetMass))}.
     * Note: classes are defined on fuel-step positions, so this convenience
     * is only correct when the chit IS at the integer mass — not at a
     * fractional position past it.
     */
    public static int weightClassModForWetMass(int wetMass) {
        return weightClassModForStep(stepsBetween(MIN_DRY_MASS, wetMass));
    }

    /**
     * Weight class modifier given (dryMass, fuelStepsRemaining). The
     * chit's strip position is {@code dryStep + fuelStepsRemaining};
     * class is read off that position directly.
     */
    public static int weightClassMod(int dryMass, int fuelStepsRemaining) {
        int dryStep = stepsBetween(MIN_DRY_MASS, dryMass);
        return weightClassModForStep(dryStep + fuelStepsRemaining);
    }

    // -------------------------------------------------------------------------
    // Jettison ladder
    // -------------------------------------------------------------------------

    /**
     * Heaviest fuel-strip step position still in each class (Transport,
     * Scout, Probe, Wisp). Iteration order = lightening: jettisoning to
     * each successive entry requires more fuel dumped. Tug isn't an
     * entry — it's the implicit "current class" before any jettison.
     */
    private static final int[] CLASS_TOP_STEP = { 40, 29, 20, 8 };

    /**
     * The amounts (in fuel steps to jettison) that would each move the chit
     * to a *different* weight class than the current state. Returns at most
     * 4 entries. Empty if no jettison can change the class (e.g. already at
     * Wisp, or dryMass keeps the class fixed because target band requires
     * lower WM than dryMass itself).
     *
     * <p>Each returned amount is the MINIMUM jettison needed to just cross
     * into the target class — i.e. the chit lands on the heaviest position
     * still in the target class's band. Jettisoning more within the same
     * band is strictly Pareto-dominated.
     */
    public static int[] jettisonAmountsForClassChange(int dryMass, int fuelStepsRemaining) {
        int dryStep = stepsBetween(MIN_DRY_MASS, dryMass);
        int currentWetStep = dryStep + fuelStepsRemaining;
        int currentMod = weightClassModForStep(currentWetStep);

        int[] out = new int[CLASS_TOP_STEP.length];
        int n = 0;
        int lastMod = currentMod;
        for (int targetTopStep : CLASS_TOP_STEP) {
            // If dryStep already exceeds the target class's top, the chit
            // physically can't reach that band (Wet ≥ Dry).
            if (dryStep > targetTopStep) continue;

            // Already at or below the target band? Skip.
            if (currentWetStep <= targetTopStep) continue;

            int targetMod = weightClassModForStep(targetTopStep);
            if (targetMod == lastMod) continue;

            int jettison = currentWetStep - targetTopStep;
            out[n++] = jettison;
            lastMod = targetMod;
        }
        if (n == out.length) return out;
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static void checkInRange(int wetMass) {
        if (wetMass < MIN_DRY_MASS || wetMass > MAX_WET_MASS) {
            throw new IllegalArgumentException("wet/dry mass position out of range [1,32]: " + wetMass);
        }
    }
}
