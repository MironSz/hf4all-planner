package com.hf4all.game.engine;

import com.hf4all.game.TestSupport;
import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.LogEntry;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Card Abilities from the review of every Ability against the rulebook, one scenario each. */
class CardAbilitiesTest {

    private static final String TERMITE_NEST = "CF078:b";   // refinery, MINE REVIVAL; needs a stationary/burst/exotic reactor
    private static final String SOLID_FLAME = "CF079:b";    // refinery, JELLYBOTS; needs an exotic reactor
    private static final String ULTRACOLD = "CX083:b";      // reactor: exotic + burst; SCOOP -2 ISRU at Aerostat Sites
    private static final String PAUL_TRAP = "CX083";        // reactor: exotic, rad 2
    private static final String NANOBOT_B = "CB062:b";      // robonaut, buggy ISRU 1, no supports
    private static final String MAGBEAM_B = "CB061:b";      // robonaut, raygun+missile ISRU 3, Solar-Powered
    private static final String FLUIDIZED_BED = "CF071";    // refinery, needs an electric generator
    private static final String ATMO_SCOOP = "CF071:b";     // refinery, SCOOP Hydration 2; needs an electric generator
    private static final String MAGNETOSHELL = "CG116";     // electric generator; "cannot be used during industrialization"
    private static final String RAINBOW_CORRAL = "CG116:b"; // electric generator, no restriction
    private static final String SCAVENGER = "CF076:b";      // Femtochemistry, SCAVENGING; no supports
    private static final String SANTA_CLAUS = "CF073:b";    // refinery, DIVINING NUBOTS (-1 ISRU)
    private static final String PHOTON_KITE = "CT049";      // sail: immune to flares and belts; Solar-Powered, rad 0
    private static final String ABLATIVE_PLATE = "CT052";   // thruster 2, needs a burst or exotic reactor
    private static final String KUCK_MOSQUITO = "CB060";    // thruster, needs a burst reactor
    private static final String VALKYRIE = "CX081:b";       // burst reactor; needs an exotic reactor and 3 Therms
    private static final String HULA_HOOP = "CD093:b";      // radiator: 3 Therms heavy
    private static final String MAGNETOCALORIC = "CD099";   // radiator: 4 Therms heavy, needs an electric generator
    private static final String SOLAR_STIRLING = "CG105";   // electric generator, needs 1 Therm

    private int uid = 8000;

    @BeforeAll
    static void data() { TestSupport.data(); }

    private static GameMap map() { return GameData.get().map; }

    /** Crews: B612 Foundation (CY, face 1: Rad-Hard 3), NASA (CW 0), Taikonauts (CP 0). */
    private static GameState game() {
        return StateOps.newGame(11, 4, List.of(
                new StateOps.PlayerConfig("A", "CY", 1),
                new StateOps.PlayerConfig("B", "CW", 0),
                new StateOps.PlayerConfig("C", "CP", 0)));
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

    private static String site(Predicate<GameMap.Node> f) {
        for (GameMap.Node n : map().nodes().values()) if (n.isSite() && f.test(n)) return n.id;
        throw new AssertionError("no such Site");
    }

    private static String nodeIn(String zone) {
        for (GameMap.Node n : map().nodes().values()) if (n.z.equals(zone) && n.t.equals("lagrange") && n.haz == null) return n.id;
        throw new AssertionError("no node in " + zone);
    }

    private static boolean outerSite(GameMap.Node n) {
        return !List.of("mercury", "venus", "earth", "neptune").contains(n.z) && !Operations.isLuna(n.id);
    }

    /** An rng state whose next d6 rolls are {@code values}. */
    private static long seedFor(int... values) {
        GameState probe = game();
        outer:
        for (long s = 1; s < 1_000_000; s++) {
            probe.rng = s;
            for (int v : values) if (Dice.d6(probe, "probe", null) != v) continue outer;
            return s;
        }
        throw new AssertionError("no seed");
    }

    private static boolean logged(GameState g, String text) {
        for (LogEntry e : g.log) if (e.text.contains(text)) return true;
        return false;
    }

    /* ---- MINE REVIVAL (Termite Nest) ---- */

    @Test
    void termiteNestRevivesABustedMineAsTheOperation() {
        GameState g = game();
        Player p = g.players.get(g.current);
        String node = site(n -> n.size >= 2 && outerSite(n));
        StateOps.site(g, node).busted = true;
        stack(g, g.current, "out1", node, TERMITE_NEST, ULTRACOLD);

        assertEquals(1, Operations.mineRevivalOptions(g).size());
        Operations.mineRevival(g, 0, null);
        Site s = g.sites.get(node);
        assertFalse(s.busted);
        assertEquals(p.idx, s.claim);
        assertEquals("mineRevival", g.turn.op, "Mine Revival is the Turn's Operation");
        assertTrue(logged(g, "MINE REVIVAL"));
    }

    @Test
    void mineRevivalNeedsAnOperationalTermiteNestOnABustedSiteOfSizeTwoOrMore() {
        GameState g = game();
        String node = site(n -> n.size >= 2 && outerSite(n));
        StateOps.site(g, node).busted = true;
        stack(g, g.current, "out1", node, TERMITE_NEST);
        assertTrue(Operations.mineRevivalOptions(g).isEmpty(), "no reactor: not Operational");
        assertThrows(RuleError.class, () -> Operations.mineRevival(g, 0, null));

        GameState g2 = game();
        String small = site(n -> n.size < 2 && outerSite(n));
        StateOps.site(g2, small).busted = true;
        stack(g2, g2.current, "out1", small, TERMITE_NEST, ULTRACOLD);
        assertTrue(Operations.mineRevivalOptions(g2).isEmpty(), "Size 1 Sites cannot be revived");

        GameState g3 = game();
        stack(g3, g3.current, "out1", node, TERMITE_NEST, ULTRACOLD);
        assertTrue(Operations.mineRevivalOptions(g3).isEmpty(), "the Site is not busted");
    }

    /* ---- JELLYBOTS (Solid Flame) ---- */

    @Test
    void solidFlameIndustrializesAsAFreeActionEvenAfterTheOperation() {
        GameState g = game();
        Player p = g.players.get(g.current);
        String node = site(CardAbilitiesTest::outerSite);
        StateOps.site(g, node).claim = p.idx;
        stack(g, g.current, "out1", node, NANOBOT_B, SOLID_FLAME, ULTRACOLD);
        Operations.income(g);
        assertEquals("income", g.turn.op);

        List<Operations.IndustrializeOpt> opts = Operations.industrializeOptions(g);
        assertEquals(1, opts.size());
        assertEquals(Boolean.TRUE, opts.get(0).free);
        Operations.industrialize(g, 0, opts.get(0).robos.get(0), opts.get(0).refs.get(0));
        assertTrue(g.sites.get(node).factory);
        assertEquals("income", g.turn.op, "JELLYBOTS: industrialization does not use the Operation");
        assertTrue(logged(g, "JELLYBOTS"));
    }

    @Test
    void industrializationWithoutJellybotsIsTheOperation() {
        GameState g = game();
        Player p = g.players.get(g.current);
        String node = site(CardAbilitiesTest::outerSite);
        StateOps.site(g, node).claim = p.idx;
        stack(g, g.current, "out1", node, NANOBOT_B, FLUIDIZED_BED, RAINBOW_CORRAL);
        Operations.IndustrializeOpt o = Operations.industrializeOptions(g).get(0);
        assertNull(o.free);
        Operations.industrialize(g, 0, o.robos.get(0), o.refs.get(0));
        assertEquals("industrialize", g.turn.op);
    }

    /* ---- Magnetoshell Plasma Parachute: not during industrialization ---- */

    @Test
    void magnetoshellCannotSupportIndustrialization() {
        GameState g = game();
        Player p = g.players.get(g.current);
        String node = site(CardAbilitiesTest::outerSite);
        StateOps.site(g, node).claim = p.idx;
        Stack s = stack(g, g.current, "out1", node, NANOBOT_B, FLUIDIZED_BED, MAGNETOSHELL);
        assertTrue(Cards.analyze(s.cards, map().zoneOf(node)).supported[1], "it supports the refinery otherwise");
        assertTrue(Operations.industrializeOptions(g).isEmpty());

        GameState g2 = game();
        StateOps.site(g2, node).claim = g2.current;
        stack(g2, g2.current, "out1", node, NANOBOT_B, FLUIDIZED_BED, RAINBOW_CORRAL);
        assertEquals(1, Operations.industrializeOptions(g2).size(), "its Black-Side has no such restriction");
    }

    /* ---- Flare and Belt immunity only while Operational (J2e) ---- */

    @Test
    void sailIsImmuneToFlares() {
        GameState g = game();
        int pi = g.current;
        stack(g, pi, "out1", nodeIn("jupiter"), PHOTON_KITE, PAUL_TRAP);
        g.rng = seedFor(6);
        Events.solarFlare(g, 0);
        List<CardInst> left = g.players.get(pi).stacks.get("out1").cards;
        assertEquals(1, left.size(), "severity 6 - 3 hits the Rad-Hard 2 reactor");
        assertEquals("CT049", left.get(0).id);
    }

    @Test
    void sailIsImmuneToBeltRollsOnlyWhileOperational() {
        assertEquals(Boolean.TRUE, kiteSurvivesBelt("jupiter"), "an Operational sail is immune");
        // Solar-Powered cards are not Operational in the Neptune zone (unless pushed): no immunity.
        assertEquals(Boolean.FALSE, kiteSurvivesBelt("neptune"));
    }

    /** Enters a radiation belt of the zone with a weak non-solar thruster and a Photon Kite Sail as cargo. */
    private Boolean kiteSurvivesBelt(String zone) {
        for (GameMap.Node b : map().nodes().values()) {
            if (!b.t.equals("radhaz") || !b.z.equals(zone)) continue;
            for (String x : map().adj(b.id)) {
                GameMap.Node X = map().node(x);
                if (X.haz != null || X.isSite() || X.t.equals("radhaz") || !X.z.equals(zone)) continue;
                GameState g = game();
                Player p = g.players.get(g.current);
                p.privilege = "none";
                Stack r = stack(g, g.current, "rocket", x, ABLATIVE_PLATE, ULTRACOLD, PHOTON_KITE);
                FuelTrack.addFuel(r.fuel, 10, "water");
                if (!Movement.plan(g, 0, false, null).ok) continue;
                Movement.start(g, 0, false, null);
                Movement.MoveOption o = Movement.options(g).stream().filter(m -> m.to.equals(b.id)).findFirst().orElse(null);
                if (o == null || !o.legal) continue;
                assertTrue(6 - g.move.net > 0, "the Belt Roll must be able to hurt");
                g.rng = seedFor(6);
                Movement.step(g, b.id, new Movement.StepChoices());
                assertTrue(logged(g, "Belt Roll severity"));
                return p.stacks.rocket != null && p.stacks.rocket.cards.stream().anyMatch(c -> "CT049".equals(c.id));
            }
        }
        return null;
    }

    /* ---- ISRU modifiers of Colocated cards ---- */

    @Test
    void divingNubotsInAnotherStackOnTheSpaceLowerTheIsru() {
        GameState g = game();
        Player p = g.players.get(g.current);
        p.privilege = "none";
        String node = site(n -> outerSite(n) && !Operations.isAerostat(n.id));
        Stack a = stack(g, g.current, "out1", node, MAGBEAM_B);
        Cards.Analysis an = Cards.analyze(a.cards, map().zoneOf(node));
        assertEquals(3, Operations.isruAt(g, p, node, an, 0).r);
        stack(g, g.current, "out2", node, SANTA_CLAUS);
        assertEquals(2, Operations.isruAt(g, p, node, an, 0).r);
    }

    @Test
    void scoopInAnotherStackLowersTheIsruAtAnAerostatSite() {
        GameState g = game();
        Player p = g.players.get(g.current);
        p.privilege = "none";
        String node = site(n -> Operations.isAerostat(n.id) && !n.z.equals("neptune"));
        Stack a = stack(g, g.current, "out1", node, MAGBEAM_B);
        Cards.Analysis an = Cards.analyze(a.cards, map().zoneOf(node));
        stack(g, g.current, "out2", node, ULTRACOLD);
        assertEquals(1, Operations.isruAt(g, p, node, an, 0).r);
    }

    /* ---- SCAVENGING (Femtochemistry): doubles every Site refuel ---- */

    @Test
    void scavengingDoublesIsruAndFactoryRefuel() {
        GameState g = game();
        Player p = g.players.get(g.current);
        p.privilege = "none";
        String node = site(n -> outerSite(n) && !Operations.isAerostat(n.id) && n.water >= 1);
        Site s = StateOps.site(g, node);
        s.claim = p.idx;
        s.factory = true;
        stack(g, g.current, "out1", node, NANOBOT_B);
        int isru = refuelFts(g, "isru"), factory = refuelFts(g, "factory");
        assertEquals(7, factory);
        stack(g, g.current, "out2", node, SCAVENGER);
        assertEquals(2 * isru, refuelFts(g, "isru"));
        assertEquals(14, refuelFts(g, "factory"), "a Factory refuel is a Site refuel too");
    }

    private static int refuelFts(GameState g, String kind) {
        return Operations.refuelOptions(g).stream().filter(o -> o.kind.equals(kind) && "out1".equals(o.key)).findFirst().orElseThrow().fts;
    }

    /* ---- SCOOP (Atmospheric Scoop): Adjacent Aerostat Sites get Hydration 2 ---- */

    @Test
    void atmosphericScoopWetsAnAerostatSiteAdjacentToItsSpace() {
        String aero = null, from = null;
        search:
        for (GameMap.Node s : map().nodes().values()) {
            if (!s.isSite() || !Operations.isAerostat(s.id) || s.water >= 2 || s.z.equals("neptune")) continue;
            for (GameMap.Node x : map().nodes().values()) {
                if (!x.id.equals(s.id) && !x.z.equals("neptune") && map().adjacentSites(x.id).contains(s.id)) { aero = s.id; from = x.id; break search; }
            }
        }
        assertTrue(aero != null, "an Aerostat Site with Hydration < 2 next to a Space");
        GameState g = game();
        Player p = g.players.get(g.current);
        int dry = Operations.siteWater(g, p, aero);
        stack(g, g.current, "out1", from, ATMO_SCOOP, RAINBOW_CORRAL);
        assertEquals(2, Operations.siteWater(g, p, aero), "scoop on " + map().name(from) + " (Hydration was " + dry + ")");
    }

    /* ---- Project Valkyrie: Colocated cards (all the player's stacks on the Space); killing Humans is a Felony (J1c) ---- */

    @Test
    void projectValkyrieHitsEveryStackOnTheSpaceAndKillingAHumanIsAFelony() {
        GameState g = game();
        Player p = g.players.stream().filter(x -> "CY".equals(x.crew)).findFirst().orElseThrow();
        g.current = p.idx;
        String here = nodeIn("mars"), elsewhere = nodeIn("jupiter");
        Stack r = stack(g, p.idx, "rocket", here, KUCK_MOSQUITO, VALKYRIE, PAUL_TRAP, HULA_HOOP);
        FuelTrack.addFuel(r.fuel, 10, "water");
        CardInst human = null;
        Stack leo = p.stacks.get("leo");
        for (CardInst c : leo.cards) if (c.crew != null) human = c;
        leo.cards.remove(human);
        Stack outpost = stack(g, p.idx, "out1", here, TERMITE_NEST);
        outpost.cards.add(human);
        stack(g, p.idx, "out2", elsewhere, TERMITE_NEST);
        assertTrue(Cards.stats(human).radOr0() < 4, "the B612 Human has Rad-Hard 3");

        p.privilege = "none";
        Movement.Plan no = Movement.plan(g, 0, false, null);
        assertFalse(no.ok, "the Human in the Outpost on the Rocket's Space is Colocated");
        assertTrue(no.reason.contains("Project Valkyrie"), no.reason);

        p.privilege = "felonious";
        Movement.Plan yes = Movement.plan(g, 0, false, null);
        assertTrue(yes.ok, yes.reason);
        Movement.start(g, 0, false, null);
        assertTrue(g.turn.felony);
        assertTrue(logged(g, "Felony"));
        assertTrue(p.stacks.rocket.cards.stream().noneMatch(c -> "CX083".equals(c.id)), "the Rad-Hard 2 reactor in the Rocket");
        Stack out1 = p.stacks.get("out1");
        assertTrue(out1 == null || out1.cards.isEmpty(), "the Outpost on the Space loses its Termite Nest and its Human");
        assertTrue(p.stacks.get("leo").cards.contains(human), "the Human returns to LEO");
        assertEquals(1, p.stacks.get("out2").cards.size(), "a stack on another Space is not Colocated");
    }

    /* ---- Magnetocaloric Refrigerator cools its own supports (J4b) ---- */

    @Test
    void magnetocaloricRefrigeratorCoolsItsOwnGenerator() {
        GameState g = game();
        String node = nodeIn("mars");
        Stack s = stack(g, g.current, "out1", node, MAGNETOCALORIC, SOLAR_STIRLING);
        Cards.Analysis an = Cards.analyze(s.cards, "mars");
        assertTrue(an.supported[0], "the refrigerator, powered by the generator");
        assertTrue(an.supported[1], "the generator, cooled by the refrigerator");
    }
}
