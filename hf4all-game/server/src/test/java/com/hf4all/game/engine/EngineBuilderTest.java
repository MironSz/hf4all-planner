package com.hf4all.game.engine;

import com.hf4all.game.TestSupport;
import com.hf4all.game.data.CardDb;
import com.hf4all.game.data.GameData;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Stack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Engines the player builds for the Rocket (thrust triangle + explicit support chain, J1c). */
class EngineBuilderTest {

    private static final String RE_SOLAR_MOTH = "CT048";   // no supports: always available
    private static final String ABLATIVE_PLATE = "CT052";  // needs a burst or exotic reactor
    private static final String DT_GUN_FUSION = "CX091";   // burst reactor, needs a pulsed generator
    private static final String FLYWHEEL = "CG111";        // pulsed generator

    private int uid = 8000;

    @BeforeAll
    static void data() { TestSupport.data(); }

    private GameState game() {
        List<CardDb.Crew> crews = GameData.get().cards.crews();
        return StateOps.newGame(5, 4, List.of(new StateOps.PlayerConfig("A", crews.get(0).id, 0), new StateOps.PlayerConfig("B", crews.get(1).id, 0)));
    }

    private Stack rocket(GameState g, String... ids) {
        Player p = g.players.get(g.current);
        Stack s = Stack.at(GameData.get().map.leo);
        for (String id : ids) s.cards.add(CardInst.patent(id, "w", uid++));
        p.stacks.rocket = s;
        StateOps.syncRocketDry(p);
        FuelTrack.addFuel(s.fuel, 6, "water");
        return s;
    }

    @Test
    void aThrusterWithoutSupportsIsAlwaysAvailable() {
        GameState g = game();
        rocket(g, RE_SOLAR_MOTH, ABLATIVE_PLATE, DT_GUN_FUSION, FLYWHEEL);
        List<Movement.EngineView> es = Movement.engines(g);
        assertEquals(1, es.size(), "only the supportless card; the Ablative Plate needs building");
        assertTrue(es.get(0).simple);
        assertEquals(0, es.get(0).idx);
        RuleError e = assertThrows(RuleError.class, () -> Movement.saveEngine(g, 0, null));
        assertTrue(e.getMessage().contains("always available"));
    }

    @Test
    void buildingAnEngineNeedsAnExplicitSupportForEveryRequirement() {
        GameState g = game();
        Stack r = rocket(g, ABLATIVE_PLATE, DT_GUN_FUSION, FLYWHEEL);
        RuleError none = assertThrows(RuleError.class, () -> Movement.saveEngine(g, 0, null));
        assertTrue(none.getMessage().contains("reactor for Ablative Plate"), none.getMessage());
        RuleError half = assertThrows(RuleError.class, () -> Movement.saveEngine(g, 0, Map.of("0:reactor", 1)));
        assertTrue(half.getMessage().contains("generator for D-T Gun Fusion"), half.getMessage());

        String id = Movement.saveEngine(g, 0, Map.of("0:reactor", 1, "1:gen", 2));
        assertNotNull(id);
        assertEquals(1, r.engines.size());
        Movement.EngineView v = Movement.engines(g).get(0);
        assertEquals(id, v.id);
        assertEquals("Ablative Plate + D-T Gun Fusion + Flywheel Compulsator", v.name);
        assertTrue(v.plan.ok, v.plan.reason);
        assertThrows(RuleError.class, () -> Movement.saveEngine(g, 0, Map.of("0:reactor", 1, "1:gen", 2)), "no duplicates");

        Movement.startEngine(g, id, false, null);
        assertNotNull(g.move);
        assertEquals(List.of(r.cards.get(0).uid, r.cards.get(1).uid, r.cards.get(2).uid), g.move.chainUids);
    }

    @Test
    void anEngineFollowsItsCardsAndDisappearsWhenOneLeaves() {
        GameState g = game();
        Player p = g.players.get(g.current);
        Stack r = rocket(g, ABLATIVE_PLATE, DT_GUN_FUSION, FLYWHEEL);
        Movement.saveEngine(g, 0, Map.of("0:reactor", 1, "1:gen", 2));

        r.cards.add(0, CardInst.patent(RE_SOLAR_MOTH, "w", uid++)); // every index shifts by one
        Movement.EngineView v = Movement.engines(g).stream().filter(x -> !x.simple).findFirst().orElseThrow();
        assertEquals(1, v.idx);
        assertEquals(Map.of("1:reactor", 2, "2:gen", 3), v.chain);

        Actions.transfer(g, "rocket", "leo", List.of(3), 0); // the Flywheel goes back to LEO
        Movement.pruneEngines(g);
        assertNull(r.engines);
        assertFalse(Movement.engines(g).stream().anyMatch(x -> !x.simple));
        assertTrue(p.stacks.leo.cards.stream().anyMatch(c -> FLYWHEEL.equals(c.id)));
    }
}
