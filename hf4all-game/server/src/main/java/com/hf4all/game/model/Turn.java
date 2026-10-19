package com.hf4all.game.model;

/** Per-turn bookkeeping ({@code G.turn}), reset when a player's turn begins. */
public final class Turn {
    /** Operation done this turn (operation id) or null. */
    public String op;
    public boolean moved;
    public boolean mooncable;
    public boolean crewDirt;
    public boolean felony;
    public boolean murder;
    public int glitchRepairs;
    public OpDetail opDetail;
    /** Site where this turn's landing put the first Humans (glory may be taken). */
    public String gloryEligible;

    public static Turn fresh() { return new Turn(); }

    public static final class OpDetail {
        public int won;
        public int price;
        public OpDetail() {}
        public OpDetail(int won, int price) { this.won = won; this.price = price; }
    }
}
