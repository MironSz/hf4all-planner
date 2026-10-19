package com.hf4all.planner.rules;

import com.hf4all.planner.model.Fraction;
import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.NodeType;
import com.hf4all.planner.model.Season;
import com.hf4all.planner.model.SolarMap;

/**
 * HF4A movement rules (H) as pure functions, shared by the planner's
 * {@code Pathfinder} and the hot-seat game ({@code hf4all-game}).
 *
 * <p>Where the planner and the game apply a rule differently, the difference
 * is an explicit parameter and each caller keeps its own behaviour: the
 * planner plans for the worst case (a Belt Roll at the printed maximum, a
 * Hazard counted rather than rolled) and keeps its long-standing landing
 * gates, while the game follows the rulebook calls made for it (no thrust
 * gate on lander burns, the Europa / Io aerobrake fix). Nothing here
 * allocates; the search calls it in its hot path.
 */
public final class MoveRules {

    private MoveRules() {}

    /* ------------------------------------------------------------ H3 net thrust */

    /**
     * Solar-powered engines are non-operational where the solar modifier is at
     * or below this (H3c / J3a: the Neptune zone).
     */
    public static final int SOLAR_SHUTDOWN_SOLAR_MOD = -5;

    /**
     * J3a in the planner: a solar-powered engine is shut down at this solar
     * modifier. (The game reads it from its zones instead: no solar number in
     * the Neptune zone, while Uranus' -5 is an ordinary modifier there.)
     */
    public static boolean solarShutdown(boolean solarPowered, int solarMod) {
        return solarPowered && solarMod <= SOLAR_SHUTDOWN_SOLAR_MOD;
    }

    /**
     * H3 net thrust: base thrust + weight-class modifier + solar modifier
     * ({@code 0} for an engine that is not solar-powered). Afterburn, supports
     * and pushes are added by the caller; a shut-down engine is the caller's call.
     */
    public static int netThrust(int baseThrust, int weightClassMod, int solarMod) {
        return baseThrust + weightClassMod + solarMod;
    }

    /** H5c: the paid burns of a move - the net thrust, never negative. */
    public static int burnLimit(int netThrust) {
        return Math.max(netThrust, 0);
    }

    /* ------------------------------------------------------------------ H5 fuel */

    /**
     * H5 / H5e: fuel steps of {@code burns} burns at {@code perBurn} each; on a
     * lander burn each costs its landing fraction (a half lander burn = half).
     *
     * @param landing the entered Space's landing fraction, or {@code null} / zero
     *                for an ordinary burn
     */
    public static Fraction burnFuel(Fraction perBurn, int burns, Fraction landing) {
        Fraction cost = burns == 1 ? perBurn : perBurn.multiply(burns);
        return landing == null || landing.isZero() ? cost : cost.multiply(landing);
    }

    /** H5d: the fuel spent so far in the move (fractional) must not exceed the fuel steps on hand. */
    public static boolean fuelCovers(Fraction spentThisMove, int fuelSteps) {
        return !spentThisMove.isGreaterThan(Fraction.of(fuelSteps));
    }

    /** H5b: fuel steps a move expends - its fractional spend rounded up when it ends. */
    public static int spentSteps(Fraction spentThisMove) {
        return spentThisMove.ceilToInt();
    }

    /** H5b: fuel steps left once the move ends. */
    public static int settledFuel(int fuelSteps, Fraction spentThisMove) {
        return fuelSteps - spentSteps(spentThisMove);
    }

    /* ----------------------------------------------------- H4 lanes and pivots */

    /** The edge label of a one-way (aerobrake) path: it cannot be entered from that side. */
    public static final String ONE_WAY_LABEL = "0";

    /** A Pivot costs this many burns (H4c). */
    public static final int PIVOT_BURNS = 2;

    public static boolean isOneWay(String edgeLabel) {
        return ONE_WAY_LABEL.equals(edgeLabel);
    }

    /**
     * H4: at a Space with lane labels a Spacecraft leaves along the lane it
     * entered on; taking another lane is a separate change of direction.
     */
    public static boolean leavesOffLane(boolean spaceHasLabels, String departureLabel, String entryLabel) {
        return spaceHasLabels && departureLabel != null && entryLabel != null && !departureLabel.equals(entryLabel);
    }

    /** H4c: changing lanes at a Hohmann intersection is a Pivot (elsewhere a free turn). */
    public static boolean isPivot(MapNode at, String entryLabel, String newLabel) {
        return at.isHohmann() && entryLabel != null && newLabel != null && !entryLabel.equals(newLabel);
    }

    /**
     * Burns of a Pivot paid with the move's Bonus Burns: they go first, up to
     * both burns, and paid burns cover the rest (a Bonus Burn pays any Burn
     * but an afterburn or a lander burn).
     */
    public static int pivotBurnsFromBonus(int bonusBurns) {
        return Math.min(bonusBurns, PIVOT_BURNS);
    }

    /** H4e: no U-turn - the move may not go straight back where it came from. */
    public static boolean isUTurn(int previousNodeIdx, int nextNodeIdx) {
        return previousNodeIdx != -1 && nextNodeIdx == previousNodeIdx;
    }

    /** H4e by Space id (the game's routes). */
    public static boolean isUTurn(String previous, String next) {
        return previous != null && previous.equals(next);
    }

    /* -------------------------------------------------------- H8 bonus burns */

    /** H8f Mag Sail: Bonus Burns per radiation belt entered. */
    public static final int MAG_SAIL_BELT_BONUS = 1;

    /** H8c: the Venus flyby can be entered only in season blue. */
    public static boolean venusOpen(MapNode dest, Season season) {
        return dest.type() != NodeType.VENUS || season == Season.BLUE;
    }

    /** H8b: Bonus Burns of a flyby; picking them up needs an Operational thruster (H8a). */
    public static int flybyBonus(MapNode dest, boolean operational) {
        return dest.isFlyby() && operational ? dest.flybyBoost() : 0;
    }

    /**
     * May the move enter a flyby whose bonus it already took this move? The
     * planner forbids it; the game allows it ({@code allowReentry}).
     */
    public static boolean flybyEntryAllowed(boolean usedThisMove, boolean allowReentry) {
        return allowReentry || !usedThisMove;
    }

    /** H8e Solar Oberth: the activated thruster's base thrust, +1 after an afterburn. */
    public static int oberthBonus(int baseThrust, boolean afterburned) {
        return baseThrust + (afterburned ? 1 : 0);
    }

    /** H5e: Bonus Burns cannot pay a lander burn. */
    public static boolean bonusPaysBurn(MapNode dest) {
        return dest.landing().isZero();
    }

    /**
     * Bonus Burns kept after crossing a one-way edge: the planner treats the
     * passive aerobrake manoeuvre as using them all ({@code oneWayClearsBonus}).
     */
    public static int bonusAfterOneWay(int bonusBurns, boolean oneWay, boolean oneWayClearsBonus) {
        return oneWay && oneWayClearsBonus ? 0 : bonusBurns;
    }

    /* --------------------------------------------- H6 landing, liftoff, stops */

    /** H6a: powered landing or liftoff needs net thrust above the Site's size (none for size 0). */
    public static boolean poweredLanding(int netThrust, int siteSize) {
        return siteSize <= 0 || netThrust > siteSize;
    }

    /** Minimum aerobrake Site size (H6b). */
    public static final int AEROBRAKE_MIN_SIZE = 6;

    /**
     * H6b aerobrake landing instead of a powered one.
     *
     * @param oneWayIn          the route into the Site follows a one-way (aerobrake) path
     * @param viaDecorative     the route bends through decorative points
     * @param fromAerobrake     the move comes from an aerobrake Hazard Space
     * @param rulebook          the game's variant: any one-way path, or a bent route out of an
     *                          aerobrake into a Site of size 6+ (Europa and Io have bent routes
     *                          from their radiation belts and no atmosphere); the planner's
     *                          variant: a one-way or bent route into a Site of size 6+
     */
    public static boolean aerobrakeLanding(boolean oneWayIn, boolean viaDecorative, boolean fromAerobrake,
                                           int siteSize, boolean rulebook) {
        if (rulebook) return oneWayIn || (fromAerobrake && viaDecorative && siteSize >= AEROBRAKE_MIN_SIZE);
        return (viaDecorative || oneWayIn) && siteSize >= AEROBRAKE_MIN_SIZE;
    }

    /**
     * H5e lander burn: the planner gates net thrust at the printed requirement
     * ({@code gate}); the game does not (only that the move can be completed).
     */
    public static boolean landerBurnThrustOk(MapNode burn, int netThrust, boolean gate) {
        if (!gate || burn.landing().isZero()) return true;
        int required = burn.thrustRequired();
        return required <= 0 || netThrust >= required;
    }

    /** A move cannot end on a lander burn (H5e) or a decorative bend point. */
    public static boolean canStop(MapNode n) {
        return n.landing().isZero() && !n.isDecorative();
    }

    /** B7h: a synodic comet (and its gate Space) is open only in its season. */
    public static boolean seasonGateOpen(SolarMap map, MapNode n, Season season) {
        Season gate = map.synodicGate(n);
        return gate == null || gate == season;
    }

    /**
     * H6b: does a sail entering this Space burn up? The game burns sails in
     * aerobrakes only ({@code rulebook}); the planner on any Hazard Space.
     */
    public static boolean sailBurnsUp(boolean hazardSpace, boolean aerobrakeHazard, boolean rulebook) {
        return rulebook ? aerobrakeHazard : hazardSpace;
    }

    /* ---------------------------------------------------- H10 radiation belts */

    /** H10b: Belt Rolls get +2 in season red. */
    public static final int RED_SEASON_BELT_PENALTY = 2;

    /**
     * H10 Belt Roll result: the roll (the planner: the belt's worst case) minus
     * the net thrust, +2 in season red. Not floored: the planner floors it at 0,
     * and the game floors a negative net thrust first (it is not subtracted).
     */
    public static int beltSeverity(int roll, int netThrust, Season season) {
        return roll - netThrust + (season == Season.RED ? RED_SEASON_BELT_PENALTY : 0);
    }
}
