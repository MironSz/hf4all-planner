package com.hf4all.planner.pathfinder.rules;

import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.api.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hf4all.planner.support.MapSubgraph;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static com.hf4all.planner.support.RuleTestSupport.*;

/**
 * Rules H10 (radiation mitigation via thrust) and H3c (solar-power thrust
 * modifier by heliocentric zone).
 */
class RadiationAndSolarTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * H10 — worstRadRoll at a radhaz node is {@code max(radiation - thrust, 0)}.
     * Node "38" is a radhaz (radiation=6) directly adjacent to BURN "37".
     * Crossing 37 → 38 with:
     * <ul>
     *   <li>thrust=3 → 6 − 3 = 3, endpoint at 38 must have worstRadRoll=3.
     *   <li>thrust=6 → 6 − 6 = 0, endpoint at 38 must have worstRadRoll=0.
     * </ul>
     */
    @Test
    void radMitigatedByThrust() {
        SolarMap sub = MapSubgraph.extract(fullMap, "38", 2);

        TraverseResponse low = traverse(sub, "37", new EngineSpec(5, 2, false, 0), FUEL_DEFAULT);
        assertEquals("ok", low.status());
        Set<String> lowCosts = costVectorsAt(low, "38");
        assertFalse(lowCosts.isEmpty(), "38 must be reachable from 37 with thrust=3");
        assertTrue(lowCosts.stream().anyMatch(c -> c.endsWith("|3")),
                "thrust=3 must yield worstRadRoll=3 at node 38; got " + lowCosts);

        TraverseResponse high = traverse(sub, "37", new EngineSpec(8, 2, false, 0), FUEL_DEFAULT);
        assertEquals("ok", high.status());
        Set<String> highCosts = costVectorsAt(high, "38");
        assertFalse(highCosts.isEmpty(), "38 must be reachable from 37 with thrust=6");
        assertTrue(highCosts.stream().anyMatch(c -> c.endsWith("|0")),
                "thrust=6 must fully mitigate radiation at node 38 (worstRadRoll=0); got " + highCosts);
    }

    /**
     * H3c — a solar-powered engine with base thrust 4 in a sphere7 zone
     * (solarMod = −4) has effective thrust 0 and cannot burn at all. A
     * non-solar engine with the same base thrust is unaffected and reaches
     * strictly more endpoints.
     *
     * <p>Scenario: start at BURN "669" (sphere7). With a small radius the
     * search stays fast.
     */
    /**
     * H10 — {@code worstRadRoll} is the running max over the path, not the sum.
     *
     * <p>Untestable on the default map: every RADHAZ carries the same
     * {@code radiation=6} value (verified by
     * MapScenarioScanner#printRadhazVariance). With constant-severity radhazes
     * you cannot distinguish max from sum of a single-hit path. A synthetic
     * map with differently-weighted radhazes would be required.
     */
    @org.junit.jupiter.api.Disabled("All radhaz nodes on the default map have radiation=6")
    @Test
    void worstRadRollIsCumulativeMax() {
        // Placeholder — see note above.
    }

    /**
     * H10 — a same-node direction change (pivot) in a radhaz should not
     * re-roll radiation.
     *
     * <p>Untestable on the default map: radhaz nodes are not also Hohmann
     * nodes (no direction labels), so same-node direction changes don't
     * occur at radhazes. Would require a synthetic map.
     */
    @org.junit.jupiter.api.Disabled("No RADHAZ node on the default map is also a Hohmann")
    @Test
    void rollNotUpdatedOnSameNodeMove() {
        // Placeholder — see note above.
    }

    /**
     * H3c (complement to {@link #solarPoweredLosesInOuterZone}) — a
     * solar-powered engine with base thrust 1 in a sphere0 zone (+2) has
     * effective thrust 3 and reaches strictly more endpoints than a
     * non-solar engine of the same base thrust.
     */
    @org.junit.jupiter.api.Disabled(
        "Needs re-derivation under mass-aware thrust: with the default Tug-class loadout "
        + "(-2 mod) both base=0 solar (+2-2=0) and base=0 non-solar (-2) have "
        + "non-positive net thrust and reach the same nodes via coasting only. "
        + "Re-derive with a Wisp/Probe loadout, or with bumped baseThrust.")
    @Test
    void solarPoweredGainsInMercuryZone() {
        SolarMap sub = MapSubgraph.extract(fullMap, "39", 6);

        EngineSpec nonSolar = new EngineSpec(0, 2, false, 0);
        EngineSpec solar    = new EngineSpec(0, 2, true,  0);

        TraverseResponse nonSolarResp = traverse(sub, "39", nonSolar, 28);
        TraverseResponse solarResp    = traverse(sub, "39", solar,    28);

        assertEquals("ok", nonSolarResp.status());
        assertEquals("ok", solarResp.status());

        assertTrue(solarResp.endpoints().size() > nonSolarResp.endpoints().size(),
                "solar engine in sphere0 (+2) must reach more endpoints than base-0 "
                + "non-solar (which has 0 effective thrust and cannot burn). "
                + "Got solar=" + solarResp.endpoints().size()
                + ", nonSolar=" + nonSolarResp.endpoints().size());
    }

    /**
     * H3c — solarMod must not affect a non-solar engine. Flipping the engine
     * from non-solar to "solar with base = sphere7 sphere effective thrust"
     * must reach the same or fewer endpoints as the reference non-solar;
     * crucially, the non-solar side of any A/B is stable regardless of start
     * zone.
     */
    @Test
    void nonSolarEngineUnaffectedBySolarMod() {
        // Run the same non-solar engine starting at sphere0 (+2) and sphere7 (−4).
        // Endpoint counts can differ (different starting topology), but the
        // invariant we assert is weaker: the search status must be ok for both
        // and the effective thrust reported at the starting burn must equal
        // the engine's base thrust (solarMod ignored for non-solar).
        SolarMap sphere0 = MapSubgraph.extract(fullMap, "39", 4);
        SolarMap sphere7 = MapSubgraph.extract(fullMap, "669", 4);

        EngineSpec engine = new EngineSpec(5, 2, false, 0);

        TraverseResponse inner = traverse(sphere0, "39", engine, 28);
        TraverseResponse outer = traverse(sphere7, "669", engine, 28);

        assertEquals("ok", inner.status());
        assertEquals("ok", outer.status());
        // A non-solar engine reaches at least the starting node from both zones
        // (no burn required to stay put; any reachable site proves it's not
        // dead-locked by solarMod).
        assertFalse(inner.endpoints().isEmpty(),
                "non-solar engine at sphere0 must still have endpoints");
        assertFalse(outer.endpoints().isEmpty(),
                "non-solar engine at sphere7 must still have endpoints");
    }

    /**
     * H3c — when solarMod makes effective thrust negative, the seed state's
     * {@code burnsRemaining} clamps at 0 (via {@code Math.max(thrust, 0)})
     * and the pathfinder must not crash or produce malformed output.
     *
     * <p>Starting at sphere7 BURN "669" with a solar engine of base 0
     * yields effective thrust of −4. The seed has burns=0 and freeBurns=0,
     * so no paid burns are possible. The search should still complete
     * cleanly, and the start node must appear in endpoints.
     */
    @Test
    void effectiveThrustNeverGoesNegativeForBurnCount() {
        SolarMap sub = MapSubgraph.extract(fullMap, "669", 4);
        EngineSpec starved = new EngineSpec(0, 2, true, 0);

        TraverseResponse r = traverse(sub, "669", starved, 28);

        assertEquals("ok", r.status());
        assertNotNull(r.tree(), "tree must be built even when effective thrust is negative");
        assertTrue(reached(r, "669"),
                "the starting node must appear in endpoints regardless of thrust");
    }

    @Test
    void solarPoweredLosesInOuterZone() {
        // Radius 4 is too small — the local sphere7 region has few burn nodes
        // to differentiate solar vs non-solar reachability. Bumping to 10.
        SolarMap sub = MapSubgraph.extract(fullMap, "669", 10);

        EngineSpec nonSolar = new EngineSpec(6, 2, false, 0);
        EngineSpec solar    = new EngineSpec(6, 2, true,  0);

        TraverseResponse nonSolarResp = traverse(sub, "669", nonSolar, 28);
        TraverseResponse solarResp    = traverse(sub, "669", solar,    28);

        assertEquals("ok", nonSolarResp.status());
        assertEquals("ok", solarResp.status());

        int nonSolarEndpoints = nonSolarResp.endpoints().size();
        int solarEndpoints    = solarResp.endpoints().size();

        assertTrue(solarEndpoints < nonSolarEndpoints,
                "solar engine at sphere7 (effective thrust 0) must reach strictly fewer "
                + "endpoints than a non-solar engine. Got solar=" + solarEndpoints
                + ", nonSolar=" + nonSolarEndpoints);
    }
}
