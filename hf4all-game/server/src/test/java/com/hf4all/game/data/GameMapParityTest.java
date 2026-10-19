package com.hf4all.game.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hf4all.game.TestSupport;
import com.hf4all.game.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game map built on the planner's SolarMap must match what the browser's
 * mapgraph.js computes from data/map.js (fixture recorded from it: fixtures/map.json).
 */
class GameMapParityTest {

    static GameMap map;
    static JsonObject fx;

    @BeforeAll
    static void load() {
        map = TestSupport.data().map;
        fx = TestSupport.fixture("map.json").getAsJsonObject();
    }

    @Test
    void sameNodes() {
        JsonObject nodes = fx.getAsJsonObject("nodes");
        assertEquals(new TreeSet<>(nodes.keySet()), new TreeSet<>(map.nodes().keySet()));
        List<String> diffs = new ArrayList<>();
        for (String id : nodes.keySet()) {
            for (String d : TestSupport.diff(nodes.get(id), map.nodeJson(id), 20)) diffs.add(id + " " + d);
            if (diffs.size() > 40) break;
        }
        assertTrue(diffs.isEmpty(), TestSupport.report(diffs));
    }

    @Test
    void sameNamesAndLabels() {
        JsonObject names = fx.getAsJsonObject("names");
        for (String id : names.keySet()) assertEquals(names.get(id).getAsString(), map.name(id), "name of " + id);
        Set<String> js = new TreeSet<>();
        for (JsonElement e : fx.getAsJsonArray("hasLabels")) js.add(e.getAsString());
        Set<String> java = new TreeSet<>();
        for (String id : map.nodes().keySet()) if (map.hasLabels(id)) java.add(id);
        assertEquals(js, java);
    }

    @Test
    void sameRoutes() {
        JsonObject routes = fx.getAsJsonObject("routes");
        List<String> diffs = new ArrayList<>();
        for (String id : routes.keySet()) {
            JsonElement mine = Json.GSON.toJsonTree(map.routes(id));
            for (String d : TestSupport.diff(routes.get(id), mine, 10)) diffs.add(id + " " + d);
            if (diffs.size() > 40) break;
        }
        assertTrue(diffs.isEmpty(), TestSupport.report(diffs));
    }

    @Test
    void sameSynodicGates() {
        JsonObject syn = fx.getAsJsonObject("synodic");
        for (String id : map.nodes().keySet()) {
            String js = syn.has(id) ? syn.get(id).getAsString() : null;
            assertEquals(js, map.synodic(id), "synodic gate at " + id);
        }
    }

    @Test
    void sameAdjacentSites() {
        JsonObject adj = fx.getAsJsonObject("adjacentSites");
        List<String> diffs = new ArrayList<>();
        for (String id : adj.keySet()) {
            JsonArray mine = Json.GSON.toJsonTree(map.adjacentSites(id)).getAsJsonArray();
            if (!mine.equals(adj.get(id))) diffs.add(id + ": " + adj.get(id) + " vs " + mine);
        }
        assertTrue(diffs.isEmpty(), TestSupport.report(diffs));
    }
}
