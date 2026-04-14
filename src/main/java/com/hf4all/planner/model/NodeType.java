package com.hf4all.planner.model;

public enum NodeType {
    /** Costs 1 burn to traverse. May have landing/thrust requirement. */
    BURN,
    /** Free to pass straight through; turning costs 2 burns or 1 pivot. */
    HOHMANN,
    /** Free direction change, no burn cost. */
    LAGRANGE,
    /** Named destination (asteroid, planet, moon, etc.). */
    SITE,
    /** Radiation hazard zone; risk cost depends on rocket thrust. */
    RADHAZ,
    /** Grants free bonus burns on entry. */
    FLYBY,
    /** Venus flyby — like FLYBY but toggled separately (requires Venus route enabled). */
    VENUS,
    /** Visual/decorative only; ignored by pathfinder. */
    DECORATIVE
}
