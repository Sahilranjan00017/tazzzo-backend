package com.tazzzo.auth;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * PR-11A — the customer access-token codec: a stateless, versioned, HMAC-SHA256 signed, opaque
 * bearer token. Mirrors the established repository pattern ({@code ConsumerCursorCodec}) for the
 * same reasons: fail-closed key configuration, constant-time signature comparison, exactly-one
 * accepted spelling, and length-prefixed (never delimiter-joined) field encoding so no field's
 * content can ever be misread as another field's boundary.
 *
 * <p><b>Wire form:</b> {@code base64url( payload || HMAC-SHA256(key, payload) )}. The payload is
 * written via {@link DataOutputStream#writeUTF(String)} for every string field (a 2-byte length
 * then the exact UTF-8 bytes) and {@code writeLong} for the epoch-second timestamps — never a
 * delimiter-joined string:
 * <pre>
 *   version(int) | customerId(UTF) | sessionId(UTF) | issuedAtEpochSeconds(long) | expiresAtEpochSeconds(long)
 * </pre>
 *
 * <p><b>Binds:</b> token version, customerId, sessionId, issuedAt, expiresAt. Never binds PIN,
 * address, installation id, IP, OTP, password, or any internal Mongo id beyond the opaque
 * customer/session ids themselves.
 *
 * <p><b>Verification order:</b> length cap → decode (canonical spelling only) → signature
 * (constant-time) → THEN parse claims → THEN time validity against the injected {@link Clock}
 * (never {@code Instant.now()} in this class). A tampered payload fails on the signature, never on
 * a parser exception.
 *
 * <p><b>PR-11A boundary:</b> this proves CRYPTOGRAPHIC and TIME validity only. There is no session
 * database yet — no revocation, no logout invalidation, no server-side session lookup. A
 * cryptographically valid, unexpired token is accepted; revocation is a PR-11C concern once session
 * lifecycle exists. Do not treat verification success here as proof a session is still active
 * server-side — it is proof only that THIS token was minted by this server and has not expired.
 */
@Component
public class CustomerAccessTokenCodec {

    /** Tokens are small and fixed-shape; anything larger cannot be one of ours. */
    public static final int MAX_ENCODED_LENGTH = 1024;
    /** The key must decode to at least this many bytes (256 bits). */
    public static final int MIN_KEY_BYTES = 32;
    static final int TOKEN_VERSION = 1;
    /** A tiny, explicit tolerance for clock skew between issuance and verification. */
    static final Duration FUTURE_ISSUED_TOLERANCE = Duration.ofSeconds(30);

    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final int MAC_BYTES = 32;

    private final SecretKeySpec key;      // null when not configured or malformed
    private final String unavailableReason;
    private final Clock clock;

    public CustomerAccessTokenCodec(CustomerAuthProperties properties, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock required");
        SecretKeySpec resolved = null;
        String reason = null;
        String configured = properties.getAccessTokenHmacKeyB64();
        if (configured == null || configured.isBlank()) {
            reason = "customer access-token signing key is not configured";
        } else {
            try {
                byte[] raw = Base64.getDecoder().decode(configured.trim());
                if (raw.length < MIN_KEY_BYTES) {
                    reason = "customer access-token signing key is shorter than " + MIN_KEY_BYTES + " bytes";
                } else {
                    resolved = new SecretKeySpec(raw, MAC_ALGORITHM);
                }
                Arrays.fill(raw, (byte) 0);
            } catch (IllegalArgumentException e) {
                reason = "customer access-token signing key is not valid Base64";
            }
        }
        this.key = resolved;
        this.unavailableReason = reason;
    }

    /** True when a key is configured and usable. The customer surface must not serve otherwise. */
    public boolean isReady() {
        return key != null;
    }

    /** @throws CustomerAuthFailure NOT_READY — the key is missing or malformed; fail closed. */
    public void requireReady() {
        if (key == null) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.NOT_READY);
        }
    }

    /** Issue a token for {@code principal}, valid from now for {@code ttl}. */
    public String issue(CustomerPrincipal principal, Duration ttl) {
        Objects.requireNonNull(principal, "principal required");
        Objects.requireNonNull(ttl, "ttl required");
        Instant now = clock.instant();
        return encode(principal.customerId(), principal.sessionId(), now, now.plus(ttl));
    }

    String encode(CustomerId customerId, SessionId sessionId, Instant issuedAt, Instant expiresAt) {
        requireReady();
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(TOKEN_VERSION);
            out.writeUTF(customerId.value());
            out.writeUTF(sessionId.value());
            out.writeLong(issuedAt.getEpochSecond());
            out.writeLong(expiresAt.getEpochSecond());
            out.flush();
            byte[] payload = buffer.toByteArray();
            byte[] signature = sign(payload);
            byte[] token = new byte[payload.length + signature.length];
            System.arraycopy(payload, 0, token, 0, payload.length);
            System.arraycopy(signature, 0, token, payload.length, signature.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @throws CustomerAuthFailure MISSING/MALFORMED/INVALID_SIGNATURE/EXPIRED/FUTURE_ISSUED/
     *         MALFORMED_CLAIMS/NOT_READY — every failure mode, so the caller need only catch ONE
     *         type and flatten it to a single public 401.
     */
    public CustomerPrincipal verify(String encoded) {
        requireReady();
        if (encoded == null || encoded.isEmpty()) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MISSING);
        }
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MALFORMED);
        }
        byte[] token;
        try {
            token = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MALFORMED);
        }
        // Exactly one accepted spelling — anything the JDK decodes leniently (padding, a
        // non-canonical alphabet, unused trailing bits) that does not re-encode to itself is not
        // a token we minted.
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(token).equals(encoded)) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MALFORMED);
        }
        if (token.length <= MAC_BYTES) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MALFORMED);
        }
        byte[] payload = Arrays.copyOfRange(token, 0, token.length - MAC_BYTES);
        byte[] presented = Arrays.copyOfRange(token, token.length - MAC_BYTES, token.length);
        // Constant-time: a forger must not learn how many signature bytes matched.
        if (!MessageDigest.isEqual(sign(payload), presented)) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.INVALID_SIGNATURE);
        }
        return parseAndValidate(payload);
    }

    private CustomerPrincipal parseAndValidate(byte[] payload) {
        int version;
        String customerIdValue;
        String sessionIdValue;
        long issuedAtEpoch;
        long expiresAtEpoch;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            version = in.readInt();
            customerIdValue = in.readUTF();
            sessionIdValue = in.readUTF();
            issuedAtEpoch = in.readLong();
            expiresAtEpoch = in.readLong();
            if (in.available() > 0) {
                // trailing bytes: not a shape we ever produced.
                throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MALFORMED_CLAIMS);
            }
        } catch (IOException e) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MALFORMED_CLAIMS);
        }
        if (version != TOKEN_VERSION) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MALFORMED_CLAIMS);
        }
        if (!CustomerId.isValid(customerIdValue) || !SessionId.isValid(sessionIdValue)) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MALFORMED_CLAIMS);
        }
        if (expiresAtEpoch <= issuedAtEpoch) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MALFORMED_CLAIMS);
        }
        Instant now = clock.instant();
        Instant issuedAt = Instant.ofEpochSecond(issuedAtEpoch);
        Instant expiresAt = Instant.ofEpochSecond(expiresAtEpoch);
        if (issuedAt.isAfter(now.plus(FUTURE_ISSUED_TOLERANCE))) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.FUTURE_ISSUED);
        }
        if (!expiresAt.isAfter(now)) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.EXPIRED);
        }
        return new CustomerPrincipal(new CustomerId(customerIdValue), new SessionId(sessionIdValue));
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);   // Mac is not thread-safe; one per call
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.NOT_READY);
        }
    }
}
