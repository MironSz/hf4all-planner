package test.rules;

import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.pathfinder.Pathfinder;
import com.hf4all.planner.server.dto.EngineSpec;
import com.hf4all.planner.server.dto.PathNode;
import com.hf4all.planner.server.dto.TraverseRequest;
import com.hf4all.planner.server.dto.TraverseResponse;

import java.util.*;

/**
 * Shared helpers for rule-focused tests. Every helper is map-agnostic and
 * subgraph-agnostic — pass the {@link SolarMap} you want to search.
 */
final class RuleTestSupport {

    /** Common low-thrust engine used when the test doesn't care about thrust specifics. */
    static final EngineSpec ENGINE_LOW   = new EngineSpec(3, 2, false, 0);
    /** Engine capable of powered landings on any mid-sized site. */
    static final EngineSpec ENGINE_HIGH  = new EngineSpec(11, 2, false, 0);
    static final int FUEL_DEFAULT = 20;

    private RuleTestSupport() {}

    static TraverseResponse traverse(SolarMap map, String startId,
                                     List<EngineSpec> engines, int fuel) {
        return Pathfinder.traverse(map, new TraverseRequest(startId, engines, fuel));
    }

    static TraverseResponse traverse(SolarMap map, String startId, EngineSpec engine, int fuel) {
        return traverse(map, startId, List.of(engine), fuel);
    }

    /** Collect all unique (fuel|turns|hazards|radRoll) cost vectors reported for a node. */
    static Set<String> costVectorsAt(TraverseResponse r, String nodeId) {
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
    static boolean reached(TraverseResponse r, String nodeId) {
        return r.endpoints() != null && r.endpoints().containsKey(nodeId);
    }

    /** First PathNode at which the given mapNodeId appears in the search tree, null if none. */
    static PathNode findNodeByMapId(PathNode root, String mapNodeId) {
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
    static PathNode findTreeNode(PathNode root, int id) {
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
    static Optional<MapNode> findNeighbour(SolarMap map, String startId,
                                           java.util.function.Predicate<MapNode> predicate) {
        for (MapNode n : map.neighboursOf(startId)) {
            if (predicate.test(n)) return Optional.of(n);
        }
        return Optional.empty();
    }
}
