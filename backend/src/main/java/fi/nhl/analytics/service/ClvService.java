package fi.nhl.analytics.service;

import fi.nhl.analytics.core.Clv;
import fi.nhl.analytics.core.Devig;
import fi.nhl.analytics.core.GameResult;
import fi.nhl.analytics.core.MarketMath;
import fi.nhl.analytics.core.Metrics;
import fi.nhl.analytics.domain.*;
import fi.nhl.analytics.domain.Repos.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Closing Line Value for the model's own flagged selections. Closing line = last observation from the reference provider
 * (default Pinnacle) made before the start and within MAX_GAP of it, with a complete market so the margin can be removed.
 * "Taken" price = the best price stored in the model's last pre-game snapshot; nothing here is an actual bet.
 */
@Service
public class ClvService {
    private static final long MAX_GAP_MIN = 180;
    private final GameRepo games; private final OddsRepo odds; private final PredictionRepo preds; private final AppProperties props;

    public ClvService(GameRepo g, OddsRepo o, PredictionRepo p, AppProperties props) { this.games = g; this.odds = o; this.preds = p; this.props = props; }

    private record Row(String market, String lead, double clv, double closingEv) {}

    private static Map<String, Object> sum(List<Double> xs) {
        Clv.Summary s = Clv.summarize(xs);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", s.n()); m.put("mean", fin(s.mean())); m.put("se", fin(s.se())); m.put("ci95Lo", fin(s.ciLo()));
        m.put("ci95Hi", fin(s.ciHi())); m.put("shareAboveZero", fin(s.shareAboveZero()));
        return m;
    }
    private static Double fin(double d) { return Double.isFinite(d) ? d : null; }

    private static String bucket(double hours) { return hours < 3 ? "<3h before start" : hours < 12 ? "3-12h before start" : ">=12h before start"; }

    public Map<String, Object> report() {
        String ref = props.clvReferenceProvider().toLowerCase();
        Instant now = Instant.now();
        List<Row> rows = new ArrayList<>(); List<Double> brierDiff = new ArrayList<>(), mBr = new ArrayList<>(), kBr = new ArrayList<>();
        int withSnapshot = 0, noSnapshot = 0, noClosing = 0;
        for (GameEntity g : games.findAll()) {
            if (!g.startUtc.isBefore(now)) continue;
            Optional<PredictionEntity> snap = preds.findByGameIdOrderByCreatedAtDesc(g.id).stream().filter(p -> !p.createdAt.isAfter(g.startUtc)).findFirst();
            if (snap.isEmpty()) { noSnapshot++; continue; }
            withSnapshot++;
            PredictionEntity p = snap.get();
            List<OddsEntity> os = odds.findByGameId(g.id).stream()
                    .filter(o -> o.provider.toLowerCase().contains(ref) && !o.retrievedAt.isAfter(g.startUtc)
                            && !o.retrievedAt.isBefore(g.startUtc.minus(Duration.ofMinutes(MAX_GAP_MIN)))).toList();
            if (os.isEmpty()) { noClosing++; continue; }
            String provider = os.stream().max(Comparator.comparing(o -> o.retrievedAt)).get().provider;
            Map<String, OddsEntity> latest = new HashMap<>();
            for (OddsEntity o : os) if (o.provider.equals(provider)) latest.merge(o.market + "|" + o.selection, o, (a, b) -> a.retrievedAt.isAfter(b.retrievedAt) ? a : b);
            double lead = Duration.between(p.createdAt, g.startUtc).toMinutes() / 60.0;
            for (String market : List.of("MONEYLINE", "REG_1X2")) {
                List<String> sels = "MONEYLINE".equals(market) ? MarketService.ML : MarketService.REG;
                double[] close = new double[sels.size()]; boolean complete = true;
                for (int i = 0; i < close.length; i++) { OddsEntity o = latest.get(market + "|" + sels.get(i)); if (o == null) { complete = false; break; } close[i] = o.decimalOdds; }
                if (!complete) { noClosing++; continue; }
                double[] fair = Devig.power(close);
                Double[] taken = "MONEYLINE".equals(market) ? new Double[]{p.oddsHomeMl, p.oddsAwayMl} : new Double[]{p.oddsHomeReg, p.oddsDraw, p.oddsAwayReg};
                double[] mp = "MONEYLINE".equals(market) ? new double[]{p.pHomeMl, p.pAwayMl} : new double[]{p.pHomeReg, p.pDraw, p.pAwayReg};
                for (int i = 0; i < close.length; i++)
                    if (taken[i] != null && MarketMath.evPct(mp[i], taken[i]) >= props.minEvPct())
                        rows.add(new Row(market, bucket(lead), Clv.clvPct(taken[i], close[i]), Clv.closingEvPct(taken[i], fair[i])));
                GameResult r = RefreshService.toResult(g);
                if ("MONEYLINE".equals(market) && r != null) {
                    double bm = Metrics.brier(mp, r.mlIndex()), bk = Metrics.brier(fair, r.mlIndex());
                    mBr.add(bm); kBr.add(bk); brierDiff.add(bm - bk);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("referenceProvider", ref);
        out.put("coverage", Map.of("gamesWithPreGameSnapshot", withSnapshot, "gamesWithoutSnapshot", noSnapshot, "marketsWithoutUsableClosingLine", noClosing));
        out.put("flaggedClvPct", sum(rows.stream().map(Row::clv).toList()));
        out.put("flaggedClosingEvPct", sum(rows.stream().map(Row::closingEv).toList()));
        Map<String, Object> byMarket = new LinkedHashMap<>(), byLead = new LinkedHashMap<>();
        for (String m : List.of("MONEYLINE", "REG_1X2")) byMarket.put(m, sum(rows.stream().filter(r -> r.market().equals(m)).map(Row::clv).toList()));
        for (String b : List.of("<3h before start", "3-12h before start", ">=12h before start")) byLead.put(b, sum(rows.stream().filter(r -> r.lead().equals(b)).map(Row::clv).toList()));
        out.put("clvByMarket", byMarket); out.put("clvByLeadTime", byLead);
        Map<String, Object> bench = new LinkedHashMap<>(); Clv.Summary d = Clv.summarize(brierDiff);
        bench.put("n", d.n()); bench.put("modelBrierMl", d.n() == 0 ? null : fin(mBr.stream().mapToDouble(x -> x).average().orElse(Double.NaN)));
        bench.put("closingLineBrierMl", d.n() == 0 ? null : fin(kBr.stream().mapToDouble(x -> x).average().orElse(Double.NaN)));
        bench.put("brierDiffMean", fin(d.mean())); bench.put("brierDiffSe", fin(d.se()));
        bench.put("interpretation", "Difference < 0 means the model beat the closing line on settled games. Closing line is usually the hardest benchmark.");
        out.put("benchmarkVsClosingLine", bench);
        out.put("message", rows.isEmpty() ? "No CLV data yet: it needs pre-game model snapshots with stored prices AND a closing line from the reference provider "
                + "(configure ODDS_API_KEY, keep the app running, and consider CLOSING_CAPTURE=true)." : null);
        out.put("notes", List.of(
                "CLV % = (taken odds / closing odds - 1) * 100. Closing EV % uses the no-vig closing probability (power method) from the full closing market.",
                "Taken price is the best price across providers in the snapshot; the closing price is from a single reference provider, which flatters CLV.",
                "Early snapshots (before starting goalies are confirmed) are split out by lead time: expect the most line movement there.",
                "Positive CLV over a large sample is evidence of an edge over the market; short samples are noisy, check the confidence interval."));
        return out;
    }
}
