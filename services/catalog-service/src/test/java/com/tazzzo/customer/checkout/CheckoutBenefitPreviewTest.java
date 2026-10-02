package com.tazzzo.customer.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.customer.address.AddressId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public projection of the stored advisory snapshot: shapes, invariants and the exact JSON (no Mongo, no Spring). */
class CheckoutBenefitPreviewTest {

    private static final long SUBTOTAL = 10_000;
    private static final Instant T = Instant.parse("2026-06-01T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CheckoutQuote quote(CheckoutBenefitSnapshot snapshot) {
        return new CheckoutQuote(CheckoutQuoteId.generate().value(), 1, AddressId.generate().value(), 1L,
                List.of(new CheckoutQuote.Line("TZP-1", 2, 5_000, SUBTOTAL)), 2, SUBTOTAL, "INR", T, T.plusSeconds(300),
                snapshot);
    }

    private static JsonNode json(CheckoutQuote q) {
        return JSON.valueToTree(CheckoutQuoteDto.of(q, "req_1"));
    }

    // ---------- projection ----------

    @Test
    void every_internal_no_benefit_reason_projects_to_the_identical_not_applied_preview() {
        for (BenefitEvaluation.NoBenefitReason reason : BenefitEvaluation.NoBenefitReason.values()) {
            assertThat(CheckoutBenefitPreview.from(new CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL, reason)))
                    .as("%s", reason).isEqualTo(new CheckoutBenefitPreview.NotApplied());
        }
    }

    @Test
    void an_applied_snapshot_projects_its_STORED_values_exactly_never_recomputed_from_the_rate() {
        // deliberately NOT floor(10000 * 123 / 10000) = 123: a recomputation would be visible
        CheckoutBenefitPreview p = CheckoutBenefitPreview.from(new CheckoutBenefitSnapshot.Applied(SUBTOTAL, 777, 123));

        assertThat(p).isEqualTo(new CheckoutBenefitPreview.Applied(777, 123));
    }

    @Test
    void a_legacy_quote_has_no_preview_at_all_which_is_not_the_same_as_not_applied() {
        assertThat(quote(null).benefitPreview()).isEmpty();
        assertThat(quote(new CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL,
                BenefitEvaluation.NoBenefitReason.NO_RULE)).benefitPreview())
                .hasValue(new CheckoutBenefitPreview.NotApplied());
    }

    @Test
    void the_projection_invariants_are_enforced() {
        assertThatThrownBy(() -> new CheckoutBenefitPreview.Applied(0, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutBenefitPreview.Applied(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutBenefitPreview.Applied(1, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CheckoutBenefitPreview.Applied(1, 10_000)).isNotNull();
    }

    // ---------- public DTO invariants ----------

    @Test
    void the_public_benefit_preview_dto_allows_only_the_two_valid_shapes() {
        assertThat(new CheckoutQuoteDto.BenefitPreview(false, null, null)).isNotNull();
        assertThat(new CheckoutQuoteDto.BenefitPreview(true, 500L, 500)).isNotNull();
        assertThatThrownBy(() -> new CheckoutQuoteDto.BenefitPreview(false, 500L, 500))
                .as("not applied with discount").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutQuoteDto.BenefitPreview(false, 500L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutQuoteDto.BenefitPreview(true, null, null)).as("applied without discount")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutQuoteDto.BenefitPreview(true, 0L, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutQuoteDto.BenefitPreview(true, 500L, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutQuoteDto.BenefitPreview(true, 500L, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Arrays.stream(CheckoutQuoteDto.BenefitPreview.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("applied", "discountPaise", "discountBps");
    }

    // ---------- the exact JSON ----------

    @Test
    void a_legacy_quote_omits_benefit_preview_from_the_json_entirely() {
        JsonNode node = json(quote(null));

        assertThat(node.has("benefitPreview")).isFalse();
        assertThat(node.toString()).doesNotContain("applied").doesNotContain("null");
    }

    @Test
    void every_modern_no_benefit_quote_serializes_to_exactly_applied_false() {
        for (BenefitEvaluation.NoBenefitReason reason : BenefitEvaluation.NoBenefitReason.values()) {
            JsonNode node = json(quote(new CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL, reason)));

            assertThat(node.get("benefitPreview").toString()).as("%s", reason).isEqualTo("{\"applied\":false}");
            assertThat(node.toString()).doesNotContain(reason.name()).doesNotContain("reason");
        }
    }

    @Test
    void an_applied_quote_serializes_exactly_applied_true_with_the_stored_discount_and_rate() {
        JsonNode node = json(quote(new CheckoutBenefitSnapshot.Applied(SUBTOTAL, 777, 123)));

        assertThat(node.get("benefitPreview").toString())
                .isEqualTo("{\"applied\":true,\"discountPaise\":777,\"discountBps\":123}");
        assertThat(node.get("subtotalPaise").asLong()).as("the canonical subtotal is untouched").isEqualTo(SUBTOTAL);
        assertThat(node.get("items").get(0).get("lineTotalPaise").asLong()).isEqualTo(SUBTOTAL);
        assertThat(node.toString()).doesNotContain("eligible").doesNotContain("membership").doesNotContain("plan")
                .doesNotContain("payable").doesNotContain("net");
    }

    @Test
    void the_quote_json_has_exactly_the_documented_top_level_fields() {
        assertThat(json(quote(new CheckoutBenefitSnapshot.Applied(SUBTOTAL, 500, 500))).fieldNames())
                .toIterable().containsExactly("quoteId", "cartVersion", "addressId", "items", "itemCount",
                        "distinctItemCount", "subtotalPaise", "currency", "createdAt", "expiresAt", "benefitPreview",
                        "requestId");
        assertThat(json(quote(null)).fieldNames()).toIterable().containsExactly("quoteId", "cartVersion", "addressId",
                "items", "itemCount", "distinctItemCount", "subtotalPaise", "currency", "createdAt", "expiresAt",
                "requestId");
    }
}
