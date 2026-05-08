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
    boolean allowFuelJettison,
    /**
     * Year on the HF4A Sol Sunspot Cycle when turn 1 begins (1..12).
     * Drives the season-aware gates: Belt Roll +2 in red (H10b), Venus
     * flyby in blue only (H8c — passage AND boost are gated), and
     * synodic comet site/adjacent-space accessibility in matching season
     * (H6 / B7h).
     */
    int startingYear
) {
    /**
     * Convenience constructor: defaults flags to false, {@code startingYear=5}
     * (yellow season — no Belt-Roll penalty, no Venus-flyby boost, no
     * synodic-comet matches). Picked deliberately so tests written before
     * season-aware logic remained behaviourally unchanged when the field
     * was added. Real frontend callers always send an explicit value.
     */
    public TraverseRequest(String startNodeId, List<EngineSpec> engines, int dryMass, int fuelSteps) {
        this(startNodeId, engines, dryMass, fuelSteps, false, 5);
    }

    /** Back-compat constructor: 5-arg form (no startingYear) defaults to 5
     *  for the same season-neutrality reason as the simpler form above. */
    public TraverseRequest(String startNodeId, List<EngineSpec> engines, int dryMass, int fuelSteps,
                           boolean allowFuelJettison) {
        this(startNodeId, engines, dryMass, fuelSteps, allowFuelJettison, 5);
    }
}
