package com.hf4all.game.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hf4all.game.data.CardDb;

/**
 * Effective stats of a card instance (the browser's {@code HF.cards.stats}):
 * a patent side with its deck and, for radiators, the orientation applied;
 * or the Crew's faction side.
 */
public final class CardStats {
    public String deck;
    public String name;
    public Integer mass;
    public Integer rad;
    public String spec;
    public CardDb.Thrust thrust;
    public CardDb.Isru isru;
    public CardDb.Req req;
    public CardDb.Prov prov;
    public CardDb.Mod mod;
    public Boolean solar;
    public Boolean sail;
    public Boolean magSail;
    public JsonObject ab;
    public String text;
    public Integer therms;
    public CardDb.Light light;
    public String cardId;
    public String sideKey;
    public String orient;
    // Crew only
    public Boolean human;
    public String faction;
    public String clout;
    public String engine;
    public String privilege;
    public String crewId;
    public Integer face;

    public int massOr0() { return mass == null ? 0 : mass; }
    public int radOr0() { return rad == null ? 0 : rad; }
    public boolean isSolar() { return Boolean.TRUE.equals(solar); }

    /** Ability flag set (truthy) in {@code ab}. */
    public boolean ab(String key) {
        if (ab == null || !ab.has(key) || ab.get(key).isJsonNull()) return false;
        JsonElement e = ab.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble() != 0;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return !e.getAsString().isEmpty();
        return true;
    }

    public JsonElement abValue(String key) {
        return ab == null || !ab.has(key) || ab.get(key).isJsonNull() ? null : ab.get(key);
    }

    public int abInt(String key) {
        JsonElement e = abValue(key);
        return e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() ? 0 : e.getAsInt();
    }

    public String abString(String key) {
        JsonElement e = abValue(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    public boolean hasReq(String type) {
        if (req == null) return false;
        return switch (type) {
            case "gen" -> req.gen != null && !req.gen.isEmpty();
            case "reactor" -> req.reactor != null && !req.reactor.isEmpty();
            default -> false;
        };
    }

    public java.util.List<String> reqList(String type) {
        if (req == null) return java.util.List.of();
        java.util.List<String> l = type.equals("gen") ? req.gen : req.reactor;
        return l == null ? java.util.List.of() : l;
    }

    public int reqTherms() { return req == null || req.therms == null ? 0 : req.therms; }
}
