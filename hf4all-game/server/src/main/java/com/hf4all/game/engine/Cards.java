package com.hf4all.game.engine;

import com.hf4all.game.data.CardDb;
import com.hf4all.game.data.GameData;
import com.hf4all.game.data.Rules;
import com.hf4all.game.model.CardInst;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Card stats and the support / Operational analysis (J1-J4), ported from the
 * browser's js/cards.js.
 */
public final class Cards {

    private Cards() {}

    private static CardDb db() { return GameData.get().cards; }

    public static String gradeName(String g) {
        if (g == null) return "-";
        return switch (g) {
            case "dirt" -> "Dirt";
            case "water" -> "Water";
            case "isotope" -> "Isotope";
            default -> "-";
        };
    }

    /** Does a side provide support type ('gen' | 'reactor') of one of the wanted subtypes? */
    public static boolean provides(CardStats s, String type, List<String> wanted) {
        if (s.prov == null) return false;
        List<String> p = type.equals("gen") ? s.prov.gen : s.prov.reactor;
        if (p == null) return false;
        for (String x : p) if (wanted.contains(x)) return true;
        return false;
    }

    public static CardStats sideStats(String id, String side) {
        CardDb.CardDef d = db().def(id);
        CardDb.CardSide s = "b".equals(side) ? d.b : d.w;
        CardStats c = new CardStats();
        c.name = s.name;
        c.mass = s.mass;
        c.rad = s.rad;
        c.spec = s.spec;
        c.thrust = s.thrust;
        c.isru = s.isru;
        c.req = s.req;
        c.prov = s.prov;
        c.mod = s.mod;
        c.solar = s.solar;
        c.sail = s.sail;
        c.magSail = s.magSail;
        c.ab = s.ab;
        c.text = s.text;
        c.therms = s.therms;
        c.light = s.light;
        c.deck = db().deckOf(id);
        c.cardId = id;
        c.sideKey = side;
        return c;
    }

    public static CardStats stats(CardInst inst) {
        if (inst.crew != null) {
            CardDb.CrewSide cs = db().crew(inst.crew).sides.get(inst.face);
            CardStats c = new CardStats();
            c.deck = "crew";
            c.name = cs.faction;
            c.faction = cs.faction;
            c.mass = cs.mass;
            c.rad = cs.rad;
            c.thrust = cs.thrust;
            c.isru = cs.isru;
            c.human = true;
            c.clout = cs.clout;
            c.engine = cs.engine;
            c.privilege = cs.privilege;
            c.spec = null;
            c.cardId = "crew";
            c.crewId = inst.crew;
            c.face = inst.face;
            return c;
        }
        CardStats s = sideStats(inst.id, inst.side);
        if ("radiator".equals(s.deck)) s.orient = inst.isLight() ? "light" : "heavy";
        if ("radiator".equals(s.deck) && inst.isLight() && s.light != null) {
            s.mass = s.light.mass;
            s.therms = s.light.therms;
            if (s.light.rad != null) s.rad = s.light.rad;
        }
        return s;
    }

    /** ISRU platform kinds of an isru entry ("raygun+missile" -> raygun, missile). */
    public static List<String> platforms(CardDb.Isru isru) {
        return isru == null ? List.of() : Arrays.asList(String.valueOf(isru.k).split("\\+"));
    }

    public static int massOf(CardInst inst) { return stats(inst).massOr0(); }

    public static boolean hasThrust(CardInst inst) { return stats(inst).thrust != null; }

    /** Result of the support analysis of a stack. */
    public static final class Analysis {
        public List<CardStats> st;
        public boolean[] supported;
        public int therms;
        public List<List<String>> reasons;
    }

    /**
     * Support analysis for a stack in a zone (J1-J4). {@code supported} is the
     * greatest fixpoint, so mutually supporting pulsed generators and reactors
     * (J1e) both count.
     */
    public static Analysis analyze(List<CardInst> cards, String zone) { return analyze(cards, zone, false); }

    /**
     * {@code pushed}: the stack is a Spacecraft pushed by a Powersat, which keeps its
     * Solar-Powered cards Operational in the Neptune zone (J3a, H3c).
     */
    public static Analysis analyze(List<CardInst> cards, String zone, boolean pushed) {
        int n = cards.size();
        List<CardStats> st = new ArrayList<>(n);
        for (CardInst c : cards) st.add(stats(c));
        boolean[] alive = new boolean[n];
        Arrays.fill(alive, true);
        List<List<String>> reasons = new ArrayList<>();
        for (int i = 0; i < n; i++) reasons.add(new ArrayList<>());
        Rules.Zone z = GameData.get().rules.zone(zone);
        boolean neptune = z != null && z.solar() == null;
        for (int i = 0; i < n; i++) {
            if (Boolean.TRUE.equals(cards.get(i).nonOp)) { alive[i] = false; reasons.get(i).add("declared non-operational"); }
            if (st.get(i).isSolar() && neptune && !pushed) { alive[i] = false; reasons.get(i).add("no solar power in the Neptune zone"); }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (!alive[i]) continue;
                List<String> miss = missingSupports(st, alive, i);
                if (!miss.isEmpty()) { alive[i] = false; reasons.get(i).add("needs " + String.join(" + ", miss)); changed = true; }
            }
        }
        int therms = 0;
        for (int i = 0; i < n; i++) {
            if (alive[i] && "radiator".equals(st.get(i).deck)) therms += st.get(i).therms == null ? 0 : st.get(i).therms;
        }
        Analysis a = new Analysis();
        a.st = st;
        a.supported = alive;
        a.therms = therms;
        a.reasons = reasons;
        return a;
    }

    static List<String> missingSupports(List<CardStats> st, boolean[] alive, int i) {
        List<String> miss = new ArrayList<>();
        for (String type : List.of("gen", "reactor")) {
            List<String> want = st.get(i).reqList(type);
            if (want.isEmpty()) continue;
            boolean found = false;
            for (int j = 0; j < st.size(); j++) {
                if (j != i && alive[j] && provides(st.get(j), type, want)) { found = true; break; }
            }
            if (!found) miss.add(String.join("/", want) + (type.equals("gen") ? " generator" : " reactor"));
        }
        return miss;
    }

    /** Supported cards that can fill requirement type of card k (J1a, J1b). */
    public static List<Integer> providers(Analysis an, int k, String type) {
        List<String> want = an.st.get(k).reqList(type);
        List<Integer> out = new ArrayList<>();
        if (want.isEmpty()) return out;
        for (int j = 0; j < an.st.size(); j++) {
            if (j != k && an.supported[j] && provides(an.st.get(j), type, want)) out.add(j);
        }
        return out;
    }

    /** One requirement slot of a support chain (J1c). */
    public static final class Slot {
        public int k;
        public String type;
        public String key;
        public List<Integer> options;
        public int depth;
        public int pick = -1;
        public boolean chosen;
        public boolean invalid;
    }

    public static final class ChainResult {
        public List<Integer> chain;
        public List<Slot> slots;
    }

    /**
     * The support chain (J1c) of card i as requirement slots, one provider per
     * requirement of the card and, recursively, of its providers. {@code choice}
     * maps "k:type" to the provider the player picked; an unpicked slot takes
     * the provider with the best thrust modifier (solarMod = the zone's solar
     * modifier), reusing a support already in the chain when possible.
     */
    public static ChainResult chainSlots(Analysis an, int i, Integer solarMod, Map<String, Integer> choice) {
        Set<Integer> picked = new LinkedHashSet<>();
        picked.add(i);
        List<Slot> slots = new ArrayList<>();
        visit(an, i, 0, solarMod, choice, picked, slots);
        ChainResult r = new ChainResult();
        r.chain = new ArrayList<>(picked);
        r.slots = slots;
        return r;
    }

    private static double score(Analysis an, int j, Integer solarMod, Set<Integer> picked) {
        CardStats s = an.st.get(j);
        CardDb.Mod m = s.mod;
        double thrust = m != null && m.thrust != null ? m.thrust : 0;
        double solar = m != null && Boolean.TRUE.equals(m.solar) && solarMod != null ? solarMod : 0;
        return thrust + solar - (s.req != null ? 0.1 : 0) + (picked.contains(j) ? 100 : 0);
    }

    private static void visit(Analysis an, int k, int depth, Integer solarMod, Map<String, Integer> choice, Set<Integer> picked, List<Slot> slots) {
        for (String type : List.of("gen", "reactor")) {
            if (an.st.get(k).reqList(type).isEmpty()) continue;
            List<Integer> options = providers(an, k, type);
            String key = k + ":" + type;
            Integer want = choice != null ? choice.get(key) : null;
            Slot slot = new Slot();
            slot.k = k;
            slot.type = type;
            slot.key = key;
            slot.options = options;
            slot.depth = depth;
            slot.chosen = want != null;
            if (want != null && options.contains(want)) slot.pick = want;
            else {
                slot.invalid = want != null;
                double best = Double.NEGATIVE_INFINITY;
                for (int j : options) {
                    double sc = score(an, j, solarMod, picked);
                    if (sc > best) { best = sc; slot.pick = j; }
                }
            }
            slots.add(slot);
            if (slot.pick >= 0 && !picked.contains(slot.pick)) {
                picked.add(slot.pick);
                visit(an, slot.pick, depth + 1, solarMod, choice, picked, slots);
            }
        }
    }

    public static List<Integer> chain(Analysis an, int i, Integer solarMod) { return chainSlots(an, i, solarMod, null).chain; }

    public static int chainTherms(Analysis an, List<Integer> chain) {
        int sum = 0;
        for (int j : chain) sum += an.st.get(j).reqTherms();
        return sum;
    }

    public static boolean operational(Analysis an, int i) { return operational(an, i, false, 0); }

    /** Is card i Operational for a normal use (supports + therms)? */
    public static boolean operational(Analysis an, int i, boolean ignoreTherms, int extraTherms) {
        if (!an.supported[i]) return false;
        if (ignoreTherms) return true;
        return chainTherms(an, chain(an, i, null)) <= an.therms + extraTherms;
    }

    public static String statusText(Analysis an, int i) {
        if (!an.supported[i]) return String.join("; ", an.reasons.get(i));
        int need = chainTherms(an, chain(an, i, null));
        if (need > an.therms) return "needs " + need + " therm" + (need > 1 ? "s" : "") + " (stack has " + an.therms + ")";
        return "Operational";
    }

    /** Effective ISRU rating (I6.1). */
    public static final class IsruInfo {
        public int r;
        public String k;
        public List<String> kinds;
    }

    /**
     * @param assay a Colocated Operational DIVINING NUBOTS card (Von Neumann Santa Claus
     *              Machine): -1 ISRU (I6.1 Assaying Smelters)
     * @param scoop the ISRU modifier of a Colocated Operational SCOOP card at an Aerostat
     *              Site (Mini-Mag RF Paul Trap / Ultracold Neutrons: -2), 0 if none
     */
    public static IsruInfo isru(Analysis an, int i, boolean pushed, boolean assay, int scoop) {
        CardStats s = an.st.get(i);
        if (s.isru == null) return null;
        int r = s.isru.r;
        if (assay) r -= 1;
        if (pushed && s.ab("pushIsru")) r += s.abInt("pushIsru");
        r += scoop;
        IsruInfo out = new IsruInfo();
        out.r = Math.max(0, r);
        out.k = s.isru.k;
        out.kinds = platforms(s.isru);
        return out;
    }
}
