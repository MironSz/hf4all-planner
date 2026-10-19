package com.hf4all.game.engine;

import com.hf4all.game.data.GameMap;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;
import com.hf4all.game.util.Fmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.hf4all.game.engine.RuleError.check;
import static com.hf4all.game.engine.StateOps.STACK_NAME;

/**
 * Free actions (G) and the decommission / glitch / hazard primitives shared
 * by movement, operations and events (ported from the former browser engine).
 */
public final class Actions {

    private Actions() {}

    private static GameMap map() { return GameData().map; }
    private static com.hf4all.game.data.GameData GameData() { return com.hf4all.game.data.GameData.get(); }

    static Player me(GameState g) { return g.players.get(g.current); }

    static void requireFree(GameState g) {
        check(g.gameOver == null, "The game is over.");
        check(g.move == null, "Free actions cannot interrupt movement (H1c).");
        check(g.pending == null, "Resolve the pending decision first.");
    }

    /** Options of a decommission (E7). */
    public static final class Decom {
        boolean involuntary, ablate;
        String why;
        public static Decom of(boolean involuntary, boolean ablate, String why) {
            Decom d = new Decom();
            d.involuntary = involuntary;
            d.ablate = ablate;
            d.why = why;
            return d;
        }
    }

    /**
     * Decommission a card (E7): patent -> owner's Hand, Crew -> its owner's LEO.
     * Involuntary: heavy radiators ablate to light instead (E7d, with ablate),
     * a lost last thruster empties the Fuel Strip (E7a), FTs of an emptied stack are lost (E7b).
     */
    public static String decommission(GameState g, int pi, String key, int idx, Decom opts) {
        Player p = g.players.get(pi);
        Stack s = p.stacks.get(key);
        CardInst card = s.cards.get(idx);
        CardStats st = Cards.stats(card);
        if (opts.involuntary && "radiator".equals(st.deck) && !card.isLight() && opts.ablate) {
            card.orient = "light";
            Dice.log(g, p.name + "'s " + st.name + " ablates to its light orientation.", pi, null);
            if (key.equals("rocket")) StateOps.syncRocketDry(p);
            return "ablated";
        }
        s.cards.remove(idx);
        if (opts.involuntary) {
            if (g.booms == null) g.booms = new ArrayList<>();
            g.booms.add(new GameState.Boom(pi, key, StateOps.stackNode(p, key), st.name, opts.why));
        }
        if (card.crew != null) {
            Player owner = g.players.get(StateOps.crewOwner(g, card, pi));
            if (card.glory != null) {
                owner.glories.add(new Player.Glory(card.glory, 1));
                Dice.log(g, "Tragedy: " + owner.name + "'s Crew dies carrying the " + GameData().rules.zone(card.glory).name() + " glory (1 VP).", owner.idx, null);
                card.glory = null;
            }
            owner.stacks.leo.cards.add(card);
            if (!key.equals("leo") || owner != p) Dice.log(g, owner.name + "'s Crew is returned to LEO.", owner.idx, null);
        } else {
            p.hand.add(card.id);
            Dice.log(g, p.name + ": " + st.name + " decommissioned to Hand" + (opts.why != null && !opts.why.isEmpty() ? " (" + opts.why + ")" : "") + ".", pi, null);
        }
        if (opts.involuntary && s.cards.isEmpty() && !key.equals("leo")) {
            if (s.ftsOr0() > 0) Dice.log(g, s.fts + " FTs are lost with the stack.", pi, null);
            s.fts = 0;
        }
        if (key.equals("rocket")) {
            if (opts.involuntary && s.cards.stream().noneMatch(Cards::hasThrust) && s.fuel != null && FuelTrack.fuelSteps(s.fuel) > 0) {
                Dice.log(g, p.name + "'s Rocket lost its last thruster: its Fuel is lost.", pi, null);
                s.fuel = null;
            }
            StateOps.syncRocketDry(p);
        }
        return "decommissioned";
    }

    /**
     * Glitch Roll: before a glitched stack performs a Glitch Trigger. Every card in the
     * player's stacks Colocated with the Glitch whose rad-hardness equals the roll is
     * decommissioned (glossary: Glitch Roll), not only the glitched stack's.
     */
    public static Integer glitchRoll(GameState g, int pi, String key, String label) {
        Player p = g.players.get(pi);
        Stack s = p.stacks.get(key);
        if (s == null || !s.glitch) return null;
        String node = StateOps.stackNode(p, key);
        int v = Dice.d6(g, "Glitch Roll (" + (label != null && !label.isEmpty() ? label : STACK_NAME.get(key)) + ")", pi);
        for (String k : StateOps.STACKS) {
            Stack st = p.stacks.get(k);
            if (st == null || !node.equals(StateOps.stackNode(p, k))) continue;
            for (int i = st.cards.size() - 1; i >= 0; i--) {
                if (i >= st.cards.size()) continue;
                CardInst c = st.cards.get(i);
                if (k.equals("leo") && c.crew != null) continue; // a Crew in LEO would only return to LEO
                CardStats cs = Cards.stats(c);
                if (cs.rad != null && cs.rad == v) decommission(g, pi, k, i, Decom.of(true, true, "glitch"));
            }
        }
        StateOps.cleanupStacks(g, p);
        return v;
    }

    public static int finaoCost(GameState g, Player p) { return StateOps.privilegeActive(g, p, "openSourceFinao") ? 3 : 4; }

    public record Hazard(boolean finao, Integer roll, boolean lost) {}

    /** Hazard Roll (H7): on a 1 every card and FT in the stack is lost; FINAO buys safety. */
    public static Hazard hazardRoll(GameState g, int pi, String key, boolean finao, String label) {
        Player p = g.players.get(pi);
        if (finao) {
            int cost = finaoCost(g, p);
            check(p.aquas >= cost, "FINAO costs " + cost + " Aquas.");
            p.aquas -= cost;
            Dice.log(g, p.name + " pays " + cost + " Aquas for FINAO (" + label + ").", pi, null);
            return new Hazard(true, null, false);
        }
        int v = Dice.d6(g, "Hazard Roll (" + label + ")", pi);
        if (v != 1) return new Hazard(false, v, false);
        Stack s = p.stacks.get(key);
        Dice.log(g, "Disaster! " + p.name + "'s " + STACK_NAME.get(key) + " is lost.", pi, "bad");
        for (int i = s.cards.size() - 1; i >= 0; i--) decommission(g, pi, key, i, Decom.of(true, true, "hazard"));
        if (p.stacks.get(key) != null) p.stacks.get(key).fts = 0;
        if (key.equals("rocket") && p.stacks.rocket != null) p.stacks.rocket.fuel = null;
        StateOps.cleanupStacks(g, p);
        return new Hazard(false, v, true);
    }

    /** Ticker tape parade (L a): a glory-carrying Human back in LEO. */
    public static void checkParade(GameState g, Player p) {
        for (String k : StateOps.STACKS) {
            Stack s = p.stacks.get(k);
            if (s == null || !map().leo.equals(StateOps.stackNode(p, k))) continue;
            for (CardInst c : s.cards) {
                if (c.crew != null && c.glory != null) {
                    int vp = GameData().rules.zone(c.glory).gloryVp();
                    Player owner = g.players.get(StateOps.crewOwner(g, c, p.idx));
                    owner.glories.add(new Player.Glory(c.glory, vp));
                    Dice.log(g, "Ticker tape parade! " + owner.name + " brings the " + GameData().rules.zone(c.glory).name() + " glory home (" + vp + " VP)" +
                            (owner != p ? " aboard " + p.name + "'s " + STACK_NAME.get(k) : "") + ".", owner.idx, "good");
                    c.glory = null;
                }
            }
        }
    }

    /* ---------------- G1 Cargo transfer ---------------- */

    /** Move cards and/or FTs between two colocated stacks; `to` may be an empty slot, created here (E2c). */
    public static void transfer(GameState g, String from, String to, List<Integer> cardIdxs, int fts) {
        requireFree(g);
        Player p = me(g);
        check(!from.equals(to), "Pick two different stacks.");
        Stack src = p.stacks.get(from);
        check(src != null, "No " + STACK_NAME.get(from) + " stack.");
        String node = StateOps.stackNode(p, from);
        if (p.stacks.get(to) == null) {
            check(!to.equals("leo"), "LEO always exists.");
            p.stacks.set(to, Stack.at(node));
            Dice.log(g, p.name + " creates a new " + STACK_NAME.get(to) + " at " + map().name(node) + ".");
        }
        Stack dst = p.stacks.get(to);
        check(node.equals(StateOps.stackNode(p, to)), "Stacks must be Colocated for cargo transfer.");
        List<Integer> idxs = new ArrayList<>(cardIdxs == null ? List.of() : cardIdxs);
        idxs.sort((a, b) -> b - a);
        check(!idxs.isEmpty() || fts != 0, "Nothing selected to transfer.");
        check(fts <= StateOps.stackFts(p, from), "Not enough FTs.");
        if (src.glitch || dst.glitch) {
            glitchRoll(g, p.idx, from, STACK_NAME.get(from));
            if (p.stacks.get(to) != null) glitchRoll(g, p.idx, to, STACK_NAME.get(to));
            if (p.stacks.get(from) != null) p.stacks.get(from).glitch = true;
            if (p.stacks.get(to) != null) p.stacks.get(to).glitch = true;
            if (p.stacks.get(from) == null || p.stacks.get(to) == null) { StateOps.cleanupStacks(g, p); return; }
        }
        List<CardInst> moved = new ArrayList<>();
        for (int i : idxs) {
            if (i >= src.cards.size()) continue;
            moved.add(0, src.cards.get(i));
            src.cards.remove(i);
        }
        dst.cards.addAll(moved);
        if (fts != 0) { StateOps.addFts(p, from, -fts); StateOps.addFts(p, to, fts); }
        if (from.equals("rocket") || to.equals("rocket")) StateOps.syncRocketDry(p);
        List<String> what = new ArrayList<>();
        for (CardInst c : moved) what.add(Cards.stats(c).name);
        if (fts != 0) what.add(Fmt.plural(fts, "FT"));
        Dice.log(g, p.name + " transfers " + String.join(", ", what) + ": " + STACK_NAME.get(from) + " -> " + STACK_NAME.get(to) + ".");
        checkParade(g, p);
        StateOps.cleanupStacks(g, p);
    }

    /** G1b: FTs from a colocated stack (Aquas in LEO) become Wet Mass Fuel. */
    public static void loadFuel(GameState g, String from, int n) {
        requireFree(g);
        Player p = me(g);
        Stack r = p.stacks.rocket;
        check(r != null, "You have no Rocket.");
        check(n > 0, "Choose how many FTs to load.");
        if (from.equals("rocket")) { ftToFuel(g, n); return; }
        check(p.stacks.get(from) != null && r.node.equals(StateOps.stackNode(p, from)), "That stack is not Colocated with your Rocket.");
        check(StateOps.stackFts(p, from) >= n, "Not enough FTs.");
        if (r.fuel == null) r.fuel = FuelTrack.create(Math.max(1, StateOps.cargoMass(p, "rocket")));
        FuelTrack.addFuel(r.fuel, n, "water");
        StateOps.addFts(p, from, -n);
        Dice.log(g, p.name + " loads " + Fmt.plural(n, "tank") + " of water from " + STACK_NAME.get(from) + " (Wet Mass " + FuelTrack.label(r.fuel.wet) + ").");
        StateOps.cleanupStacks(g, p);
    }

    /* ---------------- G2 Internal tankage ---------------- */

    public static void ftToFuel(GameState g, int n) {
        requireFree(g);
        Player p = me(g);
        Stack r = p.stacks.rocket;
        check(r != null && r.ftsOr0() >= n && n > 0, "Not enough FTs carried as Cargo.");
        if (r.fuel == null) r.fuel = FuelTrack.create(Math.max(1, StateOps.cargoMass(p, "rocket")));
        boolean had = FuelTrack.fuelSteps(r.fuel) > 0;
        r.fts -= n;
        FuelTrack.setDry(r.fuel, Math.max(1, StateOps.cargoMass(p, "rocket")), "fixed");
        r.fuel.grade = had ? FuelTrack.lowerGrade(r.fuel.grade, "water") : "water";
        Dice.log(g, p.name + " converts " + Fmt.plural(n, "FT") + " into Wet Mass Fuel.");
    }

    public static void fuelToFt(GameState g, int n) {
        requireFree(g);
        Player p = me(g);
        Stack r = p.stacks.rocket;
        check(r != null && r.fuel != null && n > 0, "No Fuel to convert.");
        check(!"dirt".equals(r.fuel.grade), "Dirt fuel cannot be changed into FTs (G2b).");
        int newDry = r.fuel.dry + n;
        check(r.fuel.wet >= FuelTrack.massToStep(newDry), "Not enough Fuel for " + Fmt.plural(n, "FT") + ".");
        r.fts = r.ftsOr0() + n;
        FuelTrack.setDry(r.fuel, newDry, "fixed");
        Dice.log(g, p.name + " converts Fuel into " + Fmt.plural(n, "FT") + " carried as Cargo.");
    }

    /** G1f */
    public static void jettisonFuel(GameState g, int steps) {
        requireFree(g);
        Player p = me(g);
        Stack r = p.stacks.rocket;
        check(r != null && r.fuel != null && steps > 0 && steps <= FuelTrack.fuelSteps(r.fuel), "Invalid amount of Fuel.");
        FuelTrack.burn(r.fuel, steps);
        Dice.log(g, p.name + " jettisons " + Fmt.plural(steps, "step") + " of Fuel (Wet Mass " + FuelTrack.label(r.fuel.wet) + ").");
    }

    /** G1g: jettison FT beads. */
    public static void jettisonFts(GameState g, String key, int n) {
        requireFree(g);
        Player p = me(g);
        check(!key.equals("leo"), "Aquas in LEO are money - spend them instead.");
        check(n > 0 && StateOps.stackFts(p, key) >= n, "Not enough FTs.");
        StateOps.addFts(p, key, -n);
        if (key.equals("rocket")) StateOps.syncRocketDry(p);
        Dice.log(g, p.name + " jettisons " + Fmt.plural(n, "FT") + " from " + STACK_NAME.get(key) + ".");
        StateOps.cleanupStacks(g, p);
    }

    /** G6 / G1g: voluntary decommission of a stack card to the Hand. */
    public static void voluntaryDecommission(GameState g, String key, int idx) {
        requireFree(g);
        Player p = me(g);
        CardInst c = StateOps.cardAt(p, key, idx);
        check(c != null, "No such card.");
        check(c.crew == null, "A Crew can only be voluntarily decommissioned by Murder/Suicide (a Felony).");
        decommission(g, p.idx, key, idx, Decom.of(false, false, "voluntary"));
        StateOps.cleanupStacks(g, p);
    }

    /** G1g / Orientation b: rotate a heavy radiator to light, shedding mass. */
    public static void radiatorToLight(GameState g, String key, int idx) { radiatorToLight(g, key, idx, false); }

    /**
     * Rotate a heavy radiator to light. As a jettison (G1g) both chits move left; with
     * {@code fogg} (Phileas Fogg, G2c, Rocket with a gray Wet Mass chit) only the Dry
     * chit moves, so the shed mass becomes Fuel.
     */
    public static void radiatorToLight(GameState g, String key, int idx, boolean fogg) {
        requireFree(g);
        Player p = me(g);
        CardInst c = StateOps.cardAt(p, key, idx);
        check(c != null && c.crew == null && "radiator".equals(GameData().cards.deckOf(c.id)) && !c.isLight(), "Not a heavy radiator.");
        if (fogg) {
            Stack r = p.stacks.rocket;
            check(key.equals("rocket") && r.fuel != null && "dirt".equals(r.fuel.grade), "Fogg's heavy radiators need the Rocket's gray (dirt) Wet Mass chit (G2c).");
            c.orient = "light";
            FuelTrack.setDry(r.fuel, Math.max(1, StateOps.cargoMass(p, "rocket")), "fixed");
            Dice.log(g, p.name + " (Phileas Fogg) rotates " + Cards.stats(c).name + " to light: the shed mass becomes dirt fuel (Wet Mass " + FuelTrack.label(r.fuel.wet) + ").");
            return;
        }
        c.orient = "light";
        if (key.equals("rocket")) StateOps.syncRocketDry(p);
        Dice.log(g, p.name + " rotates " + Cards.stats(c).name + " to its light orientation.");
    }

    /**
     * A card chain uses {@code needed} Therms of cooling (J4b), {@code extra} of them from
     * afterburn: Heatsink Fountains cover only what the other radiators cannot, and flip to
     * light the first time they provide a Therm (Orientation c).
     */
    public static void useCooling(GameState g, Player p, String key, int needed, int extra, boolean pushed) {
        Stack s = p.stacks.get(key);
        if (s == null || needed <= 0) return;
        Cards.Analysis an = Cards.analyze(s.cards, map().zoneOf(StateOps.stackNode(p, key)), pushed);
        int shortfall = needed - extra;
        List<Integer> fountains = new ArrayList<>();
        for (int i = 0; i < an.st.size(); i++) {
            CardStats st = an.st.get(i);
            if (!"radiator".equals(st.deck) || !an.supported[i]) continue;
            if (st.ab("fountain") && !s.cards.get(i).isLight()) fountains.add(i);
            else shortfall -= st.therms == null ? 0 : st.therms;
        }
        boolean flipped = false;
        for (int i : fountains) {
            if (shortfall <= 0) break;
            shortfall -= an.st.get(i).therms == null ? 0 : an.st.get(i).therms;
            s.cards.get(i).orient = "light";
            flipped = true;
        }
        if (flipped && key.equals("rocket")) StateOps.syncRocketDry(p);
    }

    /** G2c Phileas Fogg: grind cards into dirt fuel. */
    public static void phileasFogg(GameState g, List<Integer> idxs) {
        requireFree(g);
        Player p = me(g);
        Stack r = p.stacks.rocket;
        check(r != null && idxs != null && !idxs.isEmpty(), "Select Rocket cards to feed the hopper.");
        List<Integer> sorted = new ArrayList<>(idxs);
        sorted.sort((a, b) -> b - a);
        check(sorted.stream().allMatch(i -> i >= 0 && i < r.cards.size() && r.cards.get(i).crew == null), "Your Crew cannot be fed into the hopper.");
        if (r.fuel == null) r.fuel = FuelTrack.create(Math.max(1, StateOps.cargoMass(p, "rocket")));
        List<String> names = new ArrayList<>();
        for (int i : sorted) {
            names.add(Cards.stats(r.cards.get(i)).name);
            p.hand.add(r.cards.get(i).id);
            r.cards.remove(i);
        }
        FuelTrack.setDry(r.fuel, Math.max(1, StateOps.cargoMass(p, "rocket")), "fixed");
        r.fuel.grade = "dirt";
        Dice.log(g, p.name + " (Phileas Fogg) feeds " + String.join(", ", names) + " into the hopper: Fuel is now dirt grade.");
        StateOps.cleanupStacks(g, p);
    }

    /** G1c info: dirt refuel on a Site with a Factory or an Operational ISRU card. */
    public static final class DirtInfo {
        public boolean ok;
        public String reason;
        public String source;
        public Boolean crewOnly;
        static DirtInfo no(String why) { DirtInfo d = new DirtInfo(); d.reason = why; return d; }
    }

    public static DirtInfo dirtRefuelInfo(GameState g) {
        Player p = me(g);
        Stack r = p.stacks.rocket;
        if (r == null) return DirtInfo.no("No Rocket.");
        if (!map().isSite(r.node)) return DirtInfo.no("Rocket is not on a Site.");
        Cards.Analysis an = Cards.analyze(r.cards, map().zoneOf(r.node));
        int dirtIdx = -1;
        for (int i = 0; i < an.st.size(); i++) {
            CardStats s = an.st.get(i);
            if (s.thrust != null && "dirt".equals(s.thrust.g) && s.thrust.f > 0 && an.supported[i]) { dirtIdx = i; break; }
        }
        if (dirtIdx < 0) return DirtInfo.no("Needs an Operational dirt thruster.");
        Site site = g.sites.get(r.node);
        String source = site != null && site.factory ? "Factory" : null;
        if (source == null) {
            outer:
            for (String k : StateOps.ownStackKeysAt(p, r.node)) {
                Cards.Analysis a2 = Cards.analyze(p.stacks.get(k).cards, map().zoneOf(r.node));
                for (int i = 0; i < a2.st.size(); i++) {
                    if (a2.st.get(i).isru != null && Cards.operational(a2, i)) { source = "ISRU card"; break outer; }
                }
            }
        }
        if (source == null) return DirtInfo.no("Needs a Factory or an Operational ISRU card here.");
        boolean crewOnly = true;
        for (int i = 0; i < an.st.size(); i++) {
            CardStats s = an.st.get(i);
            boolean dirtAlive = s.thrust != null && "dirt".equals(s.thrust.g) && an.supported[i];
            if (dirtAlive && !"crew".equals(s.deck)) { crewOnly = false; break; }
        }
        if (crewOnly && g.turn.crewDirt) return DirtInfo.no("Crew dirt refuel already used this turn.");
        DirtInfo d = new DirtInfo();
        d.ok = true;
        d.source = source;
        d.crewOnly = crewOnly;
        return d;
    }

    public static void dirtRefuel(GameState g, int tanks) {
        requireFree(g);
        DirtInfo info = dirtRefuelInfo(g);
        check(info.ok, info.reason);
        Player p = me(g);
        Stack r = p.stacks.rocket;
        if (Boolean.TRUE.equals(info.crewOnly)) { tanks = 1; g.turn.crewDirt = true; }
        check(tanks > 0, "Choose how many tanks.");
        if (r.fuel == null) r.fuel = FuelTrack.create(Math.max(1, StateOps.cargoMass(p, "rocket")));
        if (FuelTrack.fuelSteps(r.fuel) > 0 && !"dirt".equals(r.fuel.grade)) Dice.log(g, "Fuel mixing: all Fuel becomes dirt grade.");
        FuelTrack.addFuel(r.fuel, tanks, "dirt");
        r.fuel.grade = "dirt";
        Dice.log(g, p.name + " dirt refuels " + Fmt.plural(tanks, "tank") + " (" + info.source + ").");
    }

    /** Mooncable privilege: dirt refuel at LEO. */
    public static void mooncable(GameState g) {
        requireFree(g);
        Player p = me(g);
        check(StateOps.privilegeActive(g, p, "mooncable"), "Requires the Mooncable privilege.");
        check(!g.turn.mooncable, "Mooncable already used this turn.");
        Stack r = p.stacks.rocket;
        check(r != null && map().leo.equals(r.node), "Your Rocket must be in LEO.");
        Cards.Analysis an = Cards.analyze(r.cards, "earth");
        boolean any = false, nonCrew = false;
        for (int i = 0; i < an.st.size(); i++) {
            CardStats s = an.st.get(i);
            if (s.thrust != null && "dirt".equals(s.thrust.g) && an.supported[i]) {
                any = true;
                if (!"crew".equals(s.deck)) nonCrew = true;
            }
        }
        check(any, "Needs an Operational dirt thruster.");
        int tanks = nonCrew ? 7 : 1;
        if (r.fuel == null) r.fuel = FuelTrack.create(Math.max(1, StateOps.cargoMass(p, "rocket")));
        FuelTrack.addFuel(r.fuel, Math.min(tanks, FuelTrack.MAX_MASS - FuelTrack.stepToPos(r.fuel.wet).mass()), "dirt");
        r.fuel.grade = "dirt";
        g.turn.mooncable = true;
        Dice.log(g, p.name + " uses the Mooncable: +" + tanks + " dirt tanks.");
    }

    /** G1e: swap the Rocket with an Outpost (or turn the Rocket into an Outpost). */
    public static void swapRocket(GameState g, String outKey) {
        requireFree(g);
        Player p = me(g);
        check(outKey.equals("out1") || outKey.equals("out2"), "Pick an Outpost.");
        Stack r = p.stacks.rocket, o = p.stacks.get(outKey);
        check(r != null || o != null, "Nothing to swap.");
        if (r != null && r.fuel != null && FuelTrack.fuelSteps(r.fuel) > 0) Dice.log(g, "Fuel left on the Fuel Strip is flushed (convert it to FTs first to keep it).");
        if (r != null) r.fuel = null;
        if (o != null) o.fuel = null;
        p.stacks.rocket = o;
        p.stacks.set(outKey, r);
        if (p.stacks.rocket != null) StateOps.syncRocketDry(p);
        Dice.log(g, p.name + " swaps Rocket and " + STACK_NAME.get(outKey) + ".");
        StateOps.cleanupStacks(g, p);
    }

    /* ---------------- G3-G7 ---------------- */

    public static void buildColony(GameState g) {
        requireFree(g);
        Player p = me(g);
        StateOps.CrewLoc loc = StateOps.crewLocation(g, p);
        check(loc != null && map().isSite(loc.node()), "Your Crew must be on a Site.");
        Site site = g.sites.get(loc.node());
        check(site != null && site.factory && site.claim != null && site.claim == p.idx, "You need your own Factory here.");
        check(!site.colony, "This Factory already has a Colony.");
        check(!"Kreutz Sungrazer".equals(map().node(loc.node()).name) || StateOps.canFelony(g, p), "Colonising the kreutz sungrazer is a Felony.");
        decommission(g, loc.pi(), loc.key(), loc.idx(), Decom.of(false, false, "settlers"));
        site.colony = true;
        Dice.log(g, p.name + " founds a Colony on " + map().name(loc.node()) + "!", null, "good");
        StateOps.cleanupStacks(g, g.players.get(loc.pi()));
    }

    public static void claimJump(GameState g) {
        requireFree(g);
        Player p = me(g);
        check(StateOps.canFelony(g, p), "Claim Jump is a Felony (Felonious privilege or Anarchy).");
        StateOps.CrewLoc loc = StateOps.crewLocation(g, p);
        check(loc != null && map().isSite(loc.node()), "Your Crew must be on the Site.");
        Site site = g.sites.get(loc.node());
        check(site != null && site.claim != null && site.claim != p.idx, "There is no opponent Claim here.");
        check(!site.factory, "Industrialized Claims cannot be jumped.");
        boolean opposed = StateOps.humansAt(g, loc.node()).stream().anyMatch(x -> x != p.idx);
        check(!opposed, "An opposing Human defends this Claim.");
        Player victim = g.players.get(site.claim);
        site.claim = p.idx;
        g.turn.felony = true;
        Dice.log(g, p.name + " jumps " + victim.name + "'s Claim on " + map().name(loc.node()) + "!", null, "bad");
    }

    public static void loadGlory(GameState g) {
        requireFree(g);
        Player p = me(g);
        StateOps.CrewLoc loc = StateOps.crewLocation(g, p);
        check(loc != null && map().isSite(loc.node()), "Your Crew must be on a Site.");
        check(loc.node().equals(g.turn.gloryEligible), "Glory is only taken when landing a Human on a Site no Human visited before.");
        String zone = map().zoneOf(loc.node());
        check(Boolean.TRUE.equals(g.glory.get(zone)), "The " + GameData().rules.zone(zone).name() + " glory is already taken.");
        CardInst crew = g.players.get(loc.pi()).stacks.get(loc.key()).cards.get(loc.idx());
        check(crew.glory == null, "A Crew can carry only one glory chit.");
        crew.glory = zone;
        g.glory.put(zone, false);
        Dice.log(g, p.name + "'s Crew takes the " + GameData().rules.zone(zone).name() + " glory chit!", null, "good");
    }

    public static void glitchRepair(GameState g, String key) {
        requireFree(g);
        Player p = me(g);
        Stack s = p.stacks.get(key);
        check(s != null && s.glitch, "That stack has no Glitch.");
        String node = StateOps.stackNode(p, key);
        boolean scrum = StateOps.privilegeActive(g, p, "scrumTroubleshooters");
        check(scrum || StateOps.humansAt(g, node).contains(p.idx) || s.cards.stream().anyMatch(c -> c.crew != null),
                "A Glitch is repaired by your Human on the same Space (G7).");
        s.glitch = false;
        Dice.log(g, p.name + " repairs the Glitch on " + STACK_NAME.get(key) + (scrum ? " (Scrum Troubleshooters)" : "") + ".");
    }

    /** A Human the current player may decommission by Murder/Suicide. */
    public static final class MurderOpt {
        public int pi;
        public String key;
        public int idx;
        public String node;
        public int victim;
        public String kind;
        public boolean ok;
        public String reason;
    }

    public static List<MurderOpt> murderOptions(GameState g) {
        Player p = me(g);
        List<MurderOpt> out = new ArrayList<>();
        for (Player q : g.players) {
            for (String k : StateOps.STACKS) {
                Stack s = q.stacks.get(k);
                if (s == null) continue;
                String node = StateOps.stackNode(q, k);
                for (int i = 0; i < s.cards.size(); i++) {
                    CardInst c = s.cards.get(i);
                    if (c.crew == null) continue;
                    int victim = StateOps.crewOwner(g, c, q.idx);
                    if (victim != p.idx && q.idx != p.idx) continue;
                    Set<Integer> humans = StateOps.humansAt(g, node);
                    Site site = g.sites.get(node);
                    boolean others = humans.stream().anyMatch(x -> x != victim) || (site != null && site.colony && site.claim != null && site.claim == victim);
                    MurderOpt o = new MurderOpt();
                    o.pi = q.idx;
                    o.key = k;
                    o.idx = i;
                    o.node = node;
                    o.victim = victim;
                    o.kind = others ? "Murder" : "Suicide";
                    o.ok = true;
                    if (node.equals(map().leo)) { o.ok = false; o.reason = "the Crew is already in LEO"; }
                    else if (victim != p.idx && !humans.contains(p.idx)) { o.ok = false; o.reason = "your own Human must be on the Space"; }
                    out.add(o);
                }
            }
        }
        return out;
    }

    /** Murder/Suicide (Felony, once per Turn): decommission a Human; it returns to its owner's LEO. */
    public static void murder(GameState g, int pi, String key, int idx) {
        requireFree(g);
        Player p = me(g);
        check(StateOps.canFelony(g, p), "Murder/Suicide is a Felony (Felonious privilege or Anarchy).");
        check(!g.turn.murder, "Murder/Suicide is limited to once per Turn.");
        MurderOpt o = null;
        for (MurderOpt x : murderOptions(g)) if (x.pi == pi && x.key.equals(key) && x.idx == idx) { o = x; break; }
        check(o != null, "That is not a Human you can decommission.");
        check(o.ok, o.kind + " is not possible: " + o.reason + ".");
        Player victim = g.players.get(o.victim);
        g.turn.murder = true;
        g.turn.felony = true;
        Dice.log(g, o.kind + " (Felony): " + p.name + " decommissions " + (victim == p ? "their own" : victim.name + "'s") + " Crew at " + map().name(o.node) + ".", null, "bad");
        decommission(g, pi, key, idx, Decom.of(false, false, o.kind.toLowerCase(java.util.Locale.ROOT)));
        StateOps.cleanupStacks(g, g.players.get(pi));
    }

    /** G6a: discard your Factory cube from a Site; the Claim stays and can be industrialized again. */
    public static void discardFactory(GameState g, String siteId) {
        requireFree(g);
        Player p = me(g);
        Site site = g.sites.get(siteId);
        check(site != null && site.factory && site.claim != null && site.claim == p.idx, "You have no Factory there.");
        check(!site.colony, "A colonized Factory is permanent (G6b).");
        site.factory = false;
        Dice.log(g, p.name + " discards the Factory cube on " + map().name(siteId) + " (G6a).");
    }

    /** G6a: discard your Claim disk from a Site without a Factory; the Site can be prospected again. */
    public static void discardClaim(GameState g, String siteId) {
        requireFree(g);
        Player p = me(g);
        Site site = g.sites.get(siteId);
        check(site != null && site.claim != null && site.claim == p.idx, "You have no Claim there.");
        check(!site.factory, "Discard the Factory cube first (G6a).");
        site.claim = null;
        Dice.log(g, p.name + " discards the Claim disk on " + map().name(siteId) + " (G6a).");
    }

    public static void discardHand(GameState g, int handIdx) {
        requireFree(g);
        Player p = me(g);
        check(handIdx >= 0 && handIdx < p.hand.size(), "No such Hand card.");
        String id = p.hand.remove(handIdx);
        g.decks.get(GameData().cards.deckOf(id)).add(id);
        Dice.log(g, p.name + " discards " + GameData().cards.def(id).w.name + " to the bottom of its deck.");
    }
}
