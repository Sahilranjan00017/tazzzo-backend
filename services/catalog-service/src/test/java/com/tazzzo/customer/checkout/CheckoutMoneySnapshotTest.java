package com.tazzzo.customer.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.customer.address.AddressId;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Checkout ADVISORY money snapshot: formula and invariants, the persisted shape and STRICT reconstruction, the
 * legacy compatibility matrix, the quote-level consistency with the Benefits snapshot, the public-safe projection and
 * the exact public JSON (no Mongo, no Spring).
 */
class CheckoutMoneySnapshotTest {

    // TEST FIXTURES only (never launch policy)
    private static final long SUBTOTAL = 10_000;
    private static final Instant T = Instant.parse("2026-06-01T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CheckoutBenefitSnapshot.Applied applied() {
        return new CheckoutBenefitSnapshot.Applied(SUBTOTAL, 500, 500);
    }

    private static CheckoutBenefitSnapshot.NoBenefit noBenefit() {
        return new CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL, BenefitEvaluation.NoBenefitReason.NO_RULE);
    }

    private static CheckoutQuote quote(CheckoutBenefitSnapshot benefits, CheckoutMoneySnapshot money) {
        return new CheckoutQuote(CheckoutQuoteId.generate().value(), 1, AddressId.generate().value(), 1L,
                List.of(new CheckoutQuote.Line("TZP-1", 2, 5_000, SUBTOTAL)), 2, SUBTOTAL, "INR", T, T.plusSeconds(300),
                benefits, money);
    }

    private static Document doc(CheckoutQuote q) {
        return CheckoutQuoteRepository.toDocument(q, "cus_1", "digest", "fingerprint");
    }

    // ---------- formula and invariants ----------

    @Test
    void payable_is_the_subtotal_minus_the_discount_including_zero() {
        assertThat(new CheckoutMoneySnapshot(10_000, 0).payablePaise()).isEqualTo(10_000);
        assertThat(new CheckoutMoneySnapshot(10_000, 500).payablePaise()).isEqualTo(9_500);
        assertThat(new CheckoutMoneySnapshot(10_000, 10_000).payablePaise()).as("zero payable is valid").isZero();
        assertThat(new CheckoutMoneySnapshot(0, 0).payablePaise()).isZero();
        assertThat(new CheckoutMoneySnapshot(Long.MAX_VALUE, 0).payablePaise()).isEqualTo(Long.MAX_VALUE);
        assertThat(new CheckoutMoneySnapshot(Long.MAX_VALUE, Long.MAX_VALUE).payablePaise()).isZero();
        assertThat(new CheckoutMoneySnapshot(Long.MAX_VALUE, 1).payablePaise()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @Test
    void invalid_components_are_rejected() {
        assertThatThrownBy(() -> new CheckoutMoneySnapshot(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutMoneySnapshot(100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutMoneySnapshot(100, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutMoneySnapshot(Long.MAX_VALUE - 1, Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payable_is_derived_never_a_record_component() {
        assertThat(Arrays.stream(CheckoutMoneySnapshot.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("merchandiseSubtotalPaise", "benefitDiscountPaise");
    }

    @Test
    void the_snapshot_is_built_from_the_STORED_benefits_discount_never_from_the_rate() {
        // 777 != floor(10000 * 123 / 10000) = 123: a recomputation from the rate would be visible
        CheckoutBenefitSnapshot stored = new CheckoutBenefitSnapshot.Applied(SUBTOTAL, 777, 123);

        assertThat(CheckoutMoneySnapshot.from(SUBTOTAL, stored)).isEqualTo(new CheckoutMoneySnapshot(SUBTOTAL, 777));
        assertThat(CheckoutMoneySnapshot.from(SUBTOTAL, stored).payablePaise()).isEqualTo(9_223);
        for (BenefitEvaluation.NoBenefitReason reason : BenefitEvaluation.NoBenefitReason.values()) {
            assertThat(CheckoutMoneySnapshot.from(SUBTOTAL, new CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL, reason)))
                    .as("%s", reason).isEqualTo(new CheckoutMoneySnapshot(SUBTOTAL, 0));
        }
    }

    // ---------- persisted shape and strict reconstruction ----------

    @Test
    void the_valid_shapes_round_trip_with_an_explicit_stored_payable() {
        for (var pair : List.of(
                new Object[]{noBenefit(), new CheckoutMoneySnapshot(SUBTOTAL, 0), 10_000L},
                new Object[]{applied(), new CheckoutMoneySnapshot(SUBTOTAL, 500), 9_500L},
                new Object[]{new CheckoutBenefitSnapshot.Applied(SUBTOTAL, SUBTOTAL, 10_000),
                        new CheckoutMoneySnapshot(SUBTOTAL, SUBTOTAL), 0L})) {
            CheckoutQuote q = quote((CheckoutBenefitSnapshot) pair[0], (CheckoutMoneySnapshot) pair[1]);
            Document stored = (Document) doc(q).get("money");

            assertThat(stored.keySet()).containsExactlyInAnyOrder("merchandiseSubtotalPaise", "benefitDiscountPaise",
                    "payablePaise");
            assertThat(stored.get("payablePaise")).isEqualTo(pair[2]);
            assertThat(CheckoutQuoteRepository.toQuote(doc(q))).isEqualTo(q);
            assertThat(doc(q).keySet()).as("no duplicated top-level money field")
                    .doesNotContain("merchandiseSubtotalPaise", "benefitDiscountPaise", "payablePaise");
        }
    }

    private static void corruptMoney(CheckoutQuote base, Consumer<Document> mutation) {
        Document d = doc(base);
        mutation.accept((Document) d.get("money"));
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
    void every_malformed_money_document_fails_reconstruction() {
        CheckoutQuote base = quote(applied(), new CheckoutMoneySnapshot(SUBTOTAL, 500));
        corruptMoney(base, m -> m.remove("payablePaise"));                      // missing field
        corruptMoney(base, m -> m.remove("merchandiseSubtotalPaise"));
        corruptMoney(base, m -> m.remove("benefitDiscountPaise"));
        corruptMoney(base, m -> m.put("payablePaise", null));                   // explicit null
        corruptMoney(base, m -> m.put("benefitDiscountPaise", null));
        corruptMoney(base, m -> m.put("payablePaise", "9500"));                 // wrong BSON type
        corruptMoney(base, m -> m.put("payablePaise", 9_500.0));
        corruptMoney(base, m -> m.put("merchandiseSubtotalPaise", "10000"));
        corruptMoney(base, m -> m.put("benefitDiscountPaise", 500.0));
        corruptMoney(base, m -> m.put("payablePaise", -500L));                  // negative
        corruptMoney(base, m -> m.put("benefitDiscountPaise", -1L));
        corruptMoney(base, m -> m.put("merchandiseSubtotalPaise", -1L));
        corruptMoney(base, m -> m.put("benefitDiscountPaise", SUBTOTAL + 1));   // discount > subtotal
        corruptMoney(base, m -> m.put("payablePaise", 10_000L));                // formula mismatch (payable = subtotal)
        corruptMoney(base, m -> m.put("payablePaise", 0L));                     // formula mismatch (payable = 0)
        corruptMoney(base, m -> m.put("payablePaise", 9_501L));
        corruptMoney(base, m -> m.put("merchandiseSubtotalPaise", SUBTOTAL + 1)); // != the quote subtotal (and formula)
        corruptMoney(base, m -> m.put("benefitDiscountPaise", 499L));           // != the stored Benefits discount
        corruptMoney(base, m -> { m.put("benefitDiscountPaise", 499L); m.put("payablePaise", 9_501L); });
        corruptMoney(base, m -> m.put("taxPaise", 0L));                         // foreign / placeholder fields
        corruptMoney(base, m -> m.put("feePaise", 0L));
        corruptMoney(base, m -> m.put("walletPaise", 0L));
    }

    @Test
    void a_money_field_that_is_not_a_document_fails_reconstruction() {
        CheckoutQuote base = quote(applied(), new CheckoutMoneySnapshot(SUBTOTAL, 500));
        corruptDocument(base, d -> d.put("money", null));                       // an explicit null is NOT "absent"
        corruptDocument(base, d -> d.put("money", "9500"));
        corruptDocument(base, d -> d.put("money", 9_500L));
        corruptDocument(base, d -> d.put("money", List.of()));
        corruptDocument(base, d -> d.put("money", new Document()));             // empty object
    }

    @Test
    void int32_is_accepted_like_the_neighbouring_codecs_and_int64_round_trips() {
        Document d = doc(quote(applied(), new CheckoutMoneySnapshot(SUBTOTAL, 500)));
        Document money = (Document) d.get("money");
        money.put("merchandiseSubtotalPaise", 10_000);
        money.put("benefitDiscountPaise", 500);
        money.put("payablePaise", 9_500);

        assertThat(CheckoutQuoteRepository.toQuote(d).moneySnapshot()).isEqualTo(new CheckoutMoneySnapshot(SUBTOTAL, 500));
    }

    // ---------- the legacy / consistency matrix ----------

    @Test
    void legacy_matrix_A_benefits_absent_money_absent_is_a_valid_legacy_quote() {
        CheckoutQuote q = quote(null, null);

        assertThat(CheckoutQuoteRepository.toQuote(doc(q))).isEqualTo(q);
        assertThat(doc(q).containsKey("money")).as("never written as null/placeholder").isFalse();
        assertThat(q.moneyPreview()).as("absent, NOT payable = subtotal and NOT 0").isEmpty();
    }

    @Test
    void legacy_matrix_B_and_C_benefits_present_money_absent_are_valid_pre_money_quotes() {
        for (CheckoutBenefitSnapshot b : List.of(noBenefit(), applied())) {
            CheckoutQuote q = quote(b, null);

            CheckoutQuote read = CheckoutQuoteRepository.toQuote(doc(q));
            assertThat(read).as("%s", b).isEqualTo(q);
            assertThat(read.moneySnapshot()).isNull();
            assertThat(read.moneyPreview()).as("no synthesized preview for a legacy quote").isEmpty();
        }
    }

    @Test
    void legacy_matrix_D_money_without_benefits_is_invalid() {
        assertThatThrownBy(() -> quote(null, new CheckoutMoneySnapshot(SUBTOTAL, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("benefit snapshot");
        Document d = doc(quote(noBenefit(), new CheckoutMoneySnapshot(SUBTOTAL, 0)));
        d.remove("benefits");
        assertThatThrownBy(() -> CheckoutQuoteRepository.toQuote(d)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacy_matrix_E_and_F_matching_money_is_valid() {
        CheckoutQuote none = quote(noBenefit(), new CheckoutMoneySnapshot(SUBTOTAL, 0));
        CheckoutQuote some = quote(applied(), new CheckoutMoneySnapshot(SUBTOTAL, 500));

        assertThat(CheckoutQuoteRepository.toQuote(doc(none))).isEqualTo(none);
        assertThat(CheckoutQuoteRepository.toQuote(doc(some))).isEqualTo(some);
        assertThat(some.moneyPreview()).hasValue(new CheckoutMoneyPreview(10_000, 500, 9_500));
        assertThat(none.moneyPreview()).hasValue(new CheckoutMoneyPreview(10_000, 0, 10_000));
    }

    @Test
    void legacy_matrix_G_money_that_disagrees_with_the_benefits_snapshot_is_invalid() {
        assertThatThrownBy(() -> quote(applied(), new CheckoutMoneySnapshot(SUBTOTAL, 499)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("benefit discount");
        assertThatThrownBy(() -> quote(applied(), new CheckoutMoneySnapshot(SUBTOTAL, 0)))
                .as("applied benefit but zero money discount").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote(noBenefit(), new CheckoutMoneySnapshot(SUBTOTAL, 1)))
                .as("no benefit but a money discount").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote(noBenefit(), new CheckoutMoneySnapshot(SUBTOTAL + 1, 0)))
                .as("a different subtotal").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subtotal");
    }

    // ---------- the public-safe projection and the exact public JSON ----------

    private static JsonNode json(CheckoutQuote q) {
        return JSON.valueToTree(CheckoutQuoteDto.of(q, "req_1"));
    }

    @Test
    void the_public_money_preview_json_is_exactly_the_three_amounts() {
        assertThat(json(quote(noBenefit(), new CheckoutMoneySnapshot(SUBTOTAL, 0))).get("moneyPreview").toString())
                .isEqualTo("{\"merchandiseSubtotalPaise\":10000,\"benefitDiscountPaise\":0,\"payablePaise\":10000}");
        assertThat(json(quote(applied(), new CheckoutMoneySnapshot(SUBTOTAL, 500))).get("moneyPreview").toString())
                .isEqualTo("{\"merchandiseSubtotalPaise\":10000,\"benefitDiscountPaise\":500,\"payablePaise\":9500}");
        assertThat(json(quote(new CheckoutBenefitSnapshot.Applied(SUBTOTAL, SUBTOTAL, 10_000),
                new CheckoutMoneySnapshot(SUBTOTAL, SUBTOTAL))).get("moneyPreview").toString())
                .isEqualTo("{\"merchandiseSubtotalPaise\":10000,\"benefitDiscountPaise\":10000,\"payablePaise\":0}");
    }

    @Test
    void a_legacy_quote_has_no_money_preview_field_at_all_and_benefit_preview_is_unchanged() {
        assertThat(json(quote(null, null)).has("moneyPreview")).isFalse();
        JsonNode benefitsOnly = json(quote(applied(), null));
        assertThat(benefitsOnly.has("moneyPreview")).as("absent, never null").isFalse();
        assertThat(benefitsOnly.get("benefitPreview").toString())
                .isEqualTo("{\"applied\":true,\"discountPaise\":500,\"discountBps\":500}");
    }

    @Test
    void the_public_money_preview_is_consistent_with_the_top_level_subtotal_and_the_benefit_preview() {
        for (CheckoutQuote q : List.of(quote(noBenefit(), new CheckoutMoneySnapshot(SUBTOTAL, 0)),
                quote(applied(), new CheckoutMoneySnapshot(SUBTOTAL, 500)))) {
            JsonNode j = json(q);
            JsonNode money = j.get("moneyPreview");
            JsonNode benefit = j.get("benefitPreview");

            assertThat(money.get("merchandiseSubtotalPaise").asLong()).isEqualTo(j.get("subtotalPaise").asLong());
            assertThat(money.get("benefitDiscountPaise").asLong())
                    .isEqualTo(benefit.get("applied").asBoolean() ? benefit.get("discountPaise").asLong() : 0L);
            assertThat(money.get("payablePaise").asLong()).isEqualTo(
                    money.get("merchandiseSubtotalPaise").asLong() - money.get("benefitDiscountPaise").asLong());
        }
    }

    @Test
    void the_public_quote_dto_gains_only_the_nested_money_preview_beside_the_benefit_preview() {
        assertThat(Arrays.stream(CheckoutQuoteDto.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("quoteId", "cartVersion", "addressId", "items", "itemCount", "distinctItemCount",
                        "subtotalPaise", "currency", "createdAt", "expiresAt", "benefitPreview", "moneyPreview",
                        "requestId");
        assertThat(Arrays.stream(CheckoutQuoteDto.MoneyPreview.class.getRecordComponents()).map(c -> c.getName())
                .toList()).containsExactly("merchandiseSubtotalPaise", "benefitDiscountPaise", "payablePaise");
        // the random ids (quoteId, addressId) can contain any of the probed words by chance ("fee", "plan", ...): scan
        // every field name and every other value, never the generated ids
        com.fasterxml.jackson.databind.node.ObjectNode probed =
                (com.fasterxml.jackson.databind.node.ObjectNode) json(quote(applied(), new CheckoutMoneySnapshot(SUBTOTAL, 500)));
        assertThat(probed.has("quoteId") && probed.has("addressId")).isTrue();
        probed.put("quoteId", "QUOTE_ID").put("addressId", "ADDRESS_ID");
        String text = probed.toString().toLowerCase();
        assertThat(text).doesNotContain("tax").doesNotContain("gst").doesNotContain("fee").doesNotContain("coupon")
                .doesNotContain("coin").doesNotContain("wallet").doesNotContain("reason").doesNotContain("membership")
                .doesNotContain("plan").doesNotContain("eligible").doesNotContain("payment").doesNotContain("amountdue")
                .doesNotContain("no_rule");
    }

    @Test
    void the_public_money_preview_dto_and_projection_reject_inconsistent_values() {
        assertThat(new CheckoutQuoteDto.MoneyPreview(10_000, 500, 9_500)).isNotNull();
        assertThat(new CheckoutQuoteDto.MoneyPreview(10_000, 10_000, 0)).isNotNull();
        assertThatThrownBy(() -> new CheckoutQuoteDto.MoneyPreview(10_000, 500, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutQuoteDto.MoneyPreview(10_000, 10_001, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutQuoteDto.MoneyPreview(-1, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutMoneyPreview(10_000, 500, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
