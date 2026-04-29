package com.hf4all.planner.server.handler;

import com.hf4all.planner.config.Config;
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
        exchange.getResponseHeaders().set("Cache-Control",
                "public, max-age=" + Config.cacheMapSeconds());
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private synchronized byte[] getJson() throws IOException {
        if (cachedJson == null) {
            String resource = Config.mapResource();
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (is == null) throw new IOException(resource + " not found on classpath");
                cachedJson = is.readAllBytes();
            }
        }
        return cachedJson;
    }
}
