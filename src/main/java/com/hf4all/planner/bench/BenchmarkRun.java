package com.hf4all.planner.bench;

import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.pathfinder.Pathfinder;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.api.PathNode;
import com.hf4all.planner.api.TraverseRequest;
import com.hf4all.planner.api.TraverseResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Standalone pathfinder benchmark.
 *
 * <p>Fixed scenario:
 * <ul>
 *   <li>Start node: {@link #START_NODE_ID} (lagrange near Mars — the common
 *       reachable-from-everything test start).</li>
 *   <li>Two engines: a high-thrust chemical + a fuel-efficient solar, so the
 *       search exercises both the weight-class-modifier code path and the
 *       solar-zone code path.</li>
 *   <li>Mass / fuel: {@link #DRY_MASS} / {@link #FUEL_STEPS}.</li>
 *   <li>{@code allowFuelJettison = true} so the lazy-jettison branching is
 *       also measured.</li>
 * </ul>
 *
 * <p>Writes one row per invocation to {@link #RESULTS_FILE} in the project
 * root, columns: {@code datetime,duration_ms,tree_nodes,endpoints,description}.
 * A header is written only when the file is created.
 *
 * <p>Usage:
 * <pre>
 *   mvn -q exec:java -Dexec.mainClass=com.hf4all.planner.bench.BenchmarkRun
 *   mvn -q exec:java -Dexec.mainClass=com.hf4all.planner.bench.BenchmarkRun -Dexec.args="after-fraction-rewrite"
 * </pre>
 *
 * <p>The description is everything on the command line joined by spaces.
 */
public final class BenchmarkRun {

    // ---- Fixed scenario -------------------------------------------------

    private static final String START_NODE_ID = "334";
    private static final int    DRY_MASS      = 4;
    /** Fuel-strip step count between Dry and Wet chits. dry=4, fuelSteps=29
     *  ≡ wet=24, the same effective ship as fuel=20 tanks pre-rename. */
    private static final int    FUEL_STEPS    = 29;

    /** High-thrust chemical engine: fast, burns fuel quickly. */
    private static final EngineSpec ENGINE_CHEMICAL =
            new EngineSpec(/* baseThrust */ 7, /* fuel */ 2, /* solar */ false, /* pivots */ 1);

    /** AB-capable variant of {@link #ENGINE_CHEMICAL} (opt-in via
     *  {@code -Dbench.ab=true}): same params, but afterburnFuelCost=1,
     *  afterburnThrustGain=1. The standard bench engines have afterburn
     *  0/0 (canAfterburn() == false), so the AB code path (lazy-afterburn
     *  triggers, eager branch B) is never exercised by the default bench —
     *  this variant exists purely to measure that path. */
    private static final EngineSpec ENGINE_CHEMICAL_AB =
            new EngineSpec(/* baseThrust */ 7, /* fuelNum */ 2, /* fuelDen */ 1,
                    /* solar */ false, /* pivots */ 1,
                    /* afterburnFuelCost */ 1, /* afterburnThrustGain */ 1,
                    /* magSail */ false, /* decommissionsOnAerobrake */ false);

    /** Fuel-efficient solar engine: thrust drops in outer zones. */
    private static final EngineSpec ENGINE_SOLAR =
            new EngineSpec(/* baseThrust */ 5, /* fuel */ 1, /* solar */ true, /* pivots */ 0);

    private static final boolean ALLOW_FUEL_JETTISON = true;

    /** Opt-in AB-capable engine variant (pathfinder-boost-unify phase 0
     *  measurement tool): when {@code -Dbench.ab=true}, {@link #ENGINE_CHEMICAL_AB}
     *  replaces {@link #ENGINE_CHEMICAL} in the request so the afterburn
     *  code path is actually exercised. */
    private static final boolean BENCH_AB = Boolean.getBoolean("bench.ab");

    // ---- Output file ----------------------------------------------------

    private static final Path RESULTS_FILE = Paths.get("benchmark-results.csv");
    private static final String CSV_HEADER =
            "datetime,duration_ms,tree_nodes,endpoints,description";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ---- Warmup / measurement -------------------------------------------

    private static final int WARMUP_RUNS = 1;

    // ---------------------------------------------------------------------

    private BenchmarkRun() {}

    public static void main(String[] args) throws IOException {
        String description = String.join(" ", args).trim();
        if (BENCH_AB) {
            description = description.isEmpty() ? "ab" : description + " ab";
        }

        EngineSpec chemical = BENCH_AB ? ENGINE_CHEMICAL_AB : ENGINE_CHEMICAL;

        SolarMap map = MapLoader.loadDefault();
        TraverseRequest request = new TraverseRequest(
                START_NODE_ID,
                List.of(chemical, ENGINE_SOLAR),
                DRY_MASS,
                FUEL_STEPS,
                ALLOW_FUEL_JETTISON);

        // Warmup — let the JIT compile the hot methods before we measure.
        for (int i = 0; i < WARMUP_RUNS; i++) {
            TraverseResponse warm = Pathfinder.traverse(map, request);
            if (!"ok".equals(warm.status())) {
                throw new IllegalStateException("Warmup run failed: " + warm.status());
            }
        }

        // Measured run.
        long startNs = System.nanoTime();
        TraverseResponse response = Pathfinder.traverse(map, request);
        long elapsedNs = System.nanoTime() - startNs;

        if (!"ok".equals(response.status())) {
            throw new IllegalStateException("Measured run failed: " + response.status());
        }

        long elapsedMs = elapsedNs / 1_000_000L;
        int treeNodes  = countTreeNodes(response.tree());
        int endpoints  = response.endpoints() == null ? 0 : response.endpoints().size();

        // Console summary — useful when eyeballing runs interactively.
        System.out.println("Benchmark complete");
        System.out.println("  start         = " + START_NODE_ID);
        System.out.println("  engines       = " + chemical + " + " + ENGINE_SOLAR);
        System.out.println("  dry/fuelSteps = " + DRY_MASS + "/" + FUEL_STEPS);
        System.out.println("  jettison      = " + ALLOW_FUEL_JETTISON);
        System.out.println("  bench.ab      = " + BENCH_AB);
        System.out.println("  elapsed       = " + elapsedMs + " ms");
        System.out.println("  tree nodes    = " + treeNodes);
        System.out.println("  endpoints     = " + endpoints);
        if (!description.isEmpty()) {
            System.out.println("  description   = " + description);
        }

        appendResult(elapsedMs, treeNodes, endpoints, description);
        System.out.println("Appended to " + RESULTS_FILE.toAbsolutePath());
    }

    /** Walks the PathNode tree and counts every node reachable from the root. */
    private static int countTreeNodes(PathNode root) {
        if (root == null) return 0;
        int count = 0;
        Deque<PathNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            PathNode n = stack.pop();
            count++;
            stack.addAll(n.children());
        }
        return count;
    }

    /**
     * Appends one CSV row to {@link #RESULTS_FILE}, creating the file with a
     * header if it doesn't exist yet. The description is CSV-escaped (quoted
     * if it contains a comma, quote, or newline).
     */
    private static void appendResult(long elapsedMs, int treeNodes, int endpoints,
                                     String description) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean newFile = !Files.exists(RESULTS_FILE);
        if (newFile) {
            sb.append(CSV_HEADER).append(System.lineSeparator());
        }
        sb.append(LocalDateTime.now().format(ISO))
          .append(',').append(elapsedMs)
          .append(',').append(treeNodes)
          .append(',').append(endpoints)
          .append(',').append(csvEscape(description))
          .append(System.lineSeparator());

        Files.writeString(RESULTS_FILE, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String csvEscape(String s) {
        if (s == null || s.isEmpty()) return "";
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                          || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
