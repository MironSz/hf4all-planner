package com.hf4all.planner.api;

import java.util.List;

/**
 * Pathfinder request. Mass-tracking inputs (HF4A F2/F3, H5):
 *
 * <ul>
 *   <li>{@code dryMass} — sum of card + cargo masses (the Dry Mass chit
 *       position on the fuel strip; range 1..23).</li>
 *   <li>{@code fuelSteps} — fuel-strip step count between the Dry and
 *       Wet chits (i.e. {@code wetStep - dryStep} on the black line).
 *       Already in the planner's internal unit, no conversion needed.
 *       Caller validates the wet position lies on the strip
 *       ({@code wetStep ≤ 56}, equivalently
 *       {@code stepsBetween(1, dryMass) + fuelSteps ≤ 56}).</li>
 * </ul>
 *
 * <p>Historically this field was {@code fuel} (number of water tanks
 * loaded, red-line). It got renamed when the front-end started supporting
 * fractional Wet Mass positions (e.g. {@code 2+5/6}); the strip's step
 * count is the natural shared unit between client and server.
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
    int fuelSteps,
    boolean disableVenusFlyby,
    boolean allowFuelJettison
) {
    /** Convenience constructor: defaults flags to false. */
    public TraverseRequest(String startNodeId, List<EngineSpec> engines, int dryMass, int fuelSteps) {
        this(startNodeId, engines, dryMass, fuelSteps, false, false);
    }
}
