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
     * H8b + H2b — a Bonus Burn stays spendable while coasting with a
     * non-positive net thrust. FLYBY 1142 (boost=1, solarMod=-4) sits
     * between LAGRANGE 1143 and BURN 1141. A solar thruster with base
     * thrust 1 nets 1 (base) + 2 (Wisp) − 4 (solar) = −1 there: zero paid
     * burns, coasting only. The bonus collected at 1142 must still pay the
     * burn into 1141 — the card is Operational (J3a shuts solar down only
     * in the Neptune zone), and bonus burns ignore the burn limit (H8b).
     *
     * <p>Regression: the old destThrust-≤-0 entry gate in expandBurn
     * rejected the transition before the free-burn option was considered,
     * so 1141 was unreachable for this loadout.
     */
    @Test
    void bonusBurnSpendableWhileCoastingAtNonPositiveNetThrust() {
        SolarMap sub = MapSubgraph.extract(fullMap, "1142", 2);

        // Mirrors the reported query: dry=1 fuel=3 engine=base:1,solar:1,pivots:1
        EngineSpec engine = new EngineSpec(1, 0, 1, true, 1, 0, 0, false, false);
        TraverseResponse r = Pathfinder.traverse(sub,
                new TraverseRequest("1143", List.of(engine), /*dryMass=*/ 1, /*fuelSteps=*/ 3));
        assertEquals("ok", r.status());

        assertTrue(reached(r, "1142"), "flyby 1142 must be reachable by coasting from 1143");
        Set<String> costs = costVectorsAt(r, "1141");
        assertFalse(costs.isEmpty(),
                "burn 1141 must be reachable: the 1142 flyby bonus pays the burn even at net thrust <= 0");
        assertTrue(costs.stream().anyMatch(c -> c.startsWith("0|1|")),
                "1141 should be reached on turn 1 with 0 fuel spent via the bonus burn; got " + costs);
    }

    /**
     * H8f — a Mag Sail earns exactly ONE Bonus Burn per radiation belt
     * entered (Sails-module clarification: "receives one Bonus Burn in the
     * same manner as a flyby (H8b) for each Radiation Belt entered"), not
     * the belt's radiation severity.
     *
     * <p>Scenario (reported route): coasting solar loadout (net thrust −2
     * in the −4 zone → zero paid burns) starting on FLYBY 655 next to
     * belts 1186 and 1181. One in-move farm pass yields at most
     * 1 + 1 + 2 (flyby 655) = 4 bonus burns. That still pays single burn
     * entries — lagrange 1151 stays reachable fuel-free — but the ten-burn
     * corridor 1084..1093 to Bee-Zed (≥ 12 burns per movement incl. the
     * 1151/1131 tolls, and unfarmable from mid-corridor) must be out of
     * reach. Under the old radiation-severity grant (+6 per belt) the
     * search reached 1093 on such a farm loop.
     */
    @Test
    void magSailGrantsOneBonusBurnPerBelt() {
        EngineSpec magSail = new EngineSpec(1, 0, 1, true, 1, 0, 0, true, false);
        TraverseResponse r = Pathfinder.traverse(fullMap,
                new TraverseRequest("655", List.of(magSail), /*dryMass=*/ 1, /*fuelSteps=*/ 3,
                        /*allowFuelJettison=*/ true, /*startingYear=*/ 11));
        assertEquals("ok", r.status());

        Set<String> tollCosts = costVectorsAt(r, "1151");
        assertTrue(tollCosts.stream().anyMatch(c -> c.startsWith("0|")),
                "one belt bonus must still pay the burn into lagrange 1151 fuel-free; got " + tollCosts);
        assertFalse(reached(r, "1093"),
                "corridor end 1093 needs ~12 bonus burns in one movement — impossible at 1 per belt");
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

    /**
     * H8c — the Venus flyby is fully season-gated: passage AND bonus
     * burns are both restricted to the Sunspot Cube being in BLUE.
     * Outside blue, Venus cannot be entered.
     *
     * <p>Note: with a 24-turn search horizon and a 12-year cycle, a
     * blue window (years 1..4) will eventually arrive even when starting
     * in red. So "reachable at all" is too weak an assertion — instead we
     * check the season at every Venus arrival in the search tree:
     * <b>every path that arrives at Venus must do so on a blue-calendar
     * turn</b>. This catches both "passage in red" and "passage in yellow"
     * regressions while still letting the search legitimately wait for
     * blue.
     */
    @Test
    void venusFlybyOnlyAccessibleInBlue() {
        EngineSpec engine = new EngineSpec(5, 2, false, 0);

        // 1=blue, 5=yellow, 9=red — one starting year per season band.
        for (int startingYear : new int[]{1, 5, 9}) {
            TraverseResponse r = Pathfinder.traverse(fullMap,
                    new TraverseRequest("40", List.of(engine), DRY_DEFAULT, FUEL_DEFAULT, false, startingYear));
            assertEquals("ok", r.status(), "startingYear=" + startingYear);
            // Walk the entire search tree and assert every Venus arrival is in blue.
            int violations = countVenusArrivalsOutsideBlue(r.tree(), startingYear);
            assertEquals(0, violations,
                    "startingYear=" + startingYear + ": every Venus arrival must be in a blue calendar year. "
                  + "violations=" + violations);
        }

        // Also verify the boost differential remains observable: when turn 1
        // IS blue, Venus is immediately accessible and downstream
        // reachability is a strict superset of the red startingYear case
        // (which only reaches Venus after a long wait).
        TraverseResponse blue = Pathfinder.traverse(fullMap,
                new TraverseRequest("40", List.of(engine), DRY_DEFAULT, FUEL_DEFAULT, false, 1));
        TraverseResponse red = Pathfinder.traverse(fullMap,
                new TraverseRequest("40", List.of(engine), DRY_DEFAULT, FUEL_DEFAULT, false, 9));
        Set<String> blueOnly = new java.util.LinkedHashSet<>(blue.endpoints().keySet());
        blueOnly.removeAll(red.endpoints().keySet());
        assertFalse(blueOnly.isEmpty(),
                "blue startingYear should reach ≥1 node red cannot — Venus's +2 boost from turn 1 unlocks downstream reach");
    }

    /**
     * Counts PathNodes anywhere in the search tree at Venus (id "33") whose
     * arrival turn falls outside a blue calendar year. With H8c fully
     * gating Venus passage by season, this should always be zero.
     */
    private static int countVenusArrivalsOutsideBlue(PathNode root, int startingYear) {
        int count = 0;
        Deque<PathNode> q = new ArrayDeque<>();
        if (root != null) q.add(root);
        while (!q.isEmpty()) {
            PathNode n = q.poll();
            if ("33".equals(n.nodeId())) {
                int year = ((startingYear + n.turns() - 1 - 1) % 12 + 12) % 12 + 1;
                // Blue per K1 = years 1..4 (BLUE → YELLOW → RED ordering).
                if (year < 1 || year > 4) count++;
            }
            q.addAll(n.children());
        }
        return count;
    }
}
