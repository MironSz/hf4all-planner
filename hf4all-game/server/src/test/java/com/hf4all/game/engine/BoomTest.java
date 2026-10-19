package com.hf4all.game.engine;

import com.hf4all.game.TestSupport;
import com.hf4all.game.data.CardDb;
import com.hf4all.game.data.GameData;
import com.hf4all.game.model.CardInst;
import com.hf4all.game.model.GameState;
import com.hf4all.game.model.Player;
import com.hf4all.game.model.Stack;
import com.hf4all.game.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Lost cards are reported for the client's explosion effects, but never stored in the state. */
class BoomTest {

    @BeforeAll
    static void data() { TestSupport.data(); }

    private GameState game() {
        List<CardDb.Crew> crews = GameData.get().cards.crews();
        GameState g = StateOps.newGame(5, 4, List.of(new StateOps.PlayerConfig("A", crews.get(0).id, 0), new StateOps.PlayerConfig("B", crews.get(1).id, 0)));
        Player p = g.players.get(g.current);
        Stack s = Stack.at(GameData.get().map.leo);
        s.cards.add(CardInst.patent("CT048", "w", 9001)); // Re Solar Moth
        s.cards.add(CardInst.patent("CG111", "w", 9002)); // Flywheel Compulsator
        p.stacks.rocket = s;
        StateOps.syncRocketDry(p);
        return g;
    }

    @Test
    void anInvoluntaryLossIsReportedWithItsStackPlaceAndCause() {
        GameState g = game();
        Actions.decommission(g, g.current, "rocket", 1, Actions.Decom.of(true, false, "solar flare"));
        assertEquals(List.of(new GameState.Boom(g.current, "rocket", GameData.get().map.leo, "Flywheel Compulsator", "solar flare")), g.booms);
        assertFalse(Json.write(g).contains("booms"), "never part of the state JSON");
    }

    @Test
    void aVoluntaryDecommissionIsNoExplosion() {
        GameState g = game();
        Actions.decommission(g, g.current, "rocket", 1, Actions.Decom.of(false, false, null));
        assertNull(g.booms);
    }
}
