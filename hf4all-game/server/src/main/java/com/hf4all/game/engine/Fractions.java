package com.hf4all.game.engine;

import com.hf4all.planner.model.Fraction;

/**
 * Bridges the planner's exact {@link Fraction} and the plain numbers of the
 * state JSON (fuel consumption such as 1/3 or 1/10 per burn, half lander burns).
 */
public final class Fractions {

    private Fractions() {}

    /** The simplest fraction (denominator up to 1000) within 1e-9 of x. */
    public static Fraction of(double x) {
        if (Math.abs(x - Math.rint(x)) < 1e-9) return Fraction.of((int) Math.rint(x));
        boolean neg = x < 0;
        double v = Math.abs(x);
        // Continued-fraction convergents.
        long h0 = 0, h1 = 1, k0 = 1, k1 = 0;
        double r = v;
        for (int i = 0; i < 40; i++) {
            long a = (long) Math.floor(r);
            long h2 = a * h1 + h0, k2 = a * k1 + k0;
            if (k2 > 1000) break;
            h0 = h1; h1 = h2; k0 = k1; k1 = k2;
            if (Math.abs((double) h1 / k1 - v) < 1e-9) break;
            double frac = r - a;
            if (frac < 1e-12) break;
            r = 1 / frac;
        }
        return new Fraction((int) (neg ? -h1 : h1), (int) k1);
    }
}
