package com.hf4all.planner.model;

import java.util.Objects;

/**
 * A single node on the HF4A solar map. Immutable.
 *
 * Identity is based solely on {@code id}. Two MapNode instances with the same id
 * are considered equal regardless of their other fields. This is required so that
 * MapNode can be used safely as a Map key in SolarMap's adjacency structures.
 *
 * Field semantics by node type:
 *
 *   BURN      — landing > 0 means the burn is a landing approach (requires thrust);
 *               radiation == 0 unless also a radhaz variant.
 *   HOHMANN   — no extra fields used.
 *   LAGRANGE  — no extra fields used.
 *   SITE      — siteData is non-null; thrustRequired derived from siteData.siteSize().
 *   RADHAZ    — radiation > 0; risk cost = max(radiation - thrust, 0) per the original rules.
 *   FLYBY     — flybyBoost > 0 (free burns granted on entry).
 *   VENUS     — flybyBoost > 0; treated as a togglable flyby.
 *   DECORATIVE — ignored by pathfinder.
 *
 * Any node type may carry an optional {@code label} — a human-readable name used
 * purely for display. In the map data, LEO is a LAGRANGE node and GEO is a BURN
 * node, both carrying a label but not full SiteData.
 */
public final class MapNode {
    private final String   id;
    private final NodeType type;
    private final double   x;           // normalised [0,1] visual position
    private final double   y;
    private final boolean  hazard;      // generic hazard flag (requires a hazard roll)
    private final int      radiation;   // radhaz severity; 0 for non-radhaz nodes
    private final Fraction landing;    // landing burn fraction: 0, 1/2, or 1 (BURN nodes only)
    private final int      thrustReq;   // min thrust to enter this landing burn (from JSON, 0 if none)
    private final int      flybyBoost;  // free burns granted (FLYBY / VENUS nodes only)
    private final int      solarMod;    // solar-power thrust modifier for this heliocentric zone; 0 if unlabeled
    private final boolean  solarOberth; // H8e: this node IS the Solar Oberth flyby
    private final Season   synodic;     // B7h/H6: season required to enter or leave this node; null = no gate
    private final SiteData siteData;    // non-null only for SITE nodes
    private final String   label;       // optional display name for any node type (e.g. "LEO", "GEO")

    private MapNode(Builder b) {
        this.id          = Objects.requireNonNull(b.id,   "id must not be null");
        this.type        = Objects.requireNonNull(b.type, "type must not be null");
        this.x           = b.x;
        this.y           = b.y;
        this.hazard      = b.hazard;
        this.radiation   = b.radiation;
        this.landing     = b.landing;
        this.thrustReq   = b.thrustReq;
        this.flybyBoost  = b.flybyBoost;
        this.solarMod    = b.solarMod;
        this.solarOberth = b.solarOberth;
        this.synodic     = b.synodic;
        this.siteData    = b.siteData;
        this.label       = b.label;
    }

    // --- Accessors ---

    public String   id()          { return id; }
    public NodeType type()        { return type; }
    public double   x()           { return x; }
    public double   y()           { return y; }
    public boolean  hazard()      { return hazard; }
    public int      radiation()   { return radiation; }
    public Fraction landing()    { return landing; }
    public int      flybyBoost()  { return flybyBoost; }
    /**
     * Solar-power thrust modifier for the heliocentric zone this node sits in
     * (e.g. +2 in Mercury, −4 in Saturn). Applied only to solar-powered engines;
     * returns 0 for nodes that carry no zone label (loader default).
     */
    public int      solarMod()    { return solarMod; }
    /**
     * True if this node IS the Solar Oberth flyby (HF4A H8e). Bonus burns
     * here equal the activated thruster's BASE thrust (not net) and grant
     * one extra Bonus Burn when the ship afterburns at the same node.
     */
    public boolean  solarOberth() { return solarOberth; }
    /**
     * HF4A B7h: synodic-comet season requirement. Non-null on the comet
     * SITE itself (data field {@code "synodic": "Red"|"Yellow"|"Blue"})
     * and propagated by the loader to the closest non-decorative
     * neighbour (the "adjacent coloured space" per H6). The pathfinder
     * gates entry and exit by matching {@code seasonAtTurn(turn)}.
     */
    public Season   synodic()     { return synodic; }
    /**
     * Non-null only when type == SITE.
     * Use {@code isSite()} before calling to avoid null checks everywhere.
     */
    public SiteData siteData()    { return siteData; }

    /**
     * Optional human-readable display name. Non-null for all SITE nodes (same as
     * siteData().name()) and for any other named node such as LEO or GEO.
     * Returns null when the node has no name.
     */
    public String label() { return label != null ? label : (siteData != null ? siteData.name() : null); }

    // --- Convenience predicates ---

    public boolean isSite()       { return type == NodeType.SITE; }
    public boolean isBurn()       { return type == NodeType.BURN; }
    public boolean isHohmann()    { return type == NodeType.HOHMANN; }
    public boolean isLagrange()   { return type == NodeType.LAGRANGE; }
    public boolean isRadhaz()     { return type == NodeType.RADHAZ; }
    public boolean isFlyby()      { return type == NodeType.FLYBY || type == NodeType.VENUS; }
    public boolean isDecorative() { return type == NodeType.DECORATIVE; }

    /** Minimum thrust required to enter this node (0 if unrestricted). */
    public int thrustRequired() {
        if (type == NodeType.SITE) return siteData.thrustRequired();
        if (!landing.isZero()) return thrustReq;
        return 0;
    }

    // --- Identity ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MapNode n)) return false;
        return id.equals(n.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        String name = label();
        if (name != null) return "MapNode[" + type + ":" + name + "]";
        return "MapNode[" + type + ":" + id + "]";
    }

    // --- Builder ---

    public static Builder builder(String id, NodeType type) {
        return new Builder(id, type);
    }

    public static final class Builder {
        private final String   id;
        private final NodeType type;
        private double   x           = 0.0;
        private double   y           = 0.0;
        private boolean  hazard      = false;
        private int      radiation   = 0;
        private Fraction landing     = Fraction.ZERO;
        private int      thrustReq   = 0;
        private int      flybyBoost  = 0;
        private int      solarMod    = 0;
        private boolean  solarOberth = false;
        private Season   synodic     = null;
        private SiteData siteData    = null;
        private String   label       = null;

        private Builder(String id, NodeType type) {
            this.id   = id;
            this.type = type;
        }

        public Builder position(double x, double y) { this.x = x; this.y = y; return this; }
        public Builder hazard(boolean hazard)        { this.hazard = hazard;   return this; }
        public Builder radiation(int radiation)      { this.radiation = radiation; return this; }
        public Builder landing(Fraction landing)     { this.landing = landing; return this; }
        public Builder thrustReq(int thrustReq)      { this.thrustReq = thrustReq; return this; }
        public Builder flybyBoost(int boost)         { this.flybyBoost = boost; return this; }
        public Builder solarMod(int solarMod)        { this.solarMod = solarMod; return this; }
        public Builder solarOberth(boolean solarOberth) { this.solarOberth = solarOberth; return this; }
        public Builder synodic(Season synodic)       { this.synodic = synodic; return this; }
        public Builder siteData(SiteData siteData)   { this.siteData = siteData; return this; }
        public Builder label(String label)           { this.label = label;       return this; }

        public MapNode build() {
            return new MapNode(this);
        }
    }
}
