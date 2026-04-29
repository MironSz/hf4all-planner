package com.hf4all.planner.support;

import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.pathfinder.Pathfinder;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.api.PathNode;
import com.hf4all.planner.api.TraverseRequest;
import com.hf4all.planner.api.TraverseResponse;

import java.util.*;

/**
 * Shared helpers for rule-focused tests. Every helper is map-agnostic and
 * subgraph-agnostic — pass the {@link SolarMap} you want to search.
 */
public final class RuleTestSupport {

    /**
     * Common low-thrust engine used when the test doesn't care about thrust
     * specifics. {@code baseThrust=5} compensates for the default loadout's
     * Tug-class -2 modifier so that net thrust = 3, matching the values
     * these tests were originally calibrated against.
     */
    public static final EngineSpec ENGINE_LOW   = new EngineSpec(5, 2, false, 0);
    /** Engine capable of powered landings on mid-sized sites (net thrust 11 at Tug). */
    public static final EngineSpec ENGINE_HIGH  = new EngineSpec(13, 2, false, 0);

    /** Default Dry Mass for tests that don't care about specific weight class. */
    public static final int DRY_DEFAULT  = 4;
    /** Default fuel (water tanks loaded). With DRY_DEFAULT this gives Wet 24 (Tug class). */
    public static final int FUEL_DEFAULT = 20;

    private RuleTestSupport() {}

    public static TraverseResponse traverse(SolarMap map, String startId,
                                     List<EngineSpec> engines, int fuel) {
        return Pathfinder.traverse(map, new TraverseRequest(startId, engines, DRY_DEFAULT, fuel));
    }

    public static TraverseResponse traverse(SolarMap map, String startId, EngineSpec engine, int fuel) {
        return traverse(map, startId, List.of(engine), fuel);
    }

    /** Collect all unique (fuel|turns|hazards|radRoll) cost vectors reported for a node. */
    public static Set<String> costVectorsAt(TraverseResponse r, String nodeId) {
        Set<String> out = new LinkedHashSet<>();
        if (r.endpoints() == null) return out;
        List<Integer> ids = r.endpoints().get(nodeId);
        if (ids == null) return out;
        for (int id : ids) {
            PathNode pn = findTreeNode(r.tree(), id);
            if (pn != null) out.add(pn.fuelSpent() + "|" + pn.turns()
                    + "|" + pn.hazards() + "|" + pn.worstRadRoll());
        }
        return out;
    }

    /** True if the node id is among the search response's endpoints. */
    public static boolean reached(TraverseResponse r, String nodeId) {
        return r.endpoints() != null && r.endpoints().containsKey(nodeId);
    }

    /** First PathNode at which the given mapNodeId appears in the search tree, null if none. */
    public static PathNode findNodeByMapId(PathNode root, String mapNodeId) {
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

    /** BFS search for a tree node by its integer id. */
    public static PathNode findTreeNode(PathNode root, int id) {
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

    /** Find the first neighbour of {@code startId} whose node passes the given test. */
    public static Optional<MapNode> findNeighbour(SolarMap map, String startId,
                                           java.util.function.Predicate<MapNode> predicate) {
        for (MapNode n : map.neighboursOf(startId)) {
            if (predicate.test(n)) return Optional.of(n);
        }
        return Optional.empty();
    }
}
