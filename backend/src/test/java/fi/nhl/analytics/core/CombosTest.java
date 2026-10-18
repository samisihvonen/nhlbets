package fi.nhl.analytics.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CombosTest {
    private static Combos.Leg leg(long game, double p, double odds, double mp) {
        return new Combos.Leg(game, "g" + game, "MONEYLINE", p, odds, 40, mp, MarketMath.evPct(p, odds));
    }
    @Test void jointOddsEvAndMarginCompounding() {
        List<Combos.Combo> r = Combos.build(List.of(leg(1, 0.55, 1.95, 0.5), leg(2, 0.60, 1.80, 0.54)), 2, 10, 5);
        assertEquals(1, r.size());
        Combos.Combo c = r.get(0);
        assertEquals(0.33, c.jointProb(), 1e-12); assertEquals(1.95 * 1.80, c.combinedOdds(), 1e-12);
        assertEquals(100 * (0.33 * 1.95 * 1.80 - 1), c.evPct(), 1e-9);
        assertEquals(100 * (0.5 * 1.95 * 0.54 * 1.80 - 1), c.marketFairEvPct(), 1e-9);
        assertTrue(c.marketFairEvPct() < 0);
        assertTrue(c.evSePct() > 0);
    }
    @Test void sameGameLegsAreNeverCombined() {
        assertTrue(Combos.build(List.of(leg(1, 0.55, 1.95, 0.5), leg(1, 0.30, 4.0, 0.2)), 3, 10, 5).isEmpty());
    }
    @Test void threeLegsAndInvalidLegsAreHandled() {
        List<Combos.Combo> r = Combos.build(List.of(leg(1, 0.55, 1.95, 0.5), leg(2, 0.6, 1.8, 0.54), leg(3, 0.52, 2.1, 0.47), leg(4, 0.0, 2.0, 0.5)), 3, 10, 20);
        assertEquals(4, r.size()); // 3 pairs + 1 triple; the p=0 leg is dropped
        assertEquals("WEAK", Combos.evidence(0.3)); assertEquals("MODERATE", Combos.evidence(2.0));
    }
}
