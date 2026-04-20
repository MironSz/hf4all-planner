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

        EngineSpec engine = new EngineSpec(3, 2, false, 0);
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
        EngineSpec engine = new EngineSpec(3, 2, false, 0);
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

        EngineSpec engine = new EngineSpec(3, 2, false, 0);
        TraverseResponse r = traverse(sub, "1", engine, FUEL_DEFAULT);

        assertEquals("ok", r.status());
        Set<String> costs = costVectorsAt(r, "6");
        assertFalse(costs.isEmpty(), "Hohmann 6 must be reachable from 1");
        assertTrue(costs.contains("0|2|0|0"),
                "loiter-then-leave should reach Hohmann 6 with 0 fuel on turn 2; got " + costs);
    }
}
