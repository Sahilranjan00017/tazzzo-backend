package com.tazzzo.catalog.migration;

import java.util.regex.Pattern;

/** Makes failure text safe to persist and log: no connection strings, no credentials, bounded length. */
public final class MigrationSanitizer {
    private static final Pattern URI = Pattern.compile("(?i)mongodb(\\+srv)?://\\S+");
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(password|passwd|pwd|secret|token|apikey|api_key)\\s*[=:]\\s*\\S+");
    private static final Pattern USERINFO = Pattern.compile("(?i)[a-z][a-z0-9+.-]*://[^\\s/@]+:[^\\s/@]+@\\S*");
    private static final int MAX = 500;

    private MigrationSanitizer() { }

    public static String safeMessage(Throwable t) {
        String raw = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return sanitize(raw);
    }

    public static String sanitize(String raw) {
        String s = URI.matcher(raw).replaceAll("mongodb://***");
        s = USERINFO.matcher(s).replaceAll("***://***");
        s = CREDENTIAL.matcher(s).replaceAll("$1=***");
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > MAX ? s.substring(0, MAX) + "…" : s;
    }
}
