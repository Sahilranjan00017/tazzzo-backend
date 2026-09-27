package com.tazzzo.catalog.consumer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * LIST-CURSOR-1 — the v1 product-list cursor: stateless, versioned, HMAC-SHA256 signed,
 * Base64URL without padding, OPAQUE to clients.
 *
 * <p><b>Wire form:</b> {@code base64url( payload || HMAC-SHA256(key, payload) )}. The payload is a
 * canonical, unambiguous text — each field percent-encoded (so no field can contain the separator)
 * and joined with {@code |} in a FIXED order. The shape is PER-ROUTE (final review, compatibility
 * gate): the legacy {@code /catalog/v1} route ({@link #ROUTE}) keeps the ORIGINAL, pre-PR-10B
 * 9-field shape byte-for-byte — a cursor issued before PR-10B decodes unchanged after it — while
 * the commerce {@link #COMMERCE_ROUTE} route uses a 10th, location-bound field:
 * <pre>
 *   legacy (route=list):     1 | list          | 1 | node_id | release | page_size | id | asc | last_product_id
 *   commerce (route=commerce-list):
 *                            1 | commerce-list | 1 | node_id | release | page_size | id | asc | last_product_id | location_context
 *   ^cursor                     ^query                                              ^sort ^direction              ^PR-10B: bound
 *    version                    version                                                                           location
 * </pre>
 * Everything the listing's identity depends on is bound and signed (L-7): a cursor from another
 * node, release, page size, sort, direction — or, on the commerce route, LOCATION CONTEXT — is
 * rejected, and a client cannot edit any of it. {@code location_context} is a caller-supplied
 * opaque token (never the raw PIN — callers must fingerprint it, e.g. via {@link #fingerprint(String)})
 * so continuing a page under a different location/serviceability/inventory routing context is
 * impossible: the caller compares its current location context against
 * {@link ListCursor#locationContext()} and rejects a mismatch as {@code INVALID_CURSOR} before this
 * codec is even involved. The legacy route has no location concept at all — it never reads or
 * writes a 10th field — so {@link ListCursor#locationContext()} on a legacy-decoded cursor is
 * always the fixed {@link #LOCATION_UNBOUND} sentinel, never carried on the wire.
 * It does NOT bind {@code projectionVersion}, prices, inventory or the current-release pointer —
 * projection stays live and pagination stays on the release that started it.
 *
 * <p><b>Verification order:</b> length cap → decode (canonical spelling only) → signature
 * (constant-time) → THEN parse. A
 * tampered payload fails on the signature, never on a parser exception; an unknown version is
 * refused after the signature proves we minted it.
 *
 * <p><b>The key is standard (RFC 4648 §4) Base64</b> of at least 32 bytes; the URL-safe alphabet
 * is not accepted for the key, so one configured value has one meaning.
 *
 * <p><b>The key never leaves this class.</b> It is not in any exception message, log line, metric,
 * payload or response. Every rejection is the same typed {@link ConsumerFailures.InvalidCursor}
 * with a generic message.
 */
@Component
@EnableConfigurationProperties(ConsumerCursorProperties.class)
public class ConsumerCursorCodec {

    /** LIST-CURSOR-1: anything longer is INVALID_CURSOR before parsing. */
    public static final int MAX_ENCODED_LENGTH = 2048;
    /** The key must decode to at least this many bytes. */
    public static final int MIN_KEY_BYTES = 32;

    static final int CURSOR_VERSION = 1;
    static final int QUERY_VERSION = 1;
    static final String ROUTE = "list";
    /**
     * PR-10B: the commerce /v1 list surface signs cursors with a DISTINCT route literal so a
     * cursor minted for {@code /catalog/v1} is rejected on {@code /v1} and vice-versa (the
     * route literal is part of the signed, validated payload). Same HMAC key, same crypto — only
     * the namespacing literal differs, injected via the {@code (properties, route, queryVersion)}
     * constructor. The default {@code @Component} keeps {@code "list"} so the consumer surface is
     * byte-for-byte unchanged.
     */
    public static final String COMMERCE_ROUTE = "commerce-list";
    static final String SORT = "id";
    static final String DIRECTION = "asc";
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final int MAC_BYTES = 32;
    /** Original, pre-PR-10B shape — the legacy {@code /catalog/v1} wire format, byte-for-byte. */
    private static final int LEGACY_FIELD_COUNT = 9;
    /** PR-10B commerce shape: the 9 legacy fields plus the signed {@code location_context}. */
    private static final int COMMERCE_FIELD_COUNT = 10;
    private static final int FINGERPRINT_BYTES = 16;   // 128 bits: not reversible without the key

    /** Callers with no location concept (legacy consumer list) bind this fixed sentinel. */
    public static final String LOCATION_UNBOUND = "n/a";
    /** Anonymous browse (no location supplied) binds this fixed, non-secret sentinel. */
    public static final String LOCATION_ANONYMOUS = "anon";

    /** The bound position of one continuation, including the location context it started under. */
    public record ListCursor(String nodeId, String releaseId, int pageSize, String lastProductId,
                              String locationContext) { }

    private final SecretKeySpec key;          // null when not configured or malformed
    private final String unavailableReason;   // generic; carries no key material
    private final String route;               // signed+validated surface discriminator
    private final int queryVersion;
    /**
     * Compatibility gate (final review): ONLY the commerce route carries the location-bound 10th
     * field. Derived from the route literal so the legacy {@code @Component} constructor — and
     * any future caller that reuses {@link #ROUTE} — automatically stays on the original 9-field
     * shape without needing its own flag.
     */
    private final boolean locationBound;

    @org.springframework.beans.factory.annotation.Autowired
    public ConsumerCursorCodec(ConsumerCursorProperties properties) {
        this(properties, ROUTE, QUERY_VERSION);
    }

    public ConsumerCursorCodec(ConsumerCursorProperties properties, String route, int queryVersion) {
        this.route = route;
        this.queryVersion = queryVersion;
        this.locationBound = COMMERCE_ROUTE.equals(route);
        SecretKeySpec resolved = null;
        String reason = null;
        String configured = properties.getCursorHmacKeyB64();
        if (configured == null || configured.isBlank()) {
            reason = "cursor signing key is not configured";
        } else {
            try {
                byte[] raw = Base64.getDecoder().decode(configured.trim());
                if (raw.length < MIN_KEY_BYTES) {
                    reason = "cursor signing key is shorter than " + MIN_KEY_BYTES + " bytes";
                } else {
                    resolved = new SecretKeySpec(raw, MAC_ALGORITHM);
                }
                Arrays.fill(raw, (byte) 0);
            } catch (IllegalArgumentException e) {
                reason = "cursor signing key is not valid Base64";
            }
        }
        this.key = resolved;
        this.unavailableReason = reason;
    }

    /** True when a key is configured and usable. LIST must not serve otherwise. */
    public boolean isReady() {
        return key != null;
    }

    /**
     * @throws ConsumerFailures.Unavailable the key is missing or malformed — LIST fails closed
     *         BEFORE any product work, with a reason that names the problem, never the key
     */
    public void requireReady() {
        if (key == null) {
            throw new ConsumerFailures.Unavailable(unavailableReason);
        }
    }

    public String encode(ListCursor cursor) {
        requireReady();
        String payload = locationBound
                ? String.join("|",
                        Integer.toString(CURSOR_VERSION), route, Integer.toString(queryVersion),
                        field(cursor.nodeId()), field(cursor.releaseId()),
                        Integer.toString(cursor.pageSize()), SORT, DIRECTION, field(cursor.lastProductId()),
                        field(cursor.locationContext()))
                : String.join("|",
                        Integer.toString(CURSOR_VERSION), route, Integer.toString(queryVersion),
                        field(cursor.nodeId()), field(cursor.releaseId()),
                        Integer.toString(cursor.pageSize()), SORT, DIRECTION, field(cursor.lastProductId()));
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(payloadBytes);
        byte[] token = new byte[payloadBytes.length + signature.length];
        System.arraycopy(payloadBytes, 0, token, 0, payloadBytes.length);
        System.arraycopy(signature, 0, token, payloadBytes.length, signature.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /**
     * @throws ConsumerFailures.InvalidCursor oversized, undecodable, unsigned by this key, of an
     *         unknown version, or otherwise not a cursor we minted — all the same generic failure
     */
    public ListCursor decode(String encoded) {
        requireReady();
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_LENGTH) {
            throw new ConsumerFailures.InvalidCursor("cursor length");
        }
        byte[] token;
        try {
            token = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new ConsumerFailures.InvalidCursor("cursor encoding");
        }
        // The JDK decoder is lenient (padding, stray trailing bits). A cursor has exactly ONE
        // spelling -- the one we minted -- so anything that does not re-encode to itself is not it.
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(token).equals(encoded)) {
            throw new ConsumerFailures.InvalidCursor("cursor encoding");
        }
        if (token.length <= MAC_BYTES) {
            throw new ConsumerFailures.InvalidCursor("cursor too short");
        }
        byte[] payloadBytes = Arrays.copyOfRange(token, 0, token.length - MAC_BYTES);
        byte[] presented = Arrays.copyOfRange(token, token.length - MAC_BYTES, token.length);
        // Constant-time: a forger must not learn how many signature bytes matched.
        if (!MessageDigest.isEqual(sign(payloadBytes), presented)) {
            throw new ConsumerFailures.InvalidCursor("cursor signature");
        }
        return parse(new String(payloadBytes, StandardCharsets.UTF_8));
    }

    private ListCursor parse(String payload) {
        String[] parts = payload.split("\\|", -1);
        int expected = locationBound ? COMMERCE_FIELD_COUNT : LEGACY_FIELD_COUNT;
        if (parts.length != expected) {
            throw new ConsumerFailures.InvalidCursor("cursor shape");
        }
        if (!Integer.toString(CURSOR_VERSION).equals(parts[0])) {
            throw new ConsumerFailures.InvalidCursor("cursor version");
        }
        if (!route.equals(parts[1]) || !Integer.toString(queryVersion).equals(parts[2])
                || !SORT.equals(parts[6]) || !DIRECTION.equals(parts[7])) {
            throw new ConsumerFailures.InvalidCursor("cursor identity");
        }
        int pageSize;
        try {
            pageSize = Integer.parseInt(parts[5]);
        } catch (NumberFormatException e) {
            throw new ConsumerFailures.InvalidCursor("cursor page size");
        }
        String nodeId = unfield(parts[3]);
        String releaseId = unfield(parts[4]);
        String lastProductId = unfield(parts[8]);
        String locationContext = locationBound ? unfield(parts[9]) : LOCATION_UNBOUND;
        if (nodeId.isEmpty() || releaseId.isEmpty() || lastProductId.isEmpty() || pageSize < 1
                || locationContext.isEmpty()) {
            throw new ConsumerFailures.InvalidCursor("cursor fields");
        }
        return new ListCursor(nodeId, releaseId, pageSize, lastProductId, locationContext);
    }

    /**
     * A keyed, non-reversible fingerprint of an arbitrary normalized value (PR-10B): the same MAC
     * key that signs the cursor, applied to a caller-namespaced string (e.g. {@code "pin:560001"}),
     * truncated to {@value #FINGERPRINT_BYTES} bytes. A client cannot recover the input from the
     * fingerprint; only the party holding this key can. This is NOT encryption of the cursor payload
     * (the payload stays plainly readable, opaque-by-contract only) — it is how a value can be
     * BOUND into the signed payload without ever appearing in it.
     */
    public String fingerprint(String normalizedValue) {
        requireReady();
        byte[] mac = sign(normalizedValue.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac, 0, FINGERPRINT_BYTES);
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);   // Mac is not thread-safe; one per call
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new ConsumerFailures.Unavailable("cursor signing unavailable");
        }
    }

    /** Percent-encoding guarantees the separator can never occur inside a field. */
    private static String field(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("cursor field must not be empty");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unfield(String encoded) {
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ConsumerFailures.InvalidCursor("cursor field encoding");
        }
    }
}
