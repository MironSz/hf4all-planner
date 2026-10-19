package com.hf4all.game.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hf4all.game.Main;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTTP API end to end on a real server (random port, temporary saves). */
class ApiTest {

    @TempDir Path saves;
    GameServer server;
    final HttpClient http = HttpClient.newHttpClient();

    record Res(int status, JsonElement body) {
        JsonObject obj() { return body.getAsJsonObject(); }
    }

    @BeforeEach
    void start() {
        server = new GameServer(0, Main.gameRoot(), saves);
        server.start();
    }

    @AfterEach
    void stop() { server.stop(); }

    Res call(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonElement e = r.body().isEmpty() || !r.headers().firstValue("Content-Type").orElse("").contains("json") ? null : JsonParser.parseString(r.body());
        return new Res(r.statusCode(), e);
    }

    Res cmd(String id, Integer version, Integer seat, String cmd, String argsJson) throws IOException, InterruptedException {
        String body = "{\"cmd\":\"" + cmd + "\",\"args\":" + argsJson + (version != null ? ",\"version\":" + version : "") + (seat != null ? ",\"seat\":" + seat : "") + "}";
        return call("POST", "/api/games/" + id + "/commands", body);
    }

    static int aquas(JsonObject r, int pi) { return r.getAsJsonObject("state").getAsJsonArray("players").get(pi).getAsJsonObject().get("aquas").getAsInt(); }

    @Test
    void playThroughTheApi() throws Exception {
        assertEquals(200, call("GET", "/api/health", null).status());
        assertEquals(200, call("GET", "/", null).status());

        Res created = call("POST", "/api/games", "{\"seed\":42,\"cycles\":1,\"players\":[{\"name\":\"Ann\",\"crew\":\"CW\",\"face\":0},{\"name\":\"Bob\",\"crew\":\"CP\",\"face\":0},{\"name\":\"Cid\",\"crew\":\"CY\",\"face\":0}]}");
        assertEquals(201, created.status());
        JsonObject g = created.obj();
        String id = g.get("id").getAsString();
        assertEquals(1, g.get("version").getAsInt());
        assertEquals(3, g.getAsJsonObject("state").getAsJsonArray("players").size());
        assertEquals(2, g.getAsJsonObject("state").get("current").getAsInt(), "UN (clout A) starts");
        assertTrue(g.getAsJsonObject("view").getAsJsonObject("ops").getAsJsonObject("income").get("ok").getAsBoolean());
        assertEquals(3, g.getAsJsonObject("view").getAsJsonArray("scores").size());

        // A command bumps the version and can be undone.
        Res inc = cmd(id, 1, 2, "ops.income", "[]");
        assertEquals(200, inc.status());
        assertEquals(2, inc.obj().get("version").getAsInt());
        assertEquals(9, aquas(inc.obj(), 2));
        assertTrue(inc.obj().get("canUndo").getAsBoolean());

        assertEquals(409, cmd(id, 1, 2, "ops.income", "[]").status(), "stale version");
        Res twice = cmd(id, 2, 2, "ops.income", "[]");
        assertEquals(422, twice.status());
        assertEquals("You already performed an Operation this turn (D1b).", twice.obj().get("error").getAsString());
        assertEquals(403, cmd(id, 2, 0, "actions.discardHand", "[0]").status(), "not Ann's turn");
        assertEquals(400, cmd(id, 2, null, "nope.nothing", "[]").status());
        assertEquals(400, cmd(id, 2, null, "actions.decommission", "[2,\"leo\",0,{}]").status(), "internal functions are not commands");

        Res undone = call("POST", "/api/games/" + id + "/undo", "{\"version\":2}");
        assertEquals(200, undone.status());
        assertEquals(3, undone.obj().get("version").getAsInt());
        assertEquals(8, aquas(undone.obj(), 2));
        assertFalse(undone.obj().get("canUndo").getAsBoolean());
        assertEquals(422, call("POST", "/api/games/" + id + "/undo", "{}").status(), "nothing to undo");

        // Queries never change the game.
        Res q = call("POST", "/api/games/" + id + "/query", "{\"cmd\":\"events.score\",\"args\":[2]}");
        assertEquals(200, q.status());
        assertEquals(0, q.obj().getAsJsonObject("result").get("total").getAsInt());
        assertEquals(3, call("GET", "/api/games/" + id, null).obj().get("version").getAsInt());

        // End the year: the cube moves onto an event space, dice are rolled and undo is cleared.
        JsonObject last = null;
        for (int i = 0; i < 3; i++) {
            Res r = cmd(id, null, null, "events.endTurn", "[]");
            assertEquals(200, r.status(), String.valueOf(r.body()));
            last = r.obj();
        }
        assertEquals(2, last.getAsJsonObject("state").get("year").getAsInt());
        JsonArray rolls = last.getAsJsonObject("events").getAsJsonArray("rolls");
        assertEquals(1, rolls.size(), "event roll");
        assertEquals("Event Roll", rolls.get(0).getAsJsonObject().get("label").getAsString());
        assertFalse(last.getAsJsonObject("events").get("lastEvent").isJsonNull(), "event announced once");
        assertTrue(last.getAsJsonObject("state").get("lastEvent").isJsonNull(), "and cleared from the state");
        assertFalse(last.get("canUndo").getAsBoolean());
        int version = last.get("version").getAsInt();

        // The game survives a server restart.
        server.stop();
        server = new GameServer(0, Main.gameRoot(), saves);
        server.start();
        Res again = call("GET", "/api/games/" + id, null);
        assertEquals(200, again.status());
        assertEquals(version, again.obj().get("version").getAsInt());
        assertEquals(last.getAsJsonObject("state"), again.obj().getAsJsonObject("state"));
        JsonArray list = call("GET", "/api/games", null).body().getAsJsonArray();
        assertEquals(1, list.size());
        assertEquals(id, list.get(0).getAsJsonObject().get("id").getAsString());

        // Export and import as a new game; delete.
        Res exported = call("GET", "/api/games/" + id + "/export", null);
        assertEquals(200, exported.status());
        Res imported = call("POST", "/api/games/import", "{\"state\":" + exported.body() + "}");
        assertEquals(201, imported.status());
        assertNotEquals(id, imported.obj().get("id").getAsString());
        assertEquals(422, call("POST", "/api/games/import", "{\"state\":{\"x\":1}}").status());
        assertTrue(call("DELETE", "/api/games/" + imported.obj().get("id").getAsString(), null).obj().get("deleted").getAsBoolean());
        assertEquals(404, call("GET", "/api/games/" + imported.obj().get("id").getAsString(), null).status());
    }

    @Test
    void demoAndMovement() throws Exception {
        Res demo = call("POST", "/api/games/demo", null);
        assertEquals(201, demo.status());
        JsonObject d = demo.obj();
        String id = d.get("id").getAsString();
        JsonArray thrust = d.getAsJsonObject("view").getAsJsonArray("thrustOptions");
        assertTrue(thrust.size() >= 2, "Amelia's Rocket has thrust triangles");
        int idx = -1;
        for (JsonElement e : thrust) if (e.getAsJsonObject().get("ok").getAsBoolean()) { idx = e.getAsJsonObject().get("idx").getAsInt(); break; }
        assertTrue(idx >= 0);
        Res started = cmd(id, d.get("version").getAsInt(), 0, "movement.start", "[" + idx + ",false,{}]");
        assertEquals(200, started.status(), String.valueOf(started.body()));
        JsonObject view = started.obj().getAsJsonObject("view");
        assertFalse(view.getAsJsonArray("moveOptions").isEmpty());
        assertTrue(view.getAsJsonObject("canEnd").get("ok").getAsBoolean());
        Res ended = cmd(id, null, 0, "movement.end", "[]");
        assertEquals(200, ended.status());
        assertTrue(ended.obj().getAsJsonObject("state").getAsJsonObject("turn").get("moved").getAsBoolean());
    }
}
