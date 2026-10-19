package com.hf4all.game.server;

import com.hf4all.game.api.GameService;
import com.hf4all.game.data.GameData;
import com.hf4all.game.engine.FuelTrack;
import com.hf4all.game.store.GameStore;
import com.hf4all.game.util.Json;
import com.hf4all.planner.server.RequestLogFilter;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * HTTP server of the game: the JSON API under {@code /api/} and the browser
 * client from the hf4all-game directory. Same stack as the planner's
 * {@code PlannerServer} (JDK HttpServer on virtual threads, its request log filter).
 */
public final class GameServer {

    private static final Logger LOG = Logger.getLogger(GameServer.class.getName());

    private final int port;
    private final Path root;
    private final Path saves;
    private HttpServer server;

    public GameServer(int port, Path root, Path saves) {
        this.port = port;
        this.root = root;
        this.saves = saves;
    }

    public void start() {
        GameData.load(root);
        GameStore store = new GameStore(saves);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to bind to port " + port, e);
        }
        Filter log = new RequestLogFilter();
        server.createContext("/api/", new ApiHandler(new GameService(store), this::stopLater)).getFilters().add(log);
        // The Fuel Strip table of the browser, from the planner's FuelStrip (via FuelTrack).
        byte[] fuelRules = utf8("window.HF_FUEL_RULES = " + Json.write(FuelTrack.clientRules()) + ";\n");
        server.createContext("/data/fuelrules.js", ex -> {
            try (ex) {
                ex.getResponseHeaders().set("Content-Type", "text/javascript; charset=utf-8");
                ex.sendResponseHeaders(200, fuelRules.length);
                ex.getResponseBody().write(fuelRules);
            }
        }).getFilters().add(log);
        server.createContext("/", new StaticHandler(root)).getFilters().add(log);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        LOG.info(() -> "Game server running at http://localhost:" + port() + "/");
    }

    /** The bound port (useful when started with port 0 in tests). */
    public int port() { return server != null ? server.getAddress().getPort() : port; }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void stopLater() {
        Thread.ofVirtual().start(() -> {
            LOG.info("Stop requested. Shutting down...");
            server.stop(1);
        });
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
