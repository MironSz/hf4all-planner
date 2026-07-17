package com.hf4all.planner.pathfinder.rules;

import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.support.MapSubgraph;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.api.PathNode;
import com.hf4all.planner.api.TraverseRequest;
import com.hf4all.planner.api.TraverseResponse;
import com.hf4all.planner.pathfinder.Pathfinder;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour newly unlocked (or newly regression-tested) by the phase-3
 * "boost unification" refactor: fuel jettison and afterburn are both turn-
 * start {@code ThrustBoost} options considered by the same trigger, so a
 * jettison can now respond to a radiation trigger (previously AB-only under
 * the phase-2 masks) and the afterburn Solar-Oberth pickup gets its first
 * dedicated regression test (the free-burns math fixed by commit 592954d).
 */
class Phase3BoostBehaviorTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * Jettison-for-radiation: a completeness gap closed by phase 3.
     *
     * <p>Node 62 is a RADHAZ (fixed radiation level 6, see
     * {@code MapLoader.RADHAZ_RADIATION_LEVEL}) directly adjacent to LAGRANGE
     * node 56 (free direction change, no gate). Ship: dryMass=4, baseThrust=3,
     * fuelSteps=25 (Wet Mass 20, Tug class, weight mod -2) → no-jettison net
     * thrust = 1, so entering 62 costs {@code max(6-1,0) = 5} worst-rad-roll.
     * Jettisoning 4 fuel steps crosses into Transport class (mod -1, thrust 2,
     * severity 4); a bigger rung (9 steps, verified against
     * FuelStrip.jettisonAmountsForClassChange) reaches Scout class (mod 0,
     * thrust 3, severity 3) — both strictly better than the no-jettison
     * route's worst-rad-roll.
     *
     * <p>Under phase 2's per-trigger response mask, RADIATION only let the
     * pure-AB option respond — with an engine that CAN'T afterburn (this
     * scenario's engine has afterburn cost/gain 0/0), no boost alt was ever
     * spawned there, so only the no-jettison route existed. Phase 3 removes
     * the mask: RADIATION now considers the full menu including jettison
     * rungs, closing the gap. This test FAILS on phase-2 code (verified via a
     * throwaway worktree probe against commit 80009d3: only the no-jettison
     * route, worstRadRoll=5, was found) and PASSES on phase 3.
     */
    @Test
    void jettisonForRadiationUnlocksLowerRadRoll() {
        SolarMap sub = MapSubgraph.extract(fullMap, "56", 2);

        EngineSpec engine = new EngineSpec(3, 2, false, 0); // no afterburn (cost=gain=0)
        TraverseResponse r = Pathfinder.traverse(sub,
                new TraverseRequest("56", List.of(engine), 4, 25, true));
        assertEquals("ok", r.status());

        List<Integer> ids = r.endpoints().get("62");
        assertNotNull(ids, "radhaz node 62 must be reachable from 56");

        Map<Integer, PathNode> byId = new HashMap<>();
        Map<Integer, Integer> parentOf = new HashMap<>();
        indexTree(r.tree(), byId, parentOf, -1);

        int noJettisonWorstRad = Integer.MIN_VALUE;
        int bestJettisonWorstRad = Integer.MAX_VALUE;
        boolean sawJettisonRoute = false;
        for (int id : ids) {
            PathNode pn = byId.get(id);
            boolean jettisonedOnPath = anyAncestorJettisoned(id, byId, parentOf);
            if (jettisonedOnPath) {
                sawJettisonRoute = true;
                bestJettisonWorstRad = Math.min(bestJettisonWorstRad, pn.worstRadRoll());
            } else {
                noJettisonWorstRad = Math.max(noJettisonWorstRad, pn.worstRadRoll());
            }
        }

        assertTrue(sawJettisonRoute,
                "expected at least one route to 62 that jettisons at its turn-start; "
                        + "endpoint ids=" + ids);
        assertTrue(bestJettisonWorstRad < noJettisonWorstRad,
                "a jettisoning route must achieve a strictly lower worstRadRoll than every "
                        + "no-jettison route: bestJettison=" + bestJettisonWorstRad
                        + " noJettison=" + noJettisonWorstRad);
    }

    /**
     * AB-for-Oberth: regression test for the free-burns math fixed by commit
     * 592954d (afterburn dominance on maps with a zero thrust-key cap).
     *
     * <p>Node 1486 is the map's only {@code solarOberth} node (a landing-burn
     * BURN with no thrust requirement, so the H8e trigger fires on entry with
     * no thrust gate involved). {@link MapSubgraph#extract} around it has no
     * other gates or radiation belts, so {@code computeGlobalThrustCap} would
     * floor at 0 without the H8e afterburn-bit guard (592954d) — this test
     * exercises exactly that path with a real AB-capable engine.
     *
     * <p>Starting AT 1486 (turn 1): entering with an afterburn turn-start
     * harvests {@code baseThrust + 1} free burns (H8e), one more than the
     * no-AB pickup's plain {@code baseThrust}. That extra burn is enough to
     * reach a boundary node of the 3-hop subgraph in the SAME turn that the
     * no-AB route needs a second turn for — a strictly better (fewer turns)
     * route, so the AB seed survives Pareto pruning into the response tree.
     */
    @Test
    void afterburnAtOberthHarvestsExtraFreeBurn() {
        SolarMap sub = MapSubgraph.extract(fullMap, "1486", 3);

        // AB-capable engine: baseThrust=3, afterburnFuelCost=1, afterburnThrustGain=1.
        EngineSpec engine = new EngineSpec(3, 2, 1, false, 0, 1, 1, false, false);
        TraverseResponse r = Pathfinder.traverse(sub,
                new TraverseRequest("1486", List.of(engine), 4, 25, true));
        assertEquals("ok", r.status());

        // Find the afterburned turn-start seed at 1486 (root's direct child
        // per buildResponse's "seed carries a badge -> gets its own PathNode"
        // rule) and confirm the badge value is exactly afterburnThrustGain().
        PathNode abSeed = null;
        for (PathNode child : r.tree().children()) {
            if ("1486".equals(child.nodeId()) && child.afterburnedHere() > 0) {
                abSeed = child;
                break;
            }
        }
        assertNotNull(abSeed, "expected an afterburned turn-start seed at 1486 in the tree; "
                + "root children=" + describeChildren(r.tree()));
        assertEquals(engine.afterburnThrustGain(), abSeed.afterburnedHere(),
                "afterburnedHere badge must equal the engine's afterburnThrustGain()");

        // Route-shape assertion: the AB seed's subtree must reach a turn-1
        // child (the same-turn reach the extra free burn buys), proving the
        // +1 bonus burn (H8e, baseThrust+1 total) was actually spent, not
        // just recorded as a badge.
        int abSeedTurn = abSeed.turns();
        boolean reachedSameTurnChild = abSeed.children().stream()
                .anyMatch(c -> c.turns() == abSeedTurn);
        assertTrue(reachedSameTurnChild,
                "the afterburned seed at the Oberth node must reach at least one further node "
                        + "in the same turn (harvesting its extra free burn); children="
                        + describeChildren(abSeed));
    }

    /**
     * Rung+AB combo materialisation: a thrust gate that ONLY the combined
     * jettison-plus-afterburn option can clear.
     *
     * <p>Node 356 is a landing-burn node gated at {@code thrust >= 7},
     * adjacent to plain burn node 354. Ship: dryMass=4, fuelSteps=25 (strip
     * position 44, Tug class, mod -2), engine baseThrust=7 with afterburn
     * cost=1/gain=1. The turn-2 boost menu at 354 (turn 1 excludes jettison,
     * F3d): base=(0,5), AB=(1,6), rung4+AB=(5,7), rung15+AB=(16,8) — the
     * pure rung 4 (thrust 6) is menu-pruned by the cheaper AB, so the ONLY
     * cheapest option with thrust >= 7 is the rung4+AB combo. Its correct
     * materialisation is one turn-start with BOTH badges: jettisonedHere=4,
     * afterburnedHere=1, thrust 7 (weight class from F-4 per H3a, gain
     * layered on top), fuel 25-5=20.
     *
     * <p>Before the combo fix, maybeSpawnBoostAlt routed the combo through
     * addTurnStartStates at the AB-over-deducted fuel level: the afterburn
     * bit and +gain were dropped (thrust 6, gate still blocked) and the gate
     * was only cleared later by a chained pure-AB alt that paid the afterburn
     * cost a second time — no turn-start anywhere in the tree carried both
     * badges. This test FAILS on that code and passes after the fix.
     */
    @Test
    void comboJettisonPlusAfterburnClearsThrustGate() {
        SolarMap sub = MapSubgraph.extract(fullMap, "354", 2);

        EngineSpec engine = new EngineSpec(7, 2, 1, false, 0, 1, 1, false, false);
        TraverseResponse r = Pathfinder.traverse(sub,
                new TraverseRequest("354", List.of(engine), 4, 25, true));
        assertEquals("ok", r.status());

        PathNode combo = findComboTurnStart(r.tree());
        assertNotNull(combo,
                "expected a turn-start carrying BOTH a jettison and an afterburn badge "
                        + "(the rung4+AB combo at 354) somewhere in the tree");
        assertEquals(4, combo.jettisonedHere(),
                "combo must jettison exactly the 4-step Tug->Transport rung");
        assertEquals(engine.afterburnThrustGain(), combo.afterburnedHere(),
                "combo's afterburn badge must equal the engine's afterburnThrustGain()");
        assertTrue(subtreeContainsNode(combo, "356"),
                "the combo turn-start must actually clear the thrust-7 landing gate "
                        + "into node 356; combo children=" + describeChildren(combo));
    }

    // --- helpers -------------------------------------------------------

    /** First tree node carrying both a jettison and an afterburn badge. */
    private static PathNode findComboTurnStart(PathNode root) {
        if (root == null) return null;
        if (root.jettisonedHere() > 0 && root.afterburnedHere() > 0) return root;
        for (PathNode child : root.children()) {
            PathNode hit = findComboTurnStart(child);
            if (hit != null) return hit;
        }
        return null;
    }

    /** True if {@code root}'s subtree (inclusive) visits {@code nodeId}. */
    private static boolean subtreeContainsNode(PathNode root, String nodeId) {
        if (root == null) return false;
        if (nodeId.equals(root.nodeId())) return true;
        for (PathNode child : root.children()) {
            if (subtreeContainsNode(child, nodeId)) return true;
        }
        return false;
    }

    /** Indexes every PathNode by id and records each node's parent id
     *  (-1 for the root), since {@link PathNode} itself has no parent
     *  pointer — needed to walk a route's ancestry from an endpoint. */
    private static void indexTree(PathNode root, Map<Integer, PathNode> byId,
                                   Map<Integer, Integer> parentOf, int parentId) {
        if (root == null) return;
        byId.put(root.id(), root);
        parentOf.put(root.id(), parentId);
        for (PathNode child : root.children()) {
            indexTree(child, byId, parentOf, root.id());
        }
    }

    /** True if the node at {@code id}, or any ancestor on its path back to
     *  the root, jettisoned fuel at its turn-start. */
    private static boolean anyAncestorJettisoned(int id, Map<Integer, PathNode> byId,
                                                  Map<Integer, Integer> parentOf) {
        int cur = id;
        while (cur != -1) {
            PathNode n = byId.get(cur);
            if (n != null && n.jettisonedHere() > 0) return true;
            cur = parentOf.getOrDefault(cur, -1);
        }
        return false;
    }

    private static String describeChildren(PathNode n) {
        StringBuilder sb = new StringBuilder("[");
        for (PathNode c : n.children()) {
            sb.append(c.nodeId()).append("(turn=").append(c.turns())
                    .append(",ab=").append(c.afterburnedHere()).append(") ");
        }
        return sb.append("]").toString();
    }
}
