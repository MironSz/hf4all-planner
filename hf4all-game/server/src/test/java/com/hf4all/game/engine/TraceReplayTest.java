package com.hf4all.game.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hf4all.game.TestSupport;
import com.hf4all.game.api.Rpc;
import com.hf4all.game.model.GameState;
import com.hf4all.game.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-trace parity with the browser engine: every engine call recorded by
 * the former browser engine (random games, the demo, the scripted smoke test) is replayed
 * on the Java engine from the same state with the same arguments, and the
 * resulting state, return value or rule error must match.
 */
class TraceReplayTest {

    @BeforeAll
    static void data() { TestSupport.data(); }

    private static final String LANDER = "lander burns have no fixed thrust requirement (H5e, H8c; RuleFixesTest)";
    private static final String REFUEL = "factory refuel without a stack on the Site (I5b, E6c; RuleFixesTest)";
    private static final String GLITCH_EVENT = "Glitch event skips stacks with Colocated Humans (K2b; RuleFixesTest)";
    private static final String GLITCH_ROLL = "Glitch Roll hits all own Colocated stacks (glossary; RuleFixesTest)";
    private static final String CYCLER = "the cycler Space is shielded from flares (K2d; RuleFixesTest)";
    private static final String PAD = "a pad explosion tie is the player's choice (K2c; MinorRuleFixesTest)";

    /**
     * Recorded calls whose outcome changed on purpose when a rule of the first port
     * was fixed. They are still replayed (and must not crash) but not compared.
     */
    static final Map<String, Map<Integer, String>> RULE_FIXES = Map.of(
            "demo-a.json.gz", Map.of(21, LANDER, 699, LANDER, 42, REFUEL, 66, REFUEL, 443, REFUEL, 714, REFUEL,
                    187, GLITCH_EVENT, 355, GLITCH_EVENT, 196, GLITCH_ROLL, 28, PAD),
            "demo-anarchy.json.gz", Map.of(39, REFUEL, 396, REFUEL, 442, REFUEL, 626, REFUEL, 928, REFUEL, 992, GLITCH_EVENT, 576, PAD),
            "demo-b.json.gz", Map.of(7, REFUEL, 62, REFUEL, 434, REFUEL, 438, REFUEL, 464, REFUEL, 37, PAD, 383, PAD),
            "fuzz-2p.json.gz", Map.of(495, PAD),
            "fuzz-3p-marathon.json.gz", Map.of(721, PAD),
            "fuzz-4p.json.gz", Map.of(638, PAD),
            "fuzz-4p-anarchy.json.gz", Map.of(940, PAD),
            "smoke.json.gz", Map.of(51, CYCLER, 115, LANDER));

    @TestFactory
    Stream<DynamicTest> traces() throws IOException, URISyntaxException {
        Path dir = Path.of(TraceReplayTest.class.getResource("/fixtures/traces").toURI());
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json.gz")).sorted().toList();
        }
        assertTrue(!files.isEmpty(), "no traces in fixtures/traces");
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> replay(f)));
    }

    static JsonArray load(Path f) {
        try (var in = new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void replay(Path f) {
        JsonArray recs = load(f);
        Map<Integer, String> fixed = RULE_FIXES.getOrDefault(f.getFileName().toString(), Map.of());
        List<String> failures = new ArrayList<>();
        JsonElement prevAfter = null;
        for (int i = 0; i < recs.size() && failures.size() < 30; i++) {
            JsonObject rec = recs.get(i).getAsJsonObject();
            String fn = rec.get("fn").getAsString();
            JsonArray args = rec.getAsJsonArray("args");
            String where = "#" + i + " " + fn + " " + shorten(args.toString());
            if (fn.equals("state.newGame")) {
                JsonObject cfg = args.get(0).getAsJsonObject();
                List<StateOps.PlayerConfig> pcs = new ArrayList<>();
                for (JsonElement e : cfg.getAsJsonArray("players")) {
                    JsonObject p = e.getAsJsonObject();
                    pcs.add(new StateOps.PlayerConfig(p.has("name") && !p.get("name").isJsonNull() ? p.get("name").getAsString() : null,
                            p.get("crew").getAsString(), p.get("face").getAsInt()));
                }
                GameState g = StateOps.newGame(cfg.get("seed").getAsLong(), cfg.has("cycles") ? cfg.get("cycles").getAsInt() : 4, pcs);
                for (String d : TestSupport.diff(rec.get("after"), Json.GSON.toJsonTree(g), 8)) failures.add(where + " state " + d);
                prevAfter = rec.get("after");
                continue;
            }
            JsonElement before;
            if (rec.get("before").isJsonPrimitive() && rec.get("before").getAsString().equals("$prev")) {
                JsonObject b = prevAfter.deepCopy().getAsJsonObject();
                b.add("log", new JsonArray());
                before = b;
            } else before = rec.get("before");
            GameState g = Json.GSON.fromJson(before, GameState.class);
            boolean expectError = rec.has("error");
            if (fixed.containsKey(i)) {
                try {
                    Rpc.call(g, fn, args);
                } catch (RuleError e) {
                    // a rule error is a valid outcome of a changed rule
                } catch (RuntimeException e) {
                    failures.add(where + " (" + fixed.get(i) + "): Java crashed " + e);
                }
                prevAfter = expectError || rec.get("after").isJsonPrimitive() ? before : rec.get("after");
                continue;
            }
            try {
                Object result = Rpc.call(g, fn, args);
                if (expectError) {
                    failures.add(where + ": JS failed with \"" + rec.get("error").getAsString() + "\" but Java succeeded");
                } else {
                    JsonElement expected = rec.get("after").isJsonPrimitive() ? before : rec.get("after");
                    for (String d : TestSupport.diff(expected, Json.GSON.toJsonTree(g), 8)) failures.add(where + " state " + d);
                    if (rec.has("result")) {
                        for (String d : TestSupport.diff(rec.get("result"), Json.GSON.toJsonTree(result), 8)) failures.add(where + " result " + d);
                    }
                }
            } catch (RuleError e) {
                if (!expectError) failures.add(where + ": Java failed with \"" + e.getMessage() + "\"");
                else if (!e.getMessage().equals(rec.get("error").getAsString())) {
                    failures.add(where + ": error \"" + e.getMessage() + "\" vs JS \"" + rec.get("error").getAsString() + "\"");
                }
            } catch (RuntimeException e) {
                failures.add(where + ": Java crashed " + e);
            }
            prevAfter = expectError || rec.get("after").isJsonPrimitive() ? before : rec.get("after");
        }
        assertTrue(failures.isEmpty(), f.getFileName() + ": " + failures.size() + " mismatches\n" + String.join("\n", failures));
    }

    private static String shorten(String s) { return s.length() > 120 ? s.substring(0, 120) + "..." : s; }
}
