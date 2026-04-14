package com.hf4all.planner.server.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the search result tree. Serialised to JSON as the API response.
 * Each node carries an integer {@code id} so that the endpoint index can
 * reference specific tree positions without duplicating path data.
 */
public final class PathNode {

    private final int id;
    private final String nodeId;
    private final int fuelSpent;
    private final int turns;
    private final int hazards;
    private final int worstRadRoll;
    private final List<PathNode> children = new ArrayList<>();

    public PathNode(int id, String nodeId, int fuelSpent, int turns, int hazards, int worstRadRoll) {
        this.id = id;
        this.nodeId = nodeId;
        this.fuelSpent = fuelSpent;
        this.turns = turns;
        this.hazards = hazards;
        this.worstRadRoll = worstRadRoll;
    }

    public int id() { return id; }
    public String nodeId() { return nodeId; }
    public int fuelSpent() { return fuelSpent; }
    public int turns() { return turns; }
    public int hazards() { return hazards; }
    public int worstRadRoll() { return worstRadRoll; }
    public List<PathNode> children() { return children; }

    public void addChild(PathNode child) { children.add(child); }
}
