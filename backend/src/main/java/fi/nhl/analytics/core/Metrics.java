package fi.nhl.analytics.core;

public final class Metrics {
    private Metrics() {}

    /** Multiclass Brier: sum over classes of (p_k - o_k)^2. Range [0,2]. */
    public static double brier(double[] p, int actual) {
        double s = 0;
        for (int k = 0; k < p.length; k++) { double o = k == actual ? 1 : 0; s += (p[k] - o) * (p[k] - o); }
        return s;
    }

    public static double logLoss(double[] p, int actual) { return -Math.log(Math.max(p[actual], 1e-15)); }

    /** Wilson 95% interval; returns {lo, hi}, or {NaN, NaN} if n == 0. */
    public static double[] wilson(int k, int n) {
        if (n == 0) return new double[]{Double.NaN, Double.NaN};
        double z = 1.96, ph = (double) k / n, d = 1 + z * z / n;
        double c = (ph + z * z / (2 * n)) / d;
        double h = z * Math.sqrt(ph * (1 - ph) / n + z * z / (4.0 * n * n)) / d;
        return new double[]{Math.max(0, c - h), Math.min(1, c + h)};
    }
}
