package com.hf4all.game.model;

/** A die roll queued for the dice overlay ({@code G.rolls}). */
public final class Roll {
    public String label;
    public int value;
    public Integer player;
    public int seq;

    public Roll() {}

    public Roll(String label, int value, Integer player, int seq) {
        this.label = label;
        this.value = value;
        this.player = player;
        this.seq = seq;
    }
}
