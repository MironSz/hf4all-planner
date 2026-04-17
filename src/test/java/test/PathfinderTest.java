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
        String startId = "0.2807724500807758";       // lagrange near Mars
        String arsiaId = "0.3297091594567021";       // Mars: Arsia Mons caves

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
        String startId = "0.2807724500807758";           // lagrange near Mars
        String northPoleId = "0.1990466816181795";       // Mars: north pole

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
        String startId = "0.2807724500807758";           // lagrange near Mars
        String northPoleId = "0.1990466816181795";       // Mars: north pole

        // thrust=11 meets the landing burn thrustRequired=11 and exceeds site size=10
        EngineSpec highEngine = new EngineSpec(11, 2, false, 0);
        TraverseRequest highThrust = new TraverseRequest(
                startId, List.of(highEngine), 40);

        TraverseResponse highResponse = Pathfinder.traverse(map, highThrust);
        assertEquals("ok", highResponse.status());

        // Debug: print all reachable endpoints
        System.out.println("Reachable endpoints with thrust=11:");
        for (var entry : highResponse.endpoints().entrySet()) {
            String id = entry.getKey();
            var p = map.nodeById(id);
            System.out.println("  " + id.substring(0, 10) + " " + (p != null ? p : "?"));
        }

        assertTrue(highResponse.endpoints().containsKey(northPoleId),
                "Mars north pole should be reachable with thrust 11");
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
        String burnA = "0.016525490471957616";   // burn — "left" side
        String burnB = "0.4092470027101103";      // burn — "right" side
        String lagrangeA = "0.12420602360382982"; // lagrange on the "left" side of burnA

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
