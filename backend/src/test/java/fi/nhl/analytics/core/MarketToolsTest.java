package fi.nhl.analytics.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class MarketToolsTest {
    @Test void americanOddsConversion() {
        assertEquals(2.10, Odds.americanToDecimal(110), 1e-9);
        assertEquals(100.0 / 130 + 1, Odds.americanToDecimal(-130), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Odds.americanToDecimal(50));
        assertThrows(IllegalArgumentException.class, () -> Odds.toDecimal(1.0, false));
    }
    @Test void devigSumsToOneAndKeepsOrdering() {
        double[] p = Devig.power(new double[]{1.87, 2.0});
        assertEquals(1.0, p[0] + p[1], 1e-9); assertTrue(p[0] > p[1]);
        assertEquals(1.0, Arrays.stream(Devig.power(new double[]{2.4, 3.8, 3.0})).sum(), 1e-9);
        assertEquals((1 / 1.87) / (1 / 1.87 + 0.5), Devig.proportional(new double[]{1.87, 2.0})[0], 1e-12);
    }
    @Test void clvMath() {
        assertEquals(5.0, Clv.clvPct(2.10, 2.00), 1e-9);
        assertEquals(-4.0, Clv.clvPct(1.92, 2.00), 1e-9);
        assertEquals(5.0, Clv.closingEvPct(2.10, 0.5), 1e-9);
        Clv.Summary s = Clv.summarize(List.of(1.0, 2.0, 3.0));
        assertEquals(2.0, s.mean(), 1e-9); assertTrue(s.ciLo() < 2.0 && s.ciHi() > 2.0);
        assertEquals(0, Clv.summarize(List.of()).n());
    }
    @Test void csvParsing() {
        List<Map<String, String>> rows = Csv.parse("\uFEFFDate,Home Team,Away\r\n2025-10-08,\"Montreal, QC\",\"A \"\"x\"\"\"\r\n\r\n2025-10-09,B,C");
        assertEquals(2, rows.size());
        assertEquals("Montreal, QC", rows.get(0).get("home team")); assertEquals("A \"x\"", rows.get(0).get("away"));
    }
    @Test void teamCodesAndNames() {
        assertEquals("TBL", TeamCodes.normalize("T.B")); assertEquals("LAK", TeamCodes.normalize("L.A")); assertEquals("BOS", TeamCodes.normalize("BOS"));
        assertTrue(TeamNames.matches("NYRangers", "New York Rangers", "Rangers", "NYR"));
        assertTrue(TeamNames.matches("LA Kings", "Los Angeles Kings", "Kings", "LAK"));
        assertTrue(TeamNames.matches("Tampa Bay", "Tampa Bay Lightning", "Lightning", "TBL"));
        assertFalse(TeamNames.matches("Boston", "Montréal Canadiens", "Canadiens", "MTL"));
    }
    @Test void marketBacktestRunsAndReportsUncertainty() {
        Random r = new Random(3); String[] t = {"A", "B", "C", "D"}; List<GameResult> g = new ArrayList<>(); Map<String, MarketBacktest.Priced> close = new HashMap<>();
        LocalDate d = LocalDate.of(2025, 10, 1);
        for (int i = 0; i < 600; i++) {
            int x = r.nextInt(4), y = (x + 1 + r.nextInt(3)) % 4; double ph = x == 0 ? 0.60 : 0.50; boolean hw = r.nextDouble() < ph;
            GameResult gr = GameResult.fromFinal(d.plusDays(i / 4), t[x], t[y], hw ? 3 : 2, hw ? 2 : 3, "REG"); g.add(gr);
            close.put(MarketBacktest.key(gr.date(), gr.home(), gr.away()), new MarketBacktest.Priced(1 / (ph * 1.04), 1 / ((1 - ph) * 1.04)));
        }
        MarketBacktest.Report rep = MarketBacktest.run(g, close, new ScoreModel(10, 5), 100, 2.0);
        assertTrue(rep.evaluated() > 400);
        assertTrue(rep.modelBrier() > 0 && rep.marketBrier() > 0 && rep.brierDiffSe() > 0);
        assertEquals(0, MarketBacktest.run(g, Map.of(), new ScoreModel(10, 5), 100, 2.0).evaluated());
    }
}
