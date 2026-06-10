package com.hf4all.planner.pathfinder;

import com.hf4all.planner.config.Config;
import com.hf4all.planner.model.Fraction;
import com.hf4all.planner.model.FuelStrip;
import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.NodeType;
import com.hf4all.planner.model.Season;
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
    private final boolean allowFuelJettison;
    /** Year on the Sol Sunspot Cycle when turn 1 begins (1..12). Drives
     *  the season-aware gates: Belt Roll +2 in red (H10b), Venus flyby
     *  bonus blue-only (H8c), synodic-comet site/adjacent-space match. */
    private final int startingYear;

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

    // Streaming support. {@code sink} is non-null only for a streaming run;
    // the plain traverse() path leaves it null and therefore skips every
    // year-boundary bookkeeping branch below, staying perf-identical.
    private PartialSink sink;
    private String startNodeId;
    // queuedByTurn[t] = number of states with turn == t currently in the
    // priority queue. Once it (and every lower bucket) hits zero, mission
    // year t can gain no further route — turn is monotonic non-decreasing
    // across expansion — so its partial is final and we publish it. Allocated
    // only when streaming. minQueuedTurn / emittedYear are the advancing
    // cursor over fully-drained years.
    private int[] queuedByTurn;
    private int minQueuedTurn;
    private int emittedYear;
    // Delta emission: a stable tree-node id per on-path SearchState (assigned
    // once, reused across every chunk), the running id counter (0 = synthetic
    // root), whether the root has been emitted, and which (endpoint nodeId →
    // tree id) pairs have already been sent. Together these let each chunk
    // transmit only the newly-finalised subtree, so the whole tree crosses the
    // wire exactly once.
    private IdentityHashMap<SearchState, Integer> streamNodeId;
    private int streamNextId;
    private boolean rootEmitted;
    private Map<String, Set<Integer>> sentEndpointIds;

    /** Frontier-bucket key: groups states that are directly comparable by
     *  the {@link SearchState#notDominatedBy} context gates.
     *
     *  <p>{@code turnMod12} ({@code (turn-1) % 12}) discriminates states by
     *  position in the 12-year Sunspot Cycle (K1). Two states in the same
     *  mod-12 class are at the same calendar year, so every future event
     *  (Belt-Roll +2 in red H10b, synodic-comet gating B7h, Venus blue-only
     *  H8c) lands on the same season for both — they are fully comparable
     *  on the turn dim and lower-turn dominates. Two states in different
     *  mod-12 classes have different calendar years going forward; their
     *  future season-conditioned costs may diverge in either direction, so
     *  they must remain incomparable on turn even when otherwise tied.
     *
     *  <p>This is the "identical-except-turn ⇒ incomparable, with cycle
     *  closure at 12 turns" rule — the user's "up to 11 more turns
     *  tolerance" specified literally. A coarser season-bucket (3 buckets
     *  instead of 12) was tried as a performance hedge but is incorrect:
     *  it allowed a turn-5-yellow state to dominate a turn-8-yellow state
     *  even though their downstream radhaz crossings may fall in different
     *  seasons (turn 13 red vs turn 16 yellow → +2 penalty asymmetry).
     */
    private record FrontierKey(String nodeId, int engineIndex,
                               String entryLabel, String previousNodeId,
                               int turnMod12) {}

    private FrontierKey keyOf(SearchState s) {
        int turnMod = ((s.turn - 1) % 12 + 12) % 12;
        return new FrontierKey(s.node.id(), s.engineIndex, s.entryLabel,
                s.previousNodeId, turnMod);
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
                       boolean allowFuelJettison,
                       int startingYear) {
        this.map = map;
        this.engines = engines;
        this.dryMass = dryMass;
        this.initialFuelSteps = initialFuelSteps;
        this.allowFuelJettison = allowFuelJettison;
        this.startingYear = startingYear;
    }

    /** Season at the start of a given mission turn (turn 1 = startingYear).
     *  Pure function — no Pareto-frontier interaction beyond what {@code turn}
     *  already provides as a search dimension. */
    private Season seasonAtTurn(int turn) {
        return Season.atYear(startingYear + turn - 1);
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Callback for {@link #traverseStreaming}: receives each cumulative
     * partial result as a mission year is fully planned, then a final
     * {@code done} chunk. Implementations typically serialise the chunk to
     * the HTTP response; throwing from {@code accept} (e.g. on client
     * disconnect) unwinds the search and stops it promptly.
     */
    @FunctionalInterface
    public interface PartialSink {
        void accept(TraverseStreamChunk chunk);
    }

    /** Non-streaming entry point — runs the search to completion and returns
     *  the full result. Behaviour is unchanged from before streaming existed;
     *  every test and the benchmark use this form. */
    public static TraverseResponse traverse(SolarMap map, TraverseRequest request) {
        return traverseInternal(map, request, null);
    }

    /** Streaming entry point — same search, but pushes a cumulative partial
     *  result to {@code sink} each time a mission year is fully planned, plus
     *  a final {@code done} chunk carrying the complete result. On a
     *  validation failure the lone {@code done} chunk carries the error. */
    public static void traverseStreaming(SolarMap map, TraverseRequest request, PartialSink sink) {
        traverseInternal(map, request, sink);
    }

    private static TraverseResponse traverseInternal(SolarMap map, TraverseRequest request,
                                                     PartialSink sink) {
        MapNode start = map.nodeById(request.startNodeId());
        String validationMsg = validationError(map, request, start);
        if (validationMsg != null) {
            TraverseResponse err = error(request.startNodeId(), validationMsg);
            // Deliver validation failures through the same channel so the
            // frontend's streaming reader sees a uniform shape: one done chunk,
            // year 0, nothing planned, with the message carried in `status`.
            if (sink != null) {
                sink.accept(new TraverseStreamChunk(0, MAX_TURNS, true,
                        request.startNodeId(), List.of(), Map.of(), validationMsg));
            }
            return err;
        }
        return new Pathfinder(map, request.engines(),
                request.dryMass(), request.fuelSteps(),
                request.allowFuelJettison(),
                request.startingYear()).run(start, sink);
    }

    /** Shared request validation for both entry points. Returns the error
     *  message, or {@code null} when the request is runnable. Check order
     *  matches the original traverse() so the error text is unchanged. */
    private static String validationError(SolarMap map, TraverseRequest request, MapNode start) {
        if (start == null) return "unknown node: " + request.startNodeId();
        if (request.engines() == null || request.engines().isEmpty()) {
            return "at least one engine required";
        }
        try {
            FuelStrip.validateFuelSteps(request.dryMass(), request.fuelSteps());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        int year = request.startingYear();
        if (year < 1 || year > 12) {
            return "startingYear must be in 1..12 (got " + year + ")";
        }
        return null;
    }

    private static TraverseResponse error(String startId, String message) {
        return new TraverseResponse(startId, null, Map.of(), message);
    }

    // -------------------------------------------------------------------------
    // Algorithm phases
    // -------------------------------------------------------------------------

    private TraverseResponse run(MapNode start, PartialSink sink) {
        this.sink = sink;
        this.startNodeId = start.id();
        bestFound = new HashMap<>();
        queue = new PriorityQueue<>(BY_COST);
        if (sink != null) {
            streamNodeId = new IdentityHashMap<>();
            streamNextId = 1;            // 0 is reserved for the synthetic root
            rootEmitted = false;
            sentEndpointIds = new HashMap<>();
        }
        Map<FrontierKey, List<SearchState>> result = search(start);
        if (sink != null) {
            // Final message: flush any subtree deeper than the last partial year
            // and mark done. Every node has now been sent exactly once across the
            // deltas — there is no full resend.
            emitDelta(Integer.MAX_VALUE, MAX_TURNS, true);
            return null; // streaming callers ignore the return value
        }
        Map<String, List<SearchState>> endpoints = finalPrune(result, Integer.MAX_VALUE);
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
        if (sink != null) {
            queuedByTurn = new int[MAX_TURNS + 2];
            minQueuedTurn = 1;
            emittedYear = 0;
        }
        for (SearchState s : seeds) {
            if (addIfBest(s, bestFound)) enqueue(s);
        }

        int iterations = 0;
        while (!queue.isEmpty()) {
            if (++iterations > MAX_ITERATIONS) break;

            SearchState current = queue.poll();
            // Decrement before the stale-skip so dominated (dropped) states
            // still free their turn bucket. turn is monotonic, so a bucket
            // reaching zero genuinely means that year has no more active work.
            if (sink != null) queuedByTurn[Math.min(current.turn, MAX_TURNS + 1)]--;
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
                    if (addIfBest(next, bestFound)) enqueue(next);
                }
            }

            // Wait-turn option. HF4A lets a player decline to move and just
            // advance the Sunspot Cube — so waitTurn is available not only
            // mid-turn (settling partial fuel after a move) but also at
            // turn-start (a no-move turn that costs nothing). Without the
            // turn-start branch the search has to move out and back to
            // waste a turn at the start node, which spends a paid burn just
            // to wait — exactly what's needed when timing a season-gated
            // departure (e.g. waiting for blue to enter Venus).
            // canEndTurnHere already excludes landing burns (H5e) and
            // decoratives, so this is the same gate isAllowed enforces
            // for the same-node-different-turn transition.
            if (canEndTurnHere(current)) {
                for (SearchState wait : waitTurn(current)) {
                    if (!isAllowed(current, wait)) continue;
                    if (wait.turn > MAX_TURNS) continue;
                    if (addIfBest(wait, bestFound)) enqueue(wait);
                }
            }

            // Streaming: now that this state's same-turn children are enqueued
            // and counted, publish any mission year that just drained to zero.
            // Because turn is monotonic non-decreasing across expansion, a year
            // with no queued state at or below it can never gain another route,
            // so its partial is final. Emit one snapshot per newly-drained year;
            // the queue-not-empty guard leaves the tail to the final done send
            // instead of re-emitting identical snapshots as the search winds down.
            if (sink != null && !queue.isEmpty()) {
                while (minQueuedTurn <= MAX_TURNS && queuedByTurn[minQueuedTurn] == 0) {
                    minQueuedTurn++;
                }
                while (emittedYear + 1 < minQueuedTurn) {
                    emittedYear++;
                    emitDelta(emittedYear, emittedYear, false);
                }
            }
        }
        return bestFound;
    }

    /** Enqueue a frontier state, tracking its turn for the streaming
     *  year-cursor. The per-turn count lets {@link #search} detect when a
     *  mission year has no remaining active states (and is therefore final).
     *  The count is skipped entirely when not streaming. */
    private void enqueue(SearchState s) {
        queue.add(s);
        if (sink != null) queuedByTurn[Math.min(s.turn, MAX_TURNS + 1)]++;
    }

    /**
     * Emit a streaming delta: everything reachable within {@code pruneYear}
     * mission years that hasn't been sent yet. {@code reportYear} is stamped on
     * the chunk (= pruneYear for a partial; MAX_TURNS for the final flush).
     *
     * <p>Correctness rests on the superset property: a Pareto-optimal route
     * completing in ≤ pruneYear years is never dominated later (a later state
     * has a strictly greater turn and so cannot dominate on the turn dimension),
     * so each year's Pareto subtree contains the previous year's. The delta is
     * therefore purely additive — every on-path state is assigned a stable id
     * once and each node / endpoint entry is transmitted exactly once.
     */
    private void emitDelta(int pruneYear, int reportYear, boolean done) {
        Map<String, List<SearchState>> endpoints = finalPrune(bestFound, pruneYear);

        List<StreamNode> addedNodes = new ArrayList<>();
        if (!rootEmitted) {
            addedNodes.add(rootStreamNode());
            rootEmitted = true;
        }

        // Assign ids to any on-path states not yet seen, parents before
        // children, emitting each as a flat delta node exactly once.
        for (List<SearchState> states : endpoints.values()) {
            for (SearchState ep : states) {
                List<SearchState> chain = null;
                for (SearchState s = ep; s != null && !hasId(s); s = s.parent) {
                    if (chain == null) chain = new ArrayList<>();
                    chain.add(s);
                }
                if (chain == null) continue;
                // Ancestor-first so each node's parent already has an id.
                for (int i = chain.size() - 1; i >= 0; i--) {
                    SearchState s = chain.get(i);
                    int id = streamNextId++;
                    streamNodeId.put(s, id);
                    int parentId = (s.parent == null) ? 0 : idOf(s.parent);
                    addedNodes.add(toStreamNode(s, id, parentId));
                }
            }
        }

        // Endpoint delta: map node id → newly Pareto-optimal tree node ids.
        Map<String, List<Integer>> addedEndpoints = new LinkedHashMap<>();
        for (var entry : endpoints.entrySet()) {
            String nodeId = entry.getKey();
            Set<Integer> sent = sentEndpointIds.computeIfAbsent(nodeId, k -> new HashSet<>());
            for (SearchState ep : entry.getValue()) {
                int id = idOf(ep);
                if (sent.add(id)) {
                    addedEndpoints.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(id);
                }
            }
        }

        sink.accept(new TraverseStreamChunk(reportYear, MAX_TURNS, done,
                startNodeId, addedNodes, addedEndpoints, "ok"));
    }

    /** A plain turn-1 seed (no afterburn, no jettison) is folded into the
     *  synthetic root rather than getting its own node — matching
     *  {@link #buildResponse}. Such states resolve to root id 0. */
    private static boolean isPlainSeed(SearchState s) {
        return s.parent == null && !s.afterburnedThisMove && s.jettisonedAtTurnStart == 0;
    }

    /** True once a state already has a stable id (or is folded into the root). */
    private boolean hasId(SearchState s) {
        return isPlainSeed(s) || streamNodeId.containsKey(s);
    }

    /** Stable id of an already-seen state (root id 0 for a plain seed). */
    private int idOf(SearchState s) {
        return isPlainSeed(s) ? 0 : streamNodeId.get(s);
    }

    /** The synthetic root: the start position before any move (id 0).
     *  Mirrors the root node built by {@link #buildResponse}. */
    private StreamNode rootStreamNode() {
        int rootWetMass = FuelStrip.wetMassAt(dryMass, initialFuelSteps);
        return new StreamNode(0, -1, startNodeId,
                initialFuelSteps, 0, initialFuelSteps, 1, 0, 1,
                rootWetMass, 0, 0, 1, 0, 0, -1);
    }

    /** Flatten a search state to a delta tree node, mirroring the per-node
     *  field computation in {@link #buildResponse}. */
    private StreamNode toStreamNode(SearchState s, int id, int parentId) {
        int eff   = s.effectiveFuelStepsRemaining();
        int wm    = FuelStrip.wetMassAt(dryMass, eff);
        int spent = initialFuelSteps - eff;
        Fraction remainFrac = Fraction.of(s.fuelStepsRemaining).subtract(s.partialStepsThisMove);
        Fraction spentFrac  = Fraction.of(initialFuelSteps).subtract(remainFrac);
        // jettison / afterburn badges are reported only on the turn-start node
        // where they happened (same rule as buildResponse).
        boolean isTurnStart = (s.turnStart == null);
        int jettisonedHere  = isTurnStart ? s.jettisonedAtTurnStart : 0;
        int afterburnedHere = (isTurnStart && s.afterburnedThisMove)
                ? engines.get(s.engineIndex).afterburnThrustGain() : 0;
        return new StreamNode(id, parentId, s.node.id(),
                eff, spent,
                remainFrac.numerator(), remainFrac.denominator(),
                spentFrac.numerator(),  spentFrac.denominator(),
                wm, jettisonedHere, afterburnedHere,
                s.turn, s.hazards, s.worstRadRoll, s.engineIndex);
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
    private Map<String, List<SearchState>> finalPrune(Map<FrontierKey, List<SearchState>> bestFound,
                                                      int maxTurn) {
        // Collapse context-keyed buckets to a per-nodeId list, dropping states
        // deeper than maxTurn. A partial snapshot keeps only routes ≤ year; the
        // final result passes Integer.MAX_VALUE to keep everything.
        Map<String, List<SearchState>> byNode = new LinkedHashMap<>();
        for (var entry : bestFound.entrySet()) {
            String nodeId = entry.getKey().nodeId();
            MapNode node = map.nodeById(nodeId);
            if (node == null || node.isDecorative()) continue;
            for (SearchState s : entry.getValue()) {
                if (s.turn <= maxTurn) {
                    byNode.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(s);
                }
            }
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

        // H8c — Venus flyby is fully gated by season: passage AND boost are
        // both restricted to BLUE. Outside blue, the spacecraft cannot enter
        // a Venus node at all (handled here so every flavour of expand* sees
        // the same gate; the bonus-burn check inside expandCruise is now
        // structurally unreachable outside blue).
        if (!sameNode && dest.type() == NodeType.VENUS
                && seasonAtTurn(current.turn) != Season.BLUE) {
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
     * Radiation is applied on node entry. Severity is the node's raw
     * radiation minus the ship's current thrust (floor 0; original HF4A
     * rule {@code Math.max(RADIATION - thrust, 0)}). The new
     * {@code worstRadRoll} is the running max over the path.
     *
     * <p>HF4A H10b: in season RED, every Belt Roll suffers a +2 penalty.
     * Applied here before the floor so a radhaz with rad=2 + season-red
     * still produces a positive severity even when thrust would normally
     * cancel it.
     */
    private int updatedRadRoll(int currentWorst, MapNode dest, int thrust, int turn) {
        int penalty = (seasonAtTurn(turn) == Season.RED) ? 2 : 0;
        int mitigated = Math.max(dest.radiation() - thrust + penalty, 0);
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
        int newRadRoll   = updatedRadRoll(current.worstRadRoll, dest, destThrust, current.turn);
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
                : updatedRadRoll(current.worstRadRoll, dest, newThrust, current.turn);
        int visitedInc   = (!sameNode && !dest.isDecorative()) ? 1 : 0;

        int newFreeBurns = current.freeBurns;
        List<String> newBonusSites = new ArrayList<>(current.bonusSites);
        if (!sameNode && dest.isFlyby()) {
            // H8c Venus passage is fully season-gated upstream in
            // expandToNeighbor — by the time we get here, a Venus dest is
            // guaranteed to be in BLUE, so the +flybyBoost is unconditional
            // for all flyby types.
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
                    if (addIfBest(s, bestFound)) enqueue(s);
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
            if (!passesSynodicSeasonGate(from, to)) return false;
            if (isReversingLastMove(from, to))      return false;
        } else if (from.turn != to.turn) {
            // Same-node different-turn = waitTurn.
            if (!canEndTurnHere(from))              return false;
        }
        return true;
    }

    /**
     * HF4A B7h/H6: synodic comet sites and their adjacent coloured spaces
     * are gated by season — a Spacecraft can only enter or leave them when
     * the Sunspot Cube is in the matching season. The "adjacent coloured
     * space" is auto-derived as the closest non-decorative neighbour of
     * each tagged site (see {@link SolarMap#synodicGate(MapNode)}).
     *
     * <p>Both endpoints of the transition are checked: if either side is
     * a gated node and the current season doesn't match, the transition
     * is rejected. This naturally enforces both entry AND exit semantics.
     */
    private boolean passesSynodicSeasonGate(SearchState from, SearchState to) {
        Season toGate = map.synodicGate(to.node);
        if (toGate != null && seasonAtTurn(to.turn) != toGate) return false;
        Season fromGate = map.synodicGate(from.node);
        // Liftoff: 'from' was reached at from.turn; the move into 'to'
        // happens during to.turn (which equals from.turn unless this
        // transition crosses a turn boundary, but that's only true for
        // waitTurn — handled separately by sameNode branch above).
        if (fromGate != null && seasonAtTurn(to.turn) != fromGate) return false;
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
    private boolean addIfBest(SearchState state, Map<FrontierKey, List<SearchState>> bestFound) {
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
    private boolean isStillBest(SearchState state, Map<FrontierKey, List<SearchState>> bestFound) {
        List<SearchState> best = bestFound.get(keyOf(state));
        if (best == null) return false;
        for (SearchState s : best) {
            if (s == state) return true; // identity check
        }
        return false;
    }
}
