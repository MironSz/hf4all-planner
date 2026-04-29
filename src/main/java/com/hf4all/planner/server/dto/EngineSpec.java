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
    int afterburnThrustGain
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
        this(baseThrust, fuelConsumption, 1, solarPowered, bonusPivots, 0, 0);
    }

    /** True if the engine can afterburn (cost > 0 and gain > 0). */
    public boolean canAfterburn() {
        return afterburnFuelCost > 0 && afterburnThrustGain > 0;
    }

    public Fraction fuelConsumption() {
        return new Fraction(fuelConsumptionNum, fuelConsumptionDen);
    }
}
