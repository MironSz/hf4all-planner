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
    // (nodeIdx, engineIndex, entryLabel, prevNodeIdx) so every bucket
    // contains only states that are directly comparable via notDominatedBy.
    // Keying on just nodeIdx would fold incomparable states together and
    // force every dominance check to re-test the context discriminators —
    // a large class of wasted work on maps with multi-engine / labelled-
    // edge traffic.
    private Map<FrontierKey, List<SearchState>> bestFound;
    private Frontier queue;

    /** Option A — scoped thrust dominance. Per-query (NOT static: searches run
     *  concurrently) map from node to the highest clearable gate "thrust needed"
     *  reachable within the hop horizon. {@code null} disables the feature. */
    private Map<MapNode, Integer> thrustGateCap;

    /** Proposal 1 — thrust-in-key dominance. When &gt; 0, every state's frozen
     *  net thrust (clamped to this cap) plus its afterburn bit becomes part of
     *  the {@link FrontierKey}, so states with behaviourally distinct thrust
     *  are never compared (sound), while states above the cap collapse (no
     *  clearable gate distinguishes them and every belt roll floors the same).
     *  0 disables the feature (falls back to Option A / off). Per-query. */
    private int keyThrustCap;

    /** Sound once-per-move-site dominance (pathfinder.dom.bonusSitesSound,
     *  default OFF — measured ~9× slower; see SearchState.notDominatedBy). */
    private boolean bonusSitesSound;

    /** Lazy afterburn branching (pathfinder.ab.lazy, default ON). When true,
     *  {@link #addTurnStartStates} emits only the no-afterburn branch per
     *  engine; the afterburn sibling is spawned on demand by
     *  {@link #maybeSpawnAfterburnAlt} at the points where the +gain could
     *  actually change a route (see that method's trigger taxonomy). When
     *  false, the eager branch B in {@code addTurnStartStates} is restored:
     *  every AB-capable engine emits its AB variant at every turn-start. Read
     *  fresh in {@link #run} like the {@code pathfinder.dom.*} flags. */
    private boolean abLazy;

    /** Total states enqueued this run — reported when {@code pathfinder.stats}
     *  is set. Iterations + enqueues are deterministic per (query, flags), so
     *  they benchmark search-space size independent of clock speed / power
     *  state, unlike wallclock. */
    private long statEnqueued;

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
    // output-cost vector) pairs have already been sent. Together these let each
    // chunk transmit only the newly-finalised subtree, so the whole tree crosses
    // the wire exactly once.
    //
    // The sent-set is keyed by COST VECTOR, not tree id: a per-year finalPrune
    // may pick a different state object as the representative for the same
    // output-cost vector across emits (its tie-break depends on bestFound's
    // growing iteration order), and ids are per-object. Deduping by cost vector
    // makes the stream emit exactly one route per (node, cost vector), matching
    // the one-shot finalPrune instead of double-counting the re-picked route.
    private IdentityHashMap<SearchState, Integer> streamNodeId;
    private int streamNextId;
    private boolean rootEmitted;
    private Map<String, Set<String>> sentEndpointCostVectors;

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
     *
     *  <p>{@code thrustKey} / {@code afterburned} (Proposal 1): the frozen
     *  net thrust clamped to the global sound cap, and the once-per-move
     *  afterburn bit. Frozen thrust changes future costs in ways no other
     *  dimension captures — gate clearance (landing/liftoff/landing-burn)
     *  and belt-roll mitigation ({@code rad − thrust}) — for the remainder
     *  of the current movement, and movement reach is unbounded (free
     *  coasting), so no hop horizon is sound. Clamping at the global cap is
     *  sound because above it every clearable gate clears and every belt
     *  roll floors identically. The afterburn bit matters via the Solar
     *  Oberth +1 (H8e). {@code decommissioned} separates states whose set of
     *  permanently dead engines differs — a state that lost its sail must
     *  never displace one that kept it. All three are sentinel (0/false/0)
     *  when the feature is off.
     */
    private record FrontierKey(int nodeIdx, int engineIndex,
                               String entryLabel, int prevNodeIdx,
                               int turnMod12, int thrustKey, boolean afterburned,
                               long decommissioned) {
        /** The generated record hash of several small dense ints clusters
         *  badly in a multi-hundred-thousand-key HashMap (the String ids it
         *  replaced hashed wide). Fibonacci-mix each component so nearby
         *  indices land in distant buckets. equals() stays generated. */
        @Override
        public int hashCode() {
            int h = nodeIdx;
            h = h * 0x9E3779B1 + prevNodeIdx;
            h = h * 0x9E3779B1 + turnMod12;
            h = h * 0x9E3779B1 + engineIndex;
            h = h * 0x9E3779B1 + thrustKey;
            h = h * 0x9E3779B1 + (afterburned ? 1 : 0);
            h = h * 0x9E3779B1 + (int) (decommissioned ^ (decommissioned >>> 32));
            h = h * 0x9E3779B1 + (entryLabel == null ? 0 : entryLabel.hashCode());
            return h ^ (h >>> 16);
        }
    }

    private FrontierKey keyOf(SearchState s) {
        int turnMod = ((s.turn - 1) % 12 + 12) % 12;
        int thrustKey = keyThrustCap > 0 ? Math.min(s.thrust, keyThrustCap) : 0;
        boolean ab = keyThrustCap > 0 && s.afterburnedThisMove;
        long decom = keyThrustCap > 0 ? s.decommissionedEngines : 0L;
        return new FrontierKey(s.nodeIdx, s.engineIndex, s.entryLabel,
                s.previousNodeIdx, turnMod, thrustKey, ab, decom);
    }

    private static final int MAX_TURNS = Config.searchMaxTurns();
    private static final int MAX_ITERATIONS = Config.searchMaxIterations();

    /** Canonical ordering for the priority queue: earliest turn first, then
     *  more remaining fuel (cheaper plans), then fewer hazards. */
    private static final Comparator<SearchState> BY_COST =
            Comparator.comparingInt((SearchState s) -> s.turn)
                      .thenComparingInt(s -> -s.fuelStepsRemaining)
                      .thenComparingInt(s -> s.hazards);

    // -------------------------------------------------------------------------
    // Frontier — the expansion queue for the Pareto label-correcting BFS.
    //
    // At the scale this search runs (5–11M live entries per heavy query), the
    // dominant cost of a binary-heap PriorityQueue is not the O(log n) shape
    // work but the sheer VOLUME of BY_COST comparator invocations: each push
    // and pop sifts through log2(n) ≈ 23 levels, and every level runs the
    // three-key comparator (turn, −fuel, hazards). That is tens of millions of
    // comparator calls, and they dominate the profile.
    //
    // The insight that unlocks an O(1) structure is that both leading BY_COST
    // keys have a small, fixed integer range verified in the surrounding code:
    //   * turn ∈ [1, MAX_TURNS]  — states with turn > MAX_TURNS are filtered
    //     out before enqueue (see the `next.turn > MAX_TURNS` guards in the
    //     search loop and waitTurn), so nothing above the bound ever lands here.
    //   * fuelStepsRemaining ∈ [0, 56] — the fuel-strip geometry bound enforced
    //     by FuelStrip.validateFuelSteps (wetStep ≤ 56).
    // A value in a bounded integer range needs no comparator to be ordered — a
    // radix (bucket) layout indexes it directly. That is Implementation A.
    // -------------------------------------------------------------------------

    /** Abstraction over the expansion queue so the search can swap a bucketed
     *  radix frontier for the classic binary-heap frontier at runtime without
     *  touching the loop. Both implementations honour the same primary/secondary
     *  ordering; they differ only in whether they pay the comparator cost. */
    private interface Frontier {
        void add(SearchState s);
        SearchState poll();
        boolean isEmpty();
    }

    /** Upper bound (inclusive) on {@code fuelStepsRemaining} — the fuel-strip
     *  geometry caps the Wet chit at step 56 (FuelStrip.validateFuelSteps),
     *  so the fuel axis of the radix buckets needs indices 0..56. */
    private static final int MAX_FUEL_STEPS = 56;

    /**
     * Implementation A — a turn-bucketed radix frontier that reproduces the
     * primary and secondary keys of {@link #BY_COST} in O(1) per add/poll,
     * with no comparator calls at all.
     *
     * <p><b>Ordering contract.</b> Polls emerge in exactly BY_COST's first two
     * keys: turn ascending (primary), then fuelStepsRemaining <em>descending</em>
     * (secondary — more remaining fuel is a cheaper plan), FIFO within a
     * (turn, fuel) bucket. BY_COST's tertiary key (hazards ascending) is
     * <em>intentionally dropped</em>: it only ever broke ties between states
     * already equal on turn and fuel, and the Pareto frontier / output-prune
     * stages downstream do not depend on that tie-break for correctness — they
     * re-derive the true Pareto set. Two states equal on (turn, fuel) but
     * differing on hazards will therefore pop in insertion order rather than
     * hazards order; this changes only the exploration sequence, never the set
     * of routes found.
     *
     * <p><b>Layout.</b> {@code buckets[turn][fuel]} is a lazily-allocated grid
     * of FIFO deques. Turn is the major axis (ascending scan) and fuel the minor
     * axis (descending scan), matching the key precedence above. Rows and cells
     * are allocated on first use to keep the memory footprint proportional to
     * the live turn/fuel combinations rather than MAX_TURNS × 57.
     *
     * <p><b>Cursors.</b> {@code turnCursor} is the lowest turn known to possibly
     * hold work; {@code fuelCursor} is the highest fuel index scanned so far
     * within {@code turnCursor}. Turn is monotonic across expansion (children
     * are same-turn or turn+1; lazy-jettison alts are same-turn), so the poll
     * cursor normally only ever advances forward. add() nonetheless moves the
     * cursor <em>back</em> if an entry ever lands strictly below it — cheap
     * (a couple of comparisons and assignments) and defensive, so a stray
     * lower-turn or higher-fuel insert can never be stranded behind the cursor.
     */
    private static final class RadixFrontier implements Frontier {
        private final int maxTurn;
        // buckets[turn][fuel]; rows and cells allocated lazily.
        @SuppressWarnings("unchecked")
        private final ArrayDeque<SearchState>[][] buckets;
        private int size;
        // Poll cursor: turnCursor = lowest turn that may hold work; fuelCursor =
        // current fuel index within that turn (scanned high→low). Both start at
        // the "nothing scanned yet" origin (turn 1, fuel = MAX_FUEL_STEPS).
        private int turnCursor;
        private int fuelCursor;

        @SuppressWarnings("unchecked")
        RadixFrontier(int maxTurn) {
            this.maxTurn = maxTurn;
            // Index by turn directly (1..maxTurn); slot 0 unused so no −1 offset.
            this.buckets = new ArrayDeque[maxTurn + 1][];
            this.turnCursor = 1;
            this.fuelCursor = MAX_FUEL_STEPS;
        }

        @Override
        public void add(SearchState s) {
            // turn is guaranteed in [1, maxTurn] by the enqueue-time filters;
            // clamp defensively so a stray value can never index out of bounds.
            int turn = s.turn;
            if (turn < 1) turn = 1;
            else if (turn > maxTurn) turn = maxTurn;
            int fuel = s.fuelStepsRemaining;
            if (fuel < 0) fuel = 0;
            else if (fuel > MAX_FUEL_STEPS) fuel = MAX_FUEL_STEPS;

            ArrayDeque<SearchState>[] row = buckets[turn];
            if (row == null) {
                row = new ArrayDeque[MAX_FUEL_STEPS + 1];
                buckets[turn] = row;
            }
            ArrayDeque<SearchState> cell = row[fuel];
            if (cell == null) {
                cell = new ArrayDeque<>();
                row[fuel] = cell;
            }
            cell.addLast(s);            // FIFO within a (turn, fuel) bucket
            size++;

            // Keep the cursor at or below any live entry. Turn monotonicity
            // means this branch is essentially never taken during normal
            // expansion, but honouring it keeps correctness independent of that
            // assumption: an entry must never sit behind the scan position.
            if (turn < turnCursor) {
                turnCursor = turn;
                fuelCursor = fuel;
            } else if (turn == turnCursor && fuel > fuelCursor) {
                fuelCursor = fuel;
            }
        }

        @Override
        public SearchState poll() {
            if (size == 0) return null;
            // Advance (turn asc, fuel desc) to the next non-empty bucket. The
            // total scan work across a whole search is bounded by
            // maxTurn × (MAX_FUEL_STEPS + 1) cell visits plus the pops, so this
            // stays O(1) amortised per element — no comparator ever runs.
            while (turnCursor <= maxTurn) {
                ArrayDeque<SearchState>[] row = buckets[turnCursor];
                if (row != null) {
                    while (fuelCursor >= 0) {
                        ArrayDeque<SearchState> cell = row[fuelCursor];
                        if (cell != null && !cell.isEmpty()) {
                            SearchState s = cell.pollFirst();
                            size--;
                            return s;
                        }
                        fuelCursor--;
                    }
                }
                // Row exhausted (or absent): drop to the next turn, rewind the
                // fuel scan to the top (highest fuel first within the new turn).
                turnCursor++;
                fuelCursor = MAX_FUEL_STEPS;
            }
            return null; // size said non-empty but nothing found — unreachable
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }
    }

    /** Implementation B — a thin wrapper around the classic binary-heap
     *  {@link PriorityQueue} ordered by {@link #BY_COST}. Retained as the
     *  reference frontier for A/B comparison against {@link RadixFrontier};
     *  selected when {@code pathfinder.queue.radix=false}. */
    private static final class HeapFrontier implements Frontier {
        private final PriorityQueue<SearchState> pq = new PriorityQueue<>(BY_COST);

        @Override
        public void add(SearchState s) { pq.add(s); }

        @Override
        public SearchState poll() { return pq.poll(); }

        @Override
        public boolean isEmpty() { return pq.isEmpty(); }
    }

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
    // Option A — scoped thrust dominance precompute
    // -------------------------------------------------------------------------

    /** Hop horizon for thrust sensitivity: how far ahead of a thrust gate a
     *  state can still be when the gate matters (a movement's reach). */
    private int thrustScopeHops() {
        return Integer.getInteger("pathfinder.dom.thrustScopeHops", 5);
    }

    /**
     * Per node, the maximum gate "thrust needed to clear" reachable within the
     * hop horizon — the clamp cap used by {@link SearchState#notDominatedBy}.
     * Nodes absent from the map have no reachable gate (pure-fuel dominance).
     *
     * <p>Only gates a ship in this query could actually clear are counted
     * ({@code need ≤ maxAchievableThrust}): a gate no weight class can clear is
     * never contested, so it would only bloat the frontier with no route to
     * preserve. Built with a bounded BFS from each gate over the undirected
     * graph (an over-approximation of in-movement reachability — safe: it only
     * widens the sensitive zone, never narrows it).
     */
    private Map<MapNode, Integer> computeThrustGateCaps() {
        int hops = thrustScopeHops();
        int maxAch = maxAchievableThrust();

        Map<MapNode, Integer> need = new IdentityHashMap<>();
        for (MapNode n : map.allNodes()) {
            int g = gateNeed(n);
            if (g > 0 && g <= maxAch) need.put(n, g);
        }

        Map<MapNode, Integer> caps = new IdentityHashMap<>();
        for (var entry : need.entrySet()) {
            int gn = entry.getValue();
            // Bounded BFS from this gate; every node within `hops` takes the max.
            Map<MapNode, Integer> dist = new IdentityHashMap<>();
            Deque<MapNode> q = new ArrayDeque<>();
            dist.put(entry.getKey(), 0);
            q.add(entry.getKey());
            while (!q.isEmpty()) {
                MapNode u = q.poll();
                int du = dist.get(u);
                caps.merge(u, gn, Math::max);
                if (du == hops) continue;
                for (MapNode v : map.neighboursOf(u)) {
                    if (!dist.containsKey(v)) {
                        dist.put(v, du + 1);
                        q.add(v);
                    }
                }
            }
        }
        return caps;
    }

    /** Scoped-thrust clamp cap for a node (0 = no reachable gate / feature off). */
    private int thrustCapFor(MapNode node) {
        if (thrustGateCap == null) return 0;
        Integer c = thrustGateCap.get(node);
        return c == null ? 0 : c;
    }

    /**
     * Proposal 1 — the global sound thrust clamp. Two frozen-thrust values at
     * or above this cap are behaviourally identical anywhere on the map:
     * <ul>
     *   <li>every clearable gate clears ({@code maxClearableGateNeed});</li>
     *   <li>every belt roll floors to 0 even with the season-RED +2
     *       ({@code maxRadiation + 2}, H10b).</li>
     * </ul>
     * Values below the cap can differ in future cost, so they stay distinct
     * in the {@link FrontierKey}. Unlike the per-node hop-horizon cap this
     * needs no reachability assumption — movement reach is unbounded via
     * free coasting, so only a map-global bound is sound.
     */
    private int computeGlobalThrustCap() {
        int maxAch = maxAchievableThrust();
        int cap = 0;
        boolean oberth = false;
        for (MapNode n : map.allNodes()) {
            int g = gateNeed(n);
            if (g <= maxAch) cap = Math.max(cap, g);
            if (n.radiation() > 0) cap = Math.max(cap, n.radiation() + 2);
            oberth |= n.solarOberth();
        }
        // H8e guard: keyOf keys the afterburn bit (and dead-engine mask) only
        // when the cap is positive. On a map with no clearable gate and no
        // belt the cap would be 0, dropping the ab bit even though a pending
        // Solar-Oberth +1 still distinguishes an afterburned state from its
        // no-AB sibling for the rest of the movement — the no-AB sibling
        // would unsoundly dominate it once the extra burns are spent,
        // pre-harvest. Floor the cap at 1 so the bit stays keyed. The
        // resulting min(thrust, 1) merges all positive thrusts, which is
        // sound here: with no gates and no belts, frozen thrust acts only
        // through the burns budget, which is already a dominance axis.
        if (cap == 0 && oberth && anyEngineCanAfterburn()) cap = 1;
        return cap;
    }

    /** True if any engine in this query can afterburn (H3a). */
    private boolean anyEngineCanAfterburn() {
        for (EngineSpec e : engines) {
            if (e.canAfterburn()) return true;
        }
        return false;
    }

    /** Thrust required to clear a node's gate, or 0 if it has none. Sites gate
     *  landing AND liftoff at {@code thrust > siteSize} (need = size+1);
     *  landing-burn nodes gate at {@code thrust ≥ thrustRequired}. */
    private static int gateNeed(MapNode n) {
        if (n.isSite() && n.thrustRequired() > 0) return n.thrustRequired() + 1;
        if (!n.landing().isZero() && n.thrustRequired() > 0) return n.thrustRequired();
        return 0;
    }

    /** Upper bound on net thrust any engine in this query can reach: base +
     *  the lightest weight class (+2 Wisp) + best-case solar zone (solar only)
     *  + afterburn gain (if able). Gates above this are never clearable. */
    private int maxAchievableThrust() {
        int bestSolar = 0;
        for (MapNode n : map.allNodes()) bestSolar = Math.max(bestSolar, n.solarMod());
        int best = 0;
        for (EngineSpec e : engines) {
            int t = e.baseThrust() + 2
                    + (e.solarPowered() ? Math.max(bestSolar, 0) : 0)
                    + (e.canAfterburn() ? e.afterburnThrustGain() : 0);
            best = Math.max(best, t);
        }
        return best;
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
        // Thrust-dominance soundness, in preference order (flags read fresh each
        // run so a single JVM can A/B all modes):
        //   1. Proposal 1 (default): clamped frozen thrust + afterburn bit in the
        //      FrontierKey. Fully sound for thrust (gates AND radiation, no
        //      horizon); Option A's per-node caps are not computed.
        //   2. Option A (thrustKeyed=false, thrustScoped=true): per-node clamped
        //      thrust as an in-bucket dominance axis, hop-horizon scoped.
        //      Kept for A/B probes — known unsound (horizon + radiation).
        //   3. Off (both false): thrust ignored entirely (pre-fix behaviour).
        boolean thrustKeyed =
                Boolean.parseBoolean(System.getProperty("pathfinder.dom.thrustKeyed", "true"));
        this.keyThrustCap = thrustKeyed ? computeGlobalThrustCap() : 0;
        this.bonusSitesSound =
                Boolean.parseBoolean(System.getProperty("pathfinder.dom.bonusSitesSound", "false"));
        this.abLazy =
                Boolean.parseBoolean(System.getProperty("pathfinder.ab.lazy", "true"));
        this.thrustGateCap =
                (!thrustKeyed && Boolean.parseBoolean(
                        System.getProperty("pathfinder.dom.thrustScoped", "true")))
                        ? computeThrustGateCaps()
                        : null;
        // Frontier selection — read fresh each run so a single JVM can A/B the
        // radix and heap frontiers (same pattern as the pathfinder.dom.* flags
        // above). Default is the O(1) radix frontier; setting it false falls
        // back to the reference binary-heap frontier.
        boolean useRadix =
                Boolean.parseBoolean(System.getProperty("pathfinder.queue.radix", "true"));
        bestFound = new HashMap<>();
        queue = useRadix ? new RadixFrontier(MAX_TURNS) : new HeapFrontier();
        if (sink != null) {
            streamNodeId = new IdentityHashMap<>();
            streamNextId = 1;            // 0 is reserved for the synthetic root
            rootEmitted = false;
            sentEndpointCostVectors = new HashMap<>();
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
                start, map.indexOf(start), null, 0, 0, 0, 0, 0,
                initialFuelSteps, Fraction.ZERO, 0,
                1, 0, 0,                       // turn=1 hazards=0 worstRadRoll=0
                1, -1, null, List.of(),        // previousNodeIdx=-1 (no prior move)
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
                        // Lazy-jettison / lazy-afterburn trigger: if this
                        // transition was blocked by a thrust gate, see if a
                        // jettison alt OR the afterburn sibling at the current
                        // turn-start would have unblocked it (both raise the
                        // frozen net thrust that the gate compares against).
                        int req = requiredThrustForTransition(current.node, next.node);
                        if (req > current.thrust) {
                            maybeSpawnJettisonAlt(current);
                            maybeSpawnAfterburnAlt(current);
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
        if (Boolean.getBoolean("pathfinder.stats")) {
            long frontierStates = 0;
            for (List<SearchState> l : bestFound.values()) frontierStates += l.size();
            boolean truncated = iterations > MAX_ITERATIONS;
            System.out.printf(
                    "[pathfinder.stats] iterations=%d enqueued=%d buckets=%d frontierStates=%d truncated=%b%n",
                    Math.min(iterations, MAX_ITERATIONS), statEnqueued,
                    bestFound.size(), frontierStates, truncated);
        }
        return bestFound;
    }

    /** Enqueue a frontier state, tracking its turn for the streaming
     *  year-cursor. The per-turn count lets {@link #search} detect when a
     *  mission year has no remaining active states (and is therefore final).
     *  The count is skipped entirely when not streaming. */
    private void enqueue(SearchState s) {
        queue.add(s);
        statEnqueued++;
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

        // Drop any endpoint whose output-cost vector this node already emitted
        // in an earlier delta, BEFORE assigning ids — so a re-picked
        // representative for an already-sent route neither gets a fresh id nor
        // leaves an orphan subtree on the wire. What remains is exactly the set
        // of routes new to this chunk.
        Map<String, List<SearchState>> fresh = new LinkedHashMap<>();
        for (var entry : endpoints.entrySet()) {
            String nodeId = entry.getKey();
            Set<String> sent = sentEndpointCostVectors.computeIfAbsent(nodeId, k -> new HashSet<>());
            for (SearchState ep : entry.getValue()) {
                if (sent.add(costVectorKey(ep))) {
                    fresh.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(ep);
                }
            }
        }

        List<StreamNode> addedNodes = new ArrayList<>();
        if (!rootEmitted) {
            addedNodes.add(rootStreamNode());
            rootEmitted = true;
        }

        // Assign ids to any on-path states not yet seen, parents before
        // children, emitting each as a flat delta node exactly once.
        for (List<SearchState> states : fresh.values()) {
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

        // Endpoint delta: map node id → tree node ids of the routes new to this
        // chunk (one per not-yet-seen cost vector, per the dedup above).
        Map<String, List<Integer>> addedEndpoints = new LinkedHashMap<>();
        for (var entry : fresh.entrySet()) {
            List<Integer> ids = new ArrayList<>();
            for (SearchState ep : entry.getValue()) {
                ids.add(idOf(ep));
            }
            if (!ids.isEmpty()) addedEndpoints.put(entry.getKey(), ids);
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
            MapNode node = map.nodeByIndex(entry.getKey().nodeIdx());
            if (node == null || node.isDecorative()) continue;
            // Output map keys must remain the String node ids exactly as before.
            String nodeId = node.id();
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
            String key = costVectorKey(s);
            SearchState existing = bestPerCost.get(key);
            if (existing == null || s.visitedNodes < existing.visitedNodes) {
                bestPerCost.put(key, s);
            }
        }
        return new ArrayList<>(bestPerCost.values());
    }

    /** Stable string key for the 4 output-cost dimensions. Two states with the
     *  same key represent the same route cost — collapsed to one in
     *  {@link #keepShortestPerCostVector} and deduped per node by the streaming
     *  delta emitter. */
    private static String costVectorKey(SearchState s) {
        return s.effectiveFuelStepsRemaining() + ":" + s.turn
             + ":" + s.hazards + ":" + s.worstRadRoll;
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

        int prevNode = sameNode ? current.previousNodeIdx : current.nodeIdx;
        // Destination's dense index — resolved ONCE per expansion (same-node
        // reuses the cached current index) and threaded into the expand*
        // constructors, so per-state key building never probes the map.
        int destIdx = sameNode ? current.nodeIdx : map.indexOf(dest);

        // Crossing a one-way edge (aerobrake-style) consumes all free burns:
        // the maneuver is passive, not a powered burn.
        boolean oneWay = !sameNode && "0".equals(map.edgeLabel(current.node, dest));

        if (!sameNode && dest.isBurn()) {
            return expandBurn(current, neighbor, destIdx, prevNode, oneWay);
        }
        if (isPivot(current, neighbor, sameNode)) {
            return expandTurn(current, neighbor, prevNode);
        }
        return expandCruise(current, neighbor, destIdx, prevNode, oneWay, sameNode);
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
        return Math.max(currentWorst, radSeverityUnder(dest, thrust, turn));
    }

    /**
     * Belt-Roll severity on entering {@code dest} under a given frozen
     * {@code thrust} at {@code turn}: raw radiation minus thrust, plus the
     * H10b season-RED +2 penalty, floored at 0. Shared by
     * {@link #updatedRadRoll} (which maxes it into worstRadRoll) and the
     * radiation triggers in {@link #expandBurn}/{@link #expandCruise}, which
     * spawn the afterburn alt when this is still positive (a higher frozen
     * thrust would mitigate a worse roll better).
     */
    private int radSeverityUnder(MapNode dest, int thrust, int turn) {
        int penalty = (seasonAtTurn(turn) == Season.RED) ? 2 : 0;
        return Math.max(dest.radiation() - thrust + penalty, 0);
    }

    /** BURN edge: either a free burn (if available) or a paid burn. */
    private List<SearchState> expandBurn(SearchState current, Neighbor neighbor,
                                         int destIdx, int prevNode, boolean oneWay) {
        MapNode dest = neighbor.node;
        // J3a operational gate: a solar engine is non-operational only in
        // the hard-shutdown zone (Neptune J, solarMod ≤
        // SOLAR_SHUTDOWN_SOLAR_MOD). Elsewhere a recomputed thrust ≤ 0 does
        // NOT bar burn entry: the paid-burn budget is the H3 net thrust
        // frozen at turn start (burnsRemaining), and accumulated Bonus
        // Burns stay spendable even on a non-operational thruster (H2b
        // coasting examples). So entry is never rejected wholesale here —
        // only the paid-burn option (B) and the Oberth bonus pickup
        // require operationality.
        boolean operationalAtDest = engineOperationalAt(current, dest);
        // Lazy-jettison trigger (kept from the old destThrust entry gate):
        // destination-zone thrust ≤ 0 at the current weight means a class
        // drop — possibly onto a different engine — may revive paid burns
        // here, which waiting alone never would.
        if (effectiveThrust(current.engineIndex, dest, current.fuelStepsRemaining) <= 0) {
            maybeSpawnJettisonAlt(current);
        }

        EngineSpec engine = engines.get(current.engineIndex);
        List<SearchState> out = new ArrayList<>(2);
        boolean isHazard  = dest.hazard();
        boolean isLanding = !dest.landing().isZero();
        int newHazards   = current.hazards + (isHazard ? 1 : 0);
        // Belt-roll mitigation uses the H3 FROZEN net thrust, same as cruise
        // entries — destThrust above is only the operational gate (H2b).
        // Recomputing mitigation from current fuel inverted fuel dominance
        // (heavier = worse mitigation) and was the last order-sensitive
        // soundness gap (user-confirmed rules call, 2026-07-04).
        int newRadRoll   = updatedRadRoll(current.worstRadRoll, dest, current.thrust, current.turn);
        // Radiation trigger: entering a belt whose post-mitigation severity is
        // still positive under the current frozen thrust. Afterburn's +gain
        // raises that frozen thrust, so its sibling would suffer a lower (or
        // zero) Belt Roll here — a strictly better radRoll route. (Same-node is
        // impossible in expandBurn: burns are always cross-node entries.)
        if (dest.radiation() > 0
                && radSeverityUnder(dest, current.thrust, current.turn) > 0) {
            maybeSpawnAfterburnAlt(current);
        }
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
        boolean oberthBoost = dest.solarOberth() && operationalAtDest
                && !current.bonusSites.contains(dest.id());
        int oberthBonusBurns = 0;
        List<String> bonusSitesAfterEntry = current.bonusSites;
        if (oberthBoost) {
            oberthBonusBurns = engine.baseThrust();
            if (current.afterburnedThisMove) oberthBonusBurns += 1;
            bonusSitesAfterEntry = new ArrayList<>(current.bonusSites);
            bonusSitesAfterEntry.add(dest.id());
        }
        // Solar-Oberth trigger: entering an Oberth node without having
        // afterburned this movement. The AB sibling harvests +1 Oberth bonus
        // burn (H8e), a strictly better route this movement.
        if (dest.solarOberth() && !current.afterburnedThisMove) {
            maybeSpawnAfterburnAlt(current);
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
                    dest, destIdx, neighbor.direction, current.engineIndex,
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
        // Option B: paid burn — needs an Operational thruster (J2a) plus
        // the H5d gate: fractional partial ≤ remaining
        if (operationalAtDest && current.burnsRemaining > 0
                && !newPartial.isGreaterThan(fuelCap)) {
            // burnsAfter accounts for paid-burn decrement *after* decommission
            // (so post-decommission state has 0 burns, not −1).
            int paidBurnsAfter = willDecommission ? 0 : (current.burnsRemaining - 1);
            out.add(new SearchState(
                    dest, destIdx, neighbor.direction, current.engineIndex,
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
        //
        // Afterburn IS different: its entire value is buying more burns per
        // turn, so a burns-exhausted paid burn is exactly where it pays off —
        // the AB sibling can spend the extra burn to reach dest this turn
        // rather than after a wasted waitTurn (arrive earlier). Fired once per
        // expandBurn attempt (not per neighbor variant), and only when no paid
        // burn was possible because the budget was empty (burnsRemaining == 0);
        // when burns remained but the H5d fuel cap blocked the burn, afterburn
        // can't help — it only reduces fuel.
        if (current.burnsRemaining == 0) {
            maybeSpawnAfterburnAlt(current);
        }
        return out;
    }

    /** Same-node direction change at a Hohmann intersection: pivot or 2-burn force-turn. */
    private List<SearchState> expandTurn(SearchState current, Neighbor neighbor, int prevNode) {
        List<SearchState> out = new ArrayList<>(2);
        MapNode dest = neighbor.node;

        // Option A: use a pivot (free, no fuel)
        if (current.pivotsRemaining > 0) {
            out.add(new SearchState(
                    dest, current.nodeIdx, neighbor.direction, current.engineIndex,
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
                        dest, current.nodeIdx, neighbor.direction, current.engineIndex,
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
            // the engine; afterburn would raise the frozen net thrust the same
            // way. (Jettison doesn't trigger on burnsRemaining < 2 alone:
            // that's just running out of budget this turn.)
            maybeSpawnJettisonAlt(current);
            maybeSpawnAfterburnAlt(current);
        } else {
            // Force-turn skipped for burn BUDGET (thrust > 0, burnsRemaining
            // ≤ 1). Afterburn's extra burn is exactly the missing budget, so
            // the AB sibling may make this force-turn — without this trigger
            // lazy mode loses max-burn sprint routes that need a 2-burn turn
            // late in a movement (verified: eager-only 1:2:0:0 at 728/734).
            maybeSpawnAfterburnAlt(current);
        }
        return out;
    }

    /** Free passage: Lagrange, flyby, radhaz, site, decorative, etc. */
    private List<SearchState> expandCruise(SearchState current, Neighbor neighbor,
                                           int destIdx, int prevNode,
                                           boolean oneWay, boolean sameNode) {
        MapNode dest = neighbor.node;
        // Net thrust is FROZEN for the entire movement per H3 ("calculated
        // once before movement begins"). Base thrust, weight class (post-
        // jettison), solar mod (at START node), afterburn — all snapshotted
        // at turn-start. Crossing a heliocentric-zone boundary mid-move does
        // NOT update net thrust: the H3 calculation uses solar mod at the
        // start node, and that's what gates landing/liftoff/burn-limit/
        // force-turn for the rest of the move (H3, H5c, H6a).
        //
        // Engine operational status is a separate concern (J3a: solar
        // engines shut down in the Neptune zone) — engineOperationalAt
        // gates paid burns and new-bonus pickups, but never the spending
        // of accumulated Bonus Burns (H2b) and never the frozen net thrust.
        EngineSpec engine = engines.get(current.engineIndex);
        int newThrust = current.thrust;
        boolean isHazard = !sameNode && dest.hazard();
        int newHazards   = current.hazards + (isHazard ? 1 : 0);
        int newRadRoll   = sameNode
                ? current.worstRadRoll
                : updatedRadRoll(current.worstRadRoll, dest, newThrust, current.turn);
        // Radiation trigger (cross-node entries only): a belt still severe under
        // the frozen thrust. The afterburn sibling's higher thrust mitigates a
        // lower Belt Roll — a strictly better radRoll route (H10b).
        if (!sameNode && dest.radiation() > 0
                && radSeverityUnder(dest, newThrust, current.turn) > 0) {
            maybeSpawnAfterburnAlt(current);
        }
        int visitedInc   = (!sameNode && !dest.isDecorative()) ? 1 : 0;

        int newFreeBurns = current.freeBurns;
        List<String> newBonusSites = new ArrayList<>(current.bonusSites);
        // H8a: picking up NEW Bonus Burns (flyby, mag-sail belt, Oberth)
        // requires an Operational activated thruster — coasting qualifies,
        // a decommissioned sail or a shutdown-zone solar card does not.
        // Already-accumulated bonuses stay spendable regardless (H2b).
        boolean operationalAtDest = engineOperationalAt(current, dest);
        if (!sameNode && dest.isFlyby() && operationalAtDest) {
            // H8c Venus passage is fully season-gated upstream in
            // expandToNeighbor — by the time we get here, a Venus dest is
            // guaranteed to be in BLUE, so the +flybyBoost is unconditional
            // for all flyby types.
            newFreeBurns += dest.flybyBoost();
            newBonusSites.add(dest.id());
        }
        // H8f Mag Sail: every radiation belt entered confers ONE Bonus Burn,
        // once per belt per movement. The Sails-module text pins the count:
        // "receives one Bonus Burn in the same manner as a flyby (H8b) for
        // each Radiation Belt entered" — NOT the belt's radiation severity.
        if (!sameNode && dest.isRadhaz() && engine.magSail() && operationalAtDest
                && !current.bonusSites.contains(dest.id())) {
            newFreeBurns += 1;
            newBonusSites.add(dest.id());
        }
        // H8e Solar Oberth flyby (cruise entry): some maps tag a non-burn
        // node (e.g. central lagrange of an Oberth structure) with the
        // Oberth flag. Grant base-thrust bonus burns once per move.
        // (expandBurn handles the canonical lander-burn-typed Oberth.)
        if (!sameNode && dest.solarOberth() && operationalAtDest
                && !current.bonusSites.contains(dest.id())) {
            int oberth = engine.baseThrust();
            if (current.afterburnedThisMove) oberth += 1;
            newFreeBurns += oberth;
            newBonusSites.add(dest.id());
        }
        // Solar-Oberth trigger (cross-node entries only): entering an Oberth
        // node without having afterburned this movement. The AB sibling
        // harvests +1 Oberth bonus burn (H8e), a strictly better route.
        if (!sameNode && dest.solarOberth() && !current.afterburnedThisMove) {
            maybeSpawnAfterburnAlt(current);
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
                dest, destIdx, neighbor.direction, current.engineIndex,
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
     * <p>For each engine we always emit the afterburn-off branch (branch A).
     * The afterburn-on branch (branch B) is governed by {@link #abLazy}:
     * <ul>
     *   <li>{@code abLazy == false} (eager): when the engine can afterburn and
     *       fuel covers the cost we ALSO emit an afterburn-on branch here, so
     *       every AB-capable engine forks at every turn-start. This is the
     *       historical behaviour, kept behind the flag for A/B comparison.</li>
     *   <li>{@code abLazy == true} (default): branch B is suppressed; the AB
     *       sibling is instead spawned on demand by
     *       {@link #maybeSpawnAfterburnAlt} only when the search reaches a point
     *       where the +gain could change a route. Benchmarks showed the eager
     *       fork costs +60–100% search iterations even where AB never matters.</li>
     * </ul>
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

            // Branch B: afterburn. Eager only — under lazy mode this branch is
            // spawned on demand by maybeSpawnAfterburnAlt from the no-AB sibling
            // instead of unconditionally here.
            if (!abLazy) {
                int cost = engine.afterburnFuelCost();
                if (engine.canAfterburn() && newFuelSteps >= cost) {
                    int abThrust = noAbThrust + engine.afterburnThrustGain();
                    out.add(buildTurnStart(current, i, engine, abThrust,
                            newFuelSteps - cost, jettisoned, turn, parent, /*ab=*/ true));
                }
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
                current.node, current.nodeIdx, null, engineIndex,
                burns, engine.bonusPivots(), 0, thrust,
                fuelSteps, Fraction.ZERO, jettisoned,
                turn,
                current.hazards, current.worstRadRoll,
                current.visitedNodes, -1, parent, List.of(),
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
        // operational regardless of base thrust. Returning 0 zeroes the
        // paid-burn budget of movements that START here; entry-time
        // operationality is gated separately by engineOperationalAt.
        if (engine.solarPowered() && node.solarMod() <= SOLAR_SHUTDOWN_SOLAR_MOD) {
            return 0;
        }
        int wm = FuelStrip.wetMassAt(dryMass, fuelStepsRemaining);
        int weightMod = FuelStrip.weightClassModForWetMass(wm);
        int solar = engine.solarPowered() ? node.solarMod() : 0;
        return engine.baseThrust() + weightMod + solar;
    }

    /**
     * J2/J3 operational status of the state's activated engine while at
     * {@code node}. Non-operational when (a) solar-powered in the hard-
     * shutdown zone (J3a — Neptune J, {@code solarMod ≤
     * SOLAR_SHUTDOWN_SOLAR_MOD}), or (b) already decommissioned this
     * search (H6b sail aerobrake). A merely negative net thrust elsewhere
     * does NOT de-activate the card — it only zeroes the paid-burn budget
     * of movements that start there (H3/H5c). Non-operational blocks paid
     * burns (J2a) and picking up new Bonus Burns (H8a), but never the
     * spending of bonuses accumulated earlier in the move (H2b).
     */
    private boolean engineOperationalAt(SearchState state, MapNode node) {
        if ((state.decommissionedEngines & (1L << state.engineIndex)) != 0) return false;
        EngineSpec engine = engines.get(state.engineIndex);
        return !(engine.solarPowered() && node.solarMod() <= SOLAR_SHUTDOWN_SOLAR_MOD);
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
     * Lazily enqueue the afterburn (AB) sibling of {@code current}'s most
     * recent turn-start, when that turn-start could afterburn but did not.
     * The AB sibling is the exact state eager branch B in
     * {@link #addTurnStartStates} would have emitted: same node / turn /
     * parent / engine / jettison amount as the no-AB turn-start, thrust raised
     * by {@code afterburnThrustGain} (frozen for the whole movement), burns
     * budget = {@code max(thrust, 0)}, fuel reduced by {@code afterburnFuelCost},
     * and the afterburn bit set. Weight class is NOT recomputed from the reduced
     * fuel — the AB gain layers on top of the pre-AB net thrust (project rule,
     * H3a), so the thrust must be {@code ts.thrust + gain}, never re-derived from
     * {@code ts.fuelStepsRemaining - cost}.
     *
     * <p><b>Trigger taxonomy.</b> Afterburn helps a route through exactly three
     * frozen-for-the-movement effects: it raises net thrust by the gain, it
     * raises the per-turn burns budget by the gain, and (H8e) it adds +1 to any
     * Solar-Oberth bonus this movement. The callers therefore fire this on the
     * situations where one of those effects could unblock or improve a route:
     * <ul>
     *   <li><b>thrust-gate</b> — an {@code isAllowed} rejection where the
     *       transition's required thrust exceeds the frozen net thrust
     *       (landing / liftoff / landing-burn); the +gain might clear it;</li>
     *   <li><b>dead force-turn</b> — {@link #expandTurn} hits {@code thrust ≤ 0}
     *       so no force-turn is possible; the +gain might revive the engine;</li>
     *   <li><b>burns-exhausted</b> — {@link #expandBurn} wants a paid burn but
     *       {@code burnsRemaining == 0}; the +gain buys more burns this turn, so
     *       the destination can be reached earlier (fewer turns);</li>
     *   <li><b>radiation</b> — entering a radhaz whose post-mitigation Belt-Roll
     *       severity is still positive under the frozen thrust; the +gain
     *       mitigates a worse roll better (H10b);</li>
     *   <li><b>Oberth</b> — entering a Solar-Oberth node without having
     *       afterburned this movement; the AB variant harvests +1 bonus
     *       burn (H8e).</li>
     * </ul>
     *
     * <p><b>Over-firing is safe.</b> The AB sibling is deduped by the standard
     * Pareto frontier check in {@link #addIfBest}, so triggering many times for
     * the same turn-start enqueues its subtree at most once. Under-firing, by
     * contrast, silently loses routes, so callers err toward triggering. Because
     * the trigger keys off {@code current.turnStart()} generically, it fires
     * correctly for states descending from lazy-jettison alternatives too (whose
     * turn-start is the jettison alt, itself AB-capable).
     */
    private void maybeSpawnAfterburnAlt(SearchState current) {
        SearchState ts = current.turnStart();
        if (ts.afterburnedThisMove) return;            // already afterburning
        EngineSpec engine = engines.get(ts.engineIndex);
        if (!engine.canAfterburn()) return;
        int cost = engine.afterburnFuelCost();
        if (ts.fuelStepsRemaining < cost) return;      // can't afford the burn
        int abThrust = ts.thrust + engine.afterburnThrustGain();
        SearchState ab = buildTurnStart(ts, ts.engineIndex, engine, abThrust,
                ts.fuelStepsRemaining - cost, ts.jettisonedAtTurnStart,
                ts.turn, ts.parent, /*ab=*/ true);
        if (addIfBest(ab, bestFound)) enqueue(ab);
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
        return from.previousNodeIdx != -1
            && to.nodeIdx == from.previousNodeIdx;
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
     * (nodeIdx, engineIndex, entryLabel, prevNodeIdx) by construction,
     * so {@link SearchState#notDominatedBy}'s context early-returns are
     * structurally dead here — every call exercises only the cost dims.
     */
    private boolean addIfBest(SearchState state, Map<FrontierKey, List<SearchState>> bestFound) {
        FrontierKey key = keyOf(state);
        List<SearchState> existing = bestFound.computeIfAbsent(key, k -> new ArrayList<>());

        // Thrust handling inside the bucket:
        //  - Proposal 1 (thrust in key): every bucket member shares the same
        //    clamped thrust by construction, so the in-bucket thrust axis is
        //    structurally dead — pass 0 to skip it.
        //  - Option A: every bucket member shares this node, so the scoped
        //    clamp cap is the same for all comparisons here — look it up once.
        final int cap = keyThrustCap > 0 ? 0 : thrustCapFor(state.node);

        // Reject if any existing state dominates or equals the new state.
        final boolean sites = bonusSitesSound;
        for (SearchState e : existing) {
            if (!state.notDominatedBy(e, cap, sites) || state.equalState(e, cap, sites)) {
                return false;
            }
        }

        // Evict any existing states now dominated by the new state.
        existing.removeIf(e -> !e.notDominatedBy(state, cap, sites));

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
