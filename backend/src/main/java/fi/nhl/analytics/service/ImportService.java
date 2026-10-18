package fi.nhl.analytics.service;

import fi.nhl.analytics.core.Csv;
import fi.nhl.analytics.core.MarketMath;
import fi.nhl.analytics.core.Odds;
import fi.nhl.analytics.core.TeamCodes;
import fi.nhl.analytics.core.TeamNames;
import fi.nhl.analytics.domain.*;
import fi.nhl.analytics.domain.Repos.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ImportService {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private final GameRepo games; private final OddsRepo odds; private final TeamXgRepo xg; private final TransactionTemplate tx;

    public ImportService(GameRepo g, OddsRepo o, TeamXgRepo x, TransactionTemplate tx) { this.games = g; this.odds = o; this.xg = x; this.tx = tx; }

    private static String pick(Map<String, String> row, String... names) { for (String n : names) if (row.containsKey(n)) return n; return null; }

    /**
     * Imports historical moneyline prices (e.g. a Kaggle/SBR export) into the odds table.
     * Required columns: date (yyyy-MM-dd), home team, away team, home/away closing price. Optional: opening prices.
     * Accepted headers: date | home_team/home | away_team/away | home_ml_close/home_close | away_ml_close/away_close | home_ml_open/home_open | away_ml_open/away_open.
     */
    public Map<String, Object> importOdds(String csv, boolean american, String source) {
        String src = source == null ? "" : source.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if (src.isEmpty()) throw new IllegalArgumentException("source must contain letters or digits");
        String provider = "import:" + src;
        List<Map<String, String>> rows = Csv.parse(csv);
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV contains no data rows");
        Map<String, String> h = rows.get(0);
        String cDate = pick(h, "date"), cHome = pick(h, "home_team", "home", "hometeam"), cAway = pick(h, "away_team", "away", "awayteam"),
                cHc = pick(h, "home_ml_close", "home_close", "home_odds_close"), cAc = pick(h, "away_ml_close", "away_close", "away_odds_close"),
                cHo = pick(h, "home_ml_open", "home_open", "home_odds_open"), cAo = pick(h, "away_ml_open", "away_open", "away_odds_open");
        List<String> missing = new ArrayList<>();
        if (cDate == null) missing.add("date"); if (cHome == null) missing.add("home_team"); if (cAway == null) missing.add("away_team");
        if (cHc == null) missing.add("home_ml_close"); if (cAc == null) missing.add("away_ml_close");
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing required columns " + missing + "; found " + h.keySet());

        List<GameEntity> all = games.findAll();
        int matched = 0, unmatched = 0, ambiguous = 0, bad = 0; List<String> sample = new ArrayList<>(); List<OddsEntity> toSave = new ArrayList<>();
        Set<String> deleteKeys = new HashSet<>();
        for (Map<String, String> r : rows) {
            LocalDate d; double hc, ac; Double ho = null, ao = null;
            try {
                d = LocalDate.parse(r.get(cDate));
                hc = Odds.toDecimal(Double.parseDouble(r.get(cHc)), american); ac = Odds.toDecimal(Double.parseDouble(r.get(cAc)), american);
                if (cHo != null && cAo != null && !r.get(cHo).isBlank() && !r.get(cAo).isBlank()) {
                    ho = Odds.toDecimal(Double.parseDouble(r.get(cHo)), american); ao = Odds.toDecimal(Double.parseDouble(r.get(cAo)), american);
                }
            } catch (RuntimeException e) { bad++; continue; }
            String hn = r.get(cHome), an = r.get(cAway);
            List<GameEntity> c = all.stream().filter(g -> Math.abs(ChronoUnit.DAYS.between(g.startUtc.atZone(ET).toLocalDate(), d)) <= 1)
                    .filter(g -> TeamNames.matches(hn, g.homeName, g.homeCommon, g.homeAbbrev) && TeamNames.matches(an, g.awayName, g.awayCommon, g.awayAbbrev)).toList();
            if (c.size() > 1) { List<GameEntity> exact = c.stream().filter(g -> g.startUtc.atZone(ET).toLocalDate().equals(d)).toList(); if (exact.size() == 1) c = exact; }
            if (c.isEmpty()) { unmatched++; if (sample.size() < 10) sample.add(d + " " + an + " @ " + hn + ": no matching game in database (run a refresh/backfill first?)"); continue; }
            if (c.size() > 1) { ambiguous++; if (sample.size() < 10) sample.add(d + " " + an + " @ " + hn + ": ambiguous"); continue; }
            GameEntity g = c.get(0); matched++;
            toSave.add(obs(g, provider, "HOME", hc, g.startUtc.minusSeconds(60), "HISTORICAL_CLOSE"));
            toSave.add(obs(g, provider, "AWAY", ac, g.startUtc.minusSeconds(60), "HISTORICAL_CLOSE"));
            deleteKeys.add(g.id + "|HISTORICAL_CLOSE");
            if (ho != null) {
                toSave.add(obs(g, provider, "HOME", ho, g.startUtc.minus(Duration.ofHours(24)), "HISTORICAL_OPEN"));
                toSave.add(obs(g, provider, "AWAY", ao, g.startUtc.minus(Duration.ofHours(24)), "HISTORICAL_OPEN"));
                deleteKeys.add(g.id + "|HISTORICAL_OPEN");
            }
        }
        tx.executeWithoutResult(t -> {
            for (String k : deleteKeys) { String[] p = k.split("\\|"); odds.deleteByGameIdAndProviderAndSourceStatus(Long.parseLong(p[0]), provider, p[1]); }
            odds.saveAll(toSave);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider); out.put("rows", rows.size()); out.put("matched", matched); out.put("unmatched", unmatched);
        out.put("ambiguous", ambiguous); out.put("invalidRows", bad); out.put("observationsStored", toSave.size()); out.put("examples", sample);
        out.put("note", "Format assumed: " + (american ? "American" : "decimal") + ". Closing prices are stamped 1 min before start; opening prices 24 h before start "
                + "(timestamp is an approximation). Only games already in the database can be matched. Check the dataset's licence/terms before use.");
        return out;
    }

    private static OddsEntity obs(GameEntity g, String provider, String sel, double price, Instant at, String status) {
        OddsEntity e = new OddsEntity(); e.gameId = g.id; e.provider = provider; e.market = "MONEYLINE"; e.selection = sel;
        e.decimalOdds = price; e.retrievedAt = at; e.marketTimestamp = null; e.sourceStatus = status; return e;
    }

    /** Imports a MoneyPuck-style team CSV (columns: team, season, situation, games_played, xGoalsFor, xGoalsAgainst, goalsFor, goalsAgainst). */
    public Map<String, Object> importXg(String csv, Integer seasonParam, String source) {
        List<Map<String, String>> rows = Csv.parse(csv);
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV contains no data rows");
        Map<String, String> h = rows.get(0);
        List<String> missing = new ArrayList<>();
        for (String c : List.of("team", "games_played", "xgoalsfor", "xgoalsagainst", "goalsfor", "goalsagainst")) if (!h.containsKey(c)) missing.add(c);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing required columns " + missing + "; found " + h.keySet());
        if (!h.containsKey("season") && seasonParam == null) throw new IllegalArgumentException("CSV has no season column; pass season=YYYY (start year)");
        Set<String> known = new HashSet<>(); games.findAll().forEach(g -> { known.add(g.homeAbbrev); known.add(g.awayAbbrev); });
        List<TeamXgEntity> out = new ArrayList<>(); Set<String> unknown = new TreeSet<>(); int skipped = 0;
        for (Map<String, String> r : rows) {
            if (h.containsKey("situation") && !"all".equalsIgnoreCase(r.get("situation"))) continue;
            try {
                String team = TeamCodes.normalize(r.get("team"));
                if (!known.isEmpty() && !known.contains(team)) { unknown.add(r.get("team")); continue; }
                TeamXgEntity e = new TeamXgEntity();
                e.season = h.containsKey("season") && !r.get("season").isBlank() ? Integer.parseInt(r.get("season")) : seasonParam;
                e.team = team; e.id = e.season + ":" + team; e.games = Integer.parseInt(r.get("games_played"));
                e.xgf = Double.parseDouble(r.get("xgoalsfor")); e.xga = Double.parseDouble(r.get("xgoalsagainst"));
                e.gf = Double.parseDouble(r.get("goalsfor")); e.ga = Double.parseDouble(r.get("goalsagainst"));
                e.source = source; e.importedAt = Instant.now();
                if (e.games <= 0) { skipped++; continue; }
                out.add(e);
            } catch (RuntimeException ex) { skipped++; }
        }
        tx.executeWithoutResult(t -> xg.saveAll(out));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teamsStored", out.size()); m.put("skippedRows", skipped); m.put("unknownTeams", unknown);
        m.put("note", "Season-level xG is shown as context only. It is not point-in-time, so it is NOT used by the model or any backtest (that would leak future information).");
        return m;
    }

    /**
     * Manually entered current odds (e.g. read from Veikkaus or Paf in the user's own browser). Stored as fresh observations under
     * provider "manual:<name>" so EV, edge and best-price logic work as for any provider; they go stale like other prices.
     */
    public Map<String, Object> manualOdds(long gameId, String market, String provider, Double home, Double draw, Double away) {
        GameEntity g = games.findById(gameId).orElseThrow(() -> new IllegalArgumentException("Unknown game id"));
        if (g.state != GameState.SCHEDULED || !g.startUtc.isAfter(Instant.now())) throw new IllegalArgumentException("Odds can only be entered for games that have not started");
        String name = provider == null ? "" : provider.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if (name.isEmpty() || name.length() > 30) throw new IllegalArgumentException("Provider name must be 1-30 letters or digits");
        List<String> sels; Double[] vals;
        if ("MONEYLINE".equals(market)) {
            if (home == null || away == null) throw new IllegalArgumentException("Moneyline needs both home and away odds");
            if (draw != null) throw new IllegalArgumentException("Moneyline has no draw price (draw belongs to the 60-minute 1X2 market)");
            sels = List.of("HOME", "AWAY"); vals = new Double[]{home, away};
        } else if ("REG_1X2".equals(market)) {
            if (home == null || draw == null || away == null) throw new IllegalArgumentException("1X2 needs home, draw and away odds");
            sels = List.of("HOME", "DRAW", "AWAY"); vals = new Double[]{home, draw, away};
        } else throw new IllegalArgumentException("market must be MONEYLINE or REG_1X2");
        for (Double v : vals) if (v == null || !MarketMath.validOdds(v) || v > 1000) throw new IllegalArgumentException("Odds must be decimal numbers between 1.001 and 1000");
        Instant now = Instant.now(); List<OddsEntity> rows = new ArrayList<>();
        for (int i = 0; i < sels.size(); i++) {
            OddsEntity e = new OddsEntity();
            e.gameId = g.id; e.provider = "manual:" + name; e.market = market; e.selection = sels.get(i);
            e.decimalOdds = vals[i]; e.retrievedAt = now; e.marketTimestamp = null; e.sourceStatus = "MANUAL";
            rows.add(e);
        }
        tx.executeWithoutResult(t -> odds.saveAll(rows));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stored", rows.size()); out.put("provider", "manual:" + name); out.put("game", g.awayName + " @ " + g.homeName);
        out.put("note", "Entered by hand; treated as fresh until the maximum odds age passes, then stale. Check the market definition at the bookmaker (OT/SO vs 60 min).");
        return out;
    }
}
