package fi.nhl.analytics.core;

/** Removes the bookmaker margin using the actual two- or three-sided market, never an assumed fixed margin. */
public final class Devig {
    private Devig() {}

    public static double[] proportional(double[] odds) { return MarketMath.normalized(odds); }

    /**
     * Power method: finds k with sum(p_i^k) = 1 (p_i raw implied). Shifts margin away from favourites less than
     * proportional scaling does, which better reflects the favourite-longshot bias.
     */
    public static double[] power(double[] odds) {
        double[] p = new double[odds.length]; double sum = 0;
        for (int i = 0; i < p.length; i++) { p[i] = MarketMath.implied(odds[i]); sum += p[i]; }
        if (sum <= 1.0 + 1e-12) return proportional(odds);
        double lo = 1.0, hi = 20.0;
        for (int it = 0; it < 100; it++) {
            double k = (lo + hi) / 2, s = 0;
            for (double x : p) s += Math.pow(x, k);
            if (s > 1.0) lo = k; else hi = k;
        }
        double k = (lo + hi) / 2; double[] out = new double[p.length]; double t = 0;
        for (int i = 0; i < p.length; i++) { out[i] = Math.pow(p[i], k); t += out[i]; }
        for (int i = 0; i < out.length; i++) out[i] /= t;
        return out;
    }
}
