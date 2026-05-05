package com.hf4all.planner.pathfinder;

import com.hf4all.planner.config.Config;
import com.hf4all.planner.model.Fraction;
import com.hf4all.planner.model.FuelStrip;
import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.api.*;

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
    // The frontier is keyed by the full comparability context
    // (nodeId, engineIndex, entryLabel, previousNodeId) so every bucket
    // contains only states that are directly comparable via notDominatedBy.
    // Keying on just nodeId would fold incomparable states together and
    // force every dominance check to re-test the context discriminators —
    // a large class of wasted work on maps with multi-engine / labelled-
    // edge traffic.
    private Map<FrontierKey, List<SearchState>> bestFound;
    private PriorityQueue<SearchState> queue;

    /** Frontier-bucket key: groups states that are directly comparable by
     *  the {@link SearchState#notDominatedBy} context gates. */
    private record FrontierKey(String nodeId, int engineIndex,
                               String entryLabel, String previousNodeId) {}

    private static FrontierKey keyOf(SearchState s) {
        return new FrontierKey(s.node.id(), s.engineIndex, s.entryLabel, s.previousNodeId);
    }

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
        try {
            FuelStrip.validateFuelSteps(request.dryMass(), request.fuelSteps());
        } catch (IllegalArgumentException e) {
            return error(request.startNodeId(), e.getMessage());
        }
        return new Pathfinder(map, request.engines(),
                request.dryMass(), request.fuelSteps(),
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
        Map<FrontierKey, List<SearchState>> result = search(start);
        Map<String, List<SearchState>> endpoints = finalPrune(result);
        return buildResponse(start.id(), endpoints);
    }

    /** Phase 1 — Pareto-optimal BFS. */
    private Map<FrontierKey, List<SearchState>> search(MapNode start) {

        // Seed: one initial state per engine. Weight class derived from the
        // user-supplied (dryMass, fuel); jettison at turn 1 is NOT a branch
        // — the player committed to the starting load by entering it.
        // Afterburn IS a branch on turn 1 though (eager): for engines that
        // can afterburn we emit both no-AB and AB variants alongside the
        // baseline so the search treats turn 1 like every other turn-start.
        List<SearchState> seeds = new ArrayList<>(engines.size() * 2);
        SearchState pseudoCurrent = new SearchState(
                start, null, 0, 0, 0, 0, 0,
                initialFuelSteps, Fraction.ZERO, 0,
                1, 0, 0,                       // turn=1 hazards=0 worstRadRoll=0
                1, null, null, List.of(),
                null, false, 0L);
        addTurnStartStates(seeds, pseudoCurrent, initialFuelSteps, /*jettisoned=*/ 0,
                /*turn=*/ 1, /*parent=*/ null);
        for (SearchState s : seeds) {
            if (addIfBest(s, bestFound)) queue.add(s);
        }

        int iterations = 0;
        while (!queue.isEmpty()) {
            if (++iterations > MAX_ITERATIONS) break;

            SearchState current = queue.poll();
            if (!isStillBest(current, bestFound)) continue;

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
                rootWetMass, 0, 0,
                1, 0, 0, -1);

        Map<SearchState, PathNode> stateToNode = new IdentityHashMap<>();
        Deque<SearchState> buildQueue = new ArrayDeque<>();
        for (SearchState s : onPath) {
            if (s.parent == null) {
                // Turn-1 seeds and lazy-jettison alts are parent-null. A seed
                // that EITHER jettisoned OR afterburned at turn 1 carries info
                // (badge + costs) that would be silently dropped if collapsed
                // into the shared root, so it gets its own PathNode under root.
                boolean abAtSeed  = s.afterburnedThisMove;
                boolean jetAtSeed = s.jettisonedAtTurnStart > 0;
                if (jetAtSeed || abAtSeed) {
                    int eff   = s.effectiveFuelStepsRemaining();
                    int wm    = FuelStrip.wetMassAt(dryMass, eff);
                    int spent = initialFuelSteps - eff;
                    Fraction remainFrac = Fraction.of(s.fuelStepsRemaining)
                            .subtract(s.partialStepsThisMove);
                    Fraction spentFrac  = Fraction.of(initialFuelSteps)
                            .subtract(remainFrac);
                    int abGain = abAtSeed
                            ? engines.get(s.engineIndex).afterburnThrustGain() : 0;
                    PathNode seedRoot = new PathNode(nextId++, s.node.id(),
                            eff, spent,
                            remainFrac.numerator(), remainFrac.denominator(),
                            spentFrac.numerator(),  spentFrac.denominator(),
                            wm, s.jettisonedAtTurnStart, abGain,
                            s.turn, s.hazards, s.worstRadRoll, s.engineIndex);
                    root.addChild(seedRoot);
                    stateToNode.put(s, seedRoot);
                } else {
                    stateToNode.put(s, root);
                }
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

                // jettisonedAtTurnStart and afterburnedThisMove are both
                // inherited by every mid-turn descendant. Report them on the
                // PathNode ONLY at the actual turn-start (where the event
                // occurred) so the UI doesn't splash badges across the turn.
                boolean isTurnStart = (child.turnStart == null);
                int jettisonedOnNode = isTurnStart ? child.jettisonedAtTurnStart : 0;
                int afterburnedOnNode = (isTurnStart && child.afterburnedThisMove)
                        ? engines.get(child.engineIndex).afterburnThrustGain() : 0;

                PathNode childPN = new PathNode(nextId++, child.node.id(),
                        eff, spent,
                        remainFrac.numerator(), remainFrac.denominator(),
                        spentFrac.numerator(),  spentFrac.denominator(),
                        wm, jettisonedOnNode, afterburnedOnNode,
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

        EngineSpec engine = engines.get(current.engineIndex);
        List<SearchState> out = new ArrayList<>(2);
        boolean isHazard  = dest.hazard();
        boolean isLanding = !dest.landing().isZero();
        int newHazards   = current.hazards + (isHazard ? 1 : 0);
        int newRadRoll   = updatedRadRoll(current.worstRadRoll, dest, destThrust);
        // H5e: half-burn lander spaces (landing = 1/2) cost half the fuel
        // steps of a full burn. landing == 1 (full burn) leaves cost unchanged.
        Fraction burnCost = engine.fuelConsumption();
        if (isLanding) burnCost = burnCost.multiply(dest.landing());
        int visitedInc   = dest.isDecorative() ? 0 : 1;

        Fraction newPartial = current.partialStepsThisMove.add(burnCost);
        Fraction fuelCap    = Fraction.of(current.fuelStepsRemaining);

        // H8e Solar Oberth flyby: lander burn that grants Bonus Burns equal
        // to the activated thruster's BASE thrust (not net), +1 if the ship
        // afterburned this movement. Once per move per node (use bonusSites).
        boolean oberthBoost = dest.solarOberth()
                && !current.bonusSites.contains(dest.id());
        int oberthBonusBurns = 0;
        List<String> bonusSitesAfterEntry = current.bonusSites;
        if (oberthBoost) {
            oberthBonusBurns = engine.baseThrust();
            if (current.afterburnedThisMove) oberthBonusBurns += 1;
            bonusSitesAfterEntry = new ArrayList<>(current.bonusSites);
            bonusSitesAfterEntry.add(dest.id());
        }

        // H6b sail-aerobrake decommission: a sail entering a hazard node
        // is destroyed. Engine becomes permanently non-operational for the
        // rest of the search; the resulting state has thrust=0, burns=0,
        // and the engine's bit set in decommissionedEngines.
        boolean willDecommission = isHazard
                && engine.decommissionsOnAerobrake()
                && (current.decommissionedEngines & (1L << current.engineIndex)) == 0;
        long newDecom    = willDecommission
                ? current.decommissionedEngines | (1L << current.engineIndex)
                : current.decommissionedEngines;
        int  thrustAfter = willDecommission ? 0 : current.thrust;
        int  burnsAfter  = willDecommission ? 0 : current.burnsRemaining;

        // Option A: free burn (not allowed on a landing approach)
        if (current.freeBurns > 0 && !isLanding) {
            out.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    burnsAfter, current.pivotsRemaining,
                    oneWay ? 0 : current.freeBurns - 1 + oberthBonusBurns, thrustAfter,
                    current.fuelStepsRemaining, current.partialStepsThisMove,
                    current.jettisonedAtTurnStart,
                    current.turn, newHazards, newRadRoll,
                    current.visitedNodes + visitedInc, prevNode, current,
                    bonusSitesAfterEntry,
                    current.turnStart(), current.afterburnedThisMove,
                    newDecom));
        }
        // Option B: paid burn — H5d gate: fractional partial ≤ remaining
        if (current.burnsRemaining > 0 && !newPartial.isGreaterThan(fuelCap)) {
            // burnsAfter accounts for paid-burn decrement *after* decommission
            // (so post-decommission state has 0 burns, not −1).
            int paidBurnsAfter = willDecommission ? 0 : (current.burnsRemaining - 1);
            out.add(new SearchState(
                    dest, neighbor.direction, current.engineIndex,
                    paidBurnsAfter, current.pivotsRemaining,
                    oneWay ? 0 : current.freeBurns + oberthBonusBurns, thrustAfter,
                    current.fuelStepsRemaining, newPartial,
                    current.jettisonedAtTurnStart,
                    current.turn, newHazards, newRadRoll,
                    current.visitedNodes + visitedInc, prevNode, current,
                    bonusSitesAfterEntry,
                    current.turnStart(), current.afterburnedThisMove,
                    newDecom));
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
                    current.turnStart(), current.afterburnedThisMove,
                    current.decommissionedEngines));
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
                        current.turnStart(), current.afterburnedThisMove,
                    current.decommissionedEngines));
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
        // Net thrust is FROZEN for the entire movement per H3 ("calculated
        // once before movement begins"). Base thrust, weight class (post-
        // jettison), solar mod (at START node), afterburn — all snapshotted
        // at turn-start. Crossing a heliocentric-zone boundary mid-move does
        // NOT update net thrust: the H3 calculation uses solar mod at the
        // start node, and that's what gates landing/liftoff/burn-limit/
        // force-turn for the rest of the move (H3, H5c, H6a).
        //
        // Engine operational status is a separate concern (per H2b: solar
        // engines become non-operational in the Neptune zone) — handled in
        // expandBurn via the destThrust check, which guards burn entry but
        // does NOT modify the frozen net thrust.
        EngineSpec engine = engines.get(current.engineIndex);
        int newThrust = current.thrust;
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
        // H8f Mag Sail: every radiation belt entered confers Bonus Burns
        // (severity = node.radiation), once per belt per movement.
        if (!sameNode && dest.isRadhaz() && engine.magSail()
                && !current.bonusSites.contains(dest.id())) {
            newFreeBurns += dest.radiation();
            newBonusSites.add(dest.id());
        }
        // H8e Solar Oberth flyby (cruise entry): some maps tag a non-burn
        // node (e.g. central lagrange of an Oberth structure) with the
        // Oberth flag. Grant base-thrust bonus burns once per move.
        // (expandBurn handles the canonical lander-burn-typed Oberth.)
        if (!sameNode && dest.solarOberth()
                && !current.bonusSites.contains(dest.id())) {
            int oberth = engine.baseThrust();
            if (current.afterburnedThisMove) oberth += 1;
            newFreeBurns += oberth;
            newBonusSites.add(dest.id());
        }
        // One-way edge zeroes out free burns (applies after flyby/magsail boost too).
        if (oneWay) newFreeBurns = 0;

        // H6b sail-aerobrake decommission: a sail entering a hazard node
        // is destroyed. Engine becomes permanently non-operational; state
        // gets thrust=0 burns=0 so coasting is the only option for the rest
        // of the move and waitTurn skips this engine onward.
        boolean willDecommission = isHazard && engine.decommissionsOnAerobrake()
                && (current.decommissionedEngines & (1L << current.engineIndex)) == 0;
        long newDecom    = willDecommission
                ? current.decommissionedEngines | (1L << current.engineIndex)
                : current.decommissionedEngines;
        int  thrustAfter = willDecommission ? 0 : newThrust;
        int  burnsAfter  = willDecommission ? 0 : current.burnsRemaining;

        return List.of(new SearchState(
                dest, neighbor.direction, current.engineIndex,
                burnsAfter, current.pivotsRemaining,
                newFreeBurns, thrustAfter,
                current.fuelStepsRemaining, current.partialStepsThisMove,
                current.jettisonedAtTurnStart,
                current.turn, newHazards, newRadRoll,
                current.visitedNodes + visitedInc,
                prevNode, current, newBonusSites,
                current.turnStart(), current.afterburnedThisMove,
                newDecom));
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
     * Spawn fresh-turn states per available engine, given the settled fuel
     * level. Used by {@link #waitTurn}, {@link #maybeSpawnJettisonAlt}, and
     * the search seed.
     *
     * <p>For each engine we eagerly emit BOTH an afterburn-off and (when the
     * engine can afterburn and fuel covers the cost) an afterburn-on branch.
     * Per the project decision: weight class is derived from {@code newFuelSteps}
     * (post-jettison, pre-afterburn). Afterburn gain is layered on top of the
     * resulting net thrust; the cost is deducted from the AB branch's fuel
     * budget but does NOT feed back into weight class.
     */
    private void addTurnStartStates(List<SearchState> out, SearchState current,
                                    int newFuelSteps, int jettisoned,
                                    int turn, SearchState parent) {
        long decom = current.decommissionedEngines;
        for (int i = 0; i < engines.size(); i++) {
            // Skip engines that have been permanently decommissioned (e.g.
            // sail destroyed by an aerobrake hazard, H6b).
            if ((decom & (1L << i)) != 0) continue;

            EngineSpec engine = engines.get(i);
            int noAbThrust = effectiveThrust(i, current.node, newFuelSteps);

            // Branch A: no afterburn (always present).
            out.add(buildTurnStart(current, i, engine, noAbThrust,
                    newFuelSteps, jettisoned, turn, parent, /*ab=*/ false));

            // Branch B: afterburn (eager, when supported and affordable).
            int cost = engine.afterburnFuelCost();
            if (engine.canAfterburn() && newFuelSteps >= cost) {
                int abThrust = noAbThrust + engine.afterburnThrustGain();
                out.add(buildTurnStart(current, i, engine, abThrust,
                        newFuelSteps - cost, jettisoned, turn, parent, /*ab=*/ true));
            }
        }
    }

    /** Builds a single turn-start SearchState. Centralises the constructor
     *  call so the AB-vs-no-AB branching above stays readable. */
    private SearchState buildTurnStart(SearchState current, int engineIndex,
                                       EngineSpec engine, int thrust,
                                       int fuelSteps, int jettisoned, int turn,
                                       SearchState parent, boolean afterburned) {
        int burns = Math.max(thrust, 0);
        return new SearchState(
                current.node, null, engineIndex,
                burns, engine.bonusPivots(), 0, thrust,
                fuelSteps, Fraction.ZERO, jettisoned,
                turn,
                current.hazards, current.worstRadRoll,
                current.visitedNodes, null, parent, List.of(),
                null /* this IS a turn-start */,
                afterburned, current.decommissionedEngines);
    }

    /**
     * Solar-powered engines become non-operational in zones whose solar
     * modifier is at or below this threshold (H3c — Neptune J zone). Maps
     * with deeper-than-Saturn zones should set {@code solarMod ≤ −5} on
     * those nodes; this guard makes any such zone a hard shutdown rather
     * than letting a high-base-thrust engine still produce positive net
     * thrust there.
     */
    private static final int SOLAR_SHUTDOWN_SOLAR_MOD = -5;

    /**
     * Net thrust for an engine at a given node, with Wet Mass derived from
     * {@code fuelStepsRemaining}: {@code baseThrust + weightClassMod(WM) +
     * (solar ? node.solarMod : 0)}.
     *
     * <p>Afterburn (H3a) is intentionally NOT folded in here. By project
     * decision, afterburn is a flat layer on top of the post-weight-class
     * thrust — the cost is deducted from the AB turn-start's fuel budget
     * but does not feed back into weight class. Callers in
     * {@link #addTurnStartStates} add {@code engine.afterburnThrustGain()}
     * after this method returns.
     */
    private int effectiveThrust(int engineIndex, MapNode node, int fuelStepsRemaining) {
        EngineSpec engine = engines.get(engineIndex);
        // H3c hard shutdown: solar engines in deep outer zones are non-
        // operational regardless of base thrust. Returning 0 ensures the
        // existing destThrust-≤-0 guards treat the engine as inoperative.
        if (engine.solarPowered() && node.solarMod() <= SOLAR_SHUTDOWN_SOLAR_MOD) {
            return 0;
        }
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
                    if (addIfBest(s, bestFound)) queue.add(s);
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
     * if the state is non-dominated. Bucket members are guaranteed to share
     * (nodeId, engineIndex, entryLabel, previousNodeId) by construction,
     * so {@link SearchState#notDominatedBy}'s context early-returns are
     * structurally dead here — every call exercises only the cost dims.
     */
    private static boolean addIfBest(SearchState state, Map<FrontierKey, List<SearchState>> bestFound) {
        FrontierKey key = keyOf(state);
        List<SearchState> existing = bestFound.computeIfAbsent(key, k -> new ArrayList<>());

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
     * Returns true if the state is still present in its comparability-context
     * bucket (it may have been evicted by a state enqueued after it).
     */
    private static boolean isStillBest(SearchState state, Map<FrontierKey, List<SearchState>> bestFound) {
        List<SearchState> best = bestFound.get(keyOf(state));
        if (best == null) return false;
        for (SearchState s : best) {
            if (s == state) return true; // identity check
        }
        return false;
    }
}
