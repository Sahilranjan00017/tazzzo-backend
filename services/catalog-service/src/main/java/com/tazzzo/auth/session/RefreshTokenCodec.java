package com.tazzzo.auth.session;

import com.tazzzo.auth.SessionId;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PR-11C — the refresh-token codec. A refresh token is {@code <sessionId>.<opaque CSPRNG secret>}:
 * the session id is NOT a secret (it already travels inside the signed, client-visible access
 * token), so embedding it as a routing prefix lets {@code sessionId} be looked up by primary key —
 * no separate index over a digest column is needed. The SECRET half is what is cryptographically
 * verified; ONLY its keyed digest is ever persisted, never the plaintext secret.
 *
 * <p><b>Key separation:</b> this codec's HMAC key is a dedicated secret
 * ({@code tazzzo.customer-auth.session.refresh-token-hmac-key-b64}) — never the access-token
 * signing key (PR-11A) or the OTP verifier/phone-digest key (PR-11B). Fail-closed exactly like
 * those: missing/malformed key ⇒ NOT READY, no anonymous/plaintext fallback.
 */
@Component
public class RefreshTokenCodec {

    public static final int MIN_KEY_BYTES = 32;
    /** 32 random bytes of secret — 256 bits of entropy, effectively unguessable. */
    private static final int SECRET_BYTES = 32;
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^(SES_[A-Za-z0-9_-]{6,64})\\.([A-Za-z0-9_-]{20,64})$");

    private final SecretKeySpec key;
    private final String unavailableReason;

    public RefreshTokenCodec(CustomerSessionProperties properties) {
        SecretKeySpec resolved = null;
        String reason = null;
        String configured = properties.getRefreshTokenHmacKeyB64();
        if (configured == null || configured.isBlank()) {
            reason = "refresh-token signing key is not configured";
        } else {
            try {
                byte[] raw = Base64.getDecoder().decode(configured.trim());
                if (raw.length < MIN_KEY_BYTES) {
                    reason = "refresh-token signing key is shorter than " + MIN_KEY_BYTES + " bytes";
                } else {
                    resolved = new SecretKeySpec(raw, MAC_ALGORITHM);
                }
                Arrays.fill(raw, (byte) 0);
            } catch (IllegalArgumentException e) {
                reason = "refresh-token signing key is not valid Base64";
            }
        }
        this.key = resolved;
        this.unavailableReason = reason;
    }

    public boolean isReady() {
        return key != null;
    }

    /** @throws SessionAuthFailure UNAVAILABLE — the key is missing or malformed; fail closed. */
    public void requireReady() {
        if (key == null) {
            throw new SessionAuthFailure(SessionAuthFailure.Reason.UNAVAILABLE);
        }
    }

    /** A fresh, CSPRNG, URL-safe secret half for a new refresh token. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The public wire form handed to the client. Never persisted in this form. */
    public String format(SessionId sessionId, String secret) {
        return sessionId.value() + "." + secret;
    }

    /**
     * @throws SessionAuthFailure INVALID_REQUEST — the token is not shaped like anything this
     *         server could ever have issued (malformed request, mapped to {@code 400}).
     */
    public ParsedRefreshToken parse(String token) {
        if (token == null) {
            throw new SessionAuthFailure(SessionAuthFailure.Reason.INVALID_REQUEST);
        }
        var matcher = TOKEN_SHAPE.matcher(token);
        if (!matcher.matches()) {
            throw new SessionAuthFailure(SessionAuthFailure.Reason.INVALID_REQUEST);
        }
        return new ParsedRefreshToken(new SessionId(matcher.group(1)), matcher.group(2));
    }

    /** The keyed digest persisted instead of the plaintext secret. */
    public byte[] digest(SessionId sessionId, String secret) {
        requireReady();
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(key);
            mac.update(sessionId.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return mac.doFinal(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new SessionAuthFailure(SessionAuthFailure.Reason.UNAVAILABLE);
        }
    }

    /** Constant-time comparison — a forger must not learn how many digest bytes matched. */
    public boolean matches(byte[] storedDigest, SessionId sessionId, String secret) {
        return MessageDigest.isEqual(storedDigest, digest(sessionId, secret));
    }

    String unavailableReason() {
        return unavailableReason;
    }

    public record ParsedRefreshToken(SessionId sessionId, String secret) {
    }
}
