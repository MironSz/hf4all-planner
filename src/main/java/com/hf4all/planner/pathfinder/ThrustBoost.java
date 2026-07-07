package com.hf4all.planner.pathfinder;

/**
 * A turn-start "thrust boost": spend {@code fuelCost} fuel steps at the
 * start of a movement to get frozen net thrust {@code thrust} for the rest
 * of the movement (HF4A H3 / H3a / F3d / G1f).
 *
 * <p>This is the single data shape that unifies fuel jettison and
 * afterburn: jettisoning fuel drops the ship a weight class (raising
 * {@code effectiveThrust}), afterburn spends fuel for a flat {@code +gain}
 * layered on top (project rule H3a — the afterburn cost does NOT feed back
 * into the weight-class snapshot), and a turn-start may do both at once
 * (jettison then afterburn). The search core treats every option the same
 * way — differences between "jettison" and "afterburn" exist only as data
 * here (which fields are nonzero) and in the boost-menu generator that
 * produces these records ({@link Pathfinder#boostMenu}).
 *
 * @param fuelCost        total fuel steps spent to take this option (jettisoned
 *                        steps + afterburn cost, if any).
 * @param thrust          frozen net thrust for the rest of the movement.
 * @param oberthBonus     1 if this option includes afterburn (drives the
 *                        Solar-Oberth +1, H8e, and the FrontierKey's
 *                        afterburn bit); 0 otherwise.
 * @param jettisonedSteps fuel steps jettisoned to reach this option's weight
 *                        class (0 if this option didn't jettison). Provenance
 *                        only — used for response badges, not for dominance.
 */
record ThrustBoost(int fuelCost, int thrust, int oberthBonus, int jettisonedSteps) {

    /** The trivial "do nothing" boost: no fuel spent, no afterburn, whatever
     *  thrust the base weight class already gives. Convenience for callers
     *  building a base option. */
    static ThrustBoost base(int thrust) {
        return new ThrustBoost(0, thrust, 0, 0);
    }

    /** True if this option includes an afterburn (H3a once-per-movement use). */
    boolean afterburned() {
        return oberthBonus > 0;
    }
}
