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
    private final boolean disableVenusFlyby;

    private static final int MAX_TURNS = 24;
    private static final int MAX_ITERATIONS = 5_000_000;

    /** Canonical ordering for the priority queue and the per-node Pareto frontier. */
    private static final Comparator<SearchState> BY_COST =
            Comparator.comparingInt((SearchState s) -> s.fuelSpent)
                      .thenComparingInt(s -> s.hazards)
                      .thenComparingInt(s -> s.turn);

    private Pathfinder(SolarMap map, List<EngineSpec> engines, int maxFuel, boolean disableVenusFlyby) {
        this.map = map;
        this.engines = engines;
        this.maxFuel = maxFuel;
        this.disableVenusFlyby = disableVenusFlyby;
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    public static TraverseResponse traverse(SolarMap map, TraverseRequest request) {
        MapNode start = map.nodeById(request.startNodeId());
        if (start == null) {
            return error(request.startNodeId(), "unknown node: " + request.startNodeId());
        }
        if (request.engines() == null || request.engines().isEmpty()) {
            return error(request.startNodeId(), "at least one engine required");
        }
        return new Pathfinder(map, request.engines(), request.fuel(),
                request.disableVenusFlyby()).run(start);
    }

    private static TraverseResponse error(String startId, String message) {
        return new TraverseResponse(startId, null, Map.of(), message);
    }

    // -------------------------------------------------------------------------
    // Algorithm phases
    // -------------------------------------------------------------------------

    private TraverseResponse run(MapNode start) {
        Map<String, List<SearchState>> bestFound = search(start);
        Map<String, List<SearchState>> endpoints = finalPrune(bestFound);
        return buildResponse(start.id(), endpoints);
    }

    /** Phase 1 — Pareto-optimal BFS. */
    private Map<String, List<SearchState>> search(MapNode start) {
        Map<String, List<SearchState>> bestFound = new HashMap<>();
        PriorityQueue<SearchState> queue = new PriorityQueue<>(BY_COST);

        // Seed: one initial state per engine (simulates waitTurn from prequel)
        for (int i = 0; i < engines.size(); i++) {
            EngineSpec engine = engines.get(i);
            int thrust = effectiveThrustAt(i, start);
            int burns  = Math.max(thrust, 0);
            SearchState initial = new SearchState(
                    start, null, i,
                    burns, engine.bonusPivots(), 0, thrust,
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
            if (!isStillBest(current, bestFound)) continue;

            // Expand to graph neighbors
            for (Neighbor neighbor : getNeighbors(current)) {
                for (SearchState next : expandToNeighbor(current, neighbor)) {
                    if (!isAllowed(current, next)) continue;
                    if (next.fuelSpent > maxFuel || next.turn > MAX_TURNS) continue;
                    if (addIfBest(next, bestFound)) queue.add(next);
                }
            }

            // Wait-turn option (available when mid-turn)
            if (isMidTurn(current)) {
                for (SearchState wait : waitTurn(current)) {
                    if (!isAllowed(current, wait)) continue;
                    if (wait.turn > MAX_TURNS) continue;
                    if (addIfBest(wait, bestFound)) queue.add(wait);
                }
            }
        }
        return bestFound;
    }

    /**
     * Phase 2 — prune to the 4 output dimensions at every non-decorative node,
     * then collapse states with identical output-cost vectors to the shortest
     * path so the UI shows one route per (cost, destination).
     */
    private Map<String, List<SearchState>> finalPrune(Map<String, List<SearchState>> bestFound) {
        Map<String, List<SearchState>> endpoints = new LinkedHashMap<>();
        for (var entry : bestFound.entrySet()) {
            MapNode node = map.nodeById(entry.getKey());
            if (node == null || node.isDecorative()) continue;

            List<SearchState> survivors = paretoOnOutputCosts(entry.getValue());
            List<SearchState> deduped   = keepShortestPerCostVector(survivors);
            if (!deduped.isEmpty()) endpoints.put(entry.getKey(), deduped);
        }
        return endpoints;
    }

    /** Keep only states not strictly dominated on (fuel, turn, hazards, radRoll). */
    private static List<SearchState> paretoOnOutputCosts(List<SearchState> states) {
        List<SearchState> result = new ArrayList<>();
        for (SearchState s : states) {
            boolean dominated = false;
            for (SearchState other : states) {
                if (other != s && outputDominates(other, s)) { dominated = true; break; }
            }
            if (!dominated) result.add(s);
        }
        return result;
    }

    /** Strict Pareto dominance on the 4 output cost dimensions. */
    private static boolean outputDominates(SearchState a, SearchState b) {
        boolean le = a.fuelSpent <= b.fuelSpent && a.turn <= b.turn
                  && a.hazards   <= b.hazards   && a.worstRadRoll <= b.worstRadRoll;
        boolean lt = a.fuelSpent <  b.fuelSpent || a.turn <  b.turn
                  || a.hazards   <  b.hazards   || a.worstRadRoll <  b.worstRadRoll;
        return le && lt;
    }

    /**
     * Collapses states with identical output-cost vectors, keeping the one with
     * the fewest visited nodes (shortest path) as a deterministic tiebreaker.
     */
    private static List<SearchState> keepShortestPerCostVector(List<SearchState> states) {
        Map<String, SearchState> bestPerCost = new LinkedHashMap<>();
        for (SearchState s : states) {
            String key = s.fuelSpent + ":" + s.turn + ":" + s.hazards + ":" + s.worstRadRoll;
            SearchState existing = bestPerCost.get(key);
            if (existing == null || s.visitedNodes < existing.visitedNodes) {
                bestPerCost.put(key, s);
            }
        }
        return new ArrayList<>(bestPerCost.values());
    }

    /** Phase 3 — Build the PathNode tree and endpoint index from parent pointers. */
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

        // Root: merged representation of all initial states (all share the same output costs).
        // engineIndex = -1 marks "no engine in use yet" — used at the starting node before any move.
        int nextId = 0;
        PathNode root = new PathNode(nextId++, startNodeId, 0, 1, 0, 0, -1);

        Map<SearchState, PathNode> stateToNode = new IdentityHashMap<>();
        Deque<SearchState> buildQueue = new ArrayDeque<>();
        for (SearchState s : onPath) {
            if (s.parent == null) {
                stateToNode.put(s, root);
                buildQueue.add(s);
            }
        }

        while (!buildQueue.isEmpty()) {
            SearchState current = buildQueue.poll();
            PathNode currentPN = stateToNode.get(current);
            for (SearchState child : childrenMap.getOrDefault(current, List.of())) {
                PathNode childPN = new PathNode(nextId++, child.node.id(),
                        child.fuelSpent, child.turn, child.hazards, child.worstRadRoll,
                        child.engineIndex);
                currentPN.addChild(childPN);
                stateToNode.put(child, childPN);
                buildQueue.add(child);
            }
        }

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
        MapNode node = current.node;
        String dir = current.entryLabel;
        List<MapNode> adjList = map.neighboursOf(node);
        int n = adjList.size();

        // Single pass: cache both directed labels per neighbor so Parts 1 & 2
        // below don't re-hit the edgeLabel map three times per adjacency.
        String[] labelFromNode = new String[n];
        String[] labelAtAdj    = new String[n];
        boolean nodeHasLabels  = false;
        for (int i = 0; i < n; i++) {
            MapNode adj = adjList.get(i);
            labelFromNode[i] = map.edgeLabel(node, adj);
            labelAtAdj[i]    = map.edgeLabel(adj, node);
            if (labelFromNode[i] != null) nodeHasLabels = true;
        }

        List<Neighbor> neighbors = new ArrayList<>();

        // Part 1: Same-node direction changes (Hohmann pivots).
        //   At a Hohmann node, we can switch to any other direction label present.
        Set<String> seenLabels = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String label = labelFromNode[i];
            if (label != null && !label.equals("0") && !label.equals(dir)
                    && seenLabels.add(label)) {
                neighbors.add(new Neighbor(node, label));
            }
        }

        // Part 2: Cross-node moves.
        for (int i = 0; i < n; i++) {
            // One-way block: if the edge at adj pointing toward us is "0", skip
            String labelAtAdjI = labelAtAdj[i];
            if ("0".equals(labelAtAdjI)) continue;

            // Direction compatibility:
            //   Allow if: node has no labels, OR no label on this edge, OR label matches dir
            String labelFromNodeI = labelFromNode[i];
            if (nodeHasLabels && labelFromNodeI != null && dir != null
                    && !labelFromNodeI.equals(dir)) {
                continue;
            }

            MapNode adj = adjList.get(i);
            // Arrival direction at adj = the label at adj pointing toward node
            String arrivalDir = labelAtAdjI;

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
     * Expands the current state toward a single neighbor, dispatching on the
     * edge kind (burn / same-node turn / cruise).
     */
    private List<SearchState> expandToNeighbor(SearchState current, Neighbor neighbor) {
        MapNode dest = neighbor.node;
        boolean sameNode = current.node.equals(dest);

        // User-controlled: forbid entering Venus flyby nodes.
        if (disableVenusFlyby && !sameNode
                && dest.type() == com.hf4all.planner.model.NodeType.VENUS) {
            return List.of();
        }

        // For same-node moves we preserve previousNodeId; for cross-node moves
        // we record the node we just left (enables the no-U-turn rule).
        String prevNode = sameNode ? current.previousNodeId : current.node.id();

        // Crossing a one-way edge (aerobrake-style) consumes all free burns:
        // the maneuver is passive, not a powered burn.
        boolean oneWay = !sameNode && "0".equals(map.edgeLabel(current.node, dest));

        if (!sameNode && dest.isBurn()) {
            return expandBurn(current, neighbor, prevNode, oneWay);
        }
        if (isPivot(current, neighbor, sameNode)) {
            return expandTurn(current, neighbor, prevNode);
        }
        return expandCruise(current, neighbor, prevNode, oneWay, sameNode);
    }

    private static boolean isPivot(SearchState current, Neighbor neighbor, boolean sameNode) {
        return sameNode && neighbor.node.isHohmann()
                && current.entryLabel != null && neighbor.direction != null
                && !current.entryLabel.equals(neighbor.direction);
    }

    /**
     * Radiation is applied on node entry. The rolled severity is the node's
     * raw radiation minus the ship's current thrust (floor 0, per the original
     * HF4A JS rule {@code Math.max(RADIATION - thrust, 0)}). The new
     * {@code worstRadRoll} is the running max over the path.
     */
    private static int updatedRadRoll(int currentWorst, MapNode dest, int thrust) {
        int mitigated = Math.max(dest.radiation() - thrust, 0);
        return Math.max(currentWorst, mitigated);
    }

    /** BURN edge: either a free burn (if available) or a paid burn. */
    private List<SearchState> expandBurn(SearchState current, Neighbor neighbor,
                                         String prevNode, boolean oneWay) {
        MapNode dest = neighbor.node;
        // Entering a burn space requires an operational thruster. For solar engines
        // in outer zones, effective thrust at dest can drop to ≤ 0 — no burns possible.
        int destThrust = effectiveThrustAt(current.engineIndex, dest);
        if (destThrust <= 0) return List.of();

        List<SearchState> out = new ArrayList<>(2);
        boolean isHazard  = dest.hazard();
        boolean isLanding = !dest.landing().isZero();
        int newHazards   = current.hazards + (isHazard ? 1 : 0);
        int newRadRoll   = updatedRadRoll(current.worstRadRoll, dest, destThrust);
        int fuelCost     = engines.get(current.engineIndex).fuelConsumption();
        int visitedInc   = dest.isDecorative() ? 0 : 1;

        // Option A: free burn (not allowed on a landing approach)
        if (current.freeBurns > 0 && !isLanding) {
            out.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    current.burnsRemaining, current.pivotsRemaining,
                    oneWay ? 0 : current.freeBurns - 1, destThrust,
                    current.fuelSpent, current.turn, newHazards, newRadRoll,
                    current.visitedNodes + visitedInc, prevNode, current,
                    current.bonusSites));
        }
        // Option B: paid burn
        if (current.burnsRemaining > 0) {
            out.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    current.burnsRemaining - 1, current.pivotsRemaining,
                    oneWay ? 0 : current.freeBurns, destThrust,
                    current.fuelSpent + fuelCost, current.turn, newHazards, newRadRoll,
                    current.visitedNodes + visitedInc, prevNode, current,
                    current.bonusSites));
        }
        return out;
    }

    /** Same-node direction change at a Hohmann intersection: pivot or 2-burn force-turn. */
    private List<SearchState> expandTurn(SearchState current, Neighbor neighbor, String prevNode) {
        List<SearchState> out = new ArrayList<>(2);
        MapNode dest = neighbor.node;

        // Option A: use a pivot
        if (current.pivotsRemaining > 0) {
            out.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    current.burnsRemaining, current.pivotsRemaining - 1,
                    current.freeBurns, current.thrust,
                    current.fuelSpent, current.turn, current.hazards, current.worstRadRoll,
                    current.visitedNodes, prevNode, current,
                    current.bonusSites));
        }
        // Option B: force-turn via 2 paid burns (requires an operational thruster)
        if (current.burnsRemaining > 1 && current.thrust > 0) {
            int fuelCost = engines.get(current.engineIndex).fuelConsumption() * 2;
            out.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    current.burnsRemaining - 2, current.pivotsRemaining,
                    current.freeBurns, current.thrust,
                    current.fuelSpent + fuelCost, current.turn, current.hazards, current.worstRadRoll,
                    current.visitedNodes, prevNode, current,
                    current.bonusSites));
        }
        return out;
    }

    /** Free passage: Lagrange, flyby, radhaz, site, decorative, etc. */
    private List<SearchState> expandCruise(SearchState current, Neighbor neighbor,
                                           String prevNode, boolean oneWay, boolean sameNode) {
        MapNode dest = neighbor.node;
        // For cross-node moves, recompute effective thrust at the destination
        // (solar engines change thrust as they cross heliocentric zones).
        int newThrust    = sameNode ? current.thrust : effectiveThrustAt(current.engineIndex, dest);
        boolean isHazard = !sameNode && dest.hazard();
        int newHazards   = current.hazards + (isHazard ? 1 : 0);
        int newRadRoll   = sameNode
                ? current.worstRadRoll
                : updatedRadRoll(current.worstRadRoll, dest, newThrust);
        int visitedInc   = (!sameNode && !dest.isDecorative()) ? 1 : 0;

        int newFreeBurns = current.freeBurns;
        List<String> newBonusSites = new ArrayList<>(current.bonusSites);
        if (!sameNode && dest.isFlyby()) {
            newFreeBurns += dest.flybyBoost();
            newBonusSites.add(dest.id());
        }
        // One-way edge zeroes out free burns (applies after flyby boost too).
        if (oneWay) newFreeBurns = 0;

        return List.of(new SearchState(
                dest, neighbor.direction, current.engineIndex,
                current.burnsRemaining, current.pivotsRemaining,
                newFreeBurns, newThrust,
                current.fuelSpent, current.turn, newHazards, newRadRoll,
                current.visitedNodes + visitedInc,
                prevNode, current, newBonusSites));
    }

    /**
     * End the current turn: increment turn counter, reset per-turn resources,
     * optionally switch engine. Produces one state per available engine.
     */
    private List<SearchState> waitTurn(SearchState current) {
        List<SearchState> results = new ArrayList<>(engines.size());
        for (int i = 0; i < engines.size(); i++) {
            EngineSpec engine = engines.get(i);
            int thrust = effectiveThrustAt(i, current.node);
            int burns  = Math.max(thrust, 0);
            results.add(new SearchState(
                    current.node, null, i,
                    burns, engine.bonusPivots(), 0, thrust,
                    current.fuelSpent, current.turn + 1,
                    current.hazards, current.worstRadRoll,
                    current.visitedNodes, null, current, List.of()));
        }
        return results;
    }

    /**
     * Effective net thrust for an engine at a given node. For solar-powered
     * engines, adds the node's heliocentric-zone modifier (can go negative →
     * engine is non-operational there; ship can coast but cannot burn or
     * force-turn). For non-solar engines, just returns the engine's base thrust.
     */
    private int effectiveThrustAt(int engineIndex, MapNode node) {
        EngineSpec engine = engines.get(engineIndex);
        int base = engine.netThrust();
        return engine.solarPowered() ? base + node.solarMod() : base;
    }

    /** True while the state is a mid-turn position (reached by burn/turn/cruise, not waitTurn). */
    private static boolean isMidTurn(SearchState s) {
        return s.parent != null && s.turn == s.parent.turn;
    }

    // -------------------------------------------------------------------------
    // Transition restrictions — movement rules that can't be expressed by the
    // edge-cost model alone. Each rule is its own predicate for readability.
    // -------------------------------------------------------------------------

    /** Composite gate: every rule must permit the transition for it to be allowed. */
    private boolean isAllowed(SearchState from, SearchState to) {
        // Flyby re-entry applies unconditionally (same-node or cross-node).
        if (isFlybyReentry(from, to)) return false;

        boolean sameNode = from.node.equals(to.node);
        if (!sameNode) {
            if (!passesLandingBurnThrustGate(to))   return false;
            if (!passesSiteLandingRule(from, to))   return false;
            if (!passesSiteLiftoffRule(from, to))   return false;
            if (isReversingLastMove(from, to))      return false;
        } else if (from.turn != to.turn) {
            // Same-node different-turn = waitTurn.
            if (!canEndTurnHere(from))              return false;
        }
        return true;
    }

    /** A landing-burn node requires {@code thrust ≥ thrustRequired}. */
    private static boolean passesLandingBurnThrustGate(SearchState to) {
        if (to.node.landing().isZero()) return true;
        int required = to.node.thrustRequired();
        return required <= 0 || to.thrust >= required;
    }

    /**
     * H6a / H6b: powered landing on a site requires {@code thrust > siteSize};
     * aerobrake landings bypass this gate when {@code siteSize ≥ 6}. Aerobrake
     * entry takes one of two forms in the HF4A data:
     * <ol>
     *   <li>dec-chain entry: the "0" one-way label sits upstream, so by the
     *       time we reach the site the source node is a decorative.</li>
     *   <li>direct entry: the "0" one-way label sits on the final edge
     *       leading into the site (e.g. haz-lagrange → Mars: north pole).</li>
     * </ol>
     */
    private boolean passesSiteLandingRule(SearchState from, SearchState to) {
        if (!to.node.isSite()) return true;
        int required = to.node.thrustRequired();
        if (required <= 0 || to.thrust > required) return true;

        boolean viaDecChain = from.node.isDecorative();
        boolean viaOneWayIn = "0".equals(map.edgeLabel(from.node, to.node));
        return (viaDecChain || viaOneWayIn) && required >= 6;
    }

    /** H6a: liftoff from a site requires {@code thrust > siteSize}. */
    private static boolean passesSiteLiftoffRule(SearchState from, SearchState to) {
        if (!from.node.isSite()) return true;
        int required = from.node.thrustRequired();
        return required <= 0 || to.thrust > required;
    }

    /** Cannot end a turn on a landing-burn node or a decorative (mid-edge) node. */
    private static boolean canEndTurnHere(SearchState at) {
        return at.node.landing().isZero() && !at.node.isDecorative();
    }

    /** No-U-turn: cannot immediately return to the node we just came from. */
    private static boolean isReversingLastMove(SearchState from, SearchState to) {
        return from.previousNodeId != null
            && to.node.id().equals(from.previousNodeId);
    }

    /** Cannot re-enter a flyby node already used this turn. */
    private static boolean isFlybyReentry(SearchState from, SearchState to) {
        return from.bonusSites.contains(to.node.id());
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

        // Reject if any existing state dominates or equals the new state.
        for (SearchState e : existing) {
            if (!state.notDominatedBy(e) || state.equalState(e)) {
                return false;
            }
        }

        // Evict any existing states now dominated by the new state.
        existing.removeIf(e -> !e.notDominatedBy(state));

        existing.add(state);
        // No sort needed — the priority queue governs expansion order; this list
        // is only scanned for dominance/identity, both order-independent.
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
