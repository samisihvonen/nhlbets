package fi.nhl.analytics.core;

public final class Odds {
    private Odds() {}

    /** +110 -> 2.10, -130 -> 1.769. American odds with |value| < 100 are invalid. */
    public static double americanToDecimal(double a) {
        if (!Double.isFinite(a) || Math.abs(a) < 100) throw new IllegalArgumentException("Invalid American odds: " + a);
        return a > 0 ? a / 100.0 + 1.0 : 100.0 / -a + 1.0;
    }

    public static double toDecimal(double value, boolean american) {
        double d = american ? americanToDecimal(value) : value;
        if (!MarketMath.validOdds(d)) throw new IllegalArgumentException("Invalid decimal odds: " + d);
        return d;
    }
}
