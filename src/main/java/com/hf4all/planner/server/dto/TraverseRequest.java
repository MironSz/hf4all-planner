package com.hf4all.planner.server.dto;

import java.util.List;

/**
 * Pathfinder request. Mass-tracking inputs (HF4A F2/F3, H5):
 *
 * <ul>
 *   <li>{@code dryMass} — sum of card + cargo masses (the Dry Mass chit
 *       position on the fuel strip; range 1..23).</li>
 *   <li>{@code fuel} — number of water tanks loaded (red-line steps);
 *       starting Wet Mass = {@code dryMass + fuel}, capped at 32.</li>
 * </ul>
 *
 * <p>The planner converts these into a {@code fuelStepsRemaining} count
 * via the (non-linear) {@link com.hf4all.planner.model.FuelStrip}.
 *
 * <p>{@code allowFuelJettison} enables the turn-start jettison branching
 * described in HF4A rules F3d / G1f: dumping fuel to drop into a lighter
 * weight class (and therefore higher net thrust). When false, the search
 * never jettisons — useful for a "what's the cheapest path with no fuel
 * dumping" comparison.
 */
public record TraverseRequest(
    String startNodeId,
    List<EngineSpec> engines,
    int dryMass,
    int fuel,
    boolean disableVenusFlyby,
    boolean allowFuelJettison
) {
    /** Convenience constructor: defaults flags to false. */
    public TraverseRequest(String startNodeId, List<EngineSpec> engines, int dryMass, int fuel) {
        this(startNodeId, engines, dryMass, fuel, false, false);
    }
}
