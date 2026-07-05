package com.hf4all.planner.pathfinder.rules;

import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.api.PathNode;
import com.hf4all.planner.api.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hf4all.planner.support.MapSubgraph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static com.hf4all.planner.support.RuleTestSupport.*;

/**
 * Rules around {@code waitTurn} resource resets and multi-engine switching.
 */
class EngineAndWaitTurnTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * After a {@code waitTurn}, per-turn resources (burns, freeBurns, pivots)
     * are reset. Indirect check: with thrust=1 (max 1 burn = 2 fuel per turn),
     * any endpoint with {@code fuelSpent} &gt; 2 proves that burns carried
     * across a turn boundary — which is only possible if waitTurn refreshed
     * {@code burnsRemaining}.
     */
    @Test
    void waitTurnResetsPerTurnResources() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 5);

        EngineSpec engine = new EngineSpec(3, 2, false, 0);
        TraverseResponse r = traverse(sub, "334", engine, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        int worstFuel = maxFuelInTree(r.tree());
        assertTrue(worstFuel > 2,
                "with thrust=1, single-turn max fuel is 2; higher implies waitTurn "
                        + "reset burnsRemaining. Saw max fuelSpent=" + worstFuel);
    }

    /**
     * With two engines, {@code waitTurn} spawns one successor state per
     * engine — letting the search explore routes using either engine. The
     * PathNode tree must contain at least one node for each engine index.
     */
    @Test
    void engineSwitchOnWaitTurn() {
        // Radius 5 (not 4): at radius 4 both engines reach every endpoint with
        // identical output-cost vectors, so only ONE engine's route survives as
        // the per-cost-vector representative and which one is an iteration-order
        // artifact. Radius 5 exposes endpoints where the two engines produce
        // Pareto-distinct routes, so both engine indices appear in the pruned
        // tree regardless of representative tie-breaking.
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 5);

        EngineSpec engineA = new EngineSpec(5, 2, false, 0);
        EngineSpec engineB = new EngineSpec(8, 3, false, 0);
        TraverseResponse r = traverse(sub, "334", List.of(engineA, engineB), 28);
        assertEquals("ok", r.status());

        Set<Integer> engineIndices = collectEngineIndices(r.tree());
        // Root is engineIndex=-1 (start marker). Exclude it — we want both real engines.
        engineIndices.remove(-1);

        assertTrue(engineIndices.contains(0) && engineIndices.contains(1),
                "both engines must appear in tree nodes; saw " + engineIndices);
    }

    /**
     * Bonus pivots reset each turn. After a bonus-pivot-using manoeuvre in
     * turn 1, a waitTurn refreshes pivotsRemaining to engine.bonusPivots(),
     * so another free pivot is available in turn 2.
     *
     * <p>Needs a very specific topology: two consecutive Hohmann pivots
     * separated by a stoppable node. Not clean to construct on the default
     * map without a long scenario hunt.
     */
    @org.junit.jupiter.api.Disabled(
            "Needs a two-pivot scenario separated by a stoppable node — no clean "
                    + "real-map candidate found without a dedicated search")
    @Test
    void bonusPivotsResetEachTurn() {
        // Placeholder — see note above.
    }

    // --- tree helpers ---------------------------------------------------

    private static int maxFuelInTree(PathNode root) {
        if (root == null) return 0;
        int worst = 0;
        Deque<PathNode> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            PathNode n = q.poll();
            if (n.fuelSpent() > worst) worst = n.fuelSpent();
            q.addAll(n.children());
        }
        return worst;
    }

    private static Set<Integer> collectEngineIndices(PathNode root) {
        Set<Integer> out = new HashSet<>();
        if (root == null) return out;
        Deque<PathNode> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            PathNode n = q.poll();
            out.add(n.engineIndex());
            q.addAll(n.children());
        }
        return out;
    }
}
