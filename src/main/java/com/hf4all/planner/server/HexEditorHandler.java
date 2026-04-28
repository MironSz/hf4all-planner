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
 * Single handler for /hex-editor and its sub-paths.
 *
 * <ul>
 *   <li>GET  /hex-editor         — editor HTML page</li>
 *   <li>GET  /hex-editor/hexes   — fresh hexes.json with edited overrides
 *       merged on top, so the algorithm output stays the source of truth
 *       for everything the user has not manually fixed.</li>
 *   <li>POST /hex-editor/save    — body is just the user-edited subset
 *       (entries with {@code edited:true}); written to hexes-edited.json.</li>
 * </ul>
 *
 * <p>Both reads go straight to the project source tree on disk, NOT to the
 * classpath snapshot baked at build time. That way running the Python
 * extractor or editing the HTML/JS in your editor is reflected on the next
 * request without a Maven rebuild. All responses are sent with cache-busting
 * headers so the browser does not retain a stale copy either.
 */
public final class HexEditorHandler implements HttpHandler {

    private static final Path EDITED_PATH   = Path.of("src", "main", "resources", "static", "hexes-edited.json");
    private static final Path ORIGINAL_PATH = Path.of("src", "main", "resources", "static", "hexes.json");
    private static final Path HTML_PATH     = Path.of("src", "main", "resources", "static", "hex-editor.html");

    private static final String NO_CACHE = "no-store, no-cache, must-revalidate, max-age=0";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path   = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if ((path.equals("/hex-editor") || path.equals("/hex-editor/")) && "GET".equals(method)) {
                serveDisk(exchange, HTML_PATH, "text/html; charset=UTF-8", "static/hex-editor.html");
            } else if ("/hex-editor/hexes".equals(path) && "GET".equals(method)) {
                serveHexes(exchange);
            } else if ("/hex-editor/save".equals(path) && "POST".equals(method)) {
                if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    byte[] msg = "hex-editor edits are only accepted from localhost".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                    exchange.sendResponseHeaders(403, msg.length);
                    try (var os = exchange.getResponseBody()) { os.write(msg); }
                    return;
                }
                saveHexes(exchange);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } catch (IOException e) {
            byte[] msg = ("Error: " + e.getMessage()).getBytes();
            exchange.sendResponseHeaders(500, msg.length);
            try (var os = exchange.getResponseBody()) { os.write(msg); }
        }
    }

    /**
     * Read from the project source tree if available, otherwise fall back to the
     * classpath copy (so the server still works when run from a packaged jar).
     */
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

    private void serveHexes(HttpExchange exchange) throws IOException {
        byte[] freshBytes = readDiskOrClasspath(ORIGINAL_PATH, "static/hexes.json");
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
     * Start from the fresh hexes.json object; for every site id present in
     * the edited file, replace the entry. Returns pretty-printed JSON bytes.
     */
    private static byte[] mergeFreshWithEdits(byte[] freshBytes, byte[] editedBytes) {
        Gson gson = new Gson();
        JsonObject fresh = JsonParser.parseString(new String(freshBytes, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject edits;
        try {
            edits = JsonParser.parseString(new String(editedBytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException e) {
            // Edited file unreadable — fall through to fresh untouched.
            return freshBytes;
        }
        for (Map.Entry<String, JsonElement> e : edits.entrySet()) {
            fresh.add(e.getKey(), e.getValue());
        }
        return gson.toJson(fresh).getBytes(StandardCharsets.UTF_8);
    }

    private void saveHexes(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Files.createDirectories(EDITED_PATH.getParent());
        Path tmp = EDITED_PATH.resolveSibling("hexes-edited.json.tmp");
        Files.write(tmp, body,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, EDITED_PATH,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
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
        try (InputStream is = HexEditorHandler.class.getClassLoader().getResourceAsStream(classpathFallback)) {
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
