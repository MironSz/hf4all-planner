package com.hf4all.game.engine;

import com.hf4all.game.TestSupport;
import com.hf4all.game.data.CardDb;
import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.LogEntry;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;
import com.hf4all.game.model.Turn;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The minor deviations of the rules review (10-20), one scenario each; see also RuleFixesTest. */
class MinorRuleFixesTest {

    private static final String RE_SOLAR_MOTH = "CT048";     // Solar-Powered, pushable, no supports, rad 5
    private static final String NANOBOT_B = "CB062:b";       // buggy ISRU 1, no supports, Black-Side spectral type C
    private static final String MICROPROBE_B = "CB067:b";    // raygun ISRU 3, Nanites re-roll
    private static final String AEROSOL_LASER_B = "CB063:b"; // raygun ISRU 2, needs 1 Therm
    private static final String LI_FOUNTAIN = "CD102";       // Heatsink Fountain: 5 Therms heavy, 1 light
    private static final String BUBBLE_MEMBRANE = "CD092";   // radiator: 2 Therms heavy
    private static final String NAK_LOOP = "CD093";          // radiator: mass 4 heavy, 2 light
    private static final String HALL_EFFECT = "CT045";       // mass 2
    private static final String MOLI_HEAT_PIPE = "CD098";    // mass 2

    private int uid = 7000;

    @BeforeAll
    static void data() { TestSupport.data(); }

    private static GameMap map() { return GameData.get().map; }

    private static GameState game() {
        List<CardDb.Crew> crews = GameData.get().cards.crews();
        return StateOps.newGame(11, 4, List.of(
                new StateOps.PlayerConfig("A", crews.get(0).id, 0),
                new StateOps.PlayerConfig("B", crews.get(1).id, 0),
                new StateOps.PlayerConfig("C", crews.get(2).id, 0)));
    }

    private CardInst card(String spec) {
        boolean black = spec.endsWith(":b");
        String id = spec.replace(":b", "");
        CardInst c = CardInst.patent(id, black ? "b" : "w", uid++);
        if ("radiator".equals(GameData.get().cards.deckOf(id))) c.orient = "heavy";
        return c;
    }

    private Stack stack(GameState g, int pi, String key, String node, String... cards) {
        Player p = g.players.get(pi);
        Stack s = Stack.at(node);
        for (String id : cards) s.cards.add(card(id));
        p.stacks.set(key, s);
        if (key.equals("rocket")) StateOps.syncRocketDry(p);
        return s;
    }

    /** An rng state whose next d6 rolls are {@code values} (0 = any). */
    private static long seedFor(int... values) {
        GameState probe = game();
        outer:
        for (long s = 1; s < 1_000_000; s++) {
            probe.rng = s;
            for (int v : values) {
                int r = Dice.d6(probe, "probe", null);
                if (v > 0 && r != v || v < 0 && r <= -v) continue outer;
            }
            return s;
        }
        throw new AssertionError("no seed");
    }

    private static boolean logged(GameState g, String text) {
        for (LogEntry e : g.log) if (e.text.contains(text)) return true;
        return false;
    }

    private static String nodeIn(String zone) {
        for (GameMap.Node n : map().nodes().values()) if (n.z.equals(zone) && n.t.equals("lagrange") && n.haz == null) return n.id;
        throw new AssertionError("no node in " + zone);
    }

    private static int prospectOption(GameState g, String site) {
        List<Operations.ProspectOpt> opts = Operations.prospectOptions(g);
        for (int i = 0; i < opts.size(); i++) if (opts.get(i).targets.contains(site)) return i;
        throw new AssertionError("no prospect option for " + site);
    }

    /* ---- 10. Solar-Powered cards in the Neptune zone work when pushed (-6) ---- */

    @Test
    void solarThrusterInNeptuneZoneNeedsAPushAndGetsMinusSix() {
        GameState g = game();
        Player p = g.players.get(g.current);
        p.privilege = "none";
        stack(g, g.current, "rocket", nodeIn("neptune"), RE_SOLAR_MOTH);
        Movement.Plan dark = Movement.plan(g, 0, false, null);
        assertFalse(dark.ok);
        assertTrue(dark.reason.contains("no solar power"), dark.reason);

        Movement.PlanOpts push = new Movement.PlanOpts();
        push.push = true;
        Movement.Plan lit = Movement.plan(g, 0, false, push);
        assertTrue(lit.ok, lit.reason);
        assertTrue(lit.parts.stream().anyMatch(pt -> pt.label.startsWith("Solar power") && pt.value == -6));
        Movement.start(g, 0, false, push);
        assertEquals(Boolean.TRUE, g.move.pushed, "a negotiated push is remembered for the whole move");
        assertTrue(Movement.thrusterAlive(g));

        GameState g2 = game();
        g2.players.get(g2.current).powersat = true;
        stack(g2, g2.current, "rocket", nodeIn("neptune"), RE_SOLAR_MOTH);
        assertTrue(Movement.plan(g2, 0, false, null).ok, "own Powersat Ability pushes too");
    }

    /* ---- 11. Powersat gained from a card survives Anarchy ---- */

    @Test
    void powersatFromACardSurvivesAnarchyButThePrivilegeDoesNot() {
        GameState g = game();
        g.anarchy = true;
        Player a = g.players.get(0), b = g.players.get(1);
        a.privilege = "none";
        a.powersat = true;
        b.privilege = "powersat";
        assertTrue(StateOps.hasPowersat(g, a));
        assertFalse(StateOps.hasPowersat(g, b));
    }

    /* ---- 12. Prospecting Luna without permission is a Felony ---- */

    @Test
    void prospectingLunaWithoutPermissionIsAFelonyNeedingYourHuman() {
        GameState g = game();
        g.current = (g.firstPlayer + 1) % g.players.size();
        g.turn = Turn.fresh();
        Player p = g.players.get(g.current);
        String luna = map().idByName("Luna: Shackleton polar rim");
        Stack r = stack(g, g.current, "rocket", luna, NANOBOT_B);

        p.privilege = "none";
        RuleError plain = assertThrows(RuleError.class, () -> Operations.prospect(g, prospectOption(g, luna), List.of(luna), false));
        assertEquals("Prospecting Luna requires the 1st Player's permission (I6.1).", plain.getMessage());

        p.privilege = "felonious";
        RuleError noHuman = assertThrows(RuleError.class, () -> Operations.prospect(g, prospectOption(g, luna), List.of(luna), false));
        assertTrue(noHuman.getMessage().contains("is a Felony"), noHuman.getMessage());

        r.cards.add(p.stacks.leo.cards.remove(0)); // the Crew lands with the robonaut
        StateOps.syncRocketDry(p);
        Operations.prospect(g, prospectOption(g, luna), List.of(luna), false);
        assertTrue(g.turn.felony);
        assertEquals(p.idx, g.sites.get(luna).claim);
        assertTrue(logged(g, "Felony"));
    }

    /* ---- 13. the kreutz sungrazer keeps its Claim disk ---- */

    @Test
    void kreutzSungrazerLosesItsFactoryButKeepsTheClaim() {
        GameState g = game();
        String kreutz = map().idByName("Kreutz Sungrazer");
        Site s = StateOps.site(g, kreutz);
        s.claim = 0;
        s.factory = true;
        Events.kreutzMelts(g);
        assertEquals(0, s.claim);
        assertFalse(s.factory);
    }

    /* ---- 14. Claim disks and Factory cubes: move a disk on success, discard as a free action ---- */

    private static List<String> otherSites(String except, int n) {
        List<String> out = new ArrayList<>();
        for (GameMap.Node node : map().sites()) if (!node.id.equals(except) && out.size() < n) out.add(node.id);
        return out;
    }

    @Test
    void aSuccessWithNoDiskLeftMovesADiskFromAnUnindustrializedClaim() {
        String luna = map().idByName("Luna: Shackleton polar rim");
        GameState g = game();
        Player p = g.players.get(g.current);
        stack(g, g.current, "rocket", luna, NANOBOT_B);
        List<String> claims = otherSites(luna, 9);
        for (String id : claims) StateOps.site(g, id).claim = p.idx;
        Operations.prospect(g, prospectOption(g, luna), List.of(luna), false, List.of(claims.get(3)), null);
        assertEquals(p.idx, g.sites.get(luna).claim);
        assertNull(g.sites.get(claims.get(3)).claim);
        assertEquals(9, StateOps.counts(g, p.idx).claims());

        GameState g2 = game();
        Player p2 = g2.players.get(g2.current);
        stack(g2, g2.current, "rocket", luna, NANOBOT_B);
        for (String id : claims) StateOps.site(g2, id).claim = p2.idx;
        Operations.prospect(g2, prospectOption(g2, luna), List.of(luna), false);
        assertNull(StateOps.site(g2, luna).claim);
        assertTrue(logged(g2, "success wasted"));
    }

    @Test
    void factoryCubeAndClaimDiskCanBeDiscardedButNotAColony() {
        GameState g = game();
        Player p = g.players.get(g.current);
        List<String> sites = otherSites("", 2);
        Site a = StateOps.site(g, sites.get(0)), b = StateOps.site(g, sites.get(1));
        a.claim = p.idx;
        a.factory = true;
        b.claim = p.idx;
        b.factory = true;
        b.colony = true;
        assertThrows(RuleError.class, () -> Actions.discardClaim(g, sites.get(0)));
        Actions.discardFactory(g, sites.get(0));
        assertFalse(a.factory);
        Actions.discardClaim(g, sites.get(0));
        assertNull(a.claim);
        RuleError colony = assertThrows(RuleError.class, () -> Actions.discardFactory(g, sites.get(1)));
        assertTrue(colony.getMessage().contains("permanent"));
    }

    /* ---- 15. a raygun does not prospect an Atmospheric Site at range ---- */

    @Test
    void raygunOnArsiaMonsCannotProspectTheOtherMarsSitesAtRange() {
        GameState g = game();
        String arsia = map().idByName("Mars: Arsia Mons caves");
        stack(g, g.current, "out1", arsia, MICROPROBE_B);
        List<String> targets = Operations.prospectOptions(g).get(0).targets;
        assertTrue(targets.contains(arsia), "range zero is fine");
        assertFalse(targets.contains(map().idByName("Mars: north pole")), "atmospheric, at range");
        assertFalse(targets.contains(map().idByName("Mars: Hellas Basin buried glaciers")), "atmospheric, at range");
        assertTrue(map().adjacentSites(arsia).contains(map().idByName("Mars: north pole")), "the test needs them Adjacent");
    }

    /* ---- 16. choices that belong to the player ---- */

    @Test
    void padExplosionTieIsResolvedByThePlayer() {
        GameState g = game();
        Player a = g.players.get(0);
        a.stacks.leo.cards.add(card(HALL_EFFECT));
        a.stacks.leo.cards.add(card(MOLI_HEAT_PIPE));
        g.rng = seedFor(4);
        Events.eventRoll(g);
        assertNotNull(g.pending);
        assertEquals("padExplosion", g.pending.type);
        assertEquals(List.of(0), g.pending.queue);
        assertEquals(List.of("leo:1", "leo:2"), g.pending.cards);
        Events.resolvePadExplosion(g, "leo:2");
        assertNull(g.pending);
        assertTrue(a.hand.contains(MOLI_HEAT_PIPE));
        assertEquals(2, a.stacks.leo.cards.size());
    }

    @Test
    void nanitesReRollGoesToTheFailedSiteThePlayerPutFirst() {
        String[] sites = {map().idByName("Menoetius"), map().idByName("Patroclus")};
        for (int pick = 0; pick < 2; pick++) {
            GameState g = game();
            Player p = g.players.get(g.current);
            p.privilege = "none";
            stack(g, g.current, "out1", "497", MICROPROBE_B);
            int opt = prospectOption(g, sites[0]);
            assertTrue(Operations.prospectOptions(g).get(opt).targets.contains(sites[1]));
            assertEquals(Boolean.TRUE, Operations.prospectOptions(g).get(opt).reroll);
            g.rng = seedFor(-2, -2); // both Size 2 rolls fail
            Operations.prospect(g, opt, List.of(sites[0], sites[1]), false, null, List.of(sites[pick]));
            assertTrue(logged(g, "Nanites re-roll " + map().name(sites[pick])), "re-roll on " + map().name(sites[pick]));
            assertFalse(logged(g, "Nanites re-roll " + map().name(sites[1 - pick])));
        }
    }

    /* ---- 17. heroism only before the last year ---- */

    @Test
    void heroismCannotBeAwardedInTheLastYear() {
        GameState g = game();
        String chit = GameData.get().rules.heroism.get(0).id();
        g.year = g.config.cycles * 12;
        assertThrows(RuleError.class, () -> Events.awardHeroism(g, chit, 1));
        g.year = g.config.cycles * 12 - 1;
        Events.awardHeroism(g, chit, 1);
        assertEquals(1, g.heroism.get(chit));
    }

    /* ---- 18. Black-Side cards sell from any stack in the Bank ---- */

    @Test
    void blackSideCardSellsFromARocketParkedInLeo() {
        GameState g = game();
        Player p = g.players.get(g.current);
        int aquas = p.aquas;
        Stack r = stack(g, g.current, "rocket", map().leo, RE_SOLAR_MOTH, NANOBOT_B);
        int price = StateOps.stockPrice(g, "C");
        assertThrows(RuleError.class, () -> Operations.sellBlack(g, "rocket", 0), "White-Side");
        Operations.sellBlack(g, "rocket", 1);
        assertEquals(aquas + price, p.aquas);
        assertEquals(1, r.cards.size());
        assertTrue(p.hand.contains("CB062"));

        GameState g2 = game();
        stack(g2, g2.current, "rocket", "901", RE_SOLAR_MOTH, NANOBOT_B);
        RuleError away = assertThrows(RuleError.class, () -> Operations.sellBlack(g2, "rocket", 1));
        assertTrue(away.getMessage().contains("Bank"), away.getMessage());
    }

    /* ---- 19. ET production into a glitched Rocket is a cargo transfer (Glitch Trigger) ---- */

    @Test
    void etProductionIntoAGlitchedRocketRollsForTheGlitch() {
        GameState g = game();
        Player p = g.players.get(g.current);
        String site = null;
        for (GameMap.Node n : map().sites()) if ("C".equals(n.spec) && n.syn == null) { site = n.id; break; }
        Site s = StateOps.site(g, site);
        s.claim = p.idx;
        s.factory = true;
        Stack r = stack(g, g.current, "rocket", site, RE_SOLAR_MOTH);
        r.glitch = true;
        p.hand.clear();
        p.hand.add("CB062");
        g.rng = seedFor(6); // matches no card: nothing is lost
        Operations.etProduce(g, site, 0, "rocket", null, null);
        assertTrue(logged(g, "Glitch Roll (cargo transfer)"));
        assertEquals(2, r.cards.size());
        assertTrue(r.glitch);
    }

    /* ---- 20. Phileas Fogg with a heavy radiator; Heatsink Fountains in Operations ---- */

    @Test
    void foggRotatesAHeavyRadiatorIntoDirtFuel() {
        GameState g = game();
        Stack r = stack(g, g.current, "rocket", map().leo, RE_SOLAR_MOTH, NAK_LOOP);
        FuelTrack.addFuel(r.fuel, 3, "water");
        assertThrows(RuleError.class, () -> Actions.radiatorToLight(g, "rocket", 1, true), "needs a gray chit");
        r.fuel.grade = "dirt";
        int wet = r.fuel.wet;
        assertEquals(4, r.fuel.dry);
        Actions.radiatorToLight(g, "rocket", 1, true);
        assertEquals(2, r.fuel.dry);
        assertEquals(wet, r.fuel.wet, "the Wet chit stays: the shed mass is now Fuel");
        assertTrue(r.cards.get(1).isLight());
    }

    @Test
    void heatsinkFountainFlipsOnlyWhenItsThermIsNeededInAnOperation() {
        String arsia = map().idByName("Mars: Arsia Mons caves");
        for (boolean helper : new boolean[]{false, true}) {
            GameState g = game();
            Stack s = helper ? stack(g, g.current, "out1", arsia, AEROSOL_LASER_B, LI_FOUNTAIN, BUBBLE_MEMBRANE)
                    : stack(g, g.current, "out1", arsia, AEROSOL_LASER_B, LI_FOUNTAIN);
            List<Operations.RefuelOpt> opts = Operations.refuelOptions(g);
            int isru = -1;
            for (int i = 0; i < opts.size(); i++) if ("isru".equals(opts.get(i).kind)) isru = i;
            assertTrue(isru >= 0);
            Operations.refuel(g, isru, "out1", false, null);
            assertEquals(helper ? 2 : 2, s.ftsOr0(), "1 + hydration 3 - ISRU 2");
            assertEquals(!helper, s.cards.get(1).isLight(), helper ? "the membrane cools the laser" : "the fountain had to cool the laser");
        }
    }
}
