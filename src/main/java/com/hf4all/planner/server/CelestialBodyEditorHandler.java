package com.hf4all.planner.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Mirror of {@link HexEditorHandler} for celestial bodies (planets + named
 * moons). Three sub-paths under /celestial-body-editor:
 *
 * <ul>
 *   <li>GET  /celestial-body-editor          — editor HTML page</li>
 *   <li>GET  /celestial-body-editor/bodies   — fresh bodies.json with
 *       on-disk edits merged on top, by name</li>
 *   <li>POST /celestial-body-editor/save     — body is the user-edited
 *       subset (entries flagged {@code edited:true}); written to
 *       bodies-edited.json. Localhost only.</li>
 * </ul>
 *
 * <p>Same disk-first read strategy and cache-busting headers as the hex
 * editor — re-running the Python extractor or editing the HTML shows up
 * on the next request without a Maven rebuild, and the browser never
 * caches the JSON.
 */
public final class CelestialBodyEditorHandler implements HttpHandler {

    private static final Path EDITED_PATH   = Path.of("src", "main", "resources", "static", "bodies-edited.json");
    private static final Path ORIGINAL_PATH = Path.of("src", "main", "resources", "static", "bodies.json");
    private static final Path HTML_PATH     = Path.of("src", "main", "resources", "static", "celestial-body-editor.html");

    private static final String NO_CACHE = "no-store, no-cache, must-revalidate, max-age=0";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path   = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if ((path.equals("/celestial-body-editor") || path.equals("/celestial-body-editor/")) && "GET".equals(method)) {
                serveDisk(exchange, HTML_PATH, "text/html; charset=UTF-8", "static/celestial-body-editor.html");
            } else if ("/celestial-body-editor/bodies".equals(path) && "GET".equals(method)) {
                serveBodies(exchange);
            } else if ("/celestial-body-editor/save".equals(path) && "POST".equals(method)) {
                if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    byte[] msg = "celestial-body-editor edits are only accepted from localhost".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                    exchange.sendResponseHeaders(403, msg.length);
                    try (var os = exchange.getResponseBody()) { os.write(msg); }
                    return;
                }
                saveBodies(exchange);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } catch (IOException e) {
            byte[] msg = ("Error: " + e.getMessage()).getBytes();
            exchange.sendResponseHeaders(500, msg.length);
            try (var os = exchange.getResponseBody()) { os.write(msg); }
        }
    }

    private void serveDisk(HttpExchange exchange, Path diskPath, String type, String classpathFallback) throws IOException {
        byte[] body = readDiskOrClasspath(diskPath, classpathFallback);
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        setNoCache(exchange, type);
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) { os.write(body); }
    }

    private void serveBodies(HttpExchange exchange) throws IOException {
        byte[] freshBytes = readDiskOrClasspath(ORIGINAL_PATH, "static/bodies.json");
        if (freshBytes == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        byte[] body;
        if (Files.exists(EDITED_PATH)) {
            body = mergeFreshWithEdits(freshBytes, Files.readAllBytes(EDITED_PATH));
        } else {
            body = freshBytes;
        }
        setNoCache(exchange, "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) { os.write(body); }
    }

    /**
     * Overlay edited entries on top of the fresh dataset, keyed by body name.
     * Edited entries with {@code "removed":true} are deleted from the merged
     * result so the user can prune unwanted seeds without their reappearing.
     */
    private static byte[] mergeFreshWithEdits(byte[] freshBytes, byte[] editedBytes) {
        Gson gson = new Gson();
        JsonObject fresh = JsonParser.parseString(new String(freshBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject edits;
        try {
            edits = JsonParser.parseString(new String(editedBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return freshBytes;
        }
        for (Map.Entry<String, JsonElement> e : edits.entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonObject() && v.getAsJsonObject().has("removed")
                    && v.getAsJsonObject().get("removed").getAsBoolean()) {
                fresh.remove(e.getKey());
            } else {
                fresh.add(e.getKey(), v);
            }
        }
        return gson.toJson(fresh).getBytes(StandardCharsets.UTF_8);
    }

    private void saveBodies(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Files.createDirectories(EDITED_PATH.getParent());
        Path tmp = EDITED_PATH.resolveSibling("bodies-edited.json.tmp");
        Files.write(tmp, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, EDITED_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, EDITED_PATH, StandardCopyOption.REPLACE_EXISTING);
        }
        String msg  = "Saved " + body.length + " bytes to " + EDITED_PATH.toAbsolutePath();
        byte[] resp = msg.getBytes();
        setNoCache(exchange, "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, resp.length);
        try (var os = exchange.getResponseBody()) { os.write(resp); }
    }

    private static byte[] readDiskOrClasspath(Path diskPath, String classpathFallback) throws IOException {
        if (Files.exists(diskPath)) {
            return Files.readAllBytes(diskPath);
        }
        try (InputStream is = CelestialBodyEditorHandler.class.getClassLoader().getResourceAsStream(classpathFallback)) {
            return is == null ? null : is.readAllBytes();
        }
    }

    private static void setNoCache(HttpExchange exchange, String contentType) {
        var h = exchange.getResponseHeaders();
        h.set("Content-Type", contentType);
        h.set("Cache-Control", NO_CACHE);
        h.set("Pragma", "no-cache");
        h.set("Expires", "0");
    }
}
