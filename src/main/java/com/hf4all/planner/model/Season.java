package com.hf4all.planner.model;

import java.util.Locale;

/**
 * HF4A Sol Sunspot Cycle season (K1). The 12 years of the cycle are
 * partitioned into three contiguous coloured sectors of 4 years each,
 * in the order BLUE → YELLOW → RED:
 * <pre>
 *   years 1..4   → BLUE
 *   years 5..8   → YELLOW
 *   years 9..12  → RED
 * </pre>
 *
 * <p>The season at any in-game turn is derived from the player-set
 * starting year (year of turn 1) and the turn count via
 * {@link #atYear(int)}.
 */
public enum Season {
    RED, YELLOW, BLUE;

    private static final Season[] BY_YEAR = {
        // Year 1..12 (index = year - 1)
        BLUE, BLUE, BLUE, BLUE,
        YELLOW, YELLOW, YELLOW, YELLOW,
        RED, RED, RED, RED,
    };

    /** Season for an integer in-cycle year (1..12). Out-of-range years
     *  are wrapped via positive modulo. */
    public static Season atYear(int year) {
        int idx = ((year - 1) % 12 + 12) % 12;
        return BY_YEAR[idx];
    }

    /** Parse a JSON-friendly token into a Season. Accepts any case
     *  ("Red", "RED", "red"). Returns {@code null} for blank / null input. */
    public static Season parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return Season.valueOf(t.toUpperCase(Locale.ROOT));
    }
}
