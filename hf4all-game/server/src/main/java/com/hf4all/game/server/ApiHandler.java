package com.hf4all.game.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hf4all.game.api.GameService;
import com.hf4all.game.engine.RuleError;
import com.hf4all.game.engine.Scenarios;
import com.hf4all.game.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON API under {@code /api/}:
 * <pre>
 *   GET    /api/health
 *   GET    /api/games                      list of saved games
 *   POST   /api/games                      {players:[{name,crew,face}], cycles, seed} -> new game
 *   POST   /api/games/demo                 {scenario?} the demo position, or a demo scenario (+ scenario info)
 *   GET    /api/scenarios                  the demo scenarios {id, group, title, text}
 *   POST   /api/games/import               {state} (a saved game file)
 *   GET    /api/games/{id}                 {id, version, canUndo, state, view}
 *   POST   /api/games/{id}/commands        {seat?, version?, cmd, args} -> same + events {rolls, lastEvent, booms}
 *   POST   /api/games/{id}/undo            {version?}
 *   POST   /api/games/{id}/query           {cmd, args} -> {result}
 *   GET    /api/games/{id}/export          the game state as a file
 *   DELETE /api/games/{id}
 *   POST   /api/stop                       (local requests only)
 * </pre>
 * Rule violations answer 422 {error} with the rule's message, stale versions 409.
 */
final class ApiHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());
    private static final Pattern GAME = Pattern.compile("^/api/games/([a-z0-9-]{1,40})(?:/(commands|undo|query|export))?/?$");

    private final GameService games;
    private final Runnable stop;

    ApiHandler(GameService games, Runnable stop) {
        this.games = games;
        this.stop = stop;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            try {
                route(ex, method, path);
            } catch (RuleError e) {
                error(ex, 422, e.getMessage());
            } catch (GameService.ApiError e) {
                error(ex, e.status, e.getMessage());
            } catch (BadRequest e) {
                error(ex, 400, e.getMessage());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "API error on " + method + " " + path, e);
                error(ex, 500, "Server error: " + e);
            }
        }
    }

    private void route(HttpExchange ex, String method, String path) throws IOException {
        if (path.equals("/api/health")) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            json(ex, 200, (JsonElement) o);
            return;
        }
        if (path.equals("/api/stop") && method.equals("POST")) {
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
                error(ex, 403, "The stop endpoint only accepts local requests.");
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            json(ex, 200, (JsonElement) o);
            stop.run();
            return;
        }
        if (path.equals("/api/games") || path.equals("/api/games/")) {
            if (method.equals("GET")) { json(ex, 200, Json.GSON.toJsonTree(games.list())); return; }
            if (method.equals("POST")) { json(ex, 201, games.create(body(ex).getAsJsonObject())); return; }
        }
        if (path.equals("/api/games/demo") && method.equals("POST")) { json(ex, 201, games.createDemo(str(body(ex).getAsJsonObject(), "scenario"))); return; }
        if (path.equals("/api/scenarios") && method.equals("GET")) { json(ex, 200, Json.GSON.toJsonTree(Scenarios.list())); return; }
        if (path.equals("/api/games/import") && method.equals("POST")) {
            JsonObject b = body(ex).getAsJsonObject();
            json(ex, 201, games.importState(b.has("state") ? b.get("state") : b));
            return;
        }
        Matcher m = GAME.matcher(path);
        if (m.matches()) {
            String id = m.group(1), action = m.group(2);
            if (action == null) {
                if (method.equals("GET")) { json(ex, 200, games.get(id)); return; }
                if (method.equals("DELETE")) {
                    JsonObject o = new JsonObject();
                    o.addProperty("deleted", games.delete(id));
                    json(ex, 200, o);
                    return;
                }
            } else if (action.equals("commands") && method.equals("POST")) {
                JsonObject b = body(ex).getAsJsonObject();
                if (b.has("batch") && b.get("batch").isJsonArray()) {
                    json(ex, 200, games.batch(id, integer(b, "seat"), integer(b, "version"), b.getAsJsonArray("batch")));
                    return;
                }
                String cmd = str(b, "cmd");
                if (cmd == null) throw new BadRequest("Missing cmd.");
                json(ex, 200, games.command(id, integer(b, "seat"), integer(b, "version"), cmd, args(b)));
                return;
            } else if (action.equals("undo") && method.equals("POST")) {
                JsonObject b = body(ex).getAsJsonObject();
                json(ex, 200, games.undo(id, integer(b, "version")));
                return;
            } else if (action.equals("query") && method.equals("POST")) {
                JsonObject b = body(ex).getAsJsonObject();
                String cmd = str(b, "cmd");
                if (cmd == null) throw new BadRequest("Missing cmd.");
                JsonObject o = new JsonObject();
                o.add("result", games.query(id, cmd, args(b)));
                json(ex, 200, o);
                return;
            } else if (action.equals("export") && method.equals("GET")) {
                JsonObject state = games.export(id);
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"hf4a-" + id + "-year" + state.get("year").getAsInt() + ".json\"");
                json(ex, 200, state);
                return;
            }
        }
        error(ex, 404, "No such endpoint: " + method + " " + path);
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String m) { super(m, null, false, false); }
    }

    private static JsonElement body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (s.isBlank()) return new JsonObject();
            JsonElement e = JsonParser.parseString(s);
            if (!e.isJsonObject()) throw new BadRequest("The request body must be a JSON object.");
            return e;
        } catch (RuntimeException e) {
            if (e instanceof BadRequest) throw e;
            throw new BadRequest("Malformed JSON: " + e.getMessage());
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static Integer integer(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return null;
        try { return o.get(k).getAsInt(); } catch (RuntimeException e) { throw new BadRequest(k + " must be a number."); }
    }

    private static JsonArray args(JsonObject b) {
        if (!b.has("args") || b.get("args").isJsonNull()) return new JsonArray();
        if (!b.get("args").isJsonArray()) throw new BadRequest("args must be a list.");
        return b.getAsJsonArray("args");
    }

    static void json(HttpExchange ex, int status, JsonElement body) throws IOException {
        StaticHandler.send(ex, status, "application/json; charset=utf-8", GameServer.utf8(Json.write(body)));
    }

    static void json(HttpExchange ex, int status, Object body) throws IOException {
        StaticHandler.send(ex, status, "application/json; charset=utf-8", GameServer.utf8(Json.write(body)));
    }

    static void error(HttpExchange ex, int status, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        json(ex, status, (JsonElement) o);
    }
}
