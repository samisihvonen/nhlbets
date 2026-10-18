package fi.nhl.analytics.core;

import java.util.*;

/** Seeded, conditional Monte Carlo. It makes no claim about real-world profitability. */
public final class BankrollSim {
    private BankrollSim() {}
    public record Wager(long stakeCents, double modelProbability, double marketProbability, double decimalOdds) {}
    public record Result(int paths, long medianFinalCents, long p05FinalCents, double probability30PctDrawdown,
                         double probabilityReserveBreach, String assumption) {}

    public static Result simulate(long openingCents, long reserveCents, List<Wager> wagers, double edgeFactor, int paths, long seed) {
        if (openingCents < 0 || reserveCents < 0 || paths < 1 || !Double.isFinite(edgeFactor) || edgeFactor < 0 || edgeFactor > 1)
            throw new IllegalArgumentException("Invalid simulation inputs");
        Random random = new Random(seed); long[] finals = new long[paths]; int drawdowns = 0, breaches = 0;
        for (int i = 0; i < paths; i++) {
            long balance = openingCents, peak = balance; boolean dd = false, breach = false;
            for (Wager w : wagers == null ? List.<Wager>of() : wagers) {
                if (w == null || w.stakeCents() < 0 || w.stakeCents() > balance || w.decimalOdds() <= 1) continue;
                double p = w.marketProbability() + edgeFactor * (w.modelProbability() - w.marketProbability());
                p = Math.max(0, Math.min(1, p));
                balance += random.nextDouble() < p ? Math.round(w.stakeCents() * (w.decimalOdds() - 1)) : -w.stakeCents();
                peak = Math.max(peak, balance); dd |= peak > 0 && balance <= peak * .70; breach |= balance < reserveCents;
            }
            finals[i] = balance; if (dd) drawdowns++; if (breach) breaches++;
        }
        Arrays.sort(finals);
        return new Result(paths, finals[paths / 2], finals[(int) Math.floor((paths - 1) * .05)], (double) drawdowns / paths,
                (double) breaches / paths, "Conditional scenario: probability = market + " + edgeFactor + " × (model − market)");
    }
}
