import React, { useCallback, useEffect, useState } from "react";
import { api, Card, Empty, ErrorBar, Calibration, loadAll, fmtTime, pct, num, signed, ok, hit } from "../ui.jsx";

const STATUS = [["ALL", "Kaikki"], ["SETTLED", "Selvitetyt"], ["PENDING", "Odottavat"], ["VOID", "Mitätöidyt"]];
const REG = ["1", "X", "2"], ML = ["Koti", "Vieras"];
const stColor = { SETTLED: "bg-emerald-900 text-emerald-200", PENDING: "bg-amber-900 text-amber-100", VOID: "bg-slate-700 text-slate-200" };
const mark = (h) => (h == null ? "–" : h ? "✓" : "✗");

export default function History({ reloadKey }) {
  const [filters, setFilters] = useState({ from: "", to: "", status: "ALL" });
  const [rows, setRows] = useState([]);
  const [acc, setAcc] = useState(null);
  const [clv, setClv] = useState(null);
  const [bt, setBt] = useState(null);
  const [mbt, setMbt] = useState(null);
  const [importRes, setImportRes] = useState(null);
  const [fmt, setFmt] = useState("american");
  const [source, setSource] = useState("pinnacle");
  const [season, setSeason] = useState("");
  const [error, setError] = useState("");

  const load = useCallback(() => {
    const q = new URLSearchParams(Object.entries(filters).filter(([k, v]) => v && k !== "status")).toString();
    const hq = new URLSearchParams({ ...Object.fromEntries(new URLSearchParams(q)), status: filters.status, limit: "200" }).toString();
    return loadAll([["/history?" + hq, setRows], ["/stats/accuracy?" + q, setAcc], ["/clv", setClv]], setError);
  }, [filters]);

  useEffect(() => { load(); }, [load, reloadKey]);

  const upload = async (path, file, params) => {
    if (!file) return;
    const fd = new FormData();
    fd.append("file", file);
    try { setImportRes(await api(path + "?" + new URLSearchParams(params), { method: "POST", body: fd })); load(); }
    catch (e) { setError(e.message); }
  };
  const run = async (path, set) => { try { set(await api(path, { method: "POST" })); } catch (e) { setError(e.message); } };

  return (
    <div className="space-y-4">
      <ErrorBar errors={error} />

      <Card title="Ennusteiden ja selvitysten historia">
        <div className="mb-3 flex flex-wrap items-end gap-3 text-sm">
          <label>Alkaen <input type="date" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })} className="ml-1 rounded bg-slate-800 p-1" /></label>
          <label>Päättyen <input type="date" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })} className="ml-1 rounded bg-slate-800 p-1" /></label>
          <label>Tila <select value={filters.status} onChange={(e) => setFilters({ ...filters, status: e.target.value })} className="ml-1 rounded bg-slate-800 p-1">
            {STATUS.map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
          <span className="text-xs text-slate-400">{rows.length} riviä (viimeisin ennuste ennen alkua per ottelu)</span>
        </div>
        {rows.length === 0 ? <Empty>Ei rivejä valituilla suodattimilla. Selvitettyjä ennusteita syntyy, kun ennustetut ottelut on pelattu ja päivitys ajettu.</Empty> : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-slate-400">
                <tr><th>Alkoi</th><th>Ottelu</th><th>Tulos</th><th>Tila</th><th>1X2-ennuste</th><th>1X2</th><th>ML-ennuste</th><th>ML</th><th>Liputettu</th><th>Malli</th></tr>
              </thead>
              <tbody>
                {rows.map((h) => (
                  <tr key={h.gameId} className="border-t border-slate-800">
                    <td className="py-1 text-xs">{fmtTime(h.startUtc)}</td>
                    <td>{h.game}</td>
                    <td>{h.score ?? "–"}</td>
                    <td><span className={`rounded px-2 py-0.5 text-xs ${stColor[h.status]}`}>{h.status}</span></td>
                    <td>{REG[h.regPick]} <span className="text-xs text-slate-400">({h.regProbs.map((p) => pct(p, 0)).join("/")})</span></td>
                    <td>{h.regActual == null ? "–" : `${REG[h.regActual]} ${mark(h.regHit)}`}</td>
                    <td>{ML[h.mlPick]} <span className="text-xs text-slate-400">({pct(h.mlProbs[h.mlPick], 0)})</span></td>
                    <td>{h.mlActual == null ? "–" : `${ML[h.mlActual]} ${mark(h.mlHit)}`}</td>
                    <td title="Valintoja, joiden EV oli snapshotissa yli kynnyksen">{h.flaggedAtSnapshot}</td>
                    <td className="text-xs">{h.modelVersion}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title="Tarkkuus ja kalibrointi">
        {!acc ? <Empty>Ladataan…</Empty> : (
          <div className="grid gap-4 md:grid-cols-2">
            <div className="space-y-1 text-sm">
              <div>Moneyline osumatarkkuus: {hit(acc.moneyline.hit)}</div>
              <div>1X2 osumatarkkuus: {hit(acc.regulation1x2.hit)}</div>
              <div>Brier ML {num(acc.moneyline.brier)} · 1X2 {num(acc.regulation1x2.brier)} (tasajakauma 0.500 / 0.667)</div>
              <div>Log loss ML {num(acc.moneyline.logLoss)} · 1X2 {num(acc.regulation1x2.logLoss)} (tasajakauma 0.693 / 1.099)</div>
              <div>Positiivisen EV:n valinnat: {hit(acc.positiveEvSelections)}</div>
              <p className="text-xs text-slate-500">{acc.note}</p>
            </div>
            <div className="space-y-3">
              <Calibration data={acc.moneyline.calibrationHomeWin} title="Moneyline: kotivoiton kalibrointi" />
              <Calibration data={acc.regulation1x2.calibrationPooledClasses} title="1X2: kalibrointi (kaikki luokat)" />
            </div>
          </div>
        )}
        <div className="mt-4">
          <button onClick={() => run("/backtest?warmup=100", setBt)} className="rounded bg-slate-700 px-3 py-1 text-sm hover:bg-slate-600">Aja walk-forward-backtest</button>
          {bt && (
            <div className="mt-2 text-sm">
              <div>Ennustettu {bt.predicted} / {bt.games} ottelusta · ML osuma {pct(bt.mlHitRate)} · 1X2 osuma {pct(bt.regHitRate)}</div>
              <div>Brier ML {num(bt.mlBrier)} (vertailu {num(bt.mlBrierUniform)}) · 1X2 {num(bt.regBrier)} (vertailu {num(bt.regBrierUniform)})</div>
              <Calibration data={bt.mlHomeCalibration} title="Backtest: kotivoiton kalibrointi" />
              <p className="text-xs text-slate-500">{bt.note}</p>
            </div>
          )}
        </div>
      </Card>

      <Card title="CLV-analyysi (Closing Line Value)">
        {!clv ? <Empty>Ladataan…</Empty> : (
          <div className="space-y-3 text-sm">
            <div className="text-slate-300">Vertailulinja: {clv.referenceProvider} · ennustesnapshotteja ennen alkua: {clv.coverage.gamesWithPreGameSnapshot} · ilman käyttökelpoista sulkeutumislinjaa: {clv.coverage.marketsWithoutUsableClosingLine}</div>
            {clv.message && <Empty>{clv.message}</Empty>}
            <table className="w-full text-xs">
              <thead className="text-left text-slate-400"><tr><th>Ryhmä</th><th>n</th><th>CLV % (keskiarvo)</th><th>95 % CI</th><th>CLV &gt; 0</th></tr></thead>
              <tbody>
                {[["Kaikki liputetut", clv.flaggedClvPct], ["Sulkeutumis-EV %", clv.flaggedClosingEvPct], ...Object.entries(clv.clvByMarket), ...Object.entries(clv.clvByLeadTime)].map(([k, v]) => (
                  <tr key={k} className="border-t border-slate-800">
                    <td className="py-1">{k}</td><td>{v.n}</td><td>{signed(v.mean, 2)}</td>
                    <td>{ok(v.ci95Lo) ? `${signed(v.ci95Lo, 2)} … ${signed(v.ci95Hi, 2)}` : "–"}</td><td>{pct(v.shareAboveZero, 0)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div>Malli vs sulkeutumislinja (ML, n={clv.benchmarkVsClosingLine.n}): Brier {num(clv.benchmarkVsClosingLine.modelBrierMl)} vs {num(clv.benchmarkVsClosingLine.closingLineBrierMl)}, ero {signed(clv.benchmarkVsClosingLine.brierDiffMean, 4)} ± {num(clv.benchmarkVsClosingLine.brierDiffSe, 4)} (negatiivinen = malli voitti markkinan)</div>
            <ul className="list-disc pl-5 text-xs text-slate-500">{clv.notes.map((n) => <li key={n}>{n}</li>)}</ul>
          </div>
        )}
      </Card>

      <Card title="Datan tuonti ja markkinabacktest">
        <div className="space-y-4 text-sm">
          <div>
            <div className="font-medium">Historialliset kertoimet (CSV, esim. Kaggle/SBR)</div>
            <p className="text-xs text-slate-400">Sarakkeet: date, home_team, away_team, home_ml_close, away_ml_close (+ valinnaisesti home_ml_open, away_ml_open). Ottelut pitää olla ensin haettu (Refresh/backfill).</p>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <select value={fmt} onChange={(e) => setFmt(e.target.value)} className="rounded bg-slate-800 p-1">
                <option value="american">Amerikkalainen (-130/+110)</option><option value="decimal">Desimaali</option>
              </select>
              <input value={source} onChange={(e) => setSource(e.target.value)} className="w-32 rounded bg-slate-800 p-1" aria-label="Lähteen nimi" />
              <input type="file" accept=".csv" onChange={(e) => upload("/import/odds", e.target.files[0], { format: fmt, source })} />
            </div>
          </div>
          <div>
            <div className="font-medium">xG-data (MoneyPuck-tyylinen joukkue-CSV)</div>
            <p className="text-xs text-slate-400">Näytetään vain kontekstina, ei mallissa (kausitason xG vuotaisi tulevaa tietoa backtestiin). Tarkista lähteen käyttöehdot.</p>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <input value={season} onChange={(e) => setSeason(e.target.value)} placeholder="kausi, esim. 2025" className="w-36 rounded bg-slate-800 p-1" />
              <input type="file" accept=".csv" onChange={(e) => upload("/import/xg", e.target.files[0], season ? { season } : {})} />
            </div>
          </div>
          {importRes && <pre className="overflow-x-auto rounded bg-slate-950 p-2 text-xs">{JSON.stringify(importRes, null, 2)}</pre>}
          <div>
            <button onClick={() => run(`/backtest/market?warmup=100&minEv=3&source=${encodeURIComponent(source)}`, setMbt)} className="rounded bg-slate-700 px-3 py-1 hover:bg-slate-600">Aja markkinabacktest (lähde: {source})</button>
            {mbt && (
              <div className="mt-2">
                <div>Arvioitu {mbt.evaluated} ottelua ({mbt.withClosingOdds} kertoimilla {mbt.games} ottelusta)</div>
                <div>Brier: malli {num(mbt.modelBrier)} vs sulkeutumislinja {num(mbt.marketBrier)}, ero {signed(mbt.brierDiffMean, 4)} ± {num(mbt.brierDiffSe, 4)}</div>
                <div>Liputetut valinnat: {mbt.flaggedSelections} · osumat {pct(mbt.flaggedHitRate)} · hypoteettinen tuotto {signed(mbt.flaggedMeanReturnPct, 1)} % ± {num(mbt.flaggedReturnSePct, 1)} yksikköä kohti</div>
                <p className="text-xs text-slate-500">{mbt.note}</p>
              </div>
            )}
          </div>
        </div>
      </Card>
    </div>
  );
}
