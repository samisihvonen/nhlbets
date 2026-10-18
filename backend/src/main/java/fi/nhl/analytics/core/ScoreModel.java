package fi.nhl.analytics.core;

import java.util.*;

/**
 * Independent-Poisson regulation score model with shrunk attack/defence ratings.
 * Draw (tied after 60 min) is resolved to an OT/SO winner using the shrunk empirical home OT/SO win rate,
 * tilted by relative scoring strength. The tilt is a heuristic and NOT validated.
 */
public final class ScoreModel {
    public static final String VERSION = "poisson-shrunk-v1";
    private static final int MAXG = 12;
    private static final double OT_PRIOR_GAMES = 20, OT_TILT = 0.5;

    public record Prediction(double lambdaHome, double lambdaAway, double pHomeReg, double pDraw, double pAwayReg,
                             double pHomeMl, double pAwayMl, int gamesHome, int gamesAway) {}

    private final double shrinkGames;
    private final int minGames;

    public ScoreModel(double shrinkGames, int minGames) { this.shrinkGames = shrinkGames; this.minGames = minGames; }

    private static final class T { int gp; double gf, ga; }

    /** Returns empty when either team has fewer than minGames of history; never fabricates a prediction. */
    public Optional<Prediction> predict(String home, String away, List<GameResult> history) {
        if (history == null || history.isEmpty() || home.equals(away)) return Optional.empty();
        Map<String, T> ts = new HashMap<>();
        double sh = 0, sa = 0; int ties = 0, homeOt = 0;
        for (GameResult g : history) {
            T h = ts.computeIfAbsent(g.home(), k -> new T()), a = ts.computeIfAbsent(g.away(), k -> new T());
            h.gp++; a.gp++; h.gf += g.homeReg(); h.ga += g.awayReg(); a.gf += g.awayReg(); a.ga += g.homeReg();
            sh += g.homeReg(); sa += g.awayReg();
            if (g.extraTime()) { ties++; if (g.homeWon()) homeOt++; }
        }
        T th = ts.get(home), ta = ts.get(away);
        if (th == null || ta == null || th.gp < minGames || ta.gp < minGames) return Optional.empty();
        int n = history.size();
        double muH = sh / n, muA = sa / n, mu = (muH + muA) / 2;
        if (mu <= 0) return Optional.empty();
        double attH = ((th.gf + shrinkGames * mu) / (th.gp + shrinkGames)) / mu;
        double defH = ((th.ga + shrinkGames * mu) / (th.gp + shrinkGames)) / mu;
        double attA = ((ta.gf + shrinkGames * mu) / (ta.gp + shrinkGames)) / mu;
        double defA = ((ta.ga + shrinkGames * mu) / (ta.gp + shrinkGames)) / mu;
        double lh = muH * attH * defA, la = muA * attA * defH;

        double[] ph = pmf(lh), pa = pmf(la);
        double pH = 0, pD = 0, pA = 0, tot = 0;
        for (int i = 0; i <= MAXG; i++) for (int j = 0; j <= MAXG; j++) {
            double p = ph[i] * pa[j]; tot += p;
            if (i > j) pH += p; else if (i == j) pD += p; else pA += p;
        }
        pH /= tot; pD /= tot; pA /= tot;

        double rate = (homeOt + OT_PRIOR_GAMES * 0.5) / (ties + OT_PRIOR_GAMES);
        double pHomeOt = Math.min(0.65, Math.max(0.35, rate + OT_TILT * (lh / (lh + la) - 0.5)));
        double mlH = pH + pD * pHomeOt;
        return Optional.of(new Prediction(lh, la, pH, pD, pA, mlH, 1 - mlH, th.gp, ta.gp));
    }

    static double[] pmf(double lambda) {
        double[] r = new double[MAXG + 1]; r[0] = Math.exp(-lambda);
        for (int k = 1; k <= MAXG; k++) r[k] = r[k - 1] * lambda / k;
        return r;
    }
}
