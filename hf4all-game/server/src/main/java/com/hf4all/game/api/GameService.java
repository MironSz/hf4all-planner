package com.hf4all.game.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hf4all.game.engine.DemoFactory;
import com.hf4all.game.engine.Scenarios;
import com.hf4all.game.engine.Movement;
import com.hf4all.game.engine.RuleError;
import com.hf4all.game.engine.StateOps;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Roll;
import com.hf4all.game.store.GameStore;
import com.hf4all.game.util.Json;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The games the server holds. Each game keeps its state, a version number
 * (bumped by every accepted command, for optimistic concurrency) and an undo
 * history with the browser's rules: up to 60 steps, cleared when dice are
 * rolled (dice are final) or the turn passes. Every change is saved to disk.
 */
public final class GameService {

    /** HTTP-mappable failures: 404 unknown game, 409 stale version, 403 wrong seat. */
    public static final class ApiError extends RuntimeException {
        public final int status;
        public ApiError(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    static final class Session {
        String id;
        GameState state;
        int version;
        String created;
        String updated;
        final Deque<String> history = new ArrayDeque<>();
    }

    private final GameStore store;
    private final Map<String, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public GameService(GameStore store) { this.store = store; }

    /* ------------------------------------------------------------ lifecycle */

    public JsonObject create(JsonObject cfg) {
        List<StateOps.PlayerConfig> players = new ArrayList<>();
        JsonArray arr = cfg.has("players") && cfg.get("players").isJsonArray() ? cfg.getAsJsonArray("players") : new JsonArray();
        RuleError.check(arr.size() >= 1 && arr.size() <= 5, "A game has 1 to 5 players.");
        for (JsonElement e : arr) {
            JsonObject p = e.getAsJsonObject();
            players.add(new StateOps.PlayerConfig(p.has("name") && !p.get("name").isJsonNull() ? p.get("name").getAsString() : null,
                    p.get("crew").getAsString(), p.has("face") ? p.get("face").getAsInt() : 0));
        }
        long seed = cfg.has("seed") && !cfg.get("seed").isJsonNull() ? cfg.get("seed").getAsLong() : (random.nextInt() & 0x7fffffffL);
        int cycles = cfg.has("cycles") && !cfg.get("cycles").isJsonNull() ? cfg.get("cycles").getAsInt() : 4;
        return open(StateOps.newGame(seed, cycles, players));
    }

    public JsonObject createDemo() { return open(DemoFactory.build()); }

    /** A demo scenario (Scenarios): the new game plus the scenario's title and what to do. */
    public JsonObject createDemo(String scenario) {
        if (scenario == null || scenario.isBlank()) return createDemo();
        JsonObject o = open(Scenarios.build(scenario));
        o.add("scenario", Json.GSON.toJsonTree(Scenarios.info(scenario)));
        return o;
    }

    /** A game file saved by the client (or exported earlier) becomes a server game. */
    public JsonObject importState(JsonElement state) {
        RuleError.check(state != null && state.isJsonObject() && state.getAsJsonObject().has("players") && state.getAsJsonObject().has("decks"), "Not a game file.");
        GameState g;
        try {
            g = Json.GSON.fromJson(state, GameState.class);
        } catch (RuntimeException e) {
            throw new RuleError("Not a game file: " + e.getMessage());
        }
        RuleError.check(g.players != null && !g.players.isEmpty() && g.turn != null, "Not a game file.");
        return open(g);
    }

    private JsonObject open(GameState g) {
        Session s = new Session();
        synchronized (sessions) {
            do s.id = newId(); while (sessions.containsKey(s.id) || store.load(s.id) != null);
            s.state = g;
            s.version = 1;
            s.created = s.updated = Instant.now().toString();
            sessions.put(s.id, s);
        }
        synchronized (s) {
            save(s);
            return response(s, null);
        }
    }

    private String newId() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return b.toString();
    }

    Session session(String id) {
        synchronized (sessions) {
            Session s = sessions.get(id);
            if (s != null) return s;
            JsonObject doc = store.load(id);
            if (doc == null) throw new ApiError(404, "No game " + id + ".");
            s = new Session();
            s.id = id;
            s.version = doc.get("version").getAsInt();
            s.created = doc.has("created") ? doc.get("created").getAsString() : null;
            s.updated = doc.has("updated") ? doc.get("updated").getAsString() : null;
            s.state = Json.GSON.fromJson(doc.get("state"), GameState.class);
            sessions.put(id, s);
            return s;
        }
    }

    private void save(Session s) {
        JsonObject doc = new JsonObject();
        doc.addProperty("id", s.id);
        doc.addProperty("version", s.version);
        doc.addProperty("created", s.created);
        doc.addProperty("updated", s.updated);
        doc.add("state", Json.GSON.toJsonTree(s.state));
        store.save(s.id, doc);
    }

    public List<JsonObject> list() {
        List<JsonObject> out = new ArrayList<>();
        for (String id : store.ids()) {
            try {
                Session s = session(id);
                synchronized (s) { out.add(summary(s)); }
            } catch (RuntimeException e) {
                // An unreadable save is skipped, not fatal.
            }
        }
        out.sort((a, b) -> b.get("updated").getAsString().compareTo(a.get("updated").getAsString()));
        return out;
    }

    private JsonObject summary(Session s) {
        GameState g = s.state;
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id);
        o.addProperty("version", s.version);
        o.addProperty("updated", s.updated == null ? "" : s.updated);
        o.addProperty("year", g.year);
        o.addProperty("totalYears", g.config.cycles * 12);
        o.addProperty("current", g.current);
        o.addProperty("gameOver", g.gameOver != null);
        JsonArray players = new JsonArray();
        for (Player p : g.players) {
            JsonObject q = new JsonObject();
            q.addProperty("name", p.name);
            q.addProperty("faction", p.faction);
            q.addProperty("color", p.color);
            players.add(q);
        }
        o.add("players", players);
        return o;
    }

    public boolean delete(String id) {
        synchronized (sessions) {
            sessions.remove(id);
            return store.delete(id);
        }
    }

    /* ------------------------------------------------------------ playing */

    public JsonObject get(String id) {
        Session s = session(id);
        synchronized (s) { return response(s, null); }
    }

    public JsonObject export(String id) {
        Session s = session(id);
        synchronized (s) { return Json.GSON.toJsonTree(s.state).getAsJsonObject(); }
    }

    /**
     * Run a command. {@code seat} (the acting player, optional in hot-seat play)
     * must be the player whose decision it is; {@code version} (optional) must be current.
     */
    public JsonObject command(String id, Integer seat, Integer version, String cmd, JsonArray args) {
        JsonObject one = new JsonObject();
        one.addProperty("cmd", cmd);
        one.add("args", args == null ? new JsonArray() : args);
        JsonArray batch = new JsonArray();
        batch.add(one);
        return batch(id, seat, version, batch);
    }

    /**
     * Run several commands as one step (one version, one undo step); if any
     * fails, none of them is applied.
     */
    public JsonObject batch(String id, Integer seat, Integer version, JsonArray batch) {
        Session s = session(id);
        synchronized (s) {
            checkVersion(s, version);
            if (batch == null || batch.isEmpty()) throw new ApiError(400, "No command.");
            GameState g = s.state;
            String snap = Json.write(g);
            int rollBefore = g.rollSeq == null ? 0 : g.rollSeq;
            String turnBefore = turnKey(g);
            try {
                for (JsonElement el : batch) {
                    JsonObject c = el.getAsJsonObject();
                    String cmd = c.has("cmd") && !c.get("cmd").isJsonNull() ? c.get("cmd").getAsString() : null;
                    JsonArray args = c.has("args") && c.get("args").isJsonArray() ? c.getAsJsonArray("args") : new JsonArray();
                    Rpc.Entry e = cmd == null ? null : Rpc.get(cmd);
                    if (e == null || e.kind() != Rpc.Kind.COMMAND) throw new ApiError(400, "Unknown command " + cmd + ".");
                    checkSeat(g, seat, cmd, new Args(args));
                    Rpc.call(g, cmd, args);
                    Movement.pruneEngines(g); // an engine whose card left the Rocket is gone
                }
            } catch (RuntimeException ex) {
                s.state = Json.read(snap, GameState.class);
                throw ex;
            }
            int rollAfter = g.rollSeq == null ? 0 : g.rollSeq;
            boolean rolled = rollAfter != rollBefore;
            if (rolled || !turnKey(g).equals(turnBefore)) s.history.clear();
            else {
                s.history.push(snap);
                while (s.history.size() > 60) s.history.removeLast();
            }
            JsonObject events = new JsonObject();
            // Cards lost in this batch, for the explosion effects (never stored).
            events.add("booms", Json.GSON.toJsonTree(g.booms == null ? List.of() : g.booms));
            g.booms = null;
            List<Roll> rolls = new ArrayList<>();
            for (Roll r : g.rolls) if (r.seq > rollBefore) rolls.add(r);
            events.add("rolls", Json.GSON.toJsonTree(rolls));
            // A sunspot event is announced once, then cleared (as the browser did).
            events.add("lastEvent", Json.GSON.toJsonTree(g.lastEvent));
            g.lastEvent = null;
            s.version++;
            s.updated = Instant.now().toString();
            save(s);
            return response(s, events);
        }
    }

    public JsonObject undo(String id, Integer version) {
        Session s = session(id);
        synchronized (s) {
            checkVersion(s, version);
            if (s.history.isEmpty()) {
                throw new RuleError(s.state.diceLocked ? "Nothing to undo - dice are final." : "Nothing to undo.");
            }
            s.state = Json.read(s.history.pop(), GameState.class);
            s.version++;
            s.updated = Instant.now().toString();
            save(s);
            return response(s, null);
        }
    }

    /** A read-only query (dialog previews); runs on a copy so it can never change the game. */
    public JsonElement query(String id, String cmd, JsonArray args) {
        Session s = session(id);
        GameState copy;
        synchronized (s) { copy = Json.read(Json.write(s.state), GameState.class); }
        Rpc.Entry e = Rpc.get(cmd);
        if (e == null || e.kind() != Rpc.Kind.QUERY) throw new ApiError(400, "Unknown query " + cmd + ".");
        return Json.GSON.toJsonTree(Rpc.call(copy, cmd, args));
    }

    private static void checkVersion(Session s, Integer version) {
        if (version != null && version != s.version) {
            throw new ApiError(409, "The game changed (version " + s.version + ", yours " + version + "). Reload it.");
        }
    }

    /** Hot-seat play may omit the seat; when given it must be the deciding player. */
    private static void checkSeat(GameState g, Integer seat, String cmd, Args a) {
        if (seat == null) return;
        int who;
        if (cmd.startsWith("negotiation.")) who = a.integer(0, -1);
        else if ((cmd.equals("events.resolveBudgetCut") || cmd.equals("events.resolveGlitch") || cmd.equals("events.resolvePadExplosion"))
                && g.pending != null && g.pending.queue != null) who = g.pending.queue.get(0);
        else if (cmd.equals("ops.resolveAuction") && g.pending != null && g.pending.initiator != null) who = g.pending.initiator;
        else if (cmd.equals("events.awardHeroism")) return; // decided by vote at the table
        else who = g.current;
        if (seat != who) throw new ApiError(403, "Seat " + seat + " cannot do that now (it is player " + who + "'s decision).");
    }

    private static String turnKey(GameState g) { return g.year + ":" + g.current + ":" + g.turnNo; }

    private JsonObject response(Session s, JsonObject events) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id);
        o.addProperty("version", s.version);
        o.addProperty("canUndo", !s.history.isEmpty());
        o.add("state", Json.GSON.toJsonTree(s.state));
        o.add("view", Json.GSON.toJsonTree(ViewModel.of(s.state)));
        if (events != null) o.add("events", events);
        return o;
    }
}
