package fi.nhl.analytics.core;

import java.time.LocalDate;

/** A finished regular-season game. Regulation score is derived from the final score and last period type. */
public record GameResult(LocalDate date, String home, String away, int homeReg, int awayReg,
                         boolean homeWon, String periodType) {

    public static GameResult fromFinal(LocalDate date, String home, String away, int hs, int as, String lastPeriodType) {
        if (hs == as) throw new IllegalArgumentException("NHL games cannot end tied");
        String pt = lastPeriodType == null ? "REG" : lastPeriodType;
        int hr, ar;
        if ("REG".equals(pt)) { hr = hs; ar = as; }
        else { int m = Math.min(hs, as); hr = m; ar = m; } // OT/SO winner scores exactly one extra goal
        return new GameResult(date, home, away, hr, ar, hs > as, pt);
    }

    public boolean extraTime() { return homeReg == awayReg; }
    /** 0 = home leads after 60 min, 1 = tied, 2 = away leads. */
    public int regIndex() { return homeReg > awayReg ? 0 : (homeReg == awayReg ? 1 : 2); }
    /** 0 = home wins incl. OT/SO, 1 = away wins. */
    public int mlIndex() { return homeWon ? 0 : 1; }
}
