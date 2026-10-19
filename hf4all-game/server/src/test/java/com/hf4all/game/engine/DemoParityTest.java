package com.hf4all.game.engine;

import com.hf4all.game.TestSupport;
import com.hf4all.game.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Java demo position equals the browser's demo.build(). */
class DemoParityTest {

    @Test
    void demoMatchesTheBrowser() {
        TestSupport.data();
        List<String> diffs = TestSupport.diff(TestSupport.fixture("demo.json"), Json.GSON.toJsonTree(DemoFactory.build()), 20);
        assertTrue(diffs.isEmpty(), TestSupport.report(diffs));
    }
}
