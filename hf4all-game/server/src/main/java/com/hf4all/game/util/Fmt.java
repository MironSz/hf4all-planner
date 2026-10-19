package com.hf4all.game.util;

/** Text helpers shared with the browser (util.js), so log lines read the same. */
public final class Fmt {

    private Fmt() {}

    private static final double[] FRAC = {0.5, 0.25, 0.75, 1.0 / 3, 2.0 / 3, 0.1};
    private static final String[] GLYPH = {"½", "¼", "¾", "⅓", "⅔", "⅒"};

    /** Fuel numbers: 0.5 -> "½", 1.5 -> "1½", 0.1 -> "⅒" (HF.fmtNum). */
    public static String num(double x) {
        long whole = (long) Math.floor(x + 1e-9);
        double frac = x - whole;
        if (frac < 1e-6) return Long.toString(whole);
        for (int i = 0; i < FRAC.length; i++) {
            if (Math.abs(frac - FRAC[i]) < 1e-6) return (whole != 0 ? Long.toString(whole) : "") + GLYPH[i];
        }
        String s = String.format(java.util.Locale.ROOT, "%.2f", x);
        return s.replaceAll("0+$", "");
    }

    public static String plural(int n, String one) { return plural(n, one, one + "s"); }

    public static String plural(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    public static String cap(String s) { return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** JS number to string for log text: integers without ".0". */
    public static String js(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }
}
