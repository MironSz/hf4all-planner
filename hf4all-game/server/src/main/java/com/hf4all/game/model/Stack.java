package com.hf4all.game.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A stack of cards on a playmat slot. LEO has neither {@code node} nor
 * {@code fts} (its FTs are the player's Aquas); only the Rocket has a Fuel Strip.
 */
public final class Stack {
    public String node;
    public List<CardInst> cards = new ArrayList<>();
    public Integer fts;
    public Fuel fuel;
    public boolean glitch;
    /** Engines built for this stack as a Rocket (null when none); they travel with the stack. */
    public List<Engine> engines;

    public Stack() {}

    /** A new Rocket / Outpost on a Space (E2c); the Rocket starts with no Fuel Strip state. */
    public static Stack at(String node) {
        Stack s = new Stack();
        s.node = node;
        s.fts = 0;
        return s;
    }

    public int ftsOr0() { return fts == null ? 0 : fts; }
}
