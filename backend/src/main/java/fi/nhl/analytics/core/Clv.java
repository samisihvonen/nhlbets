package fi.nhl.analytics.core;

import java.util.List;

public final class Clv {
    private Clv() {}

    /** Closing line value in percent: (taken / closing - 1) * 100. Positive = beat the closing price. */
    public static double clvPct(double takenOdds, double closingOdds) {
        if (!MarketMath.validOdds(takenOdds) || !MarketMath.validOdds(closingOdds)) throw new IllegalArgumentException("Invalid odds");
        return (takenOdds / closingOdds - 1.0) * 100.0;
    }

    /** EV in percent of the taken price when the closing no-vig probability is treated as the truth. */
    public static double closingEvPct(double takenOdds, double closingFairProb) {
        if (!MarketMath.validOdds(takenOdds) || closingFairProb <= 0 || closingFairProb >= 1) throw new IllegalArgumentException("Invalid input");
        return (takenOdds * closingFairProb - 1.0) * 100.0;
    }

    public record Summary(int n, double mean, double se, double ciLo, double ciHi, double shareAboveZero) {}

    public static Summary summarize(List<Double> xs) {
        int n = xs.size();
        if (n == 0) return new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        double m = xs.stream().mapToDouble(d -> d).sum() / n;
        double se = Double.NaN;
        if (n > 1) { double v = 0; for (double x : xs) v += (x - m) * (x - m); se = Math.sqrt(v / (n - 1) / n); }
        long pos = xs.stream().filter(d -> d > 0).count();
        return new Summary(n, m, se, Double.isNaN(se) ? Double.NaN : m - 1.96 * se, Double.isNaN(se) ? Double.NaN : m + 1.96 * se, (double) pos / n);
    }
}
