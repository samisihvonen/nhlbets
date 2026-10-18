package fi.nhl.analytics.core;

import java.util.*;

/** Deterministic, money-safe plan builder. All monetary values are integer cents. */
public final class Staking {
    private Staking() {}
    public record Candidate(String id, String gameId, double modelProbability, double marketProbability, double standardError,
                            double decimalOdds, boolean fresh, boolean experimental, String evidence) {}
    public record Item(String id, String gameId, long stakeCents, double probabilityUsed, double kellyFraction,
                       double minimumOdds, String reason) {}
    public record Plan(List<Item> items, long totalExposureCents, long reserveCents, long investableCents, String reason) {}

    public static Plan plan(long bankrollCents, List<Candidate> candidates, RiskLimits limits, boolean gatesPass, boolean liveMode) {
        if (bankrollCents < 0) throw new IllegalArgumentException("bankrollCents must be non-negative");
        if (!gatesPass) return zero(bankrollCents, candidates, limits, "Validation gates have not passed");
        long reserve = floor(bankrollCents * limits.reserveFraction());
        long investable = Math.max(0, bankrollCents - reserve);
        if (investable == 0) return zero(bankrollCents, candidates, limits, "Bankroll is at the protected reserve");
        Map<String, Candidate> bestByGame = new LinkedHashMap<>();
        for (Candidate c : candidates == null ? List.<Candidate>of() : candidates) {
            if (!eligible(c, limits, liveMode)) continue;
            Candidate old = bestByGame.get(c.gameId());
            if (old == null || score(c) > score(old)) bestByGame.put(c.gameId(), c);
        }
        List<Item> raw = new ArrayList<>();
        long perBetCap = floor(bankrollCents * limits.maxPerBetFraction());
        for (Candidate c : bestByGame.values()) {
            double p = Kelly.conservativeProbability(c.modelProbability(), c.marketProbability(), c.standardError(), limits.probabilityShrinkWeight());
            double fraction = Math.min(limits.maxPerBetFraction(), limits.kellyMultiplier() * Kelly.fraction(p, c.decimalOdds()));
            long stake = Math.min(perBetCap, floor(bankrollCents * fraction));
            raw.add(new Item(c.id(), c.gameId(), stake, p, fraction, Kelly.minimumOdds(p, limits.minEvFraction()), "Eligible"));
        }
        long cap = Math.min(investable, floor(bankrollCents * limits.maxDailyExposureFraction()));
        long total = raw.stream().mapToLong(Item::stakeCents).sum();
        List<Item> scaled = new ArrayList<>();
        for (Item i : raw) {
            long stake = total > cap && total > 0 ? floor(i.stakeCents() * (double) cap / total) : i.stakeCents();
            stake = roundDown(stake, limits.roundingIncrementCents());
            if (stake < limits.minimumStakeCents()) stake = 0;
            scaled.add(new Item(i.id(), i.gameId(), stake, i.probabilityUsed(), i.kellyFraction(), i.minimumOdds(), stake == 0 ? "Below minimum stake after limits" : "Eligible"));
        }
        long exposure = scaled.stream().mapToLong(Item::stakeCents).sum();
        return new Plan(List.copyOf(scaled), exposure, reserve, investable, exposure == 0 ? "No eligible stake after hard limits" : "Conditional hypothetical plan");
    }
    private static boolean eligible(Candidate c, RiskLimits l, boolean live) {
        if (c == null || !c.fresh() || (live && c.experimental()) || "WEAK".equals(c.evidence())) return false;
        double p = Kelly.conservativeProbability(c.modelProbability(), c.marketProbability(), c.standardError(), l.probabilityShrinkWeight());
        return Double.isFinite(p) && Double.isFinite(c.decimalOdds()) && c.decimalOdds() > 1 && p * c.decimalOdds() - 1 >= l.minEvFraction();
    }
    private static double score(Candidate c) { double p = Kelly.conservativeProbability(c.modelProbability(), c.marketProbability(), c.standardError(), .5); return p * c.decimalOdds() - 1; }
    private static Plan zero(long bankroll, List<Candidate> c, RiskLimits l, String why) {
        long reserve = floor(bankroll * l.reserveFraction()); List<Item> items = new ArrayList<>();
        for (Candidate x : c == null ? List.<Candidate>of() : c) items.add(new Item(x.id(), x.gameId(), 0, Double.NaN, 0, Double.POSITIVE_INFINITY, why));
        return new Plan(List.copyOf(items), 0, reserve, Math.max(0, bankroll - reserve), why);
    }
    private static long floor(double v) { return v <= 0 ? 0 : (long) Math.floor(v); }
    private static long roundDown(long cents, long increment) { return cents - cents % increment; }
}
