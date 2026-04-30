package com.hf4all.planner.server.handler.editor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Editor for HF4A fuel-strip chit-placement coordinates. Each chit position
 * corresponds to one viable Wet Mass position (1..32) on the rulebook fuel
 * strip image. The user clicks coordinates one-by-one; each click is saved
 * as the next ordered placement.
 *
 * <p>Mirrors {@link CelestialBodyEditorHandler} in structure:
 * <ul>
 *   <li>GET  /chit-editor          — editor HTML page</li>
 *   <li>GET  /chit-editor/chits    — current placements (edited file if it
 *       exists, otherwise the seed file)</li>
 *   <li>POST /chit-editor/save     — body is the user-edited JSON; written
 *       atomically to chits-edited.json. Localhost only.</li>
 * </ul>
 *
 * <p>Data shape on disk:
 * <pre>{@code
 * { "chits": [
 *     { "mass": 1, "x": 0.04, "y": 0.93 },
 *     { "mass": 2, "x": 0.10, "y": 0.85 },
 *     ...
 * ] }
 * }</pre>
 *
 * <p>{@code x} and {@code y} are normalised image coords in [0, 1]. Mass
 * numbers are 1-based and contiguous (no gaps): if the user removes mass
 * #5, all higher mass numbers shift down by one in the saved file. The
 * editor enforces this on the client side so the saved data is always
 * a clean 1..N sequence.
 */
public final class ChitEditorHandler implements HttpHandler {

    private static final Path EDITED_PATH   = Path.of("src", "main", "resources", "static", "data",   "chits-edited.json");
    private static final Path ORIGINAL_PATH = Path.of("src", "main", "resources", "static", "data",   "chits.json");
    private static final Path HTML_PATH     = Path.of("src", "main", "resources", "static", "editor", "chit-editor.html");

    private static final String NO_CACHE = "no-store, no-cache, must-revalidate, max-age=0";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path   = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if ((path.equals("/chit-editor") || path.equals("/chit-editor/")) && "GET".equals(method)) {
                serveDisk(exchange, HTML_PATH, "text/html; charset=UTF-8", "static/editor/chit-editor.html");
            } else if ("/chit-editor/chits".equals(path) && "GET".equals(method)) {
                serveChits(exchange);
            } else if ("/chit-editor/save".equals(path) && "POST".equals(method)) {
                if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    byte[] msg = "chit-editor edits are only accepted from localhost".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                    exchange.sendResponseHeaders(403, msg.length);
                    try (var os = exchange.getResponseBody()) { os.write(msg); }
                    return;
                }
                saveChits(exchange);
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

    /**
     * Returns the edited chits file if it exists on disk; otherwise returns
     * the seed file. Unlike the celestial-body editor, we don't merge: the
     * chit list is a single ordered array, so partial overlays wouldn't be
     * meaningful — the saved file is the canonical state.
     */
    private void serveChits(HttpExchange exchange) throws IOException {
        byte[] body;
        if (Files.exists(EDITED_PATH)) {
            body = Files.readAllBytes(EDITED_PATH);
        } else {
            body = readDiskOrClasspath(ORIGINAL_PATH, "static/data/chits.json");
            if (body == null) {
                // No seed and no edits — return an empty list.
                body = "{\"chits\":[]}".getBytes(StandardCharsets.UTF_8);
            }
        }
        setNoCache(exchange, "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) { os.write(body); }
    }

    private void saveChits(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Files.createDirectories(EDITED_PATH.getParent());
        Path tmp = EDITED_PATH.resolveSibling("chits-edited.json.tmp");
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
        try (InputStream is = ChitEditorHandler.class.getClassLoader().getResourceAsStream(classpathFallback)) {
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
