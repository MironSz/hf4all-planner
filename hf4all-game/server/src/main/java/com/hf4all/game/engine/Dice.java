package com.hf4all.game.engine;

import com.hf4all.game.model.GameState;
import com.hf4all.game.model.LogEntry;
import com.hf4all.game.model.Roll;

import java.util.List;

/**
 * Randomness and the game log. The RNG is mulberry32 with its state in the
 * game ({@code G.rng}), bit-for-bit the browser's {@code HF.rng}, so a game
 * replays identically on either side. Every die roll is logged, queued for the
 * dice overlay and makes the undo history final.
 */
public final class Dice {

    private Dice() {}

    public static double next(GameState g) {
        g.rng = (g.rng + 0x6D2B79F5L) & 0xFFFFFFFFL;
        int t = (int) g.rng;
        t = (t ^ (t >>> 15)) * (t | 1);
        t ^= t + (t ^ (t >>> 7)) * (t | 61);
        return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    public static int nextInt(GameState g, int n) { return (int) Math.floor(next(g) * n); }

    public static <T> List<T> shuffle(GameState g, List<T> arr) {
        for (int i = arr.size() - 1; i > 0; i--) {
            int j = nextInt(g, i + 1);
            T tmp = arr.get(i);
            arr.set(i, arr.get(j));
            arr.set(j, tmp);
        }
        return arr;
    }

    /** Roll 1d6 (logged, shown, undo becomes final). */
    public static int d6(GameState g, String label, Integer player) {
        int v = 1 + nextInt(g, 6);
        g.rollSeq = (g.rollSeq == null ? 0 : g.rollSeq) + 1;
        g.rolls.add(new Roll(label, v, player, g.rollSeq));
        g.diceLocked = true;
        log(g, (label == null || label.isEmpty() ? "Roll" : label) + ": rolled " + v, player, "dice");
        return v;
    }

    public static void log(GameState g, String text) { log(g, text, null, null); }

    public static void log(GameState g, String text, Integer player, String kind) {
        g.log.add(new LogEntry(g.year, player == null ? g.current : player, text, kind == null ? "info" : kind));
        if (g.log.size() > 800) g.log.subList(0, g.log.size() - 800).clear();
    }
}
