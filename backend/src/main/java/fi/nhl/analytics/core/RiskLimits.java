package fi.nhl.analytics.core;

/** Validated risk limits expressed as fractions of the current bankroll. */
public record RiskLimits(double reserveFraction, double maxPerBetFraction, double maxDailyExposureFraction,
                         double kellyMultiplier, double probabilityShrinkWeight, double minEvFraction,
                         long minimumStakeCents, long roundingIncrementCents) {
    public RiskLimits {
        fraction(reserveFraction, "reserveFraction"); fraction(maxPerBetFraction, "maxPerBetFraction");
        fraction(maxDailyExposureFraction, "maxDailyExposureFraction"); fraction(kellyMultiplier, "kellyMultiplier");
        fraction(probabilityShrinkWeight, "probabilityShrinkWeight"); fraction(minEvFraction, "minEvFraction");
        if (minimumStakeCents < 0) throw new IllegalArgumentException("minimumStakeCents must be non-negative");
        if (roundingIncrementCents <= 0) throw new IllegalArgumentException("roundingIncrementCents must be positive");
    }
    private static void fraction(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException(field + " must be between 0 and 1");
    }
    public static RiskLimits defaults() { return new RiskLimits(.30, .01, .03, .25, .50, .03, 200, 100); }
}
