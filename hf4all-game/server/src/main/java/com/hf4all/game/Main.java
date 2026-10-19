package com.hf4all.game;

import com.hf4all.game.server.GameServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Starts the hf4all-game server.
 *
 * <pre>
 *   mvn exec:java@run                      # port 8097
 *   mvn exec:java@run -Dexec.args=9000     # another port
 * </pre>
 *
 * System properties: {@code game.root} (the hf4all-game directory, found
 * automatically from the working directory) and {@code game.saves} (where
 * games are stored, default {@code <game.root>/server/games}).
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    public static final int DEFAULT_PORT = 8097;

    private Main() {}

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path root = gameRoot();
        Path saves = Path.of(System.getProperty("game.saves", root.resolve("server").resolve("games").toString()));
        LOG.info(() -> "Game root: " + root + ", saves: " + saves);
        new GameServer(port, root, saves).start();
    }

    /** The hf4all-game directory: -Dgame.root, or the nearest ancestor of the working directory holding index.html + data/cards.js. */
    public static Path gameRoot() {
        String prop = System.getProperty("game.root");
        if (prop != null) return Path.of(prop).toAbsolutePath().normalize();
        Path p = Path.of("").toAbsolutePath();
        for (Path cur = p; cur != null; cur = cur.getParent()) {
            if (Files.isRegularFile(cur.resolve("index.html")) && Files.isRegularFile(cur.resolve("data").resolve("cards.js"))) return cur;
            Path game = cur.resolve("hf4all-game");
            if (Files.isRegularFile(game.resolve("index.html"))) return game;
        }
        throw new IllegalStateException("Cannot find the hf4all-game directory from " + p + " (set -Dgame.root)");
    }
}
