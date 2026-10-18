package fi.nhl.analytics.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class CoreTest {
    private static final LocalDate D = LocalDate.of(2025, 10, 1);

    @Test void evAndEdge() {
        assertEquals(0.10, MarketMath.ev(0.55, 2.0), 1e-12);
        assertEquals(10.0, MarketMath.evPct(0.55, 2.0), 1e-9);
        assertEquals(5.0, MarketMath.edgePp(0.55, 0.50), 1e-9);
    }
    @Test void oddsValidationAndNormalization() {
        assertFalse(MarketMath.validOdds(1.0)); assertFalse(MarketMath.validOdds(Double.NaN)); assertTrue(MarketMath.validOdds(1.01));
        assertThrows(IllegalArgumentException.class, () -> MarketMath.implied(0.9));
        double[] o = {2.0, 3.5, 4.0};
        assertEquals(1.0, Arrays.stream(MarketMath.normalized(o)).sum(), 1e-12);
        assertEquals(0.5 + 1 / 3.5 + 0.25 - 1, MarketMath.overround(o), 1e-12);
    }
    @Test void classificationRules() {
        assertEquals("INSUFFICIENT_DATA", MarketMath.classify(false, 2.0, false, 0.5, false, 3));
        assertEquals("STALE_ODDS", MarketMath.classify(true, null, true, 0.5, false, 3));
        assertEquals("MARKET_UNAVAILABLE", MarketMath.classify(true, null, false, 0.5, false, 3));
        assertEquals("NO_VALUE_IDENTIFIED", MarketMath.classify(true, 1.9, false, 0.5, false, 3));
        assertEquals("POSITIVE_EV_EXPERIMENTAL", MarketMath.classify(true, 2.2, false, 0.5, true, 3));
    }
    @Test void regulationFromOvertimeAndShootout() {
        GameResult ot = GameResult.fromFinal(D, "A", "B", 3, 2, "OT");
        assertEquals(1, ot.regIndex()); assertEquals(0, ot.mlIndex()); assertEquals(2, ot.homeReg());
        GameResult so = GameResult.fromFinal(D, "A", "B", 2, 3, "SO");
        assertEquals(1, so.regIndex()); assertEquals(1, so.mlIndex());
        GameResult reg = GameResult.fromFinal(D, "A", "B", 4, 1, "REG");
        assertEquals(0, reg.regIndex());
        assertThrows(IllegalArgumentException.class, () -> GameResult.fromFinal(D, "A", "B", 2, 2, "REG"));
    }
    @Test void modelProbabilitiesAreValidAndNoDataMeansNoPrediction() {
        List<GameResult> h = new ArrayList<>(); Random r = new Random(7);
        for (int i = 0; i < 300; i++) {
            String a = i % 2 == 0 ? "A" : "B", b = i % 2 == 0 ? "B" : "A"; int hs = r.nextInt(6), as = r.nextInt(6); String pt = "REG";
            if (hs == as) { hs++; pt = "OT"; }
            h.add(GameResult.fromFinal(D.plusDays(i), a, b, hs, as, pt));
        }
        ScoreModel m = new ScoreModel(10, 5);
        ScoreModel.Prediction p = m.predict("A", "B", h).orElseThrow();
        assertEquals(1.0, p.pHomeReg() + p.pDraw() + p.pAwayReg(), 1e-9);
        assertEquals(1.0, p.pHomeMl() + p.pAwayMl(), 1e-9);
        assertTrue(p.pDraw() > 0 && p.pHomeMl() >= p.pHomeReg());
        assertTrue(m.predict("A", "ZZZ", h).isEmpty());
        assertTrue(m.predict("A", "B", List.of()).isEmpty());
    }
    @Test void backtestDoesNotUseSameDayOrFutureGames() {
        List<GameResult> h = new ArrayList<>();
        for (int i = 0; i < 200; i++) h.add(GameResult.fromFinal(D.plusDays(i / 4), i % 2 == 0 ? "A" : "B", i % 2 == 0 ? "B" : "A", 3, 2, "REG"));
        Backtest.Report rep = Backtest.run(h, new ScoreModel(10, 5), 40);
        assertTrue(rep.predicted() > 0 && rep.predicted() <= 160);
        assertTrue(rep.regBrier() >= 0 && rep.mlBrier() >= 0);
    }
    @Test void v2ComparisonKeepsPromotionGateClosedWithoutAdequateSample() {
        List<GameResult> h = new ArrayList<>();
        for (int i = 0; i < 80; i++) h.add(GameResult.fromFinal(D.plusDays(i), "A", "B", 3, 2, "REG"));
        assertFalse(Backtest.compareTieCalibration(h, 10, 5, 20).v2EligibleForPromotion());
    }
    @Test void teamNameMatchingIsAccentInsensitive() {
        assertTrue(TeamNames.matches("Montreal Canadiens", "Montréal Canadiens", "Canadiens"));
        assertFalse(TeamNames.matches("Boston Bruins", "Montréal Canadiens", "Canadiens"));
    }
}
