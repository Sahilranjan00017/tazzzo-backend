package com.tazzzo.content;

import java.util.regex.Pattern;

/**
 * Operational app configuration read by every app start: whether the store takes orders, a maintenance banner, the minimum
 * supported and latest app versions (force/soft update), support contacts, and the approved legal documents (terms, privacy
 * policy, refund policy) as https links. Absent fields mean "no constraint" / "not published yet".
 */
public record AppConfig(boolean storeOpen, boolean maintenance, String maintenanceMessage, String minAndroid, String latestAndroid,
                        String minIos, String latestIos, String supportPhone, String supportEmail, String termsUrl, String privacyUrl,
                        String refundPolicyUrl, long version) {

    public static final AppConfig DEFAULT = new AppConfig(true, false, null, null, null, null, null, null, null, 0);

    public static final int MAX_URL = 500;

    /** Without legal links. */
    public AppConfig(boolean storeOpen, boolean maintenance, String maintenanceMessage, String minAndroid, String latestAndroid,
                     String minIos, String latestIos, String supportPhone, String supportEmail, long version) {
        this(storeOpen, maintenance, maintenanceMessage, minAndroid, latestAndroid, minIos, latestIos, supportPhone, supportEmail,
                null, null, null, version);
    }

    static final Pattern VERSION = Pattern.compile("[0-9]{1,4}(\\.[0-9]{1,4}){0,3}");
    static final Pattern PHONE = Pattern.compile("\\+[1-9][0-9]{7,14}");
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}");

    /** @throws IllegalArgumentException the first violated rule */
    public void validate() {
        if (maintenanceMessage != null && (maintenanceMessage.isBlank() || maintenanceMessage.length() > 200
                || maintenanceMessage.chars().anyMatch(c -> c < 0x20 || c == 0x7F))) {
            throw new IllegalArgumentException("maintenanceMessage must be 1..200 chars of plain text");
        }
        if (maintenance && maintenanceMessage == null) throw new IllegalArgumentException("maintenance needs a message");
        for (String v : new String[]{minAndroid, latestAndroid, minIos, latestIos}) {
            if (v != null && !VERSION.matcher(v).matches()) throw new IllegalArgumentException("versions are dotted numbers like 1.4.2");
        }
        if (minAndroid != null && latestAndroid != null && compare(minAndroid, latestAndroid) > 0) throw new IllegalArgumentException("minAndroid exceeds latestAndroid");
        if (minIos != null && latestIos != null && compare(minIos, latestIos) > 0) throw new IllegalArgumentException("minIos exceeds latestIos");
        if (supportPhone != null && !PHONE.matcher(supportPhone).matches()) throw new IllegalArgumentException("supportPhone must be E.164");
        if (supportEmail != null && !EMAIL.matcher(supportEmail).matches()) throw new IllegalArgumentException("supportEmail is not an email address");
        requireHttpsUrl(termsUrl, "termsUrl");
        requireHttpsUrl(privacyUrl, "privacyUrl");
        requireHttpsUrl(refundPolicyUrl, "refundPolicyUrl");
    }

    /** null, or an absolute https URL with a host, no credentials, no whitespace, at most {@link #MAX_URL} chars. */
    static void requireHttpsUrl(String url, String what) {
        if (url == null) return;
        String problem = what + " must be an absolute https URL";
        if (url.length() > MAX_URL || url.chars().anyMatch(c -> c <= 0x20 || c == 0x7F)) throw new IllegalArgumentException(problem);
        java.net.URI u;
        try {
            u = new java.net.URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(problem);
        }
        if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getHost().isBlank() || u.getRawUserInfo() != null
                || u.getRawAuthority() == null || u.getRawAuthority().contains("@")) {
            throw new IllegalArgumentException(problem);
        }
    }

    /** Numeric, segment-wise comparison of dotted versions (1.10 > 1.9). */
    public static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? Integer.parseInt(x[i]) : 0;
            int yi = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }
}
