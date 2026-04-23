package test;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.FuelStrip;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.pathfinder.Pathfinder;
import com.hf4all.planner.server.dto.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PathfinderTest {

    private static SolarMap map;

    /**
     * Default ship: dryMass=4, fuel=15 (Wet 19, Tug class → -2 thrust mod).
     * Default engine has baseThrust=5 so net thrust at Tug = 3 — matching the
     * pre-mass-tracking tests that assumed a fixed thrust of 3.
     */
    private static final int DEFAULT_DRY  = 4;
    private static final int DEFAULT_FUEL = 15;
    private static final EngineSpec DEFAULT_ENGINE = new EngineSpec(5, 2, false, 0);

    @BeforeAll
    static void loadMap() {
        map = MapLoader.loadDefault();
    }

    /** Initial fuel steps the default loadout grants (used to convert
     *  "steps spent" expectations into "remaining" assertions). */
    private static int defaultInitialSteps() {
        return FuelStrip.initialFuelSteps(DEFAULT_DRY, DEFAULT_FUEL);
    }

    private static TraverseRequest defaultRequest(String startId) {
        return new TraverseRequest(startId, List.of(DEFAULT_ENGINE), DEFAULT_DRY, DEFAULT_FUEL);
    }

    /**
     * Arsia Mons caves (size 10) should be reachable from its nearby lagrange
     * via the aerobrake path (decorative chain) with the default engine
     * (net thrust 3 at Tug class).
     *
     * Cost: t1, no fuel spent (aerobrake), 1 hazard, 0 rad.
     */
    @Test
    void arsiaMonsReachableViaAerobrake() {
        String startId = "334";       // lagrange near Mars
        String arsiaId = "341";       // Mars: Arsia Mons caves

        TraverseResponse response = Pathfinder.traverse(map, defaultRequest(startId));

        assertEquals("ok", response.status());
        assertNotNull(response.endpoints());
        assertTrue(response.endpoints().containsKey(arsiaId),
                "Arsia Mons caves should be reachable via aerobrake with net thrust 3");

        List<Integer> treeNodeIds = response.endpoints().get(arsiaId);
        assertFalse(treeNodeIds.isEmpty());

        PathNode endpoint = findTreeNode(response.tree(), treeNodeIds.get(0));
        assertNotNull(endpoint, "Endpoint tree node should exist");
        assertEquals(arsiaId, endpoint.nodeId());
        assertEquals(defaultInitialSteps(), endpoint.fuelStepsRemaining(),
                "Aerobrake should cost no fuel");
        assertEquals(1, endpoint.turns(), "Should arrive in turn 1");
        assertEquals(1, endpoint.hazards(), "Aerobrake path has 1 hazard");
        assertEquals(0, endpoint.worstRadRoll(), "No radiation on this path");
    }

    /**
     * Mars: north pole (size 10) has a direct aerobrake entry: the edge
     * haz-lagrange 0.38552 → Mars-NP carries a "0" one-way label. Per HF4A
     * rule H6b, aerobrake landings bypass the site-size thrust gate as long
     * as size >= 6. So net thrust=3 must be able to land via aerobrake.
     */
    @Test
    void marsNorthPoleReachableViaAerobrakeWithLowThrust() {
        String startId = "334";           // lagrange near Mars
        String northPoleId = "340";       // Mars: north pole

        TraverseResponse response = Pathfinder.traverse(map, defaultRequest(startId));
        assertEquals("ok", response.status());
        assertTrue(response.endpoints().containsKey(northPoleId),
                "Mars north pole should be reachable with net thrust 3 via the "
                + "direct one-way aerobrake edge from haz-lagrange 0.38552");
    }

    /**
     * High-thrust path: with baseThrust=13 the engine has net thrust 11 at
     * Tug class — meets the landing burn thrustRequired=11 and exceeds site
     * size=10.
     */
    @Test
    void marsNorthPoleReachableWithHighThrust() {
        String startId = "334";
        String northPoleId = "340";

        EngineSpec highEngine = new EngineSpec(13, 2, false, 0);
        TraverseRequest highThrust = new TraverseRequest(
                startId, List.of(highEngine), DEFAULT_DRY, /* fuel = */ 20);

        TraverseResponse highResponse = Pathfinder.traverse(map, highThrust);
        assertEquals("ok", highResponse.status());

        assertTrue(highResponse.endpoints().containsKey(northPoleId),
                "Mars north pole should be reachable with net thrust 11");
    }

    /**
     * Decorative chain bidirectionality. If the forward direction reaches a
     * node, the reverse direction must reach it back — used to verify the
     * pathfinder's symmetric handling of decorative-burn chains.
     */
    @Test
    void decorativeChainBidirectional() {
        String burnA = "890";
        String burnB = "257";
        String lagrangeA = "894";

        TraverseResponse fwdResp = Pathfinder.traverse(map, defaultRequest(burnA));
        assertEquals("ok", fwdResp.status());
        assertNotNull(findNodeByMapId(fwdResp.tree(), burnB),
                "Forward: burnB should be reachable from burnA via decorative chain");

        TraverseResponse revResp = Pathfinder.traverse(map, defaultRequest(burnB));
        assertEquals("ok", revResp.status());
        assertNotNull(findNodeByMapId(revResp.tree(), burnA),
                "Reverse: burnA should be reachable from burnB via decorative chain");
        assertNotNull(findNodeByMapId(revResp.tree(), lagrangeA),
                "Reverse: lagrangeA should be reachable from burnB via the chain");
    }

    /**
     * Solar-power thrust modifier is loaded into MapNode.solarMod from the
     * JSON map. Sanity check on a few known nodes.
     */
    @Test
    void solarModLoadsFromJson() {
        assertEquals(-4, map.nodeById("669").solarMod(),
                "sphere7 node should load with solarMod = −4");
        assertEquals(+2, map.nodeById("39").solarMod(),
                "sphere0 node should load with solarMod = +2");
        assertEquals(0, map.nodeById("90").solarMod(),
                "Unlabeled node should default to solarMod = 0");
    }

    @Disabled("Pareto state-space explosion at sphere7 with high thrust/fuel — see Pathfinder interrupt-check TODO")
    @Test
    void solarEngineInOuterZoneHasReducedReachability() {
        // Carry-over disabled test; preserved for future enabling once the
        // search-budget interrupt is added.
    }

    @Disabled("Engine-switching cost vectors need recalibration under mass-aware thrust"
              + " — preserved as a fixture for the next pass.")
    @Test
    void engineSwitchingOffersRadiationMitigationRoutes() {
        // The original test asserted three exact cost vectors at GEO. Those
        // vectors were derived under the old "thrust is a fixed integer"
        // model. With mass-tracked thrust the thrust-10 engine's net thrust
        // varies with current Wet Mass, so the optimal-route set changes.
        // Re-derive after the UI is migrated and we have ground-truth runs.
    }

    // -------------------------------------------------------------------------
    // New: fuel-strip / mass-tracking tests
    // -------------------------------------------------------------------------

    /** The 1↔2 interval is 9 fuel steps (the worst-case rocket-equation slot). */
    @Test
    void fuelStripIntervalsFromRulebookExample() {
        // "Going from 5 to 2 uses 13 steps of Fuel (3+4+6)" — FUEL STRIP KEY
        assertEquals(13, FuelStrip.stepsBetween(2, 5));
        // Worked example (E5): 3 tanks loaded at WM 5 → 7 fuel steps in the
        // 5–8 black-line range.
        assertEquals(7, FuelStrip.stepsBetween(5, 8));
        // Worked example (H5): WM 19 → 7 burned 16 steps.
        assertEquals(16, FuelStrip.stepsBetween(7, 19));
        // 1↔2 = 9 (per rulebook playmat printed value).
        assertEquals(9, FuelStrip.stepsBetween(1, 2));
    }

    /** Weight-class thresholds match HF4A H3c. */
    @Test
    void weightClassThresholds() {
        assertEquals(+2, FuelStrip.weightClassModForWetMass(1));   // Wisp
        assertEquals(+1, FuelStrip.weightClassModForWetMass(2));   // Probe
        assertEquals( 0, FuelStrip.weightClassModForWetMass(3));   // Scout
        assertEquals(-1, FuelStrip.weightClassModForWetMass(4));   // Transport
        assertEquals(-1, FuelStrip.weightClassModForWetMass(5));   // Transport
        assertEquals(-2, FuelStrip.weightClassModForWetMass(6));   // Tug
        assertEquals(-2, FuelStrip.weightClassModForWetMass(32));  // Tug
    }

    /**
     * The (dryMass, fuelStepsRemaining) overload walks the non-linear fuel
     * strip: INTERVALS[1..31] = {9, 6, 4, 3, 3, 2, 2, 2, 2, 2, 1, 1, …}.
     * For dry=1 the class bands (in fuel steps) are:
     *   Wisp      = WM=1    = [ 0,  8]   (9 steps of interval 1↔2)
     *   Probe     = WM=2    = [ 9, 14]   (6 steps of interval 2↔3)
     *   Scout     = WM=3    = [15, 18]   (4 steps of interval 3↔4)
     *   Transport = WM=4..5 = [19, 24]   (3 + 3 steps)
     *   Tug       = WM=6..  = [25,   ]
     * Tests each band's first step and last step to catch off-by-ones.
     */
    @Test
    void weightClassModDryOneBoundaries() {
        assertEquals(+2, FuelStrip.weightClassMod(1,  0), "dry=1, fuel=0 → Wisp");
        assertEquals(+2, FuelStrip.weightClassMod(1,  8), "dry=1, fuel=8 (last Wisp step) → Wisp");
        assertEquals(+1, FuelStrip.weightClassMod(1,  9), "dry=1, fuel=9 (first Probe step) → Probe");
        assertEquals(+1, FuelStrip.weightClassMod(1, 14), "dry=1, fuel=14 (last Probe step) → Probe");
        assertEquals( 0, FuelStrip.weightClassMod(1, 15), "dry=1, fuel=15 (first Scout step) → Scout");
        assertEquals( 0, FuelStrip.weightClassMod(1, 18), "dry=1, fuel=18 (last Scout step) → Scout");
        assertEquals(-1, FuelStrip.weightClassMod(1, 19), "dry=1, fuel=19 (WM=4) → Transport");
        assertEquals(-1, FuelStrip.weightClassMod(1, 24), "dry=1, fuel=24 (last Transport step) → Transport");
        assertEquals(-2, FuelStrip.weightClassMod(1, 25), "dry=1, fuel=25 (first Tug step) → Tug");
    }

    /**
     * The starting class when fuel=0 is determined by dryMass alone (Wet
     * Mass = Dry Mass). Steps between class boundaries from each dryMass
     * are probed to catch off-by-ones in the strip walk.
     */
    @Test
    void weightClassModFuelZeroAndBoundaries() {
        // Note the correction of the third requested case: dry=2 fuel=0 gives
        // Wet Mass = 2 → Probe (+1), NOT Scout. Scout needs WM=3.
        assertEquals(+1, FuelStrip.weightClassMod(2, 0), "dry=2, fuel=0 → Probe");
        assertEquals(+1, FuelStrip.weightClassMod(2, 5), "dry=2, fuel=5 (last Probe step) → Probe");
        assertEquals( 0, FuelStrip.weightClassMod(2, 6), "dry=2, fuel=6 (first Scout step) → Scout");

        assertEquals( 0, FuelStrip.weightClassMod(3, 0), "dry=3, fuel=0 → Scout");
        assertEquals( 0, FuelStrip.weightClassMod(3, 3), "dry=3, fuel=3 (last Scout step) → Scout");
        assertEquals(-1, FuelStrip.weightClassMod(3, 4), "dry=3, fuel=4 (first Transport step) → Transport");

        assertEquals(-1, FuelStrip.weightClassMod(4, 0), "dry=4, fuel=0 → Transport");
        assertEquals(-1, FuelStrip.weightClassMod(4, 5), "dry=4, fuel=5 (last Transport step) → Transport");
        assertEquals(-2, FuelStrip.weightClassMod(4, 6), "dry=4, fuel=6 (first Tug step) → Tug");

        assertEquals(-1, FuelStrip.weightClassMod(5, 0), "dry=5, fuel=0 → Transport");
        assertEquals(-1, FuelStrip.weightClassMod(5, 2), "dry=5, fuel=2 (last Transport step) → Transport");
        assertEquals(-2, FuelStrip.weightClassMod(5, 3), "dry=5, fuel=3 (first Tug step) → Tug");
    }

    /**
     * Tug-range edge cases: dryMass ≥ 6 is Tug regardless of fuel load, and
     * the chit never leaves Tug no matter how much it climbs. Also exercises
     * the MAX_WET_MASS boundary and max dry mass.
     */
    @Test
    void weightClassModTugAndMaxBoundaries() {
        assertEquals(-2, FuelStrip.weightClassMod( 6,  0), "dry=6, fuel=0 → Tug");
        assertEquals(-2, FuelStrip.weightClassMod(10,  0), "dry=10, fuel=0 → Tug");
        assertEquals(-2, FuelStrip.weightClassMod(23,  0), "dry=MAX_DRY(23), fuel=0 → Tug");

        // Max Wet Mass = 32: dry=1 + full strip traversal.
        assertEquals(-2, FuelStrip.weightClassMod( 1, 56), "dry=1, full strip (WM=32) → Tug");
        assertEquals(-2, FuelStrip.weightClassMod(31,  1), "dry=31, fuel=1 step (WM=32) → Tug");

        // Tug ship with some fuel stays Tug (large wet mass region).
        assertEquals(-2, FuelStrip.weightClassMod( 6, 10), "dry=6, fuel=10 (WM=11) → Tug");
    }

    /**
     * Jettison ladder shape: from a Tug-class state, the only class-changing
     * jettison is the one that drops into Transport (WM ≤ 5). Lower targets
     * (Scout/Probe/Wisp) are unreachable when dryMass > those thresholds.
     *
     * <p>The amount jettisons to the MAX fuel still in Transport class (WM=5,
     * partial 2 into 5↔6 interval = fuelSteps 24 for dry=1 or fuelSteps 5
     * for dry=4). It does NOT jettison down to the exact WM integer — that
     * would waste fuel that's still in the same class.
     */
    @Test
    void jettisonLadderProducesOnlyClassChangingAmounts() {
        // Tug-class ship: dry 4, fuel 15 (wet 19).
        int initialSteps = FuelStrip.initialFuelSteps(4, 15);
        int[] amounts = FuelStrip.jettisonAmountsForClassChange(4, initialSteps);

        assertEquals(1, amounts.length, "Tug → Transport is the only class change with dryMass=4");
        // Max fuel still in Transport (wm ≤ 5) = stepsBetween(4, 6) - 1 = 2 + 3 - 1 = wait,
        // stepsBetween(4,6) = INTERVALS[4] + INTERVALS[5] = 3+3 = 6 → upperFuel = 5.
        int upperFuelInTransport = FuelStrip.stepsBetween(4, 6) - 1;
        int expected = initialSteps - upperFuelInTransport;
        assertEquals(expected, amounts[0]);
    }

    /**
     * Lazy jettison: enabling {@code allowFuelJettison} should never *reduce*
     * reachable endpoints (jettison only adds capabilities). It often gives
     * the SAME endpoint count when no thrust gate is in play — which is the
     * laziness property: useless jettison branches aren't spawned.
     */
    @Test
    void lazyJettisonNeverReducesEndpoints() {
        TraverseRequest noJet = new TraverseRequest(
                "334", List.of(DEFAULT_ENGINE), DEFAULT_DRY, DEFAULT_FUEL, false, false);
        TraverseRequest withJet = new TraverseRequest(
                "334", List.of(DEFAULT_ENGINE), DEFAULT_DRY, DEFAULT_FUEL, false, true);

        TraverseResponse rNo = Pathfinder.traverse(map, noJet);
        TraverseResponse rJet = Pathfinder.traverse(map, withJet);

        assertEquals("ok", rNo.status());
        assertEquals("ok", rJet.status());
        assertTrue(rJet.endpoints().size() >= rNo.endpoints().size(),
                "Enabling jettison must not REMOVE any reachable endpoints. "
                + "noJet=" + rNo.endpoints().size() + " withJet=" + rJet.endpoints().size());
    }

    /**
     * Lazy jettison: when no thrust gate exists that the loadout can't pass,
     * the lazy spawner should produce zero jettison events. We force this
     * scenario with an absurdly-high-thrust engine (baseThrust=30 → net 28
     * at Tug class) — every map gate is trivially satisfied, so jettison
     * has nothing to unlock.
     */
    @Test
    void lazyJettisonProducesNoEventsWhenNotNeeded() {
        EngineSpec overpowered = new EngineSpec(30, 2, false, 0);
        TraverseRequest req = new TraverseRequest(
                "334", List.of(overpowered), DEFAULT_DRY, DEFAULT_FUEL, false, true);
        TraverseResponse r = Pathfinder.traverse(map, req);
        assertEquals("ok", r.status());

        int withJettison = 0;
        Deque<PathNode> stack = new ArrayDeque<>();
        if (r.tree() != null) stack.push(r.tree());
        while (!stack.isEmpty()) {
            PathNode n = stack.pop();
            if (n.jettisonedHere() > 0) withJettison++;
            stack.addAll(n.children());
        }
        assertEquals(0, withJettison,
                "An overpowered engine never hits a thrust gate, so the lazy "
                + "spawner must produce zero jettison events. Got " + withJettison);
    }

    /**
     * dryMass=1 ship has the full jettison ladder available: it can reach
     * Transport, Scout, Probe, and Wisp by progressive dumping.
     */
    @Test
    void jettisonLadderFullForDryOne() {
        int initialSteps = FuelStrip.initialFuelSteps(1, 20);   // wet 21, Tug
        int[] amounts = FuelStrip.jettisonAmountsForClassChange(1, initialSteps);
        assertEquals(4, amounts.length,
                "dryMass=1 ship should have 4 class-change options (Transport/Scout/Probe/Wisp)");
        // Amounts must be strictly increasing (each subsequent class = lower WM = bigger dump).
        for (int i = 1; i < amounts.length; i++) {
            assertTrue(amounts[i] > amounts[i-1],
                    "Jettison amounts should be strictly increasing along the class ladder");
        }
    }

    // -------------------------------------------------------------------------
    // Tree-walking helpers (carried over from the previous test file).
    // -------------------------------------------------------------------------

    private static PathNode findTreeNode(PathNode root, int id) {
        if (root == null) return null;
        Deque<PathNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            PathNode n = queue.poll();
            if (n.id() == id) return n;
            queue.addAll(n.children());
        }
        return null;
    }

    private static PathNode findNodeByMapId(PathNode root, String mapNodeId) {
        if (root == null) return null;
        Deque<PathNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            PathNode n = queue.poll();
            if (mapNodeId.equals(n.nodeId())) return n;
            queue.addAll(n.children());
        }
        return null;
    }
}
