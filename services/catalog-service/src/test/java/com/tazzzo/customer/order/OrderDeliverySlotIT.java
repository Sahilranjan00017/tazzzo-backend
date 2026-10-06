package com.tazzzo.customer.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.CatalogApplication;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** COD placement with an optional delivery slot: atomic with the order, idempotent, safe to fail, never leaky. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractOrderSlotIT.BigAddressLimit.class})
class OrderDeliverySlotIT extends AbstractOrderSlotIT {

    private Document usage(Shopper s, String window) {
        return db.getCollection("delivery_slot_usage").find(new Document("_id", s.areaId() + "|" + window + "|" + tomorrow())).first();
    }

    private void assertNothingCommitted(Shopper s, long stockBefore) {
        assertThat(db.getCollection("orders").countDocuments(new Document("customerId", s.customerId()))).as("no order").isZero();
        assertThat(onHand(s.sku())).as("stock untouched").isEqualTo(stockBefore);
        assertThat(db.getCollection("customer_carts").find(new Document("_id", s.customerId())).first().getList("items", Document.class))
                .as("cart not cleared").isNotEmpty();
    }

    @Test
    void a_slot_is_reserved_atomically_with_the_order_and_shown_without_internal_detail() {
        Shopper s = shopper();
        openWindow(s.areaId(), "early", 5);
        String slotId = "early~" + tomorrow();
        ResponseEntity<JsonNode> r = place(s.token(), body(s, slotId));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        JsonNode slot = r.getBody().get("deliverySlot");
        assertThat(slot.get("slotId").asText()).isEqualTo(slotId);
        assertThat(slot.get("label").asText()).isEqualTo("Early early");
        assertThat(slot.get("startsAt").asText()).isNotBlank();
        String json = r.getBody().toString().toLowerCase();
        assertThat(json).doesNotContain("sa-slot").doesNotContain("capacity").doesNotContain("ful-slot").doesNotContain("windowid");

        String orderId = r.getBody().get("orderId").asText();
        Document stored = db.getCollection("orders").find(new Document("_id", orderId)).first();
        assertThat(stored.get("deliverySlot", Document.class).getString("serviceAreaId")).isEqualTo(s.areaId());
        Document u = usage(s, "early");
        assertThat(u.getInteger("used")).isEqualTo(1);
        assertThat(u.getList("holds", String.class)).containsExactly(orderId);
        assertThat(onHand(s.sku())).as("stock consumed").isEqualTo(8);

        JsonNode read = call(HttpMethod.GET, "/v1/customer/orders/" + orderId, s.token(), null, null).getBody();
        assertThat(read.get("deliverySlot").get("slotId").asText()).isEqualTo(slotId);
    }

    @Test
    void an_order_without_a_slot_is_unchanged_and_reserves_nothing() {
        Shopper s = shopper();
        openWindow(s.areaId(), "early", 5);
        ResponseEntity<JsonNode> r = place(s.token(), body(s, null));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().has("deliverySlot")).isFalse();
        assertThat(db.getCollection("orders").find(new Document("_id", r.getBody().get("orderId").asText())).first().containsKey("deliverySlot")).isFalse();
        assertThat(db.getCollection("delivery_slot_usage").countDocuments(new Document("_id", new Document("$regex", "^" + s.areaId())))).isZero();
    }

    @Test
    void a_replay_returns_the_same_order_and_never_takes_a_second_unit() {
        Shopper s = shopper();
        openWindow(s.areaId(), "early", 5);
        String slotId = "early~" + tomorrow();
        String first = place(s.token(), body(s, slotId)).getBody().get("orderId").asText();
        ResponseEntity<JsonNode> again = place(s.token(), body(s, slotId));
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().get("orderId").asText()).isEqualTo(first);
        assertThat(usage(s, "early").getInteger("used")).isEqualTo(1);
        assertThat(onHand(s.sku())).isEqualTo(8);
    }

    @Test
    void a_full_slot_is_409_and_the_whole_placement_rolls_back() {
        Shopper s = shopper();
        openWindow(s.areaId(), "early", 1);
        db.getCollection("delivery_slot_usage").insertOne(new Document("_id", s.areaId() + "|early|" + tomorrow())
                .append("used", 1).append("holds", List.of("SOMEONE-ELSE")).append("expire_at", new java.util.Date(System.currentTimeMillis() + 86_400_000L)));
        ResponseEntity<JsonNode> r = place(s.token(), body(s, "early~" + tomorrow()));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().get("code").asText()).isEqualTo("DELIVERY_SLOT_UNAVAILABLE");
        assertThat(r.getBody().toString()).doesNotContain("SOMEONE-ELSE").doesNotContain(s.areaId());
        assertNothingCommitted(s, 10);
        assertThat(usage(s, "early").getInteger("used")).isEqualTo(1);
        assertThat(db.getCollection("inventory_reservations").countDocuments(new Document("status", "RESERVED"))).as("no dangling reservation").isZero();
    }

    @Test
    void unknown_closed_or_out_of_horizon_slots_are_409_and_malformed_ones_are_400() {
        Shopper s = shopper();
        openWindow(s.areaId(), "early", 5);
        for (String slot : new String[]{"nope~" + tomorrow(), "early~" + tomorrow().plusDays(30), "early~" + tomorrow().minusDays(5)}) {
            ResponseEntity<JsonNode> r = place(s.token(), body(s, slot));
            assertThat(r.getStatusCode().value()).as(slot).isEqualTo(409);
            assertThat(r.getBody().get("code").asText()).isEqualTo("DELIVERY_SLOT_UNAVAILABLE");
        }
        for (Object bad : new Object[]{"bad", "early~2026-13-45x", "EARLY~" + tomorrow(), "", 7, List.of("early~" + tomorrow()), "early~" + tomorrow() + "|x"}) {
            Map<String, Object> b = body(s, null);
            b.put("deliverySlotId", bad);
            ResponseEntity<JsonNode> r = place(s.token(), b);
            assertThat(r.getStatusCode().value()).as(String.valueOf(bad)).isEqualTo(400);
            assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        Map<String, Object> impossible = body(s, "early~2026-02-30");
        assertThat(place(s.token(), impossible).getStatusCode().value()).isEqualTo(400);
        assertNothingCommitted(s, 10);
    }

    @Test
    void an_inactive_window_or_an_area_with_no_windows_refuses_the_slot() {
        Shopper s = shopper();
        assertThat(place(s.token(), body(s, "early~" + tomorrow())).getStatusCode().value()).as("no windows at all").isEqualTo(409);
        openWindow(s.areaId(), "early", 5);
        slots.setActive(com.tazzzo.common.audit.TestActors.TEST, s.areaId(), "early", 1, false);
        assertThat(place(s.token(), body(s, "early~" + tomorrow())).getStatusCode().value()).as("deactivated").isEqualTo(409);
        assertNothingCommitted(s, 10);
        // the same shopper can still order without a slot
        assertThat(place(s.token(), body(s, null)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void concurrent_orders_for_the_last_unit_never_oversell_the_slot() throws Exception {
        List<Shopper> shoppers = new ArrayList<>();
        Shopper first = shopper();
        openWindow(first.areaId(), "early", 1);
        shoppers.add(first);
        String slotId = "early~" + tomorrow();
        // a second shopper in the SAME area/PIN: reuse the area by pointing a new address at the first PIN
        Shopper second = shopperInArea(first);
        shoppers.add(second);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
        for (Shopper sh : shoppers) {
            futures.add(pool.submit(() -> {
                go.await();
                return place(sh.token(), body(sh, slotId)).getStatusCode().value();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (var f : futures) statuses.add(f.get());
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(usage(first, "early").getInteger("used")).isEqualTo(1);
    }

    /** A new customer + cart + quote whose address is in {@code other}'s PIN (so the same service area and slot windows). */
    private Shopper shopperInArea(Shopper other) {
        Shopper s = shopper();
        // move this shopper's PIN into the other's: rewrite the service-area doc for s's PIN to the other's area id
        db.getCollection("service_areas").updateOne(new Document("pincode", s.pin()), new Document("$set", new Document("service_area_id", other.areaId())));
        return new Shopper(s.token(), s.customerId(), s.sku(), s.addressId(), s.quoteId(), s.pin(), other.areaId());
    }
}
