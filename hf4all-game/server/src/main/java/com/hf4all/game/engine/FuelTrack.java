package com.hf4all.game.engine;

import com.hf4all.game.model.Fuel;
import com.hf4all.planner.model.FuelStrip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Fuel Strip rules of the game (F1-F4) on top of the planner's
 * {@link FuelStrip}, which owns the strip geometry (intervals between masses)
 * and the weight classes. A {@link Fuel} keeps the Wet Mass chit as a
 * black-line step: 0 = mass 1 .. 56 = mass 32.
 */
public final class FuelTrack {

    private FuelTrack() {}

    public static final int MAX_MASS = FuelStrip.MAX_WET_MASS;
    public static final int MAX_DRY_TO_MOVE = FuelStrip.MAX_DRY_MASS;
    /** STEP[m] = black-line step of integer mass m. */
    private static final int[] STEP = new int[MAX_MASS + 1];
    /** INTERVALS[m] = steps between mass m and m+1 (0 past the end). */
    private static final int[] INTERVALS = new int[MAX_MASS + 1];
    static {
        for (int m = 1; m <= MAX_MASS; m++) STEP[m] = FuelStrip.stepsBetween(FuelStrip.MIN_DRY_MASS, m);
        for (int m = 1; m < MAX_MASS; m++) INTERVALS[m] = FuelStrip.stepsBetween(m, m + 1);
    }
    public static final int MAX_STEP = STEP[MAX_MASS];

    /** Weight class (H3c) of a Wet Mass step: the planner's modifier, the playmat's band name. */
    public record WeightClass(String name, int mod, int top) {}

    /** The playmat's names of the weight-class bands, by modifier. */
    private static final Map<Integer, String> CLASS_NAMES = Map.of(2, "Wisp", 1, "Probe", 0, "Scout", -1, "Transport", -2, "Tug");

    /** Weight classes, lightest first; each band's top step comes from the planner's FuelStrip. */
    public static final List<WeightClass> CLASSES;
    static {
        List<WeightClass> out = new ArrayList<>();
        for (int step = 0; step <= MAX_STEP; step++) {
            int mod = FuelStrip.weightClassModForStep(step);
            if (step == MAX_STEP || FuelStrip.weightClassModForStep(step + 1) != mod) out.add(new WeightClass(CLASS_NAMES.get(mod), mod, step));
        }
        CLASSES = List.copyOf(out);
    }

    public static WeightClass weightClass(int step) {
        int mod = FuelStrip.weightClassModForStep(step);
        for (WeightClass c : CLASSES) if (c.mod() == mod) return c;
        throw new IllegalStateException("no weight class for step " + step);
    }

    public static int massToStep(int m) { return STEP[Math.max(1, Math.min(MAX_MASS, m))]; }

    public static int interval(int m) { return m >= 0 && m <= MAX_MASS ? INTERVALS[m] : 0; }

    /** Integer mass at/below a step and the steps walked into the next interval. */
    public record Pos(int mass, int into, int size) {}

    public static Pos stepToPos(int step) {
        int m = FuelStrip.wetMassAt(FuelStrip.MIN_DRY_MASS, Math.max(0, step));
        return new Pos(m, step - STEP[m], INTERVALS[m] == 0 ? 1 : INTERVALS[m]);
    }

    /** Fuel steps to jettison for the next lighter weight class (the planner's jettison ladder), or null. */
    public static Integer jettisonToLighterClass(Fuel f) {
        if (f == null) return null;
        int[] ladder = FuelStrip.jettisonAmountsForClassChange(f.dry, fuelSteps(f));
        return ladder.length > 0 ? ladder[0] : null;
    }

    /** The strip as the browser draws it: the planner's intervals and the weight classes. */
    public static Map<String, Object> clientRules() {
        Map<String, Object> o = new LinkedHashMap<>();
        List<Integer> intervals = new ArrayList<>();
        for (int m = 0; m <= MAX_MASS; m++) intervals.add(INTERVALS[m]);
        o.put("intervals", intervals);
        o.put("maxMass", MAX_MASS);
        o.put("maxDryToMove", MAX_DRY_TO_MOVE);
        o.put("classes", CLASSES);
        return o;
    }

    public static int fuelSteps(Fuel f) { return f == null ? 0 : Math.max(0, f.wet - STEP[f.dry]); }

    /** "4+1/3" style label of a chit position. */
    public static String label(int step) {
        Pos p = stepToPos(step);
        if (p.into() == 0) return String.valueOf(p.mass());
        return p.mass() + "+" + p.into() + "/" + p.size();
    }

    public static Fuel create(int dry) {
        RuleError.check(dry <= MAX_MASS, "Dry Mass " + dry + " does not fit on the Fuel Strip (max 32).");
        int d = Math.max(1, dry);
        return new Fuel(d, STEP[d], null);
    }

    /** Move a chit n masses along the dashed red line (one per FT / cargo mass). */
    public static int redShift(int step, int n) {
        Pos p = stepToPos(step);
        int mass = Math.max(1, Math.min(MAX_MASS, p.mass() + n));
        int size = INTERVALS[mass];
        int into = size != 0 ? Math.min(p.into(), size - 1) : 0;
        return STEP[mass] + into;
    }

    /** Add n FTs of a grade as Wet Mass Fuel (G1b), with fuel mixing (F4d). */
    public static void addFuel(Fuel f, int n, String grade) {
        RuleError.check(STEP[f.dry] <= f.wet, "Corrupt fuel strip");
        Pos p = stepToPos(f.wet);
        RuleError.check(p.mass() + n <= MAX_MASS, "The Wet Mass chit cannot pass 32 on the Fuel Strip.");
        boolean had = fuelSteps(f) > 0;
        f.wet = redShift(f.wet, n);
        f.grade = had ? lowerGrade(f.grade, grade) : grade;
    }

    /**
     * Change the Dry Mass. "red": cargo added/removed, the Wet chit moves the
     * same along the red line (F2b). "fixed": internal tankage, Wet stays (F3c).
     */
    public static void setDry(Fuel f, int newDry, String mode) {
        newDry = Math.max(1, newDry);
        RuleError.check(newDry <= MAX_MASS, "Dry Mass would exceed 32.");
        if (mode.equals("red")) {
            int delta = newDry - f.dry;
            // The Wet chit moves as far as the Dry chit (F2b). Past 32 the strip has no spaces,
            // and capping the chit would silently burn the Fuel: refuse instead (F3a).
            RuleError.check(stepToPos(f.wet).mass() + delta <= MAX_MASS,
                    "That would push the Wet Mass past 32 (F3a): jettison Fuel first (G1f).");
            f.wet = redShift(f.wet, delta);
            if (f.wet < STEP[newDry]) f.wet = STEP[newDry];
        } else {
            RuleError.check(f.wet >= STEP[newDry], "The Wet Mass chit can never be left of the Dry Mass chit.");
        }
        f.dry = newDry;
    }

    /** Expend fuel steps along the black line (H5a). */
    public static void burn(Fuel f, int steps) {
        RuleError.check(f.wet - steps >= STEP[f.dry], "Insufficient Fuel.");
        f.wet -= steps;
    }

    public static int gradeRank(String g) {
        if (g == null) return -1;
        return switch (g) {
            case "dirt" -> 0;
            case "water" -> 1;
            case "isotope" -> 2;
            default -> -1;
        };
    }

    public static String lowerGrade(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return gradeRank(a) <= gradeRank(b) ? a : b;
    }

    /** A thruster of grade `need` can burn fuel of grade `have` (F4c). */
    public static boolean gradeOk(String need, String have) {
        return need == null || gradeRank(have) >= gradeRank(need);
    }

    /** Wet Mass (integer label) needed to afford `steps` of fuel above `dry`, or null. */
    public static Integer requiredWet(int dry, int steps) {
        int m = Math.max(1, dry), acc = 0;
        while (acc < steps && m < MAX_MASS) { acc += INTERVALS[m]; m++; }
        return acc >= steps ? m : null;
    }
}
