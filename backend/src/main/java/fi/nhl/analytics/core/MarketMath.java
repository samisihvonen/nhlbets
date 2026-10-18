package fi.nhl.analytics.core;

public final class MarketMath {
    private MarketMath() {}

    public static boolean validOdds(double o) { return Double.isFinite(o) && o > 1.0; }

    public static double implied(double odds) {
        if (!validOdds(odds)) throw new IllegalArgumentException("Invalid decimal odds: " + odds);
        return 1.0 / odds;
    }

    public static double overround(double[] decimalOdds) {
        double s = 0; for (double o : decimalOdds) s += implied(o);
        return s - 1.0;
    }

    public static double[] normalized(double[] decimalOdds) {
        double s = 0; double[] r = new double[decimalOdds.length];
        for (int i = 0; i < r.length; i++) { r[i] = implied(decimalOdds[i]); s += r[i]; }
        for (int i = 0; i < r.length; i++) r[i] /= s;
        return r;
    }

    /** EV per unit: p*O - 1. */
    public static double ev(double p, double odds) {
        if (!validOdds(odds)) throw new IllegalArgumentException("Invalid decimal odds: " + odds);
        return p * odds - 1.0;
    }
    public static double evPct(double p, double odds) { return 100.0 * ev(p, odds); }

    /** Edge in percentage points: model probability minus normalized market probability. */
    public static double edgePp(double modelP, double marketNormP) { return 100.0 * (modelP - marketNormP); }

    public static String classify(boolean hasPrediction, Double odds, boolean stale, double p,
                                  boolean experimental, double minEvPct) {
        if (!hasPrediction) return "INSUFFICIENT_DATA";
        if (odds == null) return stale ? "STALE_ODDS" : "MARKET_UNAVAILABLE";
        double e = evPct(p, odds);
        if (e < minEvPct) return "NO_VALUE_IDENTIFIED";
        return experimental ? "POSITIVE_EV_EXPERIMENTAL" : "POSITIVE_EV";
    }
}
