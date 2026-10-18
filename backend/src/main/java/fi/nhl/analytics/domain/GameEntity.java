package fi.nhl.analytics.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "games", indexes = @Index(columnList = "startUtc"))
public class GameEntity {
    @Id public long id;
    public Instant startUtc;
    public String homeAbbrev, awayAbbrev, homeName, awayName, homeCommon, awayCommon;
    @Enumerated(EnumType.STRING) public GameState state;
    public Integer homeScore, awayScore;
    public String lastPeriodType;
    public Instant updatedAt;
}
