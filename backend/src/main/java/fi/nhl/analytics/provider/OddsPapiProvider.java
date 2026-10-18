package fi.nhl.analytics.provider;

import com.fasterxml.jackson.databind.JsonNode;
import fi.nhl.analytics.core.MarketMath;
import fi.nhl.analytics.core.TeamNames;
import fi.nhl.analytics.domain.GameEntity;
import fi.nhl.analytics.domain.OddsEntity;
import fi.nhl.analytics.service.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * OddsPapi (https://oddspapi.io), an odds aggregator with a free developer tier. Schema taken from the vendor's own NHL tutorial
 * (GET /v4/fixtures, GET /v4/odds?fixtureId=...; market "151" = Winner incl. overtime and penalties, outcome 151 = participant 1,
 * 152 = participant 2). Not yet verified against a live response: report any mismatch from the refresh report.
 * Only the two-way moneyline is read; regulation 1X2 is not requested. Orientation is derived from team names, never assumed.
 */
@Component
public class OddsPapiProvider implements OddsProvider {
    private static final String MONEYLINE_MARKET = "151";
    private static final long WINDOW_HOURS = 48; // only fetch odds for games starting soon: saves quota
    private final AppProperties props;
    private final RestClient http;
    private volatile String seenBookmakers = "none yet";

    public OddsPapiProvider(AppProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(15000);
        http = RestClient.builder().baseUrl("https://api.oddspapi.io").requestFactory(f).build();
    }

    @Override public String id() { return "oddspapi"; }

    private String key() { return props.oddspapiKey() == null ? "" : props.oddspapiKey().strip().replaceAll("^[\"']|[\"']$", ""); }

    @Override public Status status() {
        return key().isBlank()
                ? new Status("NOT_CONFIGURED", "Set ODDSPAPI_KEY (free developer key from oddspapi.io) in .env and restart")
                : new Status("CONFIGURED", "Key present (length " + key().length() + "); NHL moneyline only; bookmakers seen on last fetch: " + seenBookmakers);
    }

    private Set<String> allowedBooks() {
        return Arrays.stream(props.oddspapiBookmakers().split(",")).map(String::trim).map(String::toLowerCase)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Override public Result fetch(List<GameEntity> upcoming) {
        List<OddsEntity> out = new ArrayList<>(); List<String> warn = new ArrayList<>();
        if (key().isBlank()) return new Result(out, List.of("oddspapi: ODDSPAPI_KEY not configured"));
        Instant now = Instant.now();
        List<GameEntity> soon = upcoming.stream().filter(g -> g.startUtc.isBefore(now.plus(Duration.ofHours(WINDOW_HOURS)))).toList();
        if (soon.isEmpty()) return new Result(out, List.of("oddspapi: no games starting within " + WINDOW_HOURS + " h"));
        LocalDate from = LocalDate.now(ZoneOffset.UTC);
        JsonNode fixtures = get("/v4/fixtures", Map.of("sportId", "15", "from", from.toString(), "to", from.plusDays(3).toString()));
        if (fixtures == null || !fixtures.isArray()) throw new IllegalStateException("Unexpected OddsPapi fixtures response (expected a JSON array)");
        Set<String> allowed = allowedBooks(), seen = new TreeSet<>();
        for (JsonNode f : fixtures) {
            if (!"NHL".equals(f.path("tournamentName").asText()) || !f.path("hasOdds").asBoolean(false)) continue;
            String n1 = f.path("participant1Name").asText(), n2 = f.path("participant2Name").asText();
            Instant start = parse(f.path("startTime").asText());
            if (start == null) continue;
            List<GameEntity> cands = soon.stream().filter(g -> Duration.between(g.startUtc, start).abs().toHours() < 6)
                    .filter(g -> (TeamNames.matches(n1, g.homeName, g.homeCommon) && TeamNames.matches(n2, g.awayName, g.awayCommon))
                            || (TeamNames.matches(n1, g.awayName, g.awayCommon) && TeamNames.matches(n2, g.homeName, g.homeCommon))).toList();
            if (cands.size() != 1) { warn.add("oddspapi: skipped " + n1 + " vs " + n2 + (cands.isEmpty() ? " (no matching fixture)" : " (ambiguous)")); continue; }
            GameEntity g = cands.get(0);
            boolean p1IsHome = TeamNames.matches(n1, g.homeName, g.homeCommon);
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } // vendor documents ~0.88 s cooldown
            JsonNode odds = get("/v4/odds", Map.of("fixtureId", f.path("fixtureId").asText()));
            JsonNode books = odds == null ? null : odds.path("bookmakerOdds");
            if (books == null || !books.isObject()) { warn.add("oddspapi: no bookmakerOdds for " + n1 + " vs " + n2); continue; }
            for (Iterator<String> it = books.fieldNames(); it.hasNext(); ) {
                String slug = it.next(); seen.add(slug);
                if (!allowed.isEmpty() && !allowed.contains(slug.toLowerCase())) continue;
                JsonNode outs = books.path(slug).path("markets").path(MONEYLINE_MARKET).path("outcomes");
                Double o1 = price(outs.path("151")), o2 = price(outs.path("152"));
                if (o1 == null || o2 == null) continue; // a complete two-way set is required to remove the margin
                out.add(obs(g, slug, p1IsHome ? "HOME" : "AWAY", o1, now));
                out.add(obs(g, slug, p1IsHome ? "AWAY" : "HOME", o2, now));
            }
        }
        seenBookmakers = seen.isEmpty() ? "none" : (String.join(", ", seen).length() > 400 ? String.join(", ", seen).substring(0, 400) + "..." : String.join(", ", seen));
        if (out.isEmpty()) warn.add("oddspapi: no NHL moneyline prices matched (market " + MONEYLINE_MARKET + ")");
        return new Result(out, warn);
    }

    private static Instant parse(String s) { try { return Instant.parse(s); } catch (RuntimeException e) { return null; } }

    /** Suspended (inactive) or invalid prices are ignored. */
    private static Double price(JsonNode outcome) {
        JsonNode pl = outcome.path("players").path("0");
        if (!pl.path("active").asBoolean(true)) return null;
        double p = pl.path("price").asDouble(0);
        return MarketMath.validOdds(p) ? p : null;
    }

    private static OddsEntity obs(GameEntity g, String slug, String sel, double price, Instant at) {
        OddsEntity e = new OddsEntity();
        e.gameId = g.id; e.provider = "oddspapi/" + slug; e.market = "MONEYLINE"; e.selection = sel;
        e.decimalOdds = price; e.retrievedAt = at; e.marketTimestamp = null; e.sourceStatus = "OK";
        return e;
    }

    private JsonNode get(String path, Map<String, String> params) {
        try {
            return http.get().uri(u -> { u.path(path).queryParam("apiKey", key()); for (var e : params.entrySet()) u.queryParam(e.getKey(), e.getValue()); return u.build(); })
                    .retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            String b = e.getResponseBodyAsString();
            if (b == null || b.isBlank()) b = e.getStatusText();
            b = b.replace(key(), "***");
            throw new IllegalStateException("oddspapi HTTP " + e.getStatusCode().value() + ": " + (b.length() > 300 ? b.substring(0, 300) + "..." : b));
        }
    }
}
