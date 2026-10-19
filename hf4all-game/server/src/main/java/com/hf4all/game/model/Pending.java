package com.hf4all.game.model;

import java.util.List;

/**
 * A decision that blocks play until resolved ({@code G.pending}):
 * a research auction ({@code type: "auction"}: deck, card, initiator), the
 * Budget Cuts event ({@code type: "budgetCuts"}: queue of players to discard) or
 * a Glitch event tie ({@code type: "glitchEvent"}: queue of players who choose
 * among their tied stacks; {@code stacks} = the choices of the first one, K2b) or
 * a pad explosion tie ({@code type: "padExplosion"}: {@code cards} = "stack:index"
 * of the first player's heaviest LEO cards, K2c).
 */
public final class Pending {
    public String type;
    public String deck;
    public String card;
    public Integer initiator;
    public List<Integer> queue;
    public List<String> stacks;
    public List<String> cards;

    public static Pending auction(String deck, String card, int initiator) {
        Pending p = new Pending();
        p.type = "auction";
        p.deck = deck;
        p.card = card;
        p.initiator = initiator;
        return p;
    }

    public static Pending glitchEvent(List<Integer> queue, List<String> stacks) {
        Pending p = new Pending();
        p.type = "glitchEvent";
        p.queue = queue;
        p.stacks = stacks;
        return p;
    }

    public static Pending padExplosion(List<Integer> queue, List<String> cards) {
        Pending p = new Pending();
        p.type = "padExplosion";
        p.queue = queue;
        p.cards = cards;
        return p;
    }

    public static Pending budgetCuts(List<Integer> queue) {
        Pending p = new Pending();
        p.type = "budgetCuts";
        p.queue = queue;
        return p;
    }
}
