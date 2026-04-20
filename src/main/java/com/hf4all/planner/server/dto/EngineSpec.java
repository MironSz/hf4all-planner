package com.hf4all.planner.server.dto;

import com.hf4all.planner.model.Fraction;

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
 */
public record EngineSpec(
    int baseThrust,
    int fuelConsumptionNum,
    int fuelConsumptionDen,
    boolean solarPowered,
    int bonusPivots,
    /**
     * Fuel-step cost of one afterburn (HF4A rule H3a). {@code 0} = engine
     * cannot afterburn. Reserved for the dedicated afterburn implementation;
     * the current pathfinder ignores this field.
     */
    int afterburnFuelCost
) {

    public EngineSpec {
        if (fuelConsumptionDen <= 0) {
            throw new IllegalArgumentException(
                    "fuelConsumptionDen must be positive: " + fuelConsumptionDen);
        }
        if (fuelConsumptionNum < 0) {
            throw new IllegalArgumentException(
                    "fuelConsumptionNum must be >= 0: " + fuelConsumptionNum);
        }
    }

    /** Convenience constructor for integer fuel consumption (denominator = 1). */
    public EngineSpec(int baseThrust, int fuelConsumption,
                     boolean solarPowered, int bonusPivots) {
        this(baseThrust, fuelConsumption, 1, solarPowered, bonusPivots, 0);
    }

    public Fraction fuelConsumption() {
        return new Fraction(fuelConsumptionNum, fuelConsumptionDen);
    }
}
