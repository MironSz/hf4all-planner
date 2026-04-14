package com.hf4all.planner.model;

import java.util.Objects;

/**
 * Metadata attached to SITE nodes. Immutable.
 *
 * siteSize encodes two things from the original data: a numeric thrust requirement
 * and a size-class letter (S/M/C/V/D/H). Examples: "1S", "9H", "11C".
 *
 * siteWater is the ISRU water rating (0–4). Rockets with ISRU equipment can refuel
 * at sites whose water rating meets or exceeds their ISRU threshold.
 */
public final class SiteData {

    private final String name;
    private final String siteSize;   // raw string, e.g. "9H"
    private final int    water;      // 0–4

    public SiteData(String name, String siteSize, int water) {
        this.name     = Objects.requireNonNull(name,     "site name must not be null");
        this.siteSize = Objects.requireNonNull(siteSize, "siteSize must not be null");
        this.water    = water;
    }

    public String name()     { return name; }
    public String siteSize() { return siteSize; }
    public int    water()    { return water; }

    /**
     * Extracts the numeric thrust requirement from siteSize.
     * e.g. "9H" → 9,  "11C" → 11,  "1S" → 1
     */
    public int thrustRequired() {
        StringBuilder digits = new StringBuilder();
        for (char c : siteSize.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else break;
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
    }

    /**
     * Extracts the size-class letter from siteSize.
     * e.g. "9H" → 'H',  "1S" → 'S'
     */
    public char sizeClass() {
        for (char c : siteSize.toCharArray()) {
            if (Character.isLetter(c)) return c;
        }
        throw new IllegalStateException("No size-class letter in siteSize: " + siteSize);
    }

    @Override
    public String toString() {
        return "SiteData{name='" + name + "', size=" + siteSize + ", water=" + water + "}";
    }
}
