package com.hf4all.planner.model;

import java.util.*;

/**
 * The full HF4A solar system graph. Immutable after construction (use the Builder).
 *
 * Adjacency is stored as {@code Map<MapNode, List<MapNode>>}.
 * Edge direction labels (used at Hohmann intersections) are stored as
 * {@code Map<MapNode, Map<MapNode, String>>} — meaning: "when leaving fromNode
 * toward toNode, the outgoing direction label is X".
 *
 * Direction labels are strings "1" or "2". At a Hohmann node, arriving from
 * direction "1" and departing toward direction "1" is straight-through (free).
 * Departing toward a different direction costs 2 burns or 1 pivot.
 */
public final class SolarMap {

    private final Map<String, MapNode>                    nodesById;
    private final Map<MapNode, List<MapNode>>             adjacency;
    // Redundant with adjacency but gives O(1) edge existence checks
    private final Map<MapNode, Set<MapNode>>              adjacencySet;
    private final Map<MapNode, Map<MapNode, String>>      edgeLabels;

    /**
     * HF4A B7h/H6 synodic-comet season gates. Populated at build time:
     * every node tagged {@code "synodic": "Red|Yellow|Blue"} in the data
     * maps to the corresponding {@link Season}; additionally, the closest
     * non-decorative neighbour of each synodic site (the "adjacent
     * coloured space" per H6) inherits the same season here. Pathfinder
     * uses this map to gate entry/exit; missing key = no gate.
     */
    private final Map<MapNode, Season>                    synodicGates;

    private SolarMap(Builder b) {
        this.nodesById = Collections.unmodifiableMap(new HashMap<>(b.nodesById));

        // Freeze adjacency lists (ordered, for deterministic iteration)
        Map<MapNode, List<MapNode>> adj = new HashMap<>();
        b.adjacency.forEach((node, neighbours) ->
            adj.put(node, Collections.unmodifiableList(new ArrayList<>(neighbours))));
        this.adjacency = Collections.unmodifiableMap(adj);

        // Build O(1) lookup sets from the same data
        Map<MapNode, Set<MapNode>> adjSet = new HashMap<>();
        b.adjacency.forEach((node, neighbours) ->
            adjSet.put(node, Collections.unmodifiableSet(new HashSet<>(neighbours))));
        this.adjacencySet = Collections.unmodifiableMap(adjSet);

        // Freeze edge label maps
        Map<MapNode, Map<MapNode, String>> el = new HashMap<>();
        b.edgeLabels.forEach((from, targets) ->
            el.put(from, Collections.unmodifiableMap(new HashMap<>(targets))));
        this.edgeLabels = Collections.unmodifiableMap(el);

        // Compute synodic gates: each tagged site contributes itself and
        // its closest non-decorative neighbour (BFS over decoratives).
        this.synodicGates = Collections.unmodifiableMap(buildSynodicGates());
    }

    private Map<MapNode, Season> buildSynodicGates() {
        Map<MapNode, Season> gates = new HashMap<>();
        for (MapNode site : nodesById.values()) {
            Season s = site.synodic();
            if (s == null) continue;
            gates.put(site, s);
            // BFS from the site, walking through decorative nodes only,
            // until we hit the first non-decorative neighbour. That node
            // also gets the same season gate (H6 "adjacent coloured space").
            Set<MapNode> seen = new HashSet<>();
            seen.add(site);
            Deque<MapNode> queue = new ArrayDeque<>();
            for (MapNode neighbor : adjacency.getOrDefault(site, List.of())) {
                if (seen.add(neighbor)) queue.add(neighbor);
            }
            while (!queue.isEmpty()) {
                MapNode n = queue.poll();
                if (!n.isDecorative()) {
                    // First non-decorative reachable through dec-chain — this is
                    // the "adjacent coloured space". Stop the BFS for this site.
                    gates.merge(n, s, (existing, candidate) ->
                            existing == candidate ? existing : existing); // keep first if conflict
                    break;
                }
                for (MapNode hop : adjacency.getOrDefault(n, List.of())) {
                    if (seen.add(hop)) queue.add(hop);
                }
            }
        }
        return gates;
    }

    /**
     * Returns the {@link Season} this node may be entered/exited in (B7h/H6),
     * or {@code null} for unrestricted nodes (the vast majority).
     * Populated for every {@link MapNode#synodic() synodic-tagged} site
     * AND its closest non-decorative neighbour (the "adjacent coloured
     * space" per H6).
     */
    public Season synodicGate(MapNode node) {
        return synodicGates.get(node);
    }

    // -------------------------------------------------------------------------
    // Node access
    // -------------------------------------------------------------------------

    /** All nodes in the map, keyed by their string id. */
    public Map<String, MapNode> nodesById() {
        return nodesById;
    }

    /** All nodes as an unordered collection. */
    public Collection<MapNode> allNodes() {
        return nodesById.values();
    }

    /** Looks up a node by its raw string id. Returns null if not found. */
    public MapNode nodeById(String id) {
        return nodesById.get(id);
    }

    /** All site nodes (named destinations). */
    public List<MapNode> allSites() {
        return nodesById.values().stream()
                .filter(MapNode::isSite)
                .toList();
    }

    /** Finds a site by its display name (case-insensitive). Returns empty if not found. */
    public Optional<MapNode> siteByName(String name) {
        return nodesById.values().stream()
                .filter(n -> n.isSite() && n.siteData().name().equalsIgnoreCase(name))
                .findFirst();
    }

    // -------------------------------------------------------------------------
    // Adjacency
    // -------------------------------------------------------------------------

    /**
     * All neighbours of a node. Never returns null (returns empty list for
     * isolated nodes).
     */
    public List<MapNode> neighboursOf(MapNode node) {
        return adjacency.getOrDefault(node, Collections.emptyList());
    }

    /** Convenience overload accepting a node id. */
    public List<MapNode> neighboursOf(String nodeId) {
        MapNode node = nodesById.get(nodeId);
        return node == null ? Collections.emptyList() : neighboursOf(node);
    }

    public boolean hasEdge(MapNode a, MapNode b) {
        Set<MapNode> neighbours = adjacencySet.getOrDefault(a, Collections.emptySet());
        return neighbours.contains(b);
    }

    // -------------------------------------------------------------------------
    // Edge labels (Hohmann direction encoding)
    // -------------------------------------------------------------------------

    /**
     * Returns the direction label on the edge from {@code from} toward {@code to},
     * or {@code null} if no label is set (i.e. the edge is undirected / label-free).
     */
    public String edgeLabel(MapNode from, MapNode to) {
        Map<MapNode, String> targets = edgeLabels.get(from);
        return targets == null ? null : targets.get(to);
    }

    /** True if the edge from→to carries the label "0" (one-way blocker in original data). */
    public boolean isOneWayBlocked(MapNode from, MapNode to) {
        return "0".equals(edgeLabel(from, to));
    }

    // -------------------------------------------------------------------------
    // Statistics (useful for debugging / tests)
    // -------------------------------------------------------------------------

    public int nodeCount() { return nodesById.size(); }

    public int edgeCount() {
        // Each undirected edge is stored in both directions; divide by 2.
        return adjacency.values().stream().mapToInt(List::size).sum() / 2;
    }

    @Override
    public String toString() {
        return "SolarMap{nodes=" + nodeCount() + ", edges=" + edgeCount() + "}";
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<String, MapNode>               nodesById    = new HashMap<>();
        private final Map<MapNode, List<MapNode>>        adjacency    = new HashMap<>();
        // Parallel set structure for O(1) edge existence checks during build
        private final Map<MapNode, Set<MapNode>>         adjacencySet = new HashMap<>();
        private final Map<MapNode, Map<MapNode, String>> edgeLabels   = new HashMap<>();

        private Builder() {}

        public Builder addNode(MapNode node) {
            nodesById.put(node.id(), node);
            adjacency.computeIfAbsent(node, k -> new ArrayList<>());
            adjacencySet.computeIfAbsent(node, k -> new HashSet<>());
            return this;
        }

        /**
         * Adds an undirected edge between a and b.
         * Both nodes must have been added via {@link #addNode} first.
         */
        public Builder addEdge(MapNode a, MapNode b) {
            requireKnown(a);
            requireKnown(b);
            adjacency.get(a).add(b);
            adjacency.get(b).add(a);
            adjacencySet.get(a).add(b);
            adjacencySet.get(b).add(a);
            return this;
        }

        /**
         * Sets a direction label on the directed half-edge from → to.
         * Labels encode which "lane" a Hohmann intersection belongs to ("1" or "2").
         */
        public Builder setEdgeLabel(MapNode from, MapNode to, String label) {
            requireKnown(from);
            requireKnown(to);
            if (!adjacencySet.get(from).contains(to)) {
                throw new IllegalArgumentException(
                    "Cannot label a non-existent edge: " + from.id() + " → " + to.id());
            }
            edgeLabels.computeIfAbsent(from, k -> new HashMap<>()).put(to, label);
            return this;
        }

        public SolarMap build() {
            return new SolarMap(this);
        }

        private void requireKnown(MapNode node) {
            if (!nodesById.containsKey(node.id())) {
                throw new IllegalArgumentException("Unknown node: " + node.id() +
                    " — call addNode() before addEdge() or setEdgeLabel().");
            }
        }
    }
}
