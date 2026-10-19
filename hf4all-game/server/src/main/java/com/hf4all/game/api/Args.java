package com.hf4all.game.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hf4all.game.engine.RuleError;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Positional command arguments as sent by the client (the same order as the
 * JS engine functions, without the game). Conversions follow JS semantics:
 * a missing or null argument is "undefined", booleans use JS truthiness.
 */
public final class Args {

    private final JsonArray a;

    public Args(JsonArray a) { this.a = a == null ? new JsonArray() : a; }

    public int size() { return a.size(); }

    public JsonElement get(int i) { return i < a.size() ? a.get(i) : JsonNull.INSTANCE; }

    public boolean has(int i) { return !get(i).isJsonNull(); }

    public String str(int i) {
        JsonElement e = get(i);
        if (e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) return e.getAsString();
        throw new RuleError("Argument " + (i + 1) + " must be text.");
    }

    public Integer integer(int i) {
        JsonElement e = get(i);
        if (e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return (int) Math.floor(p.getAsDouble());
            if (p.isString()) {
                try { return (int) Math.floor(Double.parseDouble(p.getAsString().trim())); } catch (NumberFormatException ignored) { /* below */ }
            }
            if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
        }
        throw new RuleError("Argument " + (i + 1) + " must be a number.");
    }

    public int integer(int i, int dflt) { Integer v = integer(i); return v == null ? dflt : v; }

    public Double number(int i) {
        JsonElement e = get(i);
        if (e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsDouble();
            if (p.isString()) {
                try { return Double.parseDouble(p.getAsString().trim()); } catch (NumberFormatException ignored) { return Double.NaN; }
            }
            if (p.isBoolean()) return p.getAsBoolean() ? 1.0 : 0.0;
        }
        return Double.NaN;
    }

    /** JS truthiness. */
    public boolean bool(int i) { return truthy(get(i)); }

    public static boolean truthy(JsonElement e) {
        if (e == null || e.isJsonNull()) return false;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) { double d = p.getAsDouble(); return d != 0 && !Double.isNaN(d); }
            return !p.getAsString().isEmpty();
        }
        return true;
    }

    public List<Integer> intList(int i) {
        JsonElement e = get(i);
        List<Integer> out = new ArrayList<>();
        if (e.isJsonNull()) return out;
        if (!e.isJsonArray()) throw new RuleError("Argument " + (i + 1) + " must be a list.");
        for (JsonElement x : e.getAsJsonArray()) out.add((int) Math.floor(x.getAsDouble()));
        return out;
    }

    public List<String> strList(int i) {
        JsonElement e = get(i);
        List<String> out = new ArrayList<>();
        if (e.isJsonNull()) return out;
        if (!e.isJsonArray()) throw new RuleError("Argument " + (i + 1) + " must be a list.");
        for (JsonElement x : e.getAsJsonArray()) out.add(x.isJsonNull() ? null : x.getAsString());
        return out;
    }

    public JsonObject obj(int i) {
        JsonElement e = get(i);
        return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    public JsonArray arr(int i) {
        JsonElement e = get(i);
        return e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /** A player argument: an index, or {"$player": index} as recorded by the trace tool. */
    public Player player(GameState g, int i) {
        JsonElement e = get(i);
        int idx;
        if (e.isJsonObject() && e.getAsJsonObject().has("$player")) idx = e.getAsJsonObject().get("$player").getAsInt();
        else idx = integer(i);
        RuleError.check(idx >= 0 && idx < g.players.size(), "No such player.");
        return g.players.get(idx);
    }

    /** A {"k:type": providerIdx} support-chain choice. */
    public static Map<String, Integer> intMap(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        Map<String, Integer> m = new LinkedHashMap<>();
        for (var en : e.getAsJsonObject().entrySet()) {
            if (en.getValue().isJsonNull()) continue;
            m.put(en.getKey(), (int) Math.floor(en.getValue().getAsDouble()));
        }
        return m;
    }
}
