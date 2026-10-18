import React, { useCallback, useEffect, useState } from "react";
import { api, Card, Badge, Empty, Stat, ErrorBar, loadAll, fmtTime, pct, num, signed, LABEL, REG_LABEL } from "./ui.jsx";
import History from "./pages/History.jsx";
import Suggestions from "./pages/Suggestions.jsx";
import Bankroll from "./pages/Bankroll.jsx";

const PAGES = [["dashboard", "Yleiskatsaus"], ["suggestions", "Ehdotukset"], ["history", "Historia"], ["bankroll", "Kassa"]];
const pageFromHash = () => {
  const h = window.location.hash.replace(/^#\/?/, "").replace(/\/$/, "").split("?")[0];
  return PAGES.some(([k]) => k === h) ? h : "dashboard";
};

function MarketTable({ rows, market }) {
  if (!rows.length) return <Empty>Ei tulevia otteluita tallennettuna. Paina "Refresh Data &amp; Settle Predictions".</Empty>;
  return (
    <div className="space-y-3">
      {rows.map((g) => (
        <div key={g.gameId} className="rounded-lg border border-slate-800 p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="font-medium">{g.home} – {g.away}</div>
            <div className="text-sm text-slate-400">{fmtTime(g.startUtc)} · maalivahti: {g.goalie.status}</div>
          </div>
          {!g.hasPrediction ? (
            <Empty>Ennuste: INSUFFICIENT_DATA (joukkueilla ei riittävästi otteluhistoriaa).</Empty>
          ) : (
            <div className="mt-2 overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-slate-400">
                  <tr><th>Valinta</th><th>Malli</th><th>Paras kerroin</th><th>Tarjoaja / aika</th><th>Markkina (norm.)</th><th>Edge (pp)</th><th>EV %</th><th>Luokitus</th></tr>
                </thead>
                <tbody>
                  {g.selections.map((s) => (
                    <tr key={s.selection} className="border-t border-slate-800">
                      <td className="py-1">{market === "REG" ? REG_LABEL[s.selection] : LABEL[s.selection]}</td>
                      <td>{pct(s.modelProb)}</td>
                      <td>{s.bestOdds ? num(s.bestOdds, 2) : "–"}</td>
                      <td className="text-xs">{s.provider ? `${s.provider} · ${fmtTime(s.oddsAt)}` : s.stale ? "vanhentunut" : "ei kertoimia"}</td>
                      <td>{pct(s.marketProb)}</td>
                      <td>{signed(s.edgePp)}</td>
                      <td>{signed(s.evPct)}</td>
                      <td><Badge cls={s.classification} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {g.xg && (
                <p className="mt-1 text-xs text-slate-400">
                  xG/ottelu (konteksti): {g.home} {num(g.xg.home.xgfPerGame, 2)}–{num(g.xg.home.xgaPerGame, 2)} · {g.away} {num(g.xg.away.xgfPerGame, 2)}–{num(g.xg.away.xgaPerGame, 2)} (tehdyt–päästetyt, kausi {g.xg.home.season}; ei mallissa)
                </p>
              )}
              <p className="mt-1 text-xs text-slate-500">Malli {g.modelVersion}{g.experimental ? " (kokeellinen, ei validoitu)" : ""} · {g.dataQuality}</p>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function Dashboard({ reloadKey }) {
  const [health, setHealth] = useState(null);
  const [summary, setSummary] = useState(null);
  const [ml, setMl] = useState([]);
  const [reg, setReg] = useState([]);
  const [combos, setCombos] = useState([]);
  const [lineups, setLineups] = useState(null);
  const [maxLegs, setMaxLegs] = useState(2);
  const [error, setError] = useState("");

  const load = useCallback(() => loadAll([
    ["/health", setHealth], ["/stats/summary", setSummary], ["/predictions/moneyline", setMl], ["/predictions/regulation", setReg],
    ["/combinations?maxLegs=" + maxLegs, setCombos], ["/lineups", setLineups],
  ], setError), [maxLegs]);

  useEffect(() => { load(); }, [load, reloadKey]);

  return (
    <div className="space-y-4">
      <ErrorBar errors={error} />
      {health?.dataWarning && <div className="rounded-lg border border-amber-800 bg-amber-950 p-3 text-sm text-amber-100">{health.dataWarning}</div>}

      <Card title="Yleiskatsaus">
        {summary ? (
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="Ennustesnapshotit" value={summary.totalPredictionSnapshots} sub={`selvitetty ${summary.settled}`} />
            <Stat label="Moneyline osumatarkkuus" value={summary.mlHitRate.n ? pct(summary.mlHitRate.rate) : "–"} sub={`n=${summary.mlHitRate.n}`} />
            <Stat label="1X2 osumatarkkuus" value={summary.regHitRate.n ? pct(summary.regHitRate.rate) : "–"} sub={`n=${summary.regHitRate.n}`} />
            <Stat label="Positiivinen EV nyt" value={summary.eligiblePositiveEvSelections} sub={summary.experimental ? "kokeellinen" : "validoitu"} />
            <Stat label="Brier (ML / 1X2)" value={`${num(summary.mlBrier)} / ${num(summary.regBrier)}`} sub="vertailu: 0.500 / 0.667" />
            <Stat label="Log loss (ML / 1X2)" value={`${num(summary.mlLogLoss)} / ${num(summary.regLogLoss)}`} sub="vertailu: 0.693 / 1.099" />
            <Stat label="Viimeisin päivitys" value={health?.lastRefresh ? fmtTime(health.lastRefresh.finishedAt) : "–"} />
            <Stat label="Otteluita tietokannassa" value={health?.games ?? "–"} />
          </div>
        ) : <Empty>Ladataan…</Empty>}
        <p className="mt-2 text-xs text-slate-500">{summary?.validationNote}</p>
        <div className="mt-3 flex flex-wrap gap-2 text-xs">
          {health?.providers.map((p) => <span key={p.id} title={p.detail} className="rounded bg-slate-800 px-2 py-1">{p.id}: {p.state}</span>)}
        </div>
      </Card>

      <Card title="Moneyline (sis. jatkoaika ja voittolaukaukset)"><MarketTable rows={ml} market="ML" /></Card>
      <Card title="Varsinainen peliaika 1X2 (60 min)"><MarketTable rows={reg} market="REG" /></Card>

      <Card title="Yhdistelmäehdotukset (kokeellinen)"
        right={<select value={maxLegs} onChange={(e) => setMaxLegs(Number(e.target.value))} className="rounded bg-slate-800 p-1 text-sm" aria-label="Valintoja enintään">
          <option value={2}>2 valintaa</option><option value={3}>2–3 valintaa</option></select>}>
        <div className="mb-3 rounded border border-amber-800 bg-amber-950 p-2 text-xs text-amber-100">
          Mikään yhdistelmä ei ole "varma". Jokainen lisävalinta pienentää osumatodennäköisyyttä ja kertoo vedonvälittäjän marginaalin yhä uudelleen.
          Alla oleva näyttö (z-luku) kertoo, kuinka paljon malli-EV:n voi uskoa; se jättää huomiotta mallivirheen ja ylikorostaa listan parasta.
        </div>
        {combos.length === 0 ? <Empty>Ei yhdistelmiä: tarvitaan tuoreet kertoimet ja vähintään kaksi positiivisen EV:n valintaa eri otteluissa.</Empty> : (
          <ul className="space-y-2 text-sm">
            {combos.map((c, i) => (
              <li key={i} className="rounded border border-slate-800 p-2">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span>{c.legs.join("  +  ")}</span>
                  <span className="flex gap-1">
                    <span className={`rounded px-2 py-0.5 text-xs ${c.evidence === "MODERATE" ? "bg-emerald-900 text-emerald-200" : "bg-amber-900 text-amber-100"}`}>näyttö: {c.evidence}</span>
                    <span className="rounded bg-slate-800 px-2 py-0.5 text-xs">{c.recommendation}</span>
                  </span>
                </div>
                <div className="mt-1 text-slate-300">Osumatn. {pct(c.jointProb)} (±{pct(c.jointProbApproxSe)}) · kerroin {num(c.combinedOdds, 2)} · reilu kerroin mallin mukaan {num(c.fairOddsAtModelProb, 2)}</div>
                <div className="text-slate-300">EV {signed(c.evPct)} % ± {num(c.evSePct, 1)} (z = {num(c.zScore, 2)}) · EV jos markkina on oikeassa: {signed(c.evIfMarketIsRightPct)} %</div>
                <div className="text-xs text-slate-500">{c.assumption}</div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="Kokoonpano- ja loukkaantumissyöte"><Empty>{lineups?.detail}</Empty></Card>
    </div>
  );
}

export default function App() {
  const [page, setPage] = useState(pageFromHash());
  const [report, setReport] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const f = () => setPage(pageFromHash());
    window.addEventListener("hashchange", f);
    return () => window.removeEventListener("hashchange", f);
  }, []);

  const refresh = async () => {
    setBusy(true); setReport(null); setError("");
    try { setReport(await api("/refresh", { method: "POST" })); } catch (e) { setError(e.message); }
    setBusy(false); setReloadKey((k) => k + 1);
  };
  const navigate = (event, nextPage) => {
    event.preventDefault();
    // Set state immediately; hashchange remains useful for browser back/forward navigation.
    window.location.hash = `/${nextPage}`;
    setPage(nextPage);
  };

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">NHL Prediction &amp; Value Analytics</h1>
          <p className="text-sm text-slate-400">Päätöstukityökalu ja tutkimus. Ei vedonlyöntiä, panoksia tai euromääräisiä tuloksia.</p>
        </div>
        <button onClick={refresh} disabled={busy} className="rounded-lg bg-sky-600 px-4 py-2 font-medium hover:bg-sky-500 disabled:opacity-50">
          {busy ? "Päivitetään…" : "Refresh Data & Settle Predictions"}
        </button>
      </header>

      <nav className="flex gap-1 border-b border-slate-800" aria-label="Sivut">
        {PAGES.map(([k, label]) => (
          <a key={k} href={`#/${k}`} onClick={(event) => navigate(event, k)} aria-current={page === k ? "page" : undefined}
            className={`rounded-t px-4 py-2 text-sm ${page === k ? "bg-slate-800 font-semibold text-white" : "text-slate-400 hover:text-white"}`}>{label}</a>
        ))}
      </nav>

      <ErrorBar errors={error} />
      {report && (
        <Card title="Päivitysraportti">
          <ul className="space-y-1 text-sm">
            {report.stages.map((s) => (
              <li key={s.stage}><span className={s.status === "OK" ? "text-emerald-400" : "text-red-400"}>{s.status}</span> · {s.stage}: {s.detail}</li>
            ))}
          </ul>
        </Card>
      )}

      {page === "dashboard" && <Dashboard reloadKey={reloadKey} />}
      {page === "suggestions" && <Suggestions reloadKey={reloadKey} />}
      {page === "history" && <History reloadKey={reloadKey} />}
      {page === "bankroll" && <Bankroll />}
    </div>
  );
}
