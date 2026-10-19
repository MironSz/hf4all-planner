package com.hf4all.game.engine;

import com.hf4all.game.TestSupport;
import com.hf4all.game.model.GameState;
import com.hf4all.game.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every demo scenario builds a valid game and reaches the decision it promises. */
class ScenariosTest {

    @BeforeAll
    static void data() { TestSupport.data(); }

    @TestFactory
    List<DynamicTest> everyScenarioReachesItsDecision() {
        Set<String> ids = new HashSet<>();
        return Scenarios.list().stream().map(info -> DynamicTest.dynamicTest(info.id(), () -> {
            assertTrue(ids.add(info.id()), "unique id " + info.id());
            GameState g = Scenarios.build(info.id());
            // Through JSON and back, as the server stores and the client receives it.
            g = Json.read(Json.write(g), GameState.class);
            Scenarios.check(info.id(), g);
        })).toList();
    }
}
