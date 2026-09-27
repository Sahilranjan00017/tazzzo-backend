package com.tazzzo.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-11A — the customer access-token codec: cryptographic and time validity only (§19 test
 * matrix). Every rejection is the SAME typed {@link CustomerAuthFailure}, so the filter can
 * flatten all of them to one public 401 regardless of which check actually failed.
 */
class CustomerAccessTokenCodecTest {

    static final String KEY_A = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    static final String KEY_B = Base64.getEncoder().encodeToString(
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final CustomerId CUSTOMER = new CustomerId("CUS_test0001");
    private static final SessionId SESSION = new SessionId("SES_test0001");

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static CustomerAccessTokenCodec codec(String keyB64, Clock clock) {
        CustomerAuthProperties props = new CustomerAuthProperties();
        props.setAccessTokenHmacKeyB64(keyB64);
        return new CustomerAccessTokenCodec(props, clock);
    }

    private static CustomerAccessTokenCodec codec(String keyB64) {
        return codec(keyB64, clockAt(NOW));
    }

    @Test void valid_round_trip() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String token = codec.issue(new CustomerPrincipal(CUSTOMER, SESSION), Duration.ofMinutes(15));
        CustomerPrincipal verified = codec.verify(token);
        assertThat(verified.customerId()).isEqualTo(CUSTOMER);
        assertThat(verified.sessionId()).isEqualTo(SESSION);
    }

    @Test void deterministic_verification() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String token = codec.encode(CUSTOMER, SESSION, NOW, NOW.plusSeconds(900));
        assertThat(codec.verify(token)).isEqualTo(codec.verify(token));
    }

    @Test void token_does_not_contain_the_raw_configured_secret() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String token = codec.encode(CUSTOMER, SESSION, NOW, NOW.plusSeconds(900));
        assertThat(token).doesNotContain("0123456789abcdef");
    }

    @Test void token_does_not_contain_installation_id_ip_pin_or_otp() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String token = codec.encode(CUSTOMER, SESSION, NOW, NOW.plusSeconds(900));
        for (String forbidden : new String[]{"192.168", "560001", "installation", "otp", "pin"}) {
            assertThat(token.toLowerCase()).doesNotContain(forbidden);
        }
    }

    @Test void canonical_base64url_spelling_only() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String token = codec.encode(CUSTOMER, SESSION, NOW, NOW.plusSeconds(900));
        assertThat(token).matches("[A-Za-z0-9_-]+");
        byte[] raw = Base64.getUrlDecoder().decode(token);
        String padded = Base64.getUrlEncoder().encodeToString(raw);
        if (!padded.equals(token)) {
            assertThatThrownBy(() -> codec.verify(padded))
                    .as("padding must not be accepted as an equivalent spelling")
                    .isInstanceOf(CustomerAuthFailure.class);
        }
    }

    @Test void a_tampered_payload_byte_is_rejected() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String token = codec.encode(CUSTOMER, SESSION, NOW, NOW.plusSeconds(900));
        byte[] raw = Base64.getUrlDecoder().decode(token);
        raw[0] ^= 0x01;
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertThatThrownBy(() -> codec.verify(tampered))
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.INVALID_SIGNATURE));
    }

    @Test void a_tampered_signature_byte_is_rejected() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String token = codec.encode(CUSTOMER, SESSION, NOW, NOW.plusSeconds(900));
        byte[] raw = Base64.getUrlDecoder().decode(token);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertThatThrownBy(() -> codec.verify(tampered)).isInstanceOf(CustomerAuthFailure.class);
    }

    @Test void wrong_key_is_rejected() {
        String token = codec(KEY_B).encode(CUSTOMER, SESSION, NOW, NOW.plusSeconds(900));
        assertThatThrownBy(() -> codec(KEY_A).verify(token))
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.INVALID_SIGNATURE));
    }

    @Test void malformed_base64_token_is_rejected() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        for (String bad : new String[]{"not base64!!", "===", "", "a".repeat(2000)}) {
            assertThatThrownBy(() -> codec.verify(bad))
                    .as("[" + bad.length() + " chars]").isInstanceOf(CustomerAuthFailure.class);
        }
    }

    @Test void missing_key_means_verifier_not_ready_and_fails_closed() {
        CustomerAccessTokenCodec codec = codec(null);
        assertThat(codec.isReady()).isFalse();
        assertThatThrownBy(codec::requireReady)
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.NOT_READY));
        assertThatThrownBy(() -> codec.verify("anything")).isInstanceOf(CustomerAuthFailure.class);
        assertThatThrownBy(() -> codec.issue(new CustomerPrincipal(CUSTOMER, SESSION), Duration.ofMinutes(1)))
                .isInstanceOf(CustomerAuthFailure.class);
    }

    @Test void short_config_key_is_rejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        CustomerAccessTokenCodec codec = codec(shortKey);
        assertThat(codec.isReady()).isFalse();
    }

    @Test void exactly_32_bytes_is_the_minimum_and_is_ready() {
        String key32 = Base64.getEncoder().encodeToString(new byte[32]);
        assertThat(codec(key32).isReady()).isTrue();
    }

    @Test void malformed_key_base64_is_rejected() {
        assertThat(codec("not valid base64 !!!").isReady()).isFalse();
    }

    @Test void expired_token_is_rejected() {
        CustomerAccessTokenCodec issuer = codec(KEY_A, clockAt(NOW));
        String token = issuer.encode(CUSTOMER, SESSION, NOW, NOW.plusSeconds(60));
        CustomerAccessTokenCodec laterVerifier = codec(KEY_A, clockAt(NOW.plusSeconds(61)));
        assertThatThrownBy(() -> laterVerifier.verify(token))
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.EXPIRED));
    }

    @Test void a_token_expiring_exactly_now_is_rejected() {
        CustomerAccessTokenCodec issuer = codec(KEY_A, clockAt(NOW));
        String token = issuer.encode(CUSTOMER, SESSION, NOW.minusSeconds(60), NOW);
        assertThatThrownBy(() -> issuer.verify(token))
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.EXPIRED));
    }

    @Test void future_issued_token_beyond_tolerance_is_rejected() {
        CustomerAccessTokenCodec issuer = codec(KEY_A, clockAt(NOW.plusSeconds(3600)));
        String token = issuer.encode(CUSTOMER, SESSION, NOW.plusSeconds(3600), NOW.plusSeconds(4200));
        CustomerAccessTokenCodec verifier = codec(KEY_A, clockAt(NOW));
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.FUTURE_ISSUED));
    }

    @Test void future_issued_token_within_tiny_tolerance_is_accepted() {
        Instant issuedSlightlyAhead = NOW.plusSeconds(5); // well within the 30s tolerance
        CustomerAccessTokenCodec issuer = codec(KEY_A, clockAt(issuedSlightlyAhead));
        String token = issuer.encode(CUSTOMER, SESSION, issuedSlightlyAhead, issuedSlightlyAhead.plusSeconds(900));
        CustomerAccessTokenCodec verifier = codec(KEY_A, clockAt(NOW));
        assertThat(verifier.verify(token)).isNotNull();
    }

    @Test void expires_at_before_or_equal_to_issued_at_is_rejected() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String equalToken = codec.encode(CUSTOMER, SESSION, NOW, NOW);
        assertThatThrownBy(() -> codec.verify(equalToken))
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.MALFORMED_CLAIMS));

        String beforeToken = codec.encode(CUSTOMER, SESSION, NOW, NOW.minusSeconds(1));
        assertThatThrownBy(() -> codec.verify(beforeToken)).isInstanceOf(CustomerAuthFailure.class);
    }

    @Test void empty_customer_id_is_rejected_at_construction() {
        assertThatThrownBy(() -> new CustomerId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void empty_session_id_is_rejected_at_construction() {
        assertThatThrownBy(() -> new SessionId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void oversized_token_is_rejected() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String oversized = "A".repeat(CustomerAccessTokenCodec.MAX_ENCODED_LENGTH + 1);
        assertThatThrownBy(() -> codec.verify(oversized)).isInstanceOf(CustomerAuthFailure.class);
    }

    @Test void an_unsupported_version_is_rejected() throws Exception {
        // Forge a payload with a bad version, signed by the real key -- proves the signature
        // authenticates BEFORE version/claims are trusted, and a wrong version is still refused.
        CustomerAccessTokenCodec codec = codec(KEY_A);
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
        out.writeInt(99); // unsupported version
        out.writeUTF(CUSTOMER.value());
        out.writeUTF(SESSION.value());
        out.writeLong(NOW.getEpochSecond());
        out.writeLong(NOW.plusSeconds(900).getEpochSecond());
        out.flush();
        byte[] payload = buffer.toByteArray();
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(KEY_A), "HmacSHA256"));
        byte[] sig = mac.doFinal(payload);
        byte[] full = Arrays.copyOf(payload, payload.length + sig.length);
        System.arraycopy(sig, 0, full, payload.length, sig.length);
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(full);

        assertThatThrownBy(() -> codec.verify(forged))
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.MALFORMED_CLAIMS));
    }

    @Test void issue_rejects_zero_or_negative_ttl_as_programmer_misuse() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        CustomerPrincipal principal = new CustomerPrincipal(CUSTOMER, SESSION);
        assertThatThrownBy(() -> codec.issue(principal, Duration.ZERO))
                .as("a zero TTL would mint a token verify() immediately refuses — issuer bug, not an auth failure")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.issue(principal, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A correctly-signed payload can still carry a pathological epoch value outside Instant's
     * representable range. This must stay a 401 (MALFORMED_CLAIMS), never an uncaught
     * DateTimeException / 500 at the public boundary.
     */
    @Test void pathological_signed_epoch_values_are_malformed_claims_not_a_crash() throws Exception {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        assertThatThrownBy(() -> codec.verify(forgeWithEpochs(Long.MAX_VALUE, Long.MAX_VALUE)))
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.MALFORMED_CLAIMS));
        assertThatThrownBy(() -> codec.verify(forgeWithEpochs(Long.MIN_VALUE, 0L)))
                .isInstanceOf(CustomerAuthFailure.class)
                .satisfies(e -> assertThat(((CustomerAuthFailure) e).reason())
                        .isEqualTo(CustomerAuthFailure.Reason.MALFORMED_CLAIMS));
    }

    private static String forgeWithEpochs(long issuedAtEpoch, long expiresAtEpoch) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
        out.writeInt(1);
        out.writeUTF(CUSTOMER.value());
        out.writeUTF(SESSION.value());
        out.writeLong(issuedAtEpoch);
        out.writeLong(expiresAtEpoch);
        out.flush();
        byte[] payload = buffer.toByteArray();
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(KEY_A), "HmacSHA256"));
        byte[] sig = mac.doFinal(payload);
        byte[] full = Arrays.copyOf(payload, payload.length + sig.length);
        System.arraycopy(sig, 0, full, payload.length, sig.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(full);
    }

    @Test void constant_time_comparison_and_no_key_material_in_rejection() {
        CustomerAccessTokenCodec codec = codec(KEY_A);
        String source = codec.encode(CUSTOMER, SESSION, NOW, NOW.plusSeconds(900));
        assertThatThrownBy(() -> codec(KEY_B).verify(source))
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain(KEY_A).doesNotContain("0123456789abcdef"));
    }
}
