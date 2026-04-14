package com.hf4all.planner.pathfinder;

import com.hf4all.planner.model.MapNode;

import java.util.List;
import java.util.Objects;

/**
 * Internal search state used during the Pareto-optimal BFS.
 * Forms a tree via the {@code parent} pointer; the tree is later converted
 * to a {@link com.hf4all.planner.server.dto.PathNode} tree for the API response.
 */
final class SearchState {

    final MapNode node;
    final String entryLabel;      // direction label at current node (null = no direction)
    final int engineIndex;        // index into the engine list
    final int burnsRemaining;     // burns available this turn (= engine thrust at turn start)
    final int pivotsRemaining;    // bonus pivots available
    final int freeBurns;          // free burns from flyby nodes
    final int thrust;             // current engine's net thrust

    // Cumulative output costs (the 4 Pareto dimensions)
    final int fuelSpent;
    final int turn;
    final int hazards;
    final int worstRadRoll;

    final int visitedNodes;       // number of distinct nodes in path (tiebreaker: fewer is better)
    final String previousNodeId;  // node we physically moved from (null at turn start)
    final SearchState parent;
    final List<String> bonusSites; // flyby nodes visited this turn (for re-entry prevention)

    SearchState(MapNode node, String entryLabel, int engineIndex,
                int burnsRemaining, int pivotsRemaining, int freeBurns, int thrust,
                int fuelSpent, int turn, int hazards, int worstRadRoll,
                int visitedNodes, String previousNodeId, SearchState parent, List<String> bonusSites) {
        this.node = node;
        this.entryLabel = entryLabel;
        this.engineIndex = engineIndex;
        this.burnsRemaining = burnsRemaining;
        this.pivotsRemaining = pivotsRemaining;
        this.freeBurns = freeBurns;
        this.thrust = thrust;
        this.fuelSpent = fuelSpent;
        this.turn = turn;
        this.hazards = hazards;
        this.worstRadRoll = worstRadRoll;
        this.visitedNodes = visitedNodes;
        this.previousNodeId = previousNodeId;
        this.parent = parent;
        this.bonusSites = bonusSites;
    }

    /**
     * Returns true if {@code other} does NOT dominate this state.
     * Two states can only dominate each other when they share the same
     * direction label and engine index.
     *
     * Domination means: other is ≤ on all cost dimensions and ≥ on all
     * resource dimensions (with at least one strict inequality — but that
     * is checked externally via {@link #equalState}).
     */
    boolean notDominatedBy(SearchState other) {
        // States with different direction, engine, or entry edge are incomparable
        if (!Objects.equals(this.entryLabel, other.entryLabel)) return true;
        if (this.engineIndex != other.engineIndex) return true;
        if (!Objects.equals(this.previousNodeId, other.previousNodeId)) return true;

        // If other is worse on any dimension, it cannot dominate this
        if (other.fuelSpent > this.fuelSpent) return true;
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

    /**
     * Structural equality on all state dimensions (used to detect duplicates).
     */
    boolean equalState(SearchState other) {
        return this.fuelSpent == other.fuelSpent
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
