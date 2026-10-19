package com.hf4all.game.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hf4all.game.engine.Actions;
import com.hf4all.game.engine.Events;
import com.hf4all.game.engine.Movement;
import com.hf4all.game.engine.Negotiation;
import com.hf4all.game.engine.Operations;
import com.hf4all.game.engine.RuleError;
import com.hf4all.game.engine.StateOps;
import com.hf4all.game.model.GameState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every engine function the client may call, by the name of the browser
 * engine function ("movement.step", "ops.boost", ...) with the same positional
 * arguments minus the game. Commands change the game; queries only read it.
 * A few internal primitives are callable only by the parity tests.
 */
public final class Rpc {

    public enum Kind { COMMAND, QUERY, INTERNAL }

    @FunctionalInterface
    public interface Fn { Object call(GameState g, Args a); }

    public record Entry(String name, Kind kind, Fn fn) {}

    private static final Map<String, Entry> ALL = new LinkedHashMap<>();

    private static void cmd(String name, Fn fn) { ALL.put(name, new Entry(name, Kind.COMMAND, fn)); }
    private static void query(String name, Fn fn) { ALL.put(name, new Entry(name, Kind.QUERY, fn)); }
    private static void internal(String name, Fn fn) { ALL.put(name, new Entry(name, Kind.INTERNAL, fn)); }

    private Rpc() {}

    public static Entry get(String name) { return ALL.get(name); }

    public static Map<String, Entry> all() { return ALL; }

    /** Run a function by name; throws RuleError for an unknown name. */
    public static Object call(GameState g, String name, JsonArray args) {
        Entry e = ALL.get(name);
        RuleError.check(e != null, "Unknown command " + name + ".");
        return e.fn().call(g, new Args(args));
    }

    static Movement.PlanOpts planOpts(JsonElement e) {
        Movement.PlanOpts o = new Movement.PlanOpts();
        if (e != null && e.isJsonObject()) {
            JsonObject j = e.getAsJsonObject();
            o.push = Args.truthy(j.get("push"));
            o.chain = Args.intMap(j.get("chain"));
        }
        return o;
    }

    static Movement.StepChoices stepChoices(JsonElement e) {
        Movement.StepChoices c = new Movement.StepChoices();
        if (e != null && e.isJsonObject()) {
            JsonObject j = e.getAsJsonObject();
            c.finao = Args.truthy(j.get("finao"));
            c.permission = Args.truthy(j.get("permission"));
            c.hijack = Args.truthy(j.get("hijack"));
        }
        return c;
    }

    static List<Operations.BoostItem> boostItems(JsonArray arr) {
        List<Operations.BoostItem> out = new ArrayList<>();
        for (JsonElement x : arr) {
            JsonObject o = x.getAsJsonObject();
            out.add(new Operations.BoostItem(o.get("handIdx").getAsInt(), o.has("orient") && !o.get("orient").isJsonNull() ? o.get("orient").getAsString() : null));
        }
        return out;
    }

    static Map<Integer, Double> bids(JsonObject o) {
        Map<Integer, Double> m = new LinkedHashMap<>();
        for (var en : o.entrySet()) {
            JsonElement v = en.getValue();
            if (v.isJsonNull()) continue;
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                String s = v.getAsString().trim();
                if (s.isEmpty()) continue;
                try { m.put(Integer.parseInt(en.getKey()), Double.parseDouble(s)); } catch (NumberFormatException ex) { /* isNaN: skip */ }
                continue;
            }
            m.put(Integer.parseInt(en.getKey()), v.getAsDouble());
        }
        return m;
    }

    static {
        /* ---------------- free actions (G) ---------------- */
        cmd("actions.transfer", (g, a) -> { Actions.transfer(g, a.str(0), a.str(1), a.intList(2), a.integer(3, 0)); return null; });
        cmd("actions.loadFuel", (g, a) -> { Actions.loadFuel(g, a.str(0), a.integer(1, 0)); return null; });
        cmd("actions.ftToFuel", (g, a) -> { Actions.ftToFuel(g, a.integer(0, 0)); return null; });
        cmd("actions.fuelToFt", (g, a) -> { Actions.fuelToFt(g, a.integer(0, 0)); return null; });
        cmd("actions.jettisonFuel", (g, a) -> { Actions.jettisonFuel(g, a.integer(0, 0)); return null; });
        cmd("actions.jettisonFts", (g, a) -> { Actions.jettisonFts(g, a.str(0), a.integer(1, 0)); return null; });
        cmd("actions.voluntaryDecommission", (g, a) -> { Actions.voluntaryDecommission(g, a.str(0), a.integer(1, -1)); return null; });
        cmd("actions.radiatorToLight", (g, a) -> { Actions.radiatorToLight(g, a.str(0), a.integer(1, -1), a.bool(2)); return null; });
        cmd("actions.discardFactory", (g, a) -> { Actions.discardFactory(g, a.str(0)); return null; });
        cmd("actions.discardClaim", (g, a) -> { Actions.discardClaim(g, a.str(0)); return null; });
        cmd("actions.phileasFogg", (g, a) -> { Actions.phileasFogg(g, a.intList(0)); return null; });
        cmd("actions.dirtRefuel", (g, a) -> { Actions.dirtRefuel(g, a.integer(0, 0)); return null; });
        cmd("actions.mooncable", (g, a) -> { Actions.mooncable(g); return null; });
        cmd("actions.swapRocket", (g, a) -> { Actions.swapRocket(g, a.str(0)); return null; });
        cmd("actions.buildColony", (g, a) -> { Actions.buildColony(g); return null; });
        cmd("actions.claimJump", (g, a) -> { Actions.claimJump(g); return null; });
        cmd("actions.loadGlory", (g, a) -> { Actions.loadGlory(g); return null; });
        cmd("actions.glitchRepair", (g, a) -> { Actions.glitchRepair(g, a.str(0)); return null; });
        cmd("actions.murder", (g, a) -> { Actions.murder(g, a.integer(0, -1), a.str(1), a.integer(2, -1)); return null; });
        cmd("actions.discardHand", (g, a) -> { Actions.discardHand(g, a.integer(0, -1)); return null; });
        query("actions.dirtRefuelInfo", (g, a) -> Actions.dirtRefuelInfo(g));
        query("actions.murderOptions", (g, a) -> Actions.murderOptions(g));
        query("actions.finaoCost", (g, a) -> Actions.finaoCost(g, a.player(g, 0)));

        /* ---------------- operations (I) ---------------- */
        cmd("ops.income", (g, a) -> { Operations.income(g); return null; });
        cmd("ops.startAuction", (g, a) -> { Operations.startAuction(g, a.str(0)); return null; });
        cmd("ops.resolveAuction", (g, a) -> { Operations.resolveAuction(g, bids(a.obj(0)), a.integer(1)); return null; });
        cmd("ops.sellHand", (g, a) -> { Operations.sellHand(g, a.integer(0, -1)); return null; });
        cmd("ops.sellBlack", (g, a) -> {
            // (leoIdx) as recorded by the browser, or (stack key, index) for any stack in the Bank.
            if (a.get(0).isJsonPrimitive() && a.get(0).getAsJsonPrimitive().isString()) Operations.sellBlack(g, a.str(0), a.integer(1, -1));
            else Operations.sellBlack(g, a.integer(0, -1));
            return null;
        });
        cmd("ops.boost", (g, a) -> { Operations.boost(g, boostItems(a.arr(0))); return null; });
        cmd("ops.refuel", (g, a) -> { Operations.refuel(g, a.integer(0, -1), a.str(1), a.bool(2), a.str(3)); return null; });
        cmd("ops.prospect", (g, a) -> { Operations.prospect(g, a.integer(0, -1), a.strList(1), a.bool(2), a.strList(3), a.strList(4)); return null; });
        cmd("ops.industrialize", (g, a) -> { Operations.industrialize(g, a.integer(0, -1), a.integer(1), a.integer(2, -1)); return null; });
        cmd("ops.etProduce", (g, a) -> { Operations.etProduce(g, a.str(0), a.integer(1, -1), a.str(2), a.str(3), a.str(4)); return null; });
        cmd("ops.deliver", (g, a) -> { Operations.deliver(g, a.integer(0, -1)); return null; });
        cmd("ops.mineRevival", (g, a) -> { Operations.mineRevival(g, a.integer(0, -1), a.strList(1)); return null; });
        query("ops.canBid", (g, a) -> Operations.canBid(g, a.integer(0, -1)));
        query("ops.boostCost", (g, a) -> Operations.boostCost(g, boostItems(a.arr(0))));
        query("ops.refuelOptions", (g, a) -> Operations.refuelOptions(g));
        query("ops.prospectOptions", (g, a) -> Operations.prospectOptions(g));
        query("ops.industrializeOptions", (g, a) -> Operations.industrializeOptions(g));
        query("ops.etOptions", (g, a) -> Operations.etOptions(g));
        query("ops.deliveryOptions", (g, a) -> Operations.deliveryOptions(g));
        query("ops.mineRevivalOptions", (g, a) -> Operations.mineRevivalOptions(g));
        internal("ops.sizeRollMod", (g, a) -> Operations.sizeRollMod(g, a.player(g, 0), a.str(1), a.str(2), a.bool(3)));
        internal("ops.siteWater", (g, a) -> Operations.siteWater(g, a.player(g, 0), a.str(1)));

        /* ---------------- movement (H) ---------------- */
        cmd("movement.start", (g, a) -> { Movement.start(g, a.integer(0, -1), a.bool(1), planOpts(a.get(2))); return null; });
        cmd("movement.saveEngine", (g, a) -> Movement.saveEngine(g, a.integer(0, -1), Args.intMap(a.get(1))));
        cmd("movement.deleteEngine", (g, a) -> { Movement.deleteEngine(g, a.str(0)); return null; });
        cmd("movement.startEngine", (g, a) -> { Movement.startEngine(g, a.str(0), a.bool(1), planOpts(a.get(2))); return null; });
        query("movement.engines", (g, a) -> Movement.engines(g));
        cmd("movement.step", (g, a) -> { Movement.step(g, a.str(0), stepChoices(a.get(1))); return null; });
        cmd("movement.end", (g, a) -> { Movement.end(g); return null; });
        cmd("movement.cancel", (g, a) -> { Movement.cancel(g); return null; });
        query("movement.thrustOptions", (g, a) -> Movement.thrustOptions(g, planOpts(a.get(0))));
        query("movement.plan", (g, a) -> {
            RuleError.check(g.players.get(g.current).stacks.rocket != null, "You have no Rocket.");
            return Movement.plan(g, a.integer(0, -1), a.bool(1), planOpts(a.get(2)));
        });
        query("movement.options", (g, a) -> Movement.options(g));
        query("movement.canEnd", (g, a) -> Movement.canEnd(g));
        query("movement.aeroSafe", (g, a) -> Movement.aeroSafe(g));
        query("movement.thrusterAlive", (g, a) -> g.move != null && Movement.thrusterAlive(g));

        /* ---------------- turn, events, scoring ---------------- */
        cmd("events.endTurn", (g, a) -> { Events.endTurn(g); return null; });
        cmd("events.resolveBudgetCut", (g, a) -> { Events.resolveBudgetCut(g, a.integer(0, -1)); return null; });
        cmd("events.resolveGlitch", (g, a) -> { Events.resolveGlitch(g, a.str(0)); return null; });
        cmd("events.resolvePadExplosion", (g, a) -> { Events.resolvePadExplosion(g, a.str(0)); return null; });
        cmd("events.awardHeroism", (g, a) -> { Events.awardHeroism(g, a.str(0), a.integer(1, -1)); return null; });
        query("events.endTurnProblems", (g, a) -> Events.endTurnProblems(g));
        query("events.score", (g, a) -> Events.score(g, a.integer(0, -1)));

        /* ---------------- negotiation (N) ---------------- */
        cmd("negotiation.giveAquas", (g, a) -> { Negotiation.giveAquas(g, a.integer(0, -1), a.integer(1, -1), a.number(2) == null ? 0 : a.number(2)); return null; });
        cmd("negotiation.giveHandCards", (g, a) -> { Negotiation.giveHandCards(g, a.integer(0, -1), a.integer(1, -1), a.intList(2)); return null; });
        cmd("negotiation.giveStackItems", (g, a) -> {
            Negotiation.giveStackItems(g, a.integer(0, -1), a.str(1), a.intList(2), a.number(3) == null ? 0 : a.number(3), a.integer(4, -1), a.str(5));
            return null;
        });
        cmd("negotiation.giveStack", (g, a) -> { Negotiation.giveStack(g, a.integer(0, -1), a.str(1), a.integer(2, -1), a.str(3)); return null; });
        cmd("negotiation.giveSite", (g, a) -> { Negotiation.giveSite(g, a.integer(0, -1), a.str(1), a.integer(2, -1)); return null; });
        query("negotiation.canTrade", (g, a) -> Negotiation.canTrade(g));
        query("negotiation.tradeLock", (g, a) -> Negotiation.tradeLock(g, a.str(0)));
        query("negotiation.stackTargets", (g, a) -> Negotiation.stackTargets(g, a.integer(0, -1), a.str(1), a.integer(2, -1)));
        query("negotiation.freeSlots", (g, a) -> Negotiation.freeSlots(g, a.integer(0, -1)));
        query("negotiation.ownedSites", (g, a) -> Negotiation.ownedSites(g, a.integer(0, -1)));

        /* ---------------- state queries ---------------- */
        query("state.humansAt", (g, a) -> new ArrayList<>(StateOps.humansAt(g, a.str(0))));
        query("state.crewLocation", (g, a) -> StateOps.crewLocation(g, a.player(g, 0)));
        query("state.felonyAt", (g, a) -> StateOps.felonyAt(g, a.player(g, 0), a.str(1), a.bool(2)));
        query("state.hasPowersat", (g, a) -> StateOps.hasPowersat(g, a.player(g, 0)));
        query("state.counts", (g, a) -> StateOps.counts(g, a.integer(0, -1)));
        query("state.stockPrice", (g, a) -> StateOps.stockPrice(g, a.str(0)));
        query("state.factoryCount", (g, a) -> StateOps.factoryCount(g, a.str(0)));
        query("state.season", (g, a) -> StateOps.season(g));
        query("state.order", (g, a) -> StateOps.order(g));
        query("state.nextPlayer", (g, a) -> StateOps.nextPlayer(g));
        query("state.stacksAt", (g, a) -> StateOps.stacksAt(g, a.str(0)));
        internal("state.site", (g, a) -> StateOps.site(g, a.str(0)));
        internal("actions.decommission", (g, a) -> {
            JsonObject o = a.obj(3);
            return Actions.decommission(g, a.integer(0, -1), a.str(1), a.integer(2, -1),
                    Actions.Decom.of(Args.truthy(o.get("involuntary")), Args.truthy(o.get("ablate")), o.has("why") && !o.get("why").isJsonNull() ? o.get("why").getAsString() : null));
        });
    }
}
