package com.hf4all.game.api;

import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.engine.Actions;
import com.hf4all.game.engine.Cards;
import com.hf4all.game.engine.Events;
import com.hf4all.game.engine.FuelTrack;
import com.hf4all.game.engine.Movement;
import com.hf4all.game.engine.Negotiation;
import com.hf4all.game.engine.Operations;
import com.hf4all.game.engine.StateOps;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Score;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the client needs to render a position that comes from the
 * rules: scores, card statuses, which Operations and free actions are
 * possible (with the reason when not), thrust options, move options. The
 * client stays a renderer; the server decides.
 */
public final class ViewModel {

    public record Avail(boolean ok, String why) {}

    public record StackView(List<String> status, List<Boolean> ok, int therms, int mass, String node) {}

    public List<Score> scores = new ArrayList<>();
    /** Per player: stack key -> card statuses. */
    public List<Map<String, StackView>> stacks = new ArrayList<>();
    public List<Boolean> powersat = new ArrayList<>();
    public Map<String, Avail> ops = new LinkedHashMap<>();
    public Map<String, Avail> free = new LinkedHashMap<>();
    public List<String> endTurnProblems;
    public Integer nextPlayer;
    public List<Integer> order;
    public String season;
    public Map<String, Integer> stockPrices = new LinkedHashMap<>();
    public StateOps.Check canTrade;
    public List<Movement.Plan> thrustOptions;
    /** The current player's engines (built ones and supportless cards), with activation previews. */
    public List<Movement.EngineView> engines;
    /** Fuel steps to jettison for the next lighter weight class (+1 net thrust), or null. */
    public Integer jettisonToLighterClass;
    public List<Movement.MoveOption> moveOptions;
    public StateOps.Check canEnd;
    public Boolean thrusterAlive;
    public List<Operations.RefuelOpt> refuelOptions;
    public List<Operations.ProspectOpt> prospectOptions;
    /** Per prospect option: target Site -> size-roll modifier, hydration, Luna (needs permission). */
    public List<Map<String, ProspectTarget>> prospectTargets = new ArrayList<>();

    public record ProspectTarget(int mod, int water, boolean luna) {}
    public List<Operations.IndustrializeOpt> industrializeOptions;
    public List<Operations.MineRevivalOpt> mineRevivalOptions;
    public List<Operations.EtOpt> etOptions;
    public List<Operations.DeliveryOpt> deliveryOptions;
    public Actions.DirtInfo dirtRefuel;
    public List<Actions.MurderOpt> murderOptions;
    public Map<String, Integer> blackSalePrices = new LinkedHashMap<>();

    public static ViewModel of(GameState g) {
        GameMap map = GameData.get().map;
        ViewModel v = new ViewModel();
        for (int i = 0; i < g.players.size(); i++) {
            v.scores.add(Events.score(g, i));
            Player p = g.players.get(i);
            Map<String, StackView> sv = new LinkedHashMap<>();
            for (String k : StateOps.STACKS) {
                Stack s = p.stacks.get(k);
                if (s == null) continue;
                String node = StateOps.stackNode(p, k);
                Cards.Analysis an = Cards.analyze(s.cards, map.zoneOf(node));
                List<String> status = new ArrayList<>();
                List<Boolean> ok = new ArrayList<>();
                for (int c = 0; c < s.cards.size(); c++) {
                    String st = Cards.statusText(an, c);
                    status.add(st);
                    ok.add(st.equals("Operational"));
                }
                sv.put(k, new StackView(status, ok, an.therms, StateOps.cargoMass(p, k), node));
            }
            v.stacks.add(sv);
            v.powersat.add(StateOps.hasPowersat(g, p));
        }
        v.endTurnProblems = Events.endTurnProblems(g);
        v.nextPlayer = StateOps.nextPlayer(g);
        v.order = StateOps.order(g);
        v.season = StateOps.season(g);
        for (String spec : GameData.get().rules.spectralTypes.keySet()) v.stockPrices.put(spec, StateOps.stockPrice(g, spec));
        v.canTrade = Negotiation.canTrade(g);
        if (g.gameOver != null) return v;

        Player p = g.players.get(g.current);
        if (g.move != null) {
            v.moveOptions = Movement.options(g);
            v.canEnd = Movement.canEnd(g);
            v.thrusterAlive = Movement.thrusterAlive(g);
        } else if (p.stacks.rocket != null) {
            v.thrustOptions = Movement.thrustOptions(g, null);
            v.engines = Movement.engines(g);
            v.jettisonToLighterClass = FuelTrack.jettisonToLighterClass(p.stacks.rocket.fuel);
        }
        v.refuelOptions = Operations.refuelOptions(g);
        v.prospectOptions = Operations.prospectOptions(g);
        for (Operations.ProspectOpt o : v.prospectOptions) {
            Map<String, ProspectTarget> info = new LinkedHashMap<>();
            for (String id : o.targets) {
                info.put(id, new ProspectTarget(Operations.sizeRollMod(g, p, o.node, id, o.raygun), Operations.siteWater(g, p, id), Operations.isLuna(id)));
            }
            v.prospectTargets.add(info);
        }
        v.industrializeOptions = Operations.industrializeOptions(g);
        v.mineRevivalOptions = Operations.mineRevivalOptions(g);
        v.etOptions = Operations.etOptions(g);
        v.deliveryOptions = Operations.deliveryOptions(g);
        v.dirtRefuel = Actions.dirtRefuelInfo(g);
        v.murderOptions = Actions.murderOptions(g);
        // I3b: Black-Side cards in any of the player's stacks in the Bank (LEO), keyed "stack:index".
        for (String k : StateOps.ownStackKeysAt(p, map.leo)) {
            List<CardInst> cards = p.stacks.get(k).cards;
            for (int i = 0; i < cards.size(); i++) {
                Integer price = Operations.blackSalePrice(g, cards.get(i));
                if (price != null) v.blackSalePrices.put(k + ":" + i, price);
            }
        }
        v.opAvailability(g, p);
        v.freeActions(g, p, map);
        return v;
    }

    /** The nine Operations with the reason when one is not possible (I1-I9). */
    private void opAvailability(GameState g, Player p) {
        String busy = g.move != null ? "Not during movement." : (g.pending != null ? "Resolve the pending decision." : null);
        if (busy == null && g.turn.op != null) busy = "You already performed an Operation this turn (D1b).";
        op("income", true, null, busy);
        op("auction", Operations.canBid(g, p.idx), "Academia hand limit: discard down to 3 cards first.", busy);
        op("freeMarket", !p.hand.isEmpty() || !blackSalePrices.isEmpty(), "Nothing to sell (Hand empty, no Black-Side card in LEO).", busy);
        op("boost", !p.hand.isEmpty(), "No cards in Hand.", busy);
        op("refuel", !refuelOptions.isEmpty(), "Needs an Operational ISRU card on a Site with enough Hydration, a Factory (yours, or an opponent’s with permission or a hijack), or an Air-Eater on an Aerobrake.", busy);
        op("prospect", !prospectOptions.isEmpty(), "Needs an Operational ISRU card on (or a raygun next to) an unclaimed Site with Hydration ≥ ISRU.", busy);
        op("industrialize", !industrializeOptions.isEmpty(), "Needs an Operational robonaut + refinery on your unindustrialized Claim.", busy);
        op("etProduction", etOptions.stream().anyMatch(o -> !o.cards.isEmpty()), "Needs a Factory (yours, or an opponent’s with permission or a hijack) and a Hand card of its spectral type.", busy);
        op("delivery", deliveryOptions.stream().anyMatch(o -> o.affordable), "Needs a Black-Side card and enough FTs in a stack at a Factory.", busy);
        // A card Operation is offered only while the player has that card in play.
        if (hasAbility(p, "mineRevival")) op("mineRevival", !mineRevivalOptions.isEmpty(), "Needs an Operational Termite Nest on a Busted Site of Size 2+.", busy);
    }

    private static boolean hasAbility(Player p, String key) {
        for (String k : StateOps.STACKS) {
            Stack s = p.stacks.get(k);
            if (s == null) continue;
            for (CardInst c : s.cards) if (Cards.stats(c).ab(key)) return true;
        }
        return false;
    }

    private void op(String id, boolean ok, String why, String busy) {
        ops.put(id, new Avail(ok && busy == null, busy != null ? busy : (ok ? null : why)));
    }

    /** Contextual free actions (G) and why they are not possible. */
    private void freeActions(GameState g, Player p, GameMap map) {
        free.put("dirtRefuel", new Avail(dirtRefuel.ok, dirtRefuel.reason));
        if (StateOps.privilegeActive(g, p, "mooncable")) {
            boolean ok = !g.turn.mooncable && p.stacks.rocket != null && map.leo.equals(p.stacks.rocket.node);
            free.put("mooncable", new Avail(ok, "Rocket must be in LEO; once per turn."));
        }
        StateOps.CrewLoc crew = StateOps.crewLocation(g, p);
        String zone = crew != null ? map.zoneOf(crew.node()) : null;
        boolean gloryOk = crew != null && crew.node().equals(g.turn.gloryEligible) && Boolean.TRUE.equals(g.glory.get(zone));
        free.put("loadGlory", new Avail(gloryOk, "Land your Crew on a Site no Human visited, in a zone whose glory is still on the map."));
        Site site = crew != null ? g.sites.get(crew.node()) : null;
        boolean colonyOk = site != null && site.factory && site.claim != null && site.claim == p.idx && !site.colony;
        free.put("buildColony", new Avail(colonyOk, "Your Crew must be at your Factory without a Colony."));
        if (StateOps.canFelony(g, p)) {
            boolean cj = site != null && site.claim != null && site.claim != p.idx && !site.factory;
            free.put("claimJump", new Avail(cj, "Your Crew must be on an opponent's unindustrialized Claim."));
            boolean mo = murderOptions.stream().anyMatch(o -> o.ok);
            free.put("murder", new Avail(mo && !g.turn.murder, g.turn.murder ? "Already used this Turn." : "No Crew outside LEO you could decommission."));
        }
        for (String k : StateOps.STACKS) {
            Stack s = p.stacks.get(k);
            if (s != null && s.glitch) free.put("glitchRepair:" + k, new Avail(true, null));
        }
        if (hasAbility(p, "jellybots")) {
            boolean jb = g.move == null && g.pending == null && industrializeOptions.stream().anyMatch(o -> Boolean.TRUE.equals(o.free));
            free.put("jellybots", new Avail(jb, "Needs an Operational Solid Flame and an Operational robonaut + refinery on your unindustrialized Claim on that Space."));
        }
    }
}
