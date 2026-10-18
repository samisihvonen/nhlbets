package fi.nhl.analytics.core;

import java.util.*;

/**
 * Builds multi-leg combinations from single-selection legs of DIFFERENT games and quantifies how much (little) evidence there is.
 * Independence between games is assumed (unverified). Standard errors cover rating sampling error only, not model misspecification,
 * and the top-ranked combination of many is subject to selection bias (winner's curse).
 */
public final class Combos {
    private Combos() {}

    public record Leg(long gameId, String label, String market, double p, double odds, int nEff, Double marketProb, double evPct) {}

    public record Combo(List<Leg> legs, double jointProb, double jointSe, double combinedOdds, double fairOdds,
                        double evPct, double evSePct, double z, Double marketFairEvPct) {}

    public static String evidence(double z) { return z >= 1.64 ? "MODERATE" : z >= 1.0 ? "LOW" : "WEAK"; }

    public static List<Combo> build(List<Leg> legs, int maxLegs, int poolSize, int limit) {
        List<Leg> pool = new ArrayList<>();
        for (Leg l : legs) if (l.p() > 0 && l.p() < 1 && l.nEff() > 0 && MarketMath.validOdds(l.odds())) pool.add(l);
        pool.sort(Comparator.comparingDouble(Leg::evPct).reversed());
        if (pool.size() > poolSize) pool = new ArrayList<>(pool.subList(0, poolSize));
        List<Combo> out = new ArrayList<>();
        for (int size = 2; size <= maxLegs; size++) pick(pool, 0, size, new ArrayList<>(), out);
        out.sort(Comparator.comparingDouble(Combo::evPct).reversed());
        return out.subList(0, Math.min(limit, out.size()));
    }

    private static void pick(List<Leg> pool, int from, int size, List<Leg> cur, List<Combo> out) {
        if (cur.size() == size) { out.add(make(cur)); return; }
        for (int i = from; i < pool.size(); i++) {
            Leg l = pool.get(i);
            boolean sameGame = false;
            for (Leg c : cur) if (c.gameId() == l.gameId()) sameGame = true; // same-game legs are correlated: excluded
            if (sameGame) continue;
            cur.add(l); pick(pool, i + 1, size, cur, out); cur.remove(cur.size() - 1);
        }
    }

    static Combo make(List<Leg> legs) {
        double joint = 1, odds = 1, rel = 0; Double fairEv = 1.0;
        for (Leg l : legs) {
            joint *= l.p(); odds *= l.odds();
            double se = Math.sqrt(l.p() * (1 - l.p()) / l.nEff());
            rel += Math.pow(se / l.p(), 2);
            if (l.marketProb() == null || fairEv == null) fairEv = null; else fairEv *= l.marketProb() * l.odds();
        }
        double jointSe = joint * Math.sqrt(rel), ev = MarketMath.evPct(joint, odds), evSe = 100.0 * odds * jointSe;
        return new Combo(List.copyOf(legs), joint, jointSe, odds, 1.0 / joint, ev, evSe, evSe > 0 ? ev / evSe : 0,
                fairEv == null ? null : (fairEv - 1.0) * 100.0);
    }
}
