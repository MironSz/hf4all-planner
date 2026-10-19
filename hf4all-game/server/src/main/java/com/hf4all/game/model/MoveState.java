package com.hf4all.game.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A movement in progress ({@code G.move}, H). {@code spent} is the fuel
 * expended so far in (possibly fractional) steps; it is rounded up when the
 * move ends (H5b). The engine does the arithmetic with the planner's exact
 * {@code Fraction}; the JSON keeps plain numbers.
 */
public final class MoveState {
    public int thrusterUid;
    public List<Integer> chainUids = new ArrayList<>();
    public String name;
    public int net;
    public int base;
    public double fuelCons;
    public String grade;
    public boolean gradeOk;
    public int burnLimit;
    public int burnsUsed;
    public int bonus;
    public int bonusPivots;
    public double spent;
    public int fuelAvail;
    public boolean afterburn;
    public String node;
    public String prev;
    public String prevFirst;
    /** Lane label the Rocket arrived on at {@code node} (Hohmann direction), or null. */
    public String inLab;
    public String start;
    public List<String> path = new ArrayList<>();
    public List<RouteRef> routes = new ArrayList<>();
    public boolean coastOnly;
    public boolean oberth;
    public List<String> belts = new ArrayList<>();
    public boolean magSail;
    public boolean sail;
    public String weightClass;
    public boolean landed;
    /** Only set on evaluation copies: is the activated thruster still Operational? */
    public Boolean thrusterAlive;
    /** Pushed by a Powersat (H3d); set only when true. Keeps Solar-Powered cards alive in the Neptune zone. */
    public Boolean pushed;

    /** Shallow copy with copied lists (the JS {@code Object.assign({}, m)}). */
    public MoveState copy() {
        MoveState m = new MoveState();
        m.thrusterUid = thrusterUid;
        m.chainUids = new ArrayList<>(chainUids);
        m.name = name;
        m.net = net;
        m.base = base;
        m.fuelCons = fuelCons;
        m.grade = grade;
        m.gradeOk = gradeOk;
        m.burnLimit = burnLimit;
        m.burnsUsed = burnsUsed;
        m.bonus = bonus;
        m.bonusPivots = bonusPivots;
        m.spent = spent;
        m.fuelAvail = fuelAvail;
        m.afterburn = afterburn;
        m.node = node;
        m.prev = prev;
        m.prevFirst = prevFirst;
        m.inLab = inLab;
        m.start = start;
        m.path = new ArrayList<>(path);
        m.routes = new ArrayList<>(routes);
        m.coastOnly = coastOnly;
        m.oberth = oberth;
        m.belts = new ArrayList<>(belts);
        m.magSail = magSail;
        m.sail = sail;
        m.weightClass = weightClass;
        m.landed = landed;
        m.thrusterAlive = thrusterAlive;
        m.pushed = pushed;
        return m;
    }

    /** A route already flown: from, to and the decorative points in between (for drawing). */
    public static final class RouteRef {
        public String from;
        public String to;
        public List<String> via = new ArrayList<>();
        public RouteRef() {}
        public RouteRef(String from, String to, List<String> via) { this.from = from; this.to = to; this.via = new ArrayList<>(via); }
    }
}
