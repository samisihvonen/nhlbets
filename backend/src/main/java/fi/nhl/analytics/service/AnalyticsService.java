package fi.nhl.analytics.service;

import fi.nhl.analytics.core.Backtest;
import fi.nhl.analytics.core.GameResult;
import fi.nhl.analytics.core.MarketBacktest;
import fi.nhl.analytics.core.MarketMath;
import fi.nhl.analytics.core.Metrics;
import fi.nhl.analytics.domain.*;
import fi.nhl.analytics.domain.Repos.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
    private final GameRepo games; private final PredictionRepo preds; private final OutcomeRepo outcomes;
    private final RefreshService refresh; private final MarketService market; private final AppProperties props; private final OddsRepo oddsRepo;
    private final AtomicBoolean backtesting = new AtomicBoolean(false);

    public AnalyticsService(GameRepo g, PredictionRepo p, OutcomeRepo o, RefreshService r, MarketService m, AppProperties props, OddsRepo oddsRepo) {
        this.oddsRepo = oddsRepo;
        this.games = g; this.preds = p; this.outcomes = o; this.refresh = r; this.market = m; this.props = props;
    }

    private static double mean(List<Double> xs) { return xs.isEmpty() ? Double.NaN : xs.stream().mapToDouble(d -> d).sum() / xs.size(); }
    private static Double nz(double d) { return Double.isNaN(d) ? null : d; }

    private List<OutcomeEntity> settled(LocalDate from, LocalDate to) {
        return outcomes.findAll().stream().filter(o -> "SETTLED".equals(o.status)).filter(o -> {
            if (from == null && to == null) return true;
            GameEntity g = games.findById(o.gameId).orElse(null);
            if (g == null) return false;
            LocalDate d = g.startUtc.atZone(ZoneOffset.UTC).toLocalDate();
            return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
        }).toList();
    }

    public Map<String, Object> summary() {
        List<OutcomeEntity> s = settled(null, null);
        int eligible = 0;
        for (String m : List.of("MONEYLINE", "REG_1X2")) for (Map<String, Object> g : market.upcoming(m))
            for (Object o : (List<?>) g.get("selections")) if (String.valueOf(((Map<?, ?>) o).get("classification")).startsWith("POSITIVE_EV")) eligible++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalPredictionSnapshots", preds.count()); m.put("settled", s.size());
        m.put("pendingOrOther", preds.count() - s.size());
        m.put("regHitRate", rate(s.stream().filter(o -> o.regHit).count(), s.size()));
        m.put("mlHitRate", rate(s.stream().filter(o -> o.mlHit).count(), s.size()));
        m.put("regBrier", nz(mean(s.stream().map(o -> o.brierReg).toList())));
        m.put("regLogLoss", nz(mean(s.stream().map(o -> o.loglossReg).toList())));
        m.put("mlBrier", nz(mean(s.stream().map(o -> o.brierMl).toList())));
        m.put("mlLogLoss", nz(mean(s.stream().map(o -> o.loglossMl).toList())));
        m.put("eligiblePositiveEvSelections", eligible);
        m.put("experimental", market.experimental());
        m.put("validationNote", "Model is experimental until " + props.minSettledForValidation() + " predictions are settled and calibration is reviewed.");
        return m;
    }

    private static Map<String, Object> rate(long hits, int n) {
        double[] ci = Metrics.wilson((int) hits, n);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("n", n); r.put("hits", hits); r.put("rate", n == 0 ? null : (double) hits / n);
        r.put("ci95", n == 0 ? null : List.of(ci[0], ci[1]));
        return r;
    }

    private static List<Map<String, Object>> calibration(List<double[]> pairs) { // {p, outcome}
        double[] n = new double[10], sp = new double[10], so = new double[10];
        for (double[] x : pairs) { int b = Math.min(9, (int) (x[0] * 10)); n[b]++; sp[b] += x[0]; so[b] += x[1]; }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int b = 0; b < 10; b++) if (n[b] > 0) out.add(Map.of("lower", b / 10.0, "upper", (b + 1) / 10.0, "n", (int) n[b],
                "meanPredicted", sp[b] / n[b], "observed", so[b] / n[b]));
        return out;
    }

    public Map<String, Object> accuracy(LocalDate from, LocalDate to) {
        List<OutcomeEntity> s = settled(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> ml = new LinkedHashMap<>(), reg = new LinkedHashMap<>();
        ml.put("hit", rate(s.stream().filter(o -> o.mlHit).count(), s.size()));
        ml.put("brier", nz(mean(s.stream().map(o -> o.brierMl).toList()))); ml.put("logLoss", nz(mean(s.stream().map(o -> o.loglossMl).toList())));
        ml.put("calibrationHomeWin", calibration(s.stream().map(o -> new double[]{o.pHomeMl, o.mlActual == 0 ? 1 : 0}).toList()));
        reg.put("hit", rate(s.stream().filter(o -> o.regHit).count(), s.size()));
        reg.put("brier", nz(mean(s.stream().map(o -> o.brierReg).toList()))); reg.put("logLoss", nz(mean(s.stream().map(o -> o.loglossReg).toList())));
        List<double[]> pooled = new ArrayList<>();
        for (OutcomeEntity o : s) { double[] p = {o.pHomeReg, o.pDraw, o.pAwayReg}; for (int k = 0; k < 3; k++) pooled.add(new double[]{p[k], o.regActual == k ? 1 : 0}); }
        reg.put("calibrationPooledClasses", calibration(pooled));
        Map<String, Object> byPick = new LinkedHashMap<>(); String[] names = {"HOME", "DRAW", "AWAY"};
        for (int k = 0; k < 3; k++) { final int kk = k; List<OutcomeEntity> sub = s.stream().filter(o -> o.regPick == kk).toList();
            byPick.put(names[k], rate(sub.stream().filter(o -> o.regHit).count(), sub.size())); }
        reg.put("hitByPick", byPick);
        out.put("moneyline", ml); out.put("regulation1x2", reg);
        out.put("positiveEvSelections", positiveEv(s));
        out.put("note", "Hit rate alone does not establish value. Positive-EV selection hit rates are not proof of profitability. No money amounts are modelled.");
        out.put("filters", Map.of("from", String.valueOf(from), "to", String.valueOf(to)));
        return out;
    }

    private Map<String, Object> positiveEv(List<OutcomeEntity> s) {
        int n = 0, hits = 0;
        for (OutcomeEntity o : s) {
            PredictionEntity p = preds.findById(o.predictionId).orElse(null); if (p == null) continue;
            Object[][] sel = {
                {p.pHomeMl, p.oddsHomeMl, o.mlActual == 0}, {p.pAwayMl, p.oddsAwayMl, o.mlActual == 1},
                {p.pHomeReg, p.oddsHomeReg, o.regActual == 0}, {p.pDraw, p.oddsDraw, o.regActual == 1}, {p.pAwayReg, p.oddsAwayReg, o.regActual == 2}};
            for (Object[] x : sel) if (x[1] != null && MarketMath.evPct((Double) x[0], (Double) x[1]) >= props.minEvPct()) { n++; if ((Boolean) x[2]) hits++; }
        }
        Map<String, Object> m = new LinkedHashMap<>(rate(hits, n));
        m.put("note", n == 0 ? "No settled predictions had stored odds with positive EV" : "Observed frequency only; not a return calculation");
        return m;
    }

    private static int argmax(double[] p) { int b = 0; for (int i = 1; i < p.length; i++) if (p[i] > p[b]) b = i; return b; }

    /** One row per game: last pre-game snapshot plus outcome (SETTLED/VOID) or PENDING. Filter status: ALL, SETTLED, PENDING, VOID. */
    public List<Map<String, Object>> history(LocalDate from, LocalDate to, int limit, String status) {
        Map<Long, List<PredictionEntity>> byGame = preds.findAll().stream().collect(Collectors.groupingBy(p -> p.gameId));
        Map<Long, OutcomeEntity> oc = new HashMap<>();
        for (OutcomeEntity o : outcomes.findAll()) oc.put(o.gameId, o);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GameEntity g : games.findAllById(byGame.keySet())) {
            LocalDate d = g.startUtc.atZone(ZoneOffset.UTC).toLocalDate();
            if ((from != null && d.isBefore(from)) || (to != null && d.isAfter(to))) continue;
            OutcomeEntity o = oc.get(g.id);
            String st = o == null ? "PENDING" : o.status;
            if (!"ALL".equals(status) && !status.equals(st)) continue;
            PredictionEntity p = byGame.get(g.id).stream().filter(x -> !x.createdAt.isAfter(g.startUtc))
                    .max(Comparator.comparing((PredictionEntity x) -> x.createdAt)).orElse(null);
            if (p == null) continue;
            double[] reg = {p.pHomeReg, p.pDraw, p.pAwayReg}, ml = {p.pHomeMl, p.pAwayMl};
            Double[] odds = {p.oddsHomeMl, p.oddsAwayMl, p.oddsHomeReg, p.oddsDraw, p.oddsAwayReg};
            double[] probs = {p.pHomeMl, p.pAwayMl, p.pHomeReg, p.pDraw, p.pAwayReg};
            int flagged = 0;
            for (int i = 0; i < odds.length; i++) if (odds[i] != null && MarketMath.evPct(probs[i], odds[i]) >= props.minEvPct()) flagged++;
            boolean done = o != null && "SETTLED".equals(o.status);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gameId", g.id); m.put("startUtc", g.startUtc); m.put("game", g.awayName + " @ " + g.homeName); m.put("state", g.state);
            m.put("score", g.homeScore == null ? null : g.awayScore + "-" + g.homeScore + (g.lastPeriodType == null || "REG".equals(g.lastPeriodType) ? "" : " " + g.lastPeriodType));
            m.put("status", st); m.put("snapshotAt", p.createdAt); m.put("modelVersion", p.modelVersion);
            m.put("regProbs", List.of(reg[0], reg[1], reg[2])); m.put("mlProbs", List.of(ml[0], ml[1]));
            m.put("regPick", argmax(reg)); m.put("mlPick", argmax(ml));
            m.put("regActual", done ? o.regActual : null); m.put("mlActual", done ? o.mlActual : null);
            m.put("regHit", done ? o.regHit : null); m.put("mlHit", done ? o.mlHit : null);
            m.put("flaggedAtSnapshot", flagged);
            Map<String, Object> taken = new LinkedHashMap<>();
            taken.put("mlHome", p.oddsHomeMl); taken.put("mlAway", p.oddsAwayMl);
            taken.put("reg1", p.oddsHomeReg); taken.put("regX", p.oddsDraw); taken.put("reg2", p.oddsAwayReg);
            m.put("snapshotOdds", taken);
            rows.add(m);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (Instant) r.get("startUtc")).reversed());
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    public Backtest.Report backtest(int warmup) {
        if (!backtesting.compareAndSet(false, true)) throw new IllegalStateException("A backtest is already running");
        try {
            List<GameResult> all = new ArrayList<>();
            for (GameEntity g : games.findByStateOrderByStartUtc(GameState.FINAL)) { GameResult r = RefreshService.toResult(g); if (r != null) all.add(r); }
            return Backtest.run(all, refresh.model(), warmup);
        } finally { backtesting.set(false); }
    }

    public MarketBacktest.Report marketBacktest(int warmup, double minEvPct, String source) {
        if (!backtesting.compareAndSet(false, true)) throw new IllegalStateException("A backtest is already running");
        try {
            String provider = "import:" + source.toLowerCase().replaceAll("[^a-z0-9_-]", "");
            Map<Long, double[]> byGame = new HashMap<>();
            for (OddsEntity o : oddsRepo.findBySourceStatus("HISTORICAL_CLOSE")) {
                if (!provider.equals(o.provider) || !"MONEYLINE".equals(o.market)) continue;
                double[] v = byGame.computeIfAbsent(o.gameId, k -> new double[2]);
                if ("HOME".equals(o.selection)) v[0] = o.decimalOdds; else if ("AWAY".equals(o.selection)) v[1] = o.decimalOdds;
            }
            List<GameResult> all = new ArrayList<>(); Map<String, MarketBacktest.Priced> closing = new HashMap<>();
            for (GameEntity g : games.findByStateOrderByStartUtc(GameState.FINAL)) {
                GameResult r = RefreshService.toResult(g); if (r == null) continue;
                all.add(r);
                double[] v = byGame.get(g.id);
                if (v != null && v[0] > 1 && v[1] > 1) closing.put(MarketBacktest.key(r.date(), r.home(), r.away()), new MarketBacktest.Priced(v[0], v[1]));
            }
            return MarketBacktest.run(all, closing, refresh.model(), warmup, minEvPct);
        } finally { backtesting.set(false); }
    }
}
