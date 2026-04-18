package com.hf4all.planner.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hf4all.planner.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads a {@link SolarMap} from the HF4A JSON map format (data-hf4-v2.json).
 *
 * JSON shape:
 * <pre>
 * {
 *   "points": {
 *     "<id>": { "x": 0.0, "y": 0.0, "type": "burn|hohmann|lagrange|site|radhaz|flyby|venus|decorative",
 *               "siteName": "...",   // optional
 *               "siteSize": "9H",    // optional, site nodes only
 *               "siteWater": "3",    // optional, site nodes only (string in JSON)
 *               "hazard": true,      // optional
 *               "landing": 1,        // optional: 1 or 0.5
 *               "flybyBoost": 2 }    // optional
 *   },
 *   "edges": ["a:b", ...],           // undirected, ids lexicographically sorted
 *   "edgeLabels": {
 *     "<fromId>": { "<toId>": "1" }  // direction labels at Hohmann intersections; "0" = one-way block
 *   }
 * }
 * </pre>
 *
 * RADHAZ nodes carry no radiation value in the JSON. The radiation level is
 * fixed at {@value #RADHAZ_RADIATION_LEVEL} to match the original game rule
 * (risk = max(RADIATION - thrust, 0)).
 */
public final class MapLoader {

    /**
     * Default radiation severity assigned to all RADHAZ nodes.
     * Derived from the original JS rule: {@code Math.max(6 - thrust, 0)}.
     */
    public static final int RADHAZ_RADIATION_LEVEL = 6;

    // Prevent instantiation — use static factory methods only.
    private MapLoader() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Loads the bundled {@code data-hf4-v2.json} from the classpath.
     * This is the canonical HF4A map included in the project resources.
     */
    public static SolarMap loadDefault() {
        try (InputStream is = MapLoader.class.getClassLoader()
                .getResourceAsStream("data-hf4-v2.json")) {
            if (is == null) throw new IOException("data-hf4-v2.json not found on classpath");
            return load(is);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load default map", e);
        }
    }

    /** Loads from a file on disk. */
    public static SolarMap loadFromFile(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            return load(is);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load map from " + path, e);
        }
    }

    /** Loads from any InputStream (caller is responsible for closing). */
    public static SolarMap load(InputStream is) {
        Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        return parse(root);
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static SolarMap parse(JsonObject root) {
        JsonObject pointsJson  = root.getAsJsonObject("points");
        JsonElement edgesEl    = root.get("edges");
        JsonObject edgeLabJson = root.has("edgeLabels")
                                 ? root.getAsJsonObject("edgeLabels") : new JsonObject();

        // --- 1. Parse all nodes into a lookup map (single pass) ---
        Map<String, MapNode> lookup = new java.util.HashMap<>();
        for (Map.Entry<String, JsonElement> entry : pointsJson.entrySet()) {
            MapNode node = parseNode(entry.getKey(), entry.getValue().getAsJsonObject());
            lookup.put(node.id(), node);
        }

        SolarMap.Builder b = SolarMap.builder();
        lookup.values().forEach(b::addNode);

        // --- 2. Parse edges ---
        // Stored as "a:b" strings where a < b lexicographically (undirected).
        for (JsonElement edgeEl : edgesEl.getAsJsonArray()) {
            String edgeStr = edgeEl.getAsString();
            int colon = edgeStr.indexOf(':');
            String aId = edgeStr.substring(0, colon);
            String bId = edgeStr.substring(colon + 1);
            MapNode a = lookup.get(aId);
            MapNode bNode = lookup.get(bId);
            if (a == null || bNode == null) {
                System.err.println("[MapLoader] Skipping dead edge: " + edgeStr);
                continue;
            }
            b.addEdge(a, bNode);
        }

        // --- 3. Parse edge labels ---
        // Labels encode Hohmann directions ("1"/"2") or one-way blockers ("0").
        for (Map.Entry<String, JsonElement> fromEntry : edgeLabJson.entrySet()) {
            MapNode fromNode = lookup.get(fromEntry.getKey());
            if (fromNode == null) continue;

            for (Map.Entry<String, JsonElement> toEntry :
                    fromEntry.getValue().getAsJsonObject().entrySet()) {
                MapNode toNode = lookup.get(toEntry.getKey());
                if (toNode == null) continue;
                try {
                    b.setEdgeLabel(fromNode, toNode, toEntry.getValue().getAsString());
                } catch (IllegalArgumentException ex) {
                    System.err.println("[MapLoader] Bad edge label skipped: " + ex.getMessage());
                }
            }
        }

        return b.build();
    }

    // -------------------------------------------------------------------------
    // Node parsing
    // -------------------------------------------------------------------------

    private static MapNode parseNode(String id, JsonObject p) {
        NodeType type = parseType(getString(p, "type", "decorative"));
        double x      = p.get("x").getAsDouble();
        double y      = p.get("y").getAsDouble();

        MapNode.Builder b = MapNode.builder(id, type)
                .position(x, y);

        // hazard — present on lagrange and burn nodes
        if (p.has("hazard") && !p.get("hazard").isJsonNull()) {
            b.hazard(p.get("hazard").getAsBoolean());
        }

        // radiation — not stored in JSON; inferred from node type
        if (type == NodeType.RADHAZ) {
            b.radiation(RADHAZ_RADIATION_LEVEL);
        }

        // landing — "1" or "0.5" stored as a JSON number
        if (p.has("landing") && !p.get("landing").isJsonNull()) {
            b.landing(doubleToExactFraction(p.get("landing").getAsDouble()));
        }

        // thrustRequired — integer, present on landing burn nodes (from v2 data)
        if (p.has("thrustRequired") && !p.get("thrustRequired").isJsonNull()) {
            b.thrustReq(p.get("thrustRequired").getAsInt());
        }

        // flybyBoost — integer, present on flyby and venus nodes
        if (p.has("flybyBoost") && !p.get("flybyBoost").isJsonNull()) {
            b.flybyBoost(p.get("flybyBoost").getAsInt());
        }

        // solarMod — heliocentric-zone thrust modifier for solar engines (defaults to 0)
        if (p.has("solarMod") && !p.get("solarMod").isJsonNull()) {
            b.solarMod(p.get("solarMod").getAsInt());
        }

        // siteName — may appear on any node type as a display label
        String siteName = getString(p, "siteName", null);

        // Full site data — only when siteSize and siteWater are also present
        if (p.has("siteSize") && p.has("siteWater")) {
            String siteSize = getString(p, "siteSize", "");
            int siteWater   = Integer.parseInt(getString(p, "siteWater", "0"));
            b.siteData(new SiteData(siteName != null ? siteName : id, siteSize, siteWater));
        } else if (siteName != null) {
            // Named node that isn't a full site (e.g. LEO, GEO)
            b.label(siteName);
        }

        return b.build();
    }

    private static NodeType parseType(String raw) {
        return switch (raw) {
            case "burn"       -> NodeType.BURN;
            case "hohmann"    -> NodeType.HOHMANN;
            case "lagrange"   -> NodeType.LAGRANGE;
            case "site"       -> NodeType.SITE;
            case "radhaz"     -> NodeType.RADHAZ;
            case "flyby"      -> NodeType.FLYBY;
            case "venus"      -> NodeType.VENUS;
            default           -> NodeType.DECORATIVE;
        };
    }

    /**
     * Converts a double that is known to be a simple fraction (0.5 or a whole
     * number) to an exact Fraction. Avoids floating-point representation issues.
     */
    private static Fraction doubleToExactFraction(double value) {
        // Multiply by 2, round to nearest int, then reduce.
        int times2 = (int) Math.round(value * 2);
        return new Fraction(times2, 2);
    }

    private static String getString(JsonObject obj, String key, String defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return defaultValue;
        return obj.get(key).getAsString();
    }
}
