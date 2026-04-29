package com.hf4all.planner.map;

import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.support.MapSubgraph;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MapSubgraphTest {

    private static SolarMap fullMap;

    /** A node known to exist on the default map (lagrange near Mars). */
    private static final String CENTER_ID = "334";

    @BeforeAll
    static void loadMap() {
        fullMap = MapLoader.loadDefault();
    }

    /**
     * Radius 1 is the degenerate case: the subgraph contains only the center
     * node, with no edges and no neighbours.
     */
    @Test
    void radiusOneReturnsOnlyTheCenterNode() {
        SolarMap sub = MapSubgraph.extract(fullMap, CENTER_ID, 1);

        assertEquals(1, sub.nodeCount(), "radius 1 subgraph must contain exactly one node");
        assertEquals(0, sub.edgeCount(), "radius 1 subgraph must contain no edges");

        MapNode center = sub.nodeById(CENTER_ID);
        assertNotNull(center, "the center node must be present in the subgraph");
        assertTrue(sub.neighboursOf(center).isEmpty(),
                "the center node must have no neighbours at radius 1");
    }

    /**
     * A radius far exceeding the graph diameter returns the whole reachable
     * component of the center. For the default HF4A map this is everything
     * except a handful of orphan nodes (if any) with no edges.
     */
    @Test
    void radiusHugeReturnsWholeMap() {
        SolarMap sub = MapSubgraph.extract(fullMap, CENTER_ID, 10_000);

        int reachable = countReachableFrom(fullMap, CENTER_ID);
        assertEquals(reachable, sub.nodeCount(),
                "a huge radius must include every node reachable from the center");
        assertEquals(fullMap.edgeCount(), sub.edgeCount(),
                "a huge radius must preserve every edge in the connected component "
                + "(orphan nodes have no edges, so edge count is unchanged)");
    }

    /** BFS size of the connected component containing the given node. */
    private static int countReachableFrom(SolarMap map, String startId) {
        MapNode start = map.nodeById(startId);
        Set<MapNode> seen = new HashSet<>();
        Deque<MapNode> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            MapNode n = queue.poll();
            for (MapNode adj : map.neighboursOf(n)) {
                if (seen.add(adj)) queue.add(adj);
            }
        }
        return seen.size();
    }
}
