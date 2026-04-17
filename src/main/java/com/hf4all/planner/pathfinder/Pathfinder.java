package com.hf4all.planner.pathfinder;

import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.server.dto.*;

import java.util.*;

/**
 * Multi-objective Pareto-optimal pathfinder for the HF4A solar map.
 *
 * <p>The search explores all reachable positions using a priority queue ordered by
 * (fuelSpent, hazards, turn). At each node the algorithm maintains a Pareto frontier
 * on 7 internal dimensions (fuel, turn, hazards, worstRadRoll, pivots, burns, freeBurns)
 * — gated by direction label and engine index. After the search, a second pass prunes
 * to the 4 output dimensions (fuelSpent, turns, hazards, worstRadRoll) at site nodes.
 *
 * <p>The result is a search tree rooted at the starting position, plus an endpoint index
 * mapping each reachable site to the tree node IDs of its Pareto-optimal arrival states.
 */
public final class Pathfinder {

    private final SolarMap map;
    private final List<EngineSpec> engines;
    private final int maxFuel;

    private static final int MAX_TURNS = 24;
    private static final int MAX_ITERATIONS = 5_000_000;

    private Pathfinder(SolarMap map, List<EngineSpec> engines, int maxFuel) {
        this.map = map;
        this.engines = engines;
        this.maxFuel = maxFuel;
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    public static TraverseResponse traverse(SolarMap map, TraverseRequest request) {
        MapNode start = map.nodeById(request.startNodeId());
        if (start == null) {
            return new TraverseResponse(request.startNodeId(), null, Map.of(),
                    "unknown node: " + request.startNodeId());
        }
        if (request.engines() == null || request.engines().isEmpty()) {
            return new TraverseResponse(request.startNodeId(), null, Map.of(),
                    "at least one engine required");
        }
        TraverseResponse result = new Pathfinder(map, request.engines(), request.fuel())
                .run(start);
        return result;
    }

    // -------------------------------------------------------------------------
    // Algorithm phases
    // -------------------------------------------------------------------------

    private TraverseResponse run(MapNode start) {
        Map<String, List<SearchState>> bestFound = search(start);
        Map<String, List<SearchState>> endpoints = finalPrune(bestFound);
        return buildResponse(start.id(), endpoints);
    }

    /**
     * Phase 1 — Pareto-optimal BFS.
     */
    private Map<String, List<SearchState>> search(MapNode start) {
        Map<String, List<SearchState>> bestFound = new HashMap<>();

        PriorityQueue<SearchState> queue = new PriorityQueue<>((a, b) -> {
            if (a.fuelSpent != b.fuelSpent) return a.fuelSpent - b.fuelSpent;
            if (a.hazards != b.hazards) return a.hazards - b.hazards;
            return a.turn - b.turn;
        });

        // Seed: one initial state per engine (simulates waitTurn from prequel)
        for (int i = 0; i < engines.size(); i++) {
            EngineSpec engine = engines.get(i);
            int thrust = engine.netThrust();
            SearchState initial = new SearchState(
                    start, null, i,
                    thrust, engine.bonusPivots(), 0, thrust,
                    0, 1, 0, 0,
                    1, null, null, List.of());
            if (addIfBest(initial, bestFound)) {
                queue.add(initial);
            }
        }

        int iterations = 0;
        while (!queue.isEmpty()) {
            if (++iterations > MAX_ITERATIONS) break;

            SearchState current = queue.poll();

            // Skip if this state was dominated since it was enqueued
            if (!isStillBest(current, bestFound)) continue;

            // --- Expand to graph neighbors ---
            for (Neighbor neighbor : getNeighbors(current)) {
                for (SearchState next : expandToNeighbor(current, neighbor)) {
                    if (!isAllowed(current, next)) continue;
                    if (next.fuelSpent > maxFuel || next.turn > MAX_TURNS) continue;
                    if (addIfBest(next, bestFound)) {
                        queue.add(next);
                    }
                }
            }

            // --- Wait-turn option (available when mid-turn) ---
            if (current.parent != null && current.turn == current.parent.turn) {
                for (SearchState wait : waitTurn(current)) {
                    if (!isAllowed(current, wait)) continue;
                    if (wait.turn > MAX_TURNS) continue;
                    if (addIfBest(wait, bestFound)) {
                        queue.add(wait);
                    }
                }
            }
        }

        return bestFound;
    }

    /**
     * Phase 2 — Final 4-dimension pruning at endpoint nodes.
     *
     * Every reachable non-decorative node is treated as an endpoint so the UI
     * can show routes to burns, lagranges, hohmann intersections, flybys,
     * radhaz nodes, etc. — not just to sites. Decorative nodes are skipped
     * because they represent mid-edge cruise points with no game effect.
     */
    private Map<String, List<SearchState>> finalPrune(Map<String, List<SearchState>> bestFound) {
        Map<String, List<SearchState>> endpoints = new LinkedHashMap<>();

        for (var entry : bestFound.entrySet()) {
            MapNode node = map.nodeById(entry.getKey());
            if (node == null || node.isDecorative()) continue;

            List<SearchState> states = entry.getValue();
            List<SearchState> pruned = new ArrayList<>();

            for (SearchState s : states) {
                boolean dominated = false;
                for (SearchState other : states) {
                    if (other == s) continue;
                    if (other.fuelSpent <= s.fuelSpent && other.turn <= s.turn
                            && other.hazards <= s.hazards && other.worstRadRoll <= s.worstRadRoll) {
                        if (other.fuelSpent < s.fuelSpent || other.turn < s.turn
                                || other.hazards < s.hazards || other.worstRadRoll < s.worstRadRoll) {
                            dominated = true;
                            break;
                        }
                    }
                }
                if (!dominated) pruned.add(s);
            }

            if (!pruned.isEmpty()) {
                // Collapse states with identical 4-dim cost vectors, keeping the
                // shortest path (fewest visited nodes) as a deterministic
                // tiebreaker. Without this, two routes to the same destination
                // with equal costs but different intermediate hops both survive.
                Map<String, SearchState> bestPerCost = new LinkedHashMap<>();
                for (SearchState s : pruned) {
                    String key = s.fuelSpent + ":" + s.turn + ":"
                            + s.hazards + ":" + s.worstRadRoll;
                    SearchState existing = bestPerCost.get(key);
                    if (existing == null || s.visitedNodes < existing.visitedNodes) {
                        bestPerCost.put(key, s);
                    }
                }
                endpoints.put(entry.getKey(), new ArrayList<>(bestPerCost.values()));
            }
        }

        return endpoints;
    }

    /**
     * Phase 3 — Build the PathNode tree and endpoint index from parent pointers.
     */
    private TraverseResponse buildResponse(String startNodeId,
                                           Map<String, List<SearchState>> endpoints) {
        // Collect all endpoint search states
        Set<SearchState> endpointStates = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<SearchState> states : endpoints.values()) {
            endpointStates.addAll(states);
        }

        // Walk parent chains to mark every state on an optimal path
        Set<SearchState> onPath = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SearchState ep : endpointStates) {
            for (SearchState s = ep; s != null && onPath.add(s); s = s.parent) {
                // walk up
            }
        }

        // Build parent → children map (identity-based)
        Map<SearchState, List<SearchState>> childrenMap = new IdentityHashMap<>();
        for (SearchState s : onPath) {
            if (s.parent != null && onPath.contains(s.parent)) {
                childrenMap.computeIfAbsent(s.parent, k -> new ArrayList<>()).add(s);
            }
        }

        // Assign integer IDs and build PathNode tree
        int[] idCounter = {0};
        // Root: merged representation of all initial states (all share the same output costs)
        PathNode root = new PathNode(idCounter[0]++, startNodeId, 0, 1, 0, 0);

        Map<SearchState, PathNode> stateToNode = new IdentityHashMap<>();

        // Map root search states (parent == null) to the shared root PathNode
        Deque<SearchState> buildQueue = new ArrayDeque<>();
        for (SearchState s : onPath) {
            if (s.parent == null) {
                stateToNode.put(s, root);
                buildQueue.add(s);
            }
        }

        // BFS to build the rest of the tree
        while (!buildQueue.isEmpty()) {
            SearchState current = buildQueue.poll();
            PathNode currentPN = stateToNode.get(current);
            for (SearchState child : childrenMap.getOrDefault(current, List.of())) {
                PathNode childPN = new PathNode(idCounter[0]++, child.node.id(),
                        child.fuelSpent, child.turn, child.hazards, child.worstRadRoll);
                currentPN.addChild(childPN);
                stateToNode.put(child, childPN);
                buildQueue.add(child);
            }
        }

        // Build endpoint index: site nodeId → list of PathNode IDs
        Map<String, List<Integer>> endpointIndex = new LinkedHashMap<>();
        for (var entry : endpoints.entrySet()) {
            List<Integer> ids = new ArrayList<>();
            for (SearchState s : entry.getValue()) {
                PathNode pn = stateToNode.get(s);
                if (pn != null) ids.add(pn.id());
            }
            if (!ids.isEmpty()) endpointIndex.put(entry.getKey(), ids);
        }

        return new TraverseResponse(startNodeId, root, endpointIndex, "ok");
    }

    // -------------------------------------------------------------------------
    // Neighbor generation
    // -------------------------------------------------------------------------

    private record Neighbor(MapNode node, String direction) {}

    /**
     * Generates logical neighbors for the current search state, following
     * Hohmann direction-label semantics.
     *
     * Two kinds of transitions:
     * <ol>
     *   <li>Same-node direction change at Hohmann intersections (pivot/turn).</li>
     *   <li>Cross-node move to a physical graph neighbor.</li>
     * </ol>
     */
    private List<Neighbor> getNeighbors(SearchState current) {
        List<Neighbor> neighbors = new ArrayList<>();
        MapNode node = current.node;
        String dir = current.entryLabel;

        // Precompute: does the current node have any edge labels?
        boolean nodeHasLabels = false;
        for (MapNode adj : map.neighboursOf(node)) {
            if (map.edgeLabel(node, adj) != null) { nodeHasLabels = true; break; }
        }

        // Part 1: Same-node direction changes (Hohmann pivots)
        //   At a Hohmann node, we can switch to any other direction label present.
        Set<String> seenLabels = new HashSet<>();
        for (MapNode adj : map.neighboursOf(node)) {
            String label = map.edgeLabel(node, adj);
            if (label != null && !label.equals("0") && !label.equals(dir)
                    && seenLabels.add(label)) {
                neighbors.add(new Neighbor(node, label));
            }
        }

        // Part 2: Cross-node moves
        for (MapNode adj : map.neighboursOf(node)) {
            // One-way block: if the edge at adj pointing toward us is "0", skip
            String labelAtAdj = map.edgeLabel(adj, node);
            if ("0".equals(labelAtAdj)) continue;

            // Direction compatibility:
            //   Allow if: node has no labels, OR no label on this edge, OR label matches dir
            String labelFromNode = map.edgeLabel(node, adj);
            if (nodeHasLabels && labelFromNode != null && dir != null
                    && !labelFromNode.equals(dir)) {
                continue;
            }

            // Arrival direction at adj = the label at adj pointing toward node
            String arrivalDir = labelAtAdj; // null if adj has no labels

            // Prevent degenerate self-loop: same node, had a direction, losing it
            if (node.equals(adj) && dir != null && arrivalDir == null) continue;

            neighbors.add(new Neighbor(adj, arrivalDir));
        }

        return neighbors;
    }

    // -------------------------------------------------------------------------
    // State expansion
    // -------------------------------------------------------------------------

    /**
     * Expands the current state toward a single neighbor, producing zero or more
     * successor states depending on the edge type (burn / turn / cruise).
     */
    private List<SearchState> expandToNeighbor(SearchState current, Neighbor neighbor) {
        List<SearchState> results = new ArrayList<>(2);
        MapNode dest = neighbor.node;
        boolean sameNode = current.node.equals(dest);
        // Decorative nodes don't count toward route length
        int visitedIncrement = (!sameNode && !dest.isDecorative()) ? 1 : 0;

        // --- Classify the edge ---
        boolean isBurn = !sameNode && dest.isBurn();
        boolean isTurn = sameNode && dest.isHohmann()
                && current.entryLabel != null && neighbor.direction != null
                && !current.entryLabel.equals(neighbor.direction);
        boolean isHazard = !sameNode && dest.hazard();
        int radiation = !sameNode ? dest.radiation() : 0;
        boolean isLanding = !sameNode && !dest.landing().isZero();

        // Crossing a one-way edge (aerobrake-style) consumes all free burns:
        // the maneuver is passive, not a powered burn.
        boolean oneWay = !sameNode && "0".equals(map.edgeLabel(current.node, dest));

        int newHazards = current.hazards + (isHazard ? 1 : 0);
        int newRadRoll = Math.max(current.worstRadRoll, radiation);

        if (isBurn) {
            int fuelCost = engines.get(current.engineIndex).fuelConsumption();

            // For cross-node moves, record where we came from (no-U-turn rule)
            String prevNode = current.node.id();

            // Option A: free burn (if available and not a landing approach)
            if (current.freeBurns > 0 && !isLanding) {
                results.add(new SearchState(
                        dest, neighbor.direction, current.engineIndex,
                        current.burnsRemaining, current.pivotsRemaining,
                        oneWay ? 0 : current.freeBurns - 1, current.thrust,
                        current.fuelSpent, current.turn, newHazards, newRadRoll,
                        current.visitedNodes + visitedIncrement, prevNode, current, new ArrayList<>(current.bonusSites)));
            }

            // Option B: paid burn
            if (current.burnsRemaining > 0) {
                results.add(new SearchState(
                        dest, neighbor.direction, current.engineIndex,
                        current.burnsRemaining - 1, current.pivotsRemaining,
                        oneWay ? 0 : current.freeBurns, current.thrust,
                        current.fuelSpent + fuelCost, current.turn, newHazards, newRadRoll,
                        current.visitedNodes + visitedIncrement, prevNode, current, new ArrayList<>(current.bonusSites)));
            }

        } else if (isTurn) {
            // Same-node direction change: preserve previousNodeId
            String prevNode = current.previousNodeId;

            // Option A: use a pivot
            if (current.pivotsRemaining > 0) {
                results.add(new SearchState(
                        dest, neighbor.direction, current.engineIndex,
                        current.burnsRemaining, current.pivotsRemaining - 1,
                        current.freeBurns, current.thrust,
                        current.fuelSpent, current.turn, current.hazards, current.worstRadRoll,
                        current.visitedNodes, prevNode, current, new ArrayList<>(current.bonusSites)));
            }

            // Option B: force-turn via 2 burns
            if (current.burnsRemaining > 1) {
                int fuelCost = engines.get(current.engineIndex).fuelConsumption() * 2;
                results.add(new SearchState(
                        dest, neighbor.direction, current.engineIndex,
                        current.burnsRemaining - 2, current.pivotsRemaining,
                        current.freeBurns, current.thrust,
                        current.fuelSpent + fuelCost, current.turn, current.hazards, current.worstRadRoll,
                        current.visitedNodes, prevNode, current, new ArrayList<>(current.bonusSites)));
            }

        } else {
            // CRUISE: free passage (Lagrange, flyby, radhaz, site, etc.)
            int newFreeBurns = current.freeBurns;
            List<String> newBonusSites = new ArrayList<>(current.bonusSites);
            // Cross-node cruise: record where we came from; same-node: preserve
            String prevNode = sameNode ? current.previousNodeId : current.node.id();

            if (!sameNode && dest.isFlyby()) {
                newFreeBurns += dest.flybyBoost();
                newBonusSites.add(dest.id());
            }

            // One-way edge zeroes out free burns (applies after flyby boost too).
            if (oneWay) newFreeBurns = 0;

            results.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    current.burnsRemaining, current.pivotsRemaining,
                    newFreeBurns, current.thrust,
                    current.fuelSpent, current.turn, newHazards, newRadRoll,
                    current.visitedNodes + visitedIncrement,
                    prevNode, current, newBonusSites));
        }

        return results;
    }

    /**
     * End the current turn: increment turn counter, reset per-turn resources,
     * optionally switch engine. Produces one state per available engine.
     */
    private List<SearchState> waitTurn(SearchState current) {
        List<SearchState> results = new ArrayList<>(engines.size());
        for (int i = 0; i < engines.size(); i++) {
            EngineSpec engine = engines.get(i);
            int thrust = engine.netThrust();
            results.add(new SearchState(
                    current.node, null, i,
                    thrust, engine.bonusPivots(), 0, thrust,
                    current.fuelSpent, current.turn + 1,
                    current.hazards, current.worstRadRoll,
                    current.visitedNodes, null, current, List.of()));
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------------------

    /**
     * Movement rules that cannot be expressed by the edge cost model alone:
     * thrust requirements, landing-burn restrictions, and U-turn prevention.
     */
    private boolean isAllowed(SearchState from, SearchState to) {
        boolean sameNode = from.node.equals(to.node);

        // Landing burn thrust gate: thrust must be >= thrustRequired.
        if (!sameNode && !to.node.landing().isZero()) {
            int required = to.node.thrustRequired();
            if (required > 0 && to.thrust < required) return false;
        }

        // Site thrust gate: powered landing requires thrust > site size (H6a).
        // Aerobrake landings bypass this (H6b) for sites of size >= 6. The
        // atmospheric approach takes one of two shapes in the HF4A data:
        //   (1) dec-chain entry: the "0" one-way label sits on the edge leading
        //       INTO a decorative chain that ends at the site. By the time we
        //       reach the site, the source node is a decorative.
        //   (2) direct entry:    the "0" one-way label sits on the very edge
        //       INTO the site (e.g. haz-lagrange 0.38552 -> Mars: north pole).
        //       Source of that edge is a lagrange, not a decorative.
        // Either shape is a valid aerobrake.
        if (!sameNode && to.node.isSite()) {
            int required = to.node.thrustRequired();
            if (required > 0 && to.thrust <= required) {
                boolean viaDecChain  = from.node.isDecorative();
                boolean viaOneWayIn  = "0".equals(map.edgeLabel(from.node, to.node));
                boolean aerobrakeBypass = (viaDecChain || viaOneWayIn) && required >= 6;
                if (!aerobrakeBypass) return false;
            }
        }

        // Liftoff check: leaving a site requires thrust > site size (H6a).
        if (!sameNode && from.node.isSite()) {
            int required = from.node.thrustRequired();
            if (required > 0 && to.thrust <= required) return false;
        }

        // Cannot wait (end turn) on a landing-burn node or a decorative node
        if (sameNode && from.turn != to.turn
                && (!from.node.landing().isZero() || from.node.isDecorative())) {
            return false;
        }

        // No turning back: cannot return to the node we just came from
        if (!sameNode && from.previousNodeId != null
                && to.node.id().equals(from.previousNodeId)) return false;

        // Flyby re-entry prevention: cannot re-enter a flyby node used this turn
        if (from.bonusSites.contains(to.node.id())) return false;

        return true;
    }

    // -------------------------------------------------------------------------
    // Pareto-frontier management
    // -------------------------------------------------------------------------

    /**
     * Attempts to insert {@code state} into the Pareto frontier at its node.
     * Returns true (and updates the frontier) if the state is non-dominated.
     */
    private static boolean addIfBest(SearchState state, Map<String, List<SearchState>> bestFound) {
        String nodeId = state.node.id();
        List<SearchState> existing = bestFound.computeIfAbsent(nodeId, k -> new ArrayList<>());

        // Check if any existing state dominates or equals the new state
        for (SearchState e : existing) {
            if (!state.notDominatedBy(e) || state.equalState(e)) {
                return false;
            }
        }

        // Remove states now dominated by the new state
        existing.removeIf(e -> !e.notDominatedBy(state));

        existing.add(state);

        // Keep sorted for deterministic expansion order
        existing.sort((a, b) -> {
            if (a.fuelSpent != b.fuelSpent) return a.fuelSpent - b.fuelSpent;
            if (a.hazards != b.hazards) return a.hazards - b.hazards;
            return a.turn - b.turn;
        });

        return true;
    }

    /**
     * Returns true if the state is still present in the Pareto frontier
     * (it may have been evicted by a state enqueued after it).
     */
    private static boolean isStillBest(SearchState state, Map<String, List<SearchState>> bestFound) {
        List<SearchState> best = bestFound.get(state.node.id());
        if (best == null) return false;
        for (SearchState s : best) {
            if (s == state) return true; // identity check
        }
        return false;
    }
}
