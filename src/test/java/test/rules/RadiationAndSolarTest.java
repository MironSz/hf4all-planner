package test.rules;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.server.dto.EngineSpec;
import com.hf4all.planner.server.dto.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import test.MapSubgraph;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static test.rules.RuleTestSupport.*;

/**
 * Rules H10 (radiation mitigation via thrust) and H3c (solar-power thrust
 * modifier by heliocentric zone).
 */
class RadiationAndSolarTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    /**
     * H10 — worstRadRoll at a radhaz node is {@code max(radiation - thrust, 0)}.
     * Node "38" is a radhaz (radiation=6) directly adjacent to BURN "37".
     * Crossing 37 → 38 with:
     * <ul>
     *   <li>thrust=3 → 6 − 3 = 3, endpoint at 38 must have worstRadRoll=3.
     *   <li>thrust=6 → 6 − 6 = 0, endpoint at 38 must have worstRadRoll=0.
     * </ul>
     */
    @Test
    void radMitigatedByThrust() {
        SolarMap sub = MapSubgraph.extract(fullMap, "38", 2);

        TraverseResponse low = traverse(sub, "37", new EngineSpec(3, 2, false, 0), FUEL_DEFAULT);
        assertEquals("ok", low.status());
        Set<String> lowCosts = costVectorsAt(low, "38");
        assertFalse(lowCosts.isEmpty(), "38 must be reachable from 37 with thrust=3");
        assertTrue(lowCosts.stream().anyMatch(c -> c.endsWith("|3")),
                "thrust=3 must yield worstRadRoll=3 at node 38; got " + lowCosts);

        TraverseResponse high = traverse(sub, "37", new EngineSpec(6, 2, false, 0), FUEL_DEFAULT);
        assertEquals("ok", high.status());
        Set<String> highCosts = costVectorsAt(high, "38");
        assertFalse(highCosts.isEmpty(), "38 must be reachable from 37 with thrust=6");
        assertTrue(highCosts.stream().anyMatch(c -> c.endsWith("|0")),
                "thrust=6 must fully mitigate radiation at node 38 (worstRadRoll=0); got " + highCosts);
    }

    /**
     * H3c — a solar-powered engine with base thrust 4 in a sphere7 zone
     * (solarMod = −4) has effective thrust 0 and cannot burn at all. A
     * non-solar engine with the same base thrust is unaffected and reaches
     * strictly more endpoints.
     *
     * <p>Scenario: start at BURN "669" (sphere7). With a small radius the
     * search stays fast.
     */
    @Test
    void solarPoweredLosesInOuterZone() {
        // Radius 4 is too small — the local sphere7 region has few burn nodes
        // to differentiate solar vs non-solar reachability. Bumping to 10.
        SolarMap sub = MapSubgraph.extract(fullMap, "669", 10);

        EngineSpec nonSolar = new EngineSpec(4, 2, false, 0);
        EngineSpec solar    = new EngineSpec(4, 2, true,  0);

        TraverseResponse nonSolarResp = traverse(sub, "669", nonSolar, 40);
        TraverseResponse solarResp    = traverse(sub, "669", solar,    40);

        assertEquals("ok", nonSolarResp.status());
        assertEquals("ok", solarResp.status());

        int nonSolarEndpoints = nonSolarResp.endpoints().size();
        int solarEndpoints    = solarResp.endpoints().size();

        assertTrue(solarEndpoints < nonSolarEndpoints,
                "solar engine at sphere7 (effective thrust 0) must reach strictly fewer "
                + "endpoints than a non-solar engine. Got solar=" + solarEndpoints
                + ", nonSolar=" + nonSolarEndpoints);
    }
}
