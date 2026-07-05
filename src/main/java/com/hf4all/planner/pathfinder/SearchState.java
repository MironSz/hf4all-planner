package com.hf4all.planner.pathfinder;

import com.hf4all.planner.model.Fraction;
import com.hf4all.planner.model.MapNode;

import java.util.List;
import java.util.Objects;

/**
 * Internal search state used during the Pareto-optimal BFS.
 * Forms a tree via the {@code parent} pointer; the tree is later converted
 * to a {@link com.hf4all.planner.api.PathNode} tree for the API response.
 *
 * <p>Mass / fuel tracking (HF4A F2/F3, H5):
 * <ul>
 *   <li>{@code fuelStepsRemaining} — chit's distance from Dry Mass on the
 *       black line (the burnable-fuel budget) as of the start of the move.</li>
 *   <li>{@code partialStepsThisMove} — accumulated fractional fuel use within
 *       the current move (H5b). Rounded up and applied to
 *       {@code fuelStepsRemaining} at the end of each move (waitTurn).</li>
 *   <li>{@code jettisonedAtTurnStart} — fuel steps the player jettisoned at
 *       the start of this turn (display + Pareto tiebreaker; usually 0).</li>
 * </ul>
 *
 * <p>Dry Mass is constant for a search (no card jettison in v1) and lives
 * on the {@link Pathfinder} instance, not in this state.
 */
final class SearchState {

    final MapNode node;
    final int nodeIdx;            // node's dense index in the map (== map.indexOf(node));
                                  // cached at construction so keyOf() pays no map probe
    final String entryLabel;      // direction label at current node (null = no direction)
    final int engineIndex;        // index into the engine list
    final int burnsRemaining;     // burns available this turn (= engine net thrust at turn start)
    final int pivotsRemaining;    // bonus pivots available
    final int freeBurns;          // free burns from flyby nodes
    final int thrust;             // current engine's net thrust (snapshot at turn start)

    // Mass / fuel ledger
    final int fuelStepsRemaining;          // chit position (steps from Dry Mass) at start of move
    final Fraction partialStepsThisMove;   // fractional fuel used so far this move (H5b)
    final int jettisonedAtTurnStart;       // fuel steps dumped at this turn's start (0 if none)

    // Cumulative output costs (the Pareto dimensions besides fuel)
    final int turn;
    final int hazards;
    final int worstRadRoll;

    final int visitedNodes;       // number of distinct nodes in path (tiebreaker: fewer is better)
    final int previousNodeIdx;    // index of node we physically moved from (-1 at turn start)
    final SearchState parent;
    final List<String> bonusSites; // flyby nodes visited this turn (for re-entry prevention)

    /**
     * Pointer to the most recent turn-start ancestor (the {@link #waitTurn}-
     * spawned state — or this state itself if it IS a turn-start). Used by
     * the lazy-jettison machinery to know which fuel/thrust the player
     * committed to at the start of the current turn.
     *
     * <p>Stored as {@code null} for turn-start states; {@link #turnStart()}
     * returns {@code this} in that case. Avoids the chicken-and-egg of
     * passing a self-reference through a constructor.
     */
    final SearchState turnStart;

    /**
     * Whether the engine afterburned at the start of this movement (HF4A H3a:
     * once per movement). Set on the AB turn-start state; propagated unchanged
     * through every mid-turn descendant; reset to {@code false} by the next
     * {@link Pathfinder#addTurnStartStates}.
     */
    final boolean afterburnedThisMove;

    /**
     * Bitmask of engine indices that are permanently decommissioned (HF4A
     * H6b sail aerobrake-hazard rule, etc.). Once an engine's bit is set,
     * subsequent turn-starts skip that engine and the ship can only use
     * remaining alive engines (or coast if all are gone). Propagated
     * unchanged through every {@code expand*}.
     */
    final long decommissionedEngines;

    SearchState(MapNode node, int nodeIdx, String entryLabel, int engineIndex,
                int burnsRemaining, int pivotsRemaining, int freeBurns, int thrust,
                int fuelStepsRemaining, Fraction partialStepsThisMove, int jettisonedAtTurnStart,
                int turn, int hazards, int worstRadRoll,
                int visitedNodes, int previousNodeIdx, SearchState parent, List<String> bonusSites,
                SearchState turnStart, boolean afterburnedThisMove,
                long decommissionedEngines) {
        this.node = node;
        this.nodeIdx = nodeIdx;
        this.entryLabel = entryLabel;
        this.engineIndex = engineIndex;
        this.burnsRemaining = burnsRemaining;
        this.pivotsRemaining = pivotsRemaining;
        this.freeBurns = freeBurns;
        this.thrust = thrust;
        this.fuelStepsRemaining = fuelStepsRemaining;
        this.partialStepsThisMove = partialStepsThisMove;
        this.jettisonedAtTurnStart = jettisonedAtTurnStart;
        this.turn = turn;
        this.hazards = hazards;
        this.worstRadRoll = worstRadRoll;
        this.visitedNodes = visitedNodes;
        this.previousNodeIdx = previousNodeIdx;
        this.parent = parent;
        this.bonusSites = bonusSites;
        this.turnStart = turnStart;
        this.decommissionedEngines = decommissionedEngines;
        this.afterburnedThisMove = afterburnedThisMove;
    }

    /** Returns the most recent turn-start state in this state's lineage
     *  (or {@code this} when this state is itself a turn-start). */
    SearchState turnStart() {
        return turnStart != null ? turnStart : this;
    }

    /**
     * "Effective" fuel steps remaining as the chit would settle if the move
     * ended right now (HF4A H5b: round partial up at end of movement).
     * Used by the H5d gate and by the Pareto fuel dimension.
     */
    int effectiveFuelStepsRemaining() {
        return fuelStepsRemaining - partialStepsThisMove.ceilToInt();
    }

    /**
     * Returns true if {@code other} does NOT dominate this state.
     *
     * <p>Callers (the Pathfinder's Pareto frontier) invoke this only on
     * states that already share the comparability context — same direction
     * label, same engine, same previous node — by virtue of the compound
     * frontier key. Context discriminators are therefore intentionally not
     * re-checked here; any call with mismatched context is a caller bug.
     *
     * <p>Domination means: other is ≥ on all benefit dimensions and ≤ on
     * all cost dimensions (with at least one strict inequality, checked
     * externally via {@link #equalState}).
     *
     * <p><b>Time leeway for Sunspot-cycle alignment.</b> Two states that
     * are identical on every dimension EXCEPT {@code turn} are treated as
     * incomparable — neither dominates the other regardless of who has
     * the smaller turn count. This lets the search keep a slower-arriving
     * state at a node when its eventual season-conditioned future cost
     * (Belt-Roll +2 in red H10b, synodic-comet gating B7h, Venus flyby
     * blue-only H8c) may beat the faster state's. States that differ on
     * any other dim fall back to standard Pareto (turn included). In
     * practice this only keeps a handful of extra states: paths through
     * radhaz / hazard nodes already differentiate on those costs, so
     * "identical except turn" mostly fires on safe waitTurn / coasting
     * sequences where holding both options is cheap.
     */
    boolean notDominatedBy(SearchState other, int thrustCap, boolean sitesSound) {

        // {@code other} can dominate this only if it is no worse on EVERY real
        // cost/benefit dimension. The moment it is worse on any, it cannot
        // dominate. We also track whether it is strictly BETTER on some real
        // dimension, because {@code visitedNodes} is a pure tiebreaker — it is
        // consulted ONLY when every real dimension ties. (Treating it as a
        // co-equal Pareto axis would wrongly keep a higher-fuel/longer-path
        // state incomparable to a lower-fuel/shorter one, bloating the frontier.)
        boolean otherStrictlyBetter = false;

        if (other.fuelStepsRemaining < this.fuelStepsRemaining) return true;
        if (other.fuelStepsRemaining > this.fuelStepsRemaining) otherStrictlyBetter = true;

        if (other.partialStepsThisMove.isGreaterThan(this.partialStepsThisMove)) return true;
        if (this.partialStepsThisMove.isGreaterThan(other.partialStepsThisMove)) otherStrictlyBetter = true;

        if (other.hazards > this.hazards) return true;
        if (other.hazards < this.hazards) otherStrictlyBetter = true;

        if (other.worstRadRoll > this.worstRadRoll) return true;
        if (other.worstRadRoll < this.worstRadRoll) otherStrictlyBetter = true;

        if (other.turn > this.turn) return true;
        if (other.turn < this.turn) otherStrictlyBetter = true;

        if (other.pivotsRemaining < this.pivotsRemaining) return true;
        if (other.pivotsRemaining > this.pivotsRemaining) otherStrictlyBetter = true;

        if (other.burnsRemaining < this.burnsRemaining) return true;
        if (other.burnsRemaining > this.burnsRemaining) otherStrictlyBetter = true;

        if (other.freeBurns < this.freeBurns) return true;
        if (other.freeBurns > this.freeBurns) otherStrictlyBetter = true;

        // Option A — scoped, clamped thrust ({@code thrustCap} > 0 only at nodes
        // from which a clearable thrust gate is reachable; passed in by the
        // caller, which knows the bucket's node). Thrust is clamped to the
        // gate's requirement, so states that already clear the hardest reachable
        // gate collapse (no bloat) while a below-threshold state stays
        // incomparable to an above-threshold one (preserving the gate route).
        if (thrustCap > 0) {
            int oThrust = Math.min(other.thrust, thrustCap);
            int tThrust = Math.min(this.thrust, thrustCap);
            if (oThrust < tThrust) return true;
            if (oThrust > tThrust) otherStrictlyBetter = true;
        }

        // Once-per-move sites (flyby / Oberth / mag-sail belts) consumed this
        // movement. {@code other} can dominate only if it has consumed a
        // SUBSET of this state's sites: then every future option of this
        // (re-entry blocks, pending boosts) is also open to other. The boosts
        // other already harvested are reflected in freeBurns above.
        //
        // Guarded by {@code sitesSound} (pathfinder.dom.bonusSitesSound,
        // default OFF): fully sound but measured ~9× slower — incomparable
        // consumed-site sets force both states to be kept, and mid-move
        // states dominate the search. Without it a handful of exotic
        // long-flyby-movement vectors (~0.03% in probes) can be missed.
        if (sitesSound) {
            if (!this.bonusSites.containsAll(other.bonusSites)) return true;
            if (!other.bonusSites.containsAll(this.bonusSites)) otherStrictlyBetter = true;
        }

        // other is no worse than this on every real dimension. If it is strictly
        // better on at least one, it dominates regardless of visitedNodes.
        // Otherwise the real dimensions all tie and visitedNodes breaks the tie:
        // the shorter path wins, so other dominates this only when it is no
        // longer (other.visitedNodes <= this.visitedNodes).
        if (otherStrictlyBetter) return false;
        return other.visitedNodes > this.visitedNodes;
    }

    /** Structural equality on all dimensions used for dominance + identity. */
    boolean equalState(SearchState other, int thrustCap, boolean sitesSound) {
        return this.fuelStepsRemaining == other.fuelStepsRemaining
            && this.partialStepsThisMove.equals(other.partialStepsThisMove)
            && this.turn == other.turn
            && this.hazards == other.hazards
            && this.worstRadRoll == other.worstRadRoll
            && this.pivotsRemaining == other.pivotsRemaining
            && this.burnsRemaining == other.burnsRemaining
            && this.freeBurns == other.freeBurns
            && Objects.equals(this.entryLabel, other.entryLabel)
            && this.engineIndex == other.engineIndex
            && this.visitedNodes == other.visitedNodes
            && this.previousNodeIdx == other.previousNodeIdx
            && (thrustCap == 0
                || Math.min(this.thrust, thrustCap) == Math.min(other.thrust, thrustCap))
            && (!sitesSound
                || (this.bonusSites.containsAll(other.bonusSites)
                    && other.bonusSites.containsAll(this.bonusSites)))
            && this.node.id().equals(other.node.id());
    }
}
