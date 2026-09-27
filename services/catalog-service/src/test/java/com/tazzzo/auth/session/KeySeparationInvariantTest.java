package com.tazzzo.auth.session;

import com.tazzzo.auth.CustomerAuthProperties;
import com.tazzzo.auth.otp.OtpAuthProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-11C hardening (Finding 3) — deterministic, no-Spring-context coverage of
 * {@link KeySeparationInvariant}. Every scenario the mission enumerates verbatim: three distinct
 * keys is valid; each pairwise domain collision (access/refresh, access/otp, otp/refresh) is
 * rejected; a previous access key colliding with refresh or OTP material is rejected; a previous
 * access key colliding with the CURRENT access key (ordinary rotation) is explicitly NOT rejected.
 *
 * <p>Key fixtures are deterministic FIXED byte material (never {@code SecureRandom}, seeded or
 * otherwise, as a fixture generator) — each seed produces a distinct, reproducible 32-byte array
 * filled with that seed's byte value. The secrecy assertion compares the exception message against
 * the ACTUAL fixture values used by that specific test, never a regenerated/probabilistic set.
 */
class KeySeparationInvariantTest {

    private static String key(int seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static CustomerAuthProperties access(String current, String... previous) {
        CustomerAuthProperties props = new CustomerAuthProperties();
        props.setAccessTokenHmacKeyB64(current);
        props.setPreviousAccessTokenHmacKeysB64(List.of(previous));
        return props;
    }

    private static OtpAuthProperties otp(String key) {
        OtpAuthProperties props = new OtpAuthProperties();
        props.setHmacKeyB64(key);
        return props;
    }

    private static CustomerSessionProperties session(String refreshKey) {
        CustomerSessionProperties props = new CustomerSessionProperties();
        props.setRefreshTokenHmacKeyB64(refreshKey);
        props.setAccessTokenTtlSeconds(900);
        props.setSessionTtlSeconds(2_592_000);
        return props;
    }

    @Test void three_distinct_keys_is_valid() {
        String accessKey = key(1);
        String otpKey = key(2);
        String refreshKey = key(3);
        KeySeparationInvariant invariant = new KeySeparationInvariant(access(accessKey), otp(otpKey),
                session(refreshKey));

        invariant.validate(); // must not throw
    }

    @Test void access_equal_to_refresh_is_rejected() {
        String shared = key(10);
        String otpKey = key(11);
        KeySeparationInvariant invariant = new KeySeparationInvariant(access(shared), otp(otpKey), session(shared));

        assertThatThrownBy(invariant::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-token").hasMessageContaining("refresh-token")
                .satisfies(e -> neverContainsKeyMaterial(e, shared, otpKey));
    }

    @Test void access_equal_to_otp_is_rejected() {
        String shared = key(20);
        String refreshKey = key(21);
        KeySeparationInvariant invariant = new KeySeparationInvariant(access(shared), otp(shared), session(refreshKey));

        assertThatThrownBy(invariant::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-token").hasMessageContaining("otp")
                .satisfies(e -> neverContainsKeyMaterial(e, shared, refreshKey));
    }

    @Test void otp_equal_to_refresh_is_rejected() {
        String shared = key(30);
        String accessKey = key(31);
        KeySeparationInvariant invariant = new KeySeparationInvariant(access(accessKey), otp(shared), session(shared));

        assertThatThrownBy(invariant::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otp").hasMessageContaining("refresh-token")
                .satisfies(e -> neverContainsKeyMaterial(e, shared, accessKey));
    }

    @Test void previous_access_key_equal_to_refresh_is_rejected() {
        String shared = key(40);
        String currentAccessKey = key(41);
        String otpKey = key(42);
        KeySeparationInvariant invariant = new KeySeparationInvariant(access(currentAccessKey, shared), otp(otpKey),
                session(shared));

        assertThatThrownBy(invariant::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous-access-token").hasMessageContaining("refresh-token")
                .satisfies(e -> neverContainsKeyMaterial(e, shared, currentAccessKey, otpKey));
    }

    @Test void previous_access_key_equal_to_otp_is_rejected() {
        String shared = key(50);
        String currentAccessKey = key(51);
        String refreshKey = key(52);
        KeySeparationInvariant invariant = new KeySeparationInvariant(access(currentAccessKey, shared), otp(shared),
                session(refreshKey));

        assertThatThrownBy(invariant::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous-access-token").hasMessageContaining("otp")
                .satisfies(e -> neverContainsKeyMaterial(e, shared, currentAccessKey, refreshKey));
    }

    @Test void previous_access_key_equal_to_current_access_key_is_not_rejected() {
        // Ordinary key rotation: a previous key legitimately equals a key still in the access
        // domain's own history. This must NEVER be treated as a cross-domain collision.
        String shared = key(60);
        KeySeparationInvariant invariant = new KeySeparationInvariant(access(shared, shared), otp(key(61)),
                session(key(62)));

        invariant.validate(); // must not throw
    }

    @Test void missing_keys_are_excluded_from_comparison_not_treated_as_a_collision() {
        // Absent keys are the codecs' own NOT_READY fail-closed concern, not this invariant's.
        CustomerAuthProperties accessProps = new CustomerAuthProperties(); // no key set
        KeySeparationInvariant invariant = new KeySeparationInvariant(accessProps, otp(key(70)), session(key(71)));

        invariant.validate(); // must not throw
    }

    @Test void malformed_base64_keys_are_excluded_from_comparison_not_treated_as_a_collision() {
        CustomerAuthProperties accessProps = access("not-valid-base64!!!");
        KeySeparationInvariant invariant = new KeySeparationInvariant(accessProps, otp(key(80)), session(key(81)));

        invariant.validate(); // must not throw
    }

    /** Asserts the exception message embeds NONE of the actual base64 fixture values involved in
     *  this specific test — not a regenerated/probabilistic set of candidates. */
    private static void neverContainsKeyMaterial(Throwable e, String... actualFixtureValues) {
        String message = e.getMessage();
        assertThat(message).doesNotContain("=="); // no base64-padding-shaped fragment either
        for (String fixture : actualFixtureValues) {
            assertThat(message).as("exception message must never embed the raw secret")
                    .doesNotContain(fixture);
        }
    }
}
