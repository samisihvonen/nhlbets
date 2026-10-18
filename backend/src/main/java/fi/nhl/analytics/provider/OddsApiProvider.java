package fi.nhl.analytics.provider;

import com.fasterxml.jackson.databind.JsonNode;
import fi.nhl.analytics.core.MarketMath;
import fi.nhl.analytics.core.TeamNames;
import fi.nhl.analytics.domain.GameEntity;
import fi.nhl.analytics.domain.OddsEntity;
import fi.nhl.analytics.service.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The Odds API v4 (https://the-odds-api.com). Markets: h2h (moneyline) and h2h_3_way (60-minute 1X2).
 * Check each bookmaker's rules: whether "h2h" includes OT/SO is bookmaker/API defined.
 */
@Component
public class OddsApiProvider implements OddsProvider {
    private final AppProperties props;
    private final RestClient http;
    private volatile String quotaRemaining = "unknown", quotaUsed = "unknown";

    public OddsApiProvider(AppProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(10000);
        http = RestClient.builder().baseUrl("https://api.the-odds-api.com").requestFactory(f).build();
    }

    @Override public String id() { return "the-odds-api"; }

    private String key() { return props.oddsApiKey() == null ? "" : props.oddsApiKey().strip().replaceAll("^[\"']|[\"']$", ""); }

    /** Non-secret diagnostics so a wrong key can be spotted without printing it. */
    private String keyHint() {
        String k = key();
        return "key length " + k.length() + (k.matches("[0-9a-fA-F]{32}") ? ", format looks valid"
                : ", UNEXPECTED FORMAT (expected 32 hex characters: check for spaces, quotes, a # comment or a placeholder)");
    }

    @Override public Status status() {
        return key().isBlank()
                ? new Status("NOT_CONFIGURED", "ODDS_API_KEY is empty. Set the environment variable in the same terminal that starts the app, or put ODDS_API_KEY=... in a .env file in the project root or backend folder, then restart. Free key: https://the-odds-api.com")
                : new Status("CONFIGURED", "Key present (" + keyHint() + "); API credits remaining: " + quotaRemaining + ", used: " + quotaUsed);
    }

    @Override public Result fetch(List<GameEntity> upcoming) {
        List<OddsEntity> out = new ArrayList<>(); List<String> warn = new ArrayList<>();
        if (key().isBlank()) return new Result(out, List.of("the-odds-api: ODDS_API_KEY not configured"));
        String markets = props.oddsMarkets().replace(" ", "");
        ResponseEntity<JsonNode> resp;
        try { resp = call(markets); }
        catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 422 && markets.contains(",")) {
                warn.add("Markets '" + markets + "' rejected (HTTP 422: " + body(e) + "); retried with h2h only");
                try { resp = call("h2h"); } catch (HttpClientErrorException e2) { throw fail(e2); }
            } else throw fail(e);
        }
        String rem = resp.getHeaders().getFirst("x-requests-remaining"), used = resp.getHeaders().getFirst("x-requests-used");
        if (rem != null) quotaRemaining = rem;
        if (used != null) quotaUsed = used;
        JsonNode events = resp.getBody();
        if (events == null || !events.isArray()) throw new IllegalStateException("Unexpected odds API response");
        Instant now = Instant.now();
        for (JsonNode ev : events) {
            Instant start = Instant.parse(ev.path("commence_time").asText());
            List<GameEntity> cands = upcoming.stream()
                    .filter(g -> Duration.between(g.startUtc, start).abs().toHours() < 6)
                    .filter(g -> TeamNames.matches(ev.path("home_team").asText(), g.homeName, g.homeCommon)
                            && TeamNames.matches(ev.path("away_team").asText(), g.awayName, g.awayCommon)).toList();
            if (cands.size() != 1) {
                warn.add("Skipped " + ev.path("away_team").asText() + " @ " + ev.path("home_team").asText()
                        + (cands.isEmpty() ? ": no matching NHL fixture" : ": ambiguous match"));
                continue;
            }
            GameEntity g = cands.get(0);
            for (JsonNode bk : ev.path("bookmakers")) for (JsonNode mk : bk.path("markets")) {
                String key = mk.path("key").asText();
                boolean hasDraw = false;
                for (JsonNode o : mk.path("outcomes")) if ("Draw".equalsIgnoreCase(o.path("name").asText())) hasDraw = true;
                // A three-way market (with Draw) is a 60-minute result, never a moneyline: keep the markets separate.
                String market = "h2h_3_way".equals(key) || ("h2h".equals(key) && hasDraw) ? "REG_1X2" : "h2h".equals(key) ? "MONEYLINE" : null;
                if (market == null) continue;
                for (JsonNode o : mk.path("outcomes")) {
                    double price = o.path("price").asDouble(0);
                    if (!MarketMath.validOdds(price)) continue;
                    String name = o.path("name").asText(), sel;
                    if ("Draw".equalsIgnoreCase(name)) sel = "DRAW";
                    else if (TeamNames.matches(name, g.homeName, g.homeCommon)) sel = "HOME";
                    else if (TeamNames.matches(name, g.awayName, g.awayCommon)) sel = "AWAY";
                    else continue;
                    OddsEntity e = new OddsEntity();
                    e.gameId = g.id; e.provider = "the-odds-api/" + bk.path("key").asText(); e.market = market;
                    e.selection = sel; e.decimalOdds = price; e.retrievedAt = now;
                    String lu = mk.path("last_update").asText("");
                    e.marketTimestamp = lu.isEmpty() ? null : Instant.parse(lu);
                    e.sourceStatus = "OK";
                    out.add(e);
                }
            }
        }
        return new Result(out, warn);
    }

    private ResponseEntity<JsonNode> call(String markets) {
        return http.get().uri(u -> u.path("/v4/sports/icehockey_nhl/odds")
                .queryParam("regions", props.oddsRegions()).queryParam("markets", markets)
                .queryParam("oddsFormat", "decimal").queryParam("apiKey", key()).build())
                .retrieve().toEntity(JsonNode.class);
    }

    /** Response body of the API error (never contains the key; defensively masked). */
    private String body(HttpClientErrorException e) {
        String b = e.getResponseBodyAsString();
        if (b == null || b.isBlank()) b = e.getStatusText();
        b = b.replace(key(), "***");
        return b.length() > 300 ? b.substring(0, 300) + "..." : b;
    }

    private IllegalStateException fail(HttpClientErrorException e) {
        String hint = e.getStatusCode().value() == 401
                ? " | Key rejected (" + keyHint() + "). An ODDS_API_KEY set in the terminal, system or IDE run configuration overrides the .env file; "
                  + "remove it or make sure it holds the same valid key, then restart."
                : "";
        return new IllegalStateException("the-odds-api HTTP " + e.getStatusCode().value() + ": " + body(e) + hint);
    }
}
