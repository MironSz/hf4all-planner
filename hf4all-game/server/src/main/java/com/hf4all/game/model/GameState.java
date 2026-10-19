package com.hf4all.game.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole game, field for field the JSON object {@code G} of the browser
 * client (same names, same nesting), so the state travels to the client and
 * into save files unchanged. Optional JS fields ({@code uid}, {@code rollSeq},
 * {@code tradeLocks}, {@code lastEvent}) are null until first used.
 */
public final class GameState {
    public int v = 1;
    /** mulberry32 state (unsigned 32-bit). */
    public long rng;
    public long seed;
    public Config config;
    public int year;
    public int cyclePos;
    public int seniority;
    public int firstPlayer;
    public int current;
    public int turnNo;
    public Turn turn;
    public MoveState move;
    public Pending pending;
    public boolean anarchy;
    public List<Player> players = new ArrayList<>();
    /** Patent decks, top card first, keyed by deck id in DECKS order. */
    public Map<String, List<String>> decks = new LinkedHashMap<>();
    /** Site state by node id (created lazily). Iterate with {@code StateOps.siteIds} for JS key order. */
    public Map<String, Site> sites = new LinkedHashMap<>();
    /** Glory chit still on the map, by zone id. */
    public Map<String, Boolean> glory = new LinkedHashMap<>();
    /** Heroism chit -> awarded player (null = unawarded). */
    public Map<String, Integer> heroism = new LinkedHashMap<>();
    public List<LogEntry> log = new ArrayList<>();
    public List<Roll> rolls = new ArrayList<>();
    public Integer rollSeq;
    public boolean diceLocked;
    public GameOver gameOver;
    /** Last issued card-instance uid (ops.newUid). */
    public Integer uid;
    public List<TradeLock> tradeLocks;
    /** Sunspot event to show once (cleared by the client after display). */
    public EventInfo lastEvent;
    /**
     * Cards lost against their owner's will during the current command batch (hazard,
     * flare, radiation...), for the client's explosion effects. Transient: never part
     * of the state JSON or save files.
     */
    public transient List<Boom> booms;

    /** One lost card: whose, from which stack, where, and why ("hazard", "solar flare"...). */
    public record Boom(int player, String key, String node, String card, String why) {}

    public static final class Config {
        public int cycles;
        public boolean heroism;
    }

    public static final class GameOver {
        public List<Score> scores = new ArrayList<>();
        public List<Integer> ranking = new ArrayList<>();
    }

    public static final class TradeLock {
        public String id;
        public int owner;
        public TradeLock() {}
        public TradeLock(String id, int owner) { this.id = id; this.owner = owner; }
    }

    public static final class EventInfo {
        public int roll;
        public String name;
        public String text;
        public EventInfo() {}
        public EventInfo(int roll, String name, String text) { this.roll = roll; this.name = name; this.text = text; }
    }

    public Player me() { return players.get(current); }
}
