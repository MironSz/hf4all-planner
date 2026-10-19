package com.hf4all.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.hf4all.game.data.GameData;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Shared test helpers: game data and JSON comparison with JS semantics. */
public final class TestSupport {

    private TestSupport() {}

    public static GameData data() { return GameData.load(Main.gameRoot()); }

    public static JsonElement fixture(String name) {
        try (InputStream is = TestSupport.class.getResourceAsStream("/fixtures/" + name)) {
            if (is == null) throw new IOException("missing fixture " + name);
            return JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Differences between two JSON values, JS-style: a missing key equals null
     * (JSON.stringify drops undefined), numbers compare with a 1e-9 tolerance.
     * Returns at most {@code max} difference paths.
     */
    public static List<String> diff(JsonElement a, JsonElement b, int max) {
        List<String> out = new ArrayList<>();
        diff("$", a, b, out, max);
        return out;
    }

    private static boolean isNull(JsonElement e) { return e == null || e instanceof JsonNull; }

    private static void diff(String path, JsonElement a, JsonElement b, List<String> out, int max) {
        if (out.size() >= max) return;
        if (isNull(a) && isNull(b)) return;
        if (isNull(a) || isNull(b)) { out.add(path + ": " + a + " vs " + b); return; }
        if (a.isJsonObject() && b.isJsonObject()) {
            JsonObject x = a.getAsJsonObject(), y = b.getAsJsonObject();
            TreeSet<String> keys = new TreeSet<>(x.keySet());
            keys.addAll(y.keySet());
            for (String k : keys) diff(path + "." + k, x.get(k), y.get(k), out, max);
            return;
        }
        if (a.isJsonArray() && b.isJsonArray()) {
            JsonArray x = a.getAsJsonArray(), y = b.getAsJsonArray();
            if (x.size() != y.size()) { out.add(path + ": length " + x.size() + " vs " + y.size() + " " + shorten(x) + " vs " + shorten(y)); return; }
            for (int i = 0; i < x.size(); i++) diff(path + "[" + i + "]", x.get(i), y.get(i), out, max);
            return;
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            JsonPrimitive x = a.getAsJsonPrimitive(), y = b.getAsJsonPrimitive();
            if (x.isNumber() && y.isNumber()) {
                if (Math.abs(x.getAsDouble() - y.getAsDouble()) > 1e-9) out.add(path + ": " + x + " vs " + y);
                return;
            }
            if (!x.equals(y)) out.add(path + ": " + x + " vs " + y);
            return;
        }
        out.add(path + ": " + shorten(a) + " vs " + shorten(b));
    }

    private static String shorten(JsonElement e) {
        String s = String.valueOf(e);
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    public static String report(List<String> diffs) {
        return String.join("\n", diffs);
    }
}
