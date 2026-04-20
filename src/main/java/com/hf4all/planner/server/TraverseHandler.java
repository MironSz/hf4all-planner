package com.hf4all.planner.server;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.hf4all.planner.config.Config;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.pathfinder.Pathfinder;
import com.hf4all.planner.server.dto.TraverseRequest;
import com.hf4all.planner.server.dto.TraverseResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class TraverseHandler implements HttpHandler {

    private final SolarMap map;
    private final Gson gson = new Gson();

    public TraverseHandler(SolarMap map) {
        this.map = map;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS headers
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", Config.corsAllowOrigin());
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            TraverseRequest request;
            try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                request = gson.fromJson(reader, TraverseRequest.class);
            }

            String error = validate(request);
            if (error != null) {
                sendJson(exchange, 400, gson.toJson(Map.of("status", error)));
                return;
            }

            TraverseResponse response = Pathfinder.traverse(map, request);
            sendJson(exchange, 200, gson.toJson(response));

        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, gson.toJson(Map.of("status", "invalid JSON: " + e.getMessage())));
        } catch (Exception e) {
            sendJson(exchange, 500, gson.toJson(Map.of("status", "internal error: " + e.getMessage())));
        }
    }

    private String validate(TraverseRequest request) {
        if (request == null) return "empty request";
        if (request.startNodeId() == null || request.startNodeId().isBlank()) return "startNodeId is required";
        if (request.engines() == null || request.engines().isEmpty()) return "at least one engine required";
        int maxEngines = Config.requestMaxEngines();
        if (request.engines().size() > maxEngines) return "at most " + maxEngines + " engines allowed";

        int minDry = Config.dryMassMin();
        int maxDry = Config.requestMaxDryMass();
        if (request.dryMass() < minDry || request.dryMass() > maxDry) {
            return "dryMass must be between " + minDry + " and " + maxDry;
        }
        int minFuel = Config.fuelMin();
        int maxFuel = Config.requestMaxFuel();
        if (request.fuel() < minFuel || request.fuel() > maxFuel) {
            return "fuel must be between " + minFuel + " and " + maxFuel;
        }
        // Wet Mass cap (HF4A F3a). Caught again inside Pathfinder, but a
        // friendlier 400 here than burying the error in a 200 response body.
        int wet = request.dryMass() + request.fuel();
        if (wet > 32) {
            return "wetMass (dryMass + fuel) = " + wet + " exceeds the 32 cap (HF4A F3a)";
        }
        return null;
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
