package com.hf4all.game.data;

import java.nio.file.Path;

/** The static game data (rules, cards, map), loaded once from the hf4all-game directory. */
public final class GameData {

    private static volatile GameData instance;

    public final Path root;
    public final Rules rules;
    public final CardDb cards;
    public final GameMap map;

    private GameData(Path root) {
        this.root = root;
        this.rules = new Rules(root.resolve("data").resolve("rules.js"));
        this.cards = new CardDb(root.resolve("data").resolve("cards.js"));
        this.map = new GameMap(root.resolve("data").resolve("map.js"), rules);
    }

    public static synchronized GameData load(Path root) {
        if (instance == null || !instance.root.equals(root)) instance = new GameData(root);
        return instance;
    }

    public static GameData get() {
        GameData d = instance;
        if (d == null) throw new IllegalStateException("GameData.load(root) was not called");
        return d;
    }
}
