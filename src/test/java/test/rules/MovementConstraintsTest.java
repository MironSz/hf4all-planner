package test.rules;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.server.dto.EngineSpec;
import com.hf4all.planner.server.dto.PathNode;
import com.hf4all.planner.server.dto.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import test.MapSubgraph;

import static org.junit.jupiter.api.Assertions.*;
import static test.rules.RuleTestSupport.*;

/**
 * Rules H4e (no-U-turn) and H4f (aerobrake arrow / one-way edges).
 */
class MovementConstraintsTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * H4e — within a single turn, the spacecraft must not move A→B→A.
     * Structural check: walk the entire search tree and assert no path
     * contains a grandparent and child at the same map node within the
     * same turn counter.
     */
    @Test
    void noUTurnInTreePaths() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 4);

        EngineSpec engine = new EngineSpec(3, 2, false, 0);
        TraverseResponse r = traverse(sub, "334", engine, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        assertFalse(hasSameTurnUTurn(r.tree(), null, null),
                "search tree contains a U-turn (A→B→A within same turn) — "
                + "rule H4e broken");
    }

    /**
     * Recursively check every (grandparent, parent, current) triple in the
     * tree for an immediate U-turn within a single turn.
     */
    private static boolean hasSameTurnUTurn(PathNode current, PathNode parent, PathNode grandparent) {
        if (grandparent != null
                && current.nodeId().equals(grandparent.nodeId())
                && current.turns() == grandparent.turns()) {
            return true;
        }
        for (PathNode child : current.children()) {
            if (hasSameTurnUTurn(child, current, parent)) return true;
        }
        return false;
    }

    /**
     * H4f — a one-way ("0"-labeled) edge cannot be traversed against the arrow.
     * The edge 1459→793 is one-way (aerobrake). Starting at "793", its direct
     * neighbour "1459" must not be reachable: the direction "793→1459" is
     * blocked by the aerobrake arrow, and 1459's other neighbour "1344"
     * (from which it normally enters) is excluded from a radius-2 subgraph.
     *
     * <p>Radius 4 is too wide here: a cycle 793→…→1344→1459 exists in the
     * wider graph and lets the pathfinder reach 1459 legitimately.
     */
    /**
     * H4e (complement) — after a {@code waitTurn} the previousNodeId is reset,
     * so returning to the node we came from becomes legal on the next turn.
     *
     * <p>This behaviour can't be observed through the endpoint frontier (the
     * returned state is strictly Pareto-dominated on the 4 output dimensions
     * by the start state at the same node). Nor through the tree (dominated
     * states are pruned before tree construction). Kept as a disabled
     * placeholder; a proper test would require exposing either per-state
     * inspection or a subgraph where the returned node unlocks a downstream
     * endpoint not otherwise reachable.
     */
    @org.junit.jupiter.api.Disabled(
            "Pareto dominance hides the returned-to-start state; needs internal inspection")
    @Test
    void uTurnAllowedAfterLoiter() {
        // Placeholder: see note above.
    }

    /**
     * Crossing a {@code "0"}-labeled edge zeros out any accumulated flyby
     * bonus burns (aerobrake-style manoeuvre). Scenario: FLYBY "252" grants
     * +1 free burn on entry; the edge 252→262 is one-way. After that
     * one-way hop, subsequent 262→257 (BURN) must be paid — i.e. 257 must
     * not be reachable at {@code 0|1|0|0}.
     */
    @Test
    void oneWayEdgeConsumesFreeBurns() {
        SolarMap sub = MapSubgraph.extract(fullMap, "252", 3);

        EngineSpec engine = new EngineSpec(1, 2, false, 0);
        TraverseResponse r = traverse(sub, "147", engine, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        java.util.Set<String> costs = costVectorsAt(r, "257");
        assertFalse(costs.isEmpty(), "257 must be reachable from 147 via 252→262→257");
        assertFalse(costs.contains("0|1|0|0"),
                "after the 252→262 one-way crossing, the flyby's free burn must be "
                        + "consumed, so 257 cannot be entered for 0 fuel; got " + costs);
    }

    @Test
    void cannotTraverseAerobrakeBackwards() {
        SolarMap sub = MapSubgraph.extract(fullMap, "793", 2);

        EngineSpec engine = new EngineSpec(3, 2, false, 0);
        TraverseResponse r = traverse(sub, "793", engine, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        assertFalse(reached(r, "1459"),
                "node 1459 must be unreachable from 793 at radius 2 — the only "
                + "edge (1459→793) is one-way, and no detour exists in this subgraph");
    }
}
