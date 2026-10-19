package com.hf4all.game.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hf4all.game.data.CardDb;
import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Pending;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;
import com.hf4all.game.model.Turn;
import com.hf4all.game.util.Fmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.hf4all.game.engine.RuleError.check;
import static com.hf4all.game.engine.StateOps.STACK_NAME;

/**
 * Operations (I), one per turn (ported from the former browser engine). Each
 * {@code xxxOptions} lists what is currently possible; the matching action
 * performs it.
 */
public final class Operations {

    private Operations() {}

    private static GameMap map() { return GameData.get().map; }
    private static CardDb cards() { return GameData.get().cards; }
    private static Player me(GameState g) { return g.players.get(g.current); }

    static void requireOp(GameState g) {
        check(g.gameOver == null, "The game is over.");
        check(g.move == null, "Operations cannot interrupt movement (H1c).");
        check(g.pending == null, "Resolve the pending decision first.");
        check(g.turn.op == null, "You already performed an Operation this turn (D1b).");
    }

    private static void done(GameState g, String id) {
        g.turn.op = id;
        g.turn.opDetail = null;
    }

    public static int newUid(GameState g) {
        g.uid = (g.uid == null || g.uid == 0 ? 1000 : g.uid) + 1;
        return g.uid;
    }

    private static void payTaxes(GameState g, String what) {
        for (Player p : g.players) {
            if (StateOps.privilegeActive(g, p, "taxes")) {
                p.aquas += 1;
                Dice.log(g, p.name + " collects 1 Aqua in Taxes (" + what + ").", p.idx, null);
            }
        }
    }

    /** Using an opponent's Factory for an On-Site Operation: permission (N6) or a Factory Hijack (Felony, N6a). */
    private static void useOpponentFactory(GameState g, Player p, int ownerIdx, String node, StateOps.Check hijack, String use, String what) {
        Player owner = g.players.get(ownerIdx);
        check("permission".equals(use) || "hijack".equals(use), Fmt.cap(what) + " at " + owner.name + "'s Factory needs their permission (N6) or a Factory Hijack (Felony).");
        if ("hijack".equals(use)) {
            check(hijack.ok, "Factory Hijack impossible: " + hijack.reason + ".");
            g.turn.felony = true;
            Dice.log(g, "Factory Hijack (Felony): " + p.name + " uses " + owner.name + "'s Factory on " + map().name(node) + " for " + what + ".", null, "bad");
        } else {
            Dice.log(g, owner.name + " permits " + p.name + " to use their Factory on " + map().name(node) + " for " + what + " (N6).");
        }
    }

    private static String freeOutpostKey(Player p) {
        return p.stacks.out1 == null ? "out1" : (p.stacks.out2 == null ? "out2" : null);
    }

    /** Put FTs into an existing colocated stack or a new outpost there. */
    private static String placeFts(GameState g, Player p, String node, String target, int n) {
        if ("new".equals(target)) {
            String k = freeOutpostKey(p);
            check(k != null, "Both Outpost chits are in use.");
            p.stacks.set(k, Stack.at(node));
            target = k;
            Dice.log(g, p.name + " creates " + STACK_NAME.get(k) + " at " + map().name(node) + ".");
        }
        check(StateOps.STACKS.contains(target) && p.stacks.get(target) != null && node.equals(StateOps.stackNode(p, target)), "Target stack is not here.");
        StateOps.addFts(p, target, n);
        if (target.equals("rocket")) StateOps.syncRocketDry(p);
        return target;
    }

    /* I1 */
    public static void income(GameState g) {
        requireOp(g);
        Player p = me(g);
        p.aquas += 1;
        Dice.log(g, p.name + " takes Income: +1 Aqua.");
        done(g, "income");
    }

    /* I2 Research auction */
    public static boolean canBid(GameState g, int pi) {
        Player p = g.players.get(pi);
        return p.hand.size() < 4 || StateOps.privilegeActive(g, p, "skunkworks");
    }

    public static void startAuction(GameState g, String deck) {
        requireOp(g);
        Player p = me(g);
        check(canBid(g, p.idx), "Academia hand limit: you hold 4+ cards (I2a). Discard first.");
        check(g.decks.get(deck) != null && !g.decks.get(deck).isEmpty(), "That deck is empty.");
        String card = g.decks.get(deck).get(0);
        g.pending = Pending.auction(deck, card, p.idx);
        g.turn.op = "auction";
        Dice.log(g, p.name + " opens a Research Auction for " + cards().def(card).w.name + ".");
    }

    /** bids: player index -> bid (absent = not bidding). tieWinner: picked by the initiator when needed. */
    public static void resolveAuction(GameState g, Map<Integer, Double> bids, Integer tieWinner) {
        Pending pd = g.pending;
        check(pd != null && "auction".equals(pd.type), "No auction running.");
        int init = pd.initiator;
        Map<Integer, Integer> valid = new LinkedHashMap<>();
        List<Integer> keys = new ArrayList<>(bids.keySet());
        keys.sort(Integer::compare);
        for (int pi : keys) {
            Double v = bids.get(pi);
            if (v == null || v.isNaN()) continue;
            check(pi >= 0 && pi < g.players.size(), "No such player.");
            check(canBid(g, pi), g.players.get(pi).name + " exceeds the academia hand limit and cannot bid.");
            check(v >= 0 && v <= g.players.get(pi).aquas, g.players.get(pi).name + " cannot bid " + Fmt.js(v) + " Aquas.");
            valid.put(pi, (int) Math.floor(v));
        }
        check(valid.get(init) != null, "The initiator must make the first bid (0 or more).");
        int high = Integer.MIN_VALUE;
        for (int v : valid.values()) high = Math.max(high, v);
        List<Integer> tied = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : valid.entrySet()) if (e.getValue() == high) tied.add(e.getKey());
        int winner;
        if (tied.size() == 1) winner = tied.get(0);
        else if (tied.contains(init) && tied.stream().noneMatch(k -> k != init && StateOps.privilegeActive(g, g.players.get(k), "marketeer"))) winner = init;
        else {
            List<Integer> mk = new ArrayList<>();
            for (int k : tied) if (StateOps.privilegeActive(g, g.players.get(k), "marketeer")) mk.add(k);
            if (mk.size() == 1) winner = mk.get(0);
            else {
                check(tieWinner != null && tied.contains(tieWinner), "Tie: the initiator chooses the winner.");
                winner = tieWinner;
            }
        }
        Player w = g.players.get(winner);
        w.aquas -= high;
        if (winner != init) g.players.get(init).aquas += high;
        g.decks.get(pd.deck).remove(0);
        w.hand.add(pd.card);
        CardDb.CardDef def = cards().def(pd.card);
        Dice.log(g, w.name + " wins " + def.w.name + " for " + high + " Aqua" + (high == 1 ? "" : "s") +
                (winner != init ? " (paid to " + g.players.get(init).name + ")" : "") + ".", winner, "good");
        // I2g bonus supports
        CardDb.Req req = def.w.req;
        List<String> bonus = new ArrayList<>();
        if (req != null && req.gen != null && !req.gen.isEmpty()) bonus.add("generator");
        if (req != null && req.reactor != null && !req.reactor.isEmpty()) bonus.add("reactor");
        if (req != null && req.therms != null && req.therms != 0) bonus.add("radiator");
        for (String d : bonus) {
            if (!g.decks.get(d).isEmpty()) {
                String id = g.decks.get(d).remove(0);
                w.hand.add(id);
                Dice.log(g, w.name + " also takes the bonus support " + cards().def(id).w.name + " (I2g).", winner, null);
            }
        }
        g.pending = null;
        g.turn.opDetail = new Turn.OpDetail(winner, high);
    }

    /* I3 Free market */
    public static void sellHand(GameState g, int handIdx) {
        requireOp(g);
        Player p = me(g);
        check(handIdx >= 0 && handIdx < p.hand.size(), "No such card.");
        String id = p.hand.remove(handIdx);
        g.decks.get(cards().deckOf(id)).add(id);
        p.aquas += 3;
        Dice.log(g, p.name + " sells " + cards().def(id).w.name + " on the Free Market for 3 Aquas.");
        done(g, "freeMarket");
    }

    public static Integer blackSalePrice(GameState g, CardInst card) {
        CardStats s = Cards.stats(card);
        if (card.crew != null || !"b".equals(card.side) || "ANY".equals(s.spec)) return null;
        return StateOps.stockPrice(g, s.spec);
    }

    public static void sellBlack(GameState g, int leoIdx) { sellBlack(g, "leo", leoIdx); }

    /** I3b: sell a Black-Side card from any of your stacks in your Bank (LEO), e.g. a Rocket parked there. */
    public static void sellBlack(GameState g, String key, int idx) {
        requireOp(g);
        Player p = me(g);
        Stack s = StateOps.STACKS.contains(key) ? p.stacks.get(key) : null;
        check(s != null && map().leo.equals(StateOps.stackNode(p, key)), "Only cards in your Bank (your stacks in LEO) can be sold (I3b).");
        check(idx >= 0 && idx < s.cards.size(), "No such card.");
        CardInst c = s.cards.get(idx);
        Integer price = blackSalePrice(g, c);
        check(price != null, "Only Black-Side cards (not type ANY) in LEO can be sold at stock price.");
        s.cards.remove(idx);
        p.hand.add(c.id);
        p.aquas += price;
        if (key.equals("rocket")) StateOps.syncRocketDry(p);
        Dice.log(g, p.name + " sells the Black-Side " + Cards.stats(c).name + " for " + price + " Aquas (stock price).", null, "good");
        done(g, "freeMarket");
        StateOps.cleanupStacks(g, p);
    }

    /* I4 Boost */
    public static final class BoostItem {
        public int handIdx;
        public String orient;
        public BoostItem() {}
        public BoostItem(int handIdx, String orient) { this.handIdx = handIdx; this.orient = orient; }
    }

    public static int boostCost(GameState g, List<BoostItem> items) {
        Player p = me(g);
        int sum = 0;
        for (BoostItem it : items) {
            check(it.handIdx >= 0 && it.handIdx < p.hand.size(), "No such Hand card.");
            CardStats s = Cards.sideStats(p.hand.get(it.handIdx), "w");
            sum += "radiator".equals(s.deck) && "light".equals(it.orient) ? s.light.mass : s.massOr0();
        }
        return sum;
    }

    public static void boost(GameState g, List<BoostItem> items) {
        requireOp(g);
        Player p = me(g);
        check(items != null && !items.isEmpty(), "Select Hand cards to boost.");
        int cost = boostCost(g, items);
        check(p.aquas >= cost, "Boosting costs " + cost + " Aquas; you have " + p.aquas + ".");
        p.aquas -= cost;
        List<String> names = new ArrayList<>();
        List<BoostItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> b.handIdx - a.handIdx);
        for (BoostItem it : sorted) {
            String id = p.hand.remove(it.handIdx);
            CardInst inst = CardInst.patent(id, "w", newUid(g));
            if ("radiator".equals(cards().deckOf(id))) inst.orient = "light".equals(it.orient) ? "light" : "heavy";
            p.stacks.leo.cards.add(inst);
            names.add(cards().def(id).w.name);
        }
        Dice.log(g, p.name + " boosts " + String.join(", ", names) + " to LEO for " + cost + " Aquas.");
        for (Player o : g.players) {
            if (StateOps.privilegeActive(g, o, "launchFees")) {
                o.aquas += 1;
                Dice.log(g, o.name + " collects 1 Aqua in Launch Fees.", o.idx, null);
            }
        }
        done(g, "boost");
    }

    /* I5 Site refuel */
    public static final class RefuelOpt {
        public String kind;
        public String key;
        public Integer idx;
        public String node;
        public int fts;
        public String label;
        public Integer owner;
        public StateOps.Check hijack;
    }

    public static List<RefuelOpt> refuelOptions(GameState g) {
        Player p = me(g);
        List<RefuelOpt> out = new ArrayList<>();
        for (String key : StateOps.STACKS) {
            Stack s = p.stacks.get(key);
            if (s == null || key.equals("leo")) continue;
            String node = StateOps.stackNode(p, key);
            GameMap.Node N = map().node(node);
            Cards.Analysis an = Cards.analyze(s.cards, N.z);
            if (N.t.equals("site")) {
                int water = siteWater(g, p, node);
                JsonElement scav = colocatedAbility(g, p, node, "scavenging");
                for (int i = 0; i < an.st.size(); i++) {
                    CardStats st = an.st.get(i);
                    if (st.isru == null || !Cards.operational(an, i)) continue;
                    int isru = isruAt(g, p, node, an, i).r;
                    if (isru > water) continue;
                    int fts = 1 + water - isru;
                    boolean dharma = "crew".equals(st.deck) && s.cards.get(i).glory != null && StateOps.privilegeActive(g, p, "dharmaRefuel");
                    if (dharma) fts *= 2;
                    if (scav != null) fts *= 2;
                    RefuelOpt o = new RefuelOpt();
                    o.kind = "isru";
                    o.key = key;
                    o.idx = i;
                    o.node = node;
                    o.fts = fts;
                    o.label = "ISRU refuel with " + st.name + " (ISRU " + isru + ", hydration " + water + ")" + (dharma ? " x2 Dharma" : "") + (scav != null ? " x2 Scavenging" : "");
                    out.add(o);
                }
                Site site = g.sites.get(node);
                if (site != null && site.factory && site.claim != null) {
                    RefuelOpt o = new RefuelOpt();
                    o.kind = "factory";
                    o.key = key;
                    o.node = node;
                    // SCAVENGING doubles every Site refuel, a Factory's too.
                    o.fts = scav != null ? 14 : 7;
                    String x2 = scav != null ? " x2 Scavenging" : "";
                    o.label = "Factory refuel (" + o.fts + " water FTs)" + x2;
                    if (site.claim != p.idx) {
                        o.owner = site.claim;
                        o.hijack = StateOps.felonyAt(g, p, node, false);
                        o.label = "Factory refuel at " + g.players.get(site.claim).name + "'s Factory (" + o.fts + " water FTs)" + x2;
                    }
                    out.add(o);
                }
            }
            if ("aero".equals(N.haz)) {
                int ae = -1;
                for (int i = 0; i < an.st.size(); i++) if (an.st.get(i).ab("airEater") && Cards.operational(an, i)) { ae = i; break; }
                if (ae >= 0) {
                    double cons = 5;
                    for (CardStats x : an.st) if (x.thrust != null) cons = Math.min(cons, x.thrust.f);
                    // Five minus fuel consumption, dropping the fraction (I5c example: 1/2 -> 5 FTs).
                    int fts = Math.max(0, 5 - (int) Math.floor(cons));
                    if (fts > 0) {
                        RefuelOpt o = new RefuelOpt();
                        o.kind = "airEater";
                        o.key = key;
                        o.node = node;
                        o.fts = fts;
                        o.label = "Air-Eater scoop (" + fts + " FTs, Hazard Roll)";
                        out.add(o);
                    }
                }
            }
        }
        // A Factory needs no stack of yours on its Site: its FTs can start a new Outpost there
        // (I5b, E6c "Fuel Outpost"), like ET production (I8). Hijacking still needs your Human.
        for (String id : freeOutpostKey(p) == null ? List.<String>of() : StateOps.siteIds(g)) {
            Site site = g.sites.get(id);
            if (!site.factory || site.claim == null || !StateOps.ownStackKeysAt(p, id).isEmpty()) continue;
            RefuelOpt o = new RefuelOpt();
            o.kind = "factory";
            o.node = id;
            o.fts = 7;
            o.label = "Factory refuel (7 water FTs) into a new Outpost";
            if (site.claim != p.idx) {
                o.owner = site.claim;
                o.hijack = StateOps.felonyAt(g, p, id, false);
                o.label = "Factory refuel at " + g.players.get(site.claim).name + "'s Factory (7 water FTs) into a new Outpost";
            }
            out.add(o);
        }
        return out;
    }

    /** use: "permission" | "hijack" when refuelling at an opponent's Factory. */
    public static void refuel(GameState g, int optIndex, String target, boolean finao, String use) {
        requireOp(g);
        Player p = me(g);
        List<RefuelOpt> opts = refuelOptions(g);
        check(optIndex >= 0 && optIndex < opts.size(), "That refuel is not possible.");
        RefuelOpt opt = opts.get(optIndex);
        if (opt.owner != null) useOpponentFactory(g, p, opt.owner, opt.node, opt.hijack, use, "factory refuel");
        done(g, "refuel");
        Integer cardUid = opt.idx != null ? p.stacks.get(opt.key).cards.get(opt.idx).uid : null;
        if (opt.key != null) {
            Actions.glitchRoll(g, p.idx, opt.key, "site refuel");
            StateOps.cleanupStacks(g, p);
            if (p.stacks.get(opt.key) == null) return;
        }
        if (cardUid != null && indexOfUid(p.stacks.get(opt.key), cardUid) < 0) {
            Dice.log(g, "The Glitch knocked out the refuelling card: the operation fails.", null, "bad");
            return;
        }
        if (opt.kind.equals("airEater")) {
            if (Actions.hazardRoll(g, p.idx, opt.key, finao, "air-eater").lost()) return;
            Stack st = p.stacks.get(opt.key);
            Cards.Analysis an = Cards.analyze(st.cards, map().zoneOf(opt.node));
            for (int i = 0; i < an.st.size(); i++) if (an.st.get(i).ab("airEater") && Cards.operational(an, i)) { cardUid = st.cards.get(i).uid; break; }
        }
        if (cardUid != null) coolUsedCard(g, p, opt.key, cardUid);
        String t = placeFts(g, p, opt.node, target == null || target.isEmpty() ? (opt.key != null ? opt.key : "new") : target, opt.fts);
        Dice.log(g, p.name + ": " + opt.label + " -> " + opt.fts + " FTs into " + STACK_NAME.get(t) + ".", null, "good");
    }

    private static int indexOfUid(Stack s, int uid) {
        if (s != null) for (int i = 0; i < s.cards.size(); i++) if (s.cards.get(i).uid != null && s.cards.get(i).uid == uid) return i;
        return -1;
    }

    /** The card used by an Operation takes the cooling of its support chain (J4b): Heatsink Fountains may flip. */
    private static void coolUsedCard(GameState g, Player p, String key, int uid) {
        Stack s = p.stacks.get(key);
        int i = indexOfUid(s, uid);
        if (i < 0) return;
        Cards.Analysis an = Cards.analyze(s.cards, map().zoneOf(StateOps.stackNode(p, key)));
        Actions.useCooling(g, p, key, Cards.chainTherms(an, Cards.chain(an, i, null)), 0, false);
    }

    /* I6 Prospect */
    private static final Pattern LUNA = Pattern.compile("^Luna", Pattern.CASE_INSENSITIVE);
    private static final Pattern AEROSTAT = Pattern.compile("aerostat", Pattern.CASE_INSENSITIVE);

    public static boolean isLuna(String id) { String n = map().node(id).name; return LUNA.matcher(n == null ? "" : n).find(); }

    public static boolean isAerostat(String id) { String n = map().node(id).name; return AEROSTAT.matcher(n == null ? "" : n).find(); }

    private static boolean hasPushOpt(GameState g, Player p) { return StateOps.hasPowersat(g, p); }

    /** The ability value of an Operational card with ability `key` in the player's stacks at node, or null. */
    public static JsonElement colocatedAbility(GameState g, Player p, String node, String key) {
        for (String k : StateOps.ownStackKeysAt(p, node)) {
            Cards.Analysis an = Cards.analyze(p.stacks.get(k).cards, map().zoneOf(node));
            for (int i = 0; i < an.st.size(); i++) {
                if (an.st.get(i).ab(key) && Cards.operational(an, i)) return an.st.get(i).abValue(key);
            }
        }
        return null;
    }

    /**
     * The effective ISRU rating of card i of a stack at node: Colocated (any of the player's
     * stacks on that Space) DIVINING NUBOTS -1 and, at an Aerostat Site, SCOOP -2 (I6.1).
     */
    static Cards.IsruInfo isruAt(GameState g, Player p, String node, Cards.Analysis an, int i) {
        boolean assay = colocatedAbility(g, p, node, "assay") != null;
        int scoop = 0;
        if (isAerostat(node)) {
            JsonElement v = colocatedAbility(g, p, node, "scoopIsru");
            if (v != null && v.isJsonPrimitive()) scoop = Math.min(0, v.getAsInt());
        }
        return Cards.isru(an, i, hasPushOpt(g, p), assay, scoop);
    }

    /**
     * Site Hydration, raised by an Operational SCOOP (Atmospheric Scoop): it makes the
     * Aerostat Sites Colocated with it or Adjacent to its Space Hydration 2.
     */
    public static int siteWater(GameState g, Player p, String siteId) {
        GameMap.Node T = map().node(siteId);
        int w = T.water;
        if (isAerostat(siteId)) {
            for (String k : StateOps.STACKS) {
                if (p.stacks.get(k) == null) continue;
                String x = StateOps.stackNode(p, k);
                if (x == null || !(x.equals(siteId) || map().adjacentSites(x).contains(siteId))) continue;
                JsonElement v = colocatedAbility(g, p, x, "scoopHydration");
                if (v != null && v.isJsonPrimitive() && v.getAsDouble() != 0) { w = Math.max(w, v.getAsInt()); break; }
            }
        }
        return w;
    }

    /** Sum of size-roll modifiers from Colocated abilities (Thorium Breeder, Foamed Nickel, Comet Lichen, Superlens). */
    public static int sizeRollMod(GameState g, Player p, String node, String siteId, boolean raygun) {
        int mod = 0;
        String spec = map().node(siteId).spec;
        for (String k : StateOps.ownStackKeysAt(p, node)) {
            Cards.Analysis an = Cards.analyze(p.stacks.get(k).cards, map().zoneOf(node));
            for (int i = 0; i < an.st.size(); i++) {
                JsonElement sr = an.st.get(i).abValue("sizeRoll");
                if (sr == null || !sr.isJsonObject() || !Cards.operational(an, i)) continue;
                JsonObject o = sr.getAsJsonObject();
                if (o.has("spec") && !o.get("spec").isJsonNull() && !o.get("spec").getAsString().equals(spec)) continue;
                if (o.has("raygun") && o.get("raygun").getAsBoolean() && !raygun) continue;
                mod += o.get("mod").getAsInt();
            }
        }
        return mod;
    }

    public static final class ProspectOpt {
        public String key;
        public int idx;
        public String node;
        public int isru;
        public String kind;
        public List<String> kinds;
        public boolean raygun;
        public String name;
        public List<String> targets;
        /** The card has a Nanites re-roll (I6a); set only when true. */
        public Boolean reroll;
    }

    public static List<ProspectOpt> prospectOptions(GameState g) {
        Player p = me(g);
        List<ProspectOpt> out = new ArrayList<>();
        for (String key : StateOps.STACKS) {
            Stack s = p.stacks.get(key);
            if (s == null || key.equals("leo")) continue;
            String node = StateOps.stackNode(p, key);
            GameMap.Node N = map().node(node);
            Cards.Analysis an = Cards.analyze(s.cards, N.z);
            for (int i = 0; i < an.st.size(); i++) {
                CardStats st = an.st.get(i);
                if (st.isru == null || !Cards.operational(an, i)) continue;
                Cards.IsruInfo isru = isruAt(g, p, node, an, i);
                boolean raygun = isru.kinds.contains("raygun");
                List<String> targets = new ArrayList<>();
                if (N.t.equals("site")) targets.add(node);
                if (raygun) {
                    // Opaque atmospheres: an Atmospheric Site is prospected at range zero only (I6a).
                    for (String id : map().adjacentSites(node)) {
                        if (!map().node(id).atmo) targets.add(id);
                    }
                }
                List<String> ok = new ArrayList<>();
                for (String id : targets) {
                    Site site = g.sites.get(id);
                    if (!(site != null && (site.claim != null || site.busted)) && isru.r <= siteWater(g, p, id)) ok.add(id);
                }
                if (!ok.isEmpty()) {
                    ProspectOpt o = new ProspectOpt();
                    o.key = key;
                    o.idx = i;
                    o.node = node;
                    o.isru = isru.r;
                    o.kind = isru.k;
                    o.kinds = isru.kinds;
                    o.raygun = raygun;
                    o.name = st.name;
                    o.targets = ok;
                    if (st.ab("reroll")) o.reroll = true;
                    out.add(o);
                }
            }
        }
        return out;
    }

    public static void prospect(GameState g, int optIndex, List<String> siteIds, boolean lunaPermission) {
        prospect(g, optIndex, siteIds, lunaPermission, null, null);
    }

    /**
     * vacate: your unindustrialized Claims whose disks may be moved, in this order, when you
     * have no disk left for a success (I6.4). rerollFirst: the Sites the Nanites re-roll
     * should go to first among the failures (I6a: all rolls first, then one failure is
     * re-rolled); by default the failed Site with the best odds.
     */
    public static void prospect(GameState g, int optIndex, List<String> siteIds, boolean lunaPermission, List<String> vacate, List<String> rerollFirst) {
        requireOp(g);
        Player p = me(g);
        List<ProspectOpt> opts = prospectOptions(g);
        check(optIndex >= 0 && optIndex < opts.size(), "That prospect is not possible.");
        ProspectOpt opt = opts.get(optIndex);
        List<String> ids = new ArrayList<>();
        if (siteIds != null) for (String id : siteIds) if (opt.targets.contains(id)) ids.add(id);
        check(!ids.isEmpty(), "Choose a Site to prospect.");
        if (!opt.raygun) ids = new ArrayList<>(ids.subList(0, 1));
        boolean lunaFelony = false;
        for (String id : ids) {
            if (isLuna(id) && g.firstPlayer != p.idx && !lunaPermission) {
                // Without permission this is a Felony (glossary: Claim Jump): your Human on the Site, no enemy Human.
                check(StateOps.canFelony(g, p), "Prospecting Luna requires the 1st Player's permission (I6.1).");
                StateOps.Check f = StateOps.felonyAt(g, p, id, false);
                check(f.ok, "Prospecting Luna without the 1st Player's permission is a Felony: " + f.reason + ".");
                lunaFelony = true;
            }
        }
        done(g, "prospect");
        if (lunaFelony) {
            g.turn.felony = true;
            Dice.log(g, "Felony: " + p.name + " prospects Luna without the 1st Player's permission.", null, "bad");
        }
        int uid = p.stacks.get(opt.key).cards.get(opt.idx).uid;
        Actions.glitchRoll(g, p.idx, opt.key, "prospect");
        StateOps.cleanupStacks(g, p);
        Stack s = p.stacks.get(opt.key);
        CardInst card = null;
        if (s != null) for (CardInst c : s.cards) if (c.uid != null && c.uid == uid) { card = c; break; }
        if (card == null) {
            Dice.log(g, "The Glitch knocked out the prospecting card: the operation fails.", null, "bad");
            return;
        }
        coolUsedCard(g, p, opt.key, uid);
        boolean opReroll = Cards.stats(card).ab("reroll");
        List<String> disks = new ArrayList<>(vacate == null ? List.of() : vacate);
        List<String> failed = new ArrayList<>();
        boolean blink = opt.raygun && StateOps.privilegeActive(g, p, "blinkTelescope");
        boolean buggy = opt.kinds != null && opt.kinds.contains("buggy");
        for (int n = 0; n < ids.size(); n++) {
            String id = ids.get(n);
            GameMap.Node T = map().node(id);
            Site site = StateOps.site(g, id);
            int mod = sizeRollMod(g, p, opt.node, id, opt.raygun);
            String modTxt = mod != 0 ? " (" + (mod > 0 ? "+" : "") + mod + ")" : "";
            boolean ok;
            if (T.size >= 6 || "Kreutz Sungrazer".equals(T.name)) {
                ok = true;
                Dice.log(g, map().name(id) + ": Size " + T.size + " - automatic success.");
            } else {
                ok = Dice.d6(g, "Size Roll " + map().name(id) + " (need <= " + T.size + ")" + modTxt, p.idx) + mod <= T.size;
                if (!ok && buggy) ok = Dice.d6(g, "Buggy re-roll " + map().name(id) + modTxt, p.idx) + mod <= T.size;
                if (!ok && blink && n == 0) { blink = false; ok = Dice.d6(g, "Blink Telescope re-roll" + modTxt, p.idx) + mod <= T.size; }
            }
            if (!ok && opReroll) { failed.add(id); continue; } // the Nanites re-roll is chosen after all rolls
            settleProspect(g, p, id, ok, disks);
        }
        if (!failed.isEmpty()) {
            String target = null;
            if (rerollFirst != null) for (String id : rerollFirst) if (failed.contains(id)) { target = id; break; }
            if (target == null) {
                int best = Integer.MIN_VALUE;
                for (String id : failed) {
                    int odds = map().node(id).size - sizeRollMod(g, p, opt.node, id, opt.raygun);
                    if (odds > best) { best = odds; target = id; }
                }
            }
            GameMap.Node T = map().node(target);
            int mod = sizeRollMod(g, p, opt.node, target, opt.raygun);
            String modTxt = mod != 0 ? " (" + (mod > 0 ? "+" : "") + mod + ")" : "";
            boolean ok = Dice.d6(g, "Nanites re-roll " + map().name(target) + modTxt, p.idx) + mod <= T.size;
            for (String id : failed) settleProspect(g, p, id, id.equals(target) && ok, disks);
        }
    }

    /** I6.4 / I6.5: a Claim (moving a disk from `disks` if none is left) or a Busted Site. */
    private static void settleProspect(GameState g, Player p, String id, boolean ok, List<String> disks) {
        Site site = StateOps.site(g, id);
        if (!ok) {
            site.busted = true;
            Dice.log(g, map().name(id) + " is Busted.", null, "bad");
            return;
        }
        if (StateOps.counts(g, p.idx).claims() >= 9) {
            String from = null;
            while (from == null && !disks.isEmpty()) {
                String v = disks.remove(0);
                Site vs = g.sites.get(v);
                if (vs != null && vs.claim != null && vs.claim == p.idx && !vs.factory && !v.equals(id)) from = v;
            }
            if (from == null) {
                Dice.log(g, "No Claim disks left: success wasted (free a disk first).", null, "bad");
                return;
            }
            g.sites.get(from).claim = null;
            Dice.log(g, p.name + " moves a Claim disk from " + map().name(from) + " (I6.4).");
        }
        site.claim = p.idx;
        Dice.log(g, p.name + " claims " + map().name(id) + "!", null, "good");
        payTaxes(g, "new Claim");
    }

    /* MINE REVIVAL (Termite Nest, a card Operation) */

    public static final class MineRevivalOpt {
        public String key;
        public String node;
    }

    /** Busted Sites of Size 2+ where one of the player's stacks holds an Operational Termite Nest. */
    public static List<MineRevivalOpt> mineRevivalOptions(GameState g) {
        Player p = me(g);
        List<MineRevivalOpt> out = new ArrayList<>();
        for (String key : StateOps.STACKS) {
            Stack s = p.stacks.get(key);
            if (s == null || key.equals("leo")) continue;
            String node = StateOps.stackNode(p, key);
            GameMap.Node N = map().node(node);
            Site site = g.sites.get(node);
            if (N == null || !N.isSite() || N.size < 2 || site == null || !site.busted) continue;
            if (out.stream().anyMatch(o -> o.node.equals(node))) continue;
            Cards.Analysis an = Cards.analyze(s.cards, N.z);
            for (int i = 0; i < an.st.size(); i++) {
                if (an.st.get(i).ab("mineRevival") && Cards.operational(an, i)) {
                    MineRevivalOpt o = new MineRevivalOpt();
                    o.key = key;
                    o.node = node;
                    out.add(o);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * MINE REVIVAL: as the Operation, remove the busted disk from a Colocated Site of Size 2+
     * and place your Claim there (moving a disk from {@code vacate} when none is left, I6.4).
     */
    public static void mineRevival(GameState g, int optIndex, List<String> vacate) {
        requireOp(g);
        Player p = me(g);
        List<MineRevivalOpt> opts = mineRevivalOptions(g);
        check(optIndex >= 0 && optIndex < opts.size(), "Mine Revival needs an Operational Termite Nest on a Busted Site of Size 2+.");
        MineRevivalOpt o = opts.get(optIndex);
        List<String> disks = new ArrayList<>(vacate == null ? List.of() : vacate);
        boolean diskFree = StateOps.counts(g, p.idx).claims() < 9 || disks.stream().anyMatch(v -> {
            Site vs = g.sites.get(v);
            return vs != null && vs.claim != null && vs.claim == p.idx && !vs.factory;
        });
        check(diskFree, "No Claim disk left: take one from an unindustrialized Claim of yours (I6.4).");
        done(g, "mineRevival");
        StateOps.site(g, o.node).busted = false;
        Dice.log(g, p.name + "'s Termite Nest revives the busted mine on " + map().name(o.node) + " (MINE REVIVAL).", null, "good");
        settleProspect(g, p, o.node, true, disks);
    }

    /* I7 Industrialize */
    public static final class IndustrializeOpt {
        public String key;
        public String node;
        public List<Integer> robos;
        public List<Integer> refs;
        public List<Integer> arcology;
        public Cards.Analysis an;
        /** JELLYBOTS: a Colocated Operational Solid Flame makes this industrialization a free action (null otherwise: wire format). */
        public Boolean free;
    }

    public static List<IndustrializeOpt> industrializeOptions(GameState g) {
        Player p = me(g);
        List<IndustrializeOpt> out = new ArrayList<>();
        for (String key : StateOps.STACKS) {
            Stack s = p.stacks.get(key);
            if (s == null || key.equals("leo")) continue;
            String node = StateOps.stackNode(p, key);
            Site site = g.sites.get(node);
            if (site == null || site.claim == null || site.claim != p.idx || site.factory) continue;
            Cards.Analysis an = Cards.analyze(forIndustrialization(s.cards), map().zoneOf(node));
            List<Integer> robos = new ArrayList<>(), refs = new ArrayList<>();
            for (int i = 0; i < an.st.size(); i++) {
                if (!an.supported[i]) continue;
                if ("robonaut".equals(an.st.get(i).deck)) robos.add(i);
                if ("refinery".equals(an.st.get(i).deck)) refs.add(i);
            }
            // ARCOLOGY (Solar Carbotherm): no robonaut needed in the Mercury, Venus and Earth zones.
            boolean inner = List.of("mercury", "venus", "earth").contains(map().zoneOf(node));
            List<Integer> arcology = new ArrayList<>();
            if (inner) for (int i : refs) if (an.st.get(i).ab("arcology")) arcology.add(i);
            if (!refs.isEmpty() && (!robos.isEmpty() || !arcology.isEmpty())) {
                IndustrializeOpt o = new IndustrializeOpt();
                o.key = key;
                o.node = node;
                o.robos = robos;
                o.refs = refs;
                o.arcology = arcology;
                o.an = an;
                if (colocatedAbility(g, p, node, "jellybots") != null) o.free = true;
                out.add(o);
            }
        }
        return out;
    }

    /**
     * The stack as industrialization sees it: cards that "cannot be used during
     * industrialization" (Magnetoshell Plasma Parachute) support nothing there.
     */
    static List<CardInst> forIndustrialization(List<CardInst> cards) {
        if (cards.stream().noneMatch(c -> Cards.stats(c).ab("noIndustrialize"))) return cards;
        List<CardInst> out = new ArrayList<>();
        for (CardInst c : cards) {
            if (!Cards.stats(c).ab("noIndustrialize")) { out.add(c); continue; }
            CardInst x = com.hf4all.game.util.Json.read(com.hf4all.game.util.Json.write(c), CardInst.class);
            x.nonOp = true;
            out.add(x);
        }
        return out;
    }

    public static void industrialize(GameState g, int optIndex, Integer robo, int ref) {
        Player p = me(g);
        List<IndustrializeOpt> opts = industrializeOptions(g);
        IndustrializeOpt opt = optIndex >= 0 && optIndex < opts.size() ? opts.get(optIndex) : null;
        // JELLYBOTS: Colocated industrialization is a free action (it does not use the Operation).
        boolean free = opt != null && Boolean.TRUE.equals(opt.free);
        if (free) Actions.requireFree(g);
        else requireOp(g);
        boolean noRobo = robo == null || robo < 0;
        check(opt != null && opt.refs.contains(ref) && (noRobo ? opt.arcology.contains(ref) : opt.robos.contains(robo)),
                "Pick an Operational robonaut and refinery" + (opt != null && !opt.arcology.isEmpty() ? " (or an ARCOLOGY refinery alone)" : "") + ".");
        int factories = StateOps.counts(g, p.idx).factories();
        check(factories < 7, "All 7 Factory cubes are in use (I7f): discard one first (G6a, not a colonized Factory).");
        if (free) Dice.log(g, p.name + "'s Solid Flame jellybots industrialize as a free action (JELLYBOTS).");
        else done(g, "industrialize");
        Stack s = p.stacks.get(opt.key);
        java.util.Set<Integer> uids = new java.util.HashSet<>();
        List<Integer> used = new ArrayList<>();
        if (!noRobo) used.addAll(Cards.chain(opt.an, robo, null));
        used.addAll(Cards.chain(opt.an, ref, null));
        for (int i : used) uids.add(s.cards.get(i).uid);
        Integer robUid = noRobo ? null : s.cards.get(robo).uid;
        int refUid = s.cards.get(ref).uid;
        CardStats refStats = opt.an.st.get(ref);
        Actions.glitchRoll(g, p.idx, opt.key, "industrialize");
        Stack s2 = p.stacks.get(opt.key);
        boolean robLost = !noRobo && (s2 == null || s2.cards.stream().noneMatch(c -> c.uid != null && c.uid.equals(robUid)));
        boolean refLost = s2 == null || s2.cards.stream().noneMatch(c -> c.uid != null && c.uid == refUid);
        if (s2 == null || robLost || refLost) {
            Dice.log(g, "The Glitch destroyed the equipment: industrialization fails.", null, "bad");
            StateOps.cleanupStacks(g, p);
            return;
        }
        for (int i = s2.cards.size() - 1; i >= 0; i--) {
            CardInst c = s2.cards.get(i);
            if (c.uid != null && uids.contains(c.uid) && c.crew == null && !"radiator".equals(cards().deckOf(c.id))) {
                Actions.decommission(g, p.idx, opt.key, i, Actions.Decom.of(false, false, "industrialized"));
            }
        }
        Site site = g.sites.get(opt.node);
        site.factory = true;
        GameMap.Node N = map().node(opt.node);
        String spec = N.spec;
        Dice.log(g, p.name + " builds a Factory on " + map().name(opt.node) + " (" + spec + " stock price now " + StateOps.stockPrice(g, spec) + ").", null, "good");
        // POWER GIRDLE (non-atmospheric Size 8+) / IONOSAT (Atmospheric): permanent Powersat privilege.
        String ps = refStats.abString("powersatOnIndustrialize");
        if (("size8".equals(ps) && !N.atmo && N.size >= 8) || ("atmo".equals(ps) && N.atmo)) {
            p.powersat = true;
            Dice.log(g, p.name + " permanently gains the Powersat faction privilege.", null, "good");
        }
        payTaxes(g, "new Factory");
        StateOps.cleanupStacks(g, p);
    }

    /* I8 ET production */
    public static final class EtCard {
        public String cid;
        public int i;
        public EtCard() {}
        public EtCard(String cid, int i) { this.cid = cid; this.i = i; }
    }

    public static final class EtOpt {
        public String node;
        public String spec;
        public List<EtCard> cards;
        public Integer owner;
        public StateOps.Check hijack;
    }

    public static List<EtOpt> etOptions(GameState g) {
        Player p = me(g);
        List<EtOpt> own = new ArrayList<>(), other = new ArrayList<>();
        for (String id : StateOps.siteIds(g)) {
            Site site = g.sites.get(id);
            if (!site.factory || site.claim == null) continue;
            String spec = map().node(id).spec;
            List<EtCard> list = new ArrayList<>();
            for (int i = 0; i < p.hand.size(); i++) {
                CardStats b = Cards.sideStats(p.hand.get(i), "b");
                if (spec.equals(b.spec) || "ANY".equals(b.spec)) list.add(new EtCard(p.hand.get(i), i));
            }
            EtOpt o = new EtOpt();
            o.node = id;
            o.spec = spec;
            o.cards = list;
            if (site.claim != p.idx) {
                o.owner = site.claim;
                o.hijack = StateOps.felonyAt(g, p, id, false);
                other.add(o);
            } else own.add(o);
        }
        own.addAll(other);
        return own;
    }

    /** use: "permission" | "hijack" when producing at an opponent's Factory. */
    public static void etProduce(GameState g, String node, int handIdx, String target, String orient, String use) {
        requireOp(g);
        Player p = me(g);
        EtOpt opt = null;
        for (EtOpt o : etOptions(g)) if (o.node.equals(node)) { opt = o; break; }
        check(opt != null, "There is no Factory there.");
        check(opt.cards.stream().anyMatch(x -> x.i == handIdx), "That card does not match the Factory's spectral type (I8a).");
        if (opt.owner != null) useOpponentFactory(g, p, opt.owner, opt.node, opt.hijack, use, "ET production");
        String id = p.hand.get(handIdx);
        if (target == null || target.isEmpty() || target.equals("new")) {
            String k = freeOutpostKey(p);
            check(k != null, "Both Outposts are in use: pick an existing stack at the Factory.");
            p.stacks.set(k, Stack.at(node));
            target = k;
        }
        check(StateOps.STACKS.contains(target) && p.stacks.get(target) != null && node.equals(StateOps.stackNode(p, target)), "Target stack is not at the Factory.");
        p.hand.remove(handIdx);
        CardInst inst = CardInst.patent(id, "b", newUid(g));
        if ("radiator".equals(cards().deckOf(id))) inst.orient = "light".equals(orient) ? "light" : "heavy";
        if (target.equals("rocket") && p.stacks.rocket.glitch) {
            // The card comes into play in an Outpost (I8); moving it into the glitched Rocket is a
            // cargo transfer, a Glitch Trigger (G1h).
            Actions.glitchRoll(g, p.idx, "rocket", "cargo transfer");
            if (p.stacks.rocket == null) {
                String k = freeOutpostKey(p);
                if (k == null) {
                    p.hand.add(id);
                    Dice.log(g, "The Rocket was lost to the Glitch and no Outpost chit is free: " + Cards.stats(inst).name + " stays in the Hand.", null, "bad");
                    done(g, "etProduction");
                    return;
                }
                p.stacks.set(k, Stack.at(node));
                target = k;
            }
        }
        p.stacks.get(target).cards.add(inst);
        if (target.equals("rocket")) StateOps.syncRocketDry(p);
        Dice.log(g, p.name + " ET-produces " + Cards.stats(inst).name + " (Black-Side) on " + map().name(node) + ".", null, "good");
        done(g, "etProduction");
    }

    /* I9 Delivery */
    public static int deliveryCost(String node) {
        return 2 * map().zonesFromEarth(node) + (map().node(node).size >= 7 ? 1 : 0);
    }

    public static final class DeliveryOpt {
        public String key;
        public int idx;
        public String node;
        public int cost;
        public boolean affordable;
        public String name;
    }

    public static List<DeliveryOpt> deliveryOptions(GameState g) {
        Player p = me(g);
        List<DeliveryOpt> out = new ArrayList<>();
        for (String key : StateOps.STACKS) {
            Stack s = p.stacks.get(key);
            if (s == null || key.equals("leo")) continue;
            String node = StateOps.stackNode(p, key);
            Site site = g.sites.get(node);
            if (site == null || !site.factory) continue;
            int cost = deliveryCost(node);
            for (int i = 0; i < s.cards.size(); i++) {
                CardInst c = s.cards.get(i);
                if ("b".equals(c.side) && c.crew == null) {
                    DeliveryOpt o = new DeliveryOpt();
                    o.key = key;
                    o.idx = i;
                    o.node = node;
                    o.cost = cost;
                    o.affordable = s.ftsOr0() >= cost;
                    o.name = Cards.stats(c).name;
                    out.add(o);
                }
            }
        }
        return out;
    }

    public static void deliver(GameState g, int optIndex) {
        requireOp(g);
        Player p = me(g);
        List<DeliveryOpt> opts = deliveryOptions(g);
        DeliveryOpt opt = optIndex >= 0 && optIndex < opts.size() ? opts.get(optIndex) : null;
        check(opt != null && opt.affordable, "Delivery needs " + (opt != null ? String.valueOf(opt.cost) : "?") + " FTs in the same stack.");
        done(g, "delivery");
        int uid = p.stacks.get(opt.key).cards.get(opt.idx).uid;
        Actions.glitchRoll(g, p.idx, opt.key, "delivery");
        Stack s = p.stacks.get(opt.key);
        int i = -1;
        if (s != null) for (int j = 0; j < s.cards.size(); j++) if (s.cards.get(j).uid != null && s.cards.get(j).uid == uid) { i = j; break; }
        if (i < 0) {
            Dice.log(g, "The card was lost to the Glitch: nothing delivered.", null, "bad");
            StateOps.cleanupStacks(g, p);
            return;
        }
        check(s.ftsOr0() >= opt.cost, "Not enough FTs after the Glitch.");
        s.fts -= opt.cost;
        CardInst card = s.cards.remove(i);
        p.stacks.leo.cards.add(card);
        if (opt.key.equals("rocket")) StateOps.syncRocketDry(p);
        Dice.log(g, p.name + " delivers " + Cards.stats(card).name + " to LEO for " + opt.cost + " FTs.", null, "good");
        StateOps.cleanupStacks(g, p);
    }
}
