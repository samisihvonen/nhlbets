package fi.nhl.analytics.service;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "analytics")
public record AppProperties(
        @DefaultValue("") String oddsApiKey,
        @DefaultValue("eu") String oddsRegions,
        @DefaultValue("true") boolean schedulerEnabled,
        @DefaultValue("30") int refreshMinutes,
        @DefaultValue("60") int oddsMaxAgeMinutes,
        @DefaultValue("8") int minGamesPerTeam,
        @DefaultValue("3.0") double minEvPct,
        @DefaultValue("300") int minSettledForValidation,
        @DefaultValue("2025-10-01") LocalDate backfillFrom,
        @DefaultValue("240") int oddsMinIntervalMinutes,
        @DefaultValue("false") boolean closingCapture,
        @DefaultValue("pinnacle") String clvReferenceProvider,
        @DefaultValue("h2h") String oddsMarkets,
        @DefaultValue("") String oddspapiKey,
        @DefaultValue("") String oddspapiBookmakers) {}
