package com.tazzzo.admin.audit;

import org.bson.types.ObjectId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The position AFTER the last event of a page, in the total order (at DESC, source rank ASC, _id DESC). Opaque to clients:
 * base64url of {@code v1|<epochMillis>|<source>|<objectIdHex>|<filterFingerprint>}. It is bound to the filters it was
 * issued for, so replaying it under different filters is a 400 rather than a silently inconsistent page. It carries no
 * actor identity, no request id and nothing secret; decoding is strict, and anything else is {@code cursor is malformed}.
 */
record AuditCursor(long atMillis, AuditSource source, ObjectId id, String fingerprint) {

    private static final Pattern PLAIN =
            Pattern.compile("^v1\\|(0|[1-9][0-9]{0,14})\\|(pe|ne|de)\\|([0-9a-f]{24})\\|([0-9a-f]{16})$");

    String encode() {
        String plain = "v1|" + atMillis + "|" + source.code + "|" + id.toHexString() + "|" + fingerprint;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.US_ASCII));
    }

    static AuditCursor decode(String token, AuditEventQuery query) {
        String plain;
        try {
            plain = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            throw new AuditQueryRejected("cursor is malformed");
        }
        Matcher m = PLAIN.matcher(plain);
        if (!m.matches()) {
            throw new AuditQueryRejected("cursor is malformed");
        }
        if (!m.group(4).equals(fingerprint(query))) {
            throw new AuditQueryRejected("cursor does not belong to these filters");
        }
        return new AuditCursor(Long.parseLong(m.group(1)), AuditSource.fromCode(m.group(2)), new ObjectId(m.group(3)),
                m.group(4));
    }

    static String fingerprint(AuditEventQuery query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(query.filterFingerprintSource().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
