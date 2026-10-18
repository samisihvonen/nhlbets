package fi.nhl.analytics.core;

import java.util.*;

public final class Csv {
    private Csv() {}

    /** Parses RFC-4180-style CSV (quotes, doubled quotes, CRLF). Header names are lower-cased and trimmed. */
    public static List<Map<String, String>> parse(String text) {
        if (text != null && text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; text != null && i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; } else q = false; }
                else f.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { row.add(f.toString()); f.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(f.toString()); f.setLength(0);
                if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(row);
                row = new ArrayList<>();
            } else f.append(c);
        }
        if (f.length() > 0 || !row.isEmpty()) { row.add(f.toString()); rows.add(row); }
        List<Map<String, String>> out = new ArrayList<>();
        if (rows.isEmpty()) return out;
        List<String> head = rows.get(0).stream().map(h -> h.trim().toLowerCase()).toList();
        for (int r = 1; r < rows.size(); r++) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < head.size() && c < rows.get(r).size(); c++) m.put(head.get(c), rows.get(r).get(c).trim());
            out.add(m);
        }
        return out;
    }
}
