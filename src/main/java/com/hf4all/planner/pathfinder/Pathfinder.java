package com.hf4all.planner.pathfinder;

import com.hf4all.planner.config.Config;
import com.hf4all.planner.model.Fraction;
import com.hf4all.planner.model.FuelStrip;
import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.server.dto.*;

import java.util.*;

/**
 * Multi-objective Pareto-optimal pathfinder for the HF4A solar map.
 *
 * <p>Mass-aware variant: the search tracks Wet Mass on the (non-linear)
 * fuel strip. Net thrust is recomputed at the start of each movement
 * (HF4A H3) using the engine's base thrust + the current weight-class
 * modifier (H3c) + the heliocentric solar modifier (H3c). Fractional
 * fuel consumption (H5b) accumulates across burns within a movement and
 * is rounded up at end-of-movement. With {@code allowFuelJettison}, the
 * player may dump fuel at the start of any turn to drop into a lighter
 * weight class — branched in the search only at amounts that actually
 * change the class (intermediate dumps are strictly Pareto-dominated).
 *
 * <p>The result is a search tree rooted at the starting position, plus
 * an endpoint index mapping each reachable site to the tree node IDs of
 * its Pareto-optimal arrival states.
 */
public final class Pathfinder {

    private final SolarMap map;
    private final List<EngineSpec> engines;
    private final int dryMass;
    private final int initialFuelSteps;
    private final boolean disableVenusFlyby;
    private final boolean allowFuelJettison;

    // Search-scoped mutable state — initialised in run(), shared with the
    // lazy-jettison helpers so they can enqueue alternatives without
    // threading the queue + frontier map through every call.
    //
    // Phase 2: frontier is keyed by the full comparability context
    // (nodeId, engineIndex, entryLabel, previousNodeId) so every bucket
    // contains only states that are directly comparable via notDominatedBy.
    // Pre-Phase-2 profiling showed the single-key-by-nodeId bucket grew to
    // avg ~676 entries / max 4882 with ~70% of notDominatedBy calls
    // returning immediately on context mismatch — 2.5B wasted compares per
    // search. The compound key eliminates that entire class of work.
    private Map<FrontierKey, List<SearchState>> bestFound;
    private PriorityQueue<SearchState> queue;

    /** Frontier-bucket key: groups states that are directly comparable by
     *  the {@link SearchState#notDominatedBy} context gates. */
    private record FrontierKey(String nodeId, int engineIndex,
                               String entryLabel, String previousNodeId) {}

    private static FrontierKey keyOf(SearchState s) {
        return new FrontierKey(s.node.id(), s.engineIndex, s.entryLabel, s.previousNodeId);
    }

    // --- Diagnostic counters (Phase-plan profiling pass) ---------------------
    // Reset in run(); printed by run() at the end. Single-threaded per search.
    private long statEnqueued, statPolled, statStalePolls;
    private long statAddIfBestCalls, statAddIfBestAccepted, statAddIfBestRejected;
    private long statEvictions, statNotDominatedByCalls;
    private long statJettisonTriggers, statJettisonSpawns;
    private long statIsStillBestCalls;

    private static final int MAX_TURNS = Config.searchMaxTurns();
    private static final int MAX_ITERATIONS = Config.searchMaxIterations();

    /** Canonical ordering for the priority queue: more remaining fuel first
     *  (cheaper plans), then fewer hazards, then earlier turns. */
    private static final Comparator<SearchState> BY_COST =
            Comparator.comparingInt((SearchState s) -> -s.fuelStepsRemaining)
                      .thenComparingInt(s -> s.hazards)
                      .thenComparingInt(s -> s.turn);

    private Pathfinder(SolarMap map, List<EngineSpec> engines,
                       int dryMass, int initialFuelSteps,
                       boolean disableVenusFlyby, boolean allowFuelJettison) {
        this.map = map;
        this.engines = engines;
        this.dryMass = dryMass;
        this.initialFuelSteps = initialFuelSteps;
        this.disableVenusFlyby = disableVenusFlyby;
        this.allowFuelJettison = allowFuelJettison;
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
        int initialSteps;
        try {
            initialSteps = FuelStrip.initialFuelSteps(request.dryMass(), request.fuel());
        } catch (IllegalArgumentException e) {
            return error(request.startNodeId(), e.getMessage());
        }
        return new Pathfinder(map, request.engines(),
                request.dryMass(), initialSteps,
                request.disableVenusFlyby(), request.allowFuelJettison()).run(start);
    }

    private static TraverseResponse error(String startId, String message) {
        return new TraverseResponse(startId, null, Map.of(), message);
    }

    // -------------------------------------------------------------------------
    // Algorithm phases
    // -------------------------------------------------------------------------

    private TraverseResponse run(MapNode start) {
        bestFound = new HashMap<>();
        queue = new PriorityQueue<>(BY_COST);

        long t0 = System.nanoTime();
        Map<FrontierKey, List<SearchState>> result = search(start);
        long t1 = System.nanoTime();
        Map<String, List<SearchState>> endpoints = finalPrune(result);
        long t2 = System.nanoTime();
        TraverseResponse response = buildResponse(start.id(), endpoints);
        long t3 = System.nanoTime();

        printStats(t1 - t0, t2 - t1, t3 - t2);
        return response;
    }

    /** One-shot diagnostic summary. Only prints on measured runs (the second
     *  call per JVM); skips warmups to keep the bench output clean. */
    private static int runCount = 0;
    private void printStats(long searchNs, long finalPruneNs, long buildNs) {
        runCount++;
        if (runCount < 2) return; // warmup

        // bestFound bucket-size histogram (post-Phase-2: one bucket per
        // (nodeId, engineIndex, entryLabel, previousNodeId) context).
        int bucketCount = bestFound.size();
        long totalEntries = 0;
        int maxBucket = 0;
        int[] sizes = new int[bucketCount];
        int idx = 0;
        Set<String> distinctNodes = new HashSet<>();
        for (var entry : bestFound.entrySet()) {
            int sz = entry.getValue().size();
            sizes[idx++] = sz;
            totalEntries += sz;
            if (sz > maxBucket) maxBucket = sz;
            distinctNodes.add(entry.getKey().nodeId());
        }
        java.util.Arrays.sort(sizes);
        int p50 = bucketCount == 0 ? 0 : sizes[bucketCount / 2];
        int p90 = bucketCount == 0 ? 0 : sizes[Math.min(bucketCount - 1, (bucketCount * 90) / 100)];
        int p99 = bucketCount == 0 ? 0 : sizes[Math.min(bucketCount - 1, (bucketCount * 99) / 100)];

        long totalNs = searchNs + finalPruneNs + buildNs;
        System.err.println("[PF-STATS]");
        System.err.printf("  phase wall-times:%n");
        System.err.printf("    search        = %,d ms (%.1f%%)%n",
                searchNs / 1_000_000, 100.0 * searchNs / totalNs);
        System.err.printf("    finalPrune    = %,d ms (%.1f%%)%n",
                finalPruneNs / 1_000_000, 100.0 * finalPruneNs / totalNs);
        System.err.printf("    buildResponse = %,d ms (%.1f%%)%n",
                buildNs / 1_000_000, 100.0 * buildNs / totalNs);
        System.err.printf("  queue:%n");
        System.err.printf("    enqueued      = %,d%n", statEnqueued);
        System.err.printf("    polled        = %,d%n", statPolled);
        System.err.printf("    stale polls   = %,d (%.1f%% of polls)%n",
                statStalePolls, statPolled == 0 ? 0 : 100.0 * statStalePolls / statPolled);
        System.err.printf("  addIfBest:%n");
        System.err.printf("    calls         = %,d%n", statAddIfBestCalls);
        System.err.printf("    accepted      = %,d (%.1f%%)%n",
                statAddIfBestAccepted,
                statAddIfBestCalls == 0 ? 0 : 100.0 * statAddIfBestAccepted / statAddIfBestCalls);
        System.err.printf("    rejected      = %,d (%.1f%%)%n",
                statAddIfBestRejected,
                statAddIfBestCalls == 0 ? 0 : 100.0 * statAddIfBestRejected / statAddIfBestCalls);
        System.err.printf("    evictions     = %,d%n", statEvictions);
        System.err.printf("  dominance:%n");
        System.err.printf("    notDominatedBy calls = %,d%n", statNotDominatedByCalls);
        System.err.printf("    per-accepted state   = %.1f%n",
                statAddIfBestAccepted == 0 ? 0.0
                        : (double) statNotDominatedByCalls / statAddIfBestAccepted);
        System.err.printf("  jettison:%n");
        System.err.printf("    triggers      = %,d%n", statJettisonTriggers);
        System.err.printf("    spawns        = %,d (%.1f%%)%n",
                statJettisonSpawns,
                statJettisonTriggers == 0 ? 0 : 100.0 * statJettisonSpawns / statJettisonTriggers);
        System.err.printf("  bestFound buckets:%n");
        System.err.printf("    distinct nodes   = %,d%n", distinctNodes.size());
        System.err.printf("    context buckets  = %,d%n", bucketCount);
        System.err.printf("    total entries    = %,d (avg %.2f per bucket)%n",
                totalEntries, bucketCount == 0 ? 0.0 : (double) totalEntries / bucketCount);
        System.err.printf("    bucket size max / p99 / p90 / p50 = %d / %d / %d / %d%n",
                maxBucket, p99, p90, p50);
    }

    /** Phase 1 — Pareto-optimal BFS. */
    private Map<FrontierKey, List<SearchState>> search(MapNode start) {

        // Seed: one initial state per engine. Weight class derived from the
        // user-supplied (dryMass, fuel); jettison at turn 1 is NOT a branch
        // — the player committed to the starting load by entering it.
        for (int i = 0; i < engines.size(); i++) {
            EngineSpec engine = engines.get(i);
            int thrust = effectiveThrust(i, start, initialFuelSteps);
            int burns  = Math.max(thrust, 0);
            SearchState initial = new SearchState(
                    start, null, i,
                    burns, engine.bonusPivots(), 0, thrust,
                    initialFuelSteps, Fraction.ZERO, 0,
                    1, 0, 0,
                    1, null, null, List.of(),
                    null /* this IS a turn-start */);
            if (addIfBest(initial, bestFound)) {
                queue.add(initial); statEnqueued++;
            }
        }

        int iterations = 0;
        while (!queue.isEmpty()) {
            if (++iterations > MAX_ITERATIONS) break;

            SearchState current = queue.poll(); statPolled++;
            if (!isStillBest(current, bestFound)) { statStalePolls++; continue; }

            // Expand to graph neighbors
            for (Neighbor neighbor : getNeighbors(current)) {
                for (SearchState next : expandToNeighbor(current, neighbor)) {
                    if (!isAllowed(current, next)) {
                        // Lazy-jettison trigger: if this transition was
                        // blocked by a thrust gate, see if a jettison alt
                        // at the current turn-start would have unblocked it.
                        int req = requiredThrustForTransition(current.node, next.node);
                        if (req > current.thrust) {
                            maybeSpawnJettisonAlt(current);
                        }
                        continue;
                    }
                    if (next.turn > MAX_TURNS) continue;
                    if (addIfBest(next, bestFound)) { queue.add(next); statEnqueued++; }
                }
            }

            // Wait-turn option (available when mid-turn)
            if (isMidTurn(current)) {
                for (SearchState wait : waitTurn(current)) {
                    if (!isAllowed(current, wait)) continue;
                    if (wait.turn > MAX_TURNS) continue;
                    if (addIfBest(wait, bestFound)) { queue.add(wait); statEnqueued++; }
                }
            }
        }
        return bestFound;
    }

    /**
     * Phase 2 — prune to the output dimensions at every non-decorative node,
     * then collapse states with identical output-cost vectors to the shortest
     * path so the UI shows one route per (cost, destination).
     *
     * <p>Since the search frontier is keyed by {@link FrontierKey} (full
     * comparability context), all buckets for the same nodeId must be
     * flattened together before the per-node output Pareto step.
     */
    private Map<String, List<SearchState>> finalPrune(Map<FrontierKey, List<SearchState>> bestFound) {
        // Collapse context-keyed buckets to a per-nodeId list.
        Map<String, List<SearchState>> byNode = new LinkedHashMap<>();
        for (var entry : bestFound.entrySet()) {
            String nodeId = entry.getKey().nodeId();
            MapNode node = map.nodeById(nodeId);
            if (node == null || node.isDecorative()) continue;
            byNode.computeIfAbsent(nodeId, k -> new ArrayList<>()).addAll(entry.getValue());
        }

        Map<String, List<SearchState>> endpoints = new LinkedHashMap<>();
        for (var entry : byNode.entrySet()) {
            List<SearchState> survivors = paretoOnOutputCosts(entry.getValue());
            List<SearchState> deduped   = keepShortestPerCostVector(survivors);
            if (!deduped.isEmpty()) endpoints.put(entry.getKey(), deduped);
        }
        return endpoints;
    }

    /** Keep only states not strictly dominated on (fuelRemaining, turn, hazards, radRoll). */
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

    /** Strict Pareto dominance on the 4 output cost dimensions. Higher
     *  effective fuel remaining is better (cheaper); lower turns/hazards/
     *  radRoll are better. */
    private static boolean outputDominates(SearchState a, SearchState b) {
        int aFuel = a.effectiveFuelStepsRemaining();
        int bFuel = b.effectiveFuelStepsRemaining();
        boolean le = aFuel >= bFuel && a.turn <= b.turn
                  && a.hazards <= b.hazards && a.worstRadRoll <= b.worstRadRoll;
        boolean lt = aFuel >  bFuel || a.turn <  b.turn
                  || a.hazards <  b.hazards || a.worstRadRoll <  b.worstRadRoll;
        return le && lt;
    }

    /**
     * Collapses states with identical output-cost vectors, keeping the one with
     * the fewest visited nodes (shortest path) as a deterministic tiebreaker.
     */
    private static List<SearchState> keepShortestPerCostVector(List<SearchState> states) {
        Map<String, SearchState> bestPerCost = new LinkedHashMap<>();
        for (SearchState s : states) {
            String key = s.effectiveFuelStepsRemaining() + ":" + s.turn
                       + ":" + s.hazards + ":" + s.worstRadRoll;
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
        int rootWetMass = FuelStrip.wetMassAt(dryMass, initialFuelSteps);
        PathNode root = new PathNode(nextId++, startNodeId,
                initialFuelSteps, /* fuelSpent = */ 0,
                /* remainNum */ initialFuelSteps, /* remainDen */ 1,
                /* spentNum */  0, /* spentDen */  1,
                rootWetMass, 0,
                1, 0, 0, -1);

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
                int eff = child.effectiveFuelStepsRemaining();
                int wm  = FuelStrip.wetMassAt(dryMass, eff);
                int spent = initialFuelSteps - eff;

                // Exact rational remaining / spent, preserving fractional
                // partial-consumption info before H5b end-of-move rounding.
                // remaining = fuelStepsRemaining - partialStepsThisMove
                // spent     = initialFuelSteps - remaining
                Fraction remainFrac = Fraction.of(child.fuelStepsRemaining)
                        .subtract(child.partialStepsThisMove);
                Fraction spentFrac  = Fraction.of(initialFuelSteps)
                        .subtract(remainFrac);

                // jettisonedAtTurnStart is inherited by every mid-turn
                // descendant. Report it on the PathNode ONLY at the actual
                // turn-start (where the event occurred) so the UI doesn't
                // splash the "Jettison N" badge across the whole turn.
                boolean isTurnStart = (child.turnStart == null);
                int jettisonedOnNode = isTurnStart ? child.jettisonedAtTurnStart : 0;

                PathNode childPN = new PathNode(nextId++, child.node.id(),
                        eff, spent,
                        remainFrac.numerator(), remainFrac.denominator(),
                        spentFrac.numerator(),  spentFrac.denominator(),
                        wm, jettisonedOnNode,
                        child.turn, child.hazards, child.worstRadRoll,
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
            String labelAtAdjI = labelAtAdj[i];
            if ("0".equals(labelAtAdjI)) continue;

            String labelFromNodeI = labelFromNode[i];
            if (nodeHasLabels && labelFromNodeI != null && dir != null
                    && !labelFromNodeI.equals(dir)) {
                continue;
            }

            MapNode adj = adjList.get(i);
            String arrivalDir = labelAtAdjI;

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
        // Operational check at destination: solar engines in outer zones may
        // have effective thrust ≤ 0. Note: per H3, the thrust used FOR THE
        // BURN was snapshotted at turn start; this check just gates entry.
        int destThrust = effectiveThrust(current.engineIndex, dest, current.fuelStepsRemaining);
        if (destThrust <= 0) {
            // Solar engine non-operational at destination zone. Jettison
            // would lift wet mass → improve weight class → possibly raise
            // thrust. Trigger lazy alt.
            maybeSpawnJettisonAlt(current);
            return List.of();
        }

        List<SearchState> out = new ArrayList<>(2);
        boolean isHazard  = dest.hazard();
        boolean isLanding = !dest.landing().isZero();
        int newHazards   = current.hazards + (isHazard ? 1 : 0);
        int newRadRoll   = updatedRadRoll(current.worstRadRoll, dest, destThrust);
        Fraction burnCost = engines.get(current.engineIndex).fuelConsumption();
        int visitedInc   = dest.isDecorative() ? 0 : 1;

        Fraction newPartial = current.partialStepsThisMove.add(burnCost);
        Fraction fuelCap    = Fraction.of(current.fuelStepsRemaining);

        // Option A: free burn (not allowed on a landing approach)
        if (current.freeBurns > 0 && !isLanding) {
            out.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    current.burnsRemaining, current.pivotsRemaining,
                    oneWay ? 0 : current.freeBurns - 1, current.thrust,
                    current.fuelStepsRemaining, current.partialStepsThisMove,
                    current.jettisonedAtTurnStart,
                    current.turn, newHazards, newRadRoll,
                    current.visitedNodes + visitedInc, prevNode, current,
                    current.bonusSites,
                    current.turnStart()));
        }
        // Option B: paid burn — H5d gate: fractional partial ≤ remaining
        if (current.burnsRemaining > 0 && !newPartial.isGreaterThan(fuelCap)) {
            out.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    current.burnsRemaining - 1, current.pivotsRemaining,
                    oneWay ? 0 : current.freeBurns, current.thrust,
                    current.fuelStepsRemaining, newPartial,
                    current.jettisonedAtTurnStart,
                    current.turn, newHazards, newRadRoll,
                    current.visitedNodes + visitedInc, prevNode, current,
                    current.bonusSites,
                    current.turnStart()));
        }
        // Note: we deliberately do NOT trigger lazy jettison when we simply
        // ran out of burns this turn (burnsRemaining == 0). That fires for
        // almost every multi-burn path and the alt's extra burn rarely
        // unlocks anything the no-jet sibling couldn't reach over more
        // turns. Real thrust-failure triggers (destThrust ≤ 0 here, the
        // landing/liftoff gates in isAllowed) are more selective.
        return out;
    }

    /** Same-node direction change at a Hohmann intersection: pivot or 2-burn force-turn. */
    private List<SearchState> expandTurn(SearchState current, Neighbor neighbor, String prevNode) {
        List<SearchState> out = new ArrayList<>(2);
        MapNode dest = neighbor.node;

        // Option A: use a pivot (free, no fuel)
        if (current.pivotsRemaining > 0) {
            out.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    current.burnsRemaining, current.pivotsRemaining - 1,
                    current.freeBurns, current.thrust,
                    current.fuelStepsRemaining, current.partialStepsThisMove,
                    current.jettisonedAtTurnStart,
                    current.turn, current.hazards, current.worstRadRoll,
                    current.visitedNodes, prevNode, current,
                    current.bonusSites,
                    current.turnStart()));
        }
        // Option B: force-turn via 2 paid burns (requires an operational thruster)
        if (current.burnsRemaining > 1 && current.thrust > 0) {
            Fraction twoBurns = engines.get(current.engineIndex).fuelConsumption().multiply(2);
            Fraction newPartial = current.partialStepsThisMove.add(twoBurns);
            Fraction fuelCap = Fraction.of(current.fuelStepsRemaining);
            if (!newPartial.isGreaterThan(fuelCap)) {
                out.add(new SearchState(
                        dest, neighbor.direction, current.engineIndex,
                        current.burnsRemaining - 2, current.pivotsRemaining,
                        current.freeBurns, current.thrust,
                        current.fuelStepsRemaining, newPartial,
                        current.jettisonedAtTurnStart,
                        current.turn, current.hazards, current.worstRadRoll,
                        current.visitedNodes, prevNode, current,
                        current.bonusSites,
                        current.turnStart()));
            }
        } else if (current.thrust <= 0) {
            // Engine non-operational (e.g. solar in deep outer zone). Free
            // pivot may still work (Option A above) but force-turn is dead.
            // Jettison would lift wet mass → improve weight class → revive
            // the engine. (We don't trigger on burnsRemaining < 2 alone:
            // that's just running out of budget this turn.)
            maybeSpawnJettisonAlt(current);
        }
        return out;
    }

    /** Free passage: Lagrange, flyby, radhaz, site, decorative, etc. */
    private List<SearchState> expandCruise(SearchState current, Neighbor neighbor,
                                           String prevNode, boolean oneWay, boolean sameNode) {
        MapNode dest = neighbor.node;
        // For cross-node moves, recompute effective thrust at the destination
        // (solar engines change thrust as they cross heliocentric zones).
        int newThrust    = sameNode
                ? current.thrust
                : effectiveThrust(current.engineIndex, dest, current.fuelStepsRemaining);
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
                current.fuelStepsRemaining, current.partialStepsThisMove,
                current.jettisonedAtTurnStart,
                current.turn, newHazards, newRadRoll,
                current.visitedNodes + visitedInc,
                prevNode, current, newBonusSites,
                current.turnStart()));
    }

    /**
     * End the current turn: round up partial fuel use (H5b) and start the
     * next movement with the freshly-computed weight-class-modified net
     * thrust.
     *
     * <p>Spawns only the no-jettison branch. Jettison alternatives are
     * generated lazily by {@link #maybeSpawnJettisonAlt(SearchState)} when
     * a downstream transition fails for thrust reasons that a class drop
     * would resolve. Eagerly spawning all class-change alternatives floods
     * the search with branches that get strictly dominated at the output
     * Pareto step (less fuel, same other dims) unless they actually unblock
     * something — which only the search itself can determine.
     */
    private List<SearchState> waitTurn(SearchState current) {
        // Apply end-of-move rounding (H5b) to settle the chit.
        int settledFuel = current.fuelStepsRemaining
                        - current.partialStepsThisMove.ceilToInt();
        if (settledFuel < 0) settledFuel = 0; // defensive; H5d should have prevented

        List<SearchState> results = new ArrayList<>(engines.size());
        addTurnStartStates(results, current, settledFuel, 0,
                /* turn = */ current.turn + 1, /* parent = */ current);
        return results;
    }

    /**
     * Spawn one fresh-turn state per available engine, given the settled
     * fuel level. Used by both {@link #waitTurn} (for the standard turn
     * boundary) and {@link #maybeSpawnJettisonAlt} (for lazy jettison
     * alternatives at the SAME turn boundary as an existing turn-start).
     */
    private void addTurnStartStates(List<SearchState> out, SearchState current,
                                    int newFuelSteps, int jettisoned,
                                    int turn, SearchState parent) {
        for (int i = 0; i < engines.size(); i++) {
            EngineSpec engine = engines.get(i);
            int thrust = effectiveThrust(i, current.node, newFuelSteps);
            int burns  = Math.max(thrust, 0);
            out.add(new SearchState(
                    current.node, null, i,
                    burns, engine.bonusPivots(), 0, thrust,
                    newFuelSteps, Fraction.ZERO, jettisoned,
                    turn,
                    current.hazards, current.worstRadRoll,
                    current.visitedNodes, null, parent, List.of(),
                    null /* this IS a turn-start */));
        }
    }

    /**
     * Effective net thrust for an engine at a given node, with current Wet
     * Mass derived from {@code fuelStepsRemaining}:
     * {@code baseThrust + weightClassMod(WM) + (solar ? node.solarMod : 0)}.
     *
     * <p>Reserved future term: afterburn (H3a) — adds +1 once per move,
     * for {@code engine.afterburnFuelCost} fuel steps. Currently ignored.
     */
    private int effectiveThrust(int engineIndex, MapNode node, int fuelStepsRemaining) {
        EngineSpec engine = engines.get(engineIndex);
        int wm = FuelStrip.wetMassAt(dryMass, fuelStepsRemaining);
        int weightMod = FuelStrip.weightClassModForWetMass(wm);
        int solar = engine.solarPowered() ? node.solarMod() : 0;
        return engine.baseThrust() + weightMod + solar;
    }

    /** True while the state is a mid-turn position (reached by burn/turn/cruise, not waitTurn). */
    private static boolean isMidTurn(SearchState s) {
        return s.parent != null && s.turn == s.parent.turn;
    }

    // -------------------------------------------------------------------------
    // Lazy jettison (HF4A F3d / G1f)
    //
    // Eager jettison branching at every waitTurn floods the search with
    // siblings that are strictly dominated at output-Pareto time (less fuel,
    // same other dims) UNLESS they actually unlock a transition the no-jet
    // sibling can't make. The methods below spawn jettison alternatives
    // only when a forward expansion blocks for thrust reasons that a
    // class-drop would resolve.
    // -------------------------------------------------------------------------

    /**
     * Look at the most recent turn-start ancestor of {@code current} and
     * lazily enqueue its smallest jettison alternative whose post-jettison
     * thrust strictly exceeds the no-jet sibling's. Caller is responsible
     * for invoking this only when the current state was blocked by thrust
     * (or burns, which is a downstream consequence of thrust at turn start).
     *
     * <p>The alt is spawned at the same node and turn as the original
     * turn-start; the BFS will explore its subtree from there. Duplicate
     * spawns are deduped by the standard Pareto frontier check inside
     * {@link #addIfBest}, so the trigger may fire multiple times for the
     * same alt without correctness impact.
     */
    private void maybeSpawnJettisonAlt(SearchState current) {
        if (!allowFuelJettison) return;
        statJettisonTriggers++;
        SearchState ts = current.turnStart();
        int[] alts = FuelStrip.jettisonAmountsForClassChange(dryMass, ts.fuelStepsRemaining);
        for (int alt : alts) {
            int newFuelSteps = ts.fuelStepsRemaining - alt;
            int altThrust = effectiveThrust(ts.engineIndex, ts.node, newFuelSteps);
            if (altThrust > ts.thrust) {
                // Smallest alt that produces strictly more thrust than no-jet.
                // Spawn it as a sibling turn-start of ts (same parent, same turn).
                List<SearchState> spawned = new ArrayList<>(engines.size());
                addTurnStartStates(spawned, ts, newFuelSteps, alt, ts.turn, ts.parent);
                for (SearchState s : spawned) {
                    if (addIfBest(s, bestFound)) { queue.add(s); statEnqueued++; statJettisonSpawns++; }
                }
                return;
            }
        }
    }

    /**
     * Minimum {@code thrust} a transition would need to clear the thrust
     * gates inside {@link #isAllowed} (landing-burn, site-landing, site-
     * liftoff). Returns 0 when the transition has no thrust gate.
     *
     * <p>Used by the search loop to decide whether an {@code isAllowed}
     * rejection was thrust-related (and therefore a candidate for lazy
     * jettison) vs. structural (no-U-turn, flyby reentry, etc.).
     */
    private int requiredThrustForTransition(MapNode from, MapNode to) {
        int required = 0;
        // Landing-burn gate (≥)
        if (!to.landing().isZero() && to.thrustRequired() > 0) {
            required = Math.max(required, to.thrustRequired());
        }
        // Powered-landing gate (>), modulo aerobrake bypass for size ≥ 6
        if (to.isSite()) {
            int siteReq = to.thrustRequired();
            if (siteReq > 0) {
                boolean viaDecChain = from.isDecorative();
                boolean viaOneWayIn = "0".equals(map.edgeLabel(from, to));
                boolean aerobrakeEligible = (viaDecChain || viaOneWayIn) && siteReq >= 6;
                if (!aerobrakeEligible) {
                    required = Math.max(required, siteReq + 1);
                }
            }
        }
        // Liftoff gate (>)
        if (from.isSite() && from.thrustRequired() > 0) {
            required = Math.max(required, from.thrustRequired() + 1);
        }
        return required;
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
     * Attempts to insert {@code state} into the Pareto frontier at its
     * comparability-context bucket. Returns true (and updates the frontier)
     * if the state is non-dominated. Post-Phase-2: bucket members are
     * guaranteed to share (nodeId, engineIndex, entryLabel, previousNodeId),
     * so {@link SearchState#notDominatedBy}'s context early-returns are
     * structurally dead here — every call exercises only the cost dims.
     */
    private boolean addIfBest(SearchState state, Map<FrontierKey, List<SearchState>> bestFound) {
        statAddIfBestCalls++;
        FrontierKey key = keyOf(state);
        List<SearchState> existing = bestFound.computeIfAbsent(key, k -> new ArrayList<>());

        // Reject if any existing state dominates or equals the new state.
        for (SearchState e : existing) {
            statNotDominatedByCalls++;
            if (!state.notDominatedBy(e) || state.equalState(e)) {
                statAddIfBestRejected++;
                return false;
            }
        }

        // Evict any existing states now dominated by the new state.
        int before = existing.size();
        existing.removeIf(e -> { statNotDominatedByCalls++; return !e.notDominatedBy(state); });
        statEvictions += (before - existing.size());

        existing.add(state);
        statAddIfBestAccepted++;
        // No sort needed — the priority queue governs expansion order; this list
        // is only scanned for dominance/identity, both order-independent.
        return true;
    }

    /**
     * Returns true if the state is still present in its comparability-context
     * bucket (it may have been evicted by a state enqueued after it).
     */
    private boolean isStillBest(SearchState state, Map<FrontierKey, List<SearchState>> bestFound) {
        statIsStillBestCalls++;
        List<SearchState> best = bestFound.get(keyOf(state));
        if (best == null) return false;
        for (SearchState s : best) {
            if (s == state) return true; // identity check
        }
        return false;
    }
}
