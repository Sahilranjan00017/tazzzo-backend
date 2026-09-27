package com.tazzzo.auth.session;

import com.tazzzo.auth.CustomerAuthProperties;
import com.tazzzo.auth.otp.OtpAuthProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * PR-11C hardening (Finding 3) — a fail-fast startup invariant. Separate PROPERTY NAMES for the
 * access-token / OTP / refresh-token HMAC keys ({@link CustomerAuthProperties},
 * {@link OtpAuthProperties}, {@link CustomerSessionProperties}) do not stop an operator from
 * configuring the SAME secret VALUE across trust domains — this compares DECODED key material
 * (never the raw config strings) pairwise across every domain and refuses to start if any two
 * domains that must stay independent share material:
 * <ul>
 *     <li>access-token key == OTP key</li>
 *     <li>access-token key == refresh-token key</li>
 *     <li>OTP key == refresh-token key</li>
 *     <li>any PREVIOUS access-token verification key == OTP key or refresh-token key (a previous
 *         access key is still access-domain trust material)</li>
 * </ul>
 * A previous access key is deliberately NOT compared against the CURRENT access key or other
 * previous access keys — that comparison would break ordinary key rotation, which this invariant
 * must never do.
 *
 * <p>Missing/malformed keys are NOT this class's concern — each codec's own fail-closed NOT_READY
 * readiness check already handles that at request time. This invariant only compares keys that
 * decode successfully; an absent or malformed key is simply excluded from comparison.
 *
 * <p>Never logs key material, and never places a key value into an exception message — only the
 * bounded domain names involved in a collision. Comparisons use {@link MessageDigest#isEqual} to
 * avoid timing side-channels on secret material.
 */
@Component
public class KeySeparationInvariant {

    private final CustomerAuthProperties accessProperties;
    private final OtpAuthProperties otpProperties;
    private final CustomerSessionProperties sessionProperties;

    public KeySeparationInvariant(CustomerAuthProperties accessProperties, OtpAuthProperties otpProperties,
                                  CustomerSessionProperties sessionProperties) {
        this.accessProperties = accessProperties;
        this.otpProperties = otpProperties;
        this.sessionProperties = sessionProperties;
    }

    @PostConstruct
    void validate() {
        byte[] access = decode(accessProperties.getAccessTokenHmacKeyB64());
        byte[] otp = decode(otpProperties.getHmacKeyB64());
        byte[] refresh = decode(sessionProperties.getRefreshTokenHmacKeyB64());
        List<byte[]> previousAccess = new ArrayList<>();
        for (String candidate : accessProperties.getPreviousAccessTokenHmacKeysB64()) {
            byte[] decoded = decode(candidate);
            if (decoded != null) {
                previousAccess.add(decoded);
            }
        }

        reject(access, otp, "access-token", "otp");
        reject(access, refresh, "access-token", "refresh-token");
        reject(otp, refresh, "otp", "refresh-token");
        for (byte[] previous : previousAccess) {
            reject(previous, refresh, "previous-access-token", "refresh-token");
            reject(previous, otp, "previous-access-token", "otp");
        }
    }

    private static void reject(byte[] a, byte[] b, String domainA, String domainB) {
        if (a == null || b == null) {
            return;
        }
        if (MessageDigest.isEqual(a, b)) {
            throw new IllegalStateException(
                    "customer-auth key separation violated: " + domainA + " and " + domainB
                            + " must not share the same secret material");
        }
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
