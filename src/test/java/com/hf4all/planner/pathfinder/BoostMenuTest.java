package com.hf4all.planner.pathfinder;

import com.hf4all.planner.api.EngineSpec;
import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.NodeType;
import com.hf4all.planner.model.SolarMap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Pathfinder#boostMenu}, the generator that unifies
 * fuel-jettison and afterburn turn-start options into one menu of
 * {@link ThrustBoost} records.
 *
 * <p>Scenario fixed at dryMass=4, fuelSteps=29 (Wet Mass 24, Tug class,
 * weight-class mod -2) — the same ship as the pathfinder benchmark. Verified
 * jettison rungs at this fuel level (FuelStrip.jettisonAmountsForClassChange):
 * {@code [8, 19, 28]}, landing on Transport (mod -1), Scout (mod 0), and
 * Probe (mod +1) respectively — Wisp is unreachable because dryMass alone
 * (fuel-strip step 19) already exceeds Wisp's top step (8).
 */
class BoostMenuTest {

    private static final int DRY_MASS   = 4;
    private static final int FUEL_STEPS = 29;

    /** Plain BURN node, solarMod=0, so effectiveThrust is baseThrust + weightMod. */
    private static final MapNode NODE = MapNode.builder("test-node", NodeType.BURN)
            .solarMod(0)
            .build();

    private static Pathfinder pathfinder(EngineSpec engine, boolean allowJettison) {
        // SolarMap isn't touched by boostMenu (it only reads dryMass/engines),
        // so a minimal empty map is fine here.
        SolarMap emptyMap = SolarMap.builder().build();
        return new Pathfinder(emptyMap, List.of(engine), DRY_MASS, FUEL_STEPS,
                allowJettison, /*startingYear=*/ 1);
    }

    // --- base menu (no afterburn, no jettison eligibility) ------------------

    @Test
    void baseOptionIsAlwaysPresent() {
        EngineSpec engine = new EngineSpec(7, 2, false, 1); // no AB (cost=gain=0)
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, false, true);

        // Tug class mod = -2, base thrust 7 -> effective 5.
        assertTrue(menu.contains(new ThrustBoost(0, 5, 0, 0)),
                "menu must contain the base (no-spend) option; got " + menu);
    }

    @Test
    void noAfterburnEngineHasNoAbOptions() {
        EngineSpec engine = new EngineSpec(7, 2, false, 1); // canAfterburn() == false
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, false, true);

        assertTrue(menu.stream().noneMatch(ThrustBoost::afterburned),
                "engine with afterburnFuelCost=0/gain=0 must offer no AB options; got " + menu);
    }

    // --- jettison rungs -------------------------------------------------------

    @Test
    void jettisonRungsMatchFuelStripClassBoundaries() {
        EngineSpec engine = new EngineSpec(7, 2, false, 1); // no AB
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, false, true);

        // Expect jettisonedSteps entries at 8, 19, 28 fuel-strip steps, each
        // producing successively higher thrust (weight class getting lighter).
        // Effective thrust = baseThrust(7) + weightMod:
        //   x=8  -> newFuel=21 -> mod=-1 -> thrust=6
        //   x=19 -> newFuel=10 -> mod= 0 -> thrust=7
        //   x=28 -> newFuel=1  -> mod=+1 -> thrust=8
        assertTrue(menu.contains(new ThrustBoost(8, 6, 0, 8)), "menu=" + menu);
        assertTrue(menu.contains(new ThrustBoost(19, 7, 0, 19)), "menu=" + menu);
        assertTrue(menu.contains(new ThrustBoost(28, 8, 0, 28)), "menu=" + menu);
    }

    @Test
    void jettisonRungsExcludedOnTurnOne() {
        EngineSpec engine = new EngineSpec(7, 2, false, 1);
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, /*turnOne=*/ true, true);

        assertTrue(menu.stream().allMatch(b -> b.jettisonedSteps() == 0),
                "turn 1 must not offer jettison options (F3d — committed to starting load); got "
                        + menu);
        // Base option still present.
        assertEquals(1, menu.size(), "turn-1 menu for a non-AB engine should be just the base option");
    }

    @Test
    void jettisonRungsExcludedWhenNotAllowed() {
        EngineSpec engine = new EngineSpec(7, 2, false, 1);
        Pathfinder pf = pathfinder(engine, false);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, false, /*allowJettison=*/ false);

        assertTrue(menu.stream().allMatch(b -> b.jettisonedSteps() == 0),
                "allowFuelJettison=false must suppress every jettison option; got " + menu);
    }

    // --- afterburn + combos ----------------------------------------------------

    @Test
    void afterburnOptionLayersOnBaseThrust() {
        EngineSpec engine = new EngineSpec(7, 2, 1, false, 1, /*abCost*/ 1, /*abGain*/ 1, false, false);
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, false, true);

        // Base thrust 5 (Tug -2), AB option = cost 1, thrust 5+1=6, oberthBonus=1.
        assertTrue(menu.contains(new ThrustBoost(1, 6, 1, 0)),
                "menu must contain the pure-AB option; got " + menu);
    }

    @Test
    void afterburnOmittedWhenUnaffordable() {
        // fuelSteps == 0 leaves nothing to spend on AB cost 1.
        EngineSpec engine = new EngineSpec(7, 2, 1, false, 1, 1, 1, false, false);
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, 0, false, true);

        assertTrue(menu.stream().noneMatch(ThrustBoost::afterburned),
                "with 0 fuel on hand, the AB option (cost 1) must be excluded; got " + menu);
    }

    @Test
    void rungPlusAfterburnCombosArePresentWhenAffordable() {
        EngineSpec engine = new EngineSpec(7, 2, 1, false, 1, 1, 1, false, false);
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, false, true);

        // Rung x=8 (thrust 6, no AB) + AB combo: cost 8+1=9, thrust 6+1=7, oberthBonus=1.
        assertTrue(menu.contains(new ThrustBoost(9, 7, 1, 8)),
                "menu must contain rung(8)+AB combo; got " + menu);
    }

    // --- pruning -----------------------------------------------------------

    @Test
    void menuIsSortedByFuelCostAscending() {
        EngineSpec engine = new EngineSpec(7, 2, 1, false, 1, 1, 1, false, false);
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, false, true);

        for (int i = 1; i < menu.size(); i++) {
            assertTrue(menu.get(i - 1).fuelCost() <= menu.get(i).fuelCost(),
                    "menu must be sorted by fuelCost ascending; got " + menu);
        }
    }

    @Test
    void dominatedOptionsArePrunedFromMenu() {
        // Zero-gain "afterburn" (cost 1, gain 0) is dominated by the free base
        // option: same thrust, same oberthBonus=0... wait gain 0 means
        // canAfterburn() is false (afterburnThrustGain must be > 0). Use a
        // different dominance case instead: an engine whose jettison rung
        // costs more fuel than the AB option but produces no more thrust and
        // no oberth bonus should be pruned in favour of the cheaper AB option
        // when the AB thrust is >= the rung thrust.
        //
        // With baseThrust=7 (Tug mod -2 => 5) and AB gain=3 at cost=1: pure AB
        // gives (1, 8, 1, 0). Rung x=8 gives (8, 6, 0, 8) — strictly worse on
        // fuelCost, thrust, AND oberthBonus than the AB option — must be pruned.
        EngineSpec engine = new EngineSpec(7, 2, 1, false, 1, /*abCost*/ 1, /*abGain*/ 3, false, false);
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, false, true);

        assertFalse(menu.contains(new ThrustBoost(8, 6, 0, 8)),
                "rung(8) alone should be pruned: dominated by the cheaper pure-AB option; got " + menu);
        assertTrue(menu.contains(new ThrustBoost(1, 8, 1, 0)),
                "dominant pure-AB option must survive pruning; got " + menu);
    }

    @Test
    void everySurvivingOptionIsNonDominated() {
        EngineSpec engine = new EngineSpec(7, 2, 1, false, 1, 1, 1, false, false);
        Pathfinder pf = pathfinder(engine, true);

        List<ThrustBoost> menu = pf.boostMenu(0, NODE, FUEL_STEPS, false, true);

        for (ThrustBoost candidate : menu) {
            for (ThrustBoost other : menu) {
                if (other == candidate) continue;
                boolean otherNoWorse = other.fuelCost() <= candidate.fuelCost()
                        && other.thrust() >= candidate.thrust()
                        && other.oberthBonus() >= candidate.oberthBonus();
                boolean otherStrictlyBetter = other.fuelCost() < candidate.fuelCost()
                        || other.thrust() > candidate.thrust()
                        || other.oberthBonus() > candidate.oberthBonus();
                assertFalse(otherNoWorse && otherStrictlyBetter,
                        candidate + " is dominated by " + other + " but survived pruning; menu=" + menu);
            }
        }
    }
}
