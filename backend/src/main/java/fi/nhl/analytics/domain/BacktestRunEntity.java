package fi.nhl.analytics.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only audit record for a completed model-only walk-forward run. */
@Entity @Table(name = "backtest_runs")
public class BacktestRunEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Instant createdAt;
    public String modelVersion;
    public int warmup, games, predicted;
    public Double regBrier, regLogLoss, mlBrier, mlLogLoss;
    @Column(length = 1000) public String note;
}
