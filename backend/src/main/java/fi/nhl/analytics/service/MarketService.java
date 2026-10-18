package fi.nhl.analytics.service;

import fi.nhl.analytics.core.MarketMath;
import fi.nhl.analytics.domain.*;
import fi.nhl.analytics.domain.Repos.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MarketService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketService.class);
    public static final List<String> ML = List.of("HOME", "AWAY"), REG = List.of("HOME", "DRAW", "AWAY");

    public record Best(double odds, String provider, Instant at, Double marketProb, double rawImplied) {}
    public record OddsView(Map<String, Best> best, Set<String> stale) {}

    private final GameRepo games; private final OddsRepo odds; private final PredictionRepo preds;
    private final OutcomeRepo outcomes; private final AppProperties props; private final TeamXgRepo xgRepo;

    public MarketService(GameRepo g, OddsRepo o, PredictionRepo p, OutcomeRepo oc, AppProperties props, TeamXgRepo xgRepo) {
        this.xgRepo = xgRepo;
        this.games = g; this.odds = o; this.preds = p; this.outcomes = oc; this.props = props;
    }

    /** Best FRESH price per market:selection; stale-only selections are listed separately and excluded from rankings. */
    public OddsView oddsView(long gameId) {
        Instant now = Instant.now(), cutoff = now.minus(Duration.ofMinutes(props.oddsMaxAgeMinutes()));
        Map<String, OddsEntity> latest = new HashMap<>();
        for (OddsEntity e : odds.findByGameIdAndRetrievedAtAfter(gameId, now.minus(Duration.ofDays(7))))
            latest.merge(e.provider + "|" + e.market + "|" + e.selection, e,
                    (a, b) -> a.retrievedAt.isAfter(b.retrievedAt) ? a : b);
        Map<String, Best> best = new HashMap<>(); Set<String> stale = new HashSet<>();
        for (OddsEntity e : latest.values()) {
            List<String> allowed = "MONEYLINE".equals(e.market) ? ML : "REG_1X2".equals(e.market) ? REG : List.of();
            if (!allowed.contains(e.selection)) continue; // invalid market/selection combination: never index into it
            String key = e.market + ":" + e.selection;
            if (e.retrievedAt.isBefore(cutoff)) { stale.add(key); continue; }
            Best cur = best.get(key);
            if (cur == null || e.decimalOdds > cur.odds()) {
                List<String> sels = "MONEYLINE".equals(e.market) ? ML : REG;
                double[] set = new double[sels.size()]; boolean complete = true;
                for (int i = 0; i < set.length; i++) {
                    OddsEntity o = latest.get(e.provider + "|" + e.market + "|" + sels.get(i));
                    if (o == null || o.retrievedAt.isBefore(cutoff)) { complete = false; break; }
                    set[i] = o.decimalOdds;
                }
                Double mp = complete ? MarketMath.normalized(set)[sels.indexOf(e.selection)] : null;
                best.put(key, new Best(e.decimalOdds, e.provider, e.retrievedAt, mp, MarketMath.implied(e.decimalOdds)));
            }
        }
        stale.removeAll(best.keySet());
        return new OddsView(best, stale);
    }

    public boolean experimental() {
        return outcomes.findAll().stream().filter(o -> "SETTLED".equals(o.status)).count() < props.minSettledForValidation();
    }

    static double prob(PredictionEntity p, String market, String sel) {
        if ("MONEYLINE".equals(market)) return "HOME".equals(sel) ? p.pHomeMl : p.pAwayMl;
        return switch (sel) { case "HOME" -> p.pHomeReg; case "DRAW" -> p.pDraw; default -> p.pAwayReg; };
    }

    public List<Map<String, Object>> upcoming(String market) {
        Instant now = Instant.now();
        boolean exp = experimental();
        Map<String, TeamXgEntity> xgLatest = new HashMap<>();
        try { for (TeamXgEntity x : xgRepo.findAll()) xgLatest.merge(x.team, x, (a, b) -> a.season >= b.season ? a : b); }
        catch (RuntimeException e) { log.warn("xG context unavailable (optional, predictions unaffected): {}", e.toString()); }
        List<Map<String, Object>> out = new ArrayList<>();
        for (GameEntity g : games.findByStartUtcBetweenOrderByStartUtc(now.minus(Duration.ofHours(4)), now.plus(Duration.ofDays(7)))) {
            Map<String, Object> row = new LinkedHashMap<>(gameInfo(g));
            List<PredictionEntity> ps = preds.findByGameIdOrderByCreatedAtDesc(g.id);
            PredictionEntity p = g.state == GameState.SCHEDULED && !ps.isEmpty() ? ps.get(0) : null;
            OddsView ov = oddsView(g.id);
            row.put("hasPrediction", p != null);
            row.put("modelVersion", p == null ? null : p.modelVersion);
            row.put("predictionAt", p == null ? null : p.createdAt);
            row.put("dataQuality", p == null ? "INSUFFICIENT_DATA" : p.dataQuality);
            row.put("experimental", exp);
            row.put("goalie", Map.of("status", "UNKNOWN", "note", "No goalie source integrated"));
            row.put("xg", xgContext(xgLatest.get(g.homeAbbrev), xgLatest.get(g.awayAbbrev)));
            List<Map<String, Object>> sels = new ArrayList<>();
            for (String s : "MONEYLINE".equals(market) ? ML : REG) {
                Map<String, Object> r = new LinkedHashMap<>();
                String key = market + ":" + s; Best b = ov.best().get(key);
                r.put("selection", s);
                r.put("modelProb", p == null ? null : prob(p, market, s));
                r.put("bestOdds", b == null ? null : b.odds());
                r.put("provider", b == null ? null : b.provider());
                r.put("oddsAt", b == null ? null : b.at());
                r.put("rawImplied", b == null ? null : b.rawImplied());
                r.put("marketProb", b == null ? null : b.marketProb());
                Double mp = b == null ? null : b.marketProb();
                r.put("edgePp", p == null || mp == null ? null : MarketMath.edgePp(prob(p, market, s), mp));
                r.put("evPct", p == null || b == null ? null : MarketMath.evPct(prob(p, market, s), b.odds()));
                r.put("classification", MarketMath.classify(p != null, b == null ? null : b.odds(),
                        ov.stale().contains(key), p == null ? 0 : prob(p, market, s), exp, props.minEvPct()));
                r.put("stale", ov.stale().contains(key));
                sels.add(r);
            }
            row.put("selections", sels);
            if (p != null) { row.put("nHome", p.nHome); row.put("nAway", p.nAway); }
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> xgContext(TeamXgEntity h, TeamXgEntity a) {
        if (h == null || a == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("home", xgTeam(h)); m.put("away", xgTeam(a));
        m.put("note", "Season-level xG per game (context only, not used by the model)");
        return m;
    }

    private static Map<String, Object> xgTeam(TeamXgEntity x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("season", x.season); m.put("games", x.games);
        m.put("xgfPerGame", x.xgf / x.games); m.put("xgaPerGame", x.xga / x.games);
        m.put("gfPerGame", x.gf / x.games); m.put("gaPerGame", x.ga / x.games);
        return m;
    }

    public Map<String, Object> gameInfo(GameEntity g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameId", g.id); m.put("startUtc", g.startUtc); m.put("state", g.state);
        m.put("home", g.homeName); m.put("away", g.awayName); m.put("homeAbbrev", g.homeAbbrev); m.put("awayAbbrev", g.awayAbbrev);
        m.put("homeScore", g.homeScore); m.put("awayScore", g.awayScore);
        return m;
    }

    /** Multi-leg, different-game combinations from positive-EV legs. Quantifies evidence; never claims safety. */
    public List<Map<String, Object>> combinations(int maxLegs) {
        List<fi.nhl.analytics.core.Combos.Leg> legs = new ArrayList<>();
        for (String market : List.of("MONEYLINE", "REG_1X2")) for (Map<String, Object> g : upcoming(market)) {
            if (!Boolean.TRUE.equals(g.get("hasPrediction"))) continue;
            int nEff = Math.min((Integer) g.get("nHome"), (Integer) g.get("nAway"));
            @SuppressWarnings("unchecked") List<Map<String, Object>> sels = (List<Map<String, Object>>) g.get("selections");
            for (Map<String, Object> s : sels) {
                if (!String.valueOf(s.get("classification")).startsWith("POSITIVE_EV")) continue;
                legs.add(new fi.nhl.analytics.core.Combos.Leg((Long) g.get("gameId"),
                        g.get("home") + " - " + g.get("away") + " - " + s.get("selection") + " (" + market + ")", market,
                        (Double) s.get("modelProb"), (Double) s.get("bestOdds"), nEff, (Double) s.get("marketProb"), (Double) s.get("evPct")));
            }
        }
        boolean exp = experimental();
        List<Map<String, Object>> out = new ArrayList<>();
        for (fi.nhl.analytics.core.Combos.Combo c : fi.nhl.analytics.core.Combos.build(legs, maxLegs, 10, 10)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("legs", c.legs().stream().map(fi.nhl.analytics.core.Combos.Leg::label).toList());
            m.put("individualProbs", c.legs().stream().map(fi.nhl.analytics.core.Combos.Leg::p).toList());
            m.put("individualOdds", c.legs().stream().map(fi.nhl.analytics.core.Combos.Leg::odds).toList());
            m.put("jointProb", c.jointProb()); m.put("jointProbApproxSe", c.jointSe());
            m.put("combinedOdds", c.combinedOdds()); m.put("fairOddsAtModelProb", c.fairOdds());
            m.put("evPct", c.evPct()); m.put("evSePct", c.evSePct()); m.put("zScore", c.z());
            m.put("evidence", fi.nhl.analytics.core.Combos.evidence(c.z()));
            m.put("evIfMarketIsRightPct", c.marketFairEvPct());
            m.put("experimental", exp);
            m.put("recommendation", exp ? "NO_RECOMMENDATION_EXPERIMENTAL_MODEL" : "CANDIDATE_ONLY");
            m.put("assumption", "Independence between different games (unverified); same-game legs excluded");
            m.put("warnings", List.of(
                    "Not safe: each added leg lowers the hit probability and compounds the bookmaker margin (see 'EV if market is right').",
                    "SE reflects rating sampling error only, not model error; the best of many combinations is biased upward (winner's curse).",
                    "A positive EV is a model estimate, not proof of profit."));
            out.add(m);
        }
        return out;
    }

    /**
     * Single-selection candidates for MONEYLINE and REG_1X2, ranked by z = EV / standard error. This is a ranking of evidence,
     * not a promise: SE covers rating sampling error only and the model is experimental until enough predictions are settled.
     */
    public Map<String, Object> suggestions() {
        boolean exp = experimental();
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String market : List.of("MONEYLINE", "REG_1X2")) {
            int games = 0, withPred = 0, withOdds = 0, positive = 0;
            for (Map<String, Object> g : upcoming(market)) {
                games++;
                if (!Boolean.TRUE.equals(g.get("hasPrediction"))) continue;
                withPred++;
                int nEff = Math.max(1, Math.min((Integer) g.get("nHome"), (Integer) g.get("nAway")));
                boolean any = false;
                @SuppressWarnings("unchecked") List<Map<String, Object>> sels = (List<Map<String, Object>>) g.get("selections");
                for (Map<String, Object> s : sels) {
                    Double p = (Double) s.get("modelProb"), odds = (Double) s.get("bestOdds"), ev = (Double) s.get("evPct");
                    if (p == null || odds == null || ev == null) continue;
                    any = true;
                    String cls = String.valueOf(s.get("classification")), sel = (String) s.get("selection");
                    boolean pos = cls.startsWith("POSITIVE_EV");
                    if (pos) positive++;
                    double se = 100.0 * odds * Math.sqrt(p * (1 - p) / nEff), z = se > 0 ? ev / se : 0;
                    Double mp = (Double) s.get("marketProb"), edge = (Double) s.get("edgePp");
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("gameId", g.get("gameId")); it.put("startUtc", g.get("startUtc")); it.put("game", g.get("home") + " - " + g.get("away"));
                    it.put("market", market); it.put("selection", sel);
                    it.put("label", "HOME".equals(sel) ? g.get("home") : "AWAY".equals(sel) ? g.get("away") : "Tasapeli");
                    it.put("modelProb", p); it.put("bestOdds", odds); it.put("provider", s.get("provider")); it.put("oddsAt", s.get("oddsAt"));
                    it.put("marketProb", mp); it.put("edgePp", edge); it.put("evPct", ev); it.put("evSePct", se); it.put("zScore", z);
                    it.put("evidence", fi.nhl.analytics.core.Combos.evidence(z)); it.put("classification", cls); it.put("nEff", nEff);
                    it.put("recommendation", pos ? (exp ? "NO_RECOMMENDATION_EXPERIMENTAL_MODEL" : "CANDIDATE") : "NONE");
                    List<String> why = new ArrayList<>();
                    why.add(String.format(Locale.ROOT, "Mallin todennäköisyys %.1f %% vs markkinan marginaalista puhdistettu %s%s", p * 100,
                            mp == null ? "ei saatavilla" : String.format(Locale.ROOT, "%.1f %%", mp * 100),
                            edge == null ? "" : String.format(Locale.ROOT, " (edge %+.1f pp)", edge)));
                    why.add(String.format(Locale.ROOT, "Paras kerroin %.2f (%s), EV %+.1f %% ± %.1f (z = %.2f)", odds, s.get("provider"), ev, se, z));
                    why.add("Aloittavan maalivahdin tietoa ei ole (UNKNOWN): kertoimet voivat liikkua paljon ennen pelin alkua.");
                    if (exp) why.add("Malli on kokeellinen, joten tämä on tarkasteluehdokas eikä suositus.");
                    it.put("reasons", why);
                    items.add(it);
                }
                if (any) withOdds++;
            }
            counts.put(market, Map.of("games", games, "withPrediction", withPred, "withFreshOdds", withOdds, "positiveEv", positive));
        }
        items.sort(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("zScore")).reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items); out.put("counts", counts); out.put("experimental", exp);
        out.put("minEvPct", props.minEvPct()); out.put("oddsMaxAgeMinutes", props.oddsMaxAgeMinutes());
        out.put("rankBasis", "z = EV / keskivirhe (suurempi = vahvempi näyttö samalla otoskoolla)");
        out.put("warnings", List.of(
                "Ehdokas ei ole takuu eikä suositus: positiivinen EV on mallin estimaatti ja voi johtua mallivirheestä tai markkinan uudemmasta tiedosta.",
                "Keskivirhe kattaa vain joukkuearvioiden otantavirheen, ei mallin virhettä; listan parhaat ovat valikoitumisharhan vuoksi yliarvioituja.",
                "Sovellus ei anna panossuosituksia eikä seuraa rahaa."));
        return out;
    }
}
