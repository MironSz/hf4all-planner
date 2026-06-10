package com.hf4all.planner.api;

/**
 * One node of the search-result tree in the streaming (delta) wire format:
 * flat, with an explicit {@code parentId} ({@code -1} for the synthetic root)
 * so the client can attach it to the tree it accumulates across chunks.
 *
 * <p>Carries the same display fields as {@link PathNode}; only the linkage
 * differs (a {@code parentId} reference instead of nested {@code children}).
 * Each node is transmitted exactly once over the life of a streaming run.
 */
public record StreamNode(
    int id, int parentId, String nodeId,
    int fuelStepsRemaining, int fuelSpent,
    int fuelRemainingNum, int fuelRemainingDen,
    int fuelSpentNum, int fuelSpentDen,
    int wetMass, int jettisonedHere, int afterburnedHere,
    int turns, int hazards, int worstRadRoll, int engineIndex
) {}
