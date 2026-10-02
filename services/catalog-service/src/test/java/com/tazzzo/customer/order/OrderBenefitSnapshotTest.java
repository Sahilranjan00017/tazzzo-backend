package com.tazzzo.customer.order;

import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.benefits.DiscountBps;
import com.tazzzo.common.money.Money;
import com.tazzzo.customer.address.AddressId;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.inventory.InventoryReservationId;
import com.tazzzo.membership.MembershipId;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Order Benefits snapshot: value invariants, the Order-level invariant and STRICT reconstruction (no Mongo). */
class OrderBenefitSnapshotTest {

    // TEST FIXTURES only (never launch policy)
    private static final long SUBTOTAL = 10_000;
    private static final Instant T = Instant.parse("2026-06-01T00:00:00Z");
    private static final String PLAN = "TAZZZO_PLUS_MONTHLY";
    private static final String MBR = MembershipId.generate().value();

    private static OrderBenefitSnapshot.Applied applied() {
        return new OrderBenefitSnapshot.Applied(SUBTOTAL, 500, 500, MBR, PLAN, 1);
    }

    private static OrderBenefitSnapshot.NoBenefit noBenefit(BenefitEvaluation.NoBenefitReason reason) {
        return new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, reason);
    }

    private static Order order(OrderStatus status, OrderBenefitSnapshot snapshot) {
        OrderAddressSnapshot address = new OrderAddressSnapshot("HOME", "Test Recipient", "9999999999",
                "123 Test Street", "Near Landmark", "Landmark", "Bengaluru", "Karnataka", "560001", 12.97, 77.59);
        List<OrderLine> lines = List.of(new OrderLine("TZP-1", "Widget", "BR-1", 2, 5_000, SUBTOTAL));
        boolean confirmed = status == OrderStatus.CONFIRMED;
        return new Order(OrderId.generate(), "cus_1", CheckoutQuoteId.generate().value(), status, PaymentMethod.COD,
                confirmed ? 2L : 1L, AddressId.generate().value(), 1L, address, lines, 2, SUBTOTAL, "INR",
                InventoryReservationId.generate().value(), confirmed ? ConfirmedPaymentCondition.COD_DUE : null, T,
                confirmed ? T : null, T, snapshot, null); // money null: a pre-money-model Order
    }

    // ---------- the two snapshot shapes ----------

    @Test
    void the_valid_shapes_round_trip_through_the_order_and_its_document() {
        for (OrderBenefitSnapshot snapshot : List.of(applied(),
                noBenefit(BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP),
                noBenefit(BenefitEvaluation.NoBenefitReason.NO_RULE),
                noBenefit(BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE))) {
            for (OrderStatus status : OrderStatus.values()) {
                Order order = order(status, snapshot);
                Document doc = OrderRepository.toDocument(order);

                assertThat(OrderRepository.toOrder(doc)).as("%s/%s", status, snapshot).isEqualTo(order);
                assertThat(OrderRepository.toOrder(doc).benefitSnapshot()).isEqualTo(snapshot);
            }
        }
    }

    @Test
    void a_legacy_order_without_a_snapshot_reconstructs_with_no_snapshot_and_that_is_not_no_benefit() {
        Order legacy = order(OrderStatus.CONFIRMED, null);
        Document doc = OrderRepository.toDocument(legacy);

        assertThat(doc.containsKey("benefits")).as("never written as null/placeholder").isFalse();
        Order reconstructed = OrderRepository.toOrder(doc);
        assertThat(reconstructed.benefitSnapshot()).isNull();
        assertThat(reconstructed).isEqualTo(legacy);
    }

    @Test
    void the_persisted_shapes_use_conditional_presence_with_no_placeholders() {
        Document none = (Document) OrderRepository.toDocument(
                order(OrderStatus.CREATED, noBenefit(BenefitEvaluation.NoBenefitReason.NO_RULE))).get("benefits");
        assertThat(none.keySet()).containsExactlyInAnyOrder("outcome", "eligibleSubtotalPaise", "noBenefitReason");
        assertThat(none.getString("outcome")).isEqualTo("NO_BENEFIT");
        assertThat(none.getString("noBenefitReason")).isEqualTo("NO_RULE");

        Document yes = (Document) OrderRepository.toDocument(order(OrderStatus.CREATED, applied())).get("benefits");
        assertThat(yes.keySet()).containsExactlyInAnyOrder("outcome", "eligibleSubtotalPaise", "discountPaise",
                "discountBps", "membershipId", "planId", "planVersion");
        assertThat(yes.getString("outcome")).isEqualTo("APPLIED");
        assertThat(yes.get("discountPaise")).isEqualTo(500L);
        assertThat(yes.get("discountBps")).isEqualTo(500);
        assertThat(yes.get("planVersion")).isEqualTo(1);
    }

    @Test
    void the_snapshot_is_copied_exactly_from_the_benefits_result() {
        BenefitEvaluation.Applied result = new BenefitEvaluation.Applied(MembershipId.generate(), PLAN, 3,
                Money.ofInrPaise(123_457), new DiscountBps(500).applyTo(Money.ofInrPaise(123_457)),
                new DiscountBps(500));

        OrderBenefitSnapshot.Applied snapshot = (OrderBenefitSnapshot.Applied)
                OrderBenefitSnapshot.from(result, 123_457);

        assertThat(snapshot.eligibleSubtotalPaise()).isEqualTo(123_457);
        assertThat(snapshot.discountPaise()).isEqualTo(6_172);
        assertThat(snapshot.discountBps()).isEqualTo(500);
        assertThat(snapshot.membershipId()).isEqualTo(result.membershipId().value());
        assertThat(snapshot.planId()).isEqualTo(PLAN);
        assertThat(snapshot.planVersion()).isEqualTo(3);

        for (BenefitEvaluation.NoBenefitReason reason : BenefitEvaluation.NoBenefitReason.values()) {
            assertThat(OrderBenefitSnapshot.from(new BenefitEvaluation.NoBenefit(reason), SUBTOTAL))
                    .isEqualTo(new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, reason));
        }
    }

    @Test
    void a_benefits_result_for_a_different_subtotal_is_rejected_not_copied() {
        BenefitEvaluation.Applied result = new BenefitEvaluation.Applied(MembershipId.generate(), PLAN, 1,
                Money.ofInrPaise(20_000), Money.ofInrPaise(1_000), new DiscountBps(500));

        assertThatThrownBy(() -> OrderBenefitSnapshot.from(result, SUBTOTAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- value invariants ----------

    @Test
    void the_applied_snapshot_enforces_its_invariants() {
        assertThatThrownBy(() -> new OrderBenefitSnapshot.Applied(SUBTOTAL, 0, 500, MBR, PLAN, 1)).as("zero discount")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBenefitSnapshot.Applied(SUBTOTAL, SUBTOTAL + 1, 500, MBR, PLAN, 1))
                .as("discount > subtotal").isInstanceOf(IllegalArgumentException.class);
        assertThat(new OrderBenefitSnapshot.Applied(SUBTOTAL, SUBTOTAL, 10_000, MBR, PLAN, 1)).as("100% is valid")
                .isNotNull();
        assertThatThrownBy(() -> new OrderBenefitSnapshot.Applied(SUBTOTAL, 500, 0, MBR, PLAN, 1)).as("bps 0")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBenefitSnapshot.Applied(SUBTOTAL, 500, 10_001, MBR, PLAN, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBenefitSnapshot.Applied(SUBTOTAL, 500, 500, null, PLAN, 1))
                .as("membershipId").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBenefitSnapshot.Applied(SUBTOTAL, 500, 500, " ", PLAN, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBenefitSnapshot.Applied(SUBTOTAL, 500, 500, MBR, null, 1)).as("planId")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBenefitSnapshot.Applied(SUBTOTAL, 500, 500, MBR, PLAN, 0))
                .as("planVersion < 1").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, null)).as("reason required")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBenefitSnapshot.NoBenefit(-1, BenefitEvaluation.NoBenefitReason.NO_RULE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_order_rejects_a_snapshot_about_a_different_subtotal() {
        assertThatThrownBy(() -> order(OrderStatus.CREATED,
                new OrderBenefitSnapshot.NoBenefit(SUBTOTAL + 1, BenefitEvaluation.NoBenefitReason.NO_RULE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eligibleSubtotalPaise");
        assertThatThrownBy(() -> order(OrderStatus.CONFIRMED,
                new OrderBenefitSnapshot.Applied(SUBTOTAL - 1, 500, 500, MBR, PLAN, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- strict reconstruction: every malformed snapshot fails loud ----------

    private static void corrupt(Order base, Consumer<Document> mutation) {
        Document doc = OrderRepository.toDocument(base);
        mutation.accept((Document) doc.get("benefits"));
        assertThatThrownBy(() -> OrderRepository.toOrder(doc))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class, ArithmeticException.class);
    }

    private static void corruptDocument(Order base, Consumer<Document> mutation) {
        Document doc = OrderRepository.toDocument(base);
        mutation.accept(doc);
        assertThatThrownBy(() -> OrderRepository.toOrder(doc))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class, ArithmeticException.class);
    }

    @Test
    void every_malformed_no_benefit_snapshot_fails_reconstruction() {
        Order base = order(OrderStatus.CONFIRMED, noBenefit(BenefitEvaluation.NoBenefitReason.NO_RULE));
        corrupt(base, b -> b.remove("noBenefitReason"));                       // missing reason
        corrupt(base, b -> b.put("discountPaise", 0L));                        // forbidden applied field (even zero)
        corrupt(base, b -> b.put("membershipId", MBR));                        // forbidden applied fields
        corrupt(base, b -> { b.put("planId", PLAN); b.put("planVersion", 1); });
        corrupt(base, b -> b.put("discountBps", 0));
        corrupt(base, b -> b.put("noBenefitReason", "SOMETHING_ELSE"));        // unknown reason
        corrupt(base, b -> b.put("noBenefitReason", 7));                       // wrong type
        corrupt(base, b -> b.put("noBenefitReason", ""));
        corrupt(base, b -> b.put("outcome", "WHATEVER"));                      // unknown outcome
        corrupt(base, b -> b.remove("outcome"));
        corrupt(base, b -> b.put("outcome", 1));
        corrupt(base, b -> b.remove("eligibleSubtotalPaise"));
        corrupt(base, b -> b.put("eligibleSubtotalPaise", "10000"));           // wrong type
        corrupt(base, b -> b.put("eligibleSubtotalPaise", 10_000.0));          // a double is not an integer amount
        corrupt(base, b -> b.put("eligibleSubtotalPaise", SUBTOTAL + 1));      // != the order subtotal
        corrupt(base, b -> b.put("eligibleSubtotalPaise", -5L));
        corrupt(base, b -> b.put("unexpected", "x"));                          // foreign field
    }

    @Test
    void every_malformed_applied_snapshot_fails_reconstruction() {
        Order base = order(OrderStatus.CONFIRMED, applied());
        corrupt(base, b -> b.remove("discountPaise"));                         // missing applied fields
        corrupt(base, b -> b.remove("discountBps"));
        corrupt(base, b -> b.remove("membershipId"));
        corrupt(base, b -> b.remove("planId"));
        corrupt(base, b -> b.remove("planVersion"));
        corrupt(base, b -> b.put("discountPaise", 0L));                        // zero discount
        corrupt(base, b -> b.put("discountPaise", SUBTOTAL + 1));              // discount > subtotal
        corrupt(base, b -> b.put("discountPaise", -1L));
        corrupt(base, b -> b.put("discountPaise", "500"));                     // wrong types
        corrupt(base, b -> b.put("discountBps", 0));                           // bad bps
        corrupt(base, b -> b.put("discountBps", 10_001));
        corrupt(base, b -> b.put("discountBps", "500"));
        corrupt(base, b -> b.put("discountBps", 500.5));
        corrupt(base, b -> b.put("membershipId", ""));
        corrupt(base, b -> b.put("membershipId", 12));
        corrupt(base, b -> b.put("planId", null));
        corrupt(base, b -> b.put("planVersion", 0));                           // planVersion < 1
        corrupt(base, b -> b.put("planVersion", "1"));
        corrupt(base, b -> b.put("noBenefitReason", "NO_RULE"));               // foreign field
        corrupt(base, b -> b.put("eligibleSubtotalPaise", SUBTOTAL - 1));      // != the order subtotal
        corrupt(base, b -> b.put("eligibleSubtotalPaise", 99L));
        corrupt(base, b -> b.put("extra", true));
    }

    @Test
    void a_snapshot_field_that_is_not_a_document_fails_reconstruction() {
        Order base = order(OrderStatus.CONFIRMED, applied());
        corruptDocument(base, d -> d.put("benefits", null));                   // an explicit null is NOT "absent"
        corruptDocument(base, d -> d.put("benefits", "NO_BENEFIT"));
        corruptDocument(base, d -> d.put("benefits", 1));
        corruptDocument(base, d -> d.put("benefits", List.of()));
        corruptDocument(base, d -> d.put("benefits", new Document()));         // empty
    }

    @Test
    void the_public_order_dto_is_unchanged_and_exposes_no_benefits_or_payable_field() {
        assertThat(java.util.Arrays.stream(CustomerOrderDto.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("orderId", "status", "paymentMethod", "paymentCondition", "items", "itemCount",
                        "subtotalPaise", "currency", "deliveryAddress", "createdAt", "confirmedAt", "requestId");
    }

    @Test
    void a_malformed_snapshot_is_never_dropped_into_a_legacy_order() {
        Order base = order(OrderStatus.CONFIRMED, applied());
        Document doc = OrderRepository.toDocument(base);
        ((Document) doc.get("benefits")).remove("membershipId");

        assertThatThrownBy(() -> OrderRepository.toOrder(doc)).isInstanceOf(IllegalStateException.class);
    }
}
