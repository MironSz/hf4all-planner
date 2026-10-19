package com.hf4all.game.engine;

/**
 * A rule forbids the action. The message names the rule ("burn limit 4
 * reached (H5c)") and is shown to the player; the game state is rolled back.
 */
public final class RuleError extends RuntimeException {

    public RuleError(String message) {
        super(message, null, false, false);
    }

    public static void check(boolean cond, String message) {
        if (!cond) throw new RuleError(message);
    }

    public static RuleError fail(String message) {
        throw new RuleError(message);
    }
}
