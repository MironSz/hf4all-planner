package test;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.pathfinder.Pathfinder;
import com.hf4all.planner.server.dto.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PathfinderTest {

    private static SolarMap map;

    /** Default engine: thrust=3, fuel/burn=2, no solar, no pivots. */
    private static final EngineSpec DEFAULT_ENGINE = new EngineSpec(3, 2, false, 0);
    private static final int DEFAULT_FUEL = 20;

    @BeforeAll
    static void loadMap() {
        map = MapLoader.loadDefault();
    }

    /**
     * Arsia Mons caves (size 10) should be reachable from its nearby lagrange
     * via the aerobrake path (decorative chain) with the default engine (thrust 3).
     *
     * Expected route:
     *   0.2807724500 [lagrange] → 0.9979005475 [decorative] →
     *   0.3073936033 [lagrange] → 0.2667560625 [decorative] →
     *   0.3297091594 [site: Mars: Arsia Mons caves]
     *
     * Cost: t1 f0 h1 r0
     */
    @Test
    void arsiaMonsReachableViaAerobrake() {
        String startId = "334";       // lagrange near Mars
        String arsiaId = "341";       // Mars: Arsia Mons caves

        TraverseRequest request = new TraverseRequest(
                startId, List.of(DEFAULT_ENGINE), DEFAULT_FUEL);

        TraverseResponse response = Pathfinder.traverse(map, request);

        assertEquals("ok", response.status());
        assertNotNull(response.endpoints());
        assertTrue(response.endpoints().containsKey(arsiaId),
                "Arsia Mons caves should be reachable via aerobrake with thrust 3");

        // Verify route costs: should be reachable in turn 1, zero fuel, 1 hazard
        List<Integer> treeNodeIds = response.endpoints().get(arsiaId);
        assertFalse(treeNodeIds.isEmpty());

        // Walk the tree to find the endpoint node and verify costs
        PathNode endpoint = findTreeNode(response.tree(), treeNodeIds.get(0));
        assertNotNull(endpoint, "Endpoint tree node should exist");
        assertEquals(arsiaId, endpoint.nodeId());
        assertEquals(0, endpoint.fuelSpent(), "Aerobrake should cost no fuel");
        assertEquals(1, endpoint.turns(), "Should arrive in turn 1");
        assertEquals(1, endpoint.hazards(), "Aerobrake path has 1 hazard");
        assertEquals(0, endpoint.worstRadRoll(), "No radiation on this path");
    }
    /**
     * Mars: north pole (size 10) has a direct aerobrake entry: the edge
     * haz-lagrange 0.38552 → Mars-NP carries a "0" one-way label. Per HF4A
     * rule H6b, aerobrake landings bypass the site-size thrust gate as long
     * as size >= 6. So thrust=3 must be able to land via aerobrake.
     */
    @Test
    void marsNorthPoleReachableViaAerobrakeWithLowThrust() {
        String startId = "334";           // lagrange near Mars
        String northPoleId = "340";       // Mars: north pole

        TraverseRequest req = new TraverseRequest(
                startId, List.of(DEFAULT_ENGINE), DEFAULT_FUEL);

        TraverseResponse response = Pathfinder.traverse(map, req);
        assertEquals("ok", response.status());
        assertTrue(response.endpoints().containsKey(northPoleId),
                "Mars north pole should be reachable with thrust 3 via the "
                + "direct one-way aerobrake edge from haz-lagrange 0.38552");
    }

    @Test
    void marsNorthPoleReachableWithHighThrust() {
        String startId = "334";           // lagrange near Mars
        String northPoleId = "340";       // Mars: north pole

        // thrust=11 meets the landing burn thrustRequired=11 and exceeds site size=10
        EngineSpec highEngine = new EngineSpec(11, 2, false, 0);
        TraverseRequest highThrust = new TraverseRequest(
                startId, List.of(highEngine), 40);

        TraverseResponse highResponse = Pathfinder.traverse(map, highThrust);
        assertEquals("ok", highResponse.status());

        assertTrue(highResponse.endpoints().containsKey(northPoleId),
                "Mars north pole should be reachable with thrust 11");
    }

    /**
     * Engine-switching to dodge radiation. From LEO to GEO there is a direct
     * 2-burn path that traverses a radhaz (node 0.6059...). With a single
     * low-thrust engine (3) you eat a rad-3 roll getting through it.
     *
     * But if you also carry a beefy engine (thrust 10, fuel 10), you have the
     * option of spending a turn switching engines so the radhaz is entered
     * under thrust 10 — mitigating the rad-6 down to 0. The Pareto frontier
     * at GEO should therefore contain three routes that trade fuel vs. turns
     * vs. radiation:
     *   Route 1: t=1 f=4 h=0 r=3  (fastest and cheapest, eats the rad)
     *   Route 2: t=3 f=4 h=0 r=0  (takes 3 turns juggling to avoid rad)
     *   Route 3: t=2 f=12 h=0 r=0 (switches to thrust-10 engine to skip rad)
     *
     * Seeing all three means the planner is correctly exploring multi-engine
     * waitTurn transitions, not just the cheapest single-engine path.
     */
    @Test
    void engineSwitchingOffersRadiationMitigationRoutes() {
        String leoId = "90"; // lagrange LEO
        String geoId = "96";  // burn GEO

        EngineSpec lowThrust  = new EngineSpec(3, 2, false, 0);
        EngineSpec highThrust = new EngineSpec(10, 10, false, 0);
        TraverseRequest req = new TraverseRequest(
                leoId, List.of(lowThrust, highThrust), 40);

        TraverseResponse response = Pathfinder.traverse(map, req);
        assertEquals("ok", response.status());

        List<Integer> treeIds = response.endpoints().get(geoId);
        assertNotNull(treeIds, "GEO should be reachable");

        // Collapse routes that share the same visible map-node sequence
        // (matches the UI's getTreeNodeIds dedup). The backend's Pareto set
        // can include multiple cost vectors over the same physical path
        // (e.g. same hops, different engine/fuel trade) — the UI and this
        // test only count visually distinct routes.
        Set<String> seenSequences = new LinkedHashSet<>();
        Set<String> actualCosts = new LinkedHashSet<>();
        for (int id : treeIds) {
            List<PathNode> path = getPathToRoot(response.tree(), id);
            String sequence = path.stream()
                    .map(PathNode::nodeId)
                    .reduce((a, b) -> a + "|" + b).orElse("");
            if (!seenSequences.add(sequence)) continue; // already covered by an earlier route
            PathNode n = path.get(path.size() - 1);
            actualCosts.add(n.fuelSpent() + "|" + n.turns()
                    + "|" + n.hazards() + "|" + n.worstRadRoll());
        }

        Set<String> expected = Set.of(
                "4|1|0|3",   // direct: 2 burns through radhaz, eat rad-3
                "4|3|0|0",   // 3 turns, avoid rad via waitTurn manoeuvres
                "12|2|0|0"   // switch to thrust-10, blow through radhaz unharmed
        );
        assertEquals(expected, actualCosts,
                "Pareto cost vectors at GEO must match the expected three");
    }

    /**
     * A decorative chain links two burn nodes:
     *   burn 0.0165... ↔ dec ↔ dec ↔ dec ↔ burn 0.4092...
     *
     * If the forward route (start = 0.0165...) reaches 0.4092...,
     * the reverse (start = 0.4092...) must be able to reach 0.0165...
     * (and proceed to the lagrange and sites on the other side).
     *
     * Previously blocked by the blanket "decorative → burn" restriction
     * (restriction #3, now removed). Both directions should work.
     */
    @Test
    void decorativeChainBidirectional() {
        String burnA = "890";   // burn — "left" side
        String burnB = "257";      // burn — "right" side
        String lagrangeA = "894"; // lagrange on the "left" side of burnA

        // Forward: start at burnA, burnB should be in the tree (as an intermediate
        // on paths to sites like Mars: Arsia Mons caves, reachable via the
        // aerobrake chain off lagrange 0.2807 — which sits one hop past burnB).
        TraverseRequest fwdReq = new TraverseRequest(
                burnA, List.of(DEFAULT_ENGINE), DEFAULT_FUEL);
        TraverseResponse fwdResp = Pathfinder.traverse(map, fwdReq);
        assertEquals("ok", fwdResp.status());

        assertNotNull(findNodeByMapId(fwdResp.tree(), burnB),
                "Forward: burnB should be reachable from burnA via decorative chain");

        // Reverse: start at burnB, burnA and lagrangeA should be in the tree.
        // Every reachable non-decorative node is treated as an endpoint, so
        // burnA and lagrangeA appear even when the region beyond them is
        // thrust-gated and leads to no reachable sites.
        TraverseRequest revReq = new TraverseRequest(
                burnB, List.of(DEFAULT_ENGINE), DEFAULT_FUEL);
        TraverseResponse revResp = Pathfinder.traverse(map, revReq);
        assertEquals("ok", revResp.status());

        assertNotNull(findNodeByMapId(revResp.tree(), burnA),
                "Reverse: burnA should be reachable from burnB via decorative chain");
        assertNotNull(findNodeByMapId(revResp.tree(), lagrangeA),
                "Reverse: lagrangeA should be reachable from burnB via the chain");
    }

    /**
     * Sanity: {@code MapNode.solarMod()} is loaded from the JSON. Picks one
     * known-outer (sphere7, modifier −4) and one known-inner (sphere0,
     * modifier +2) node; verifies both values round-trip through the loader.
     * Also verifies an unlabeled node defaults to 0.
     */
    @Test
    void solarModLoadsFromJson() {
        // sphere7 — outermost (−4)
        assertEquals(-4, map.nodeById("669").solarMod(),
                "sphere7 node should load with solarMod = −4");
        // sphere0 — innermost (+2)
        assertEquals(+2, map.nodeById("39").solarMod(),
                "sphere0 node should load with solarMod = +2");
        // Unlabeled node (LEO from the engine-switching test) defaults to 0
        assertEquals(0, map.nodeById("90").solarMod(),
                "Unlabeled node should default to solarMod = 0");
    }

    /**
     * Solar-power rule (H3c). Starting at a sphere7 burn node (solarMod −4):
     *   - A non-solar engine of base thrust 4 has effective thrust 4 → can burn.
     *   - The same engine with {@code solarPowered=true} has effective thrust
     *     4 + (−4) = 0 → cannot perform any burn (paid, bonus, or force-turn);
     *     it can only coast along non-burn cruise edges.
     *
     * Non-solar must therefore reach strictly more endpoints than solar.
     */
    @Test
    void solarEngineInOuterZoneHasReducedReachability() {
        String sphere7Id = "669"; // burn node, solarMod = −4

        EngineSpec nonSolar = new EngineSpec(4, 2, false, 0);
        EngineSpec solar    = new EngineSpec(4, 2, true,  0);

        TraverseResponse nonSolarResp = Pathfinder.traverse(map,
                new TraverseRequest(sphere7Id, List.of(nonSolar), 40));
        TraverseResponse solarResp = Pathfinder.traverse(map,
                new TraverseRequest(sphere7Id, List.of(solar), 40));

        assertEquals("ok", nonSolarResp.status());
        assertEquals("ok", solarResp.status());

        int nonSolarEndpoints = nonSolarResp.endpoints().size();
        int solarEndpoints    = solarResp.endpoints().size();

        assertTrue(solarEndpoints < nonSolarEndpoints,
                "Solar engine should reach strictly fewer endpoints than non-solar "
              + "at a sphere7 start (solarMod = −4 → effective thrust 0, no burns). "
              + "Got solar=" + solarEndpoints + ", nonSolar=" + nonSolarEndpoints);
    }

    /** Walk from a tree node to the root, returning the full path. */
    private static List<PathNode> getPathToRoot(PathNode root, int targetId) {
        // Build parent map
        Map<Integer, PathNode> parentMap = new HashMap<>();
        Map<Integer, PathNode> nodeMap = new HashMap<>();
        Deque<PathNode> stack = new ArrayDeque<>();
        stack.push(root);
        nodeMap.put(root.id(), root);
        while (!stack.isEmpty()) {
            PathNode n = stack.pop();
            for (PathNode child : n.children()) {
                parentMap.put(child.id(), n);
                nodeMap.put(child.id(), child);
                stack.push(child);
            }
        }
        List<PathNode> path = new ArrayList<>();
        PathNode current = nodeMap.get(targetId);
        while (current != null) {
            path.add(0, current);
            current = parentMap.get(current.id());
        }
        return path;
    }

    /** BFS search for a PathNode by its integer tree id. */
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

    /** BFS over the tree, printing the first maxNodes entries (nodeId + type). */
    private static void printTreeNodes(PathNode root, int maxNodes) {
        if (root == null) { System.out.println("  (null tree)"); return; }
        Deque<PathNode> queue = new ArrayDeque<>();
        queue.add(root);
        int count = 0;
        while (!queue.isEmpty() && count < maxNodes) {
            PathNode n = queue.poll();
            var mp = map.nodeById(n.nodeId());
            String info = mp != null ? mp.toString() : n.nodeId().substring(0, 12);
            System.out.printf("  [%d] t%d f%d h%d  %s%n",
                    n.id(), n.turns(), n.fuelSpent(), n.hazards(), info);
            count++;
            queue.addAll(n.children());
        }
    }

    /** BFS search for the first PathNode whose map nodeId matches the given id string. */
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
