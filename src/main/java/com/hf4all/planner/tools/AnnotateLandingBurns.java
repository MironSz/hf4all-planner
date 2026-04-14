package com.hf4all.planner.tools;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * One-shot script: annotates every landing-burn node with a {@code thrustRequired}
 * field derived from the nearest site(s) reachable via decorative/radhaz/flyby/venus
 * nodes (BFS stops at lagrange, hohmann, and non-landing burns).
 *
 * <p>Multiple sites per landing burn are allowed (e.g. Luna has two sites sharing
 * the same aerobrake path). All connected sites must have equal size, and that
 * size must be &ge; 6.
 *
 * <p>{@code thrustRequired = parsedSiteSize + 1}.
 *
 * <p>Reads {@code src/main/resources/data-hf4.json},
 * writes {@code src/main/resources/data-hf4-v2.json}.
 */
public final class AnnotateLandingBurns {

    public static void main(String[] args) throws IOException {
        Path inputPath  = Path.of("src/main/resources/data-hf4.json");
        Path outputPath = Path.of("src/main/resources/data-hf4-v2.json");

        // --- Load JSON ---
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        JsonObject points = root.getAsJsonObject("points");

        // --- Build adjacency from edges ---
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (JsonElement edgeEl : root.getAsJsonArray("edges")) {
            String edge = edgeEl.getAsString();
            int colon = edge.indexOf(':');
            String a = edge.substring(0, colon);
            String b = edge.substring(colon + 1);
            adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }

        // --- Process each landing burn ---
        int annotated = 0;
        int errors = 0;

        for (Map.Entry<String, JsonElement> entry : points.entrySet()) {
            String nodeId = entry.getKey();
            JsonObject node = entry.getValue().getAsJsonObject();

            String type = node.has("type") ? node.get("type").getAsString() : "decorative";
            boolean hasLanding = node.has("landing") && !node.get("landing").isJsonNull();

            if (!"burn".equals(type) || !hasLanding) continue;

            // BFS: traverse through node types that form landing/aerobrake paths.
            // Stop at: lagrange, hohmann, burns (non-landing).
            // Continue through: decorative, radhaz, flyby, venus, site (but don't go past site).
            Set<String> STOP_TYPES = Set.of("lagrange", "hohmann");

            List<String> connectedSites = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Set<String> traversedTypes = new TreeSet<>(); // track all node types in the path
            visited.add(nodeId);

            Deque<String> queue = new ArrayDeque<>();
            for (String neighbor : adjacency.getOrDefault(nodeId, Set.of())) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }

            while (!queue.isEmpty()) {
                String current = queue.poll();
                JsonObject currentNode = points.getAsJsonObject(current);
                if (currentNode == null) continue;

                String currentType = currentNode.has("type")
                        ? currentNode.get("type").getAsString() : "decorative";

                // Stop at lagrange, hohmann
                if (STOP_TYPES.contains(currentType)) continue;

                // Stop at burns that are not landing burns
                if ("burn".equals(currentType)) {
                    boolean currentHasLanding = currentNode.has("landing")
                            && !currentNode.get("landing").isJsonNull();
                    if (!currentHasLanding) continue;
                }

                traversedTypes.add(currentType);

                if ("site".equals(currentType)) {
                    connectedSites.add(current);
                    // don't continue past sites
                } else {
                    for (String next : adjacency.getOrDefault(current, Set.of())) {
                        if (visited.add(next)) {
                            queue.add(next);
                        }
                    }
                }
            }

            // --- Validate: at least one site ---
            if (connectedSites.isEmpty()) {
                System.err.println("ERROR: Landing burn " + nodeId
                        + " has no connected site! Traversed types: " + traversedTypes);
                errors++;
                continue;
            }

            // --- Collect site names and sizes ---
            List<String> siteNames = new ArrayList<>();
            List<String> siteSizes = new ArrayList<>();
            boolean missingSiteSize = false;
            for (String siteId : connectedSites) {
                JsonObject siteNode = points.getAsJsonObject(siteId);
                siteNames.add(siteNode.has("siteName")
                        ? siteNode.get("siteName").getAsString() : siteId);
                if (siteNode.has("siteSize")) {
                    siteSizes.add(siteNode.get("siteSize").getAsString());
                } else {
                    missingSiteSize = true;
                }
            }

            if (missingSiteSize) {
                System.err.println("ERROR: Landing burn " + nodeId
                        + " → site(s) " + siteNames + " missing siteSize!");
                errors++;
                continue;
            }

            // --- Compute size: use largest when sites differ ---
            int size = 0;
            boolean sizeMismatch = false;
            for (String ss : siteSizes) {
                int s = parseSiteSize(ss);
                if (size > 0 && s != size) sizeMismatch = true;
                size = Math.max(size, s);
            }

            // --- Validate: size >= 6 ---
            if (size < 6) {
                System.err.println("ERROR: Landing burn " + nodeId
                        + " → site size " + size + " < 6: " + siteNames + " " + siteSizes);
                errors++;
                continue;
            }

            int thrustRequired = size + 1;

            // --- Annotate ---
            node.addProperty("thrustRequired", thrustRequired);
            if (sizeMismatch) {
                node.addProperty("complicatedThrustRequirement", 1);
                System.out.println("  " + nodeId + " → " + siteNames
                        + " (sizes " + siteSizes + ") → thrustRequired=" + thrustRequired
                        + " COMPLICATED  path types: " + traversedTypes);
            } else {
                System.out.println("  " + nodeId + " → " + siteNames
                        + " (size " + siteSizes.get(0) + ") → thrustRequired=" + thrustRequired
                        + "  path types: " + traversedTypes);
            }
            annotated++;
        }

        // --- Write output ---
        System.out.println("\n" + annotated + " landing burns annotated.");
        if (errors > 0) {
            System.err.println(errors + " error(s) found. Output written anyway for inspection.");
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            gson.toJson(root, writer);
        }
        System.out.println("Written to " + outputPath);
    }

    /** Extracts the leading digits from a siteSize string like "9H" → 9, "11C" → 11. */
    private static int parseSiteSize(String siteSize) {
        StringBuilder digits = new StringBuilder();
        for (char c : siteSize.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else break;
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }
}
