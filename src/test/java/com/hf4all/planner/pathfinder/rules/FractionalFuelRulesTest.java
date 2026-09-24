package com.hf4all.planner.pathfinder.rules;

import com.hf4all.planner.map.MapLoader;
import com.hf4all.planner.model.SolarMap;
import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.api.TraverseResponse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hf4all.planner.support.MapSubgraph;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static com.hf4all.planner.support.RuleTestSupport.*;

/**
 * Rule H5b (fractional fuel use) and the rules it interacts with: H4c pivots,
 * H5d insufficient fuel, H5e lander half-burns.
 *
 * <p>Fractional consumption is summed exactly over one movement and rounded
 * up only when the movement ends; the rounded-off remainder never carries
 * into the next movement. Endpoints report both the rounded
 * {@code fuelSpent} and the exact {@code fuelSpentNum/Den}, which
 * {@link com.hf4all.planner.support.RuleTestSupport#exactCostVectorsAt}
 * renders as {@code fuel|turns|hazards|radRoll@num/den}.
 */
class FractionalFuelRulesTest {

    private static SolarMap fullMap;

    @BeforeAll
    static void load() { fullMap = MapLoader.loadDefault(); }

    private static EngineSpec engine(int baseThrust, int fuelNum, int fuelDen) {
        return new EngineSpec(baseThrust, fuelNum, fuelDen, false, 0, 0, 0, false, false);
    }

    /**
     * H5b — one burn of a 1/2 engine moves the chit half a step; stopping
     * there rounds it up to a whole step. Lagrange 334 → burn 257.
     */
    @Test
    void singleHalfBurnRoundsUpAtEndOfMove() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 5);

        TraverseResponse r = traverse(sub, "334", engine(5, 1, 2), FUEL_DEFAULT);

        assertEquals("ok", r.status());
        assertTrue(exactCostVectorsAt(r, "257").contains("1|1|0|0@1/2"),
                "one 1/2 burn should cost 1/2 step, rounded to 1; got " + exactCostVectorsAt(r, "257"));
    }

    /**
     * H5b — fractions accumulate over the burns of one movement and are
     * rounded once: two 1/2 burns (334 → 257 → … → 224) cost one step,
     * where a 1-step engine pays two.
     */
    @Test
    void fractionsAccumulateWithinOneMove() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 5);

        TraverseResponse half = traverse(sub, "334", engine(5, 1, 2), FUEL_DEFAULT);
        TraverseResponse whole = traverse(sub, "334", engine(5, 1, 1), FUEL_DEFAULT);

        assertEquals("ok", half.status());
        assertTrue(exactCostVectorsAt(half, "224").contains("1|1|0|0@1/1"),
                "two 1/2 burns in one move should cost exactly 1 step; got "
                        + exactCostVectorsAt(half, "224"));
        assertTrue(costVectorsAt(whole, "224").contains("2|1|0|0"),
                "the same two burns cost 2 steps with a 1-step engine; got "
                        + costVectorsAt(whole, "224"));
    }

    /**
     * H5b — the rounded-off remainder is lost when the movement ends. With
     * net thrust 1 (base 3, Tug −2) the two burns to 224 fall in two
     * movements, each rounding its own 1/2 up: 2 steps, never 1. Net thrust 2
     * fits both burns in one movement for a single step.
     */
    @Test
    void remainderIsLostBetweenMoves() {
        SolarMap sub = MapSubgraph.extract(fullMap, "334", 5);

        TraverseResponse oneBurnPerMove = traverse(sub, "334", engine(3, 1, 2), FUEL_DEFAULT);
        TraverseResponse twoBurnsPerMove = traverse(sub, "334", engine(4, 1, 2), FUEL_DEFAULT);

        assertEquals("ok", oneBurnPerMove.status());
        Set<String> split = costVectorsAt(oneBurnPerMove, "224");
        assertTrue(split.contains("2|2|0|0"),
                "1/2 + 1/2 in two movements should cost 2 steps; got " + split);
        assertFalse(split.contains("1|2|0|0"),
                "the first movement's 1/2 remainder must not carry into the second; got " + split);
        assertTrue(costVectorsAt(twoBurnsPerMove, "224").contains("1|1|0|0"),
                "both burns in one movement should cost 1 step; got "
                        + costVectorsAt(twoBurnsPerMove, "224"));
    }

    /**
     * H4c + H5b — a pivot is two burns, so a 1/4 engine pivots for exactly
     * 1/2 step (rounded to 1) and a 1/2 engine for exactly 1. The only
     * turn-1 route from lagrange 1 to Hohmann 6 in this subgraph pivots at
     * Hohmann 5 (see {@link BurnAndPivotRulesTest#pivotConsumesTwoBurns}).
     */
    @Test
    void pivotCostsTwoFractionalBurns() {
        SolarMap sub = MapSubgraph.extract(fullMap, "5", 2);

        TraverseResponse quarter = traverse(sub, "1", engine(5, 1, 4), FUEL_DEFAULT);
        TraverseResponse half = traverse(sub, "1", engine(5, 1, 2), FUEL_DEFAULT);

        assertEquals("ok", quarter.status());
        assertTrue(exactCostVectorsAt(quarter, "6").contains("1|1|0|0@1/2"),
                "pivot with a 1/4 engine should cost 1/2 step; got " + exactCostVectorsAt(quarter, "6"));
        assertTrue(exactCostVectorsAt(half, "6").contains("1|1|0|0@1/1"),
                "pivot with a 1/2 engine should cost 1 step; got " + exactCostVectorsAt(half, "6"));
    }

    /**
     * H5e + H5b — a lander half-burn halves the fractional consumption too
     * and is not rounded before the move ends: a 1/2 engine lifting off
     * Arsia Mons caves (341) through half-burn 338 to Mars north pole (340)
     * spends 1/4 step.
     */
    @Test
    void landerHalfBurnHalvesFractionalConsumption() {
        SolarMap sub = MapSubgraph.extract(fullMap, "338", 3);

        // Base 13 → net 11 at Tug: clears the Size-10 liftoff/landing and the
        // half-burn's thrust requirement of 11.
        TraverseResponse r = traverse(sub, "341", engine(13, 1, 2), FUEL_DEFAULT);

        assertEquals("ok", r.status());
        assertTrue(exactCostVectorsAt(r, "340").contains("1|1|0|0@1/4"),
                "half-burn with a 1/2 engine should cost 1/4 step; got " + exactCostVectorsAt(r, "340"));
    }

    /**
     * H5d — the fuel gate compares the exact sum for the movement with the
     * fuel on hand. With 1 step left a 1/2 engine can still pivot (2 × 1/2 is
     * exactly 1), but a 3/4 engine cannot (3/2 > 1) although each single 3/4
     * burn would fit; it has to loiter through Hohmann 5 instead.
     */
    @Test
    void insufficientFuelGateUsesExactSum() {
        SolarMap sub = MapSubgraph.extract(fullMap, "5", 2);

        TraverseResponse half = traverse(sub, "1", engine(5, 1, 2), 1);
        TraverseResponse threeQuarters = traverse(sub, "1", engine(5, 3, 4), 1);
        TraverseResponse threeQuartersMoreFuel = traverse(sub, "1", engine(5, 3, 4), 2);

        assertEquals("ok", half.status());
        assertTrue(exactCostVectorsAt(half, "6").contains("1|1|0|0@1/1"),
                "a pivot spending exactly the last step is allowed; got " + exactCostVectorsAt(half, "6"));

        Set<String> blocked = costVectorsAt(threeQuarters, "6");
        assertFalse(blocked.stream().anyMatch(c -> c.split("\\|")[1].equals("1")),
                "3/2 steps of pivot must not fit into 1 step of fuel on turn 1; got " + blocked);
        assertTrue(blocked.contains("0|2|0|0"),
                "loitering at Hohmann 5 still reaches 6 for free on turn 2; got " + blocked);

        assertTrue(exactCostVectorsAt(threeQuartersMoreFuel, "6").contains("2|1|0|0@3/2"),
                "with 2 steps the 3/4 engine pivots for 3/2, rounded to 2; got "
                        + exactCostVectorsAt(threeQuartersMoreFuel, "6"));
    }

    /** Accepted range: denominator 1..100, at most a whole fuel strip per burn. */
    @Test
    void engineSpecRejectsOutOfRangeConsumption() {
        assertThrows(IllegalArgumentException.class, () -> engine(5, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> engine(5, 1, 101));
        assertThrows(IllegalArgumentException.class, () -> engine(5, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> engine(5, 57, 1));
        assertThrows(IllegalArgumentException.class, () -> engine(5, 113, 2));

        assertDoesNotThrow(() -> engine(5, 1, 100));
        assertDoesNotThrow(() -> engine(5, 56, 1));
        assertDoesNotThrow(() -> engine(5, 0, 1));
    }
}
