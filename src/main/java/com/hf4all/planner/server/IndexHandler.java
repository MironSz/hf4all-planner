package com.hf4all.planner.server;

import com.hf4all.planner.config.Config;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class IndexHandler implements HttpHandler {

    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) path = "/index.html";

        String contentType = contentTypeFor(path);
        if (contentType == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        // Verify resource exists before caching
        byte[] body;
        try {
            body = cache.computeIfAbsent(path, this::loadResource);
        } catch (RuntimeException e) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (path.endsWith(".html")) {
            exchange.getResponseHeaders().set("Cache-Control", Config.cacheHtml());
        } else {
            exchange.getResponseHeaders().set("Cache-Control",
                    "public, max-age=" + Config.cacheAssetsSeconds());
        }
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String contentTypeFor(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=UTF-8";
        if (path.endsWith(".jpg"))  return "image/jpeg";
        if (path.endsWith(".css"))  return "text/css; charset=UTF-8";
        return null;
    }

    private byte[] loadResource(String path) {
        String resource = Config.staticPrefix() + path;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new IOException(resource + " not found on classpath");
            return is.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
