package com.hf4all.planner.server;

import com.hf4all.planner.config.Config;
import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.server.handler.ConfigHandler;
import com.hf4all.planner.server.handler.IndexHandler;
import com.hf4all.planner.server.handler.MapHandler;
import com.hf4all.planner.server.handler.TraverseHandler;
import com.hf4all.planner.server.handler.editor.CelestialBodyEditorHandler;
import com.hf4all.planner.server.handler.editor.ChitEditorHandler;
import com.hf4all.planner.server.handler.editor.HexEditorHandler;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public final class PlannerServer {

    private static final Logger LOG = Logger.getLogger(PlannerServer.class.getName());

    private final int port;
    private final boolean allowDebugEndpoints;
    private HttpServer server;

    public PlannerServer(int port, boolean allowDebugEndpoints) {
        this.port = port;
        this.allowDebugEndpoints = allowDebugEndpoints;
    }

    public void start() {
        LOG.info("Loading solar map...");
        SolarMap map = MapLoader.loadDefault();
        LOG.info(() -> "Map loaded: " + map);

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to bind to port " + port, e);
        }

        // One filter instance, shared across every context, logs a line per
        // request into the same JUL log as the rest of the server.
        Filter requestLog = new RequestLogFilter();

        server.createContext(Config.endpointMap(), new MapHandler()).getFilters().add(requestLog);
        server.createContext(Config.endpointTraverse(), new TraverseHandler(map)).getFilters().add(requestLog);
        server.createContext(Config.endpointConfig(), new ConfigHandler()).getFilters().add(requestLog);
        server.createContext("/hex-editor", new HexEditorHandler(allowDebugEndpoints)).getFilters().add(requestLog);
        server.createContext("/celestial-body-editor", new CelestialBodyEditorHandler(allowDebugEndpoints)).getFilters().add(requestLog);
        server.createContext("/chit-editor", new ChitEditorHandler(allowDebugEndpoints)).getFilters().add(requestLog);
        if (allowDebugEndpoints) {
            server.createContext(Config.endpointStop(), exchange -> {
                // Only the local machine can shut the server down. With the
                // server bound to a public port (e.g. :80) anyone could
                // otherwise call this URL and kill it. The check matches
                // HexEditorHandler's save-endpoint guard.
                if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                    LOG.warning(() -> "Rejected non-local stop attempt from "
                            + exchange.getRemoteAddress().getAddress().getHostAddress());
                    byte[] msg = "stop endpoint is only accepted from localhost".getBytes();
                    exchange.sendResponseHeaders(403, msg.length);
                    try (var os = exchange.getResponseBody()) { os.write(msg); }
                    return;
                }
                byte[] body = "Server stopping.".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (var os = exchange.getResponseBody()) { os.write(body); }
                LOG.info("Stop requested. Shutting down...");
                server.stop(Config.serverStopDelaySeconds());
            }).getFilters().add(requestLog);
        } else {
            LOG.info("Debug endpoints disabled: stop endpoint not registered, editor saves will return 403.");
        }
        server.createContext("/", new IndexHandler()).getFilters().add(requestLog);

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        LOG.info(() -> "Server running at http://localhost:" + port + "/");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
