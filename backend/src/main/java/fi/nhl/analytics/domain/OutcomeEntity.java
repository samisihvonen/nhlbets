package fi.nhl.analytics.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "prediction_outcomes", uniqueConstraints = {
        @UniqueConstraint(columnNames = "predictionId"), @UniqueConstraint(columnNames = "gameId")})
public class OutcomeEntity {
    @Id @GeneratedValue public Long id;
    public long predictionId, gameId;
    /** SETTLED or VOID. */
    public String status;
    public Integer regActual, mlActual, regPick, mlPick;
    public Boolean regHit, mlHit;
    public Double brierReg, loglossReg, brierMl, loglossMl;
    public double pHomeReg, pDraw, pAwayReg, pHomeMl;
    public Instant settledAt;
}
