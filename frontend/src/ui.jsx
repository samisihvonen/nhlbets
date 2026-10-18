import React from "react";

export const TZ = "Europe/Helsinki";
export const fmtTime = (iso) => (iso ? new Date(iso).toLocaleString("fi-FI", { timeZone: TZ, dateStyle: "short", timeStyle: "short" }) : "–");
export const ok = (x) => x != null && Number.isFinite(Number(x));
export const pct = (x, d = 1) => (ok(x) ? (Number(x) * 100).toFixed(d) + " %" : "–");
export const num = (x, d = 3) => (ok(x) ? Number(x).toFixed(d) : "–");
export const signed = (x, d = 1) => (ok(x) ? (x > 0 ? "+" : "") + Number(x).toFixed(d) : "–");
export const LABEL = { HOME: "Koti", DRAW: "Tasapeli", AWAY: "Vieras" };
export const REG_LABEL = { HOME: "Kotivoitto (1)", DRAW: "Tasapeli (X)", AWAY: "Vierasvoitto (2)" };

export async function api(path, opts) {
  const res = await fetch("/api" + path, opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

export const Card = ({ title, children, right }) => (
  <section className="rounded-xl border border-slate-800 bg-slate-900 p-4">
    <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
      <h2 className="text-lg font-semibold">{title}</h2>
      {right}
    </div>
    {children}
  </section>
);

export const Badge = ({ cls }) => {
  const color = cls?.startsWith("POSITIVE") ? "bg-emerald-900 text-emerald-200"
    : cls === "NO_VALUE_IDENTIFIED" ? "bg-slate-800 text-slate-300" : "bg-amber-900 text-amber-100";
  return <span className={`rounded px-2 py-0.5 text-xs ${color}`}>{cls}</span>;
};

export const Empty = ({ children }) => <p className="text-sm text-slate-400">{children}</p>;

export function Stat({ label, value, sub }) {
  return (
    <div className="rounded-lg bg-slate-800 p-3">
      <div className="text-xs text-slate-400">{label}</div>
      <div className="text-xl font-semibold">{value}</div>
      {sub && <div className="text-xs text-slate-400">{sub}</div>}
    </div>
  );
}

export const hit = (h) => (h?.n ? `${pct(h.rate)} (n=${h.n}, 95% CI ${pct(h.ci95[0], 0)}–${pct(h.ci95[1], 0)})` : "ei vielä dataa (n=0)");

export function ErrorBar({ errors }) {
  if (!errors) return null;
  return <div role="alert" className="rounded-lg border border-red-800 bg-red-950 p-3 text-sm text-red-200">{errors}</div>;
}

export function Calibration({ data, title }) {
  if (!data?.length) return <Empty>Ei kalibrointidataa.</Empty>;
  return (
    <div>
      <div className="mb-1 text-sm text-slate-300">{title}</div>
      {data.map((b) => (
        <div key={b.lower} className="mb-1 flex items-center gap-2 text-xs">
          <span className="w-20">{pct(b.lower, 0)}–{pct(b.upper, 0)}</span>
          <div className="relative h-3 flex-1 rounded bg-slate-800">
            <div className="absolute h-3 rounded bg-sky-500" style={{ width: `${b.observed * 100}%` }} />
            <div className="absolute top-0 h-3 w-0.5 bg-amber-300" style={{ left: `${b.meanPredicted * 100}%` }} />
          </div>
          <span className="w-40 text-right">enn. {pct(b.meanPredicted, 0)} / havaittu {pct(b.observed, 0)} (n={b.n})</span>
        </div>
      ))}
    </div>
  );
}

/** Runs several GETs independently so one failing endpoint never blanks the page. */
export async function loadAll(specs, onErrors) {
  const errors = [];
  await Promise.all(specs.map(([path, set]) => api(path).then(set).catch((e) => errors.push(`${path.split("?")[0]}: ${e.message}`))));
  onErrors(errors.length ? "Osa tiedoista ei latautunut (muu sivu toimii): " + errors.join(" | ") : "");
}
