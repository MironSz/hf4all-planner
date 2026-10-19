package com.hf4all.game.model;

/**
 * A Rocket's Fuel Strip (F): the Dry Mass chit as an integer mass, the Wet
 * Mass chit as a black-line step (0 = mass 1 .. 56 = mass 32) and the fuel grade.
 */
public final class Fuel {
    public int dry;
    public int wet;
    /** "water" | "dirt" | "isotope" | null (empty tanks). */
    public String grade;

    public Fuel() {}

    public Fuel(int dry, int wet, String grade) {
        this.dry = dry;
        this.wet = wet;
        this.grade = grade;
    }

    public Fuel copy() { return new Fuel(dry, wet, grade); }
}
