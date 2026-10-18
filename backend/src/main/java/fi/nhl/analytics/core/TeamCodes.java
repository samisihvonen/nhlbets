package fi.nhl.analytics.core;

import java.util.Map;

public final class TeamCodes {
    private TeamCodes() {}
    private static final Map<String, String> ALIAS = Map.of("TB", "TBL", "NJ", "NJD", "SJ", "SJS", "LA", "LAK");

    /** MoneyPuck-style codes (T.B, N.J, S.J, L.A) -> NHL API abbreviations. */
    public static String normalize(String code) {
        if (code == null) return "";
        String c = code.toUpperCase().replaceAll("[^A-Z]", "");
        return ALIAS.getOrDefault(c, c);
    }
}
