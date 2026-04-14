package com.hf4all.planner.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;

public final class MapHandler implements HttpHandler {

    private byte[] cachedJson;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        byte[] body = getJson();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private synchronized byte[] getJson() throws IOException {
        if (cachedJson == null) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("data-hf4-v2.json")) {
                if (is == null) throw new IOException("data-hf4-v2.json not found on classpath");
                cachedJson = is.readAllBytes();
            }
        }
        return cachedJson;
    }
}
