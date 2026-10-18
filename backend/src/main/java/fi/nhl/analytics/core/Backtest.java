package fi.nhl.analytics.core;

import java.util.*;

/** Walk-forward, model-only evaluation. History for a game contains only games on strictly earlier dates. */
public final class Backtest {
    private Backtest() {}

    public record Bucket(double lower, double upper, int n, double meanPredicted, double observed) {}
    public record Report(String modelVersion, int games, int predicted, int skippedInsufficientData,
                         double regHitRate, double[] regHitCi, double mlHitRate, double[] mlHitCi,
                         double regBrier, double regLogLoss, double mlBrier, double mlLogLoss,
                         double regBrierUniform, double mlBrierUniform, List<Bucket> mlHomeCalibration,
                         String note) {}

    public static Report run(List<GameResult> games, ScoreModel model, int warmup) {
        List<GameResult> s = new ArrayList<>(games);
        s.sort(Comparator.comparing(GameResult::date));
        int np = 0, skipped = 0, regHit = 0, mlHit = 0;
        double rb = 0, rl = 0, mb = 0, ml = 0;
        double[] bn = new double[10], bp = new double[10], bo = new double[10];
        for (int i = warmup; i < s.size(); i++) {
            GameResult g = s.get(i);
            int end = i; while (end > 0 && !s.get(end - 1).date().isBefore(g.date())) end--; // exclude same-day games
            Optional<ScoreModel.Prediction> op = model.predict(g.home(), g.away(), s.subList(0, end));
            if (op.isEmpty()) { skipped++; continue; }
            ScoreModel.Prediction p = op.get();
            double[] reg = {p.pHomeReg(), p.pDraw(), p.pAwayReg()}, mlp = {p.pHomeMl(), p.pAwayMl()};
            np++;
            if (argmax(reg) == g.regIndex()) regHit++;
            if (argmax(mlp) == g.mlIndex()) mlHit++;
            rb += Metrics.brier(reg, g.regIndex()); rl += Metrics.logLoss(reg, g.regIndex());
            mb += Metrics.brier(mlp, g.mlIndex()); ml += Metrics.logLoss(mlp, g.mlIndex());
            int b = Math.min(9, (int) (p.pHomeMl() * 10));
            bn[b]++; bp[b] += p.pHomeMl(); bo[b] += g.homeWon() ? 1 : 0;
        }
        List<Bucket> cal = new ArrayList<>();
        for (int b = 0; b < 10; b++) if (bn[b] > 0) cal.add(new Bucket(b / 10.0, (b + 1) / 10.0, (int) bn[b], bp[b] / bn[b], bo[b] / bn[b]));
        double nan = Double.NaN;
        return new Report(ScoreModel.VERSION, s.size(), np, skipped,
                np == 0 ? nan : (double) regHit / np, Metrics.wilson(regHit, np),
                np == 0 ? nan : (double) mlHit / np, Metrics.wilson(mlHit, np),
                np == 0 ? nan : rb / np, np == 0 ? nan : rl / np, np == 0 ? nan : mb / np, np == 0 ? nan : ml / np,
                2.0 / 3.0, 0.5, cal,
                "Model-only backtest on stored NHL results. No historical bookmaker odds were used, so no EV/return is evaluated. "
                + "Brier is multiclass (sum over classes, range 0-2); uniform-guess baselines are 2/3 (1X2) and 0.5 (moneyline).");
    }

    static int argmax(double[] p) { int b = 0; for (int i = 1; i < p.length; i++) if (p[i] > p[b]) b = i; return b; }
}
