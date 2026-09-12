package com.tazzzo.catalog;

import com.tazzzo.catalog.consumer.ConsumerCursorCodec;
import com.tazzzo.catalog.consumer.ConsumerCursorCodec.ListCursor;
import com.tazzzo.catalog.consumer.ConsumerCursorProperties;
import com.tazzzo.catalog.consumer.ConsumerFailures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LIST-CURSOR-1 at the codec: stateless, versioned, signed, opaque. Every rejection is the same
 * typed InvalidCursor with a generic message, and the key never appears in any of them.
 */
class ConsumerCursorCodecTest {

    /** FIXTURE keys — 32 deterministic bytes each, not production values. */
    static final String KEY_A = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    static final String KEY_B = Base64.getEncoder().encodeToString(
            "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

    static ConsumerCursorCodec codec(String keyB64) {
        ConsumerCursorProperties p = new ConsumerCursorProperties();
        p.setCursorHmacKeyB64(keyB64);
        return new ConsumerCursorCodec(p);
    }

    private static final ListCursor SAMPLE = new ListCursor("TZC-000001", "R1", 20, "TZP-L020");

    @Test
    void round_trip_is_exact_and_deterministic() {
        ConsumerCursorCodec codec = codec(KEY_A);
        String token = codec.encode(SAMPLE);

        assertThat(codec.decode(token)).isEqualTo(SAMPLE);
        assertThat(codec.encode(SAMPLE)).as("same input, same bytes").isEqualTo(token);
        assertThat(token).as("Base64URL, no padding").matches("[A-Za-z0-9_-]+");
        assertThat(token).doesNotContain("TZC-000001").doesNotContain("TZP-L020");
    }

    @Test
    void the_wire_form_carries_no_secret_and_only_the_bound_fields() {
        byte[] raw = Base64.getUrlDecoder().decode(codec(KEY_A).encode(SAMPLE));
        String payload = new String(Arrays.copyOfRange(raw, 0, raw.length - 32), StandardCharsets.UTF_8);

        assertThat(payload).isEqualTo("1|list|1|TZC-000001|R1|20|id|asc|TZP-L020");
        assertThat(payload).doesNotContain("0123456789abcdef");
    }

    @Test
    void a_tampered_payload_byte_is_rejected_on_the_signature() {
        ConsumerCursorCodec codec = codec(KEY_A);
        byte[] raw = Base64.getUrlDecoder().decode(codec.encode(SAMPLE));
        raw[raw.length - 32 - 1] ^= 0x01;                  // last payload byte: the product id
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        assertThatThrownBy(() -> codec.decode(tampered)).isInstanceOf(ConsumerFailures.InvalidCursor.class);
    }

    @Test
    void a_tampered_signature_byte_is_rejected() {
        ConsumerCursorCodec codec = codec(KEY_A);
        byte[] raw = Base64.getUrlDecoder().decode(codec.encode(SAMPLE));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        assertThatThrownBy(() -> codec.decode(tampered)).isInstanceOf(ConsumerFailures.InvalidCursor.class);
    }

    @Test
    void a_cursor_minted_under_another_key_is_rejected() {
        String token = codec(KEY_B).encode(SAMPLE);
        assertThatThrownBy(() -> codec(KEY_A).decode(token))
                .as("rotation invalidates outstanding cursors -- by design in v1")
                .isInstanceOf(ConsumerFailures.InvalidCursor.class);
    }

    @Test
    void a_correctly_signed_payload_with_a_wrong_version_or_identity_is_still_refused() {
        // Signed with the real key, so the signature passes and THEN the structure is refused:
        // version checking happens after authentication and is still enforced.
        ConsumerCursorCodec codec = codec(KEY_A);
        for (String bad : new String[]{
                "2|list|1|TZC-000001|R1|20|id|asc|TZP-L020",       // cursor version
                "1|children|1|TZC-000001|R1|20|id|asc|TZP-L020",   // route
                "1|list|2|TZC-000001|R1|20|id|asc|TZP-L020",       // query version
                "1|list|1|TZC-000001|R1|20|name|asc|TZP-L020",     // sort
                "1|list|1|TZC-000001|R1|20|id|desc|TZP-L020",      // direction
                "1|list|1|TZC-000001|R1|zero|id|asc|TZP-L020",     // page size not a number
                "1|list|1|TZC-000001|R1|0|id|asc|TZP-L020",        // page size < 1
                "1|list|1||R1|20|id|asc|TZP-L020",                 // empty node
                "1|list|1|TZC-000001|R1|20|id|asc",                // shape: too few fields
                "1|list|1|TZC-000001|R1|20|id|asc|TZP-L020|extra"  // shape: too many
        }) {
            assertThatThrownBy(() -> codec.decode(signedBy(KEY_A, bad)))
                    .as(bad).isInstanceOf(ConsumerFailures.InvalidCursor.class);
        }
        assertThat(codec.decode(signedBy(KEY_A, "1|list|1|TZC-000001|R1|20|id|asc|TZP-L020")))
                .as("the same payload, well-formed, is accepted -- so the refusals above are structural")
                .isEqualTo(SAMPLE);
    }

    @Test
    void malformed_and_oversized_tokens_are_rejected_before_parsing() {
        ConsumerCursorCodec codec = codec(KEY_A);
        for (String bad : new String[]{"", "not-a-cursor!", "AAAA", "===", "a".repeat(2049),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]),   // no payload
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[40])}) {
            assertThatThrownBy(() -> codec.decode(bad)).as("[" + bad.length() + " chars]")
                    .isInstanceOf(ConsumerFailures.InvalidCursor.class);
        }
        assertThat(codec.encode(SAMPLE).length()).isLessThan(ConsumerCursorCodec.MAX_ENCODED_LENGTH);
    }

    @Test
    void fields_containing_the_separator_or_encoding_characters_survive_the_round_trip() {
        ConsumerCursorCodec codec = codec(KEY_A);
        for (ListCursor odd : new ListCursor[]{
                new ListCursor("TZC-0|0001", "rel|1.0", 7, "TZP-|x"),
                new ListCursor("TZP+1 %2", "R 1", 3, "a+b"),
                new ListCursor("%7C", "%25", 1, "%2B%20")}) {
            assertThat(codec.decode(codec.encode(odd))).as(odd.toString()).isEqualTo(odd);
        }
    }

    @Test
    void the_length_cap_fires_before_decoding_and_the_boundary_is_decoded_then_refused_on_signature() {
        ConsumerCursorCodec codec = codec(KEY_A);
        assertThatThrownBy(() -> codec.decode("A".repeat(2049)))
                .isInstanceOf(ConsumerFailures.InvalidCursor.class).hasMessage("cursor length");
        assertThatThrownBy(() -> codec.decode("A".repeat(2048)))
                .as("2048 is within the cap: it decodes and then fails as unsigned by us")
                .isInstanceOf(ConsumerFailures.InvalidCursor.class).hasMessage("cursor signature");
    }

    @Test
    void a_signature_stripped_token_is_rejected() {
        ConsumerCursorCodec codec = codec(KEY_A);
        byte[] raw = Base64.getUrlDecoder().decode(codec.encode(SAMPLE));
        String payloadOnly = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOfRange(raw, 0, raw.length - 32));
        assertThatThrownBy(() -> codec.decode(payloadOnly)).isInstanceOf(ConsumerFailures.InvalidCursor.class);
    }

    @Test
    void a_cursor_has_exactly_one_accepted_spelling() {
        ConsumerCursorCodec codec = codec(KEY_A);
        String token = codec.encode(SAMPLE);
        byte[] raw = Base64.getUrlDecoder().decode(token);
        String padded = Base64.getUrlEncoder().encodeToString(raw);          // with '=' padding
        String standardAlphabet = Base64.getEncoder().withoutPadding().encodeToString(raw);

        assertThat(codec.decode(token)).isEqualTo(SAMPLE);
        if (!padded.equals(token)) {
            assertThatThrownBy(() -> codec.decode(padded))
                    .as("the JDK decoder would accept padding; the codec must not")
                    .isInstanceOf(ConsumerFailures.InvalidCursor.class).hasMessage("cursor encoding");
        }
        if (!standardAlphabet.equals(token)) {
            assertThatThrownBy(() -> codec.decode(standardAlphabet))
                    .isInstanceOf(ConsumerFailures.InvalidCursor.class);
        }
        // a last character with non-zero unused bits decodes to the same bytes under a lenient
        // decoder; the canonical re-encoding differs, so it is refused
        char last = token.charAt(token.length() - 1);
        String flipped = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> codec.decode(flipped)).isInstanceOf(ConsumerFailures.InvalidCursor.class);
    }

    @Test
    void the_key_is_standard_base64_only() {
        byte[] raw = new byte[32];
        raw[0] = (byte) 0xfb;   // forces '+' / '-' to appear in the first quartet
        raw[1] = (byte) 0xff;
        String standard = Base64.getEncoder().encodeToString(raw);
        String urlSafe = Base64.getUrlEncoder().encodeToString(raw);
        assertThat(standard).isNotEqualTo(urlSafe);
        assertThat(codec(standard).isReady()).isTrue();
        assertThat(codec(urlSafe).isReady()).as("one configured value, one meaning").isFalse();
    }

    @Test
    void no_rejection_message_carries_key_material() {
        ConsumerCursorCodec codec = codec(KEY_A);
        String[] inputs = {"", "a".repeat(2049), "!!!", codec(KEY_B).encode(SAMPLE)};
        for (String input : inputs) {
            assertThatThrownBy(() -> codec.decode(input))
                    .satisfies(e -> assertThat(e.getMessage())
                            .doesNotContain(KEY_A).doesNotContain("0123456789abcdef"));
        }
    }

    @Test
    void a_missing_or_malformed_key_is_not_ready_and_fails_closed_naming_no_key() {
        String[] bad = {null, "", "   ", "not base64!!", Base64.getEncoder().encodeToString(new byte[31])};
        for (String keyB64 : bad) {
            ConsumerCursorCodec codec = codec(keyB64);
            assertThat(codec.isReady()).as("[" + keyB64 + "]").isFalse();
            assertThatThrownBy(codec::requireReady).isInstanceOf(ConsumerFailures.Unavailable.class);
            if (keyB64 != null && !keyB64.isBlank()) {
                assertThatThrownBy(codec::requireReady)
                        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(keyB64));
            }
            assertThatThrownBy(() -> codec.encode(SAMPLE)).isInstanceOf(ConsumerFailures.Unavailable.class);
            assertThatThrownBy(() -> codec.decode("anything")).isInstanceOf(ConsumerFailures.Unavailable.class);
        }
        assertThat(codec(Base64.getEncoder().encodeToString(new byte[32])).isReady())
                .as("exactly 32 bytes is the minimum").isTrue();
    }

    /** Signs an arbitrary payload with the fixture key, to forge structurally-wrong cursors. */
    private static String signedBy(String keyB64, String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(keyB64), "HmacSHA256"));
            byte[] p = payload.getBytes(StandardCharsets.UTF_8);
            byte[] sig = mac.doFinal(p);
            byte[] token = Arrays.copyOf(p, p.length + sig.length);
            System.arraycopy(sig, 0, token, p.length, sig.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }
}
