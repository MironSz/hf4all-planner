package com.hf4all.game.engine;

import com.hf4all.game.data.CardDb;
import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.data.Rules;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;
import com.hf4all.game.model.Turn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Game creation and shared state queries (the browser's js/state.js). */
public final class StateOps {

    private StateOps() {}

    public static final List<String> STACKS = List.of("leo", "rocket", "out1", "out2");
    public static final Map<String, String> STACK_NAME = Map.of("leo", "LEO", "rocket", "Rocket", "out1", "Outpost 1", "out2", "Outpost 2");

    static GameData data() { return GameData.get(); }
    static GameMap map() { return GameData.get().map; }
    static Rules rules() { return GameData.get().rules; }

    /** A player at setup: name (blank = faction), Crew card and its face. */
    public record PlayerConfig(String name, String crew, int face) {}

    public static GameState newGame(long seed, int cycles, List<PlayerConfig> players) {
        GameState g = new GameState();
        long s = seed & 0xFFFFFFFFL;
        g.rng = s != 0 ? s : 1;
        g.seed = g.rng;
        g.config = new GameState.Config();
        g.config.cycles = cycles > 0 ? cycles : 4;
        g.config.heroism = players.size() >= 3;
        g.year = 1;
        g.cyclePos = 0;
        g.seniority = g.config.cycles;
        for (String d : CardDb.DECKS) {
            List<String> ids = new ArrayList<>();
            for (CardDb.CardDef c : data().cards.deck(d)) ids.add(c.id);
            g.decks.put(d, Dice.shuffle(g, ids));
        }
        for (int i = 0; i < players.size(); i++) {
            PlayerConfig pc = players.get(i);
            CardDb.Crew crew = data().cards.crew(pc.crew());
            RuleError.check(crew != null, "Unknown Crew card " + pc.crew() + ".");
            RuleError.check(pc.face() == 0 || pc.face() == 1, "A Crew card has faces 0 and 1.");
            CardDb.CrewSide side = crew.sides.get(pc.face());
            Player p = new Player();
            p.idx = i;
            p.name = pc.name() == null || pc.name().isBlank() ? side.faction : pc.name();
            p.color = crew.color;
            p.crew = pc.crew();
            p.face = pc.face();
            p.faction = side.faction;
            p.privilege = side.privilege;
            p.clout = side.clout;
            p.aquas = 6 + ("secretaryGeneral".equals(side.privilege) ? 2 : 0);
            p.stacks.leo.cards.add(CardInst.crew(pc.crew(), pc.face(), i + 1));
            g.players.add(p);
        }
        // C8: most clout (A best) becomes the 1st Player.
        int best = 0;
        for (int i = 0; i < g.players.size(); i++) if (g.players.get(i).clout.compareTo(g.players.get(best).clout) < 0) best = i;
        g.firstPlayer = best;
        g.current = best;
        for (Rules.Zone z : rules().zones) g.glory.put(z.id(), true);
        if (g.config.heroism) for (Rules.Heroism h : rules().heroism) g.heroism.put(h.id(), null);
        g.turn = Turn.fresh();
        List<String> names = new ArrayList<>();
        for (Player p : g.players) names.add(p.name + " (" + p.faction + ")");
        Dice.log(g, "New game: " + String.join(", ", names) + ". " + g.players.get(best).name + " has the most clout and is 1st Player.", best, "system");
        Dice.log(g, "Year 1 begins.", null, "system");
        return g;
    }

    /** Hand the turn to player pi (D1); pi's Hand-card trade locks expire now (N2a). */
    public static void beginTurn(GameState g, int pi) {
        g.current = pi;
        g.turn = Turn.fresh();
        g.turnNo++;
        if (g.tradeLocks != null) g.tradeLocks.removeIf(l -> l.owner == pi);
    }

    public static boolean privilegeActive(GameState g, Player p, String key) {
        return g.anarchy ? key.equals("felonious") : key.equals(p.privilege);
    }

    public static String stackNode(Player p, String key) {
        if (key.equals("leo")) return map().leo;
        Stack s = p.stacks.get(key);
        return s != null ? s.node : null;
    }

    public static int stackFts(Player p, String key) {
        if (key.equals("leo")) return p.aquas;
        Stack s = p.stacks.get(key);
        return s != null ? s.ftsOr0() : 0;
    }

    public static void addFts(Player p, String key, int n) {
        if (key.equals("leo")) {
            p.aquas += n;
            RuleError.check(p.aquas >= 0, "Not enough Aquas.");
            return;
        }
        Stack s = p.stacks.get(key);
        s.fts = s.ftsOr0() + n;
        RuleError.check(s.fts >= 0, "Not enough FTs in " + STACK_NAME.get(key) + ".");
    }

    /** Cargo mass of a stack: cards + FT beads (F2). */
    public static int cargoMass(Player p, String key) {
        Stack s = p.stacks.get(key);
        if (s == null) return 0;
        int m = 0;
        for (CardInst c : s.cards) m += Cards.massOf(c);
        return m + (key.equals("leo") ? 0 : s.ftsOr0());
    }

    /** Recompute the Rocket's Dry Mass chit after cargo changed (F2b). */
    public static void syncRocketDry(Player p) {
        Stack r = p.stacks.rocket;
        if (r == null) return;
        int dry = Math.max(1, cargoMass(p, "rocket"));
        if (r.fuel == null) r.fuel = FuelTrack.create(dry);
        else if (dry != r.fuel.dry) FuelTrack.setDry(r.fuel, dry, "red");
    }

    public record StackAt(int p, String key, Stack stack) {}

    public static List<StackAt> stacksAt(GameState g, String nodeId) {
        List<StackAt> out = new ArrayList<>();
        for (int pi = 0; pi < g.players.size(); pi++) {
            Player p = g.players.get(pi);
            for (String k : STACKS) {
                if (p.stacks.get(k) == null || !nodeId.equals(stackNode(p, k))) continue;
                out.add(new StackAt(pi, k, p.stacks.get(k)));
            }
        }
        return out;
    }

    public static List<String> ownStackKeysAt(Player p, String nodeId) {
        List<String> out = new ArrayList<>();
        for (String k : STACKS) if (p.stacks.get(k) != null && nodeId != null && nodeId.equals(stackNode(p, k))) out.add(k);
        return out;
    }

    public static boolean stackIsEmpty(Player p, String key) {
        Stack s = p.stacks.get(key);
        return s == null || (s.cards.isEmpty() && !(s.ftsOr0() > 0));
    }

    /** E2e: an empty non-LEO stack loses its Figure. */
    public static void cleanupStacks(GameState g, Player p) {
        for (String k : List.of("rocket", "out1", "out2")) {
            if (p.stacks.get(k) != null && stackIsEmpty(p, k)) {
                Dice.log(g, p.name + "'s " + STACK_NAME.get(k) + " is empty and leaves the map.", p.idx, null);
                p.stacks.set(k, null);
            }
        }
    }

    /** The player controlling a Crew card (a Crew in an opponent's stack stays its owner's, N3d). */
    public static int crewOwner(GameState g, CardInst card, int fallback) {
        for (int i = 0; i < g.players.size(); i++) if (g.players.get(i).crew.equals(card.crew)) return i;
        return fallback;
    }

    /** Humans of each player at a node: Crew in any stack (by controller), Colonies, LEO (always manned). */
    public static Set<Integer> humansAt(GameState g, String nodeId) {
        Set<Integer> set = new LinkedHashSet<>();
        for (int pi = 0; pi < g.players.size(); pi++) {
            Player p = g.players.get(pi);
            if (nodeId.equals(map().leo)) set.add(pi);
            for (String k : STACKS) {
                if (p.stacks.get(k) == null || !nodeId.equals(stackNode(p, k))) continue;
                for (CardInst c : p.stacks.get(k).cards) if (c.crew != null) set.add(crewOwner(g, c, pi));
            }
        }
        Site site = g.sites.get(nodeId);
        if (site != null && site.colony && site.claim != null) set.add(site.claim);
        return set;
    }

    /** Where p's Crew is. */
    public record CrewLoc(int pi, String key, int idx, String node) {}

    public static CrewLoc crewLocation(GameState g, Player p) {
        for (Player q : g.players) {
            for (String k : STACKS) {
                Stack s = q.stacks.get(k);
                if (s == null) continue;
                for (int i = 0; i < s.cards.size(); i++) {
                    CardInst c = s.cards.get(i);
                    if (c.crew != null && c.crew.equals(p.crew)) return new CrewLoc(q.idx, k, i, stackNode(q, k));
                }
            }
        }
        return null;
    }

    public static boolean canFelony(GameState g, Player p) { return privilegeActive(g, p, "felonious"); }

    /** Result of a check, with the reason when it fails. */
    public static final class Check {
        public boolean ok;
        public String reason;
        public Check() {}
        public Check(boolean ok, String reason) { this.ok = ok; this.reason = reason; }
    }

    /** Can p commit a Felony at a node? Needs the privilege, p's Human there (or aboard) and no enemy Human. */
    public static Check felonyAt(GameState g, Player p, String nodeId, boolean aboard) {
        if (!canFelony(g, p)) return new Check(false, "Felonies need the Felonious privilege or Anarchy");
        Set<Integer> humans = humansAt(g, nodeId);
        if (!humans.contains(p.idx) && !aboard) return new Check(false, "your Human must be on the Space");
        for (int x : humans) {
            if (x != p.idx) return new Check(false, g.players.get(x).name + "'s Human defends it");
        }
        return new Check(true, null);
    }

    /** Does the player have the Powersat Ability (privilege, push Factory, or card)? */
    public static boolean hasPowersat(GameState g, Player p) {
        if (privilegeActive(g, p, "powersat")) return true;
        // Gained from a card (Power Girdle, Ionosat): Anarchy replaces only the faction
        // privilege listed on the Crew (K2e; glossary Powersat d, h).
        if (Boolean.TRUE.equals(p.powersat)) return true;
        for (String id : siteIds(g)) {
            Site s = g.sites.get(id);
            if (s.factory && s.claim != null && s.claim == p.idx && map().node(id).push) return true;
        }
        for (String k : STACKS) {
            Stack st = p.stacks.get(k);
            if (st == null) continue;
            Cards.Analysis an = Cards.analyze(st.cards, map().zoneOf(stackNode(p, k)));
            for (int i = 0; i < an.st.size(); i++) if (an.st.get(i).ab("powersat") && Cards.operational(an, i)) return true;
        }
        return false;
    }

    public static Site site(GameState g, String id) {
        return g.sites.computeIfAbsent(id, k -> new Site());
    }

    /** Site ids in JS object key order (numeric ids ascending). */
    public static List<String> siteIds(GameState g) {
        List<String> ids = new ArrayList<>(g.sites.keySet());
        ids.sort(GameMap::jsKeyOrder);
        return ids;
    }

    public static int factoryCount(GameState g, String spec) {
        int n = 0;
        for (String id : siteIds(g)) if (g.sites.get(id).factory && spec.equals(map().node(id).spec)) n++;
        return n;
    }

    public static int stockPrice(GameState g, String spec) {
        return rules().stockPrice[Math.min(factoryCount(g, spec), 3)];
    }

    public record Counts(int claims, int factories, int colonies) {}

    public static Counts counts(GameState g, int pi) {
        int claims = 0, factories = 0, colonies = 0;
        for (String id : siteIds(g)) {
            Site s = g.sites.get(id);
            if (s.claim == null || s.claim != pi) continue;
            claims++;
            if (s.factory) factories++;
            if (s.colony) colonies++;
        }
        return new Counts(claims, factories, colonies);
    }

    public static String season(GameState g) { return rules().seasonAt(g.cyclePos); }

    public static List<Integer> order(GameState g) {
        int n = g.players.size();
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < n; k++) out.add((g.firstPlayer + k) % n);
        return out;
    }

    public static Integer nextPlayer(GameState g) {
        List<Integer> ord = order(g);
        int k = ord.indexOf(g.current);
        return k + 1 < ord.size() ? ord.get(k + 1) : null;
    }

    public static CardInst cardAt(Player p, String key, int idx) {
        Stack s = p.stacks.get(key);
        return s != null && idx >= 0 && idx < s.cards.size() ? s.cards.get(idx) : null;
    }
}
