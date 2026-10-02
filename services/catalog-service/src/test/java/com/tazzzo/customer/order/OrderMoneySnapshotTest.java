package com.tazzzo.customer.order;

import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.customer.address.AddressId;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.inventory.InventoryReservationId;
import com.tazzzo.membership.MembershipId;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The V1 Order money snapshot: formula, invariants, persisted shape, strict reconstruction and legacy absence. */
class OrderMoneySnapshotTest {

    // TEST FIXTURES only (never launch policy)
    private static final long SUBTOTAL = 10_000;
    private static final Instant T = Instant.parse("2026-06-01T00:00:00Z");
    private static final String MBR = MembershipId.generate().value();

    private static OrderBenefitSnapshot.Applied applied(long discount) {
        return new OrderBenefitSnapshot.Applied(SUBTOTAL, discount, 500, MBR, "TAZZZO_PLUS_MONTHLY", 1);
    }

    private static OrderBenefitSnapshot.NoBenefit none() {
        return new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, BenefitEvaluation.NoBenefitReason.NO_RULE);
    }

    private static Order order(OrderBenefitSnapshot benefits, OrderMoneySnapshot money) {
        OrderAddressSnapshot address = new OrderAddressSnapshot("HOME", "Test Recipient", "9999999999",
                "123 Test Street", "Near Landmark", "Landmark", "Bengaluru", "Karnataka", "560001", 12.97, 77.59);
        List<OrderLine> lines = List.of(new OrderLine("TZP-1", "Widget", "BR-1", 2, 5_000, SUBTOTAL));
        return new Order(OrderId.generate(), "cus_1", CheckoutQuoteId.generate().value(), OrderStatus.CONFIRMED,
                PaymentMethod.COD, 2L, AddressId.generate().value(), 1L, address, lines, 2, SUBTOTAL, "INR",
                InventoryReservationId.generate().value(), ConfirmedPaymentCondition.COD_DUE, T, T, T, benefits, money);
    }

    // ---------- the V1 formula ----------

    @Test
    void payable_is_the_merchandise_subtotal_minus_the_benefit_discount() {
        assertThat(OrderMoneySnapshot.from(10_000, 0).payablePaise()).isEqualTo(10_000);
        assertThat(OrderMoneySnapshot.from(10_000, 500).payablePaise()).isEqualTo(9_500);
        assertThat(OrderMoneySnapshot.from(10_000, 10_000).payablePaise()).as("zero payable is valid").isZero();
        assertThat(OrderMoneySnapshot.from(0, 0).payablePaise()).isZero();
        assertThat(OrderMoneySnapshot.from(Long.MAX_VALUE, 1).payablePaise()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @Test
    void the_snapshot_rejects_negative_amounts_and_a_discount_above_the_subtotal() {
        assertThatThrownBy(() -> OrderMoneySnapshot.from(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderMoneySnapshot.from(10_000, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderMoneySnapshot.from(10_000, 10_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderMoneySnapshot.from(0, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payable_is_derived_never_a_caller_supplied_component() {
        assertThat(Arrays.stream(OrderMoneySnapshot.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("merchandiseSubtotalPaise", "benefitDiscountPaise");
        assertThat(Arrays.stream(OrderMoneySnapshot.class.getRecordComponents()).map(c -> c.getName()).toList())
                .doesNotContain("payablePaise", "taxPaise", "deliveryFeePaise", "platformFeePaise", "couponDiscountPaise",
                        "coinRedemptionPaise", "walletAmountPaise");
    }

    // ---------- persisted shape, legacy absence ----------

    @Test
    void the_valid_orders_round_trip_through_their_document_with_the_explicit_frozen_payable() {
        for (Order o : List.of(order(none(), OrderMoneySnapshot.from(SUBTOTAL, 0)),
                order(applied(500), OrderMoneySnapshot.from(SUBTOTAL, 500)),
                order(applied(SUBTOTAL), OrderMoneySnapshot.from(SUBTOTAL, SUBTOTAL)))) {
            Document doc = OrderRepository.toDocument(o);
            Document money = (Document) doc.get("money");

            assertThat(money.keySet()).containsExactlyInAnyOrder("merchandiseSubtotalPaise", "benefitDiscountPaise",
                    "payablePaise");
            assertThat(money.get("payablePaise")).isEqualTo(o.moneySnapshot().payablePaise());
            assertThat(OrderRepository.toOrder(doc)).isEqualTo(o);
            assertThat(OrderRepository.toOrder(doc).moneySnapshot()).isEqualTo(o.moneySnapshot());
        }
    }

    @Test
    void a_legacy_order_without_a_money_snapshot_reconstructs_with_none_and_no_payable_is_synthesized() {
        for (OrderBenefitSnapshot benefits : java.util.Arrays.asList(null, none(), applied(500))) {
            Order legacy = order(benefits, null);
            Document doc = OrderRepository.toDocument(legacy);

            assertThat(doc.containsKey("money")).as("never written as null/placeholder").isFalse();
            Order reconstructed = OrderRepository.toOrder(doc);
            assertThat(reconstructed.moneySnapshot()).as("absent is NOT payable=subtotal and NOT 0").isNull();
            assertThat(reconstructed).isEqualTo(legacy);
        }
    }

    // ---------- Order-level consistency ----------

    @Test
    void an_order_requires_its_money_to_match_its_subtotal_and_its_authoritative_benefit_discount() {
        assertThatThrownBy(() -> order(none(), OrderMoneySnapshot.from(SUBTOTAL + 1, 0)))
                .as("money subtotal != order subtotal").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subtotal");
        assertThatThrownBy(() -> order(applied(500), OrderMoneySnapshot.from(SUBTOTAL, 499)))
                .as("money discount != applied benefit discount").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discount");
        assertThatThrownBy(() -> order(applied(500), OrderMoneySnapshot.from(SUBTOTAL, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order(none(), OrderMoneySnapshot.from(SUBTOTAL, 500)))
                .as("no benefit => discount 0").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order(null, OrderMoneySnapshot.from(SUBTOTAL, 0)))
                .as("money without the benefit snapshot it derives from").isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- strict reconstruction ----------

    private static void corruptMoney(Order base, Consumer<Document> mutation) {
        Document doc = OrderRepository.toDocument(base);
        mutation.accept((Document) doc.get("money"));
        assertThatThrownBy(() -> OrderRepository.toOrder(doc))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class, ArithmeticException.class);
    }

    private static void corruptDoc(Order base, Consumer<Document> mutation) {
        Document doc = OrderRepository.toDocument(base);
        mutation.accept(doc);
        assertThatThrownBy(() -> OrderRepository.toOrder(doc))
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class, ArithmeticException.class);
    }

    @Test
    void every_malformed_persisted_money_snapshot_fails_reconstruction() {
        Order base = order(applied(500), OrderMoneySnapshot.from(SUBTOTAL, 500));
        corruptMoney(base, m -> m.remove("merchandiseSubtotalPaise"));           // missing fields
        corruptMoney(base, m -> m.remove("benefitDiscountPaise"));
        corruptMoney(base, m -> m.remove("payablePaise"));
        corruptMoney(base, m -> m.put("taxPaise", 0L));                          // foreign fields: no placeholders
        corruptMoney(base, m -> m.put("deliveryFeePaise", 0L));
        corruptMoney(base, m -> m.put("benefitDiscountPaise", "500"));           // wrong types
        corruptMoney(base, m -> m.put("merchandiseSubtotalPaise", 10_000.0));
        corruptMoney(base, m -> m.put("payablePaise", "9500"));
        corruptMoney(base, m -> m.put("payablePaise", null));
        corruptMoney(base, m -> m.put("benefitDiscountPaise", -500L));           // negative discount
        corruptMoney(base, m -> m.put("benefitDiscountPaise", SUBTOTAL + 1));    // discount > subtotal
        corruptMoney(base, m -> m.put("payablePaise", 9_501L));                  // payable formula mismatch
        corruptMoney(base, m -> m.put("payablePaise", SUBTOTAL));                //   (the V0 "payable = subtotal")
        corruptMoney(base, m -> m.put("payablePaise", 0L));
        corruptMoney(base, m -> m.put("payablePaise", -1L));
        corruptMoney(base, m -> { m.put("merchandiseSubtotalPaise", 20_000L); m.put("payablePaise", 19_500L); }); // != order
        corruptMoney(base, m -> { m.put("benefitDiscountPaise", 400L); m.put("payablePaise", 9_600L); }); // != benefit snapshot
    }

    @Test
    void a_money_field_that_is_not_a_document_or_is_orphaned_from_its_benefit_snapshot_fails_reconstruction() {
        Order base = order(applied(500), OrderMoneySnapshot.from(SUBTOTAL, 500));
        corruptDoc(base, d -> d.put("money", null));                             // an explicit null is NOT "absent"
        corruptDoc(base, d -> d.put("money", "9500"));
        corruptDoc(base, d -> d.put("money", 1));
        corruptDoc(base, d -> d.put("money", List.of()));
        corruptDoc(base, d -> d.put("money", new Document()));                   // empty
        corruptDoc(base, d -> d.remove("benefits"));                             // money without its benefit snapshot
    }

    @Test
    void the_public_order_dto_exposes_no_money_snapshot_or_payable_field() {
        assertThat(Arrays.stream(CustomerOrderDto.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("orderId", "status", "paymentMethod", "paymentCondition", "items", "itemCount",
                        "subtotalPaise", "currency", "deliveryAddress", "createdAt", "confirmedAt", "requestId");
    }
}
