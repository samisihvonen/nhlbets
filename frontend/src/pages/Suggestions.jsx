import React, { useEffect, useState } from "react";
import { api, Card, Badge, Empty, ErrorBar, fmtTime, pct, num, signed } from "../ui.jsx";

const EVIDENCE = { MODERATE: "bg-emerald-900 text-emerald-200", LOW: "bg-amber-900 text-amber-100", WEAK: "bg-slate-800 text-slate-300" };

function SuggestionTable({ items, showAll, counts, emptyHint }) {
  const rows = showAll ? items : items.filter((i) => i.classification.startsWith("POSITIVE"));
  if (rows.length === 0) {
    return (
      <Empty>
        {counts && counts.withPrediction === 0 ? "Ei ennusteita: joukkueilla ei vielä riittävästi otteluhistoriaa tai otteluita ei ole haettu." :
          counts && counts.withFreshOdds === 0 ? emptyHint :
            "Ei positiivisen EV:n ehdokkaita nykyisillä tuoreilla kertoimilla. Se on usein oikea tulos."}
      </Empty>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="text-left text-xs text-slate-400">
          <tr><th>Ottelu</th><th>Valinta</th><th>Malli</th><th>Markkina</th><th>Edge (pp)</th><th>Paras kerroin</th><th>EV % ± SE</th><th>Näyttö</th><th>Luokitus</th></tr>
        </thead>
        <tbody>
          {rows.map((it) => (
            <React.Fragment key={`${it.gameId}-${it.market}-${it.selection}`}>
              <tr className="border-t border-slate-800 align-top">
                <td className="py-1">{it.game}<div className="text-xs text-slate-400">{fmtTime(it.startUtc)}</div></td>
                <td className="font-medium">{it.label}</td>
                <td>{pct(it.modelProb)}</td>
                <td>{pct(it.marketProb)}</td>
                <td>{signed(it.edgePp)}</td>
                <td>{num(it.bestOdds, 2)}<div className="text-xs text-slate-400">{it.provider} · {fmtTime(it.oddsAt)}</div></td>
                <td>{signed(it.evPct)} ± {num(it.evSePct, 1)}</td>
                <td>z = {num(it.zScore, 2)}<div><span className={`rounded px-2 py-0.5 text-xs ${EVIDENCE[it.evidence]}`}>{it.evidence}</span></div></td>
                <td><Badge cls={it.classification} /><div className="mt-1 text-xs text-slate-400">{it.recommendation}</div></td>
              </tr>
              <tr>
                <td colSpan={9} className="pb-2">
                  <details className="text-xs text-slate-400">
                    <summary className="cursor-pointer">Perustelut</summary>
                    <ul className="ml-5 mt-1 list-disc">{it.reasons.map((r) => <li key={r}>{r}</li>)}</ul>
                  </details>
                </td>
              </tr>
            </React.Fragment>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ManualOdds({ onSaved }) {
  const [games, setGames] = useState([]);
  const [f, setF] = useState({ gameId: "", market: "MONEYLINE", provider: "veikkaus", home: "", draw: "", away: "" });
  const [msg, setMsg] = useState("");
  useEffect(() => { api("/games?state=SCHEDULED").then((g) => setGames(g.slice(0, 40))).catch(() => {}); }, []);
  const n = (v) => (v === "" ? null : Number(String(v).replace(",", ".")));
  const submit = async () => {
    try {
      const r = await api("/odds/manual", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ gameId: Number(f.gameId), market: f.market, provider: f.provider, home: n(f.home), draw: f.market === "REG_1X2" ? n(f.draw) : null, away: n(f.away) }),
      });
      setMsg(`Tallennettu ${r.stored} kerrointa (${r.provider}): ${r.game}`);
      onSaved();
    } catch (e) { setMsg("Virhe: " + e.message); }
  };
  const inp = "w-24 rounded bg-slate-800 p-1";
  return (
    <Card title="Syötä kertoimet käsin (esim. Veikkaus, Paf)">
      <p className="mb-2 text-xs text-slate-400">Lue kertoimet itse tarjoajan sivulta ja kirjoita ne tähän. Sovellus ei hae niitä tarjoajan sivuilta. Syötetty kerroin vanhenee {60} min kuluttua (kuten muutkin).
        Tarkista markkinan määritelmä: moneyline sisältää jatkoajan ja voittolaukaukset, 1X2 on 60 minuutin tulos.</p>
      <div className="flex flex-wrap items-end gap-2 text-sm">
        <select value={f.gameId} onChange={(e) => setF({ ...f, gameId: e.target.value })} className="rounded bg-slate-800 p-1" aria-label="Ottelu">
          <option value="">Valitse ottelu…</option>
          {games.map((g) => <option key={g.gameId} value={g.gameId}>{g.away} @ {g.home} · {fmtTime(g.startUtc)}</option>)}
        </select>
        <select value={f.market} onChange={(e) => setF({ ...f, market: e.target.value })} className="rounded bg-slate-800 p-1" aria-label="Markkina">
          <option value="MONEYLINE">Moneyline (sis. JA/VL)</option><option value="REG_1X2">1X2 (60 min)</option>
        </select>
        <input value={f.provider} onChange={(e) => setF({ ...f, provider: e.target.value })} className="w-28 rounded bg-slate-800 p-1" aria-label="Tarjoaja" placeholder="tarjoaja" />
        <input value={f.home} onChange={(e) => setF({ ...f, home: e.target.value })} className={inp} placeholder="Koti / 1" aria-label="Kotikerroin" />
        {f.market === "REG_1X2" && <input value={f.draw} onChange={(e) => setF({ ...f, draw: e.target.value })} className={inp} placeholder="Tasapeli X" aria-label="Tasapelikerroin" />}
        <input value={f.away} onChange={(e) => setF({ ...f, away: e.target.value })} className={inp} placeholder="Vieras / 2" aria-label="Vierasker­roin" />
        <button onClick={submit} disabled={!f.gameId} className="rounded bg-sky-600 px-3 py-1 hover:bg-sky-500 disabled:opacity-50">Tallenna</button>
      </div>
      {msg && <p className="mt-2 text-sm text-slate-300">{msg}</p>}
    </Card>
  );
}

export default function Suggestions({ reloadKey }) {
  const [data, setData] = useState(null);
  const [err, setErr] = useState("");
  const [showAll, setShowAll] = useState(false);

  const reload = () => api("/suggestions").then((d) => { setData(d); setErr(""); }).catch((e) => setErr("Ehdotukset eivät latautuneet: " + e.message));
  useEffect(() => { reload(); }, [reloadKey]);

  const hint = "Ei tuoreita kertoimia (enintään " + (data?.oddsMaxAgeMinutes ?? 60) + " min vanhoja). Aseta ODDS_API_KEY ja paina päivitä; kertoimien haku on kiintiösyistä rajattu (oletus 4 h välein).";
  const by = (m) => (data ? data.items.filter((i) => i.market === m) : []);

  return (
    <div className="space-y-4">
      <ErrorBar errors={err} />
      <div className="rounded-lg border border-amber-800 bg-amber-950 p-3 text-sm text-amber-100">
        <strong>Ehdokkaat, ei suosituksia.</strong> Lista järjestää valinnat näytön vahvuuden (z = EV / keskivirhe) mukaan. Positiivinen EV on mallin estimaatti,
        ei takuu tuotosta. Malli on {data?.experimental ? "kokeellinen: yhtään valintaa ei suositella" : "validoitu rajallisesti"}. Sovellus ei anna panossuosituksia.
      </div>

      <Card title="Moneyline (sis. jatkoaika ja voittolaukaukset)"
        right={<label className="text-sm"><input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} className="mr-1" />Näytä myös ei-arvoiset valinnat</label>}>
        {!data ? <Empty>Ladataan…</Empty> : <SuggestionTable items={by("MONEYLINE")} showAll={showAll} counts={data.counts.MONEYLINE} emptyHint={hint} />}
        {data && <p className="mt-2 text-xs text-slate-500">Otteluita {data.counts.MONEYLINE.games}, ennusteita {data.counts.MONEYLINE.withPrediction}, tuoreilla kertoimilla {data.counts.MONEYLINE.withFreshOdds}, positiivinen EV {data.counts.MONEYLINE.positiveEv}.</p>}
      </Card>

      <Card title="Varsinainen peliaika 1X2 (60 min)">
        {!data ? <Empty>Ladataan…</Empty> : <SuggestionTable items={by("REG_1X2")} showAll={showAll} counts={data.counts.REG_1X2}
          emptyHint={hint + " 1X2-kertoimia tarjoaa vain osa vedonvälittäjistä."} />}
        {data && <p className="mt-2 text-xs text-slate-500">Otteluita {data.counts.REG_1X2.games}, ennusteita {data.counts.REG_1X2.withPrediction}, tuoreilla kertoimilla {data.counts.REG_1X2.withFreshOdds}, positiivinen EV {data.counts.REG_1X2.positiveEv}. Tasapelin kerrointa ei koskaan johdeta moneylinesta.</p>}
      </Card>

      <ManualOdds onSaved={reload} />

      {data && (
        <Card title="Lue ennen käyttöä">
          <p className="mb-1 text-xs text-slate-400">Järjestysperuste: {data.rankBasis}. Minimi-EV: {data.minEvPct} %.</p>
          <ul className="list-disc pl-5 text-xs text-slate-400">{data.warnings.map((w) => <li key={w}>{w}</li>)}</ul>
        </Card>
      )}
    </div>
  );
}
