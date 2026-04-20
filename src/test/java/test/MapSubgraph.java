package test;

import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.SolarMap;

import java.util.*;

/**
 * Test utility: extracts a radius-bounded neighbourhood from a {@link SolarMap}.
 *
 * <p>BFS from {@code centerId}, keeping every node at graph distance
 * {@code < radius} hops. Edges are copied only when both endpoints are in
 * the subgraph; direction labels (including "0" one-way markers) are
 * preserved on each half-edge that survives.
 *
 * <p>Radius semantics ("number of layers", 1-based):
 * <ul>
 *   <li>{@code radius = 1} → just the center node, no edges.</li>
 *   <li>{@code radius = 2} → center + its direct neighbours.</li>
 *   <li>{@code radius = n} → nodes at distance ≤ n−1 hops.</li>
 *   <li>{@code radius} ≥ graph diameter + 1 → whole map.</li>
 * </ul>
 *
 * <p>Note: boundary nodes in the extracted subgraph lose any edges leading
 * outside the radius. Pick a radius large enough that the pathfinder's
 * behaviour near the center is unaffected by boundary truncation.
 */
public final class MapSubgraph {

    private MapSubgraph() {}

    public static SolarMap extract(SolarMap source, String centerId, int radius) {
        if (radius < 1) {
            throw new IllegalArgumentException("radius must be >= 1, got " + radius);
        }
        MapNode center = source.nodeById(centerId);
        if (center == null) {
            throw new IllegalArgumentException("unknown node id: " + centerId);
        }

        // --- Phase 1: BFS to collect nodes within `radius` layers ---
        // maxHops is the largest BFS distance we still expand FROM.
        // radius=1 → maxHops=0 → never expand, only center survives.
        int maxHops = radius - 1;
        Map<MapNode, Integer> distance = new HashMap<>();
        distance.put(center, 0);
        Deque<MapNode> frontier = new ArrayDeque<>();
        frontier.add(center);
        while (!frontier.isEmpty()) {
            MapNode current = frontier.poll();
            int d = distance.get(current);
            if (d == maxHops) continue; // don't expand past the boundary
            for (MapNode neighbour : source.neighboursOf(current)) {
                if (distance.containsKey(neighbour)) continue;
                distance.put(neighbour, d + 1);
                frontier.add(neighbour);
            }
        }

        Set<MapNode> included = distance.keySet();

        // --- Phase 2: rebuild the graph, filtered to `included` ---
        SolarMap.Builder builder = SolarMap.builder();
        for (MapNode n : included) {
            builder.addNode(n);
        }

        // Add each undirected edge once, but copy labels for both half-edges.
        Set<String> addedEdges = new HashSet<>();
        for (MapNode from : included) {
            for (MapNode to : source.neighboursOf(from)) {
                if (!included.contains(to)) continue; // edge leaves the subgraph

                String edgeKey = undirectedKey(from, to);
                if (addedEdges.add(edgeKey)) {
                    builder.addEdge(from, to);
                }

                // Labels are directional; copy the one on this half-edge if set.
                // The reverse half-edge is handled when iteration reaches (to, from).
                String label = source.edgeLabel(from, to);
                if (label != null) {
                    builder.setEdgeLabel(from, to, label);
                }
            }
        }

        return builder.build();
    }

    private static String undirectedKey(MapNode a, MapNode b) {
        String ai = a.id(), bi = b.id();
        return ai.compareTo(bi) < 0 ? ai + "|" + bi : bi + "|" + ai;
    }
}
