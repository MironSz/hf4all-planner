package com.hf4all.planner.model;

import java.util.Objects;

/**
 * An exact rational number, represented as numerator/denominator with no floating-point rounding.
 * Used for burn costs (e.g. 1/2 for a half-burn landing approach) and accumulated fuel steps.
 * Always stored in reduced form with a positive denominator.
 */
public final class Fraction implements Comparable<Fraction> {

    public static final Fraction ZERO = new Fraction(0, 1);
    public static final Fraction HALF = new Fraction(1, 2);
    public static final Fraction ONE  = new Fraction(1, 1);
    public static final Fraction TWO  = new Fraction(2, 1);

    private final int numerator;
    private final int denominator;

    public Fraction(int numerator, int denominator) {
        if (denominator == 0) throw new ArithmeticException("Fraction denominator cannot be zero");
        // Normalise sign: denominator is always positive
        int sign = denominator < 0 ? -1 : 1;
        int n = numerator * sign;
        int d = denominator * sign;
        int g = gcd(Math.abs(n), d);
        this.numerator   = n / g;
        this.denominator = d / g;
    }

    /** Convenience factory for whole numbers. */
    public static Fraction of(int value) {
        return new Fraction(value, 1);
    }

    public int numerator()   { return numerator; }
    public int denominator() { return denominator; }

    public Fraction add(Fraction other) {
        return new Fraction(
            this.numerator * other.denominator + other.numerator * this.denominator,
            this.denominator * other.denominator
        );
    }

    public Fraction subtract(Fraction other) {
        return new Fraction(
            this.numerator * other.denominator - other.numerator * this.denominator,
            this.denominator * other.denominator
        );
    }

    public Fraction multiply(int scalar) {
        return new Fraction(this.numerator * scalar, this.denominator);
    }

    public Fraction multiply(Fraction other) {
        return new Fraction(this.numerator * other.numerator, this.denominator * other.denominator);
    }

    public boolean isZero() {
        return numerator == 0;
    }

    public boolean isGreaterThan(Fraction other) {
        return this.compareTo(other) > 0;
    }

    public boolean isLessThan(Fraction other) {
        return this.compareTo(other) < 0;
    }

    /** Lossy — only use for display or non-critical comparisons. */
    public double toDouble() {
        return (double) numerator / denominator;
    }

    /**
     * Smallest integer ≥ this fraction. Used for end-of-movement rounding
     * of fractional fuel consumption (HF4A rule H5b).
     */
    public int ceilToInt() {
        // Integer division of non-negative n/d truncates toward zero (= floor),
        // so ceil(n/d) = (n + d - 1) / d. For negative numerators, fall back
        // to the explicit formula.
        if (numerator >= 0) {
            return (numerator + denominator - 1) / denominator;
        }
        return -((-numerator) / denominator);
    }

    @Override
    public int compareTo(Fraction other) {
        // a/b vs c/d  →  a*d vs c*b
        return Integer.compare(this.numerator * other.denominator, other.numerator * this.denominator);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Fraction f)) return false;
        return numerator == f.numerator && denominator == f.denominator;
    }

    @Override
    public int hashCode() {
        return Objects.hash(numerator, denominator);
    }

    @Override
    public String toString() {
        return denominator == 1 ? String.valueOf(numerator) : numerator + "/" + denominator;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
