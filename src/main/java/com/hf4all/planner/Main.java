package com.hf4all.planner;

import com.hf4all.planner.config.Config;
import com.hf4all.planner.server.PlannerServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.LogManager;

public class Main {

    public static void main(String[] args) {
        initLogging();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Config.serverPort();
        PlannerServer server = new PlannerServer(port, Config.allowDebugEndpoints());
        server.start();
    }

    /**
     * Loads {@code config/logging.properties} from the classpath and applies
     * it to the JUL log manager. Ensures {@code target/} exists first so the
     * {@link java.util.logging.FileHandler} can bind to its rolling output
     * file even on a fresh checkout where {@code target/} hasn't been
     * created yet.
     */
    private static void initLogging() {
        try {
            Files.createDirectories(Path.of("target"));
        } catch (IOException ignored) {
            // Non-fatal — FileHandler will fail loud if it can't create its file.
        }
        try (InputStream is = Main.class.getResourceAsStream("/config/logging.properties")) {
            if (is == null) {
                System.err.println("[Main] /config/logging.properties not found on classpath; using JUL defaults");
                return;
            }
            LogManager.getLogManager().readConfiguration(is);
        } catch (IOException e) {
            System.err.println("[Main] failed to load logging config: " + e);
        }
    }
}
