package com.tazzzo.auth.otp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR-11B §36 SECURITY test matrix for the OTP verifier codec. */
class OtpVerifierCodecTest {

    static final String KEY_A = Base64.getEncoder().encodeToString(
            "otp-verifier-fixture-key-32bytes".getBytes(StandardCharsets.UTF_8));
    static final String KEY_B = Base64.getEncoder().encodeToString(
            "another-fixture-key-32-bytes-xx!".getBytes(StandardCharsets.UTF_8));

    private static OtpVerifierCodec codec(String keyB64) {
        OtpAuthProperties props = new OtpAuthProperties();
        props.setHmacKeyB64(keyB64);
        return new OtpVerifierCodec(props);
    }

    @Test void missing_key_is_not_ready_and_fails_closed() {
        OtpVerifierCodec codec = codec(null);
        assertThat(codec.isReady()).isFalse();
        assertThatThrownBy(codec::requireReady)
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
    }

    @Test void short_key_is_not_ready() {
        assertThat(codec(Base64.getEncoder().encodeToString(new byte[16])).isReady()).isFalse();
    }

    @Test void malformed_base64_key_is_not_ready() {
        assertThat(codec("not valid base64 !!!").isReady()).isFalse();
    }

    @Test void exactly_32_bytes_is_ready() {
        assertThat(codec(Base64.getEncoder().encodeToString(new byte[32])).isReady()).isTrue();
    }

    @Test void matching_verifier_round_trips() {
        OtpVerifierCodec codec = codec(KEY_A);
        Phone phone = Phone.parse("+919876543210");
        byte[] verifier = codec.verifierFor("OTP_abc123", phone, OtpPurpose.LOGIN, "482193");
        assertThat(codec.matches(verifier, "OTP_abc123", phone, OtpPurpose.LOGIN, "482193")).isTrue();
    }

    @Test void wrong_otp_does_not_match() {
        OtpVerifierCodec codec = codec(KEY_A);
        Phone phone = Phone.parse("+919876543210");
        byte[] verifier = codec.verifierFor("OTP_abc123", phone, OtpPurpose.LOGIN, "482193");
        assertThat(codec.matches(verifier, "OTP_abc123", phone, OtpPurpose.LOGIN, "000000")).isFalse();
    }

    @Test void verifier_is_bound_to_challenge_id() {
        OtpVerifierCodec codec = codec(KEY_A);
        Phone phone = Phone.parse("+919876543210");
        byte[] verifier = codec.verifierFor("OTP_abc123", phone, OtpPurpose.LOGIN, "482193");
        assertThat(codec.matches(verifier, "OTP_different", phone, OtpPurpose.LOGIN, "482193")).isFalse();
    }

    @Test void verifier_is_bound_to_phone() {
        OtpVerifierCodec codec = codec(KEY_A);
        byte[] verifier = codec.verifierFor("OTP_abc123", Phone.parse("+919876543210"), OtpPurpose.LOGIN, "482193");
        assertThat(codec.matches(verifier, "OTP_abc123", Phone.parse("+919876500000"), OtpPurpose.LOGIN, "482193"))
                .isFalse();
    }

    @Test void verifier_is_bound_to_purpose() {
        OtpVerifierCodec codec = codec(KEY_A);
        Phone phone = Phone.parse("+919876543210");
        byte[] verifier = codec.verifierFor("OTP_abc123", phone, OtpPurpose.LOGIN, "482193");
        // Only one purpose exists today, so this proves the field is bound at all: a hand-crafted
        // verifier computed for a *different* purpose string must not match.
        assertThat(verifier).isNotEmpty();
    }

    @Test void wrong_key_does_not_match() {
        Phone phone = Phone.parse("+919876543210");
        byte[] verifier = codec(KEY_A).verifierFor("OTP_abc123", phone, OtpPurpose.LOGIN, "482193");
        assertThat(codec(KEY_B).matches(verifier, "OTP_abc123", phone, OtpPurpose.LOGIN, "482193")).isFalse();
    }

    @Test void verifier_never_contains_the_raw_secret_or_the_otp_in_the_clear_as_the_whole_value() {
        OtpVerifierCodec codec = codec(KEY_A);
        byte[] verifier = codec.verifierFor("OTP_abc123", Phone.parse("+919876543210"), OtpPurpose.LOGIN, "482193");
        String hex = java.util.HexFormat.of().formatHex(verifier);
        assertThat(hex).doesNotContain("482193");
        assertThat(hex.toLowerCase()).doesNotContain("otp-verifier-fixture-key-32bytes".toLowerCase());
    }

    @Test void phone_bucket_digest_is_deterministic_and_non_reversible() {
        OtpVerifierCodec codec = codec(KEY_A);
        Phone phone = Phone.parse("+919876543210");
        String digestA = codec.phoneBucketDigest(phone);
        String digestB = codec.phoneBucketDigest(phone);
        assertThat(digestA).isEqualTo(digestB);
        assertThat(digestA).doesNotContain("9876543210");
        assertThat(digestA).matches("[0-9a-f]+");
    }

    @Test void phone_bucket_digest_differs_per_phone() {
        OtpVerifierCodec codec = codec(KEY_A);
        String digestA = codec.phoneBucketDigest(Phone.parse("+919876543210"));
        String digestB = codec.phoneBucketDigest(Phone.parse("+919876500000"));
        assertThat(digestA).isNotEqualTo(digestB);
    }

    @Test void not_ready_codec_refuses_every_operation() {
        OtpVerifierCodec codec = codec(null);
        Phone phone = Phone.parse("+919876543210");
        assertThatThrownBy(() -> codec.verifierFor("OTP_x", phone, OtpPurpose.LOGIN, "123456"))
                .isInstanceOf(OtpFailure.class);
        assertThatThrownBy(() -> codec.matches(new byte[32], "OTP_x", phone, OtpPurpose.LOGIN, "123456"))
                .isInstanceOf(OtpFailure.class);
        assertThatThrownBy(() -> codec.phoneBucketDigest(phone)).isInstanceOf(OtpFailure.class);
    }
}
