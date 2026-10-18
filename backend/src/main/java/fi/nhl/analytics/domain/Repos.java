package fi.nhl.analytics.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public final class Repos {
    private Repos() {}
    public interface GameRepo extends JpaRepository<GameEntity, Long> {
        List<GameEntity> findByStateOrderByStartUtc(GameState s);
        List<GameEntity> findByStartUtcBetweenOrderByStartUtc(Instant a, Instant b);
    }
    public interface OddsRepo extends JpaRepository<OddsEntity, Long> {
        List<OddsEntity> findByGameIdAndRetrievedAtAfter(long gameId, Instant after);
        List<OddsEntity> findByGameId(long gameId);
        List<OddsEntity> findBySourceStatus(String sourceStatus);
        void deleteByGameIdAndProviderAndSourceStatus(long gameId, String provider, String sourceStatus);
    }
    public interface PredictionRepo extends JpaRepository<PredictionEntity, Long> {
        List<PredictionEntity> findByGameIdOrderByCreatedAtDesc(long gameId);
    }
    public interface TeamXgRepo extends JpaRepository<TeamXgEntity, String> {}
    public interface OutcomeRepo extends JpaRepository<OutcomeEntity, Long> {
        boolean existsByPredictionId(long predictionId);
        java.util.Optional<OutcomeEntity> findByGameId(long gameId);
    }
    public interface BankrollSettingsRepo extends JpaRepository<BankrollSettingsEntity, Long> {}
    public interface BankrollLedgerRepo extends JpaRepository<BankrollLedgerEntity, Long> {
        List<BankrollLedgerEntity> findAllByOrderByCreatedAtDesc();
    }
    public interface BacktestRunRepo extends JpaRepository<BacktestRunEntity, Long> {
        List<BacktestRunEntity> findTop50ByOrderByCreatedAtDesc();
    }
}
