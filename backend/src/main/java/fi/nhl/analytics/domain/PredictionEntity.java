package fi.nhl.analytics.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Immutable snapshot: never updated after insert. */
@Entity @Table(name = "predictions", indexes = @Index(columnList = "gameId,createdAt"))
public class PredictionEntity {
    @Id @GeneratedValue public Long id;
    public long gameId;
    public Instant createdAt;
    public String modelVersion;
    public double pHomeReg, pDraw, pAwayReg, pHomeMl, pAwayMl, lambdaHome, lambdaAway;
    public int nHome, nAway;
    public Double oddsHomeReg, oddsDraw, oddsAwayReg, oddsHomeMl, oddsAwayMl;
    public String oddsProviders;
    public String dataQuality;
}
