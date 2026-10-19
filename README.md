NHL Prediction & Value Analytics (Spring Boot + React)
Decision-support and research tool: fetches real NHL data, calculates match probabilities (Poisson score model), compares them with odds (EV, edge), settles predictions automatically, and measures its own accuracy.
No betting, stakes, bankroll, or monetary results.

Structure
backend/ Spring Boot 3.3 (Java 17+), Spring Data JPA, H2 (file-based database backend/data/)

core/ pure Java: ScoreModel, MarketMath, Metrics, Backtest, GameResult, TeamNames

provider/ NhlClient, OddsApiProvider (The Odds API), Veikkaus/Paf (NOT integrated, reports its status)

service/ RefreshService (fetching, predictions, settlement), MarketService, AnalyticsService, SchedulerJob

web/ApiController REST API /api/...

frontend/ React + Vite + Tailwind (dark mode, timezone Europe/Helsinki)

GitHub
.env (keys), backend/data/ (database, may contain bankroll and bet data), node_modules/, and downloaded CSV datasets are excluded using .gitignore.
The frontend build (backend/src/main/resources/static/) is generated with the npm run build command, so it is not version-controlled. Never commit API keys; if a key leaks, rotate it immediately.

Continuous Integration (GitHub Actions)
.github/workflows/ci.yml runs on every push and pull request: mvn test, frontend build, and packaging (jar downloadable from the Actions page for 7 days). The workflow does not require secrets; do not add API keys to it.

Pages
The dashboard has three pages (routes #/dashboard, #/suggestions, #/history):

Overview: statistics, moneyline and 1X2 predictions, combinations, provider status.

Suggestions: moneyline and 1X2 candidates ranked by estimation strength (z = EV / standard error), with reasoning. GET /api/suggestions.
Candidates are not recommendations: while the model is experimental, the recommendation is NO_RECOMMENDATION_EXPERIMENTAL_MODEL. An empty list is often the correct result.

History: predictions and settlements (filtered by date and status: all/settled/pending/voided), accuracy and calibration, CLV, CSV imports, and backtests. GET /api/history?status=.

Running (Windows / Linux)
Requirements: JDK 17+, Maven 3.9+, Node 18+.

# 1) Frontend -> built into the backend's static folder
cd frontend
npm install
npm run build

# 2) Backend (creates the database automatically)
cd ../backend
mvn spring-boot:run
Open http://localhost:8080 and click "Refresh Data & Settle Predictions". The first update fetches the season's game history (BACKFILL_FROM) one week at a time, so it will take a moment.

Development mode: mvn spring-boot:run + cd frontend && npm run dev (http://localhost:5173, proxy -> 8080).

Optional settings: copy .env.example as .env (to the project root or the backend/ folder), fill in ODDS_API_KEY=your_key, and restart the application.
Alternatively, set the environment variable in the same terminal session as mvn spring-boot:run: PowerShell $env:ODDS_API_KEY="your_key", Linux export ODDS_API_KEY=your_key.
Check status: http://localhost:8080/api/health, the status of the the-odds-api provider must be CONFIGURED.

Tests and Backtest
cd backend && mvn test              # JUnit: EV, normalization, 1X2/ML, overtime, leakage, model probabilities
curl -X POST "http://localhost:8080/api/backtest?warmup=100"   # walk-forward, model only (no historical odds)
API
GET /api/health, /api/games, /api/predictions/moneyline, /api/predictions/regulation, /api/combinations,
/api/lineups, /api/stats/summary, /api/stats/accuracy?from=&to=, /api/history, /api/clv, /api/xg;
POST /api/refresh, /api/backtest, /api/backtest/market, /api/import/odds, /api/import/xg.

Model Interpretation
Model probability is an estimate, not a certainty. Predictions are experimental until at least MIN_SETTLED_FOR_VALIDATION predictions have been settled and calibration is verified.

EV % = 100·(p·odds − 1). Edge = model − normalized market prob. (percentage points). They measure different things.

Hit rate does not prove value. Compare Brier/log loss figures to uniform baselines (ML 0.500 / 0.693, 1X2 0.667 / 1.099).

Moneyline (incl. OT/SO) and 60 min 1X2 are always kept separate.

CLV Analysis, Historical Odds, and xG
CLV (Closing Line Value)
GET /api/clv (and the dashboard's "CLV Analysis") compares the price of the model's flagged selections (best price in the pre-game stored snapshot) against the reference provider's closing line (default pinnacle, setting CLV_REFERENCE_PROVIDER).

CLV % = (taken odds / closing odds − 1) · 100. Additionally, closing EV %, where the margin is removed from the entire closing market (power method) rather than assuming a fixed 3% margin.

Results are split by market and by timing (<3 h, 3–12 h, ≥12 h before start). Starting goalies are not yet known, so early prediction CLV is typically skewed: evaluate groups separately.

Includes a comparison of the model's Brier score against the closing line on settled games.

A closing line is accepted only if it is recorded at most 180 minutes before start and the market is complete. Otherwise, the game is excluded.

There is no real bet: the "taken price" is hypothetical. Comparing the best price across all providers against a single provider's closing line flatters CLV.

Markets
By default, only h2h is fetched (moneyline, ODDS_MARKETS=h2h). If you want to try 60 min 1X2 odds, set ODDS_MARKETS=h2h,h2h_3_way; if the provider rejects it (HTTP 422), the app automatically retries with just h2h and reports the reason in the refresh report. Without 3-way odds, the 1X2 column will show MARKET_UNAVAILABLE and will not be evaluated.

Combination Suggestions
GET /api/combinations?maxLegs=2|3 combines positive EV selections from different games only (same-game selections are not combined). For each combination, it displays the win probability with a margin of error, combined odds, model fair odds, EV ± standard error, z-score, "evidence" (WEAK/LOW/MODERATE), and EV if the market is right (showing margin compounding).
The feature does not promise profit: win probability decreases with each selection, independence is assumed, standard error does not include model error, and the best combination on the list is overestimated due to selection bias.
When the model is experimental, the recommendation is always NO_RECOMMENDATION_EXPERIMENTAL_MODEL.

Odds Sources and Scraping
The application does not scrape bookmaker or odds provider websites. Doing so would almost certainly violate terms of service (Veikkaus and Paf prohibit it), require bypassing bot protection (e.g., Cloudflare), and be fragile. Instead, odds come from these sources:

The Odds API (ODDS_API_KEY): free monthly quota, moneyline.

OddsPapi (ODDSPAPI_KEY): free developer key, aggregator; moneyline (market 151) for NHL games. The schema is based on the provider's own documentation and has not been tested live.
/api/health lists recently seen bookmakers (e.g., whether pinnacle or paf is included). Filter books using the ODDSPAPI_BOOKMAKERS setting.

Manual entry (Suggestions page or POST /api/odds/manual): type Veikkaus or Paf odds yourself. Saved under provider manual:<name>.

Veikkaus and Paf: no documented public API found, so they remain in the NOT_INTEGRATED state.

Odds Quota (Important)
The free tier of The Odds API has a monthly quota (~500 credits estimated; the cost of each query depends on the number of markets and regions, this app requests 2 markets). Therefore, odds are fetched at most every ODDS_MIN_INTERVAL_MINUTES=240 minutes by default, even if updates run every 30 minutes.
Remaining credits are shown in the /api/health response and the dashboard's provider status.
CLOSING_CAPTURE=true collects odds every 10 minutes within an hour before games, which depletes the quota quickly; use only with a paid plan.
This application does not use a historical odds API (its availability on the free tier is uncertain; check provider pricing).

Historical Odds from CSV (e.g., Kaggle/SBR)
curl -X POST -F "file=@nhl_odds.csv" "http://localhost:8080/api/import/odds?format=american&source=pinnacle"
Mandatory columns: date (yyyy-MM-dd), home_team, away_team, home_ml_close, away_ml_close; optional home_ml_open, away_ml_open.
format=american converts odds (+110 → 2.10, −130 → 1.77), format=decimal uses values as-is. Teams are matched by names, abbreviations, and common aliases (NYRangers, LA Kings, Tampa Bay). The game must already exist in the database (run an update first; BACKFILL_FROM determines the start date).
The response reports matched, unmatched, and ambiguous rows. Check the dataset license. OddsPortal scraping is not implemented because it likely violates site terms.

Market Backtest
curl -X POST "http://localhost:8080/api/backtest/market?warmup=100&minEv=3&source=pinnacle"
Walk-forward: the model predicts using only data from prior days and compares it against the closing moneyline no-vig probability (Brier, log loss, paired difference standard error).
Additionally, the hypothetical flat-unit return of flagged selections at closing prices is reported. This is not a profitability claim.
Warning on sample size: in a synthetic test, a sample of 700 games showed a +10% "return" against the market, but in a 12,000-game sample, the same model lost to the market (−4…−7%). Small samples lie; trust only confidence intervals and thousands of observations.

xG
curl -X POST -F "file=@teams.csv" "http://localhost:8080/api/import/xg?season=2025"
Expects a MoneyPuck-style team CSV (columns team, games_played, xGoalsFor, xGoalsAgainst, goalsFor, goalsAgainst, optionally season, situation; only rows where situation=all are read; codes T.B/N.J/S.J/L.A are converted to NHL abbreviations). Download data from the source's own site and check terms of use.
xG is displayed alongside predictions as context only: season-level xG is not point-in-time, so using it in the model or backtests would leak future data.
Expanding the model with xG requires game-level data and an ablation test.

Limitations (Honestly)
NHL API field names follow the public schema but have not been tested live in this environment.
If any field differs, the first update will report an error in the NhlClient stage.

Veikkaus and Paf: no documented, permitted public API exists; modules are intentionally disabled.

CLV, CSV import, market backtest, and xG import have been implemented, but Spring parts have not been compiled or run in a development environment; core math (CLV, devig, CSV, market backtest) has been run.

Lineup, injury, and goalie sources are not integrated: status is UNKNOWN and does not affect the model.

Database schema is created via Hibernate's ddl-auto: update setting (no versioned migrations like Flyway).

H2 is used instead of SQLite (more reliable JPA support). Backup: close the application and copy backend/data/.

Model xG, rest, and player impact features are missing; Poisson independence assumption is not validated,
OT/SO winner modeling is heuristic. Combinations assume independence across different games.

No CSRF protection: API is cookie-less and intended for local use. Add authentication before public deployment.