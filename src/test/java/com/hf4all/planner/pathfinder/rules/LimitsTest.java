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

import static org.junit.jupiter.api.Assertions.*;
import static com.hf4all.planner.support.RuleTestSupport.*;

/**
 * Pathfinder invariants around {@code MAX_TURNS} (24) and the
 * caller-supplied {@code fuel} budget.
 */
class LimitsTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * No endpoint's {@code turns} may exceed the pathfinder's internal
     * {@code MAX_TURNS = 24}. Verified across an 8-radius subgraph so the
     * search has ample room to enumerate paths.
     */
    @Test
    void maxTurnsBudgetRespected() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 8);

        EngineSpec engine = new EngineSpec(5, 2, false, 0);
        TraverseResponse r = traverse(sub, "334", engine, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        int worst = maxTurnsInTree(r.tree());
        assertTrue(worst <= 24,
                "no tree node may exceed MAX_TURNS=24 turns; saw " + worst);
    }

    /**
     * No endpoint's {@code fuelSpent} may exceed the configured fuel budget.
     * Run with {@code fuel=5} and assert every tree node stays within.
     */
    @Test
    void maxFuelBudgetRespected() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 4);

        EngineSpec engine = new EngineSpec(5, 2, false, 0);
        int fuel = 5;
        TraverseResponse r = traverse(sub, "334", engine, fuel);
        assertEquals("ok", r.status());

        int worst = maxFuelInTree(r.tree());
        assertTrue(worst <= fuel,
                "no tree node may exceed the fuel budget (" + fuel + "); saw " + worst);
    }

    // --- tree traversal helpers ---------------------------------------

    private static int maxTurnsInTree(PathNode root) {
        if (root == null) return 0;
        int worst = 0;
        Deque<PathNode> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            PathNode n = q.poll();
            if (n.turns() > worst) worst = n.turns();
            q.addAll(n.children());
        }
        return worst;
    }

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
}
