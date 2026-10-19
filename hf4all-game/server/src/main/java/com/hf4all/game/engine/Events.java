package com.hf4all.game.engine;

import com.hf4all.game.data.CardDb;
import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.data.Rules;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Pending;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Score;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;

import java.util.ArrayList;
import java.util.List;

import static com.hf4all.game.engine.RuleError.check;
import static com.hf4all.game.engine.StateOps.STACK_NAME;

/**
 * Turn and year sequence (D), Sunspot Cycle events (K), heroism (L c) and
 * endgame scoring (M), ported from the former browser engine.
 */
public final class Events {

    private Events() {}

    private static GameMap map() { return GameData.get().map; }
    private static Rules rules() { return GameData.get().rules; }

    public static List<String> endTurnProblems(GameState g) {
        List<String> out = new ArrayList<>();
        if (g.move != null) out.add("Finish your movement first.");
        if (g.pending != null) out.add("Resolve the pending decision first.");
        return out;
    }

    public static void endTurn(GameState g) {
        List<String> probs = endTurnProblems(g);
        check(probs.isEmpty(), probs.isEmpty() ? null : probs.get(0));
        Player p = g.players.get(g.current);
        StateOps.cleanupStacks(g, p);
        Dice.log(g, p.name + " ends the turn.", null, "system");
        Integer next = StateOps.nextPlayer(g);
        g.rolls = new ArrayList<>();
        if (next != null) StateOps.beginTurn(g, next);
        else endYear(g);
    }

    /** D2: advance the Sunspot Cube; events, seniority, end of game. */
    static void endYear(GameState g) {
        int from = g.cyclePos;
        int to = (from + 1) % 12;
        String oldSeason = rules().seasonAt(from);
        g.cyclePos = to;
        String newSeason = rules().seasonAt(to);
        Dice.log(g, "The Sunspot Cube advances to space " + (to + 1) + " (season " + newSeason + ").", null, "system");
        if (!oldSeason.equals(newSeason)) {
            if (g.anarchy && !newSeason.equals("blue")) {
                g.anarchy = false;
                Dice.log(g, "Anarchy ends: faction privileges are restored.", null, "system");
            }
            if (oldSeason.equals("yellow")) kreutzMelts(g);
        }
        if (to == 0) {
            g.seniority--;
            Dice.log(g, "Seniority threshold crossed: " + g.seniority + " Seniority Disk" + (g.seniority == 1 ? "" : "s") + " left.", null, "system");
            if (g.seniority <= 0) { finish(g); return; }
        }
        g.year++;
        StateOps.beginTurn(g, g.firstPlayer);
        if (rules().cycle.get(to).event()) eventRoll(g);
        Dice.log(g, "Year " + g.year + " begins.", null, "system");
    }

    static void kreutzMelts(GameState g) {
        String id = map().idByName("Kreutz Sungrazer");
        if (id == null) return;
        for (int pi = 0; pi < g.players.size(); pi++) {
            Player p = g.players.get(pi);
            for (String k : StateOps.STACKS) {
                if (p.stacks.get(k) == null || !id.equals(StateOps.stackNode(p, k))) continue;
                for (int i = p.stacks.get(k).cards.size() - 1; i >= 0; i--) {
                    Actions.decommission(g, pi, k, i, Actions.Decom.of(true, false, "kreutz sungrazer"));
                }
                if (p.stacks.get(k) != null) p.stacks.get(k).fts = 0;
            }
            StateOps.cleanupStacks(g, p);
        }
        // Cards, cubes and FTs are decommissioned (glossary: Synodic Comet b); the Claim disk stays.
        Site site = g.sites.get(id);
        if (site != null && (site.factory || site.colony)) {
            Dice.log(g, "The kreutz sungrazer passes perihelion: its Factory is lost (the Claim stays).", null, "bad");
            site.factory = false;
            site.colony = false;
        }
    }

    /** K2 */
    static void eventRoll(GameState g) {
        int v = Dice.d6(g, "Event Roll", null);
        String season = StateOps.season(g);
        List<Integer> order = StateOps.order(g);
        if (v <= 2) {
            for (String d : CardDb.DECKS) {
                List<String> deck = g.decks.get(d);
                if (!deck.isEmpty()) deck.add(deck.remove(0));
            }
            Dice.log(g, "Event: Inspiration - the top card of every patent deck goes to the bottom.", null, "event");
            g.lastEvent = new GameState.EventInfo(v, "Inspiration", "The top card of each patent deck was put on the bottom.");
        } else if (v == 3) {
            List<String> hits = new ArrayList<>();
            List<Integer> choosers = new ArrayList<>();
            for (int pi : order) {
                Player p = g.players.get(pi);
                List<String> best = glitchCandidates(g, pi);
                if (best.size() == 1) {
                    p.stacks.get(best.get(0)).glitch = true;
                    hits.add(p.name + ": " + STACK_NAME.get(best.get(0)));
                } else if (best.size() > 1) choosers.add(pi);
            }
            List<String> tied = new ArrayList<>();
            for (int pi : choosers) tied.add(g.players.get(pi).name);
            String choose = tied.isEmpty() ? "" : String.join(", ", tied) + " choose" + (tied.size() == 1 ? "s" : "") + " among tied stacks";
            Dice.log(g, "Event: Glitch - " + (hits.isEmpty() && tied.isEmpty() ? "no eligible stacks"
                    : String.join(", ", hits) + (hits.isEmpty() || tied.isEmpty() ? "" : "; ") + choose) + ".", null, "event");
            g.lastEvent = new GameState.EventInfo(v, "Glitch", hits.isEmpty() && tied.isEmpty() ? "No stack was eligible."
                    : (hits.isEmpty() ? "" : "Glitched: " + String.join(", ", hits)) + (tied.isEmpty() ? "" : (hits.isEmpty() ? "" : ". ") + choose + "."));
            if (!choosers.isEmpty()) g.pending = Pending.glitchEvent(choosers, glitchCandidates(g, choosers.get(0)));
        } else if (v == 4) {
            List<String> hits = new ArrayList<>();
            List<Integer> choosers = new ArrayList<>();
            for (int pi : order) {
                List<String> best = padCandidates(g, pi);
                if (best.size() == 1) hits.add(g.players.get(pi).name + ": " + padDecommission(g, pi, best.get(0)));
                else if (best.size() > 1) choosers.add(pi);
            }
            List<String> tied = new ArrayList<>();
            for (int pi : choosers) tied.add(g.players.get(pi).name);
            String choose = tied.isEmpty() ? "" : String.join(", ", tied) + " choose" + (tied.size() == 1 ? "s" : "") + " among their heaviest cards";
            Dice.log(g, "Event: Pad Explosion / Space Debris - " + (hits.isEmpty() && tied.isEmpty() ? "nothing in LEO"
                    : String.join(", ", hits) + (hits.isEmpty() || tied.isEmpty() ? "" : "; ") + choose) + ".", null, "event");
            g.lastEvent = new GameState.EventInfo(v, "Pad Explosion", hits.isEmpty() && tied.isEmpty() ? "Nothing vulnerable in LEO."
                    : (hits.isEmpty() ? "" : "Lost: " + String.join(", ", hits)) + (tied.isEmpty() ? "" : (hits.isEmpty() ? "" : ". ") + choose + "."));
            if (!choosers.isEmpty()) g.pending = Pending.padExplosion(choosers, padCandidates(g, choosers.get(0)));
        } else if (season.equals("blue")) {
            g.anarchy = true;
            Dice.log(g, "Event: Anarchy - until season blue ends, every faction privilege is replaced by Felonious.", null, "event");
            g.lastEvent = new GameState.EventInfo(v, "Anarchy", "Until the cube leaves season blue all players are Felonious and lose their privileges.");
        } else if (season.equals("yellow")) {
            List<Integer> queue = new ArrayList<>();
            for (int pi : order) if (!g.players.get(pi).hand.isEmpty()) queue.add(pi);
            Dice.log(g, "Event: Budget Cuts - each player discards a Hand card.", null, "event");
            g.lastEvent = new GameState.EventInfo(v, "Budget Cuts", "Each player discards a Hand card of their choice.");
            if (!queue.isEmpty()) g.pending = Pending.budgetCuts(queue);
        } else {
            solarFlare(g, v);
        }
    }

    /**
     * K2b: the player's stacks with the most cards among those with neither a Glitch nor
     * Colocated Humans (a Crew in any stack on the Space, a Colony, LEO). Several = a tie
     * the player resolves.
     */
    public static List<String> glitchCandidates(GameState g, int pi) {
        Player p = g.players.get(pi);
        List<String> best = new ArrayList<>();
        int most = -1;
        for (String k : List.of("rocket", "out1", "out2")) {
            Stack s = p.stacks.get(k);
            if (s == null || s.glitch || !StateOps.humansAt(g, StateOps.stackNode(p, k)).isEmpty()) continue;
            if (s.cards.size() > most) { most = s.cards.size(); best.clear(); }
            if (s.cards.size() == most) best.add(k);
        }
        return best;
    }

    /** The first player of a Glitch event tie picks which of their tied stacks gets the Glitch. */
    public static void resolveGlitch(GameState g, String key) {
        Pending pd = g.pending;
        check(pd != null && "glitchEvent".equals(pd.type), "No Glitch choice pending.");
        Player p = g.players.get(pd.queue.get(0));
        check(pd.stacks != null && pd.stacks.contains(key), "Choose one of the tied stacks.");
        p.stacks.get(key).glitch = true;
        Dice.log(g, p.name + " places the Glitch on " + STACK_NAME.get(key) + " (K2b).", p.idx, null);
        pd.queue.remove(0);
        if (pd.queue.isEmpty()) g.pending = null;
        else pd.stacks = glitchCandidates(g, pd.queue.get(0));
    }

    /**
     * K2c: the player's vulnerable cards in LEO (any stack there; not Crew or Black-Side)
     * with the highest Mass, as "stack:index". Several = a tie the player resolves.
     */
    public static List<String> padCandidates(GameState g, int pi) {
        Player p = g.players.get(pi);
        List<String> best = new ArrayList<>();
        int most = -1;
        for (String k : StateOps.STACKS) {
            Stack s = p.stacks.get(k);
            if (s == null || !map().leo.equals(StateOps.stackNode(p, k))) continue;
            for (int i = 0; i < s.cards.size(); i++) {
                CardInst c = s.cards.get(i);
                if (c.crew != null || "b".equals(c.side)) continue;
                int m = Cards.massOf(c);
                if (m > most) { most = m; best.clear(); }
                if (m == most) best.add(k + ":" + i);
            }
        }
        return best;
    }

    private static String padDecommission(GameState g, int pi, String ref) {
        Player p = g.players.get(pi);
        String key = ref.substring(0, ref.indexOf(':'));
        int idx = Integer.parseInt(ref.substring(ref.indexOf(':') + 1));
        String name = Cards.stats(p.stacks.get(key).cards.get(idx)).name;
        Actions.decommission(g, pi, key, idx, Actions.Decom.of(true, false, "pad explosion"));
        StateOps.cleanupStacks(g, p);
        return name;
    }

    /** The first player of a pad explosion tie picks which of their heaviest LEO cards is lost (K2c). */
    public static void resolvePadExplosion(GameState g, String ref) {
        Pending pd = g.pending;
        check(pd != null && "padExplosion".equals(pd.type), "No pad explosion choice pending.");
        Player p = g.players.get(pd.queue.get(0));
        check(pd.cards != null && pd.cards.contains(ref), "Choose one of the tied cards.");
        String name = padDecommission(g, p.idx, ref);
        Dice.log(g, p.name + " loses " + name + " to the pad explosion (K2c).", p.idx, null);
        pd.queue.remove(0);
        if (pd.queue.isEmpty()) g.pending = null;
        else pd.cards = padCandidates(g, pd.queue.get(0));
    }

    public static void resolveBudgetCut(GameState g, int handIdx) {
        Pending pd = g.pending;
        check(pd != null && "budgetCuts".equals(pd.type), "No budget cuts pending.");
        Player p = g.players.get(pd.queue.get(0));
        check(handIdx >= 0 && handIdx < p.hand.size(), "Choose a Hand card.");
        String id = p.hand.remove(handIdx);
        g.decks.get(GameData.get().cards.deckOf(id)).add(id);
        Dice.log(g, p.name + " loses " + GameData.get().cards.def(id).w.name + " to budget cuts.", p.idx, null);
        pd.queue.remove(0);
        if (pd.queue.isEmpty()) g.pending = null;
    }

    static void solarFlare(GameState g, int eventRoll) {
        int sev = Dice.d6(g, "CME Roll (flare severity)", null);
        List<String> hits = new ArrayList<>();
        for (int pi = 0; pi < g.players.size(); pi++) {
            Player p = g.players.get(pi);
            for (String k : StateOps.STACKS) {
                Stack s = p.stacks.get(k);
                if (s == null) continue;
                String node = StateOps.stackNode(p, k);
                GameMap.Node N = map().node(node);
                // K2d shielding: Van Allen (LEO and the cycler Space), Bunker (Sites), magnetic field (planetary belts).
                if (node.equals(map().leo) || node.equals(rules().cycler) || N.t.equals("site") || map().isPlanetBelt(node)) continue;
                int eff = sev + rules().zone(N.z).flare();
                // An Ability works only while its card is Operational (J2e): a sail in the Neptune zone is not immune.
                Cards.Analysis an = Cards.analyze(s.cards, N.z);
                for (int i = s.cards.size() - 1; i >= 0; i--) {
                    CardStats st = Cards.stats(s.cards.get(i));
                    if (st.ab("flareImmune") && Cards.operational(an, i)) continue;
                    if (st.radOr0() < eff) {
                        hits.add(p.name + ": " + st.name);
                        Actions.decommission(g, pi, k, i, Actions.Decom.of(true, false, "solar flare"));
                    }
                }
            }
            StateOps.cleanupStacks(g, p);
        }
        Dice.log(g, "Event: Solar Flare severity " + sev + (hits.isEmpty() ? " - no damage" : " - lost: " + String.join(", ", hits)) + ".", null, "event");
        g.lastEvent = new GameState.EventInfo(eventRoll, "Solar Flare", "CME severity " + sev + " (modified by zone). " + (hits.isEmpty() ? "No damage." : "Lost: " + String.join(", ", hits)));
    }

    public static void awardHeroism(GameState g, String chit, int pi) {
        check(g.config.heroism, "Heroism chits are used only with 3+ players.");
        check(g.year < g.config.cycles * 12, "Heroism chits can only be awarded before the last year of the game (L c).");
        check(g.heroism.containsKey(chit) && g.heroism.get(chit) == null, "That heroism chit was already awarded.");
        check(pi >= 0 && pi < g.players.size(), "No such player.");
        g.heroism.put(chit, pi);
        g.players.get(pi).heroism.add(chit);
        Rules.Heroism h = rules().heroism(chit);
        Dice.log(g, "By vote, " + g.players.get(pi).name + " is awarded the heroism chit \"" + h.name() + "\" (2 VP).", pi, "good");
    }

    /** M2 endgame scoring (also the live VP preview). */
    public static Score score(GameState g, int pi) {
        Player p = g.players.get(pi);
        Score b = new Score();
        for (String id : StateOps.siteIds(g)) {
            Site s = g.sites.get(id);
            if (s.claim == null || s.claim != pi) continue;
            GameMap.Node N = map().node(id);
            b.claims++;
            if (s.factory) { b.factories++; b.stock += StateOps.stockPrice(g, N.spec); }
            if (s.colony) { b.colonies++; b.colonyBonus += N.sub ? 2 : (N.astro ? 1 : 0); }
        }
        if (p.stacks.rocket != null) b.rocket = 1;
        for (Player.Glory gl : p.glories) b.glory += gl.vp;
        // A glory chit still carried by the Crew counts its low side (wherever the Crew rides, N3d).
        StateOps.CrewLoc loc = StateOps.crewLocation(g, p);
        if (loc != null && g.players.get(loc.pi()).stacks.get(loc.key()).cards.get(loc.idx()).glory != null) b.glory += 1;
        b.heroism = 2 * p.heroism.size();
        b.tokens = b.claims + b.factories + b.colonies + b.rocket;
        b.total = b.tokens + b.stock + b.colonyBonus + b.glory + b.heroism;
        return b;
    }

    static void finish(GameState g) {
        List<Score> scores = new ArrayList<>();
        for (int i = 0; i < g.players.size(); i++) {
            Score s = score(g, i);
            s.player = i;
            s.aquas = g.players.get(i).aquas;
            scores.add(s);
        }
        List<Score> ranked = new ArrayList<>(scores);
        ranked.sort((a, b) -> b.total != a.total ? b.total - a.total : b.aquas - a.aquas);
        g.gameOver = new GameState.GameOver();
        g.gameOver.scores = scores;
        for (Score s : ranked) g.gameOver.ranking.add(s.player);
        Dice.log(g, "The last Seniority Disk is gone: the game ends. " + g.players.get(ranked.get(0).player).name + " wins with " + ranked.get(0).total + " VP!", null, "system");
    }
}
