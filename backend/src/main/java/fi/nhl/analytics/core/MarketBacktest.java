package fi.nhl.analytics.core;

import java.util.*;

/**
 * Walk-forward comparison of the model against historical closing moneyline prices, plus the hypothetical
 * flat unit return of selections whose model EV against the closing price exceeds a threshold.
 * Bets at closing prices are the hardest test: closing lines are the most efficient prices available.
 */
public final class MarketBacktest {
    private MarketBacktest() {}

    public record Priced(double home, double away) {}
    public record Report(int games, int withClosingOdds, int evaluated, int skippedInsufficientData,
                         double modelBrier, double marketBrier, double brierDiffMean, double brierDiffSe,
                         double modelLogLoss, double marketLogLoss,
                         int flaggedSelections, double flaggedHitRate, double flaggedMeanReturnPct, double flaggedReturnSePct,
                         String note) {}

    public static String key(java.time.LocalDate d, String home, String away) { return d + "|" + home + "|" + away; }

    public static Report run(List<GameResult> games, Map<String, Priced> closing, ScoreModel model, int warmup, double minEvPct) {
        List<GameResult> s = new ArrayList<>(games);
        s.sort(Comparator.comparing(GameResult::date));
        int withOdds = 0, eval = 0, skipped = 0, flagged = 0, hits = 0;
        List<Double> diff = new ArrayList<>(), rets = new ArrayList<>();
        double mb = 0, kb = 0, ml = 0, kl = 0;
        for (int i = 0; i < s.size(); i++) {
            GameResult g = s.get(i);
            Priced pr = closing.get(key(g.date(), g.home(), g.away()));
            if (pr == null) continue;
            withOdds++;
            if (i < warmup) { skipped++; continue; }
            int end = i; while (end > 0 && !s.get(end - 1).date().isBefore(g.date())) end--;
            Optional<ScoreModel.Prediction> op = model.predict(g.home(), g.away(), s.subList(0, end));
            if (op.isEmpty()) { skipped++; continue; }
            double[] mp = {op.get().pHomeMl(), op.get().pAwayMl()};
            double[] fair = Devig.power(new double[]{pr.home(), pr.away()});
            int actual = g.mlIndex();
            double bm = Metrics.brier(mp, actual), bk = Metrics.brier(fair, actual);
            mb += bm; kb += bk; ml += Metrics.logLoss(mp, actual); kl += Metrics.logLoss(fair, actual);
            diff.add(bm - bk); eval++;
            double[] odds = {pr.home(), pr.away()};
            for (int k = 0; k < 2; k++) {
                if (MarketMath.evPct(mp[k], odds[k]) >= minEvPct) {
                    flagged++; boolean hit = actual == k; if (hit) hits++;
                    rets.add(hit ? (odds[k] - 1.0) * 100.0 : -100.0);
                }
            }
        }
        Clv.Summary d = Clv.summarize(diff), r = Clv.summarize(rets);
        double nan = Double.NaN;
        return new Report(s.size(), withOdds, eval, skipped,
                eval == 0 ? nan : mb / eval, eval == 0 ? nan : kb / eval, d.mean(), d.se(),
                eval == 0 ? nan : ml / eval, eval == 0 ? nan : kl / eval,
                flagged, flagged == 0 ? nan : (double) hits / flagged, r.mean(), r.se(),
                "Model-vs-market on stored closing moneyline prices (no-vig by power method). Brier difference < 0 means the model beat "
                + "the closing line. Returns are hypothetical flat 1-unit outcomes at closing prices, shown as percent per unit; "
                + "they are not a profitability claim and ignore limits, margins paid and execution.");
    }
}
