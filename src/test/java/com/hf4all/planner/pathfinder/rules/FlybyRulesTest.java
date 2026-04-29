package com.hf4all.planner.pathfinder.rules;

import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.NodeType;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.pathfinder.Pathfinder;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.api.PathNode;
import com.hf4all.planner.api.TraverseRequest;
import com.hf4all.planner.api.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hf4all.planner.support.MapSubgraph;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static com.hf4all.planner.support.RuleTestSupport.*;

/**
 * Rules H8 — flybys grant bonus burns; H8c — Venus flyby togglable.
 * Also covers the re-entry guard ({@code isFlybyReentry} in Pathfinder).
 */
class FlybyRulesTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * H8 — entering a flyby grants free burns (cost 0 fuel for one burn).
     *
     * <p>Scenario: FLYBY 445 (boost=1) sits between two BURN nodes 37 and 45.
     * Starting at BURN 37 with a thrust=1 engine, the path
     * 37 → 445 (flyby entry, +1 free burn) → 45 (burn) reaches 45 at cost
     * {@code 0|1|0|0}. This is only achievable if the free-burn option was
     * taken on the 445→45 transition — a paid burn would have cost 2 fuel.
     */
    @Test
    void flybyGrantsFreeBurns() {
        SolarMap sub = MapSubgraph.extract(fullMap, "445", 2);

        EngineSpec engine = new EngineSpec(3, 2, false, 0);
        TraverseResponse r = traverse(sub, "37", engine, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        Set<String> costs = costVectorsAt(r, "45");
        assertFalse(costs.isEmpty(), "burn 45 must be reachable from 37 via flyby 445");
        assertTrue(costs.contains("0|1|0|0"),
                "flyby 445 should grant a free burn so 45 is reached with 0 fuel on turn 1; got " + costs);
    }

    /**
     * H8d-ish — the same flyby must not be entered twice within a single turn.
     * Structural check over the full search tree: no path from root to leaf
     * contains the same flyby node id twice at the same turn counter.
     */
    @Test
    void flybyReentryRejected() {
        SolarMap sub = MapSubgraph.extract(fullMap, "445", 4);

        EngineSpec engine = new EngineSpec(5, 2, false, 0);
        TraverseResponse r = traverse(sub, "37", engine, FUEL_DEFAULT);
        assertEquals("ok", r.status());

        // Collect the ids of all flyby-type nodes in the subgraph
        Set<String> flybyIds = new HashSet<>();
        for (MapNode n : sub.allNodes()) {
            if (n.type() == NodeType.FLYBY || n.type() == NodeType.VENUS) {
                flybyIds.add(n.id());
            }
        }

        // Walk each root-to-leaf path; assert no same-turn re-entry of any flyby
        assertFalse(hasSameTurnFlybyReentry(r.tree(), flybyIds, new ArrayList<>()),
                "search tree contains a same-turn flyby re-entry — rule broken");
    }

    /**
     * Walk every path from root, tracking (flybyId, turn) pairs along the way.
     * Return true if any flyby id re-appears at the same turn counter.
     */
    private static boolean hasSameTurnFlybyReentry(PathNode current, Set<String> flybyIds,
                                                    List<String> pathKeys) {
        int before = pathKeys.size();
        if (flybyIds.contains(current.nodeId())) {
            String key = current.nodeId() + "@" + current.turns();
            if (pathKeys.contains(key)) return true;
            pathKeys.add(key);
        }
        for (PathNode child : current.children()) {
            if (hasSameTurnFlybyReentry(child, flybyIds, pathKeys)) return true;
        }
        // Restore path state for sibling branches
        while (pathKeys.size() > before) pathKeys.remove(pathKeys.size() - 1);
        return false;
    }

    /**
     * H8c — when {@code disableVenusFlyby=true}, the pathfinder must refuse
     * to enter any VENUS node. Scenario: decorative "40" sits between VENUS
     * "33" and decorative "943"; the edge 40↔33 is bidirectional. Starting
     * at "40", the adjacent Venus is reachable only when the flag is off.
     *
     * <p>(Lagrange "43" — another neighbour of 33 — is not used because the
     * edge 43→33 carries a one-way "0" label that blocks cross-node entry
     * into 33 from that side.)
     *
     * <ul>
     *   <li>{@code disableVenusFlyby=false} → 33 must appear in endpoints.
     *   <li>{@code disableVenusFlyby=true}  → 33 must NOT appear in endpoints.
     * </ul>
     */
    /**
     * H5e — bonus burns from a flyby cannot be used on a lander burn. The
     * HF4A default map has NO flyby adjacent to (or within 2 hops of) a
     * landing burn — verified by MapScenarioScanner#printFlybysNearLanderBurns
     * — so a clean real-map scenario does not exist.
     */
    @org.junit.jupiter.api.Disabled(
            "No flyby is within 2 hops of a landing burn on the default map")
    @Test
    void flybyBonusCannotBeUsedOnLandingBurn() {
        // Placeholder — see note above.
    }

    /**
     * A flyby visited in turn N must be re-enterable in turn N+1 after a
     * {@code waitTurn} resets {@code bonusSites}. As with
     * {@link MovementConstraintsTest#uTurnAllowedAfterLoiter}, the re-entered
     * flyby state is Pareto-dominated on the 4 output dimensions and pruned
     * before tree construction, so the behaviour can't be observed here
     * without internal state inspection.
     */
    @org.junit.jupiter.api.Disabled(
            "Pareto dominance hides the re-entered flyby state; needs internal inspection")
    @Test
    void flybyResetAfterWaitTurn() {
        // Placeholder — see note above.
    }

    /**
     * Two consecutive flybys in a single turn should stack their boost. The
     * default map has no two flybys within 2 hops of each other — verified
     * by MapScenarioScanner#printConsecutiveFlybys — so a clean real-map
     * scenario does not exist.
     */
    @org.junit.jupiter.api.Disabled(
            "No two flybys are within 2 hops on the default map")
    @Test
    void multipleFlybysStack() {
        // Placeholder — see note above.
    }

    @Test
    void disableVenusFlybyBlocksVenus() {
        SolarMap sub = MapSubgraph.extract(fullMap, "40", 2);
        EngineSpec engine = new EngineSpec(5, 2, false, 0);

        TraverseResponse enabled = Pathfinder.traverse(sub,
                new TraverseRequest("40", List.of(engine), DRY_DEFAULT, FUEL_DEFAULT, false, false));
        assertEquals("ok", enabled.status());
        assertTrue(reached(enabled, "33"),
                "Venus (33) must be reachable from 40 when disableVenusFlyby=false");

        TraverseResponse disabled = Pathfinder.traverse(sub,
                new TraverseRequest("40", List.of(engine), DRY_DEFAULT, FUEL_DEFAULT, true, false));
        assertEquals("ok", disabled.status());
        assertFalse(reached(disabled, "33"),
                "Venus (33) must be unreachable from 40 when disableVenusFlyby=true");
    }
}
