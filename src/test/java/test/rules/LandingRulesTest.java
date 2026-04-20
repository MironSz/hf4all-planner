package test.rules;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.server.dto.EngineSpec;
import com.hf4all.planner.server.dto.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import test.MapSubgraph;

import static org.junit.jupiter.api.Assertions.*;
import static test.rules.RuleTestSupport.*;

/**
 * Rules H6a (powered landing / liftoff thrust gates) and the landing-burn
 * thrustRequired gate used before powered landings.
 */
class LandingRulesTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * H6a — a powered landing on a SITE requires {@code thrust > siteSize}.
     * Eureka (id "4", size 1, no aerobrake) is directly adjacent to lagrange
     * "969". thrust=1 must NOT land (1 &gt; 1 is false); thrust=2 must land.
     */
    @Test
    void poweredLandingRequiresThrustStrictlyGreaterThanSize() {
        SolarMap sub = MapSubgraph.extract(fullMap, "969", 2);

        EngineSpec belowThreshold = new EngineSpec(1, 2, false, 0);
        TraverseResponse low = traverse(sub, "969", belowThreshold, FUEL_DEFAULT);
        assertEquals("ok", low.status());
        assertFalse(reached(low, "4"),
                "Eureka (size 1) must not be reachable with thrust=1: rule requires strict >");

        EngineSpec atThreshold = new EngineSpec(2, 2, false, 0);
        TraverseResponse ok = traverse(sub, "969", atThreshold, FUEL_DEFAULT);
        assertEquals("ok", ok.status());
        assertTrue(reached(ok, "4"),
                "Eureka (size 1) must be reachable with thrust=2");
    }

    /**
     * H6a (liftoff) — leaving a SITE requires {@code thrust > siteSize}.
     * Start at Eureka (size 1). thrust=1 must not reach the adjacent
     * lagrange "969"; thrust=2 must.
     */
    @Test
    void liftoffRequiresThrustGreaterThanSize() {
        SolarMap sub = MapSubgraph.extract(fullMap, "4", 2);

        EngineSpec belowThreshold = new EngineSpec(1, 2, false, 0);
        TraverseResponse low = traverse(sub, "4", belowThreshold, FUEL_DEFAULT);
        assertEquals("ok", low.status());
        assertFalse(reached(low, "969"),
                "Eureka liftoff must fail with thrust=1 (not strictly greater than size 1)");

        EngineSpec atThreshold = new EngineSpec(2, 2, false, 0);
        TraverseResponse ok = traverse(sub, "4", atThreshold, FUEL_DEFAULT);
        assertEquals("ok", ok.status());
        assertTrue(reached(ok, "969"),
                "Eureka liftoff must succeed with thrust=2");
    }

    /**
     * Landing-burn thrust gate — a BURN node with {@code landing > 0} requires
     * {@code thrust ≥ thrustRequired} to enter. Node "339" is a landing burn
     * (thrustRequired=11, landing=1) directly adjacent to lagrange "334".
     * thrust=10 must NOT reach 339; thrust=11 must.
     */
    @Test
    void landingBurnGateEnforced() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 2);

        EngineSpec belowThreshold = new EngineSpec(10, 2, false, 0);
        TraverseResponse low = traverse(sub, "334", belowThreshold, FUEL_DEFAULT);
        assertEquals("ok", low.status());
        assertFalse(reached(low, "339"),
                "landing burn 339 (thrustRequired=11) must not be enterable with thrust=10");

        EngineSpec atThreshold = new EngineSpec(11, 2, false, 0);
        TraverseResponse ok = traverse(sub, "334", atThreshold, 40);
        assertEquals("ok", ok.status());
        assertTrue(reached(ok, "339"),
                "landing burn 339 must be enterable with thrust=11");
    }
}
