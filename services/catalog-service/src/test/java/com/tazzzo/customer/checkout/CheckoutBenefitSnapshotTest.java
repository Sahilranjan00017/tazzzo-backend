package com.tazzzo.customer.checkout;

import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.benefits.DiscountBps;
import com.tazzzo.common.money.Money;
import com.tazzzo.customer.address.AddressId;
import com.tazzzo.membership.MembershipId;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Checkout advisory Benefits snapshot: invariants, the persisted shape and STRICT reconstruction (no Mongo). */
class CheckoutBenefitSnapshotTest {

    // TEST FIXTURES only (never launch policy)
    private static final long SUBTOTAL = 10_000;
    private static final Instant T = Instant.parse("2026-06-01T00:00:00Z");

    private static CheckoutBenefitSnapshot.Applied applied() {
        return new CheckoutBenefitSnapshot.Applied(SUBTOTAL, 500, 500);
    }

    private static CheckoutBenefitSnapshot.NoBenefit noBenefit(BenefitEvaluation.NoBenefitReason reason) {
        return new CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL, reason);
    }

    private static CheckoutQuote quote(CheckoutBenefitSnapshot snapshot) {
        return new CheckoutQuote(CheckoutQuoteId.generate().value(), 1, AddressId.generate().value(), 1L,
                List.of(new CheckoutQuote.Line("TZP-1", 2, 5_000, SUBTOTAL)), 2, SUBTOTAL, "INR", T, T.plusSeconds(300),
                snapshot);
    }

    private static Document doc(CheckoutQuote q) {
        return CheckoutQuoteRepository.toDocument(q, "cus_1", "digest", "fingerprint");
    }

    // ---------- the two shapes ----------

    @Test
    void the_valid_shapes_round_trip_through_the_quote_and_its_document() {
        for (CheckoutBenefitSnapshot snapshot : List.of(applied(),
                noBenefit(BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP),
                noBenefit(BenefitEvaluation.NoBenefitReason.NO_RULE),
                noBenefit(BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE))) {
            CheckoutQuote q = quote(snapshot);

            assertThat(CheckoutQuoteRepository.toQuote(doc(q))).as("%s", snapshot).isEqualTo(q);
            assertThat(CheckoutQuoteRepository.toQuote(doc(q)).benefitSnapshot()).isEqualTo(snapshot);
        }
    }

    @Test
    void a_legacy_quote_without_a_snapshot_reconstructs_with_none_and_that_is_not_no_benefit() {
        CheckoutQuote legacy = quote(null);
        Document d = doc(legacy);

        assertThat(d.containsKey("benefits")).as("never written as null/placeholder").isFalse();
        CheckoutQuote reconstructed = CheckoutQuoteRepository.toQuote(d);
        assertThat(reconstructed.benefitSnapshot()).isNull();
        assertThat(reconstructed).isEqualTo(legacy);
    }

    @Test
    void the_persisted_shapes_use_conditional_presence_with_no_placeholders_and_no_membership_identity() {
        Document none = (Document) doc(quote(noBenefit(BenefitEvaluation.NoBenefitReason.NO_RULE))).get("benefits");
        assertThat(none.keySet()).containsExactlyInAnyOrder("outcome", "eligibleSubtotalPaise", "noBenefitReason");
        assertThat(none.getString("outcome")).isEqualTo("NO_BENEFIT");
        assertThat(none.getString("noBenefitReason")).isEqualTo("NO_RULE");

        Document yes = (Document) doc(quote(applied())).get("benefits");
        assertThat(yes.keySet()).as("deliberately NO membershipId/planId/planVersion")
                .containsExactlyInAnyOrder("outcome", "eligibleSubtotalPaise", "discountPaise", "discountBps");
        assertThat(yes.get("discountPaise")).isEqualTo(500L);
        assertThat(yes.get("discountBps")).isEqualTo(500);
    }

    @Test
    void the_snapshot_is_projected_exactly_from_the_benefits_result_without_checkout_arithmetic() {
        BenefitEvaluation.Applied result = new BenefitEvaluation.Applied(MembershipId.generate(), "TAZZZO_PLUS_MONTHLY",
                3, Money.ofInrPaise(123_457), new DiscountBps(500).applyTo(Money.ofInrPaise(123_457)),
                new DiscountBps(500));

        CheckoutBenefitSnapshot.Applied snapshot = (CheckoutBenefitSnapshot.Applied)
                CheckoutBenefitSnapshot.from(result, 123_457);

        assertThat(snapshot).isEqualTo(new CheckoutBenefitSnapshot.Applied(123_457, 6_172, 500));
        for (BenefitEvaluation.NoBenefitReason reason : BenefitEvaluation.NoBenefitReason.values()) {
            assertThat(CheckoutBenefitSnapshot.from(new BenefitEvaluation.NoBenefit(reason), SUBTOTAL))
                    .isEqualTo(new CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL, reason));
        }
    }

    @Test
    void a_benefits_result_for_a_different_subtotal_is_rejected_not_copied() {
        BenefitEvaluation.Applied result = new BenefitEvaluation.Applied(MembershipId.generate(), "TAZZZO_PLUS_MONTHLY",
                1, Money.ofInrPaise(20_000), Money.ofInrPaise(1_000), new DiscountBps(500));

        assertThatThrownBy(() -> CheckoutBenefitSnapshot.from(result, SUBTOTAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- value invariants ----------

    @Test
    void the_applied_snapshot_enforces_its_invariants() {
        assertThatThrownBy(() -> new CheckoutBenefitSnapshot.Applied(SUBTOTAL, 0, 500)).as("zero discount")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutBenefitSnapshot.Applied(SUBTOTAL, SUBTOTAL + 1, 500))
                .as("discount > subtotal").isInstanceOf(IllegalArgumentException.class);
        assertThat(new CheckoutBenefitSnapshot.Applied(SUBTOTAL, SUBTOTAL, 10_000)).as("100% is valid").isNotNull();
        assertThatThrownBy(() -> new CheckoutBenefitSnapshot.Applied(SUBTOTAL, 500, 0)).as("bps 0")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutBenefitSnapshot.Applied(SUBTOTAL, 500, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL, null)).as("reason required")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutBenefitSnapshot.NoBenefit(-1, BenefitEvaluation.NoBenefitReason.NO_RULE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_quote_rejects_a_snapshot_about_a_different_subtotal() {
        assertThatThrownBy(() -> quote(
                new CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL + 1, BenefitEvaluation.NoBenefitReason.NO_RULE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eligibleSubtotalPaise");
        assertThatThrownBy(() -> quote(new CheckoutBenefitSnapshot.Applied(SUBTOTAL - 1, 500, 500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- strict reconstruction: every malformed snapshot fails loud ----------

    private static void corrupt(CheckoutQuote base, Consumer<Document> mutation) {
        Document d = doc(base);
        mutation.accept((Document) d.get("benefits"));
        assertThatThrownBy(() -> CheckoutQuoteRepository.toQuote(d))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class, ArithmeticException.class);
    }

    private static void corruptDocument(CheckoutQuote base, Consumer<Document> mutation) {
        Document d = doc(base);
        mutation.accept(d);
        assertThatThrownBy(() -> CheckoutQuoteRepository.toQuote(d))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class, ArithmeticException.class);
    }

    @Test
    void every_malformed_no_benefit_snapshot_fails_reconstruction() {
        CheckoutQuote base = quote(noBenefit(BenefitEvaluation.NoBenefitReason.NO_RULE));
        corrupt(base, b -> b.remove("noBenefitReason"));                       // missing reason
        corrupt(base, b -> b.put("discountPaise", 0L));                        // forbidden applied fields (even zero)
        corrupt(base, b -> b.put("discountBps", 0));
        corrupt(base, b -> b.put("membershipId", "MBR_x"));                    // identity is never persisted here
        corrupt(base, b -> b.put("noBenefitReason", "SOMETHING_ELSE"));        // unknown reason
        corrupt(base, b -> b.put("noBenefitReason", 7));                       // wrong type
        corrupt(base, b -> b.put("noBenefitReason", ""));
        corrupt(base, b -> b.put("outcome", "WHATEVER"));                      // unknown outcome
        corrupt(base, b -> b.remove("outcome"));
        corrupt(base, b -> b.put("outcome", 1));
        corrupt(base, b -> b.remove("eligibleSubtotalPaise"));
        corrupt(base, b -> b.put("eligibleSubtotalPaise", "10000"));           // wrong types
        corrupt(base, b -> b.put("eligibleSubtotalPaise", 10_000.0));
        corrupt(base, b -> b.put("eligibleSubtotalPaise", SUBTOTAL + 1));      // != the quote subtotal
        corrupt(base, b -> b.put("eligibleSubtotalPaise", -5L));
        corrupt(base, b -> b.put("unexpected", "x"));                          // foreign field
    }

    @Test
    void every_malformed_applied_snapshot_fails_reconstruction() {
        CheckoutQuote base = quote(applied());
        corrupt(base, b -> b.remove("discountPaise"));                         // missing applied fields
        corrupt(base, b -> b.remove("discountBps"));
        corrupt(base, b -> b.put("discountPaise", 0L));                        // zero discount
        corrupt(base, b -> b.put("discountPaise", SUBTOTAL + 1));              // discount > subtotal
        corrupt(base, b -> b.put("discountPaise", -1L));
        corrupt(base, b -> b.put("discountPaise", "500"));                     // wrong types
        corrupt(base, b -> b.put("discountBps", 0));                           // bad bps
        corrupt(base, b -> b.put("discountBps", 10_001));
        corrupt(base, b -> b.put("discountBps", "500"));
        corrupt(base, b -> b.put("discountBps", 500.5));
        corrupt(base, b -> b.put("noBenefitReason", "NO_RULE"));               // foreign field
        corrupt(base, b -> b.put("membershipId", "MBR_x"));                    // not a field of this shape
        corrupt(base, b -> b.put("eligibleSubtotalPaise", SUBTOTAL - 1));      // != the quote subtotal
        corrupt(base, b -> b.put("extra", true));
    }

    @Test
    void a_snapshot_field_that_is_not_a_document_fails_reconstruction() {
        CheckoutQuote base = quote(applied());
        corruptDocument(base, d -> d.put("benefits", null));                   // an explicit null is NOT "absent"
        corruptDocument(base, d -> d.put("benefits", "NO_BENEFIT"));
        corruptDocument(base, d -> d.put("benefits", 1));
        corruptDocument(base, d -> d.put("benefits", List.of()));
        corruptDocument(base, d -> d.put("benefits", new Document()));         // empty object
    }

    @Test
    void the_public_quote_dto_gains_only_the_one_nested_benefit_preview_and_no_internal_or_payable_field() {
        assertThat(Arrays.stream(CheckoutQuoteDto.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("quoteId", "cartVersion", "addressId", "items", "itemCount", "distinctItemCount",
                        "subtotalPaise", "currency", "createdAt", "expiresAt", "benefitPreview", "requestId");
        assertThat(CheckoutQuoteDto.of(quote(applied()), "req").toString().toLowerCase()).doesNotContain("reason")
                .doesNotContain("membership").doesNotContain("plan").doesNotContain("eligible")
                .doesNotContain("payable").doesNotContain("no_rule");
    }
}
