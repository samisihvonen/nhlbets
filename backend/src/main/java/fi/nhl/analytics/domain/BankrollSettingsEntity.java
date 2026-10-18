package fi.nhl.analytics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class BankrollSettingsEntity {
    @Id public Long id = 1L;
    public String mode = "PAPER";
    public double reserveFraction = .30, maxPerBetFraction = .01, maxDailyExposureFraction = .03;
    public double kellyMultiplier = .25, probabilityShrinkWeight = .50, minEvFraction = .03;
    public long minimumStakeCents = 200, roundingIncrementCents = 100;
}
