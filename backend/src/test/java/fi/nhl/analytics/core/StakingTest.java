package fi.nhl.analytics.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class StakingTest {
    private final RiskLimits limits = RiskLimits.defaults();
    private Staking.Candidate c(double p, double market, double se, double odds) { return new Staking.Candidate("a", "game", p, market, se, odds, true, false, "MODERATE"); }
    @Test void conservativeProbabilityAndKellyAreCapped() {
        assertEquals(.55, Kelly.conservativeProbability(.60, .50, .05, .5), 1e-9);
        assertEquals(.10, Kelly.fraction(.55, 2), 1e-9);
        assertEquals(0, Kelly.fraction(.4, 2), 1e-9);
    }
    @Test void gatesAndWeakEvidenceMeanZero() {
        assertEquals(0, Staking.plan(100_000, List.of(c(.70, .5, .01, 2)), limits, false, false).totalExposureCents());
        var weak = new Staking.Candidate("a", "game", .70, .5, .01, 2, true, false, "WEAK");
        assertEquals(0, Staking.plan(100_000, List.of(weak), limits, true, false).totalExposureCents());
    }
    @Test void dailyAndReserveCapsAndRoundingHold() {
        var p = Staking.plan(100_000, List.of(c(.80,.50,.01,2), new Staking.Candidate("b","g2",.80,.50,.01,2,true,false,"MODERATE"), new Staking.Candidate("c","g3",.80,.50,.01,2,true,false,"MODERATE")), limits, true, false);
        assertTrue(p.totalExposureCents() <= 3_000); assertTrue(p.totalExposureCents() <= p.investableCents());
        assertTrue(p.items().stream().allMatch(i -> i.stakeCents() % 100 == 0));
    }
    @Test void onlyBestSelectionPerGameRemains() {
        var worse = new Staking.Candidate("b", "game", .55, .5, .01, 2, true, false, "MODERATE");
        assertEquals(1, Staking.plan(100_000, List.of(c(.70,.5,.01,2), worse), limits, true, false).items().size());
    }
    @Test void simulationIsDeterministicWithSeed() {
        var wagers = List.of(new BankrollSim.Wager(1_000, .60, .50, 2.0));
        assertEquals(BankrollSim.simulate(100_000, 30_000, wagers, 1, 1000, 42),
                BankrollSim.simulate(100_000, 30_000, wagers, 1, 1000, 42));
    }
}
