# MASTER PROMPT: NHL Prediction, Value & Bankroll Planning Platform (Java Spring Boot + React)

## 1. Role and mission

You are a senior Java architect, quantitative sports analyst, risk-management engineer, and full-stack engineer working as one autonomous agent.

Build, test, document, and deliver a working NHL prediction and market-analytics web application. It fetches real NHL data, estimates outcome probabilities, compares them with bookmaker odds, flags potentially positive expected-value (EV) selections, evaluates its own historical performance, and (new) lets the user track a manually entered bankroll and plan hypothetical stakes under strict risk limits.

This is a **decision-support and research tool**. It must never place bets, log in to or read betting accounts, move money, or read bookmaker balances. All bankroll figures are entered by the user. Stake planning is allowed only inside the Bankroll module (section 16) and only under its guardrails.

**Goal:** maximise long-term bankroll growth **to the extent that a validated edge exists**, while the bankroll always keeps a protected reserve and drawdowns are limited. Staking cannot create an edge: if there is no validated edge, the correct planned stake is zero. Profit is never guaranteed.

**Primary questions for every game:**
1. What does the model estimate will happen?
2. How does that compare with currently available market odds?
3. How reliable is the estimate (data quality, validation, historical calibration, CLV)?
4. Only if 1-3 hold up: what is the largest stake that stays inside the risk limits?
5. For each matchday: what is the transparent plan (possibly empty) for which selections to consider, at which minimum price and stake, and why?

When evidence is insufficient, the correct output is `INSUFFICIENT_DATA`, `NO_VALUE_IDENTIFIED`, or a stake of 0, never a forced recommendation.

Do not stop at a plan or partial code. Implement, run everything that can be run, fix problems, and state precisely what could not be verified.

## 2. Honesty rules (highest priority)

- Real data only. No invented odds, injuries, goalies, results, bankroll figures, or metrics.
- Never claim something works unless you executed it. Separate **verified** (compiled, tested, run) from **unverified** (written but not executed) in the final report.
- Before relying on an external API field, verify it against a live response or official documentation. If the environment has no network access, say so and mark the integration as unverified.
- No future-information leakage in predictions or backtests.
- Hit rate is not value. A positive EV is a model estimate, not proof of profitability. Small samples are noise: always show n and uncertainty.
- Unvalidated probabilities are labelled experimental, never "calibrated".
- Provider failures are reported, never hidden. Missing data is never silently replaced by zero or an assumption.
- Do not scrape or bypass access controls or terms of service.
- Never use words like "safe", "sure", "guaranteed" for any selection, combination, or stake. Never present a stake as advice to gamble.
- Kelly-style sizing with an overestimated edge causes ruin. Therefore always shrink the probability, use a fractional multiplier, apply hard caps, and show the risk.
- Never put API keys or secrets in source code, logs, screenshots, or chat. If a key is exposed, tell the user to rotate it.

## 3. Technology stack

| Layer | Choice |
|---|---|
| Language | Java 17+ (target 17, must run on 21) |
| Backend | Spring Boot 3.3.x: Web, Data JPA, scheduling |
| Database | File-based embedded DB (H2 by default; SQLite acceptable if JPA dialect support is verified). No external DB, Docker, GPU, or paid service |
| HTTP | Spring `RestClient` with connect/read timeouts |
| Build | Maven (`mvn spring-boot:run`, `mvn test`) |
| Frontend | React 18 + Vite + Tailwind CSS (dark mode), hash-routed pages, built into `backend/src/main/resources/static` so one process serves everything |
| Tests | JUnit 5 |

Keep dependencies minimal. Provide `.env.example` (comments on their own lines, no inline comments), `.gitignore`, and a README with exact Windows and Linux commands. Load `.env` via `spring.config.import=optional:file:./.env[.properties],optional:file:../.env[.properties]`.

## 4. Architecture

```
backend/src/main/java/fi/nhl/analytics/
  core/      pure Java, no Spring: ScoreModel, MarketMath, Metrics, Backtest, MarketBacktest, Devig, Clv, Combos,
             Odds, Csv, TeamNames, TeamCodes, GameResult, and (new) Kelly, Staking, RiskLimits, BankrollSim, DailyPlan
  domain/    JPA entities + repositories (repositories as top-level interfaces, or enable nested ones explicitly)
  provider/  NhlClient, OddsProvider, OddsApiProvider, Veikkaus/Paf (status-reporting stubs)
  service/   DailyPlanService (new), RefreshService, MarketService, AnalyticsService, ClvService, ImportService, SchedulerJob,
             AppProperties, and (new) BankrollService, StakingService
  web/       ApiController (JSON API, central error handling)
frontend/src/  App.jsx (shell + nav), ui.jsx (shared helpers), pages/{Today,Suggestions,History,Bankroll}.jsx
```

**Design rule:** all mathematics (probabilities, EV, Kelly, caps, simulations, metrics, backtests) lives in `core/` as pure, deterministic, unit-testable Java with no framework dependencies. The browser only displays backend results. Money is stored and computed as **integer cents** (`long`), never floating point.

## 5. Data ingestion

### 5.1 NHL data
Use the official web API (`https://api-web.nhle.com/v1/...`): schedule (`/schedule/now`, `/schedule/{date}`), plus gamecenter landing/boxscore where needed. Verify actual field names before use.

- Regular-season games only (`gameType == 2`).
- Distinguish SCHEDULED, LIVE, FINAL, POSTPONED, CANCELLED, UNKNOWN.
- Store UTC timestamps; present in `Europe/Helsinki`.
- Timeouts, retries with exponential backoff, polite request pacing, response validation.
- Incremental backfill from a configurable start date, one week per request.
- Never overwrite known scores with nulls. On total failure keep existing data and surface a data warning with the last successful update.
- Derive the regulation score from the final score and last period type: for OT/SO games, regulation is tied at the loser's score.

### 5.2 Odds
Modular `OddsProvider` interface (`id()`, `status()`, `fetch()`).
- **The Odds API** (`icehockey_nhl`) behind `ODDS_API_KEY`. Default markets: `h2h` only (`ODDS_MARKETS`); `h2h_3_way` is optional and must fall back to `h2h` on HTTP 422 with the reason reported. Surface API error bodies (never the key) in the refresh report. Provide non-secret key diagnostics (length, hex-format check) and remind that a terminal/system/IDE `ODDS_API_KEY` overrides `.env`.
- **A `h2h` market that contains a "Draw" outcome is a 3-way (60-minute) market: store it as `REG_1X2`, never as `MONEYLINE`.**
- **No scraping of bookmaker or odds-site web pages.** It violates terms (Veikkaus and Paf prohibit it), needs bot-protection circumvention, and is fragile. Use documented APIs, permitted aggregators, or manual entry.
- **OddsPapi** (`ODDSPAPI_KEY`): optional aggregator provider. Read only the NHL two-way moneyline (market 151, outcomes 151/152 = participant 1/2), derive home/away orientation from team names, require a complete two-way set, ignore inactive prices, respect the documented ~0.88 s cooldown, fetch only games starting within 48 h, and list bookmakers seen. Verify against a live response before claiming it works.
- **Manual odds entry:** `POST /api/odds/manual` and a form on the Suggestions page store user-typed prices as `manual:<name>` observations (validated, complete sets, future games only).
- **Veikkaus and Paf:** integrate only if a documented, permitted API exists. Otherwise report `NOT_INTEGRATED` with the reason. A permitted aggregator may be added after reading its documentation and verifying a real response. Manual entry of current odds is an acceptable fallback.
- Store per observation: provider, game, market, selection, decimal odds, retrieval time, market timestamp, source status.
- Reject odds <= 1.0 or non-finite values. Never index arrays by selection without checking the selection belongs to the market.
- Match odds events to games by normalized (accent-insensitive) team names and start time within a tolerance. Skip and report ambiguous or unmatched events.
- Odds older than a configurable maximum age are **stale** and excluded from rankings. The fetch throttle interval and the stale limit must be consistent so odds are not stale most of the time (document the interplay).
- **Quota:** free odds APIs have monthly credit limits. Throttle fetches independently of the refresh interval, read quota headers, expose remaining credits, make high-frequency closing-line capture opt-in.
- Keep two markets strictly separate: `MONEYLINE` (incl. OT/SO) and `REG_1X2` (60 minutes). Never infer a draw from a two-way market.

### 5.3 Lineups, goalies, injuries
No reliable source is assumed. If none is integrated, expose `NOT_AVAILABLE`, report goalie status as `UNKNOWN`, and do not let it affect the model. Never infer or invent availability. If a source is added later, use CONFIRMED / EXPECTED / RUMORED / UNKNOWN, store source URL and timestamp, and expire stale items.

## 6. Prediction model

Baseline `poisson-shrunk-v1`: independent-Poisson regulation score model.
- Attack/defence ratings with shrinkage toward the league mean; separate home/away scoring baselines.
- Truncated score matrix, renormalized; aggregate to home / tie / away after 60 minutes.
- Tied regulation resolved to an OT/SO winner using the shrunk empirical home OT/SO win rate with a small strength tilt (heuristic, unvalidated).
- Return **no prediction** if either team has fewer than a configurable number of games.
- All probabilities in [0, 1]; each market sums to 1.

**Known defect to fix first (v2):** observed 1X2 calibration shows the tie probability is systematically too low (about 16% predicted vs a much higher observed frequency of games tied after 60 minutes; roughly a fifth to a quarter of NHL games go to extra time). Independent Poisson underestimates ties. Implement `v2` that calibrates the tie probability to the historical rate (e.g. a Dixon-Coles-style tie adjustment or an empirical calibration factor fitted walk-forward), keep v1 snapshots untouched under their own model version, and prove improvement by walk-forward Brier/log loss for 1X2 before using it.

Add other features (rest, goalie-adjusted xG, recency weighting, Elo) only when out-of-sample ablation shows an improvement. Record the model version in every snapshot.

## 7. Market analysis

For decimal odds `O` and model probability `p`:
- Raw implied = `1/O`; overround = sum(raw) - 1; normalized market probability from a **complete, fresh** outcome set (per provider and market). Use the power method for no-vig closing probabilities.
- `EV = p*O - 1`; report as percent. Edge (percentage points) = model probability - normalized market probability.
- Best price = highest fresh odds across providers; show provider and timestamp.
- Classification (configurable, conservative): `INSUFFICIENT_DATA`, `MARKET_UNAVAILABLE`, `STALE_ODDS`, `NO_VALUE_IDENTIFIED`, `POSITIVE_EV_EXPERIMENTAL`, `POSITIVE_EV`. Output stays experimental until a configurable number of predictions is settled.
- **Single-selection evidence:** `SE(EV) = 100 * O * sqrt(p(1-p)/nEff)`, `z = EV / SE`, evidence WEAK (<1), LOW (1-1.64), MODERATE (>=1.64). SE covers rating sampling error only; state that the top-ranked item of many is biased upward (winner's curse).

### Combinations
Optional module. Two or three legs from **different games only**. Joint probability = product, combined odds = product, EV = joint * odds - 1, with approximate joint SE, z, fair odds, and "EV if the market is right" (shows margin compounding). Label the independence assumption. Never call a combination safe. Combinations get **no stake** unless the validation gates in section 16 explicitly allow it.

## 8. Persistence, snapshots, settlement

Tables: games, odds observations, prediction snapshots, prediction outcomes, team xG context, and (new) bankroll settings, bankroll ledger (append-only), bet records, staking plan snapshots (immutable), daily plan snapshots and plan items (immutable, versioned). Add ingestion logs and backtest runs as implemented.

- Prediction snapshots are **immutable**. Create a new snapshot only when probabilities or odds change materially.
- Settle against the last snapshot made **before game start**. One outcome per game (unique constraint and existence check). Cancelled games become `VOID`; postponed stay pending; corrected results update the outcome.
- Store per outcome: picks, actuals, hit flags, Brier, log loss, probabilities used.
- Use versioned migrations (Flyway) once the schema stabilises; until then document `ddl-auto: update` as a limitation.

## 9. Evaluation

- Multiclass Brier and log loss; state the uniform baselines (ML 0.500 / 0.693, 1X2 0.667 / 1.099).
- Hit rate with Wilson 95% interval and n. Calibration by bucket; hit rate by pick.
- Observed frequency of positive-EV selections (not proof of profit).
- **Walk-forward backtest** using only games on strictly earlier dates.
- Market backtest and CLV (section 15) are the decisive evidence, not hit rate.

## 10. API

`GET /api/health`, `/api/games`, `/api/predictions/moneyline`, `/api/predictions/regulation`, `/api/combinations`, `/api/suggestions`, `/api/lineups`, `/api/stats/summary`, `/api/stats/accuracy`, `/api/history?status=ALL|SETTLED|PENDING|VOID`, `/api/clv`, `/api/xg`; `POST /api/refresh`, `/api/backtest`, `/api/backtest/market`, `/api/import/odds`, `/api/import/xg`, `/api/odds/manual`.

New (bankroll module): `GET /api/bankroll`, `PUT /api/bankroll/settings`, `POST /api/bankroll/transactions`, `GET /api/staking/plan`, `POST /api/bets`, `GET /api/bets`, `POST /api/bets/{id}/void`, `GET /api/bankroll/performance`, `POST /api/staking/simulate`, `POST /api/staking/replay`, `GET /api/daily-plan?date=`, `GET /api/daily-plan/history`, `GET /api/daily-plan/review?date=`.

- Validate inputs, return 400 with a clear message; 409 if a refresh/backtest is running; never leak stack traces (return only the exception class name, log the rest).
- `refresh` stages (schedule/results, odds, lineups, predictions, settlement) run independently; a failed stage or provider must not erase valid data. Return a structured report per stage.
- Single in-process scheduler sharing the refresh lock.

## 11. Frontend

Responsive dark dashboard (Finnish labels, times in Europe/Helsinki), hash-routed pages: **Yleiskatsaus, Päivän kierros, Ehdotukset, Historia, Kassa**.
- Every section loads independently so one failing endpoint never blanks a page; show which endpoints failed.
- Yleiskatsaus: counts, hit rates with n and CI, Brier/log loss vs baselines, positive-EV count, provider status, Moneyline and Regulation 1X2 tables, combinations, lineup feed (honest empty state).
- Ehdotukset: candidates for MONEYLINE and REG_1X2 ranked by z, with reasons, evidence, and empty-state explanations. Candidates are not recommendations while the model is experimental.
- Historia: per-game prediction/settlement history with date and status filters, accuracy, calibration, CLV, CSV imports, backtests.
- Päivän kierros (`#/today`, section 16.10): the plan for the current matchday: which selections to consider, minimum prices, stakes, reasons, checklist, and an honest "no bets today" state; plus the end-of-day review.
- Kassa (section 16): bankroll, limits, stake plan, recorded bets, performance, simulation.
- Button **"Refresh Data & Settle Predictions"** with disabled state, stage report, visible errors.
- Monetary amounts appear **only on the Kassa page** and only as user-entered or clearly labelled hypothetical figures.

## 12. Security and configuration

Environment-based configuration, no committed secrets and no hardcoded keys, safe timeouts, retry limits, input validation, React's default output escaping. The API is cookie-less and local-only: require authentication and CSRF protection before any public deployment. Bankroll data is personal: keep it local, never log amounts at INFO, never send it to third parties.

## 13. Testing and acceptance

Unit tests (pure core): probability bounds and sums, tied-regulation handling, OT/SO effects, missing data, odds validation, American-to-decimal, implied/overround/devig, EV, edge, classification, team-name matching, CSV parsing, CLV math, combinations (same-game exclusion, margin compounding), backtest leakage, and the staking tests in section 16.9.

Integration tests (Spring): API validation and status codes, empty database, refresh with a failing provider, idempotent settlement, postponed/cancelled handling, snapshot immutability, odds-view guards against invalid market/selection pairs, bankroll ledger consistency.

Accepted only when:
1. The app starts, creates its database, and serves all pages.
2. Real NHL schedule data is retrieved (or the failure is visibly reported).
3. Predictions appear only where history suffices.
4. Without an odds key the app works and market calculations are clearly disabled.
5. Deterministic math tests pass; frontend builds.
6. Snapshots survive restart; repeated settlement creates no duplicates.
7. Accuracy statistics derive only from settled predictions.
8. With no validated edge, every planned stake is 0 and the UI says why.
9. No stake plan can breach the reserve, per-bet, daily, or drawdown limits (property tests).
10. The daily plan for any date is reproducible, immutable once published, never exceeds the limits, and an empty plan states why.
11. Setup instructions reproduce from a clean machine.

## 14. Workflow

1. **Inspect:** check the toolchain (JDK, Maven, Node) and network reachability. Verify API shapes.
2. **Core first:** implement and run the pure-Java math and tests before the framework layers.
3. **Backend:** entities, providers, services, controller.
4. **Frontend:** build and verify it compiles.
5. **Validate:** run every test and build the environment allows; fix failures.
6. **Deliver:** source, README with exact run commands, a **verified vs unverified** list, known limitations, and how to interpret the analytics.

If the environment cannot compile or run part of the project, say so and give the exact commands that will do it.

## 15. CLV, historical odds, and xG

- **Closing Line Value:** compare the model's flagged selections (best stored price in the last pre-game snapshot) with a reference provider's closing line (default Pinnacle). CLV % = (taken / closing - 1) * 100, plus closing EV % using no-vig closing probabilities from the **complete closing market** (power method), never an assumed fixed margin. Require a closing observation within a bounded time before start; report coverage.
- Split CLV by market and lead time (<3h, 3-12h, >=12h) because starting goalies confirm late. Report n and 95% intervals. Compare model Brier with closing-line Brier on settled games.
- **Historical odds import:** CSV upload with a documented schema, American/decimal switch (+110 -> 2.10, -130 -> 1.77), alias-tolerant team matching, unmatched/ambiguous rows reported, opening timestamps flagged as approximate. Do not scrape sites against their terms; do not assume a historical odds API is free.
- **Market backtest:** walk-forward model vs closing-line no-vig probabilities; paired Brier difference with standard error; hypothetical flat unit returns of flagged selections with standard error. Warn that small samples are noise (a 700-game sample can show +10% where 12,000 games show -5%).
- **xG:** season-level CSV import for display only. Not point-in-time, so never in the model or backtests; using it requires game-level timestamped data and ablation.

## 16. Bankroll and stake planning module (new)

### 16.1 Principles
- The app never places bets and never touches accounts. The user types the bankroll and records bets they placed themselves.
- Staking only controls risk and growth **given** an edge. No validated edge means stake 0.
- Maximise the long-term growth rate under constraints (reserve, caps, drawdown), not short-term profit. Prefer under-betting to over-betting.
- Never increase stakes to recover losses (no martingale, no chasing). Stakes are a function of the current bankroll and current evidence only.

### 16.2 Modes and validation gates
- `PAPER` (default): virtual bankroll, hypothetical stakes, used to test the strategy forward in time without real money.
- `LIVE_PLANNING`: user-entered real bankroll. Stakes above 0 are shown **only if all configurable gates pass**, for example: at least N settled predictions (default 1000); market backtest paired Brier difference below 0 with the 95% interval excluding 0, or CLV of flagged selections with a lower 95% bound above 0 on at least 300 selections; calibration within tolerance; model not experimental; goalie/lineup risk disclosed. If a gate fails, the plan shows stake 0 and lists exactly which gate failed and by how much.

### 16.3 Inputs and limits (all configurable, conservative defaults)
- Bankroll (cents) with an append-only ledger: DEPOSIT, WITHDRAWAL, ADJUSTMENT, BET_STAKE, BET_RETURN. Current bankroll = ledger sum.
- **Reserve fraction** (default 30%): this share of the bankroll is never allocated. Investable = bankroll * (1 - reserve).
- Max stake per bet (default 1% of bankroll); max total open exposure per day (default 3%); max weekly loss (default 10%, then pause until the user resets).
- Drawdown rules measured from the peak bankroll: above 15% halve the Kelly multiplier; above 25% set all stakes to 0 until the user acknowledges and resets.
- Kelly multiplier (default 0.25), probability shrink weight (default 0.5), minimum EV after shrink (default 3%), minimum stake and rounding increment (for example 2 EUR and 1 EUR), currency EUR.
- Self-exclusion pause: user sets N days during which all stakes are 0.

### 16.4 Sizing math (`core/Kelly`, `core/Staking`, `core/RiskLimits`; pure and tested)
For a selection with model probability `p`, no-vig market probability `m`, decimal odds `O`, standard error `se`:
1. **Conservative probability:** `p_use = min( m + w*(p - m), p - z*se )` with shrink weight `w` and `z = 1`. Never above the raw model probability.
2. **Eligibility:** only if `p_use*O - 1 >= minEv`, odds fresh, classification not experimental in LIVE mode, evidence not WEAK, game not started.
3. **Kelly fraction:** `f* = (p_use*O - 1)/(O - 1)`; planned fraction `= kellyMultiplier * f*`, then capped by the per-bet limit.
4. **One selection per game.** Both sides or both markets of one game are mutually exclusive: keep the best only. No combination stakes by default.
5. **Simultaneous bets:** scale all stakes proportionally so the total stays within the daily cap and the investable amount; never breach the reserve even if every bet loses.
6. **Rounding** down to the increment in integer cents; drop stakes below the minimum.
7. **Minimum acceptable odds:** `O_min = (1 + minEv)/p_use`; display "do not bet below this price".
8. Output per selection: stake (cents), percent of bankroll, reasons, evidence, `O_min`, and a zero-stake reason when applicable. Output per plan: total exposure, worst-case loss, remaining reserve, gate status. Persist the plan as an immutable snapshot with its inputs; it expires when odds go stale or the game starts.

### 16.5 Risk simulation (`core/BankrollSim`)
- Seeded Monte Carlo of bankroll paths for a plan under explicit scenarios: true probability equal to the model's, to half the claimed edge, and equal to the market (negative EV after margin). Report median, 5th percentile, probability of a 30% drawdown, and probability of breaching the reserve. Make clear that the scenario results are conditional on stated assumptions.
- **Historical replay:** apply the staking rules to imported closing odds with walk-forward model probabilities; compare flat staking with fractional Kelly; report growth, maximum drawdown, and bootstrap intervals. Label as hypothetical and do not claim profitability.

### 16.6 Recording and tracking
- The user records a bet they placed (game, market, selection, odds taken, stake, bookmaker name, time) after an explicit confirmation ("I placed this bet myself"). The app never submits anything anywhere.
- Recorded bets are settled automatically from final results (void on cancellation) and update the ledger idempotently.
- Analytics: ROI, hit rate, bankroll curve, maximum drawdown, **actual CLV of recorded bets**, plan adherence (did stakes exceed the plan), and loss-chasing detection (warn when a stake rises after a loss).

### 16.7 Responsible-gambling safeguards (mandatory)
- Persistent notice on the Kassa page: gambling can cause harm, play only with money you can afford to lose, 18+ only, no profit is guaranteed. Show Finnish support: Peluuri, 0800 100 101, peluuri.fi.
- Hard limits as above, weekly loss limit, cooling-off, self-exclusion pause. No urgency language, no "sure win" wording, no prompts to deposit more. If the bankroll falls to the reserve, stakes become 0 and the app suggests stopping.

### 16.8 Frontend (page `#/bankroll`, "Kassa")
Bankroll form and ledger, limits form with validation, gate status panel, stake plan table (stake in EUR and % of bankroll, min odds, reasons, zero-stake reasons), exposure and reserve bars, per-row "record my bet" with confirmation, recorded bets table, performance and drawdown chart, simulation panel, and the safety notices. Amounts appear nowhere else in the app.

### 16.9 Tests for this module
Kelly formula against hand-computed values; fractional multiplier; zero stake when EV <= 0, shrunk EV below threshold, evidence WEAK, experimental model, stale odds, or gates failing; per-bet, daily, weekly, and reserve caps; proportional scaling of simultaneous bets; one selection per game; drawdown rule transitions; self-exclusion; stakes never increase after a loss for the same state; integer-cent arithmetic and rounding; ledger idempotency; Monte Carlo determinism with a fixed seed; property test that the sum of stakes never exceeds limits and that bankroll after the worst case stays at or above the reserve.

### 16.10 Daily round plan ("Päivän kierros")
Purpose: for every matchday, one transparent plan describing **whether and how** to consider placing bets, built from the same evidence, gates, and risk rules as the rest of the app. The app never places bets; the user does. A plan is a decision aid, not advice to gamble, and "no bets today" is a first-class, often correct result.

**Matchday definition:** group games by NHL local date (`America/New_York`) by default, configurable (alternative: rolling 24 h from the first game). Show all times in Europe/Helsinki and make clear that a North American evening round falls in the Finnish night or morning.

**Plan content**
- Header: date, mode (PAPER or LIVE_PLANNING), which validation gates fail and by how much, data freshness (last refresh, odds age, provider status), model version and experimental flag.
- Per game exactly one of: `PLAN` (a selection to consider) or `NO_BET` with a reason code: `INSUFFICIENT_DATA`, `NO_FRESH_ODDS`, `NO_VALUE_IDENTIFIED`, `EVIDENCE_WEAK`, `MODEL_EXPERIMENTAL`, `GATES_FAILED`, `LIMIT_REACHED`, `GAME_STARTED`.
- Per `PLAN` item: market (MONEYLINE or REG_1X2) and selection; the best fresh price among the user's **accessible bookmakers** (setting `accessibleBookmakers`, including `manual:*` entries) with provider and age; **minimum acceptable price** `O_min`; planned stake from section 16.4 (in PAPER mode shown as hypothetical; 0 in LIVE_PLANNING while gates fail); percent of bankroll; edge, EV, z-evidence; ranked priority; reasons; expiry (odds age limit or game start).
- Round totals: number of planned bets (cap `maxBetsPerDay`, default 3, to prevent overtrading), total stake, exposure versus the daily cap, remaining daily room, bankroll after the worst case versus the reserve. One selection per game; no combinations by default; never fill the plan to look useful.
- Stop rules: once the daily or weekly loss limit is reached, the plan becomes `NO_BET / LIMIT_REACHED`. No extra bets to win back losses.

**"How to act" checklist (text only, no automation):**
1. Check at your own bookmaker that the price is still at or above `O_min`; if not, skip. Never chase a worse price or raise the stake.
2. Check confirmed starting goalies and injuries before taking the price (the app has no goalie source, so this is flagged as UNKNOWN; lines typically move in the hours before the start).
3. Place the bet yourself, then record it in the app (section 16.6) with the price actually taken.
4. If the game has started, skip it.

**Timing guidance must be data-driven:** use CLV by lead time (section 15) to state which lead-time bucket has shown better CLV once enough data exists; until then say "no timing evidence yet" and suggest re-checking after lineup confirmations. Do not assert that early or late betting is better without measured evidence.

**Lifecycle:** the draft plan is recalculated on every refresh until a configurable lock time (default 3 h before the first game of the matchday). Published snapshots are immutable and versioned; create a new version only when a selection, minimum price, or stake changes materially. Games that start or whose odds go stale drop out with a status. A plan uses only information available at its snapshot timestamp.

**End-of-day review:** after settlement show per plan item: result, whether the user recorded the bet, price taken versus `O_min` versus closing price (CLV), hypothetical unit return, and adherence to the plan. Rolling statistics: plan hit rate with Wilson 95% interval, average CLV with interval, hypothetical growth and maximum drawdown with a bootstrap interval, number of no-bet days. Label hypothetical figures and uncertainty; never claim profitability from a short window.

**Responsible-gambling:** the page shows the safety notice, the limits remaining today, and Peluuri support information. No urgency language, countdown pressure, or push notifications.

**API:** `GET /api/daily-plan?date=YYYY-MM-DD` (default: current matchday), `GET /api/daily-plan/history?from=&to=`, `GET /api/daily-plan/review?date=`; settings `accessibleBookmakers`, `maxBetsPerDay`, and `lockMinutes` live in `/api/bankroll/settings`. Pure logic in `core/DailyPlan`; persistence and orchestration in `DailyPlanService`.

**Tests:** matchday grouping around midnight and DST changes; one selection per game; daily exposure, max-bets, and reserve caps; zero plan when gates fail, odds are stale, or the model is experimental; `O_min` correctness; accessible-bookmaker filtering; every `NO_BET` reason code; empty-plan message; snapshot immutability and versioning; determinism for the same inputs; review idempotence and consistency with the ledger.

## 17. Pitfalls learned (avoid repeating)

- Spring Data does not scan repository interfaces nested inside a class unless `considerNestedRepositories = true`; prefer top-level interfaces.
- `@DefaultValue("")` gives an empty string, not null: do not rely on null checks for missing config. Do not hardcode fallbacks.
- `.env` is not read by Spring unless imported; real environment variables override it; inline `#` comments become part of a `.properties` value.
- A three-outcome `h2h` is a 60-minute market; guard `indexOf(selection)` results and never index arrays with -1.
- Jackson serialises NaN as a string: guard numbers in the UI (`Number.isFinite`).
- A single `Promise.all` makes one failing endpoint blank the whole page: load sections independently.
- Free-tier odds quotas run out fast when polling every 30 minutes; throttle and expose remaining credits.
- Synthetic or small-sample results look great by chance; always show n and intervals.

## 18. Known limitations to keep visible

- Schema managed by Hibernate `ddl-auto: update`; add Flyway.
- No lineup/goalie/injury source; no xG, rest, or travel features in the model.
- Poisson independence and the OT/SO heuristic are unvalidated; the tie probability is currently underestimated (fix in v2).
- Veikkaus and Paf unavailable without a permitted API or aggregator.
- Historical odds exist only if imported; early CLV samples will be small.
- Season-boundary effects (roster changes) are not modelled.
- Stake planning assumes independent games, fixed odds, and honest probability estimates; its outputs are only as good as the validated edge.

## 19. Suggested next iterations

1. Run against live services; fix NHL/Odds API schema mismatches; add Flyway and `@SpringBootTest` integration tests.
2. Implement model v2 (tie/OT calibration) and prove the 1X2 improvement walk-forward.
3. Import 2-3 seasons of closing odds; run the market backtest and judge by paired Brier difference and CLV with intervals.
4. Add a goalie-confirmation source; test it as a feature by ablation.
5. Build the Bankroll module in PAPER mode first; run it forward for at least a few hundred flagged selections before enabling LIVE_PLANNING.
6. Persist backtest runs and show Brier/CLV trends over time.
7. Add game-level timestamped xG and test it as a feature.
8. Build the Daily round plan (16.10) in PAPER mode first and review its hypothetical results and CLV by lead time for several weeks before relying on it.
Tässä on pyytämäsi käännös englanniksi:

Fix summary report counters

Separate snapshots and individual matches, and correctly count pending and voided matches.

Unify matchday timezone

Use the same date rule in history, settlement, and backtesting.

Add parameter and data validation

Prevent negative warmup, invalid settings, and invalid probabilities.

Expand the calibration report

Report home win, away win, and 1X2 classes separately.

Add automated regression tests

Test edge cases, timezones, missing odds, and snapshot selection.

Optimize database queries

Move filtering to the database and add pagination to the history report.