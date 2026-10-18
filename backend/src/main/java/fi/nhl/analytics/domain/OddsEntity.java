package fi.nhl.analytics.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "odds_observations", indexes = @Index(columnList = "gameId,retrievedAt"))
public class OddsEntity {
    @Id @GeneratedValue public Long id;
    public long gameId;
    public String provider;
    /** MONEYLINE (incl. OT/SO) or REG_1X2 (60 minutes). */
    public String market;
    /** HOME, DRAW or AWAY. */
    public String selection;
    public double decimalOdds;
    public Instant retrievedAt, marketTimestamp;
    public String sourceStatus;
}
