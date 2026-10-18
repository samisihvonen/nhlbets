package fi.nhl.analytics.service;

import fi.nhl.analytics.core.GameResult;
import fi.nhl.analytics.core.ScoreModel;
import fi.nhl.analytics.domain.*;
import fi.nhl.analytics.domain.Repos.*;
import fi.nhl.analytics.provider.NhlClient;
import fi.nhl.analytics.provider.OddsProvider;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefreshService {
    private static final Logger log = LoggerFactory.getLogger(RefreshService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final NhlClient nhl; private final List<OddsProvider> providers; private final GameRepo games;
    private final OddsRepo odds; private final PredictionRepo preds; private final OutcomeRepo outcomes;
    private final MarketService market; private final AppProperties props; private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Object> lastReport;
    private volatile Instant lastOddsFetch;

    public RefreshService(NhlClient nhl, List<OddsProvider> providers, GameRepo games, OddsRepo odds, PredictionRepo preds,
                          OutcomeRepo outcomes, MarketService market, AppProperties props, TransactionTemplate tx) {
        this.nhl = nhl; this.providers = providers; this.games = games; this.odds = odds; this.preds = preds;
        this.outcomes = outcomes; this.market = market; this.props = props; this.tx = tx;
    }

    public boolean isRunning() { return running.get(); }
    public Map<String, Object> lastReport() { return lastReport; }
    public ScoreModel model() { return new ScoreModel(10, props.minGamesPerTeam()); }

    public static GameResult toResult(GameEntity g) {
        if (g.state != GameState.FINAL || g.homeScore == null || g.awayScore == null || g.homeScore.equals(g.awayScore)) return null;
        return GameResult.fromFinal(g.startUtc.atZone(ET).toLocalDate(), g.homeAbbrev, g.awayAbbrev, g.homeScore, g.awayScore, g.lastPeriodType);
    }

    /** @throws IllegalStateException if a refresh is already running. */
    public Map<String, Object> refresh() {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("A refresh is already running");
        try {
            Instant started = Instant.now();
            List<Map<String, Object>> stages = new ArrayList<>();
            stages.add(stage("schedule_and_results", this::stageSchedule));
            stages.add(stage("odds", this::stageOdds));
            stages.add(stage("lineups", () -> "NOT_AVAILABLE: no lineup/goalie source integrated; goalie status reported as UNKNOWN"));
            stages.add(stage("predictions", this::stagePredictions));
            stages.add(stage("settlement", this::stageSettlement));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("startedAt", started); r.put("finishedAt", Instant.now()); r.put("stages", stages);
            r.put("ok", stages.stream().allMatch(s -> !"FAILED".equals(s.get("status"))));
            lastReport = r;
            return r;
        } finally { running.set(false); }
    }

    private Map<String, Object> stage(String name, Callable<String> fn) {
        Map<String, Object> s = new LinkedHashMap<>(); s.put("stage", name); s.put("at", Instant.now());
        try { s.put("status", "OK"); s.put("detail", fn.call()); }
        catch (Exception e) {
            log.warn("Stage {} failed: {}", name, e.toString());
            s.put("status", "FAILED"); s.put("detail", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return s;
    }

    private String stageSchedule() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = games.findByStateOrderByStartUtc(GameState.FINAL).stream().map(g -> g.startUtc).max(Comparator.naturalOrder())
                .map(i -> i.atZone(ZoneOffset.UTC).toLocalDate().minusDays(7)).orElse(props.backfillFrom());
        int ok = 0, failed = 0, upserts = 0; String lastErr = "";
        for (LocalDate d = from; !d.isAfter(today.plusDays(7)) && ok + failed < 80; d = d.plusDays(7)) {
            try {
                List<GameEntity> week = nhl.fetchWeek(d.toString());
                tx.executeWithoutResult(t -> week.forEach(this::upsert));
                upserts += week.size(); ok++;
                Thread.sleep(250);
            } catch (Exception e) { failed++; lastErr = e.getMessage(); }
        }
        if (ok == 0) throw new IllegalStateException("No schedule data retrieved. Existing data kept. " + lastErr);
        return ok + " week(s) fetched, " + failed + " failed, " + upserts + " games upserted" + (failed > 0 ? " (partial: " + lastErr + ")" : "");
    }

    private void upsert(GameEntity n) {
        GameEntity e = games.findById(n.id).orElse(null);
        if (e != null) { // never overwrite known scores with nulls
            if (n.homeScore == null) { n.homeScore = e.homeScore; n.awayScore = e.awayScore; }
            if (n.lastPeriodType == null) n.lastPeriodType = e.lastPeriodType;
        }
        games.save(n);
    }

    /** Odds-only tick used for closing-line capture (opt-in via CLOSING_CAPTURE). Shares the refresh lock. */
    public void oddsTick() {
        if (!props.closingCapture()) return;
        if (!running.compareAndSet(false, true)) return;
        try { stage("odds", this::stageOdds); } finally { running.set(false); }
    }

    private String stageOdds() {
        Instant now = Instant.now();
        List<GameEntity> upcoming = games.findByStartUtcBetweenOrderByStartUtc(now, now.plus(Duration.ofDays(7)));
        boolean closeWindow = props.closingCapture() && upcoming.stream().anyMatch(g -> g.state == GameState.SCHEDULED
                && g.startUtc.isBefore(now.plus(Duration.ofMinutes(60))));
        long minInterval = closeWindow ? 10 : props.oddsMinIntervalMinutes();
        if (lastOddsFetch != null && Duration.between(lastOddsFetch, now).toMinutes() < minInterval)
            return "THROTTLED: last odds fetch " + Duration.between(lastOddsFetch, now).toMinutes() + " min ago, minimum interval "
                    + minInterval + " min (protects the odds API credit quota)";
        List<String> report = new ArrayList<>(); int stored = 0, failed = 0; boolean fetched = false;
        for (OddsProvider p : providers) {
            OddsProvider.Status st = p.status();
            if (!"CONFIGURED".equals(st.state())) { report.add(p.id() + ": " + st.state()); continue; }
            try {
                OddsProvider.Result r = p.fetch(upcoming);
                tx.executeWithoutResult(t -> odds.saveAll(r.observations()));
                stored += r.observations().size(); fetched = true;
                report.add(p.id() + ": " + r.observations().size() + " prices" + (r.warnings().isEmpty() ? "" : ", warnings: " + String.join(" | ", r.warnings().stream().limit(3).toList()))
                        + " (" + p.status().detail() + ")");
            } catch (Exception e) { failed++; report.add(p.id() + ": FAILED (" + (e instanceof IllegalStateException ? e.getMessage() : e.getClass().getSimpleName()) + ")"); }
        }
        if (fetched) lastOddsFetch = now;
        if (failed > 0 && stored == 0) throw new IllegalStateException(String.join("; ", report) + ". Previously stored odds kept; they age out as stale.");
        return String.join("; ", report);
    }

    private String stagePredictions() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        List<GameResult> hist = new ArrayList<>();
        for (GameEntity g : games.findByStateOrderByStartUtc(GameState.FINAL)) { GameResult r = toResult(g); if (r != null) hist.add(r); }
        ScoreModel model = model(); int created = 0, insufficient = 0, unchanged = 0;
        for (GameEntity g : games.findByStartUtcBetweenOrderByStartUtc(now.toInstant(), now.plusDays(7).toInstant())) {
            if (g.state != GameState.SCHEDULED) continue;
            Optional<ScoreModel.Prediction> op = model.predict(g.homeAbbrev, g.awayAbbrev, hist);
            if (op.isEmpty()) { insufficient++; continue; }
            ScoreModel.Prediction p = op.get();
            MarketService.OddsView ov = market.oddsView(g.id);
            PredictionEntity e = new PredictionEntity();
            e.gameId = g.id; e.createdAt = Instant.now(); e.modelVersion = ScoreModel.VERSION;
            e.pHomeReg = p.pHomeReg(); e.pDraw = p.pDraw(); e.pAwayReg = p.pAwayReg(); e.pHomeMl = p.pHomeMl(); e.pAwayMl = p.pAwayMl();
            e.lambdaHome = p.lambdaHome(); e.lambdaAway = p.lambdaAway(); e.nHome = p.gamesHome(); e.nAway = p.gamesAway();
            e.oddsHomeMl = o(ov, "MONEYLINE:HOME"); e.oddsAwayMl = o(ov, "MONEYLINE:AWAY");
            e.oddsHomeReg = o(ov, "REG_1X2:HOME"); e.oddsDraw = o(ov, "REG_1X2:DRAW"); e.oddsAwayReg = o(ov, "REG_1X2:AWAY");
            StringBuilder prov = new StringBuilder();
            ov.best().forEach((k, v) -> prov.append(k).append("=").append(v.provider()).append(";"));
            e.oddsProviders = prov.toString();
            e.dataQuality = "BASELINE_ONLY (results-based; goalie/injury data unavailable; min games " + Math.min(p.gamesHome(), p.gamesAway()) + ")";
            List<PredictionEntity> prev = preds.findByGameIdOrderByCreatedAtDesc(g.id);
            if (!prev.isEmpty() && same(prev.get(0), e)) { unchanged++; continue; }
            tx.executeWithoutResult(t -> preds.save(e)); created++;
        }
        return created + " new snapshot(s), " + unchanged + " unchanged, " + insufficient + " skipped (insufficient data)";
    }

    private static Double o(MarketService.OddsView ov, String k) { var b = ov.best().get(k); return b == null ? null : b.odds(); }

    private static boolean same(PredictionEntity a, PredictionEntity b) {
        return a.modelVersion.equals(b.modelVersion) && Math.abs(a.pHomeMl - b.pHomeMl) < 0.005 && Math.abs(a.pDraw - b.pDraw) < 0.005
                && Math.abs(a.pHomeReg - b.pHomeReg) < 0.005 && Objects.equals(a.oddsHomeMl, b.oddsHomeMl) && Objects.equals(a.oddsAwayMl, b.oddsAwayMl)
                && Objects.equals(a.oddsHomeReg, b.oddsHomeReg) && Objects.equals(a.oddsDraw, b.oddsDraw) && Objects.equals(a.oddsAwayReg, b.oddsAwayReg);
    }

    /** Idempotent: one outcome per game (unique constraint + existence check). Corrected results update the outcome. */
    private String stageSettlement() {
        int settled = 0, voided = 0, corrected = 0;
        for (GameEntity g : games.findAll()) {
            if (g.state != GameState.FINAL && g.state != GameState.CANCELLED) continue;
            Optional<PredictionEntity> snap = preds.findByGameIdOrderByCreatedAtDesc(g.id).stream()
                    .filter(p -> !p.createdAt.isAfter(g.startUtc)).findFirst(); // last snapshot made before the game started
            if (snap.isEmpty()) continue;
            Optional<OutcomeEntity> existing = outcomes.findByGameId(g.id);
            GameResult r = toResult(g);
            if (g.state == GameState.FINAL && r == null) continue; // unresolvable for now
            if (existing.isPresent()) {
                OutcomeEntity oe = existing.get();
                if (r != null && ("VOID".equals(oe.status) || oe.regActual != r.regIndex() || oe.mlActual != r.mlIndex())) {
                    tx.executeWithoutResult(t -> { fill(oe, snap.get(), r); outcomes.save(oe); }); corrected++;
                }
                continue;
            }
            OutcomeEntity oe = new OutcomeEntity(); oe.predictionId = snap.get().id; oe.gameId = g.id;
            if (g.state == GameState.CANCELLED) { oe.status = "VOID"; oe.settledAt = Instant.now(); voided++; }
            else { fill(oe, snap.get(), r); settled++; }
            tx.executeWithoutResult(t -> outcomes.save(oe));
        }
        return settled + " settled, " + voided + " void, " + corrected + " corrected";
    }

    private static void fill(OutcomeEntity oe, PredictionEntity p, GameResult r) {
        double[] reg = {p.pHomeReg, p.pDraw, p.pAwayReg}, ml = {p.pHomeMl, p.pAwayMl};
        oe.status = "SETTLED"; oe.settledAt = Instant.now();
        oe.regActual = r.regIndex(); oe.mlActual = r.mlIndex(); oe.regPick = argmax(reg); oe.mlPick = argmax(ml);
        oe.regHit = oe.regPick == r.regIndex(); oe.mlHit = oe.mlPick == r.mlIndex();
        oe.brierReg = fi.nhl.analytics.core.Metrics.brier(reg, r.regIndex()); oe.loglossReg = fi.nhl.analytics.core.Metrics.logLoss(reg, r.regIndex());
        oe.brierMl = fi.nhl.analytics.core.Metrics.brier(ml, r.mlIndex()); oe.loglossMl = fi.nhl.analytics.core.Metrics.logLoss(ml, r.mlIndex());
        oe.pHomeReg = p.pHomeReg; oe.pDraw = p.pDraw; oe.pAwayReg = p.pAwayReg; oe.pHomeMl = p.pHomeMl;
    }

    private static int argmax(double[] p) { int b = 0; for (int i = 1; i < p.length; i++) if (p[i] > p[b]) b = i; return b; }
}
