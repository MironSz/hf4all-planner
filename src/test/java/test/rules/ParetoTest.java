package test.rules;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.MapNode;
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
 * Observable Pareto invariants on the response from {@link
 * com.hf4all.planner.pathfinder.Pathfinder#traverse}.
 */
class ParetoTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * {@code finalPrune} filters out decorative nodes — the endpoints map
     * must never contain a decorative key.
     */
    @Test
    void finalPruneDropsDecoratives() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 6);

        EngineSpec engine = new EngineSpec(3, 2, false, 0);
        TraverseResponse r = traverse(sub, "334", engine, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        for (String id : r.endpoints().keySet()) {
            MapNode node = sub.nodeById(id);
            assertNotNull(node, "endpoint id " + id + " must exist in the subgraph");
            assertFalse(node.isDecorative(),
                    "endpoints must not include decorative node id=" + id);
        }
    }

    /**
     * A node may carry multiple Pareto-optimal cost vectors. The LEO→GEO
     * engine-switching scenario from the main test suite (start 90, engines
     * 3/2 + 10/10) yields three distinct vectors at GEO (96) — at least
     * two must survive Pareto pruning here.
     */
    @Test
    void nonDominatedCostVectorsCoexist() {
        // Radius 6 covers the LEO/GEO cluster adequately.
        SolarMap sub = MapSubgraph.extract(fullMap, "90", 6);

        EngineSpec low  = new EngineSpec(3, 2, false, 0);
        EngineSpec high = new EngineSpec(10, 10, false, 0);
        TraverseResponse r = traverse(sub, "90", java.util.List.of(low, high), 40);
        assertEquals("ok", r.status());

        Set<String> costs = costVectorsAt(r, "96");
        assertTrue(costs.size() >= 2,
                "GEO (96) should carry at least 2 Pareto-optimal cost vectors; got " + costs);
    }

    /**
     * Invariant: at every endpoint, no reported cost vector is strictly
     * dominated by another reported cost vector for the same endpoint.
     * (Dominance: all 4 dimensions ≤ and at least one &lt;.)
     */
    @Test
    void paretoFrontierHasNoDominatedVectors() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 6);

        EngineSpec engine = new EngineSpec(3, 2, false, 0);
        TraverseResponse r = traverse(sub, "334", engine, 40);
        assertEquals("ok", r.status());

        for (String id : r.endpoints().keySet()) {
            Set<String> costs = costVectorsAt(r, id);
            int[][] parsed = costs.stream()
                    .map(ParetoTest::parse)
                    .toArray(int[][]::new);
            for (int i = 0; i < parsed.length; i++) {
                for (int j = 0; j < parsed.length; j++) {
                    if (i == j) continue;
                    assertFalse(strictlyDominates(parsed[j], parsed[i]),
                            "cost vector " + java.util.Arrays.toString(parsed[i])
                                    + " at node " + id + " is dominated by "
                                    + java.util.Arrays.toString(parsed[j])
                                    + " — finalPrune missed it");
                }
            }
        }
    }

    /** Parse "fuel|turn|hazards|rad" into a 4-element int array. */
    private static int[] parse(String csv) {
        String[] parts = csv.split("\\|");
        return new int[] {
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3])
        };
    }

    /** Strict Pareto dominance on the 4 output dimensions. */
    private static boolean strictlyDominates(int[] a, int[] b) {
        boolean le = a[0] <= b[0] && a[1] <= b[1] && a[2] <= b[2] && a[3] <= b[3];
        boolean lt = a[0] <  b[0] || a[1] <  b[1] || a[2] <  b[2] || a[3] <  b[3];
        return le && lt;
    }
}
