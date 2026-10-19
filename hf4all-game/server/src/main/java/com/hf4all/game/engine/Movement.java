package com.hf4all.game.engine;

import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.data.Rules;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.Engine;
import com.hf4all.game.model.Fuel;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.MoveState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;
import com.hf4all.game.util.Fmt;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.model.Fraction;
import com.hf4all.planner.model.Season;
import com.hf4all.planner.rules.MoveRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.hf4all.game.engine.RuleError.check;

/**
 * Rocket movement (H), one move per turn (ported from the former browser engine):
 * {@link #plan} previews a thrust triangle activation (H2, H3), {@link #start}
 * commits it, {@link #options} / {@link #step} move Space by Space (H4-H10)
 * and {@link #end} rounds fractional fuel up (H5b). {@link #evaluate} is pure:
 * it prices one route from a move state without touching the game.
 *
 * <p>Fuel arithmetic uses the planner's exact {@link Fraction} (as the
 * planner's Pathfinder does for burn costs), so 1/3 or 1/10 fuel per burn and
 * half lander burns add up without rounding error.
 */
public final class Movement {

    private Movement() {}

    private static GameMap map() { return GameData.get().map; }
    private static Rules rules() { return GameData.get().rules; }
    private static Player me(GameState g) { return g.players.get(g.current); }
    private static Integer zoneSolar(String zone) { return rules().zone(zone).solar(); }
    /** Solar-Power modifier in the Neptune zone when pushed by a Powersat (H3c, J5d). */
    static final int NEPTUNE_PUSHED_SOLAR = -6;

    /* ------------------------------------------------------------- planning */

    public static final class Part {
        public String label;
        public int value;
        public Part() {}
        public Part(String label, int value) { this.label = label; this.value = value; }
    }

    /** Activation preview of one thrust triangle (the JS plan() result). */
    public static final class Plan {
        public int idx;
        public String name;
        public String deck;
        public int base;
        public double fuelBase;
        public String grade;
        public boolean canAfterburn;
        public int abSteps;
        public boolean afterburn;
        public List<Part> parts = new ArrayList<>();
        public boolean ok = true;
        public String reason;
        public List<Integer> chain = new ArrayList<>();
        public Integer uid;
        public List<Cards.Slot> slots = new ArrayList<>();
        public List<String> chainNames = new ArrayList<>();
        public int thermsNeeded;
        public int thermsHave;
        public String weightClass;
        public boolean pushed;
        public int net;
        public double fuelCons;
        public double fuelMul;
        public int fuelAvail;
        public boolean gradeOk;
        public int burnLimit;
        public boolean coastOnly;
        public int pivots;
        /** The activated thruster as the planner's engine model (not sent to the client). */
        public transient EngineSpec engine;

        void fail(String why) { ok = false; if (reason == null) reason = why; }
    }

    /** Options of an activation: a negotiated push, the player's support chain (J1c). */
    public static final class PlanOpts {
        public boolean push;
        public Map<String, Integer> chain;
    }

    /** Every card in the Rocket with a thrust triangle, with its activation preview. */
    /* Where the game and the planner apply a movement rule differently (planner.rules.MoveRules):
     * the game's calls, made against the rulebook. */
    /** H5e: no fixed thrust requirement on lander burns - only that the move can be completed. */
    static final boolean LANDER_BURN_THRUST_GATE = false;
    /** Crossing a one-way aerobrake path keeps the Bonus Burns. */
    static final boolean ONE_WAY_CLEARS_BONUS = false;
    /** H3a: an afterburn adds 1 to the net thrust. */
    static final int AFTERBURN_THRUST_GAIN = 1;

    /** The game's season name as the planner's {@link Season}. */
    static Season plannerSeason(String season) { return Season.valueOf(season.toUpperCase(java.util.Locale.ROOT)); }

    public static List<Plan> thrustOptions(GameState g, PlanOpts opts) {
        Player p = me(g);
        Stack r = p.stacks.rocket;
        List<Plan> out = new ArrayList<>();
        if (r == null) return out;
        Cards.Analysis an = Cards.analyze(r.cards, map().zoneOf(r.node));
        for (int i = 0; i < an.st.size(); i++) if (an.st.get(i).thrust != null) out.add(plan(g, i, false, opts));
        return out;
    }

    public static Plan plan(GameState g, int idx, boolean afterburn, PlanOpts opts) {
        if (opts == null) opts = new PlanOpts();
        Player p = me(g);
        Stack r = p.stacks.rocket;
        String zone = map().zoneOf(r.node);
        CardStats s0 = Cards.stats(r.cards.get(idx));
        boolean pushed = s0.thrust != null && Boolean.TRUE.equals(s0.thrust.push) && (StateOps.hasPowersat(g, p) || opts.push);
        Cards.Analysis an = Cards.analyze(r.cards, zone, pushed);
        CardStats s = an.st.get(idx);
        com.hf4all.game.data.CardDb.Thrust t = s.thrust;
        Plan res = new Plan();
        res.idx = idx;
        res.name = s.name;
        res.deck = s.deck;
        res.base = t.t;
        res.fuelBase = t.f;
        res.grade = t.g;
        res.canAfterburn = t.ab != null && t.ab != 0;
        res.abSteps = t.ab != null ? t.ab : 0;
        res.afterburn = afterburn;
        res.uid = r.cards.get(idx).uid;

        if (!an.supported[idx]) {
            String why = String.join("; ", an.reasons.get(idx));
            res.fail(why.isEmpty() ? "not Operational" : why);
        }
        Cards.ChainResult cs;
        if (an.supported[idx]) cs = Cards.chainSlots(an, idx, zoneSolar(zone), opts.chain);
        else { cs = new Cards.ChainResult(); cs.chain = new ArrayList<>(List.of(idx)); cs.slots = new ArrayList<>(); }
        List<Integer> chain = cs.chain;
        res.chain = chain;
        res.slots = cs.slots;
        for (Cards.Slot sl : cs.slots) {
            if (sl.invalid) {
                res.fail(an.st.get(sl.k).name + " cannot be supported by the chosen " + (sl.type.equals("gen") ? "generator" : "reactor") + " (J1c)");
                break;
            }
        }
        for (int j : chain) if (j != idx) res.chainNames.add(an.st.get(j).name);
        // PROJECT VALKYRIE: activating it decommissions the Colocated cards with Rad-Hard < 4; a Felony if Humans die (J1c).
        if (chain.stream().anyMatch(j -> an.st.get(j).ab("valkyrie")) && !StateOps.canFelony(g, p) && valkyrieKillsHumans(p, r.node)) {
            res.fail("Project Valkyrie would decommission a Human with Rad-Hard < 4: a Felony (needs the Felonious privilege or Anarchy)");
        }
        int thermsNeeded = Cards.chainTherms(an, chain);
        res.thermsNeeded = thermsNeeded;
        res.thermsHave = an.therms + (afterburn ? 1 : 0);
        if (an.supported[idx] && thermsNeeded > res.thermsHave) {
            res.fail("needs " + thermsNeeded + " therms of cooling (" + an.therms + " available" +
                    (res.canAfterburn && !afterburn && thermsNeeded == an.therms + 1 ? " - afterburn gives +1" : "") + ")");
        }
        Fuel tank = r.fuel != null ? r.fuel : FuelTrack.create(Math.max(1, StateOps.cargoMass(p, "rocket")));
        int fuel = FuelTrack.fuelSteps(tank);
        if (tank.dry > FuelTrack.MAX_DRY_TO_MOVE) res.fail("Dry Mass " + tank.dry + " exceeds 23 (F2a)");

        int wetStep = tank.wet;
        res.parts.add(new Part("Base thrust (" + s.name + ")", t.t));
        int abGain = 0;
        if (afterburn) {
            if (!res.canAfterburn) res.fail("this thrust triangle cannot afterburn");
            else if (fuel < t.ab) res.fail("afterburn needs " + t.ab + " fuel steps");
            else if (!FuelTrack.gradeOk(t.g, tank.grade)) res.fail("fuel grade too low to afterburn");
            else { wetStep -= t.ab; abGain = AFTERBURN_THRUST_GAIN; res.parts.add(new Part("Afterburn (" + t.ab + " fuel steps)", abGain)); }
        }
        FuelTrack.WeightClass wc = FuelTrack.weightClass(wetStep);
        res.weightClass = wc.name();
        res.parts.add(new Part("Weight class: " + wc.name() + " (Wet Mass " + FuelTrack.label(wetStep) + ")", wc.mod()));

        // J5d: modifiers count only for the thruster and the supports it needs (recursively),
        // not for supports needed only by a reactor or a radiator.
        java.util.Set<Integer> modded = modifierMembers(an, idx, cs.slots);
        boolean solar = false;
        for (int j : chain) {
            if (!modded.contains(j)) continue;
            CardStats c = an.st.get(j);
            if (c.isSolar() || (c.mod != null && Boolean.TRUE.equals(c.mod.solar))) { solar = true; break; }
        }
        // The solar modifier comes from the game's zones: none in the Neptune zone, where only a
        // Powersat push powers solar cards (N4a); Uranus' -5 is an ordinary modifier here.
        int solarMod = 0;
        if (solar) {
            Integer sm = zoneSolar(zone);
            if (sm == null && pushed) { solarMod = NEPTUNE_PUSHED_SOLAR; res.parts.add(new Part("Solar power (" + rules().zone(zone).name() + " zone, pushed by a Powersat)", solarMod)); }
            else if (sm == null) res.fail("Solar-Powered cards are not Operational in the Neptune zone");
            else { solarMod = sm; res.parts.add(new Part("Solar power (" + rules().zone(zone).name() + " zone)", sm)); }
        }
        int extra = 0;
        if (pushed) {
            res.parts.add(new Part("Beamed power push (Powersat)", 1));
            extra += 1;
            if (s.ab("pushThrust")) { res.parts.add(new Part(s.name + " push bonus", s.abInt("pushThrust"))); extra += s.abInt("pushThrust"); }
        }
        double fuelMul = 1;
        for (int j : chain) {
            if (j == idx || !modded.contains(j)) continue;
            com.hf4all.game.data.CardDb.Mod m = an.st.get(j).mod;
            if (m == null) continue;
            if (m.thrust != null && m.thrust != 0) { res.parts.add(new Part("Support: " + an.st.get(j).name, m.thrust)); extra += m.thrust; }
            if (m.fuelMul != null && m.fuelMul != 0) fuelMul *= m.fuelMul;
        }
        res.pushed = pushed;
        // H3 with the planner's formula (base + weight class + solar), then afterburn, push and supports.
        res.net = MoveRules.netThrust(t.t, wc.mod(), solarMod) + abGain + extra;
        res.fuelCons = t.f * fuelMul;
        res.fuelMul = fuelMul;
        res.fuelAvail = fuel - (afterburn && res.canAfterburn ? t.ab : 0);
        res.gradeOk = FuelTrack.gradeOk(t.g, tank.grade) || res.fuelCons == 0;
        res.burnLimit = MoveRules.burnLimit(res.net);
        res.coastOnly = res.net < 1;
        res.pivots = t.pivots != null ? t.pivots : 0;
        // The activated thruster as the planner's engine model.
        try {
            Fraction perBurn = Fractions.of(res.fuelCons);
            res.engine = new EngineSpec(t.t, perBurn.numerator(), perBurn.denominator(), solar, res.pivots,
                    res.canAfterburn ? res.abSteps : 0, res.canAfterburn ? AFTERBURN_THRUST_GAIN : 0,
                    Boolean.TRUE.equals(s.magSail), Boolean.TRUE.equals(s.sail));
        } catch (IllegalArgumentException e) {
            res.fail(e.getMessage());
        }
        if (g.turn.moved) res.fail("your Rocket already moved this turn (H1b)");
        return res;
    }

    /**
     * Chain members whose movement modifiers apply (J5d): the activated card and, recursively,
     * the supports picked for it, except supports picked only for a reactor or a radiator.
     */
    static java.util.Set<Integer> modifierMembers(Cards.Analysis an, int idx, List<Cards.Slot> slots) {
        java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
        out.add(idx);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Cards.Slot sl : slots) {
                if (sl.pick < 0 || !out.contains(sl.k)) continue;
                String deck = an.st.get(sl.k).deck;
                if ("reactor".equals(deck) || "radiator".equals(deck)) continue;
                if (out.add(sl.pick)) grew = true;
            }
        }
        return out;
    }

    public static void start(GameState g, int idx, boolean afterburn, PlanOpts opts) {
        check(g.move == null, "Already moving.");
        check(g.pending == null, "Resolve the pending decision first.");
        Player p = me(g);
        Stack r = p.stacks.rocket;
        check(r != null && !r.cards.isEmpty(), "You have no Rocket.");
        check(idx >= 0 && idx < r.cards.size() && Cards.stats(r.cards.get(idx)).thrust != null, "That card has no thrust triangle.");
        Plan pl = plan(g, idx, afterburn, opts);
        check(pl.ok, "Cannot activate " + pl.name + ": " + pl.reason + ".");
        if (r.fuel == null) r.fuel = FuelTrack.create(Math.max(1, StateOps.cargoMass(p, "rocket")));
        EngineSpec engine = pl.engine;
        if (afterburn) FuelTrack.burn(r.fuel, engine.afterburnFuelCost());
        // Heatsink fountains that had to provide cooling flip to light (Orientation c).
        if (pl.thermsNeeded != 0) Actions.useCooling(g, p, "rocket", pl.thermsNeeded, afterburn ? 1 : 0, pl.pushed);
        MoveState m = new MoveState();
        m.thrusterUid = pl.uid;
        for (int j : pl.chain) m.chainUids.add(r.cards.get(j).uid);
        m.name = pl.name;
        m.net = pl.net;
        m.base = engine.baseThrust();
        m.fuelCons = pl.fuelCons;
        m.grade = pl.grade;
        m.gradeOk = pl.gradeOk;
        m.burnLimit = pl.burnLimit;
        m.burnsUsed = 0;
        m.bonus = 0;
        m.bonusPivots = engine.bonusPivots();
        m.spent = 0;
        m.fuelAvail = pl.fuelAvail;
        m.afterburn = afterburn;
        m.node = r.node;
        m.start = r.node;
        m.path.add(r.node);
        m.coastOnly = pl.coastOnly;
        m.magSail = engine.magSail();
        m.sail = engine.decommissionsOnAerobrake();
        m.weightClass = pl.weightClass;
        // Only a negotiated push (N4a) is remembered; a Powersat of the player's own is re-checked.
        if (pl.pushed && opts != null && opts.push && !StateOps.hasPowersat(g, p)) m.pushed = true;
        g.move = m;
        Dice.log(g, p.name + " activates " + pl.name + (afterburn ? " with afterburn" : "") + ": net thrust " + pl.net +
                ", fuel " + Fmt.num(pl.fuelCons) + "/burn" + (pl.chainNames.isEmpty() ? "" : " (supports: " + String.join(", ", pl.chainNames) + ")") + ".");
        // Project Valkyrie: when activated, Colocated cards (all the player's stacks on the
        // Rocket's Space) with Rad-Hard < 4 are decommissioned.
        boolean valkyrie = false;
        for (int u : m.chainUids) {
            for (CardInst c : r.cards) if (c.uid != null && c.uid == u && Cards.stats(c).ab("valkyrie")) valkyrie = true;
        }
        if (valkyrie) {
            boolean humans = false;
            for (String k : StateOps.ownStackKeysAt(p, r.node)) {
                Stack s = p.stacks.get(k);
                for (int i = s.cards.size() - 1; i >= 0; i--) {
                    CardInst c = s.cards.get(i);
                    CardStats st = Cards.stats(c);
                    if (st.radOr0() < 4 && !st.ab("valkyrie")) {
                        if (c.crew != null) humans = true;
                        Actions.decommission(g, p.idx, k, i, Actions.Decom.of(true, false, "Project Valkyrie radiation"));
                    }
                }
            }
            if (humans) {
                g.turn.felony = true;
                Dice.log(g, "Felony: " + p.name + "'s Project Valkyrie kills Humans.", null, "bad");
            }
            StateOps.cleanupStacks(g, p);
            if (p.stacks.rocket == null) { g.move = null; g.turn.moved = true; return; }
        }
        g.diceLocked = false;
    }

    /** Would PROJECT VALKYRIE decommission a Human (Rad-Hard < 4) in one of the player's stacks on the Space? */
    private static boolean valkyrieKillsHumans(Player p, String node) {
        for (String k : StateOps.ownStackKeysAt(p, node)) {
            for (CardInst c : p.stacks.get(k).cards) if (c.crew != null && Cards.stats(c).radOr0() < 4) return true;
        }
        return false;
    }

    /** Name of an Operational card that lets the Rocket enter aerobrakes safely, or null. */
    public static String aeroSafe(GameState g) {
        Stack r = me(g).stacks.rocket;
        if (r == null) return null;
        Cards.Analysis an = Cards.analyze(r.cards, map().zoneOf(r.node));
        for (int j = 0; j < an.st.size(); j++) if (an.st.get(j).ab("aeroSafe") && Cards.operational(an, j)) return an.st.get(j).name;
        return null;
    }

    public static boolean thrusterAlive(GameState g) {
        Stack r = me(g).stacks.rocket;
        if (r == null) return false;
        int i = thrusterIndex(r, g.move.thrusterUid);
        return i >= 0 && rocketAnalysis(g).supported[i];
    }

    private static int thrusterIndex(Stack r, Integer uid) {
        for (int j = 0; j < r.cards.size(); j++) if (r.cards.get(j).uid != null && r.cards.get(j).uid.equals(uid)) return j;
        return -1;
    }

    /** The moving Rocket's support analysis: pushed if the move was, or if the activated thruster is pushable and the player has a Powersat. */
    static Cards.Analysis rocketAnalysis(GameState g) {
        Player p = me(g);
        Stack r = p.stacks.rocket;
        int i = thrusterIndex(r, g.move.thrusterUid);
        CardStats t = i >= 0 ? Cards.stats(r.cards.get(i)) : null;
        boolean pushed = Boolean.TRUE.equals(g.move.pushed) || (t != null && t.thrust != null && Boolean.TRUE.equals(t.thrust.push) && StateOps.hasPowersat(g, p));
        return Cards.analyze(r.cards, map().zoneOf(r.node), pushed);
    }

    /* ------------------------------------------------------------- engines */

    /** A thrust triangle needing no generator or reactor: always available, nothing to build. */
    static boolean simpleEngine(CardStats s) {
        return s.thrust != null && s.reqList("gen").isEmpty() && s.reqList("reactor").isEmpty();
    }

    /**
     * An engine the current player can activate: one they built (id set) or a card
     * that needs no supports (simple). {@code chain} is the index-based support choice
     * for {@link #plan} / {@link #start}; {@code planAb} is the preview with afterburn.
     */
    public static final class EngineView {
        public String id;
        public String name;
        public int idx;
        public Map<String, Integer> chain;
        public boolean simple;
        public Plan plan;
        public Plan planAb;
    }

    record Resolved(int idx, Map<String, Integer> chain) {}

    private static int indexOfUid(Stack s, int uid) {
        for (int i = 0; i < s.cards.size(); i++) if (s.cards.get(i).uid != null && s.cards.get(i).uid == uid) return i;
        return -1;
    }

    /** The engine's thruster index and support choice in the stack, or null if one of its cards is gone. */
    static Resolved resolve(Stack r, Engine e) {
        int i = indexOfUid(r, e.thruster);
        if (i < 0) return null;
        Map<String, Integer> chain = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> pk : e.picks.entrySet()) {
            int colon = pk.getKey().indexOf(':');
            int k = indexOfUid(r, Integer.parseInt(pk.getKey().substring(0, colon)));
            int j = indexOfUid(r, pk.getValue());
            if (k < 0 || j < 0) return null;
            chain.put(k + pk.getKey().substring(colon), j);
        }
        return new Resolved(i, chain);
    }

    /** The current player's engines: the always-available cards first, then the built ones. */
    public static List<EngineView> engines(GameState g) {
        List<EngineView> out = new ArrayList<>();
        Stack r = me(g).stacks.rocket;
        if (r == null) return out;
        for (int i = 0; i < r.cards.size(); i++) if (simpleEngine(Cards.stats(r.cards.get(i)))) out.add(engineView(g, null, i, null));
        if (r.engines != null) {
            for (Engine e : r.engines) {
                Resolved rs = resolve(r, e);
                if (rs != null) out.add(engineView(g, e.id, rs.idx(), rs.chain()));
            }
        }
        return out;
    }

    private static EngineView engineView(GameState g, String id, int idx, Map<String, Integer> chain) {
        PlanOpts o = new PlanOpts();
        o.chain = chain;
        EngineView v = new EngineView();
        v.id = id;
        v.idx = idx;
        v.chain = chain;
        v.simple = id == null;
        v.plan = plan(g, idx, false, o);
        if (v.plan.canAfterburn) v.planAb = plan(g, idx, true, o);
        v.name = v.plan.name + (v.plan.chainNames.isEmpty() ? "" : " + " + String.join(" + ", v.plan.chainNames));
        return v;
    }

    /**
     * Build an engine (J1c): the thrust-triangle card at {@code idx} with an explicit
     * support for every requirement of its chain ({@code choice}: "cardIdx:gen|reactor"
     * -> provider index). Returns its id.
     */
    public static String saveEngine(GameState g, int idx, Map<String, Integer> choice) {
        check(g.gameOver == null, "The game is over.");
        check(g.move == null, "Engines are built before a movement, not during it.");
        Stack r = me(g).stacks.rocket;
        check(r != null, "You have no Rocket.");
        check(idx >= 0 && idx < r.cards.size(), "No such card.");
        CardStats s = Cards.stats(r.cards.get(idx));
        check(s.thrust != null, "That card has no thrust triangle.");
        check(!simpleEngine(s), s.name + " needs no supports: it is always available.");
        String zone = map().zoneOf(r.node);
        Cards.Analysis an = Cards.analyze(r.cards, zone);
        Cards.ChainResult cs = Cards.chainSlots(an, idx, zoneSolar(zone), choice);
        Engine e = new Engine();
        e.thruster = uidOf(r, idx);
        for (Cards.Slot sl : cs.slots) {
            String what = (sl.type.equals("gen") ? "generator" : "reactor") + " for " + an.st.get(sl.k).name;
            check(!sl.options.isEmpty(), "No card in the Rocket can be the " + what + ".");
            check(sl.chosen && !sl.invalid && sl.pick >= 0, "Choose the " + what + " (J1c).");
            e.picks.put(uidOf(r, sl.k) + ":" + sl.type, uidOf(r, sl.pick));
        }
        if (r.engines == null) r.engines = new ArrayList<>();
        for (Engine x : r.engines) check(!(x.thruster == e.thruster && x.picks.equals(e.picks)), "That engine is already built.");
        e.id = "E" + Operations.newUid(g);
        r.engines.add(e);
        return e.id;
    }

    private static int uidOf(Stack r, int i) {
        Integer u = r.cards.get(i).uid;
        check(u != null, "That card has no id.");
        return u;
    }

    public static void deleteEngine(GameState g, String id) {
        check(g.move == null, "Not during a movement.");
        Stack r = me(g).stacks.rocket;
        check(r != null && r.engines != null && r.engines.removeIf(e -> e.id.equals(id)), "No such engine.");
        if (r.engines.isEmpty()) r.engines = null;
    }

    /** Activate a built engine (H2): its thruster with its saved support chain. */
    public static void startEngine(GameState g, String id, boolean afterburn, PlanOpts opts) {
        Stack r = me(g).stacks.rocket;
        check(r != null, "You have no Rocket.");
        Engine e = r.engines == null ? null : r.engines.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
        check(e != null, "No such engine.");
        Resolved rs = resolve(r, e);
        check(rs != null, "A card of that engine is no longer in the Rocket.");
        if (opts == null) opts = new PlanOpts();
        opts.chain = rs.chain();
        start(g, rs.idx(), afterburn, opts);
    }

    /** Drop every built engine one of whose cards left its Rocket (after each command). */
    public static void pruneEngines(GameState g) {
        for (Player p : g.players) {
            Stack r = p.stacks.rocket;
            if (r == null || r.engines == null) continue;
            r.engines.removeIf(e -> resolve(r, e) == null);
            if (r.engines.isEmpty()) r.engines = null;
        }
    }

    /* ------------------------------------------------------------- pricing */

    /** One priced move option (the JS evaluate() result). */
    public static final class MoveOption {
        public GameMap.Route route;
        public String to;
        public boolean legal = true;
        public String reason;
        public int burns;
        public int bonusUsed;
        public boolean pivotUsed;
        public boolean pivot;
        public double fuel;
        public List<String> flags = new ArrayList<>();
        public Boolean factoryLiftoff;
        public Boolean hazardRoll;
        public Integer factoryOwner;
        public StateOps.Check hijack;
        public Boolean factoryLanding;
        public Boolean aerobrake;
        public Boolean endsMove;
        public Boolean aeroBurn;
        public String hazard;
        public Boolean cantStop;
        public Boolean canContinue;
        public MoveState next;
        transient Fraction fuelF = Fraction.ZERO;

        MoveOption no(String why) {
            legal = false;
            if (reason == null) reason = why;
            return this;
        }
    }

    /** Mutable accumulators of one evaluation (the closure variables of the JS). */
    private static final class Acc {
        int bonus, bonusPivots, burnsUsed;
        Fraction spent;
    }

    /** Price moving along route `route` from move state `m`. Pure. */
    public static MoveOption evaluate(GameState g, MoveState m, GameMap.Route route, int depth) {
        Player p = me(g);
        GameMap.Node X = map().node(m.node), B = map().node(route.to);
        MoveOption o = new MoveOption();
        o.route = route;
        o.to = route.to;
        String season = StateOps.season(g);
        Season sea = plannerSeason(season);
        boolean alive = !Boolean.FALSE.equals(m.thrusterAlive);

        if (m.landed) return o.no("movement ended with the landing");
        if (m.path.size() > 1 && MoveRules.isUTurn(m.prevFirst, route.first)) return o.no("no U-turn (H4e)");
        if (!MoveRules.venusOpen(B.planner, sea)) return o.no("Venus flyby only in season blue (H8c)");
        if (!MoveRules.seasonGateOpen(map().solar(), B.planner, sea)) return o.no("synodic comet: only in season " + map().synodic(route.to) + " (B7h)");
        if (!MoveRules.seasonGateOpen(map().solar(), X.planner, sea)) return o.no("cannot leave a synodic comet out of season " + map().synodic(m.node));

        Acc a = new Acc();
        a.bonus = m.bonus;
        a.bonusPivots = m.bonusPivots;
        a.burnsUsed = m.burnsUsed;
        a.spent = Fractions.of(m.spent);
        Fraction fuelCons = Fractions.of(m.fuelCons);

        // Liftoff (H6): leaving a Site at the start of the move.
        if (X.t.equals("site") && m.path.size() == 1 && X.size > 0) {
            Site site = g.sites.get(X.id);
            if (MoveRules.poweredLanding(m.net, X.size)) o.flags.add("Powered liftoff (thrust " + m.net + " > size " + X.size + ")");
            else if (site != null && site.factory) {
                if (B.land > 0) return o.no("factory-assist cannot enter a lander burn (H6c)");
                o.factoryLiftoff = true;
                o.hazardRoll = !(site.colony || StateOps.hasPowersat(g, p));
                factoryUse(g, p, o, X.id, site, false);
                o.flags.add("Factory-assisted liftoff" + (o.hazardRoll ? " (Hazard Roll)" : "") + factoryUseFlag(g, o));
            } else return o.no("liftoff needs net thrust > " + X.size + " (H6a)");
        }

        // Direction change at a labelled Space (H4c/H4d).
        if (MoveRules.leavesOffLane(map().hasLabels(m.node), route.dep, m.inLab)) {
            if (MoveRules.isPivot(X.planner, m.inLab, route.dep)) {
                o.pivot = true;
                if (a.bonusPivots > 0) { a.bonusPivots--; o.pivotUsed = true; o.flags.add("Pivot (bonus pivot)"); }
                else {
                    int fromBonus = MoveRules.pivotBurnsFromBonus(a.bonus);
                    a.bonus -= fromBonus;
                    o.bonusUsed += fromBonus;
                    if (!pay(o, m, a, fuelCons, alive, MoveRules.PIVOT_BURNS - fromBonus, Fraction.ONE)) return o;
                    o.flags.add("Pivot: 2 burns");
                }
            }
        }

        // Entering the Space.
        if (B.t.equals("burn")) {
            if (!MoveRules.bonusPaysBurn(B.planner)) {
                if (!MoveRules.landerBurnThrustOk(B.planner, m.net, LANDER_BURN_THRUST_GATE)) return o.no("lander burn needs thrust " + B.treq);
                // No fixed thrust requirement here: the planner's thrustRequired on lander burns
                // (Size + 1 of the Site below) would also forbid descending to an aerobrake path
                // (H8c: Venus lander burn, then aerobrake). H5e only asks that the move can be
                // completed, which the look-ahead below checks (powered landing needs thrust > Size).
                if (!pay(o, m, a, fuelCons, alive, 1, B.planner.landing())) return o;
                o.flags.add((B.land == 0.5 ? "Half lander burn" : "Lander burn") + " (no bonus burns, cannot stop)");
            } else if (a.bonus > 0) {
                a.bonus--;
                o.bonusUsed++;
                o.flags.add("Burn paid with a Bonus Burn");
            } else {
                if (!pay(o, m, a, fuelCons, alive, 1, Fraction.ONE)) return o;
                o.flags.add("Burn");
            }
        }

        // Landing on a Site (H6).
        if (B.t.equals("site")) {
            Site site = g.sites.get(B.id);
            // An aerobrake landing follows a one-way aerobrake path (H6b, B7e). The route knows
            // whether it crossed a one-way edge; a bend into a big Site is not an aerobrake by
            // itself (Europa and Io have bent routes from their radiation belts, and no atmosphere).
            boolean aero = MoveRules.aerobrakeLanding(route.oneWay, route.viaDeco, "aero".equals(X.haz), B.size, true);
            if (MoveRules.poweredLanding(m.net, B.size)) o.flags.add("Powered landing");
            else if (aero) { o.aerobrake = true; o.flags.add("Aerobrake landing"); }
            else if (site != null && site.factory && X.land == 0 && alive) {
                o.factoryLanding = true;
                o.hazardRoll = !(site.colony || StateOps.hasPowersat(g, p));
                factoryUse(g, p, o, B.id, site, crewAboard(g, p));
                o.flags.add("Factory-assisted landing" + (o.hazardRoll ? " (Hazard Roll)" : "") + factoryUseFlag(g, o));
            } else return o.no("landing needs net thrust > " + B.size + " (H6a)");
            o.endsMove = true;
        }

        if (B.haz != null && !(Boolean.TRUE.equals(o.factoryLiftoff) && m.path.size() == 1)) {
            String safe = B.haz.equals("aero") ? aeroSafe(g) : null;
            if (MoveRules.sailBurnsUp(true, B.haz.equals("aero"), true)) o.aeroBurn = true;
            if (safe != null) o.flags.add("Aerobrake entered safely (" + safe + ")");
            else {
                o.hazard = B.haz;
                o.flags.add(B.haz.equals("aero") ? "Aerobrake Hazard roll" : "Crash Hazard roll");
            }
            if (Boolean.TRUE.equals(o.aeroBurn) && hasSails(g)) o.flags.add("Sails burn up!");
        }
        if (B.t.equals("radhaz")) o.flags.add("Radiation Belt: Belt Roll (1d6 - " + Math.max(0, m.net) + (sea == Season.RED ? " + " + MoveRules.RED_SEASON_BELT_PENALTY + " red" : "") + ")");
        boolean flyby = B.t.equals("flyby") || B.t.equals("venus");
        if (flyby && alive) o.flags.add("Flyby: +" + B.fly + " Bonus Burn" + (B.fly > 1 ? "s" : ""));
        if (B.oberth && alive && !m.oberth) o.flags.add("Solar Oberth: +" + MoveRules.oberthBonus(m.base, m.afterburn) + " Bonus Burns");
        boolean magSail = m.magSail && B.t.equals("radhaz") && !m.belts.contains(B.id) && alive;
        if (magSail) o.flags.add("Mag Sail: +" + MoveRules.MAG_SAIL_BELT_BONUS + " Bonus Burn");
        if (B.land > 0) o.cantStop = true;

        MoveState next = m.copy();
        next.bonus = a.bonus;
        next.bonusPivots = a.bonusPivots;
        next.burnsUsed = a.burnsUsed;
        next.spent = a.spent.toDouble();
        next.node = B.id;
        next.prev = m.node;
        next.prevFirst = route.via.isEmpty() ? m.node : route.via.get(route.via.size() - 1);
        next.inLab = route.arr;
        next.path.add(B.id);
        next.landed = Boolean.TRUE.equals(o.endsMove);
        next.bonus += MoveRules.flybyBonus(B.planner, alive);
        if (B.oberth && alive && !m.oberth) { next.bonus += MoveRules.oberthBonus(m.base, m.afterburn); next.oberth = true; }
        if (magSail) { next.bonus += MoveRules.MAG_SAIL_BELT_BONUS; next.belts.add(B.id); }
        next.bonus = MoveRules.bonusAfterOneWay(next.bonus, route.oneWay, ONE_WAY_CLEARS_BONUS);
        o.next = next;
        o.fuel = o.fuelF.toDouble();

        // A lander burn may only be entered if the move can continue past it (H5e).
        // The longest chain of lander burns on the map is 4 (Saturn).
        if (B.land > 0 && depth < 6) {
            boolean esc = false;
            for (MoveOption x : optionsFor(g, next, depth + 1)) {
                if (x.legal && (!Boolean.TRUE.equals(x.cantStop) || Boolean.TRUE.equals(x.canContinue))) { esc = true; break; }
            }
            if (!esc) return o.no("cannot complete the movement through this lander burn (H5e)");
            o.canContinue = true;
        }
        return o;
    }

    /** Pay n burns of fuelCons * frac each (H5). */
    private static boolean pay(MoveOption o, MoveState m, Acc a, Fraction fuelCons, boolean alive, int n, Fraction frac) {
        if (n <= 0) return true;
        if (m.coastOnly || !alive) { o.no("coasting: no Burns or Pivots allowed (H2b)"); return false; }
        if (a.burnsUsed + n > m.burnLimit) { o.no("burn limit " + m.burnLimit + " reached (H5c)"); return false; }
        Fraction cost = MoveRules.burnFuel(fuelCons, n, frac);
        if (cost.isGreaterThan(Fraction.ZERO) && !m.gradeOk) { o.no("fuel grade too low for this thruster (F4c)"); return false; }
        if (!MoveRules.fuelCovers(a.spent.add(cost), m.fuelAvail)) { o.no("insufficient Fuel (H5d)"); return false; }
        a.burnsUsed += n;
        a.spent = a.spent.add(cost);
        o.burns += n;
        o.fuelF = o.fuelF.add(cost);
        o.fuel = o.fuelF.toDouble();
        return true;
    }

    /** Factory-assist at an opponent's Factory (H6c): permission (N5) or a Factory Hijack (Felony). */
    private static void factoryUse(GameState g, Player p, MoveOption o, String siteId, Site site, boolean aboard) {
        if (site.claim == null || site.claim == p.idx) return;
        o.factoryOwner = site.claim;
        o.hijack = StateOps.felonyAt(g, p, siteId, aboard);
    }

    private static String factoryUseFlag(GameState g, MoveOption o) {
        if (o.factoryOwner == null) return "";
        return " at " + g.players.get(o.factoryOwner).name + "'s Factory: needs permission" + (o.hijack.ok ? " or a Factory Hijack" : "");
    }

    /** Does the current player's own Crew ride in their Rocket? */
    private static boolean crewAboard(GameState g, Player p) {
        Stack r = p.stacks.rocket;
        if (r == null) return false;
        for (CardInst c : r.cards) if (c.crew != null && StateOps.crewOwner(g, c, p.idx) == p.idx) return true;
        return false;
    }

    private static boolean hasSails(GameState g) {
        Stack r = me(g).stacks.rocket;
        if (r == null) return false;
        for (CardInst c : r.cards) if (c.crew == null && Boolean.TRUE.equals(Cards.stats(c).sail)) return true;
        return false;
    }

    static List<MoveOption> optionsFor(GameState g, MoveState m, int depth) {
        List<MoveOption> out = new ArrayList<>();
        for (GameMap.Route r : map().routes(m.node)) out.add(evaluate(g, m, r, depth));
        return out;
    }

    /** Options from the current position: the best option per destination (destinations in id order). */
    public static List<MoveOption> options(GameState g) {
        if (g.move == null) return new ArrayList<>();
        MoveState m = g.move.copy();
        m.thrusterAlive = thrusterAlive(g);
        TreeMap<String, MoveOption> byDest = new TreeMap<>(GameMap::jsKeyOrder);
        for (MoveOption o : optionsFor(g, m, 0)) {
            MoveOption cur = byDest.get(o.to);
            boolean better = cur == null || (o.legal && !cur.legal) || (o.legal == cur.legal && (o.burns + o.bonusUsed) < (cur.burns + cur.bonusUsed));
            if (better) byDest.put(o.to, o);
        }
        return new ArrayList<>(byDest.values());
    }

    /** Choices for a step: FINAO instead of a hazard roll; permission or hijack at an opponent's Factory. */
    public static final class StepChoices {
        public boolean finao;
        public boolean permission;
        public boolean hijack;
    }

    public static void step(GameState g, String to, StepChoices choices) {
        if (choices == null) choices = new StepChoices();
        check(g.move != null, "Not moving.");
        Player p = me(g);
        MoveOption opt = null;
        for (MoveOption o : options(g)) if (o.to.equals(to)) { opt = o; break; }
        check(opt != null, "That Space is not connected to your position.");
        check(opt.legal, "Illegal move: " + opt.reason + ".");
        Player owner = opt.factoryOwner != null ? g.players.get(opt.factoryOwner) : null;
        if (owner != null) {
            check(choices.permission || choices.hijack, "Factory-assist at " + owner.name + "'s Factory needs their permission (N5) or a Factory Hijack (Felony).");
            check(!choices.hijack || opt.hijack.ok, "Factory Hijack impossible: " + opt.hijack.reason + ".");
        }
        MoveState m = g.move;
        String factorySite = Boolean.TRUE.equals(opt.factoryLanding) ? to : m.node;
        MoveState nm = opt.next;
        m.bonus = nm.bonus;
        m.bonusPivots = nm.bonusPivots;
        m.burnsUsed = nm.burnsUsed;
        m.spent = nm.spent;
        m.node = nm.node;
        m.prev = nm.prev;
        m.prevFirst = nm.prevFirst;
        m.inLab = nm.inLab;
        m.path = nm.path;
        m.landed = nm.landed;
        m.oberth = nm.oberth;
        m.belts = nm.belts;
        m.routes.add(new MoveState.RouteRef(opt.route.from, opt.route.to, opt.route.via));
        Stack r = p.stacks.rocket;
        r.node = to;
        GameMap.Node B = map().node(to);
        Dice.log(g, p.name + " -> " + map().name(to) + (opt.flags.isEmpty() ? "" : " [" + String.join("; ", opt.flags) + "]"));
        if (owner != null && choices.hijack) {
            g.turn.felony = true;
            Dice.log(g, "Factory Hijack (Felony): " + p.name + " uses " + owner.name + "'s Factory on " + map().name(factorySite) + " without permission.", null, "bad");
        } else if (owner != null) {
            Dice.log(g, owner.name + " permits " + p.name + " to use their Factory on " + map().name(factorySite) + " for factory-assist (N5).");
        }

        // Factory-assist Hazard Roll (H6c).
        if ((Boolean.TRUE.equals(opt.factoryLiftoff) || Boolean.TRUE.equals(opt.factoryLanding)) && Boolean.TRUE.equals(opt.hazardRoll)) {
            if (Actions.hazardRoll(g, p.idx, "rocket", choices.finao, "factory-assist").lost()) { abort(g); return; }
        }
        // Sail burn-up (H6b) on any aerobrake, then the Hazard Roll (H7) unless entered safely.
        if (Boolean.TRUE.equals(opt.aeroBurn)) {
            for (int i = r.cards.size() - 1; i >= 0; i--) {
                if (r.cards.get(i).crew == null && Boolean.TRUE.equals(Cards.stats(r.cards.get(i)).sail)) {
                    Actions.decommission(g, p.idx, "rocket", i, Actions.Decom.of(true, false, "sail burn-up"));
                }
            }
            StateOps.cleanupStacks(g, p);
            if (p.stacks.rocket == null) { abort(g); return; }
        }
        if (opt.hazard != null) {
            if (Actions.hazardRoll(g, p.idx, "rocket", choices.finao, opt.hazard.equals("aero") ? "aerobrake" : "crash hazard").lost()) { abort(g); return; }
        }
        // Radiation belt (H10).
        if (B.t.equals("radhaz")) {
            int roll = Dice.d6(g, "Belt Roll", p.idx);
            // H10: a negative net thrust is not subtracted.
            int sev = MoveRules.beltSeverity(roll, Math.max(0, m.net), plannerSeason(StateOps.season(g)));
            Dice.log(g, "Belt Roll severity " + sev + ".", null, "dice");
            Stack rk = p.stacks.rocket;
            // An Ability works only while its card is Operational (J2e).
            Cards.Analysis immune = rocketAnalysis(g);
            for (int i = rk.cards.size() - 1; i >= 0; i--) {
                CardStats st = Cards.stats(rk.cards.get(i));
                if (st.ab("beltImmune") && Cards.operational(immune, i)) continue;
                if (st.radOr0() < sev) Actions.decommission(g, p.idx, "rocket", i, Actions.Decom.of(true, true, "radiation belt"));
            }
            StateOps.cleanupStacks(g, p);
            if (p.stacks.rocket == null) { abort(g); return; }
        }
        // Flyby Glitch Trigger (H8g).
        if ((B.t.equals("flyby") || B.t.equals("venus")) && p.stacks.rocket != null && p.stacks.rocket.glitch) {
            Actions.glitchRoll(g, p.idx, "rocket", "flyby");
            StateOps.cleanupStacks(g, p);
            if (p.stacks.rocket == null) { abort(g); return; }
        }
        if (!thrusterAlive(g)) Dice.log(g, "The activated thruster is no longer Operational: coasting only (H2b).");
        if (Boolean.TRUE.equals(opt.endsMove)) {
            markLanding(g, p, to);
            end(g);
        }
    }

    static void markLanding(GameState g, Player p, String siteId) {
        Stack r = p.stacks.rocket;
        List<Integer> humans = new ArrayList<>();
        if (r != null) for (CardInst c : r.cards) if (c.crew != null) humans.add(StateOps.crewOwner(g, c, p.idx));
        if (humans.isEmpty()) return;
        Site site = StateOps.site(g, siteId);
        if (site.visited.isEmpty() && humans.contains(p.idx)) {
            g.turn.gloryEligible = siteId;
            String z = map().zoneOf(siteId);
            if (Boolean.TRUE.equals(g.glory.get(z))) {
                Dice.log(g, "First Humans on " + map().name(siteId) + ": the " + rules().zone(z).name() + " glory chit can be taken.", null, "good");
            }
        }
        for (int h : humans) if (!site.visited.contains(h)) site.visited.add(h);
    }

    public static StateOps.Check canEnd(GameState g) {
        if (g.move == null) return new StateOps.Check(false, null);
        if (!map().canStop(g.move.node)) return new StateOps.Check(false, "You may not end movement on a lander burn (H5e).");
        return new StateOps.Check(true, null);
    }

    public static void end(GameState g) {
        check(g.move != null, "Not moving.");
        StateOps.Check c = canEnd(g);
        check(c.ok, c.reason);
        Player p = me(g);
        MoveState m = g.move;
        Stack r = p.stacks.rocket;
        int steps = MoveRules.spentSteps(Fractions.of(m.spent));
        if (r != null && steps > 0) FuelTrack.burn(r.fuel, Math.min(steps, FuelTrack.fuelSteps(r.fuel)));
        g.turn.moved = true;
        Dice.log(g, p.name + " ends movement at " + map().name(m.node) + ": " + m.burnsUsed + " burn" + (m.burnsUsed == 1 ? "" : "s") +
                ", " + steps + " fuel step" + (steps == 1 ? "" : "s") + " expended.");
        g.move = null;
        if (r != null) Actions.checkParade(g, p);
    }

    /** The Rocket was destroyed mid-move. */
    static void abort(GameState g) {
        g.move = null;
        g.turn.moved = true;
    }

    /** Leave movement mode without moving (before the first step only). */
    public static void cancel(GameState g) {
        check(g.move != null && g.move.path.size() == 1, "You already moved - end the movement instead.");
        check(!g.move.afterburn, "Afterburn fuel was already spent - end the movement instead.");
        g.move = null;
    }
}
