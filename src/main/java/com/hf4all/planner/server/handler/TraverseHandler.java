package com.hf4all.planner.server.handler;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.hf4all.planner.config.Config;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.pathfinder.Pathfinder;
import com.hf4all.planner.api.TraverseRequest;
import com.hf4all.planner.api.TraverseStreamChunk;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

            streamTraverse(exchange, request);

        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, gson.toJson(Map.of("status", "invalid JSON: " + e.getMessage())));
        } catch (Exception e) {
            sendJson(exchange, 500, gson.toJson(Map.of("status", "internal error: " + e.getMessage())));
        }
    }

    private String validate(TraverseRequest request) {
        if (request == null) return "empty request";
        if (request.startNodeId() == null || request.startNodeId().isBlank()) return "startNodeId is required";
        // HF4A H5e "No Stopping": you may not end (or start) movement on a
        // lander-burn space. Reject upfront with a clear 400 rather than
        // letting Pathfinder return an empty endpoint set.
        var startNode = map.nodeById(request.startNodeId());
        if (startNode != null && !startNode.landing().isZero()) {
            return "cannot start on a lander-burn space (HF4A H5e): " + request.startNodeId();
        }
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
        if (request.fuelSteps() < minFuel || request.fuelSteps() > maxFuel) {
            return "fuelSteps must be between " + minFuel + " and " + maxFuel;
        }
        // Wet-step cap. The Wet chit must land on the 57-position fuel
        // strip; equivalently the wet integer mass cannot exceed 32 (HF4A F3a).
        // Caught again inside Pathfinder, but a friendlier 400 here than
        // burying the error in a 200 response body.
        int dryStep = com.hf4all.planner.model.FuelStrip.stepsBetween(1, request.dryMass());
        int wetStep = dryStep + request.fuelSteps();
        int totalSteps = com.hf4all.planner.model.FuelStrip.stepsBetween(
                1, com.hf4all.planner.model.FuelStrip.MAX_WET_MASS);
        if (wetStep > totalSteps) {
            return "wetStep (dryStep + fuelSteps) = " + wetStep
                    + " exceeds the " + totalSteps + "-step strip cap (HF4A F3a)";
        }
        return null;
    }

    /**
     * Streams the search as NDJSON — one {@link TraverseStreamChunk} JSON
     * object per line, flushed as each mission year is planned, then a final
     * {@code done} chunk. Uses chunked transfer encoding ({@code
     * sendResponseHeaders(200, 0)}) so the browser can render routes
     * incrementally. Owns all of its own errors: once the 200 is committed the
     * status can't change, so a mid-stream failure is surfaced as a final
     * error chunk rather than a 500.
     */
    private void streamTraverse(HttpExchange exchange, TraverseRequest request) {
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=UTF-8");
            // Defeat proxy/server response buffering so chunks aren't held back.
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0); // 0 => chunked transfer encoding
        } catch (IOException e) {
            return; // couldn't start the response; connection already broken
        }

        try (var os = exchange.getResponseBody()) {
            Pathfinder.PartialSink sink = chunk -> {
                try {
                    os.write((gson.toJson(chunk) + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException e) {
                    // Client navigated away / aborted the fetch. Unwind the
                    // search promptly instead of running it to completion for
                    // a reader that's gone.
                    throw new ClientDisconnected(e);
                }
            };
            try {
                Pathfinder.traverseStreaming(map, request, sink);
            } catch (ClientDisconnected disconnect) {
                // expected when the browser cancels an in-flight stream
            } catch (Exception e) {
                // Failure after the 200 was committed: best-effort final error
                // chunk so the frontend stops waiting, then close.
                try {
                    TraverseStreamChunk errChunk = new TraverseStreamChunk(
                            0, 0, true, request.startNodeId(),
                            List.of(), Map.of(), "internal error: " + e.getMessage());
                    os.write((gson.toJson(errChunk) + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {
                    // client likely gone too; nothing more to do
                }
            }
        } catch (IOException e) {
            // opening/closing the response body failed — nothing actionable
        }
    }

    /** Thrown from the streaming sink to unwind the search when the client
     *  closes the connection mid-stream. */
    private static final class ClientDisconnected extends RuntimeException {
        ClientDisconnected(IOException cause) { super(cause); }
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
