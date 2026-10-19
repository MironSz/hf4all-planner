package com.hf4all.game.engine;

import com.hf4all.game.TestSupport;
import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.Engine;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Stack;
import com.hf4all.game.model.Turn;
import com.hf4all.game.util.Json;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.api.PathNode;
import com.hf4all.planner.api.TraverseRequest;
import com.hf4all.planner.api.TraverseResponse;
import com.hf4all.planner.model.Fraction;
import com.hf4all.planner.pathfinder.Pathfinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cross-check of the planner against the game (not a JUnit test: run its main).
 *
 * <p>For each case a Rocket is assembled in the game; its engines (as the game
 * prices them at the start) are typed into the planner as {@link EngineSpec}s
 * with the Rocket's Dry Mass, fuel steps, start Space and season; every route
 * the planner returns is then replayed in the game step by step: engine,
 * afterburn and jettison per turn as the planner chose them, each Space must
 * be a legal option along the same route, every move must end where the planner
 * ends a turn, and the fuel left at the end must match.
 *
 * <p>Replay conventions: Hazards are passed with FINAO (no die), every other die
 * is loaded to 1 (the mildest Belt Roll), and a new turn simply advances the
 * Sunspot Cube for the same player (no events - the planner has none).
 *
 * <p>Usage: {@code java -Dplanner.search.max.turns=3 -cp ... PlannerCrossCheck [caseFilter]}
 */
public final class PlannerCrossCheck {

    /** An engine of the case: a card that needs no supports, or a built engine (thruster + chain). */
    record Eng(String card, Map<String, String> chain) {
        static Eng simple(String card) { return new Eng(card, null); }
        static Eng built(String card, Map<String, String> chain) { return new Eng(card, chain); }
    }

    record Case(String name, String start, int cyclePos, int tanks, String crew, int face, List<String> cards, List<Eng> engines) {}

    /** "ID:side" (side w/b, heavy radiators "ID:w:heavy"). */
    static final List<Case> CASES = new ArrayList<>();

    static void add(String name, String start, int cyclePos, int tanks, String crew, int face, List<String> cards, List<Eng> engines) {
        CASES.add(new Case(name, start, cyclePos, tanks, crew, face, cards, engines));
    }

    static {
        // Crew-powered (NASA: 14 / 8, afterburn 2).
        add("crew-leo-yellow", "90", 3, 6, "CW", 0, List.of(), List.of(Eng.simple("crew")));
        add("crew-leo-blue", "90", 0, 8, "CW", 0, List.of(), List.of(Eng.simple("crew")));
        add("crew-mars-red", "334", 8, 8, "CW", 0, List.of(), List.of(Eng.simple("crew")));
        // Solar thruster (Re Solar Moth 3 / 4, afterburn 2), crew as passengers only.
        add("moth-leo-yellow", "90", 3, 8, "CR", 0, List.of("CT048:w"), List.of(Eng.simple("CT048")));
        add("moth-mercury-blue", "184", 0, 8, "CR", 0, List.of("CT048:w"), List.of(Eng.simple("CT048")));
        add("moth-ceres-red", "354", 7, 8, "CR", 0, List.of("CT048:w"), List.of(Eng.simple("CT048")));
        // Electric thruster with fractional fuel: Ion Drive (2 / 1/2, pivot) + Cascade Photovoltaic.
        add("ion-pv-leo", "90", 3, 6, "CR", 0, List.of("CT045:b", "CG106:w"),
                List.of(Eng.built("CT045", Map.of("CT045:gen", "CG106"))));
        add("ion-pv-mars", "334", 6, 6, "CR", 0, List.of("CT045:b", "CG106:w"),
                List.of(Eng.built("CT045", Map.of("CT045:gen", "CG106"))));
        // Dual-Stage 4-Grid (2 / 1/3, pivot) + Diamonoid tether (not solar).
        add("grid-ceres", "354", 4, 5, "CR", 0, List.of("CT055:b", "CG114:b"),
                List.of(Eng.built("CT055", Map.of("CT055:gen", "CG114"))));
        add("grid-jupiter", "536", 9, 5, "CR", 0, List.of("CT055:b", "CG114:b"),
                List.of(Eng.built("CT055", Map.of("CT055:gen", "CG114"))));
        // Mag Sail (1 / 0, pivot, solar, a sail).
        add("magsail-leo", "90", 8, 0, "CR", 0, List.of("CT049:b"), List.of(Eng.simple("CT049")));
        add("magsail-jupiter", "536", 3, 0, "CR", 0, List.of("CT049:b"), List.of(Eng.simple("CT049")));
        // Support thrust folded in: Monatomic Plug Nozzle 4 + Cermet NERVA +3 = 7 / 3, afterburn 1.
        add("plug-nerva-leo", "90", 5, 8, "CR", 0, List.of("CT051:w", "CX090:w"),
                List.of(Eng.built("CT051", Map.of("CT051:reactor", "CX090"))));
        add("plug-nerva-saturn", "637", 5, 8, "CR", 0, List.of("CT051:w", "CX090:w"),
                List.of(Eng.built("CT051", Map.of("CT051:reactor", "CX090"))));
        // Heavy high-thrust: Kuck Mosquito 10 / 8 + Project Orion +7 (cooled by a membrane): landings on big Sites.
        add("kuck-orion-luna", "115", 3, 10, "CW", 0, List.of("CB060:w", "CX081:w", "CD092:w"),
                List.of(Eng.built("CB060", Map.of("CB060:reactor", "CX081"))));
        add("kuck-orion-ceres", "353", 7, 10, "CW", 0, List.of("CB060:w", "CX081:w", "CD092:w"),
                List.of(Eng.built("CB060", Map.of("CB060:reactor", "CX081"))));
        add("kuck-orion-mars-pole", "340", 9, 12, "CW", 0, List.of("CB060:w", "CX081:w", "CD092:w"),
                List.of(Eng.built("CB060", Map.of("CB060:reactor", "CX081"))));
        // Fuel multiplier through a reactor: Ablative Plate 2 / 2 x 1/4 with D-T Gun Fusion (+1) and a Flywheel.
        add("ablative-dt-leo", "90", 10, 6, "CR", 0, List.of("CT052:w", "CX091:w", "CG111:w"),
                List.of(Eng.built("CT052", Map.of("CT052:reactor", "CX091", "CX091:gen", "CG111"))));
        // Two engines to switch between: the Crew and Re Solar Moth.
        add("crew+moth-leo", "90", 2, 8, "CW", 0, List.of("CT048:w"), List.of(Eng.simple("crew"), Eng.simple("CT048")));
        add("crew+moth-uranus", "679", 2, 8, "CW", 0, List.of("CT048:w"), List.of(Eng.simple("crew"), Eng.simple("CT048")));
        // Synodic comet in its season, the Venus flyby in blue, the Neptune zone, a solar thruster at Saturn.
        add("crew-kreutz-yellow", "619", 3, 8, "CW", 0, List.of(), List.of(Eng.simple("crew")));
        add("crew-venus-blue", "882", 11, 8, "CW", 0, List.of(), List.of(Eng.simple("crew")));
        add("grid-neptune", "808", 6, 5, "CR", 0, List.of("CT055:b", "CG114:b"),
                List.of(Eng.built("CT055", Map.of("CT055:gen", "CG114"))));
        add("moth-saturn", "637", 7, 8, "CR", 0, List.of("CT048:w"), List.of(Eng.simple("CT048")));
        // A heavy Tug (heavy radiators) with the Crew: weight class -2.
        add("tug-leo", "90", 3, 8, "CW", 0, List.of("CD093:w:heavy", "CD095:w:heavy", "CD100:w:heavy"), List.of(Eng.simple("crew")));
    }

    /* ---------------------------------------------------------------- results */

    static final Map<String, Integer> FAILS = new TreeMap<>();
    static final Map<String, Integer> THRUST_DIFF = new TreeMap<>();
    static final Map<String, Integer> FUEL_CAUSE = new TreeMap<>();
    /** -Dcrosscheck.verbose=<reason substring>: print the steps of the first route failing that way. */
    static final String VERBOSE = System.getProperty("crosscheck.verbose");
    static boolean verbosePrinted;
    static final Map<String, String> FAIL_EXAMPLE = new HashMap<>();
    static String lastFuelCause;
    static boolean lastExtraJettison;
    static final Map<String, Integer> OK_KIND = new TreeMap<>();
    /** Per replayed route: the first movement where the planner's thrust exceeded the game's, and an Oberth with folded support thrust. */
    static String lastThrustCause;
    static boolean lastOberthFold;
    /** The game used a bonus pivot on this route (a rocket always does when it has one). */
    static boolean lastBonusPivot;
    /** A solar engine entered the Neptune zone on this route (the planner's map has no such zone). */
    static boolean lastNeptuneSolar;
    static final Map<String, Integer> ROOT = new TreeMap<>();
    /** The planner's worstRadRoll vs the game's worst potential Belt Roll on the same route. */
    static final Map<String, Integer> RAD = new TreeMap<>();
    static final Map<String, String> RAD_EXAMPLE = new HashMap<>();
    static final int[] BELT_LOSS = {0};

    static void fail(String why, String example) {
        FAILS.merge(why, 1, Integer::sum);
        FAIL_EXAMPLE.putIfAbsent(why, example);
    }

    public static void main(String[] args) {
        TestSupport.data();
        String filter = args.length > 0 ? args[0] : null;
        int totalRoutes = 0, totalOk = 0;
        System.out.println("planner max turns = " + System.getProperty("planner.search.max.turns", "(default)"));
        for (Case c : CASES) {
            if (filter != null && !c.name().contains(filter)) continue;
            try {
                int[] r = runCase(c);
                totalRoutes += r[0];
                totalOk += r[1];
            } catch (RuntimeException e) {
                System.out.println(c.name() + ": SETUP FAILED " + e);
            }
        }
        System.out.println();
        System.out.println("TOTAL routes " + totalRoutes + ", replayed OK " + totalOk + ", failed " + (totalRoutes - totalOk));
        for (var e : FAILS.entrySet()) System.out.println("  " + e.getValue() + " x " + e.getKey() + "\n      e.g. " + FAIL_EXAMPLE.get(e.getKey()));
        System.out.println("Movements where the planner's net thrust differs from the game's (all replayed routes): " + THRUST_DIFF);
        System.out.println("Executable routes: " + totalOk + " of " + totalRoutes);
        for (var e : OK_KIND.entrySet()) System.out.println("  " + e.getValue() + " x " + e.getKey());
        System.out.println("Routes where a real Belt Roll (even a 1) would destroy cards - undone, dice are not the plan: " + BELT_LOSS[0]);
        System.out.println("Worst potential Belt Roll (planner worstRadRoll vs the game with a 6), routes that reach the end:");
        for (var e : RAD.entrySet()) System.out.println("  " + e.getValue() + " x " + e.getKey() + (e.getKey().startsWith("same") ? "" : "\n      e.g. " + RAD_EXAMPLE.get(e.getKey())));
        System.out.println("ROOT CAUSES of all failed routes:");
        for (var e : ROOT.entrySet()) System.out.println("  " + e.getValue() + " x " + e.getKey());
    }

    /* ---------------------------------------------------------------- one case */

    static int[] runCase(Case c) {
        GameState g0 = newGame(c);
        Player p = g0.players.get(0);
        Stack r = p.stacks.rocket;
        // The engines as the game prices them at the start, typed into the planner.
        List<EngineSpec> specs = new ArrayList<>();
        List<Object> handles = new ArrayList<>(); // Integer card index (simple) or String engine id (built)
        for (Eng e : c.engines()) {
            int idx = cardIndex(r, e.card());
            Movement.PlanOpts o = new Movement.PlanOpts();
            Object handle = idx;
            if (e.chain() != null) {
                Map<String, Integer> choice = new LinkedHashMap<>();
                for (var ch : e.chain().entrySet()) {
                    String[] k = ch.getKey().split(":");
                    choice.put(cardIndex(r, k[0]) + ":" + k[1], cardIndex(r, ch.getValue()));
                }
                String id = Movement.saveEngine(g0, idx, choice);
                o.chain = Movement.engines(g0).stream().filter(v -> id.equals(v.id)).findFirst().orElseThrow().chain;
                handle = id;
            }
            Movement.Plan pl = Movement.plan(g0, idx, false, o);
            if (!pl.ok) throw new IllegalStateException(e.card() + " cannot fire here: " + pl.reason);
            int weight = FuelTrack.weightClass(r.fuel.wet).mod();
            int solar = pl.parts.stream().filter(x -> x.label.startsWith("Solar power")).mapToInt(x -> x.value).sum();
            Fraction perBurn = Fractions.of(pl.fuelCons);
            EngineSpec es = new EngineSpec(pl.net - weight - solar, perBurn.numerator(), perBurn.denominator(), pl.engine.solarPowered(),
                    pl.pivots, pl.canAfterburn ? pl.abSteps : 0, pl.canAfterburn ? 1 : 0, pl.engine.magSail(), pl.engine.decommissionsOnAerobrake());
            specs.add(es);
            handles.add(handle);
        }
        int dry = r.fuel.dry, fuel = FuelTrack.fuelSteps(r.fuel);
        TraverseRequest req = new TraverseRequest(c.start(), specs, dry, fuel, true, plannerYear(c.cyclePos()));
        long t0 = System.currentTimeMillis();
        TraverseResponse res = Pathfinder.traverse(GameData.get().map.solar(), req);
        long ms = System.currentTimeMillis() - t0;
        // Every endpoint route, root to endpoint.
        Map<Integer, PathNode> byId = new HashMap<>();
        Map<Integer, Integer> parent = new HashMap<>();
        index(res.tree(), null, byId, parent);
        int routes = 0, ok = 0;
        for (var ep : res.endpoints().entrySet()) {
            for (int id : ep.getValue()) {
                List<PathNode> path = new ArrayList<>();
                for (Integer x = id; x != null; x = parent.get(x)) path.add(0, byId.get(x));
                routes++;
                StringBuilder trace = new StringBuilder();
                String why = replay(g0, handles, specs, path, trace);
                if (why == null) { ok++; OK_KIND.merge("exactly as planned", 1, Integer::sum); }
                else if (why.startsWith("OK")) {
                    ok++;
                    String cause = lastFuelCause != null ? lastFuelCause : "?";
                    OK_KIND.merge((why.equals("OK more fuel") ? "works, the game ends with more fuel" : "works after jettisoning surplus fuel at a turn start")
                            + (lastExtraJettison && why.equals("OK more fuel") ? " (and needed an extra jettison)" : "") + " - because " + cause, 1, Integer::sum);
                }
                else {
                    fail(why, c.name() + ": " + describe(path) + "\n        " + trace);
                    if (why.startsWith("fuel left differs")) FUEL_CAUSE.merge(String.valueOf(lastFuelCause), 1, Integer::sum);
                    ROOT.merge(rootCause(why), 1, Integer::sum);
                    if (VERBOSE != null && !verbosePrinted && (why + " " + rootCause(why)).contains(VERBOSE)) {
                        verbosePrinted = true;
                        System.out.println("VERBOSE " + why + "\n  " + c.name() + ": " + describe(path) + "\n  " + trace);
                    }
                }
            }
        }
        System.out.printf("%-22s dry %2d fuel %2d engines %s -> planner %d ms, %d routes, %d OK%n",
                c.name(), dry, fuel, specsText(specs), ms, routes, ok);
        return new int[] {routes, ok};
    }

    /* ---------------------------------------------------------------- replay */

    /** Replays one planner route in a copy of the game; null when it works, else why not. */
    static String replay(GameState g0, List<Object> handles, List<EngineSpec> specs, List<PathNode> path, StringBuilder trace) {
        GameState g = Json.read(Json.write(g0), GameState.class);
        Player p = g.players.get(0);
        GameMap map = GameData.get().map;
        // Turns: [turn-start node index, last node index] segments.
        int i = 0;
        int turn = path.get(0).turns();
        double[] spentBefore = {0};
        int[] gameWorst = {0};
        boolean[] negativeAtBelt = {false}, beltLoss = {false}, enteredBelt = {false}, sailLost = {false};
        boolean[] extraJettison = {false};
        boolean[] thrustDiffers = {false};
        String[] fuelDiv = {null};
        String[] fuelCause = {null};
        StringBuilder steps = VERBOSE != null ? trace : new StringBuilder();
        boolean[] pivotBonus = {false}, oneWayBonus = {false};
        lastFuelCause = null;
        lastThrustCause = null;
        lastOberthFold = false;
        lastBonusPivot = false;
        lastNeptuneSolar = false;
        while (i < path.size()) {
            int startI = i;
            int j = i + 1;
            while (j < path.size() && path.get(j).turns() == turn) j++;
            // Segment path[startI .. j-1] is this turn; its turn-start node is path[startI].
            PathNode ts = path.get(startI);
            List<PathNode> seg = path.subList(startI, j);
            List<PathNode> moves = new ArrayList<>();
            for (PathNode n : seg.subList(1, seg.size())) moves.add(n);
            // The turn-1 seed may be a separate child carrying afterburn / jettison (same Space, same turn).
            int ab = ts.afterburnedHere(), jet = ts.jettisonedHere();
            if (turn == path.get(0).turns() && !moves.isEmpty() && moves.get(0).nodeId().equals(ts.nodeId())
                    && (moves.get(0).afterburnedHere() > 0 || moves.get(0).jettisonedHere() > 0)) {
                ab = moves.get(0).afterburnedHere();
                jet = moves.get(0).jettisonedHere();
            }
            spentBefore[0] = (double) ts.fuelSpentNum() / ts.fuelSpentDen();
            if (!moves.isEmpty() && moves.get(0).nodeId().equals(ts.nodeId())) spentBefore[0] = (double) moves.get(0).fuelSpentNum() / moves.get(0).fuelSpentDen();
            List<String> spaces = new ArrayList<>(); // real Spaces entered, with the decoratives before each
            List<List<String>> vias = new ArrayList<>();
            List<String> via = new ArrayList<>();
            String at = ts.nodeId();
            int engine = -1;
            for (PathNode n : moves) {
                if (engine < 0 && n.engineIndex() >= 0 && !n.nodeId().equals(ts.nodeId())) engine = n.engineIndex();
                if (n.nodeId().equals(at)) continue; // pivot / turn start at the same Space
                if (map.node(n.nodeId()).t.equals("decorative")) { via.add(n.nodeId()); at = n.nodeId(); continue; }
                spaces.add(n.nodeId());
                vias.add(via);
                via = new ArrayList<>();
                at = n.nodeId();
            }
            if (!via.isEmpty()) return "planner turn ends on a decorative point";
            if (!spaces.isEmpty()) {
                if (engine < 0) engine = moves.stream().filter(n -> n.engineIndex() >= 0).map(PathNode::engineIndex).findFirst().orElse(0);
                try {
                    if (jet > 0) Actions.jettisonFuel(g, jet);
                    // The planner's net thrust for this movement (its formula) vs the game's.
                    EngineSpec es = specs.get(engine);
                    PathNode tsEff = !moves.isEmpty() && moves.get(0).nodeId().equals(ts.nodeId())
                            && (moves.get(0).afterburnedHere() > 0 || moves.get(0).jettisonedHere() > 0) ? moves.get(0) : ts;
                    int plannerFuel = tsEff.fuelStepsRemaining() + (ab > 0 ? es.afterburnFuelCost() : 0);
                    int surplus = FuelTrack.fuelSteps(p.stacks.rocket.fuel) - plannerFuel;
                    if (surplus > 0) {
                        Actions.jettisonFuel(g, surplus); // a free action (G1f), as a player would to match the plan
                        extraJettison[0] = true;
                        trace.append("[extra jettison ").append(surplus).append("] ");
                    } else if (surplus < 0) trace.append("[game has ").append(-surplus).append(" less fuel] ");
                    Stack rk = p.stacks.rocket;
                    int fuelNow = FuelTrack.fuelSteps(rk.fuel);
                    int plannerWm = com.hf4all.planner.model.FuelStrip.wetMassAt(rk.fuel.dry, fuelNow);
                    int plannerWc = com.hf4all.planner.model.FuelStrip.weightClassModForWetMass(plannerWm);
                    int gameWc = FuelTrack.weightClass(rk.fuel.wet).mod();
                    int solarMod = map.node(ts.nodeId()).planner.solarMod();
                    int plannerSolar = es.solarPowered() ? solarMod : 0;
                    int plannerThrust = com.hf4all.planner.rules.MoveRules.solarShutdown(es.solarPowered(), solarMod) ? 0
                            : es.baseThrust() + plannerWc + plannerSolar + (ab > 0 ? 1 : 0);
                    Object h = handles.get(engine);
                    if (h instanceof Integer idx) Movement.start(g, cardIndexByUid(g, g0, idx), ab > 0, null);
                    else Movement.startEngine(g, (String) h, ab > 0, null);
                    trace.append("T").append(turn).append("@").append(ts.nodeId()).append(" fuel ").append(fuelNow)
                            .append(" thrust planner ").append(plannerThrust).append(" game ").append(g.move.net);
                    if (plannerWc != gameWc) trace.append(" (weight class: planner WM ").append(plannerWm).append(" -> ").append(plannerWc)
                            .append(", game chit step ").append(rk.fuel.wet).append(" -> ").append(gameWc).append(")");
                    if (es.solarPowered()) trace.append(" (solar: planner ").append(plannerSolar).append(", game zone ").append(map.zoneOf(ts.nodeId())).append(")");
                    trace.append("; ");
                    if (plannerThrust != g.move.net) thrustDiffers[0] = true;
                    if (plannerThrust != g.move.net) {
                        int wcAfterAb = FuelTrack.weightClass(rk.fuel.wet).mod();
                        String why;
                        if (ab > 0 && wcAfterAb != gameWc) why = "afterburn: planner takes the weight class before the afterburn fuel (H3b: after)";
                        else if (plannerWc != gameWc) why = "weight class: planner rounds the Wet Mass down (H3b: the chit's band)";
                        else if (es.solarPowered()) why = "solar modifier: planner map " + solarMod + " vs game zone " + map.zoneOf(ts.nodeId());
                        else why = "other";
                        THRUST_DIFF.merge(why, 1, Integer::sum);
                        if (lastThrustCause == null && plannerThrust > g.move.net) lastThrustCause = why;
                    }
                } catch (RuleError e) {
                    return "start refused: " + generic(e.getMessage());
                }
                for (int k = 0; k < spaces.size(); k++) {
                    String to = spaces.get(k);
                    if (g.move == null) return "planner flies on after landing on a Site in the same turn (H6: landing ends the movement)" + landedOn(map, spaces, k);
                    Movement.MoveOption opt = null;
                    for (Movement.MoveOption o : Movement.optionsFor(g, withAlive(g), 0)) {
                        if (o.to.equals(to) && o.route.via.equals(vias.get(k))) { opt = o; break; }
                    }
                    if (opt == null) return "no such route in the game to " + kind(map, to);
                    if (!opt.legal) return "illegal in the game: " + generic(opt.reason) + " [" + kind(map, to) + "]";
                    Movement.MoveOption chosen = null;
                    for (Movement.MoveOption o : Movement.options(g)) if (o.to.equals(to)) chosen = o;
                    if (chosen == null || !chosen.route.via.equals(opt.route.via)) return "game takes another route to the same Space";
                    Movement.StepChoices ch = new Movement.StepChoices();
                    ch.finao = opt.hazard != null || Boolean.TRUE.equals(opt.hazardRoll);
                    ch.permission = opt.factoryOwner != null;
                    steps.append("\n          -> ").append(to).append(' ').append(kind(map, to)).append(opt.route.oneWay ? " one-way" : "")
                            .append(opt.pivot ? " PIVOT" : "").append(" game: burns ").append(opt.burns).append(" bonusUsed ").append(opt.bonusUsed)
                            .append(" fuel ").append(fmt(opt.fuel)).append(" ").append(opt.flags);
                    if (opt.pivot && opt.bonusUsed > 0) pivotBonus[0] = true;
                    if (opt.pivotUsed) lastBonusPivot = true;
                    if (map.zoneOf(to).equals("neptune") && specs.get(engine).solarPowered()) lastNeptuneSolar = true;
                    if (opt.flags.stream().anyMatch(f -> f.startsWith("Solar Oberth")) && specs.get(engine).baseThrust() != g.move.base) lastOberthFold = true;
                    if (opt.route.oneWay && g.move.bonus > 0) oneWayBonus[0] = true;
                    loadDie(g, 1);
                    int cards = p.stacks.rocket.cards.size();
                    boolean belt = map.node(to).t.equals("radhaz");
                    String stackBefore = belt ? Json.write(p.stacks.rocket) : null;
                    if (belt) {
                        // The planner reports the worst roll a route can meet (a 6); the game rolls. Compare the former only.
                        int potential = Math.max(0, com.hf4all.planner.rules.MoveRules.beltSeverity(6, Math.max(0, g.move.net),
                                Movement.plannerSeason(StateOps.season(g))));
                        gameWorst[0] = Math.max(gameWorst[0], potential);
                        if (g.move.net < 0) negativeAtBelt[0] = true;
                    }
                    try {
                        Movement.step(g, to, ch);
                    } catch (RuleError e) {
                        return "step refused: " + generic(e.getMessage());
                    }
                    if (p.stacks.rocket == null) return belt ? "rocket destroyed by the Belt Roll (dice)" : "rocket lost on the way";
                    if (belt && p.stacks.rocket.cards.size() < cards) {
                        // Dice are not the plan: undo the Belt Roll's losses and fly on.
                        Stack restored = Json.read(stackBefore, Stack.class);
                        restored.node = p.stacks.rocket.node;
                        p.stacks.rocket = restored;
                        beltLoss[0] = true;
                    }
                    if (belt) enteredBelt[0] = true;
                    if (p.stacks.rocket.cards.size() < cards) {
                        // Sails burn up in an aerobrake (H6b) - the planner models that as a decommissioned engine.
                        if (Boolean.TRUE.equals(opt.aeroBurn)) sailLost[0] = true;
                        else return "cards lost on the way";
                    }
                    if (g.move != null && fuelDiv[0] == null) {
                        PathNode pn = nodeAt(moves, to);
                        double plannerSpentTurn = (double) pn.fuelSpentNum() / pn.fuelSpentDen() - spentBefore[0];
                        steps.append("  | after: planner spent ").append(fmt(plannerSpentTurn)).append(", game spent ").append(fmt(g.move.spent))
                                .append(" bonus ").append(g.move.bonus).append(" burnsUsed ").append(g.move.burnsUsed).append('/').append(g.move.burnLimit);
                        if (Math.abs(plannerSpentTurn - g.move.spent) > 1e-9 && fuelDiv[0] == null) {
                            String cause = opt.route.oneWay ? "after a one-way (aerobrake) path the planner drops the Bonus Burns"
                                    : opt.pivot ? "the game pays a Pivot with Bonus Burns" : "other: " + kind(map, to);
                            fuelCause[0] = cause;
                        }
                        if (Math.abs(plannerSpentTurn - g.move.spent) > 1e-9) fuelDiv[0] = "fuel diverges entering " + to + " " + kind(map, to)
                                + " (this turn: planner spent " + fmt(plannerSpentTurn) + ", game " + fmt(g.move.spent) + ", game bonus burns left " + g.move.bonus + ")";
                    }
                }
                if (g.move != null) {
                    if (!map.canStop(g.move.node)) return j >= path.size()
                            ? "planner lists a lander burn as an endpoint (the game can only pass through it)"
                            : "planner ends a turn on a lander burn";
                    Movement.end(g);
                }
            }
            if (j >= path.size()) break;
            // Next turn: same player, the Sunspot Cube advances (no events).
            g.turn = Turn.fresh();
            g.year++;
            g.turnNo++;
            g.cyclePos = (g.cyclePos + 1) % 12;
            turn = path.get(j).turns();
            i = j;
        }
        if (fuelDiv[0] != null) trace.append(fuelDiv[0]);
        PathNode end = path.get(path.size() - 1);
        if (beltLoss[0]) BELT_LOSS[0]++;
        int pw = end.worstRadRoll();
        String rad = pw == gameWorst[0] ? "same worst roll"
                : !enteredBelt[0] ? "planner reports a Belt Roll on a route without belts (" + pw + "): its red-season +2 is added on every Space entered while net thrust < 2"
                : sailLost[0] && pw > gameWorst[0] ? "differs: after a sail burns up the planner counts net thrust 0 for the belts that follow"
                : thrustDiffers[0] ? "differs because the planner's net thrust differed on that route (" + (pw > gameWorst[0] ? "planner higher" : "planner lower") + ")"
                : negativeAtBelt[0] ? "differs: the planner subtracts a negative net thrust (H10: not subtracted)"
                : "differs: other (planner " + pw + ", game " + gameWorst[0] + ")";
        RAD.merge(rad, 1, Integer::sum);
        RAD_EXAMPLE.putIfAbsent(rad, describe(path) + "  planner " + pw + " game " + gameWorst[0] + (sailLost[0] ? " (sail lost)" : ""));
        Stack r = p.stacks.rocket;
        if (!r.node.equals(end.nodeId())) return "game Rocket ends elsewhere";
        int left = FuelTrack.fuelSteps(r.fuel);
        lastExtraJettison = extraJettison[0];
        lastFuelCause = pivotBonus[0] && oneWayBonus[0] ? "both: Pivot paid with Bonus Burns + Bonus Burns kept after a one-way path"
                : pivotBonus[0] ? "the game pays a Pivot with Bonus Burns (planner: 2 paid burns)"
                : oneWayBonus[0] ? "the game keeps Bonus Burns after a one-way aerobrake path (planner drops them)" : fuelCause[0];
        if (left < end.fuelStepsRemaining()) return "game ends with less fuel than the planner";
        if (left > end.fuelStepsRemaining()) return "OK more fuel";
        return extraJettison[0] ? "OK extra jettison" : null;
    }

    /* ---------------------------------------------------------------- helpers */

    static GameState newGame(Case c) {
        GameState g = StateOps.newGame(7, 4, List.of(new StateOps.PlayerConfig("P", c.crew(), c.face())));
        Player p = g.players.get(0);
        p.aquas = 999;
        Stack s = Stack.at(c.start());
        s.cards.add(p.stacks.leo.cards.remove(0)); // the Crew
        int uid = 5000;
        for (String spec : c.cards()) {
            String[] x = spec.split(":");
            CardInst ci = CardInst.patent(x[0], x[1], uid++);
            if (x.length > 2) ci.orient = x[2];
            s.cards.add(ci);
        }
        p.stacks.rocket = s;
        StateOps.syncRocketDry(p);
        if (c.tanks() > 0) FuelTrack.addFuel(s.fuel, c.tanks(), "water");
        g.cyclePos = c.cyclePos();
        return g;
    }

    /** The game's cycle position as the planner's year (planner: years 1-4 blue, 5-8 yellow, 9-12 red). */
    static int plannerYear(int cyclePos) { return (cyclePos + 2) % 12 + 1; }

    static int cardIndex(Stack r, String card) {
        for (int i = 0; i < r.cards.size(); i++) {
            CardInst c = r.cards.get(i);
            if (card.equals("crew") ? c.crew != null : card.equals(c.id)) return i;
        }
        throw new IllegalStateException("no " + card + " in the Rocket");
    }

    /** A card's index now, found by its uid in the original game (cards may have moved). */
    static int cardIndexByUid(GameState g, GameState g0, int idx0) {
        int uid = g0.players.get(0).stacks.rocket.cards.get(idx0).uid;
        Stack r = g.players.get(0).stacks.rocket;
        for (int i = 0; i < r.cards.size(); i++) if (r.cards.get(i).uid == uid) return i;
        throw new RuleError("the engine card is gone");
    }

    static com.hf4all.game.model.MoveState withAlive(GameState g) {
        com.hf4all.game.model.MoveState m = g.move.copy();
        m.thrusterAlive = Movement.thrusterAlive(g);
        return m;
    }

    static void index(PathNode n, Integer parentId, Map<Integer, PathNode> byId, Map<Integer, Integer> parent) {
        byId.put(n.id(), n);
        if (parentId != null) parent.put(n.id(), parentId);
        for (PathNode c : n.children()) index(c, n.id(), byId, parent);
    }

    /** Load the die: the next d6 rolls v. */
    static void loadDie(GameState g, int v) {
        GameState t = new GameState();
        for (long r = 1; ; r++) {
            t.rng = r;
            if (1 + Dice.nextInt(t, 6) == v) { g.rng = r; return; }
        }
    }

    static String kind(GameMap map, String id) {
        GameMap.Node n = map.node(id);
        return n.t + (n.land > 0 ? " (lander burn)" : "") + (n.haz != null ? " (" + n.haz + ")" : "") + (n.size > 0 ? " size " + n.size : "")
                + (n.z.equals("neptune") ? " in the Neptune zone" : "");
    }

    /** A reason without numbers, so equal causes group together. */
    static String generic(String s) { return s == null ? "?" : s.replaceAll("\\d+", "#"); }

    static String sign(int d) { return d > 0 ? "more" : "less"; }

    static String rootCause(String why) {
        if (why.startsWith("planner flies on after landing")) return "PLANNER: passes through a Site without landing there (H6: landing ends the movement)";
        if (why.contains("lander burn as an endpoint") || why.contains("cannot complete the movement through this lander burn"))
            return "PLANNER: lists lander burns as destinations (H5e: no stopping there; the game also refuses to enter one it cannot get out of)";
        if (why.contains("Belt Roll (dice)")) return "DICE: the whole Rocket burns up in a belt - the planner reports only the worst potential roll";
        if (why.contains("Neptune")) return "PLANNER MAP: no Neptune zone (solar cards and solar supports keep working there; Uranus solar -4 instead of -5)";
        if (why.startsWith("fuel left differs")) return "FUEL (route works, planner over-estimates the fuel): " + lastFuelCause;
        if (lastThrustCause != null) return "PLANNER THRUST too high -> " + lastThrustCause;
        if (lastNeptuneSolar && (why.contains("burn limit") || why.contains("coasting")))
            return "PLANNER MAP: no Neptune zone (solar cards and solar supports keep working there; Uranus solar -4 instead of -5)";
        if (why.contains("coasting") && lastBonusPivot)
            return "PLANNER: keeps a bonus pivot for later (pays the Pivot with burns) - the rule: a rocket always uses its bonus pivot";
        if (lastOberthFold) return "ENGINE MODEL: support thrust folded into the planner's base thrust inflates the Solar Oberth bonus (H8e: base thrust)";
        return "UNEXPLAINED: " + why;
    }

    static String fmt(double d) { return Math.abs(d - Math.rint(d)) < 1e-9 ? String.valueOf((long) Math.rint(d)) : String.format(java.util.Locale.ROOT, "%.3f", d); }

    /** The last PathNode of this turn at a Space. */
    static PathNode nodeAt(List<PathNode> moves, String id) {
        PathNode last = null;
        for (PathNode n : moves) if (n.nodeId().equals(id)) last = n;
        return last;
    }

    static String landedOn(GameMap map, List<String> spaces, int k) {
        String site = k > 0 ? spaces.get(k - 1) : "?";
        GameMap.Node n = map.node(site);
        return n == null ? "" : " [landed on " + (n.name != null ? n.name : site) + ", size " + n.size + "]";
    }

    static String describe(List<PathNode> path) {
        StringBuilder b = new StringBuilder();
        int turn = -1;
        for (PathNode n : path) {
            if (n.turns() != turn) { b.append(" |T").append(n.turns()).append(n.afterburnedHere() > 0 ? "+AB" : "").append(n.jettisonedHere() > 0 ? "-J" + n.jettisonedHere() : "").append(":"); turn = n.turns(); }
            b.append(' ').append(n.nodeId());
        }
        PathNode e = path.get(path.size() - 1);
        return b + "  (fuel left " + e.fuelStepsRemaining() + ")";
    }

    static String specsText(List<EngineSpec> specs) {
        StringBuilder b = new StringBuilder();
        for (EngineSpec e : specs) {
            b.append('[').append(e.baseThrust()).append('/').append(e.fuelConsumptionNum()).append(e.fuelConsumptionDen() == 1 ? "" : "/" + e.fuelConsumptionDen())
                    .append(e.solarPowered() ? " solar" : "").append(e.bonusPivots() > 0 ? " piv" + e.bonusPivots() : "")
                    .append(e.canAfterburn() ? " ab" + e.afterburnFuelCost() : "").append(e.magSail() ? " mag" : "").append(e.decommissionsOnAerobrake() ? " sail" : "").append(']');
        }
        return b.toString();
    }
}
