package com.hf4all.planner.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Centralized configuration loader.
 *
 * <p>Loads three properties files from the classpath at class-init time:
 * <ul>
 *   <li>{@code config/server.properties}   — HTTP/server settings</li>
 *   <li>{@code config/planner.properties}  — pathfinder and rule limits</li>
 *   <li>{@code config/frontend.properties} — UI defaults served to the browser</li>
 * </ul>
 *
 * <p>Any value can be overridden at JVM start with {@code -D<key>=<value>}.
 * System properties always win over the bundled defaults.
 */
public final class Config {

    private static final Properties SERVER   = load("config/server.properties");
    private static final Properties PLANNER  = load("config/planner.properties");
    private static final Properties FRONTEND = load("config/frontend.properties");

    private Config() {}

    private static Properties load(String resource) {
        Properties p = new Properties();
        try (InputStream is = Config.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new IllegalStateException("Missing config resource: " + resource);
            p.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + resource, e);
        }
        return p;
    }

    /** Resolves a key, preferring {@code System.getProperty} over the bundled default. */
    private static String get(Properties p, String key) {
        String sys = System.getProperty(key);
        if (sys != null) return sys;
        String val = p.getProperty(key);
        if (val == null) throw new IllegalStateException("Missing config key: " + key);
        return val;
    }

    private static int getInt(Properties p, String key) {
        return Integer.parseInt(get(p, key).trim());
    }

    private static boolean getBoolean(Properties p, String key) {
        return Boolean.parseBoolean(get(p, key).trim());
    }

    // -------------------------------------------------------------------------
    // Server
    // -------------------------------------------------------------------------

    public static int     serverPort()              { return getInt(SERVER,     "server.port"); }
    public static int     serverStopDelaySeconds()  { return getInt(SERVER,     "server.stop.delay.seconds"); }
    public static boolean allowDebugEndpoints()     { return getBoolean(SERVER, "server.debug.endpoints.allow"); }
    public static String  endpointMap()             { return get(SERVER,        "server.endpoint.map"); }
    public static String  endpointTraverse()        { return get(SERVER,        "server.endpoint.traverse"); }
    public static String  endpointConfig()          { return get(SERVER,        "server.endpoint.config"); }
    public static String  endpointStop()            { return get(SERVER,        "server.endpoint.stop"); }
    public static String  corsAllowOrigin()         { return get(SERVER,        "server.cors.allow.origin"); }
    public static String  cacheHtml()               { return get(SERVER,        "server.cache.html"); }
    public static int     cacheAssetsSeconds()      { return getInt(SERVER,     "server.cache.assets.seconds"); }
    public static int     cacheMapSeconds()         { return getInt(SERVER,     "server.cache.map.seconds"); }
    public static String  staticPrefix()            { return get(SERVER,        "server.static.prefix"); }
    public static String  mapResource()             { return get(SERVER,        "server.map.resource"); }
    public static int     requestMaxEngines()       { return getInt(SERVER,     "server.request.max.engines"); }
    public static int     requestMaxFuel()          { return getInt(SERVER,     "server.request.max.fuel"); }
    public static int     requestMaxDryMass()       { return getInt(SERVER,     "server.request.max.dry.mass"); }

    // -------------------------------------------------------------------------
    // Planner
    // -------------------------------------------------------------------------

    public static int    searchMaxTurns()          { return getInt(PLANNER, "planner.search.max.turns"); }
    public static int    searchMaxIterations()     { return getInt(PLANNER, "planner.search.max.iterations"); }
    public static int    fuelMin()                 { return getInt(PLANNER, "planner.fuel.min"); }
    public static int    fuelMax()                 { return getInt(PLANNER, "planner.fuel.max"); }
    public static int    dryMassMin()              { return getInt(PLANNER, "planner.dry.mass.min"); }
    public static int    dryMassMax()              { return getInt(PLANNER, "planner.dry.mass.max"); }
    public static int    enginesMax()              { return getInt(PLANNER, "planner.engines.max"); }
    public static String solarZoneSeedNode()       { return get(PLANNER,    "planner.solar.zone.seed.node"); }

    /** Parses {@code planner.solar.zone.modifiers} (comma-separated ints) into an int[]. */
    public static int[] solarZoneModifiers() {
        String[] parts = get(PLANNER, "planner.solar.zone.modifiers").split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Frontend
    // -------------------------------------------------------------------------

    /**
     * Returns all frontend keys, with {@code -D} overrides applied. Caller
     * (typically the {@code /api/config} handler) serializes this to JSON.
     */
    public static Map<String, String> frontendAll() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : FRONTEND.stringPropertyNames()) {
            String sys = System.getProperty(name);
            out.put(name, sys != null ? sys : FRONTEND.getProperty(name));
        }
        return out;
    }
}
