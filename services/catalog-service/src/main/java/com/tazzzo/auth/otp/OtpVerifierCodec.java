package com.tazzzo.auth.otp;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * PR-11B — computes the keyed verifier persisted instead of a plaintext OTP, and the keyed digest
 * used as a Redis rate-limit bucket key instead of a raw phone number.
 *
 * <p><b>Why keyed, not plain SHA-256:</b> a six-digit OTP has only 1,000,000 possibilities. A
 * database leak of a plain (even salted) hash would let an attacker who also knows challengeId+phone
 * brute-force the OTP offline in microseconds. Binding the verifier to a dedicated, server-held
 * HMAC-SHA256 secret makes the verifier useless without that secret — mirrors
 * {@code CustomerAccessTokenCodec}'s fail-closed key pattern, deliberately a SEPARATE secret from
 * the access-token signing key (different blast radius: this key never signs anything a client can
 * present back to the server as a bearer credential).
 *
 * <p>Binds version + challengeId + normalizedPhone + purpose + otp, length-prefixed
 * ({@link DataOutputStream#writeUTF(String)}) — never delimiter-joined — so no field's content can
 * be misread as another field's boundary.
 */
@Component
public class OtpVerifierCodec {

    public static final int MIN_KEY_BYTES = 32;
    private static final int VERSION = 1;
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;
    private final String unavailableReason;

    public OtpVerifierCodec(OtpAuthProperties properties) {
        SecretKeySpec resolved = null;
        String reason = null;
        String configured = properties.getHmacKeyB64();
        if (configured == null || configured.isBlank()) {
            reason = "OTP verifier signing key is not configured";
        } else {
            try {
                byte[] raw = Base64.getDecoder().decode(configured.trim());
                if (raw.length < MIN_KEY_BYTES) {
                    reason = "OTP verifier signing key is shorter than " + MIN_KEY_BYTES + " bytes";
                } else {
                    resolved = new SecretKeySpec(raw, MAC_ALGORITHM);
                }
                Arrays.fill(raw, (byte) 0);
            } catch (IllegalArgumentException e) {
                reason = "OTP verifier signing key is not valid Base64";
            }
        }
        this.key = resolved;
        this.unavailableReason = reason;
    }

    public boolean isReady() {
        return key != null;
    }

    /** @throws OtpFailure UNAVAILABLE — the key is missing or malformed; fail closed. */
    public void requireReady() {
        if (key == null) {
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
    }

    /** The verifier persisted on the challenge document instead of the plaintext OTP. */
    public byte[] verifierFor(String challengeId, Phone phone, OtpPurpose purpose, String otp) {
        requireReady();
        return sign(otp, challengeId, phone.value(), purpose.name());
    }

    /** Constant-time comparison — a forger must not learn how many bytes matched. */
    public boolean matches(byte[] storedVerifier, String challengeId, Phone phone, OtpPurpose purpose,
                           String otp) {
        requireReady();
        return MessageDigest.isEqual(storedVerifier, sign(otp, challengeId, phone.value(), purpose.name()));
    }

    /**
     * A bounded, non-reversible rate-limit bucket key for a phone number. Never the raw phone: a
     * Redis key or log line built from this digest cannot be reversed back to the number.
     */
    public String phoneBucketDigest(Phone phone) {
        requireReady();
        byte[] mac = sign("bucket", phone.value(), "", "");
        return HexFormat.of().formatHex(mac);
    }

    private byte[] sign(String otp, String challengeId, String phone, String purpose) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(VERSION);
            out.writeUTF(challengeId);
            out.writeUTF(phone);
            out.writeUTF(purpose);
            out.writeUTF(otp);
            out.flush();
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(buffer.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
    }

    String unavailableReason() {
        return unavailableReason;
    }
}
