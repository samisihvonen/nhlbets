package fi.nhl.analytics.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Season-level team xG summary imported from a CSV (e.g. MoneyPuck). Context only: NOT point-in-time, so never used in backtests. */
@Entity @Table(name = "team_xg")
public class TeamXgEntity {
    @Id public String id; // season:team
    public int season;
    public String team;
    public int games;
    public double xgf, xga, gf, ga;
    public String source;
    public Instant importedAt;
}
