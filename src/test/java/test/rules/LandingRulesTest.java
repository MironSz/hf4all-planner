package test.rules;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.server.dto.EngineSpec;
import com.hf4all.planner.server.dto.PathNode;
import com.hf4all.planner.server.dto.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import test.MapSubgraph;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;
import static test.rules.RuleTestSupport.*;

/**
 * Rules H6a (powered landing / liftoff thrust gates) and the landing-burn
 * thrustRequired gate used before powered landings.
 */
class LandingRulesTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * H6a — a powered landing on a SITE requires {@code thrust > siteSize}.
     * Eureka (id "4", size 1, no aerobrake) is directly adjacent to lagrange
     * "969". thrust=1 must NOT land (1 &gt; 1 is false); thrust=2 must land.
     */
    @Test
    void poweredLandingRequiresThrustStrictlyGreaterThanSize() {
        SolarMap sub = MapSubgraph.extract(fullMap, "969", 2);

        EngineSpec belowThreshold = new EngineSpec(3, 2, false, 0);
        TraverseResponse low = traverse(sub, "969", belowThreshold, FUEL_DEFAULT);
        assertEquals("ok", low.status());
        assertFalse(reached(low, "4"),
                "Eureka (size 1) must not be reachable with thrust=1: rule requires strict >");

        EngineSpec atThreshold = new EngineSpec(4, 2, false, 0);
        TraverseResponse ok = traverse(sub, "969", atThreshold, FUEL_DEFAULT);
        assertEquals("ok", ok.status());
        assertTrue(reached(ok, "4"),
                "Eureka (size 1) must be reachable with thrust=2");
    }

    /**
     * H6a (liftoff) — leaving a SITE requires {@code thrust > siteSize}.
     * Start at Eureka (size 1). thrust=1 must not reach the adjacent
     * lagrange "969"; thrust=2 must.
     */
    @Test
    void liftoffRequiresThrustGreaterThanSize() {
        SolarMap sub = MapSubgraph.extract(fullMap, "4", 2);

        EngineSpec belowThreshold = new EngineSpec(3, 2, false, 0);
        TraverseResponse low = traverse(sub, "4", belowThreshold, FUEL_DEFAULT);
        assertEquals("ok", low.status());
        assertFalse(reached(low, "969"),
                "Eureka liftoff must fail with thrust=1 (not strictly greater than size 1)");

        EngineSpec atThreshold = new EngineSpec(4, 2, false, 0);
        TraverseResponse ok = traverse(sub, "4", atThreshold, FUEL_DEFAULT);
        assertEquals("ok", ok.status());
        assertTrue(reached(ok, "969"),
                "Eureka liftoff must succeed with thrust=2");
    }

    /**
     * Landing-burn thrust gate — a BURN node with {@code landing > 0} requires
     * {@code thrust ≥ thrustRequired} to enter. Node "339" is a landing burn
     * (thrustRequired=11, landing=1) directly adjacent to lagrange "334".
     * thrust=10 must NOT reach 339; thrust=11 must.
     */
    @Test
    void landingBurnGateEnforced() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 2);

        EngineSpec belowThreshold = new EngineSpec(12, 2, false, 0);
        TraverseResponse low = traverse(sub, "334", belowThreshold, FUEL_DEFAULT);
        assertEquals("ok", low.status());
        assertFalse(reached(low, "339"),
                "landing burn 339 (thrustRequired=11) must not be enterable with thrust=10");

        EngineSpec atThreshold = new EngineSpec(13, 2, false, 0);
        TraverseResponse ok = traverse(sub, "334", atThreshold, 28);
        assertEquals("ok", ok.status());
        assertTrue(reached(ok, "339"),
                "landing burn 339 must be enterable with thrust=11");
    }

    /**
     * H5e — cannot stop on a landing burn. After reaching landing burn 339,
     * no waitTurn should be accepted: the search tree must not contain any
     * state at node 339 with {@code turns} &gt; the arrival turn (turn 1).
     */
    @Test
    void cannotEndTurnOnLandingBurn() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 3);

        EngineSpec engine = new EngineSpec(13, 2, false, 0);
        TraverseResponse r = traverse(sub, "334", engine, 28);
        assertEquals("ok", r.status());

        assertFalse(treeContainsNodeWithMinTurn(r.tree(), "339", 2),
                "landing burn 339 must never appear in the tree with turn ≥ 2 — "
                        + "rule H5e forbids ending a turn on a landing burn");
    }

    /**
     * Pathfinder's {@code canEndTurnHere} returns false for decoratives.
     * Decorative "897" is a one-way neighbour of lagrange "334". After
     * arriving there on turn 1, no waitTurn may produce a turn-2 state at 897.
     */
    @Test
    void cannotEndTurnOnDecorative() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 3);

        EngineSpec engine = new EngineSpec(5, 2, false, 0);
        TraverseResponse r = traverse(sub, "334", engine, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        assertFalse(treeContainsNodeWithMinTurn(r.tree(), "897", 2),
                "decorative 897 must never appear in the tree with turn ≥ 2 — "
                        + "waitTurn is forbidden on decoratives");
    }

    /**
     * H6a — the powered-liftoff thrust gate applies regardless of aerobrake
     * edges. Mars NP (size 10) is an aerobrake-entry site, but leaving it
     * still requires thrust &gt; 10. With thrust=3, starting at Mars NP,
     * no cross-node move should be allowed. The search tree's only children
     * of the root should be waitTurn states on the same site node.
     */
    @Test
    void cannotLiftoffBelowSizeEvenViaAerobrake() {
        SolarMap sub = MapSubgraph.extract(fullMap, "340", 2);

        EngineSpec lowThrust = new EngineSpec(5, 2, false, 0);
        TraverseResponse r = traverse(sub, "340", lowThrust, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        assertTrue(allTreeNodesAtMapId(r.tree(), "340"),
                "with thrust=3 (≤ Mars NP size 10), no cross-node move should be "
                        + "possible — all tree nodes must stay at 340 (waitTurn only)");
    }

    // --- tree helpers ---------------------------------------------------

    /** True if any PathNode matches {@code mapNodeId} with {@code turns ≥ minTurn}. */
    private static boolean treeContainsNodeWithMinTurn(PathNode root, String mapNodeId, int minTurn) {
        if (root == null) return false;
        Deque<PathNode> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            PathNode n = q.poll();
            if (mapNodeId.equals(n.nodeId()) && n.turns() >= minTurn) return true;
            q.addAll(n.children());
        }
        return false;
    }

    /** True if every PathNode in the tree is at the given map id. */
    private static boolean allTreeNodesAtMapId(PathNode root, String mapNodeId) {
        if (root == null) return true;
        Deque<PathNode> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            PathNode n = q.poll();
            if (!mapNodeId.equals(n.nodeId())) return false;
            q.addAll(n.children());
        }
        return true;
    }
}
