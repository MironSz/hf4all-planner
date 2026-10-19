package com.hf4all.game.engine;

import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;
import com.hf4all.game.util.Fmt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static com.hf4all.game.engine.RuleError.check;
import static com.hf4all.game.engine.StateOps.STACK_NAME;

/**
 * Negotiation (N): deals between any two players at any time (also on an
 * opponent's Turn), never during movement or a pending decision (N a). Ported
 * from the former browser engine.
 */
public final class Negotiation {

    private Negotiation() {}

    private static GameMap map() { return GameData.get().map; }

    static void requireDeal(GameState g, int from, int to) {
        check(g.gameOver == null, "The game is over.");
        check(g.move == null, "Items cannot change hands during movement (N a).");
        check(g.pending == null, "Resolve the pending decision first (N a).");
        check(from >= 0 && from < g.players.size() && to >= 0 && to < g.players.size(), "Pick two players.");
        check(from != to, "Pick two different players.");
    }

    public static StateOps.Check canTrade(GameState g) {
        if (g.gameOver != null) return new StateOps.Check(false, "The game is over.");
        if (g.move != null) return new StateOps.Check(false, "Not during movement (N a).");
        if (g.pending != null) return new StateOps.Check(false, "Resolve the pending decision first (N a).");
        return new StateOps.Check(true, null);
    }

    /* N1 Aquas */
    public static void giveAquas(GameState g, int from, int to, double amount) {
        requireDeal(g, from, to);
        int n = (int) Math.floor(amount);
        check(n > 0, "Choose how many Aquas.");
        Player a = g.players.get(from), b = g.players.get(to);
        check(a.aquas >= n, a.name + " has only " + a.aquas + " Aquas.");
        a.aquas -= n;
        b.aquas += n;
        Dice.log(g, "Deal: " + a.name + " pays " + b.name + " " + Fmt.plural(n, "Aqua") + " (N1).", from, null);
    }

    /* N2 Hand cards */
    public static GameState.TradeLock tradeLock(GameState g, String cardId) {
        if (g.tradeLocks == null) return null;
        for (GameState.TradeLock l : g.tradeLocks) if (l.id.equals(cardId)) return l;
        return null;
    }

    public static void giveHandCards(GameState g, int from, int to, List<Integer> handIdxs) {
        requireDeal(g, from, to);
        Player a = g.players.get(from), b = g.players.get(to);
        List<Integer> idxs = new ArrayList<>(new LinkedHashSet<>(handIdxs == null ? List.of() : handIdxs));
        idxs.sort((x, y) -> y - x);
        check(!idxs.isEmpty(), "Select Hand cards to give.");
        check(idxs.stream().allMatch(i -> i >= 0 && i < a.hand.size()), "No such Hand card.");
        for (int i : idxs) {
            GameState.TradeLock lock = tradeLock(g, a.hand.get(i));
            if (lock != null) {
                String o = g.players.get(lock.owner).name;
                RuleError.fail(GameData.get().cards.def(a.hand.get(i)).w.name + " was traded from " + o + "'s Hand and cannot be traded again before " + o + "'s next Turn (N2a).");
            }
        }
        List<String> ids = new ArrayList<>();
        for (int i : idxs) ids.add(0, a.hand.remove(i));
        b.hand.addAll(ids);
        List<GameState.TradeLock> locks = new ArrayList<>(g.tradeLocks == null ? List.of() : g.tradeLocks);
        for (String id : ids) locks.add(new GameState.TradeLock(id, from));
        g.tradeLocks = locks;
        List<String> names = new ArrayList<>();
        for (String id : ids) names.add(GameData.get().cards.def(id).w.name);
        Dice.log(g, "Deal: " + a.name + " gives " + b.name + " " + String.join(", ", names) + " from Hand (N2).", from, null);
    }

    /* N3 Stacks & Figures */
    public static final class Target {
        public String key;
        public String label;
        public boolean exists;
        public Target() {}
        public Target(String key, String label, boolean exists) { this.key = key; this.label = label; this.exists = exists; }
    }

    /** Where cards/FTs from `from`'s stack `key` can go in `to`'s playmat (N3). */
    public static List<Target> stackTargets(GameState g, int from, String key, int to) {
        Player a = g.players.get(from), b = g.players.get(to);
        String node = StateOps.stackNode(a, key);
        List<Target> out = new ArrayList<>();
        for (String k : StateOps.STACKS) {
            if (b.stacks.get(k) != null && node != null && node.equals(StateOps.stackNode(b, k))) out.add(new Target(k, STACK_NAME.get(k), true));
            else if (b.stacks.get(k) == null && !k.equals("leo")) out.add(new Target(k, "new " + STACK_NAME.get(k) + " here", false));
        }
        return out;
    }

    /** Trade cards and FTs from `from`'s stack into `to`'s stack on the same Space (created if free). */
    public static void giveStackItems(GameState g, int from, String key, List<Integer> cardIdxs, double ftsIn, int to, String toKey) {
        requireDeal(g, from, to);
        Player a = g.players.get(from), b = g.players.get(to);
        check(StateOps.STACKS.contains(key), "Pick a stack.");
        Stack src = a.stacks.get(key);
        check(src != null, a.name + " has no " + STACK_NAME.get(key) + ".");
        String node = StateOps.stackNode(a, key);
        List<Integer> idxs = new ArrayList<>(new LinkedHashSet<>(cardIdxs == null ? List.of() : cardIdxs));
        idxs.sort((x, y) -> y - x);
        int fts = (int) Math.floor(ftsIn);
        check(!idxs.isEmpty() || fts > 0, "Nothing selected to give.");
        check(idxs.stream().allMatch(i -> i >= 0 && i < src.cards.size()), "No such card.");
        check(fts >= 0 && fts <= StateOps.stackFts(a, key), "Not enough " + (key.equals("leo") ? "Aquas" : "FTs") + ".");
        if (!StateOps.STACKS.contains(toKey) || b.stacks.get(toKey) == null) {
            check(!"leo".equals(toKey) && StateOps.STACKS.contains(toKey), "Pick a stack of " + b.name + ".");
            b.stacks.set(toKey, Stack.at(node));
            Dice.log(g, b.name + " creates a new " + STACK_NAME.get(toKey) + " at " + map().name(node) + " to receive the cargo (N3).", to, null);
        }
        check(node.equals(StateOps.stackNode(b, toKey)), "The receiving stack must be on the same Space (" + map().name(node) + ").");
        Stack dst = b.stacks.get(toKey);
        if (src.glitch || dst.glitch) {
            Actions.glitchRoll(g, from, key, a.name + " " + STACK_NAME.get(key));
            Actions.glitchRoll(g, to, toKey, b.name + " " + STACK_NAME.get(toKey));
            if (a.stacks.get(key) != null) a.stacks.get(key).glitch = true;
            if (b.stacks.get(toKey) != null) b.stacks.get(toKey).glitch = true;
            if (a.stacks.get(key) == null || b.stacks.get(toKey) == null) {
                StateOps.cleanupStacks(g, a);
                StateOps.cleanupStacks(g, b);
                return;
            }
        }
        List<CardInst> moved = new ArrayList<>();
        for (int i : idxs) {
            if (i >= src.cards.size()) continue; // lost to the Glitch Roll
            moved.add(0, src.cards.remove(i));
        }
        dst.cards.addAll(moved);
        int ftsNow = Math.min(fts, StateOps.stackFts(a, key));
        if (ftsNow != 0) { StateOps.addFts(a, key, -ftsNow); StateOps.addFts(b, toKey, ftsNow); }
        if (key.equals("rocket")) StateOps.syncRocketDry(a);
        if (toKey.equals("rocket")) StateOps.syncRocketDry(b);
        List<String> what = new ArrayList<>();
        for (CardInst c : moved) {
            what.add(Cards.stats(c).name + (c.crew != null ? " (stays under " + g.players.get(StateOps.crewOwner(g, c, from)).name + "'s control)" : ""));
        }
        if (ftsNow != 0) what.add(Fmt.plural(ftsNow, key.equals("leo") ? "Aqua" : "FT"));
        Dice.log(g, "Deal: " + a.name + " gives " + b.name + " " + (what.isEmpty() ? "nothing" : String.join(", ", what)) + ": " +
                STACK_NAME.get(key) + " -> " + STACK_NAME.get(toKey) + " at " + map().name(node) + " (N3).", from, null);
        Actions.checkParade(g, b);
        StateOps.cleanupStacks(g, a);
        StateOps.cleanupStacks(g, b);
    }

    public static List<String> freeSlots(GameState g, int to) {
        List<String> out = new ArrayList<>();
        for (String k : List.of("rocket", "out1", "out2")) if (g.players.get(to).stacks.get(k) == null) out.add(k);
        return out;
    }

    /** Hand over a whole Rocket / Outpost (its Figure changes colour, N3). */
    public static void giveStack(GameState g, int from, String key, int to, String toKey) {
        requireDeal(g, from, to);
        Player a = g.players.get(from), b = g.players.get(to);
        check(StateOps.STACKS.contains(key) && !key.equals("leo") && a.stacks.get(key) != null, "Pick a Rocket or Outpost of " + a.name + ".");
        check(List.of("rocket", "out1", "out2").contains(toKey) && b.stacks.get(toKey) == null,
                b.name + " has no free " + (STACK_NAME.containsKey(toKey) ? STACK_NAME.get(toKey) : "slot") + " (stack limits, E3 / N3b).");
        Stack s = a.stacks.get(key);
        a.stacks.set(key, null);
        if (key.equals("rocket") && !toKey.equals("rocket")) {
            if (s.fuel != null && FuelTrack.fuelSteps(s.fuel) > 0) Dice.log(g, "The Fuel on the Rocket's Fuel Strip is lost: it becomes an Outpost.", from, null);
            s.fuel = null;
        }
        if (toKey.equals("rocket") && !key.equals("rocket")) s.fuel = null;
        b.stacks.set(toKey, s);
        if (toKey.equals("rocket")) StateOps.syncRocketDry(b);
        Dice.log(g, "Deal: " + a.name + "'s " + STACK_NAME.get(key) + " at " + map().name(s.node) + " becomes " + b.name + "'s " + STACK_NAME.get(toKey) + " (N3).", from, null);
        Actions.checkParade(g, b);
    }

    public static List<String> ownedSites(GameState g, int pi) {
        List<String> out = new ArrayList<>();
        for (String id : StateOps.siteIds(g)) {
            Site s = g.sites.get(id);
            if (s.claim != null && s.claim == pi) out.add(id);
        }
        return out;
    }

    /** Transfer a Claim with its Factory and Colony (N3; component limits N3b). */
    public static void giveSite(GameState g, int from, String siteId, int to) {
        requireDeal(g, from, to);
        Player a = g.players.get(from), b = g.players.get(to);
        Site site = g.sites.get(siteId);
        check(site != null && site.claim != null && site.claim == from, a.name + " does not own that Site.");
        StateOps.Counts c = StateOps.counts(g, to);
        check(c.claims() < 9, b.name + " has no Claim disk left (G4a, N3b).");
        check(!site.factory || c.factories() < 7, b.name + " has no Factory cube left (I7f, N3b).");
        site.claim = to;
        String what = site.colony ? "Colony, Factory and Claim" : site.factory ? "Factory and Claim" : "Claim";
        Dice.log(g, "Deal: " + a.name + " hands " + b.name + " the " + what + " on " + map().name(siteId) + " (N3).", from, null);
    }
}
