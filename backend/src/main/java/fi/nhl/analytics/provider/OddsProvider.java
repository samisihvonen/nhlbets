package fi.nhl.analytics.provider;

import fi.nhl.analytics.domain.GameEntity;
import fi.nhl.analytics.domain.OddsEntity;
import java.util.List;

public interface OddsProvider {
    String id();
    /** OPERATIONAL, NOT_CONFIGURED or NOT_INTEGRATED, with a human-readable reason. */
    Status status();
    /** Fetch odds and match them to the given upcoming games. Never returns invented data. */
    Result fetch(List<GameEntity> upcoming);

    record Status(String state, String detail) {}
    record Result(List<OddsEntity> observations, List<String> warnings) {}
}
