package fi.nhl.analytics.core;

/** Pure Kelly sizing helpers. Fractions are proportions, not percentages. */
public final class Kelly {
    private Kelly() {}

    public static double fraction(double probability, double decimalOdds) {
        if (!Double.isFinite(probability) || !Double.isFinite(decimalOdds) || probability < 0 || probability > 1 || decimalOdds <= 1) return 0;
        return Math.max(0, (probability * decimalOdds - 1) / (decimalOdds - 1));
    }

    public static double conservativeProbability(double model, double market, double se, double shrinkWeight) {
        if (!Double.isFinite(model) || !Double.isFinite(market) || !Double.isFinite(se) || !Double.isFinite(shrinkWeight)
                || model < 0 || model > 1 || market < 0 || market > 1 || se < 0 || shrinkWeight < 0 || shrinkWeight > 1) return Double.NaN;
        return Math.max(0, Math.min(model, Math.min(market + shrinkWeight * (model - market), model - se)));
    }

    public static double minimumOdds(double probability, double minEvFraction) {
        if (!Double.isFinite(probability) || probability <= 0 || !Double.isFinite(minEvFraction) || minEvFraction < 0) return Double.POSITIVE_INFINITY;
        return (1 + minEvFraction) / probability;
    }
}
