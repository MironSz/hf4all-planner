package com.hf4all.planner.server.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the search result tree. Serialised to JSON as the API response.
 * Each node carries an integer {@code id} so that the endpoint index can
 * reference specific tree positions without duplicating path data.
 *
 * <p>Mass / fuel fields (HF4A F2/F3, H5):
 * <ul>
 *   <li>{@code fuelStepsRemaining} — burnable fuel left at this point,
 *       measured on the black line of the fuel strip. This is the H5b
 *       round-up-settled integer used by the Pareto frontier.</li>
 *   <li>{@code fuelSpent} — fuel steps consumed from the initial budget
 *       (= {@code initialFuelSteps − fuelStepsRemaining}); integer, kept
 *       as a convenience for callers that prefer "steps spent".</li>
 *   <li>{@code fuelSpentNum/Den} and {@code fuelRemainingNum/Den} —
 *       the EXACT rational fuel state before end-of-move rounding (HF4A
 *       H5b). These carry fractional information for mid-turn arrivals
 *       with a fractional-consumption thruster; on turn-start states they
 *       are integer and equal to the plain {@code fuelSpent} /
 *       {@code fuelStepsRemaining} fields.</li>
 *   <li>{@code wetMass} — integer Wet Mass position (1..32) at this point.</li>
 *   <li>{@code jettisonedHere} — fuel steps the player jettisoned at the
 *       start of the turn this node sits in (0 if none); shown by the UI
 *       to flag jettison events on the path.</li>
 * </ul>
 */
public final class PathNode {

    private final int id;
    private final String nodeId;
    private final int fuelStepsRemaining;
    private final int fuelSpent;
    private final int fuelRemainingNum;
    private final int fuelRemainingDen;
    private final int fuelSpentNum;
    private final int fuelSpentDen;
    private final int wetMass;
    private final int jettisonedHere;
    private final int turns;
    private final int hazards;
    private final int worstRadRoll;
    /** Index into the request's engine list — which engine powered the move into this node. */
    private final int engineIndex;
    private final List<PathNode> children = new ArrayList<>();

    public PathNode(int id, String nodeId,
                    int fuelStepsRemaining, int fuelSpent,
                    int fuelRemainingNum, int fuelRemainingDen,
                    int fuelSpentNum, int fuelSpentDen,
                    int wetMass, int jettisonedHere,
                    int turns, int hazards, int worstRadRoll, int engineIndex) {
        this.id = id;
        this.nodeId = nodeId;
        this.fuelStepsRemaining = fuelStepsRemaining;
        this.fuelSpent = fuelSpent;
        this.fuelRemainingNum = fuelRemainingNum;
        this.fuelRemainingDen = fuelRemainingDen;
        this.fuelSpentNum = fuelSpentNum;
        this.fuelSpentDen = fuelSpentDen;
        this.wetMass = wetMass;
        this.jettisonedHere = jettisonedHere;
        this.turns = turns;
        this.hazards = hazards;
        this.worstRadRoll = worstRadRoll;
        this.engineIndex = engineIndex;
    }

    public int id() { return id; }
    public String nodeId() { return nodeId; }
    public int fuelStepsRemaining() { return fuelStepsRemaining; }
    public int fuelSpent() { return fuelSpent; }
    public int fuelRemainingNum() { return fuelRemainingNum; }
    public int fuelRemainingDen() { return fuelRemainingDen; }
    public int fuelSpentNum() { return fuelSpentNum; }
    public int fuelSpentDen() { return fuelSpentDen; }
    public int wetMass() { return wetMass; }
    public int jettisonedHere() { return jettisonedHere; }
    public int turns() { return turns; }
    public int hazards() { return hazards; }
    public int worstRadRoll() { return worstRadRoll; }
    public int engineIndex() { return engineIndex; }
    public List<PathNode> children() { return children; }

    public void addChild(PathNode child) { children.add(child); }
}
