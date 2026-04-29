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
    final String previousNodeId;  // node we physically moved from (null at turn start)
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

    SearchState(MapNode node, String entryLabel, int engineIndex,
                int burnsRemaining, int pivotsRemaining, int freeBurns, int thrust,
                int fuelStepsRemaining, Fraction partialStepsThisMove, int jettisonedAtTurnStart,
                int turn, int hazards, int worstRadRoll,
                int visitedNodes, String previousNodeId, SearchState parent, List<String> bonusSites,
                SearchState turnStart, boolean afterburnedThisMove) {
        this.node = node;
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
        this.previousNodeId = previousNodeId;
        this.parent = parent;
        this.bonusSites = bonusSites;
        this.turnStart = turnStart;
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
     */
    boolean notDominatedBy(SearchState other) {
        // If other is worse on any cost dimension, it cannot dominate this
        if (other.fuelStepsRemaining < this.fuelStepsRemaining) return true;
        if (other.partialStepsThisMove.isGreaterThan(this.partialStepsThisMove)) return true;
        if (other.hazards > this.hazards) return true;
        if (other.worstRadRoll > this.worstRadRoll) return true;
        if (other.turn > this.turn) return true;
        if (other.pivotsRemaining < this.pivotsRemaining) return true;
        if (other.burnsRemaining < this.burnsRemaining) return true;
        if (other.freeBurns < this.freeBurns) return true;

        // Tiebreaker: fewer visited nodes is better (only matters when all above are equal)
        if (other.visitedNodes > this.visitedNodes) return true;

        return false; // other ≤ this on all dims → other dominates this
    }

    /** Structural equality on all dimensions used for dominance + identity. */
    boolean equalState(SearchState other) {
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
            && Objects.equals(this.previousNodeId, other.previousNodeId)
            && this.node.id().equals(other.node.id());
    }
}
