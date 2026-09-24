package com.hf4all.planner.api;

import com.hf4all.planner.model.Fraction;
import com.hf4all.planner.model.FuelStrip;

/**
 * Engine description supplied by the request.
 *
 * <p>Unlike the prior version, {@code baseThrust} is the LEFT side of the
 * thrust triangle on the card — the planner derives net thrust dynamically
 * by adding the weight-class modifier (from current Wet Mass) and the
 * heliocentric solar modifier (from current node) at the start of each
 * movement (HF4A rule H3).
 *
 * <p>Fuel consumption is rational (HF4A rule H5b: fractional thrusters
 * such as 3–1/10). For integer-consumption engines pass {@code den == 1}.
 * A fuel-modifying support (J5c) is folded in by the caller: pass the
 * product, e.g. {@code 2 × 1/2 = 1}.
 */
public record EngineSpec(
    int baseThrust,
    int fuelConsumptionNum,
    int fuelConsumptionDen,
    boolean solarPowered,
    int bonusPivots,
    /**
     * Fuel-step cost of one afterburn (HF4A rule H3a). {@code 0} = engine
     * cannot afterburn (no MW icon).
     */
    int afterburnFuelCost,
    /**
     * Net-thrust gain per afterburn use. HF4A canonical = 1; TW-style
     * variants use higher values (e.g. {@code cost=1, gain=3}). The
     * planner adds this to the post-weight-class thrust at turn start;
     * the cost is deducted from fuelStepsRemaining but does NOT influence
     * the weight-class snapshot used for the same movement.
     */
    int afterburnThrustGain,
    /**
     * Mag Sail thruster (H8f): each radiation belt entered grants ONE
     * Bonus Burn (Sails-module clarification of "in the same manner as a
     * flyby"), once per belt per movement. Same once-per-node enforcement
     * as flyby spaces.
     */
    boolean magSail,
    /**
     * Sail card (H6b): immediately decommissioned on entering an Aerobrake
     * Hazard. Once decommissioned, the engine cannot be re-activated for
     * the rest of the search; remaining mid-move travel is coasting only.
     */
    boolean decommissionsOnAerobrake
) {

    /**
     * Largest accepted fuel-consumption denominator. Printed thrusters use
     * halves, quarters and tenths (H5b, glossary "Scale"); products with a
     * fuel-modifying support stay far below this. The bound also keeps the
     * exact {@link Fraction} arithmetic of a movement well inside int range.
     */
    public static final int MAX_FUEL_CONSUMPTION_DEN = 100;

    /**
     * Largest accepted fuel consumption, in fuel steps per burn: the whole
     * fuel strip. A dearer burn could never be paid (H5d), so the cap never
     * hides a route.
     */
    public static final int MAX_FUEL_CONSUMPTION =
            FuelStrip.stepsBetween(FuelStrip.MIN_DRY_MASS, FuelStrip.MAX_WET_MASS);

    public EngineSpec {
        if (fuelConsumptionDen <= 0 || fuelConsumptionDen > MAX_FUEL_CONSUMPTION_DEN) {
            throw new IllegalArgumentException(
                    "fuelConsumptionDen must be in 1.." + MAX_FUEL_CONSUMPTION_DEN
                    + ": " + fuelConsumptionDen);
        }
        if (fuelConsumptionNum < 0) {
            throw new IllegalArgumentException(
                    "fuelConsumptionNum must be >= 0: " + fuelConsumptionNum);
        }
        if (fuelConsumptionNum > MAX_FUEL_CONSUMPTION * fuelConsumptionDen) {
            throw new IllegalArgumentException(
                    "fuel consumption must be at most " + MAX_FUEL_CONSUMPTION
                    + " steps per burn: " + fuelConsumptionNum + "/" + fuelConsumptionDen);
        }
        if (afterburnFuelCost < 0) {
            throw new IllegalArgumentException(
                    "afterburnFuelCost must be >= 0: " + afterburnFuelCost);
        }
        if (afterburnThrustGain < 0) {
            throw new IllegalArgumentException(
                    "afterburnThrustGain must be >= 0: " + afterburnThrustGain);
        }
    }

    /** Convenience constructor for integer fuel consumption (denominator = 1). */
    public EngineSpec(int baseThrust, int fuelConsumption,
                     boolean solarPowered, int bonusPivots) {
        this(baseThrust, fuelConsumption, 1, solarPowered, bonusPivots,
                0, 0, false, false);
    }

    /** True if the engine can afterburn (cost > 0 and gain > 0). */
    public boolean canAfterburn() {
        return afterburnFuelCost > 0 && afterburnThrustGain > 0;
    }

    public Fraction fuelConsumption() {
        return new Fraction(fuelConsumptionNum, fuelConsumptionDen);
    }
}
