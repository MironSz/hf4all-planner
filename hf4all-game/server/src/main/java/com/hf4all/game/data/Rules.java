package com.hf4all.game.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hf4all.game.util.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static rule tables from {@code data/rules.js} (read leniently: it is a JS
 * object literal with comments, shared with the browser client).
 */
public final class Rules {

    public final String leo;
    /** The cycler Space next to LEO: shielded from solar flares like LEO (K2d). */
    public final String cycler;
    public final List<Zone> zones = new ArrayList<>();
    public final List<Heroism> heroism = new ArrayList<>();
    public final List<CycleSpace> cycle = new ArrayList<>();
    public final int[] stockPrice;
    public final Map<String, String> spectralTypes = new LinkedHashMap<>();
    public final Map<String, String> playerColors = new LinkedHashMap<>();
    public final Map<String, Privilege> privileges = new LinkedHashMap<>();
    public final List<Operation> operations = new ArrayList<>();

    public record Zone(String id, String name, String symbol, Integer solar, int flare, int gloryX, int gloryY, int gloryVp) {}
    public record Heroism(String id, String name) {}
    public record CycleSpace(String season, boolean event) {}
    public record Privilege(String name, String text) {}
    public record Operation(String id, String name, String ref, String text) {}

    public Rules(Path rulesJs) {
        JsonObject r = Json.readJsData(rulesJs).getAsJsonObject();
        leo = r.get("LEO").getAsString();
        cycler = r.has("CYCLER") ? r.get("CYCLER").getAsString() : null;
        for (JsonElement e : r.getAsJsonArray("zones")) {
            JsonObject z = e.getAsJsonObject();
            var g = z.getAsJsonArray("glory");
            zones.add(new Zone(z.get("id").getAsString(), z.get("name").getAsString(), z.get("symbol").getAsString(),
                    z.get("solar").isJsonNull() ? null : z.get("solar").getAsInt(), z.get("flare").getAsInt(),
                    g.get(0).getAsInt(), g.get(1).getAsInt(), z.get("gloryVp").getAsInt()));
        }
        for (JsonElement e : r.getAsJsonArray("heroism")) {
            JsonObject h = e.getAsJsonObject();
            heroism.add(new Heroism(h.get("id").getAsString(), h.get("name").getAsString()));
        }
        for (JsonElement e : r.getAsJsonObject("cycle").getAsJsonArray("spaces")) {
            JsonObject s = e.getAsJsonObject();
            cycle.add(new CycleSpace(s.get("season").getAsString(), s.has("event") && s.get("event").getAsBoolean()));
        }
        var sp = r.getAsJsonArray("stockPrice");
        stockPrice = new int[sp.size()];
        for (int i = 0; i < sp.size(); i++) stockPrice[i] = sp.get(i).getAsInt();
        r.getAsJsonObject("spectralTypes").entrySet().forEach(en -> spectralTypes.put(en.getKey(), en.getValue().getAsString()));
        r.getAsJsonObject("playerColors").entrySet().forEach(en -> playerColors.put(en.getKey(), en.getValue().getAsString()));
        r.getAsJsonObject("privileges").entrySet().forEach(en -> {
            JsonObject p = en.getValue().getAsJsonObject();
            privileges.put(en.getKey(), new Privilege(p.get("name").getAsString(), p.get("text").getAsString()));
        });
        for (JsonElement e : r.getAsJsonArray("operations")) {
            JsonObject o = e.getAsJsonObject();
            operations.add(new Operation(o.get("id").getAsString(), o.get("name").getAsString(), o.get("ref").getAsString(), o.get("text").getAsString()));
        }
    }

    public Zone zone(String id) {
        for (Zone z : zones) if (z.id().equals(id)) return z;
        return null;
    }

    public int zoneIndex(String id) {
        for (int i = 0; i < zones.size(); i++) if (zones.get(i).id().equals(id)) return i;
        return -1;
    }

    public String seasonAt(int cyclePos) { return cycle.get(cyclePos).season(); }

    public Heroism heroism(String id) {
        for (Heroism h : heroism) if (h.id().equals(id)) return h;
        return null;
    }
}
