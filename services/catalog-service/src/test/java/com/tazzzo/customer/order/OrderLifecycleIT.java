package com.tazzzo.customer.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.migration.IndexCatalog;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Customer order history and cancellation: atomic with stock and slot, exactly-once restock, idempotent, never leaky. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractOrderSlotIT.BigAddressLimit.class},
        properties = "tazzzo.orders.customer-cancel-window-seconds=600")
class OrderLifecycleIT extends AbstractOrderSlotIT {

    @BeforeAll
    void managedIndex() {
        IndexCatalog.ORDER_BY_CUSTOMER_RECENT_SPEC.create(db);   // migration-only in production; the IT applies it
    }

    private ResponseEntity<JsonNode> cancel(String token, String orderId, Object body) {
        return call(HttpMethod.POST, "/v1/customer/orders/" + orderId + "/cancel", token, null, body);
    }

    private ResponseEntity<JsonNode> cancel(Shopper s, String orderId) {
        return cancel(s.token(), orderId, Map.of("reason", "CHANGED_MIND"));
    }

    private String placeWithSlot(Shopper s) {
        openWindow(s.areaId(), "early", 5);
        ResponseEntity<JsonNode> r = place(s.token(), body(s, "early~" + tomorrow()));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return r.getBody().get("orderId").asText();
    }

    private Document usage(Shopper s) {
        return db.getCollection("delivery_slot_usage").find(new Document("_id", s.areaId() + "|early|" + tomorrow())).first();
    }

    @Test
    void cancelling_returns_the_stock_and_the_slot_in_one_transaction_and_records_who_and_why() {
        Shopper s = shopper();
        String orderId = placeWithSlot(s);
        assertThat(onHand(s.sku())).isEqualTo(8);
        assertThat(usage(s).getInteger("used")).isEqualTo(1);

        ResponseEntity<JsonNode> r = cancel(s, orderId);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        JsonNode b = r.getBody();
        assertThat(b.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(b.get("cancelledAt").asText()).isNotBlank();
        assertThat(b.has("paymentCondition")).as("nothing is due on a cancelled order").isFalse();
        assertThat(b.get("deliverySlot").get("slotId").asText()).isEqualTo("early~" + tomorrow());

        assertThat(onHand(s.sku())).as("stock returned").isEqualTo(10);
        assertThat(usage(s).getInteger("used")).as("slot returned").isZero();
        assertThat(usage(s).getList("holds", String.class)).isEmpty();
        Document stored = db.getCollection("orders").find(new Document("_id", orderId)).first();
        assertThat(stored.getString("status")).isEqualTo("CANCELLED");
        assertThat(stored.getLong("version")).isEqualTo(3L);
        assertThat(stored.get("cancellation", Document.class).getString("cancelledBy")).isEqualTo("CUSTOMER");
        assertThat(stored.get("cancellation", Document.class).getString("reasonCode")).isEqualTo("CHANGED_MIND");
        Document reservation = db.getCollection("inventory_reservations").find(new Document("orderId", orderId)).first();
        assertThat(reservation.getString("status")).as("history is not rewritten").isEqualTo("CONSUMED");
        assertThat(reservation.containsKey("restockedAt")).isTrue();
        Document ev = db.getCollection("domain_events").find(new Document("aggregate_id", orderId)).first();
        assertThat(ev.getString("type")).isEqualTo("ORDER_CANCELLED");
        assertThat(ev.toJson()).doesNotContain(s.token()).doesNotContain("Ravi");

        JsonNode read = call(HttpMethod.GET, "/v1/customer/orders/" + orderId, s.token(), null, null).getBody();
        assertThat(read.get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void a_second_cancel_is_an_idempotent_200_and_never_restocks_twice() {
        Shopper s = shopper();
        String orderId = placeWithSlot(s);
        assertThat(cancel(s, orderId).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<JsonNode> again = cancel(s, orderId);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(onHand(s.sku())).as("restocked exactly once").isEqualTo(10);
        assertThat(usage(s).getInteger("used")).isZero();
        assertThat(db.getCollection("domain_events").countDocuments(new Document("aggregate_id", orderId))).isEqualTo(1);
    }

    @Test
    void concurrent_cancels_restock_exactly_once() throws Exception {
        Shopper s = shopper();
        String orderId = placeWithSlot(s);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return cancel(s, orderId).getStatusCode().value();
            }));
        }
        go.countDown();
        for (var f : futures) {
            assertThat(f.get()).isIn(200, 409);
        }
        pool.shutdown();
        assertThat(onHand(s.sku())).as("never 10 + extra").isEqualTo(10);
        assertThat(usage(s).getInteger("used")).isZero();
        assertThat(db.getCollection("orders").find(new Document("_id", orderId)).first().getString("status")).isEqualTo("CANCELLED");
    }

    @Test
    void an_order_without_a_slot_cancels_too() {
        Shopper s = shopper();
        String orderId = place(s.token(), body(s, null)).getBody().get("orderId").asText();
        assertThat(cancel(s, orderId).getStatusCode().value()).isEqualTo(200);
        assertThat(onHand(s.sku())).isEqualTo(10);
    }

    @Test
    void the_window_is_enforced_and_a_refused_cancel_changes_nothing() {
        Shopper s = shopper();
        String orderId = placeWithSlot(s);
        Date old = Date.from(Instant.now().minusSeconds(3600));
        db.getCollection("orders").updateOne(new Document("_id", orderId),
                new Document("$set", new Document("createdAt", old).append("confirmedAt", old).append("updatedAt", old)));
        ResponseEntity<JsonNode> r = cancel(s, orderId);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().get("code").asText()).isEqualTo("CANCELLATION_WINDOW_CLOSED");
        assertThat(onHand(s.sku())).isEqualTo(8);
        assertThat(usage(s).getInteger("used")).isEqualTo(1);
        assertThat(db.getCollection("orders").find(new Document("_id", orderId)).first().getString("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void requests_are_validated_and_ownership_is_enforced() {
        Shopper s = shopper();
        Shopper other = shopper();
        String orderId = placeWithSlot(s);
        for (Object bad : new Object[]{Map.of(), Map.of("reason", "BOGUS"), Map.of("reason", 7), Map.of("reason", "changed_mind"), "x"}) {
            ResponseEntity<JsonNode> r = cancel(s.token(), orderId, bad);
            assertThat(r.getStatusCode().value()).as(String.valueOf(bad)).isEqualTo(400);
            assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(call(HttpMethod.POST, "/v1/customer/orders/" + orderId + "/cancel", s.token(), null, null).getStatusCode().value()).isEqualTo(400);
        assertThat(cancel(other, orderId).getStatusCode().value()).as("another customer's order is a 404").isEqualTo(404);
        assertThat(cancel(s, "ORD_doesnotexist").getStatusCode().value()).isEqualTo(404);
        assertThat(cancel(s, "not-an-id").getStatusCode().value()).isEqualTo(404);
        assertThat(cancel(null, orderId, Map.of("reason", "OTHER")).getStatusCode().value()).isEqualTo(401);
        assertThat(onHand(s.sku())).as("nothing happened").isEqualTo(8);
        assertThat(db.getCollection("orders").find(new Document("_id", orderId)).first().getString("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void re_sending_the_placement_of_a_cancelled_order_returns_it_and_never_reorders() {
        Shopper s = shopper();
        String orderId = placeWithSlot(s);
        assertThat(cancel(s, orderId).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<JsonNode> again = place(s.token(), body(s, "early~" + tomorrow()));
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().get("orderId").asText()).isEqualTo(orderId);
        assertThat(again.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(onHand(s.sku())).isEqualTo(10);
        assertThat(db.getCollection("orders").countDocuments(new Document("customerId", s.customerId()))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ list

    private void clone(Document template, String id, Instant createdAt, String status, String customerId) {
        Document d = new Document(template);
        d.put("_id", id);
        d.put("quoteId", "CHKQ_" + id.substring(4) + "0000000000");
        d.put("customerId", customerId);
        d.put("status", status);
        d.put("createdAt", Date.from(createdAt));
        d.put("updatedAt", Date.from(createdAt));
        d.put("confirmedAt", Date.from(createdAt));
        if (status.equals("CANCELLED")) {
            d.put("version", 3L);
            d.put("cancellation", new Document("cancelledAt", Date.from(createdAt)).append("cancelledBy", "CUSTOMER").append("reasonCode", "OTHER"));
        }
        if (status.equals("CREATED")) {
            d.put("version", 1L);
            d.remove("confirmedAt");
            d.remove("confirmedPaymentCondition");
        }
        db.getCollection("orders").insertOne(d);
    }

    @Test
    void the_history_is_newest_first_keyset_paged_own_orders_only_and_index_served() {
        Shopper s = shopper();
        String first = place(s.token(), body(s, null)).getBody().get("orderId").asText();
        Document template = db.getCollection("orders").find(new Document("_id", first)).first();
        Instant base = Instant.now().minusSeconds(100);
        clone(template, "ORD_hist0001aaaa", base.plusSeconds(10), "CONFIRMED", s.customerId());
        clone(template, "ORD_hist0002aaaa", base.plusSeconds(20), "CANCELLED", s.customerId());
        clone(template, "ORD_hist0003aaaa", base.plusSeconds(20), "CONFIRMED", s.customerId());   // same instant: _id breaks the tie
        clone(template, "ORD_hist0004aaaa", base.plusSeconds(30), "CONFIRMED", s.customerId());
        clone(template, "ORD_hist0005aaaa", base.plusSeconds(40), "CREATED", s.customerId());     // internal: never listed
        clone(template, "ORD_hist0006aaaa", base.plusSeconds(50), "CONFIRMED", "CUS_someoneelse00000");

        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            // page size 3 puts the page boundary BETWEEN the two orders created at the same instant (the _id tie-break)
            ResponseEntity<JsonNode> r = call(HttpMethod.GET, "/v1/customer/orders?page_size=3" + (cursor == null ? "" : "&cursor=" + cursor), s.token(), null, null);
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(r.getHeaders().getCacheControl()).contains("no-store");
            r.getBody().get("items").forEach(i -> ids.add(i.get("orderId").asText()));
            cursor = r.getBody().hasNonNull("nextCursor") ? r.getBody().get("nextCursor").asText() : null;
            assertThat(++pages).isLessThanOrEqualTo(6);
        } while (cursor != null);
        assertThat(pages).isEqualTo(2);
        assertThat(ids).as("the real order was placed now: the newest").containsExactly(first, "ORD_hist0004aaaa", "ORD_hist0003aaaa", "ORD_hist0002aaaa", "ORD_hist0001aaaa");

        JsonNode page = call(HttpMethod.GET, "/v1/customer/orders", s.token(), null, null).getBody();
        JsonNode cancelled = null;
        for (JsonNode i : page.get("items")) if (i.get("orderId").asText().equals("ORD_hist0002aaaa")) cancelled = i;
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.has("cancelledAt")).isTrue();
        String json = page.toString().toLowerCase();
        assertThat(json).doesNotContain("recipient").doesNotContain("addressline").doesNotContain("items\":[{\"sku").doesNotContain("customerid");

        Document explain = db.getCollection("orders").find(new Document("customerId", s.customerId()).append("status", new Document("$in", List.of("CONFIRMED", "CANCELLED"))))
                .sort(new Document("createdAt", -1).append("_id", -1)).limit(3).explain();
        String winning = explain.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
        assertThat(winning).as("the WINNING plan walks the index in order").contains("order_by_customer_recent")
                .doesNotContain("\"SORT\"").doesNotContain("COLLSCAN");
    }

    @org.springframework.beans.factory.annotation.Autowired OrderRepository orderRepository;
    @org.springframework.beans.factory.annotation.Autowired com.tazzzo.catalog.tx.Tx txBean;

    private boolean cas(String id, String customerId, OrderCancellation c) {
        Boolean applied = txBean.call(sess -> orderRepository.cancelConfirmed(sess, id, customerId, c));
        return Boolean.TRUE.equals(applied);
    }

    @Test
    void the_cancel_cas_only_applies_to_a_confirmed_version_2_order() {
        Shopper s = shopper();
        String id = place(s.token(), body(s, null)).getBody().get("orderId").asText();
        OrderCancellation c = new OrderCancellation(Instant.now(), OrderCancellation.CancelledBy.CUSTOMER, "OTHER");
        db.getCollection("orders").updateOne(new Document("_id", id), new Document("$set", new Document("version", 5L)));
        assertThat(cas(id, s.customerId(), c)).as("wrong version").isFalse();
        db.getCollection("orders").updateOne(new Document("_id", id), new Document("$set", new Document("version", 2L).append("status", "CREATED")));
        assertThat(cas(id, s.customerId(), c)).as("wrong status").isFalse();
        db.getCollection("orders").updateOne(new Document("_id", id), new Document("$set", new Document("status", "CONFIRMED")));
        assertThat(cas(id, "CUS_someoneelse00000", c)).as("wrong owner").isFalse();
        assertThat(cas(id, s.customerId(), c)).isTrue();
        assertThat(cas(id, s.customerId(), c)).as("only once").isFalse();
    }

    @Test
    void the_history_request_grammar_is_closed() {
        Shopper s = shopper();
        place(s.token(), body(s, null));
        for (String q : new String[]{"?page_size=0", "?page_size=51", "?page_size=abc", "?page_size=-1", "?cursor=not-a-cursor", "?cursor=" + "A".repeat(200),
                "?sort=asc", "?customerId=CUS_x", "?page_size=2&page_size=3"}) {
            ResponseEntity<JsonNode> r = call(HttpMethod.GET, "/v1/customer/orders" + q, s.token(), null, null);
            assertThat(r.getStatusCode().value()).as(q).isEqualTo(400);
        }
        assertThat(call(HttpMethod.GET, "/v1/customer/orders", null, null, null).getStatusCode().value()).isEqualTo(401);
        // a well-formed cursor of someone else's position still only walks the caller's own orders
        String forged = OrderLifecycleService.encodeCursor(Instant.now().plusSeconds(10), "ORD_someoneelse00000");
        ResponseEntity<JsonNode> r = call(HttpMethod.GET, "/v1/customer/orders?cursor=" + forged, s.token(), null, null);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("items")).hasSize(1);
    }
}
