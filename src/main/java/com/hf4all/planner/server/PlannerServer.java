package com.hf4all.planner.server;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class PlannerServer {

    private final int port;
    private HttpServer server;

    public PlannerServer(int port) {
        this.port = port;
    }

    public void start() {
        System.out.println("Loading solar map...");
        SolarMap map = MapLoader.loadDefault();
        System.out.printf("Map loaded: %s%n", map);

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to bind to port " + port, e);
        }

        server.createContext("/api/map", new MapHandler());
        server.createContext("/api/traverse", new TraverseHandler(map));
        server.createContext("/stop-hf4-planner", exchange -> {
            byte[] body = "Server stopping.".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
            System.out.println("Stop requested. Shutting down...");
            server.stop(1);
        });
        server.createContext("/", new IndexHandler());

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.printf("Server running at http://localhost:%d/%n", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
