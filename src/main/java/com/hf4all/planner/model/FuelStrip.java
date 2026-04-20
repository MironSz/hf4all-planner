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

    /** HF4A weight class modifier given the integer Wet Mass. (H3c) */
    public static int weightClassModForWetMass(int wetMass) {
        return switch (wetMass) {
            case 1       ->  +2; // Wisp
            case 2       ->  +1; // Probe
            case 3       ->   0; // Scout
            case 4, 5    ->  -1; // Transport
            default      ->  -2; // Tug (6..32)
        };
    }

    /**
     * Weight class modifier given (dryMass, fuelStepsRemaining). Convenience
     * wrapper around {@link #wetMassAt} + {@link #weightClassModForWetMass}.
     */
    public static int weightClassMod(int dryMass, int fuelStepsRemaining) {
        return weightClassModForWetMass(wetMassAt(dryMass, fuelStepsRemaining));
    }

    // -------------------------------------------------------------------------
    // Jettison ladder
    // -------------------------------------------------------------------------

    /**
     * For each target weight class below the current one, this array gives
     * the smallest Wet Mass that's NOT in the target class — i.e. the WM
     * the chit would sit at immediately after crossing the class's upper
     * boundary going UP. Pairs with CLASS_TARGET_MOD below.
     *
     * <p>Classes: Transport spans WM {4,5}, so entering Transport from Tug
     * means crossing out of WM=6 (= Tug). Scout is just WM=3, so upper
     * exclusive is WM=4. Probe is WM=2 → upper exclusive WM=3. Wisp is
     * WM=1 → upper exclusive WM=2.
     */
    private static final int[] CLASS_UPPER_EXCLUSIVE = { 6, 4, 3, 2 };

    /**
     * The amounts (in fuel steps to jettison) that would each move the chit
     * to a *different* weight class than the current state. Returns at most
     * 4 entries. Empty if no jettison can change the class (e.g. already at
     * Wisp, or dryMass keeps the class fixed).
     *
     * <p>Each returned amount is the MINIMUM jettison needed to just cross
     * into the target class. Jettisoning more within the same class band
     * is strictly Pareto-dominated (less fuel for no benefit).
     *
     * <p>Crucially, the minimum jettison leaves the chit at the TOP of the
     * target class's fuel-step range, not at the exact WM integer boundary.
     * For Tug → Wisp with dry=1, fuelSteps=45: the minimum jettison is 37
     * (leaving 8 fuel-steps, still WM=1 = Wisp), NOT 45 (which would leave
     * zero fuel — unusable for subsequent burns).
     */
    public static int[] jettisonAmountsForClassChange(int dryMass, int fuelStepsRemaining) {
        int currentMod = weightClassMod(dryMass, fuelStepsRemaining);

        int[] out = new int[CLASS_UPPER_EXCLUSIVE.length];
        int n = 0;
        int lastMod = currentMod;
        for (int upperExclusive : CLASS_UPPER_EXCLUSIVE) {
            // If dryMass ≥ upperExclusive, the target class requires WM < dryMass
            // (impossible — chit can't cross Dry). Skip it.
            if (upperExclusive <= dryMass) continue;

            // Max fuel-steps still classified as target class.
            int upperFuel = stepsBetween(dryMass, upperExclusive) - 1;
            if (upperFuel >= fuelStepsRemaining) continue; // already in (or below) this class

            // Weight-class mod of the target class = mod at WM = upperExclusive - 1.
            int targetMod = weightClassModForWetMass(upperExclusive - 1);
            if (targetMod == lastMod) continue;

            int jettison = fuelStepsRemaining - upperFuel;
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
