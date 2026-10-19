package com.hf4all.game.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hf4all.game.util.Json;
import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.NodeType;
import com.hf4all.planner.model.Season;
import com.hf4all.planner.model.SiteData;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.rules.MoveRules;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The solar-system graph of the game, built on the planner's {@link SolarMap}
 * ({@link MapLoader#loadDefault()}: nodes, edges, Hohmann lane labels, one-way
 * aerobrake edges, synodic gates) and {@link MapNode} (type, hazard, landing,
 * thrust requirement, flyby, Solar Oberth, site size/type/hydration).
 *
 * <p>Added on top, as in the browser's mapgraph.js:
 * <ul>
 *   <li>site flags the planner does not load (astrobiology, submarine,
 *       atmospheric, push, centaur), read from the same data-hf4-v2.json;</li>
 *   <li>heliocentric zones from data/map.js (the planner has solarMod -4 for
 *       Saturn, Uranus and Neptune alike; the game tells them apart);</li>
 *   <li>routes: decorative bend points collapsed into routes between real
 *       Spaces, with the lane labels at both ends.</li>
 * </ul>
 */
public final class GameMap {

    public static final double W = 5400, H = 3619;

    private final SolarMap solar;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    /** Adjacency in the browser's order (edges sorted as "a:b" pairs). */
    private final Map<String, List<String>> adj = new HashMap<>();
    private final Map<String, List<Route>> routes = new HashMap<>();
    private final Map<String, String> synodic = new HashMap<>();
    public final String leo;
    private final Rules rules;

    /** A Space of the map (the fields of a data/map.js node). */
    public static final class Node {
        public String id;
        /** burn | hohmann | lagrange | site | radhaz | flyby | venus | decorative */
        public String t;
        public String z;
        public double px, py;
        public String name;
        public int size;
        public String spec;
        public int water;
        /** "aero" (on a one-way aerobrake path) | "crash" | null */
        public String haz;
        /** Lander burn: 1 or 0.5 (half lander burn), else 0. */
        public double land;
        public int treq;
        public int fly;
        public boolean oberth, astro, sub, atmo, push, centaur;
        public String syn;
        public MapNode planner;

        public boolean isSite() { return "site".equals(t); }
    }

    /** A route between two real Spaces through decorative points. */
    public static final class Route {
        public String from, to, first;
        public List<String> via;
        /** Lane label leaving {@code from} / arriving at {@code to} (Hohmann direction), or null. */
        public String dep, arr;
        public boolean oneWay, viaDeco;
    }

    public GameMap(Path mapJs, Rules rules) {
        this.rules = rules;
        this.leo = rules.leo;
        this.solar = MapLoader.loadDefault();
        JsonObject raw = loadPlannerJson().getAsJsonObject("points");
        JsonObject zones = Json.readJsData(mapJs).getAsJsonObject().getAsJsonObject("nodes");

        // One-way (aerobrake) endpoints: hazards there are aerobrake hazards.
        Set<String> oneWayNodes = new HashSet<>();
        for (MapNode a : solar.allNodes()) {
            for (MapNode b : solar.neighboursOf(a)) {
                if (solar.isOneWayBlocked(a, b)) { oneWayNodes.add(a.id()); oneWayNodes.add(b.id()); }
            }
        }
        TreeSet<String> ids = new TreeSet<>(GameMap::jsKeyOrder);
        ids.addAll(solar.nodesById().keySet());
        for (String id : ids) {
            MapNode m = solar.nodeById(id);
            JsonObject p = raw.getAsJsonObject(id);
            Node n = new Node();
            n.id = id;
            n.planner = m;
            n.t = m.type().name().toLowerCase(Locale.ROOT);
            JsonObject zn = zones.getAsJsonObject(id);
            n.z = zn != null && zn.has("z") ? zn.get("z").getAsString() : "earth";
            n.px = m.x() * W;
            n.py = m.y() * H;
            n.name = m.label();
            SiteData sd = m.siteData();
            if (m.type() == NodeType.SITE) {
                String size = sd != null ? sd.siteSize() : "0";
                StringBuilder digits = new StringBuilder(), letters = new StringBuilder();
                for (char ch : size.toCharArray()) {
                    if (Character.isDigit(ch)) digits.append(ch);
                    else if (Character.isLetter(ch)) letters.append(ch);
                }
                n.size = digits.length() > 0 ? Integer.parseInt(digits.toString()) : 0;
                n.spec = letters.length() > 0 ? letters.substring(0, 1) : "S";
                n.water = sd != null ? sd.water() : 0;
            }
            if (m.hazard()) n.haz = oneWayNodes.contains(id) ? "aero" : "crash";
            if (m.landing() != null && !m.landing().isZero()) {
                n.land = m.landing().toDouble();
                n.treq = m.thrustRequired();
            } else if (p != null && p.has("thrustRequired") && !p.get("thrustRequired").isJsonNull()) {
                n.treq = p.get("thrustRequired").getAsInt();
            }
            n.fly = m.flybyBoost();
            n.oberth = m.solarOberth();
            n.astro = flag(p, "astrobiology");
            n.sub = flag(p, "submarine");
            n.atmo = flag(p, "atmospheric");
            n.push = flag(p, "push");
            n.centaur = flag(p, "centaur");
            if (m.synodic() != null) n.syn = m.synodic().name().toLowerCase(Locale.ROOT);
            nodes.put(id, n);
            adj.put(id, new ArrayList<>());
        }

        // Adjacency ordered like the browser: every undirected edge once, pairs sorted.
        TreeSet<String[]> edges = new TreeSet<>((x, y) -> x[0].equals(y[0]) ? x[1].compareTo(y[1]) : x[0].compareTo(y[0]));
        for (MapNode a : solar.allNodes()) {
            for (MapNode b : solar.neighboursOf(a)) {
                if (a.id().equals(b.id())) continue;
                String lo = a.id().compareTo(b.id()) < 0 ? a.id() : b.id();
                String hi = lo.equals(a.id()) ? b.id() : a.id();
                edges.add(new String[]{lo, hi});
            }
        }
        for (String[] e : edges) {
            adj.get(e[0]).add(e[1]);
            adj.get(e[1]).add(e[0]);
        }

        for (Node n : nodes.values()) {
            if (n.t.equals("decorative")) continue;
            routes.put(n.id, buildRoutes(n.id));
        }
        for (Node n : nodes.values()) {
            Season s = solar.synodicGate(n.planner);
            if (s != null) synodic.put(n.id, s.name().toLowerCase(Locale.ROOT));
        }
    }

    private List<Route> buildRoutes(String id) {
        List<Route> list = new ArrayList<>();
        for (String first : adj.get(id)) {
            List<String> via = new ArrayList<>();
            String prev = id, cur = first;
            boolean blocked = false, oneWay = false, dead = false;
            if ("0".equals(label(cur, prev))) blocked = true;
            if ("0".equals(label(prev, cur))) oneWay = true;
            while (nodes.get(cur).t.equals("decorative")) {
                via.add(cur);
                String nxt = null;
                for (String x : adj.get(cur)) if (!x.equals(prev)) { nxt = x; break; }
                if (nxt == null) { dead = true; break; }
                if ("0".equals(label(nxt, cur))) blocked = true;
                if ("0".equals(label(cur, nxt))) oneWay = true;
                prev = cur;
                cur = nxt;
            }
            if (dead || blocked || cur.equals(id)) continue;
            Route r = new Route();
            r.from = id;
            r.to = cur;
            r.via = Collections.unmodifiableList(via);
            r.first = first;
            r.dep = label(id, first);
            r.arr = label(cur, via.isEmpty() ? id : via.get(via.size() - 1));
            r.oneWay = oneWay;
            r.viaDeco = !via.isEmpty();
            list.add(r);
        }
        return Collections.unmodifiableList(list);
    }

    private static boolean flag(JsonObject p, String key) {
        return p != null && p.has(key) && !p.get(key).isJsonNull() && p.get(key).getAsBoolean();
    }

    private static JsonObject loadPlannerJson() {
        try (InputStream is = GameMap.class.getClassLoader().getResourceAsStream("data-hf4-v2.json")) {
            if (is == null) throw new IOException("data-hf4-v2.json not found on the classpath");
            return JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** JS object key order: integer-like keys ascending, then the rest. */
    public static int jsKeyOrder(String a, String b) {
        boolean na = isIndex(a), nb = isIndex(b);
        if (na && nb) return Long.compare(Long.parseLong(a), Long.parseLong(b));
        if (na) return -1;
        if (nb) return 1;
        return a.compareTo(b);
    }

    private static boolean isIndex(String s) {
        if (s.isEmpty() || s.length() > 9) return false;
        if (s.length() > 1 && s.charAt(0) == '0') return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    // ------------------------------------------------------------------ queries

    public SolarMap solar() { return solar; }
    public Node node(String id) { return id == null ? null : nodes.get(id); }
    public Map<String, Node> nodes() { return nodes; }
    public List<Route> routes(String id) { return routes.getOrDefault(id, List.of()); }
    public List<String> adj(String id) { return adj.getOrDefault(id, List.of()); }
    public String synodic(String id) { return synodic.get(id); }

    public String label(String a, String b) {
        MapNode na = solar.nodeById(a), nb = solar.nodeById(b);
        return na == null || nb == null ? null : solar.edgeLabel(na, nb);
    }

    /** Does the Space carry Hohmann lane labels (direction changes are pivots)? */
    public boolean hasLabels(String id) {
        MapNode n = solar.nodeById(id);
        if (n == null) return false;
        for (MapNode b : solar.neighboursOf(n)) {
            String l = solar.edgeLabel(n, b);
            if (l != null && !l.equals("0")) return true;
        }
        return false;
    }

    public String name(String id) {
        Node n = nodes.get(id);
        if (n == null) return "?";
        if (n.name != null) return n.name;
        String kind = switch (n.t) {
            case "burn" -> n.land > 0 ? (n.land == 0.5 ? "Half Lander Burn" : "Lander Burn") : "Lagrange Burn";
            case "hohmann" -> "Hohmann";
            case "lagrange" -> n.haz != null ? (n.haz.equals("aero") ? "Aerobrake" : "Crash Hazard") : "Lagrange";
            case "radhaz" -> "Radiation Belt";
            case "flyby" -> "Flyby";
            case "venus" -> "Venus Flyby";
            default -> n.t;
        };
        return kind + " #" + id;
    }

    public boolean isSite(String id) { Node n = nodes.get(id); return n != null && n.isSite(); }

    public List<Node> sites() {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes.values()) if (n.isSite()) out.add(n);
        return out;
    }

    public String zoneOf(String id) { Node n = nodes.get(id); return n != null ? n.z : "earth"; }

    /** Heliocentric zones between a Space and Earth (I9). */
    public int zonesFromEarth(String id) { return Math.abs(rules.zoneIndex(zoneOf(id)) - rules.zoneIndex("earth")); }

    public boolean isLanding(String id) { Node n = nodes.get(id); return n != null && n.land > 0; }

    /** A move may end here: not a lander burn, not a bend point (the planner's rule). */
    public boolean canStop(String id) { Node n = nodes.get(id); return n != null && MoveRules.canStop(n.planner); }

    /** Planetary radiation belts shield against flares; Sol's (Mercury zone) don't (K2d). */
    public boolean isPlanetBelt(String id) { Node n = nodes.get(id); return n != null && n.t.equals("radhaz") && !n.z.equals("mercury"); }

    /**
     * Sites Adjacent to a Space (glossary: Adjacent): reachable without passing
     * another Space, looking through decorative points, crash hazards and lander burns.
     */
    public List<String> adjacentSites(String id) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        seen.add(id);
        Deque<String> stack = new ArrayDeque<>();
        for (String x : adj(id)) { stack.addLast(x); seen.add(x); }
        while (!stack.isEmpty()) {
            String n = stack.pollLast();
            Node node = nodes.get(n);
            boolean transparent = node.t.equals("decorative") || node.land > 0 || "crash".equals(node.haz);
            if (node.isSite()) out.add(n);
            if (transparent) {
                for (String h : adj(n)) if (seen.add(h)) stack.addLast(h);
            }
        }
        out.remove(id);
        return new ArrayList<>(out);
    }

    /** Node id of the named Space (first match), or null. */
    public String idByName(String name) {
        for (Node n : nodes.values()) if (name.equals(n.name)) return n.id;
        return null;
    }

    /** The extra JSON-like view used by tests: node fields as in data/map.js. */
    public JsonObject nodeJson(String id) {
        Node n = nodes.get(id);
        JsonObject o = new JsonObject();
        o.addProperty("t", n.t);
        o.addProperty("z", n.z);
        if (n.name != null) o.addProperty("name", n.name);
        if (n.isSite()) { o.addProperty("size", n.size); o.addProperty("spec", n.spec); o.addProperty("water", n.water); }
        if (n.haz != null) o.addProperty("haz", n.haz);
        if (n.land > 0) o.addProperty("land", n.land);
        if (n.treq > 0) o.addProperty("treq", n.treq);
        if (n.fly > 0) o.addProperty("fly", n.fly);
        if (n.oberth) o.addProperty("oberth", true);
        if (n.astro) o.addProperty("astro", true);
        if (n.sub) o.addProperty("sub", true);
        if (n.atmo) o.addProperty("atmo", true);
        if (n.push) o.addProperty("push", true);
        if (n.centaur) o.addProperty("centaur", true);
        if (n.syn != null) o.addProperty("syn", n.syn);
        return o;
    }
}
