package com.hf4all.game.engine;

import com.hf4all.game.TestSupport;
import com.hf4all.game.data.CardDb;
import com.hf4all.game.data.GameData;
import com.hf4all.game.data.GameMap;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Site;
import com.hf4all.game.model.Stack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules the engine got wrong in the first port and that the recorded browser
 * traces therefore cannot cover (see TraceReplayTest.RULE_FIXES): one scenario
 * per rule, built directly on a fresh game.
 */
class RuleFixesTest {

    private static final String RE_SOLAR_MOTH = "CT048";      // 3-4 water thruster, Solar-Powered, no supports, mass 0
    private static final String ABLATIVE_PLATE = "CT052";     // needs a burst or exotic reactor
    private static final String PULSED_INDUCTIVE = "CT055";   // needs a pulsed generator
    private static final String DT_GUN_FUSION = "CX091";      // burst reactor, needs a pulsed generator
    private static final String FLYWHEEL = "CG111";           // pulsed generator, Solar-Powered (J5b)
    private static final String PAUL_TRAP = "CX083";          // Air-Eater ability, no supports
    private static final String ION_DRIVE = "CT045";          // Black-Side: fuel consumption 1/2
    private static final String HALL_EFFECT = "CT045";        // White-Side: mass 2
    private static final String CASCADE_PV = "CG106";         // rad-hardness 1

    private int uid = 5000;

    @BeforeAll
    static void data() { TestSupport.data(); }

    private static GameMap map() { return GameData.get().map; }

    private static GameState game() {
        List<CardDb.Crew> crews = GameData.get().cards.crews();
        return StateOps.newGame(7, 4, List.of(
                new StateOps.PlayerConfig("A", crews.get(0).id, 0),
                new StateOps.PlayerConfig("B", crews.get(1).id, 0),
                new StateOps.PlayerConfig("C", crews.get(2).id, 0)));
    }

    private Stack stack(GameState g, int pi, String key, String node, String... cards) {
        Player p = g.players.get(pi);
        Stack s = Stack.at(node);
        for (String id : cards) {
            String side = id.endsWith(":b") ? "b" : "w";
            s.cards.add(CardInst.patent(id.replace(":b", ""), side, uid++));
        }
        p.stacks.set(key, s);
        if (key.equals("rocket")) StateOps.syncRocketDry(p);
        return s;
    }

    /** An rng state whose next d6 is {@code value}. */
    private static long seedFor(int value) {
        GameState probe = game();
        for (long s = 1; s < 100_000; s++) {
            probe.rng = s;
            if (Dice.d6(probe, "probe", null) == value) return s;
        }
        throw new AssertionError("no seed for " + value);
    }

    private static Movement.MoveOption option(GameState g, String to) {
        for (Movement.MoveOption o : Movement.options(g)) if (o.to.equals(to)) return o;
        throw new AssertionError("no route to " + to);
    }

    private static Movement.StepChoices finao() {
        Movement.StepChoices c = new Movement.StepChoices();
        c.finao = true;
        return c;
    }

    /* ---- 1. lander burns: no fixed thrust gate, H5e completion only ---- */

    @Test
    void lowThrustRocketDescendsToVenusAerostatThroughLanderBurnAndAerobrake() {
        GameState g = game();
        Player p = g.players.get(g.current);
        p.aquas = 20;
        Stack r = stack(g, g.current, "rocket", "1481", RE_SOLAR_MOTH);
        FuelTrack.addFuel(r.fuel, 1, "water");
        Movement.start(g, 0, false, null);
        assertTrue(g.move.net < 12, "the test needs a thrust below the lander burn's old requirement");

        Movement.MoveOption burn = option(g, "1482");
        assertTrue(burn.legal, "lander burn above the Venus aerobrake: " + burn.reason);
        Movement.step(g, "1482", finao());
        Movement.MoveOption aero = option(g, "42");
        assertTrue(aero.legal, aero.reason);
        Movement.step(g, "42", finao());
        String aerostat = map().idByName("Venus Aerostat-Xity");
        Movement.MoveOption land = option(g, aerostat);
        assertTrue(land.legal, land.reason);
        assertEquals(Boolean.TRUE, land.aerobrake);
        Movement.step(g, aerostat, finao());
        assertEquals(aerostat, p.stacks.rocket.node);
        assertNull(g.move);
    }

    @Test
    void lowThrustRocketStillCannotEnterALanderBurnThatOnlyLeadsToAPoweredLanding() {
        GameState g = game();
        Stack r = stack(g, g.current, "rocket", "883", RE_SOLAR_MOTH);
        FuelTrack.addFuel(r.fuel, 2, "water");
        Movement.start(g, 0, false, null);
        Movement.MoveOption o = option(g, "347");
        assertFalse(o.legal);
        assertTrue(o.reason.contains("cannot complete the movement"), o.reason);
    }

    /* ---- 2. aerobrake landing only along aerobrake paths ---- */

    @Test
    void bentRoutesFromRadiationBeltsToEuropaAndIoAreNotAerobrakes() {
        for (String[] c : new String[][]{{"563", "Europa: subsurface ocean"}, {"1070", "Io: Loki Patera"}}) {
            GameState g = game();
            Stack r = stack(g, g.current, "rocket", c[0], RE_SOLAR_MOTH);
            FuelTrack.addFuel(r.fuel, 1, "water");
            Movement.start(g, 0, false, null);
            String site = map().idByName(c[1]);
            assertNotNull(site, c[1]);
            Movement.MoveOption o = option(g, site);
            assertFalse(o.legal, c[1] + " must need a powered landing");
            assertTrue(o.reason.contains("landing needs net thrust"), o.reason);
        }
    }

    @Test
    void marsAerobrakePathStillLandsWithoutThrust() {
        GameState g = game();
        g.players.get(g.current).aquas = 20;
        Stack r = stack(g, g.current, "rocket", "901", RE_SOLAR_MOTH);
        FuelTrack.addFuel(r.fuel, 1, "water");
        Movement.start(g, 0, false, null);
        Movement.MoveOption o = option(g, "340");
        assertTrue(o.legal, o.reason);
        assertEquals(Boolean.TRUE, o.aerobrake);
    }

    /* ---- 3. factory refuel without a stack on the Site (fuel outpost) ---- */

    @Test
    void ownFactoryRefuelsIntoANewOutpostWithoutAStackThere() {
        GameState g = game();
        Player p = g.players.get(g.current);
        String luna = map().idByName("Luna: Shackleton polar rim");
        Site site = StateOps.site(g, luna);
        site.claim = p.idx;
        site.factory = true;
        List<Operations.RefuelOpt> opts = Operations.refuelOptions(g);
        int idx = -1;
        for (int i = 0; i < opts.size(); i++) if ("factory".equals(opts.get(i).kind) && luna.equals(opts.get(i).node)) idx = i;
        assertTrue(idx >= 0, "no factory refuel offered at " + luna);
        assertNull(opts.get(idx).key);
        Operations.refuel(g, idx, "new", false, null);
        assertNotNull(p.stacks.out1);
        assertEquals(luna, p.stacks.out1.node);
        assertEquals(7, p.stacks.out1.fts);
        assertEquals("refuel", g.turn.op);
    }

    /* ---- 4. no silent fuel loss past Wet Mass 32 ---- */

    @Test
    void cargoThatWouldPushWetMassPast32IsRefused() {
        GameState g = game();
        Player p = g.players.get(g.current);
        Stack r = stack(g, g.current, "rocket", map().leo, RE_SOLAR_MOTH);
        FuelTrack.addFuel(r.fuel, 31, "water");
        assertEquals(32, FuelTrack.stepToPos(r.fuel.wet).mass());
        p.stacks.leo.cards.add(CardInst.patent(HALL_EFFECT, "w", uid++));
        int card = p.stacks.leo.cards.size() - 1;
        RuleError e = assertThrows(RuleError.class, () -> Actions.transfer(g, "leo", "rocket", List.of(card), 0));
        assertTrue(e.getMessage().contains("past 32"), e.getMessage());

        GameState g2 = game();
        Player p2 = g2.players.get(g2.current);
        Stack r2 = stack(g2, g2.current, "rocket", map().leo, RE_SOLAR_MOTH);
        FuelTrack.addFuel(r2.fuel, 31, "water");
        p2.stacks.leo.cards.add(CardInst.patent(HALL_EFFECT, "w", uid++));
        Actions.jettisonFuel(g2, 1);
        Actions.transfer(g2, "leo", "rocket", List.of(p2.stacks.leo.cards.size() - 1), 0);
        // Both chits moved one mass along the red lines (F2b): Dry 1 -> 2, Wet 31 -> 32.
        assertEquals(2, r2.fuel.dry);
        assertEquals(32, FuelTrack.stepToPos(r2.fuel.wet).mass());
    }

    /* ---- 5. Glitch Roll hits every own stack Colocated with the Glitch ---- */

    @Test
    void glitchRollDecommissionsMatchingCardsInAllColocatedOwnStacks() {
        GameState g = game();
        Player p = g.players.get(g.current);
        int handBefore = p.hand.size();
        Stack r = stack(g, g.current, "rocket", "260", RE_SOLAR_MOTH);
        stack(g, g.current, "out1", "260", RE_SOLAR_MOTH);
        stack(g, g.current, "out2", "901", RE_SOLAR_MOTH);
        r.glitch = true;
        int rad = Cards.stats(r.cards.get(0)).rad;
        g.rng = seedFor(rad);
        assertEquals(rad, Actions.glitchRoll(g, p.idx, "rocket", "test"));
        assertNull(p.stacks.rocket, "glitched stack hit");
        assertNull(p.stacks.out1, "colocated outpost hit too");
        assertNotNull(p.stacks.out2, "an outpost elsewhere is safe");
        assertEquals(handBefore + 2, p.hand.size());
    }

    /* ---- 6. Glitch event: Colocated Humans protect a stack; ties are the player's choice ---- */

    @Test
    void glitchEventSkipsStacksWithColocatedHumans() {
        GameState g = game();
        stack(g, 0, "rocket", "260", RE_SOLAR_MOTH, CASCADE_PV);
        stack(g, 0, "out1", "901", RE_SOLAR_MOTH);
        Player b = g.players.get(1);
        Stack host = stack(g, 1, "out1", "260");
        host.cards.add(b.stacks.leo.cards.remove(0)); // B's Crew on the same Space as A's Rocket
        assertEquals(List.of("out1"), Events.glitchCandidates(g, 0));
    }

    @Test
    void glitchEventTieIsResolvedByThePlayer() {
        GameState g = game();
        stack(g, 0, "out1", "901", RE_SOLAR_MOTH);
        stack(g, 0, "out2", "1481", RE_SOLAR_MOTH);
        g.rng = seedFor(3);
        Events.eventRoll(g);
        assertNotNull(g.pending);
        assertEquals("glitchEvent", g.pending.type);
        assertEquals(List.of(0), g.pending.queue);
        assertEquals(List.of("out1", "out2"), g.pending.stacks);
        assertThrows(RuleError.class, () -> Events.resolveGlitch(g, "rocket"));
        Events.resolveGlitch(g, "out2");
        assertTrue(g.players.get(0).stacks.out2.glitch);
        assertFalse(g.players.get(0).stacks.out1.glitch);
        assertNull(g.pending);
    }

    /* ---- 7. solar flare: the cycler Space is shielded like LEO ---- */

    @Test
    void cyclerSpaceIsShieldedFromSolarFlares() {
        GameState g = game();
        assertEquals("91", GameData.get().rules.cycler);
        stack(g, 0, "out1", "91", CASCADE_PV);
        stack(g, 0, "out2", "97", CASCADE_PV);
        assertEquals("earth", map().zoneOf("97"));
        g.rng = seedFor(4);
        Events.solarFlare(g, 6);
        assertNotNull(g.players.get(0).stacks.out1, "cycler: shielded");
        assertNull(g.players.get(0).stacks.out2, "an unshielded Space next to it is hit");
    }

    /* ---- 8. Air-Eater: five minus fuel consumption, fraction dropped ---- */

    @Test
    void airEaterDropsTheFractionOfFuelConsumption() {
        GameState g = game();
        stack(g, g.current, "rocket", "901", PAUL_TRAP, ION_DRIVE + ":b");
        Operations.RefuelOpt ae = null;
        for (Operations.RefuelOpt o : Operations.refuelOptions(g)) if ("airEater".equals(o.kind)) ae = o;
        assertNotNull(ae, "air-eater refuel on the Mars aerobrake");
        assertEquals(5, ae.fts, "I5c example: consumption 1/2 gives 5 FTs");
    }

    /* ---- 9. J5d: supports needed only by a reactor do not modify thrust ---- */

    @Test
    void solarGeneratorNeededOnlyByTheReactorDoesNotModifyThrust() {
        String mercury = null;
        for (GameMap.Node n : map().nodes().values()) {
            if (n.z.equals("mercury") && n.t.equals("lagrange") && n.haz == null) { mercury = n.id; break; }
        }
        assertNotNull(mercury);

        GameState g = game();
        stack(g, g.current, "rocket", mercury, ABLATIVE_PLATE, DT_GUN_FUSION, FLYWHEEL);
        Movement.Plan viaReactor = Movement.plan(g, 0, false, null);
        assertTrue(viaReactor.ok, viaReactor.reason);
        assertTrue(viaReactor.chainNames.contains("Flywheel Compulsator"), "the generator is in the chain");
        assertTrue(viaReactor.parts.stream().noneMatch(pt -> pt.label.startsWith("Solar power")), "solar modifier ignored (J5d)");
        assertTrue(viaReactor.parts.stream().anyMatch(pt -> pt.label.startsWith("Support: D-T Gun Fusion")), "the reactor's own modifier counts");

        GameState g2 = game();
        stack(g2, g2.current, "rocket", mercury, PULSED_INDUCTIVE, FLYWHEEL);
        Movement.Plan direct = Movement.plan(g2, 0, false, null);
        assertTrue(direct.ok, direct.reason);
        assertTrue(direct.parts.stream().anyMatch(pt -> pt.label.startsWith("Solar power") && pt.value == 2), "a generator the thruster needs keeps its solar modifier");
    }
}
