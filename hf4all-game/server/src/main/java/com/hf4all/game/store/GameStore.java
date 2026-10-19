package com.hf4all.game.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hf4all.game.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Saved games on disk: one {@code <id>.json} file per game, written
 * atomically (temp file + move) after every accepted command, so a game
 * survives a server restart. The file holds {@code {id, version, created,
 * updated, state}} where {@code state} is the game state JSON.
 */
public final class GameStore {

    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,40}");

    private final Path dir;

    public GameStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the saves directory " + dir, e);
        }
    }

    public Path dir() { return dir; }

    public static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }

    public synchronized void save(String id, JsonObject doc) {
        if (!validId(id)) throw new IllegalArgumentException("Bad game id: " + id);
        Path file = dir.resolve(id + ".json");
        Path tmp = dir.resolve(id + ".json.tmp");
        try {
            Files.writeString(tmp, Json.write(doc), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save game " + id, e);
        }
    }

    /** The saved document, or null when there is no such game. */
    public synchronized JsonObject load(String id) {
        if (!validId(id)) return null;
        Path file = dir.resolve(id + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read game " + id, e);
        }
    }

    public synchronized List<String> ids() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".json") && validId(n.substring(0, n.length() - 5))) out.add(n.substring(0, n.length() - 5));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
        return out;
    }

    public synchronized boolean delete(String id) {
        if (!validId(id)) return false;
        try {
            return Files.deleteIfExists(dir.resolve(id + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete game " + id, e);
        }
    }
}
