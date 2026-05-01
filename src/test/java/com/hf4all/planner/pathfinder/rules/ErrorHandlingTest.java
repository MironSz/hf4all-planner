package com.hf4all.planner.pathfinder.rules;

import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.pathfinder.Pathfinder;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.api.TraverseRequest;
import com.hf4all.planner.api.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hf4all.planner.support.MapSubgraph;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error-path tests for {@link Pathfinder#traverse}.
 *
 * <p>These tests use a tiny subgraph (radius 2) because they don't need to
 * search anything — they only exercise the input-validation branches.
 */
class ErrorHandlingTest {

    private static SolarMap sub;

    @BeforeAll
    static void load() {
        sub = MapSubgraph.extract(MapLoader.loadDefault(), "334", 2);
    }

    @Test
    void unknownStartNodeReturnsError() {
        TraverseRequest req = new TraverseRequest(
                "this-node-does-not-exist",
                List.of(new EngineSpec(5, 2, false, 0)),
                4, 29);

        TraverseResponse r = Pathfinder.traverse(sub, req);

        assertNotEquals("ok", r.status(), "unknown start node must not yield status=ok");
        assertNull(r.tree(), "tree must be null on error");
        assertTrue(r.status() != null && r.status().contains("unknown node"),
                "error message should mention 'unknown node'; got: " + r.status());
    }

    @Test
    void emptyEngineListReturnsError() {
        TraverseRequest req = new TraverseRequest("334", List.of(), 4, 29);

        TraverseResponse r = Pathfinder.traverse(sub, req);

        assertNotEquals("ok", r.status(), "empty engine list must not yield status=ok");
        assertNull(r.tree(), "tree must be null on error");
        assertTrue(r.status() != null && r.status().contains("engine"),
                "error message should mention 'engine'; got: " + r.status());
    }
}
