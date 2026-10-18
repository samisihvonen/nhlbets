package fi.nhl.analytics.provider;

import fi.nhl.analytics.domain.GameEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Veikkaus and Paf: no documented, permitted public odds API has been identified, and this project does not scrape or
 * bypass access controls. These providers report their real status and return no data.
 */
public final class UnavailableProviders {
    private UnavailableProviders() {}

    static class Base implements OddsProvider {
        private final String id;
        Base(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public Status status() {
            return new Status("NOT_INTEGRATED", "No documented public odds API permitted for this use; provider disabled");
        }
        @Override public Result fetch(List<GameEntity> upcoming) { return new Result(List.of(), List.of(id + ": not integrated")); }
    }

    @Component public static class Veikkaus extends Base { public Veikkaus() { super("veikkaus"); } }
    @Component public static class Paf extends Base { public Paf() { super("paf"); } }
}
