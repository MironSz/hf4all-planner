package test.rules;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.server.dto.EngineSpec;
import com.hf4all.planner.server.dto.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import test.MapSubgraph;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static test.rules.RuleTestSupport.*;

/**
 * Rules H5 (burns), H4c (Hohmann pivots), H4d (free turns on icon spaces).
 *
 * <p>Each test extracts a small subgraph around the relevant start node to
 * keep the search fast and isolate the rule under test.
 */
class BurnAndPivotRulesTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * H5 — a single burn into an adjacent burn node must cost exactly
     * {@code engine.fuelConsumption()} fuel (not 0, not doubled).
     * Start: lagrange "334" (near Mars).  Destination: burn "257".
     */
    @Test
    void burnCostsOneFuelUnit() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 4);

        EngineSpec engine = new EngineSpec(5, 2, false, 0);
        TraverseResponse r = traverse(sub, "334", engine, FUEL_DEFAULT);

        assertEquals("ok", r.status());
        Set<String> costs = costVectorsAt(r, "257");
        assertFalse(costs.isEmpty(), "burn node 257 must be reachable from 334");
        // Expect at least one path reporting fuel=2, turn=1, hazards=0, rad=0
        assertTrue(costs.contains("2|1|0|0"),
                "direct burn into 257 should cost 2 fuel on turn 1; got " + costs);
    }

    /**
     * H4c — pivoting at a Hohmann costs 2 burns (i.e. 2 × fuelConsumption).
     * Scenario: Hohmann "5" has neighbours labeled both "1" (toward Hohmann
     * "6" and dec "1412") and "2" (toward lagrange "1" and dec "990").
     * Starting at lagrange "1" and heading to Hohmann "6" arrives at "5"
     * with direction "2" and must pivot to direction "1" to continue.
     *
     * <p>Using a radius-2 subgraph around "5" keeps the subgraph to
     * {5, 1, 6, 990, 1412}, which eliminates alternative routes — the only
     * way from "1" to "6" is via the pivot at "5".
     */
    @Test
    void pivotConsumesTwoBurns() {
        SolarMap sub = MapSubgraph.extract(fullMap, "5", 2);

        // thrust 3 gives enough burns to cover the pivot (2 burns);
        // fuelConsumption 2 means the pivot costs 4 fuel.
        EngineSpec engine = new EngineSpec(5, 2, false, 0);
        TraverseResponse r = traverse(sub, "1", engine, FUEL_DEFAULT);

        assertEquals("ok", r.status());
        Set<String> costs = costVectorsAt(r, "6");
        assertFalse(costs.isEmpty(), "Hohmann 6 must be reachable from 1 via pivot at 5");
        assertTrue(costs.contains("4|1|0|0"),
                "pivot at Hohmann 5 to reach 6 should cost 4 fuel on turn 1; got " + costs);
    }

    /**
     * H4c (loiter) — stopping on a Hohmann and leaving next turn in a new
     * direction is free (no pivot cost). Start at lagrange "1", reach
     * Hohmann "5", loiter one turn, leave toward Hohmann "6" on turn 2.
     * Expected arrival at "6" on turn 2 with fuelSpent=0.
     *
     * <p>Alongside the direct-pivot route (fuel=4, turn=1), the Pareto
     * frontier at "6" should also contain the loiter branch (fuel=0, turn=2).
     */
    @Test
    void loiterFreeOnHohmann() {
        SolarMap sub = MapSubgraph.extract(fullMap, "5", 2);

        EngineSpec engine = new EngineSpec(5, 2, false, 0);
        TraverseResponse r = traverse(sub, "1", engine, FUEL_DEFAULT);

        assertEquals("ok", r.status());
        Set<String> costs = costVectorsAt(r, "6");
        assertFalse(costs.isEmpty(), "Hohmann 6 must be reachable from 1");
        assertTrue(costs.contains("0|2|0|0"),
                "loiter-then-leave should reach Hohmann 6 with 0 fuel on turn 2; got " + costs);
    }

    /**
     * H5d — entering a burn that costs more fuel than remains is blocked.
     * Uses a fuelConsumption that exceeds the strip's fuel-step budget at
     * (DRY_DEFAULT=4, fuel=1): stepsBetween(4,5)=3, so a burn costing 4 is
     * unaffordable. (The original "fuel=1 < cost=2" framing assumed the
     * old flat-budget model where 1 tank = 1 step.)
     */
    @Test
    void fuelExhaustionPreventsBurn() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 2);

        EngineSpec engine = new EngineSpec(5, 4, false, 0);
        TraverseResponse r = traverse(sub, "334", engine, 1);

        assertEquals("ok", r.status());
        assertFalse(reached(r, "257"),
                "burn 257 must not be reachable: fuel-steps available = 3 (Dry 4 → Wet 5), "
                + "burn cost = 4, so H5d should block the entry");
    }

    /**
     * Parametric check: endpoint fuel at burn 257 scales linearly with
     * {@code engine.fuelConsumption()} — 1-burn entry pays exactly the
     * per-burn cost, no coefficients or rounding.
     */
    @Test
    void fuelCostScalesWithConsumption() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 2);

        for (int cost : new int[] { 1, 2, 3, 5 }) {
            EngineSpec engine = new EngineSpec(5, cost, false, 0);
            TraverseResponse r = traverse(sub, "334", engine, FUEL_DEFAULT);
            assertEquals("ok", r.status());
            String expected = cost + "|1|0|0";
            assertTrue(costVectorsAt(r, "257").contains(expected),
                    "fuelConsumption=" + cost + " should reach 257 with cost "
                            + expected + "; got " + costVectorsAt(r, "257"));
        }
    }

    /**
     * H4c — pivoting at a Hohmann costs 2 burns; a thrust=1 engine only has
     * 1 burn per turn and must not be able to pivot. At Hohmann 5, starting
     * at lagrange 1 (dir=2) cannot reach Hohmann 6 (requires dir=1 exit).
     */
    @Test
    void pivotBlockedWhenThrustLow() {
        SolarMap sub = MapSubgraph.extract(fullMap, "5", 2);

        EngineSpec engine = new EngineSpec(3, 2, false, 0);
        TraverseResponse r = traverse(sub, "1", engine, FUEL_DEFAULT);

        assertEquals("ok", r.status());
        // With thrust=1, loiter-and-leave still reaches 6 on turn 2 at 0 fuel.
        // The direct turn-1 pivot branch (cost 4|1|0|0) must NOT exist.
        Set<String> costs = costVectorsAt(r, "6");
        assertFalse(costs.contains("4|1|0|0"),
                "thrust=1 must not be able to pivot at Hohmann 5 on turn 1; got " + costs);
    }

    /**
     * H4c (bonus pivots) — an engine configured with {@code bonusPivots=1}
     * gets one free pivot per turn, making the first direction change at a
     * Hohmann cost 0 fuel. At Hohmann 5, reaching Hohmann 6 from lagrange 1
     * would cost 4 fuel via paid-burn pivot; with a bonus pivot, cost is 0
     * on turn 1.
     */
    @Test
    void bonusPivotOverridesCost() {
        SolarMap sub = MapSubgraph.extract(fullMap, "5", 2);

        EngineSpec engine = new EngineSpec(5, 2, false, 1);
        TraverseResponse r = traverse(sub, "1", engine, FUEL_DEFAULT);

        assertEquals("ok", r.status());
        assertTrue(costVectorsAt(r, "6").contains("0|1|0|0"),
                "bonus pivot should let us reach Hohmann 6 with 0 fuel on turn 1; "
                        + "got " + costVectorsAt(r, "6"));
    }

    /**
     * H4c (straight-through) — crossing a Hohmann without changing direction
     * costs no fuel. At Hohmann 5, entering from 6 (dir=1) and leaving
     * toward dec 1412 (also dir=1) is a straight-through: the path 6→5→1412
     * happens entirely in turn 1 at 0 fuel. 1412 is decorative, so we assert
     * that a non-decorative node two hops past 5 in the same direction is
     * reached at fuel=0 on turn 1. That node is Hohmann 16 (1412→16).
     */
    @Test
    void straightThroughHohmannCostsNothing() {
        SolarMap sub = MapSubgraph.extract(fullMap, "5", 3);

        EngineSpec engine = new EngineSpec(5, 2, false, 0);
        TraverseResponse r = traverse(sub, "6", engine, FUEL_DEFAULT);

        assertEquals("ok", r.status());
        assertTrue(costVectorsAt(r, "16").contains("0|1|0|0"),
                "straight-through 6→5→1412→16 (dir=1 throughout) should cost 0 fuel; "
                        + "got " + costVectorsAt(r, "16"));
    }
}
