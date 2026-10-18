package fi.nhl.analytics.core;

import java.text.Normalizer;

public final class TeamNames {
    private TeamNames() {}
    public static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("[^a-z]", "");
    }
    /** Odds-provider name matches NHL team if equal to full name or ends with the common name. */
    public static boolean matches(String oddsName, String fullName, String commonName) {
        String n = norm(oddsName);
        if (n.isEmpty()) return false;
        return n.equals(norm(fullName)) || (!norm(commonName).isEmpty() && n.endsWith(norm(commonName)));
    }

    /** Looser matching for CSV datasets: full name, common name, place name, abbreviation, or short aliases (NYRangers, LA Kings). */
    public static boolean matches(String name, String full, String common, String abbrev) {
        if (matches(name, full, common)) return true;
        String n = norm(name), f = norm(full);
        if (n.isEmpty()) return false;
        if (abbrev != null && n.equals(norm(abbrev))) return true;
        String n2 = n;
        if (n.startsWith("ny")) n2 = "newyork" + n.substring(2);
        else if (n.startsWith("la") && !n.startsWith("las")) n2 = "losangeles" + n.substring(2);
        else if (n.startsWith("nj")) n2 = "newjersey" + n.substring(2);
        else if (n.startsWith("tb")) n2 = "tampabay" + n.substring(2);
        else if (n.startsWith("sj")) n2 = "sanjose" + n.substring(2);
        return n2.equals(f) || (n2.length() >= 4 && (f.startsWith(n2) || f.endsWith(n2)));
    }
}
