package fi.nhl.analytics.provider;

import com.fasterxml.jackson.databind.JsonNode;
import fi.nhl.analytics.domain.GameEntity;
import fi.nhl.analytics.domain.GameState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Official NHL web API (api-web.nhle.com). Field names follow the public schedule response; verify on first run. */
@Component
public class NhlClient {
    private static final Logger log = LoggerFactory.getLogger(NhlClient.class);
    private final RestClient http;

    public NhlClient() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(10000);
        http = RestClient.builder().baseUrl("https://api-web.nhle.com").requestFactory(f)
                .defaultHeader("User-Agent", "nhl-analytics/1.0 (research)").build();
    }

    /** @param dateOrNow "now" or yyyy-MM-dd; the API returns the week containing that date. */
    public List<GameEntity> fetchWeek(String dateOrNow) {
        JsonNode root = getWithRetry("/v1/schedule/" + dateOrNow);
        if (root == null || !root.has("gameWeek")) throw new IllegalStateException("Unexpected NHL schedule response (no gameWeek)");
        List<GameEntity> out = new ArrayList<>();
        for (JsonNode day : root.path("gameWeek")) for (JsonNode g : day.path("games")) {
            if (g.path("gameType").asInt() != 2) continue; // regular season only
            GameEntity e = new GameEntity();
            e.id = g.path("id").asLong();
            e.startUtc = Instant.parse(g.path("startTimeUTC").asText());
            JsonNode h = g.path("homeTeam"), a = g.path("awayTeam");
            e.homeAbbrev = h.path("abbrev").asText(); e.awayAbbrev = a.path("abbrev").asText();
            e.homeCommon = h.path("commonName").path("default").asText(e.homeAbbrev);
            e.awayCommon = a.path("commonName").path("default").asText(e.awayAbbrev);
            e.homeName = (h.path("placeName").path("default").asText("") + " " + e.homeCommon).trim();
            e.awayName = (a.path("placeName").path("default").asText("") + " " + e.awayCommon).trim();
            e.state = mapState(g.path("gameState").asText(), g.path("gameScheduleState").asText("OK"));
            if (h.has("score") && a.has("score")) { e.homeScore = h.path("score").asInt(); e.awayScore = a.path("score").asInt(); }
            JsonNode lpt = g.path("gameOutcome").path("lastPeriodType");
            e.lastPeriodType = lpt.isMissingNode() ? null : lpt.asText();
            e.updatedAt = Instant.now();
            out.add(e);
        }
        return out;
    }

    static GameState mapState(String state, String schedState) {
        if ("PPD".equals(schedState)) return GameState.POSTPONED;
        if ("CNCL".equals(schedState)) return GameState.CANCELLED;
        return switch (state) {
            case "FUT", "PRE" -> GameState.SCHEDULED;
            case "LIVE", "CRIT" -> GameState.LIVE;
            case "FINAL", "OFF" -> GameState.FINAL;
            default -> GameState.UNKNOWN;
        };
    }

    private JsonNode getWithRetry(String path) {
        RuntimeException last = null;
        for (int i = 0; i < 3; i++) {
            try { return http.get().uri(path).retrieve().body(JsonNode.class); }
            catch (RuntimeException ex) {
                last = ex; log.warn("NHL request {} failed (attempt {}): {}", path, i + 1, ex.getMessage());
                try { Thread.sleep(500L << i); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new IllegalStateException("NHL API unavailable: " + (last == null ? "unknown" : last.getClass().getSimpleName()));
    }
}
