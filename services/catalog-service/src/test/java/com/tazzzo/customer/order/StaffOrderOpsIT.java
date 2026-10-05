package com.tazzzo.customer.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.admin.auth.GoogleIdTokens;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.migration.IndexCatalog;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Staff order operations end to end: real COD orders, real staff OIDC tokens, the fulfilment state machine. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractOrderSlotIT.BigAddressLimit.class},
        properties = "tazzzo.orders.customer-cancel-window-seconds=600")
class StaffOrderOpsIT extends AbstractOrderSlotIT {

    static final String STAFF = "/api/v1/admin/orders";
    static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-staff-orders");
    static final HttpServer KEYS;
    static final String OPS = "110000000000000000041";
    static final String AGENT = "110000000000000000042";

    static {
        try {
            KEYS = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] body = TOKENS.publicJwks().toString(true).getBytes(StandardCharsets.UTF_8);
        KEYS.createContext("/certs", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        KEYS.start();
    }

    @AfterAll
    static void stopKeys() {
        KEYS.stop(0);
    }

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry r) {
        r.add("tazzzo.admin.oidc.issuer", () -> "https://accounts.google.com");
        r.add("tazzzo.admin.oidc.audience", () -> GoogleIdTokens.AUDIENCE);
        r.add("tazzzo.admin.oidc.hosted-domain", () -> GoogleIdTokens.DOMAIN);
        r.add("tazzzo.admin.oidc.credential-label", () -> GoogleIdTokens.LABEL);
        r.add("tazzzo.admin.oidc.jwks-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/certs");
        String[][] users = {{OPS, "ops@tazzzo.test", "order-ops"}, {AGENT, "agent@tazzzo.test", "support-agent"},
                {GoogleIdTokens.WRITER, GoogleIdTokens.WRITER_EMAIL_LABEL, "cms-writer"}};
        for (int i = 0; i < users.length; i++) {
            String k = "tazzzo.admin.users[" + i + "].";
            String[] u = users[i];
            r.add(k + "provider", () -> "google");
            r.add(k + "subject", () -> u[0]);
            r.add(k + "email", () -> u[1]);
            r.add(k + "roles", () -> u[2]);
            r.add(k + "enabled", () -> "true");
        }
    }

    @BeforeAll
    void queueIndexes() {
        IndexCatalog.STAFF_ORDER_QUEUE_SPECS.forEach(spec -> spec.create(db));
    }

    static String staff(String sub) {
        return TOKENS.token(sub, Instant.now());
    }

    private ResponseEntity<JsonNode> transition(String token, String orderId, String to, long version, String reason) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("to", to);
        b.put("expectedVersion", version);
        if (reason != null) b.put("reason", reason);
        return call(HttpMethod.POST, STAFF + "/" + orderId + "/transition", token, null, b);
    }

    private String placed(Shopper s, boolean withSlot) {
        if (withSlot) openWindow(s.areaId(), "early", 5);
        ResponseEntity<JsonNode> r = place(s.token(), body(s, withSlot ? "early~" + tomorrow() : null));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return r.getBody().get("orderId").asText();
    }

    private Document usage(Shopper s) {
        return db.getCollection("delivery_slot_usage").find(new Document("_id", s.areaId() + "|early|" + tomorrow())).first();
    }

    @Test
    void the_happy_path_confirmed_out_for_delivery_delivered_is_audited_and_visible_to_the_customer() {
        Shopper s = shopper();
        String id = placed(s, false);
        String ops = staff(OPS);
        JsonNode view = call(HttpMethod.GET, STAFF + "/" + id, ops, null, null).getBody();
        assertThat(view.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(view.get("customerId").asText()).isEqualTo(s.customerId());
        assertThat(view.get("deliveryAddress").get("postalCode").asText()).isEqualTo(s.pin());
        assertThat(view.get("version").asLong()).isEqualTo(2);

        JsonNode out = transition(ops, id, "OUT_FOR_DELIVERY", 2, null).getBody();
        assertThat(out.get("status").asText()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(out.get("outForDeliveryAt").asText()).isNotBlank();
        JsonNode done = transition(ops, id, "DELIVERED", 3, null).getBody();
        assertThat(done.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(done.get("version").asLong()).isEqualTo(4);

        JsonNode customerSees = call(HttpMethod.GET, "/v1/customer/orders/" + id, s.token(), null, null).getBody();
        assertThat(customerSees.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(customerSees.get("deliveredAt").asText()).isNotBlank();
        assertThat(customerSees.get("paymentCondition").asText()).isEqualTo("COD_DUE");
        assertThat(onHand(s.sku())).as("a delivered order keeps its stock consumed").isEqualTo(8);

        List<String> types = db.getCollection("domain_events").find(new Document("aggregate_id", id)).map(d -> d.getString("type")).into(new ArrayList<>());
        assertThat(types).containsExactly("ORDER_OUT_FOR_DELIVERY", "ORDER_DELIVERED");
        assertThat(db.getCollection("domain_events").find(new Document("aggregate_id", id)).into(new ArrayList<>()))
                .allSatisfy(e -> assertThat(e.get("actor", Document.class).getString("id")).isEqualTo("google:" + OPS));
    }

    @Test
    void illegal_transitions_stale_versions_and_bad_bodies_are_refused_without_side_effects() {
        Shopper s = shopper();
        String id = placed(s, false);
        String ops = staff(OPS);
        assertThat(transition(ops, id, "DELIVERED", 2, null).getBody().at("/error/code").asText()).as("must go out first").isEqualTo("INVALID_TRANSITION");
        assertThat(transition(ops, id, "OUT_FOR_DELIVERY", 7, null).getBody().at("/error/code").asText()).isEqualTo("STALE_VERSION");
        assertThat(transition(ops, id, "CONFIRMED", 2, null).getStatusCode().value()).isEqualTo(400);
        assertThat(transition(ops, id, "CREATED", 2, null).getStatusCode().value()).isEqualTo(400);
        assertThat(transition(ops, id, "SHIPPED", 2, null).getStatusCode().value()).isEqualTo(400);
        assertThat(transition(ops, id, "CANCELLED", 2, null).getStatusCode().value()).as("cancel needs a reason").isEqualTo(400);
        assertThat(transition(ops, id, "CANCELLED", 2, "BECAUSE").getStatusCode().value()).as("closed reason set").isEqualTo(400);
        assertThat(transition(ops, id, "OUT_FOR_DELIVERY", 2, "OTHER").getStatusCode().value()).as("a reason only on cancel").isEqualTo(400);
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/transition", ops, null, Map.of("to", "OUT_FOR_DELIVERY", "expectedVersion", 2, "actor", "x"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(transition(ops, "ORD_doesnotexist0", "OUT_FOR_DELIVERY", 2, null).getStatusCode().value()).isEqualTo(404);
        assertThat(db.getCollection("orders").find(new Document("_id", id)).first().getString("status")).isEqualTo("CONFIRMED");
        assertThat(db.getCollection("domain_events").countDocuments(new Document("aggregate_id", id))).isZero();

        transition(ops, id, "OUT_FOR_DELIVERY", 2, null);
        transition(ops, id, "DELIVERED", 3, null);
        assertThat(transition(ops, id, "CANCELLED", 4, "OTHER").getBody().at("/error/code").asText()).as("a delivered order is never cancelled").isEqualTo("INVALID_TRANSITION");
        assertThat(onHand(s.sku())).isEqualTo(8);
    }

    @Test
    void a_staff_cancel_returns_stock_and_slot_from_confirmed_or_out_for_delivery() {
        Shopper a = shopper();
        String confirmedId = placed(a, true);
        String ops = staff(OPS);
        JsonNode c1 = transition(ops, confirmedId, "CANCELLED", 2, "OUT_OF_STOCK").getBody();
        assertThat(c1.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(c1.get("cancelledBy").asText()).isEqualTo("STAFF");
        assertThat(c1.get("cancelReason").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(c1.get("version").asLong()).isEqualTo(3);
        assertThat(onHand(a.sku())).isEqualTo(10);
        assertThat(usage(a).getInteger("used")).isZero();

        Shopper b = shopper();
        String shippedId = placed(b, true);
        transition(ops, shippedId, "OUT_FOR_DELIVERY", 2, null);
        JsonNode c2 = transition(ops, shippedId, "CANCELLED", 3, "DELIVERY_FAILED").getBody();
        assertThat(c2.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(c2.get("version").asLong()).isEqualTo(4);
        assertThat(c2.get("outForDeliveryAt").asText()).as("the failed delivery is kept in the record").isNotBlank();
        assertThat(onHand(b.sku())).isEqualTo(10);
        assertThat(usage(b).getInteger("used")).isZero();
        assertThat(transition(ops, shippedId, "CANCELLED", 4, "OTHER").getStatusCode().value()).as("already cancelled").isEqualTo(409);
        assertThat(onHand(b.sku())).as("never restocked twice").isEqualTo(10);

        JsonNode customer = call(HttpMethod.GET, "/v1/customer/orders/" + shippedId, b.token(), null, null).getBody();
        assertThat(customer.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(customer.toString()).doesNotContain("DELIVERY_FAILED").doesNotContain("STAFF");
    }

    @Test
    void a_customer_cannot_cancel_once_the_order_is_out_for_delivery() {
        Shopper s = shopper();
        String id = placed(s, false);
        transition(staff(OPS), id, "OUT_FOR_DELIVERY", 2, null);
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, "/v1/customer/orders/" + id + "/cancel", s.token(), null, Map.of("reason", "CHANGED_MIND"));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().get("code").asText()).isEqualTo("ORDER_NOT_CANCELLABLE");
        assertThat(onHand(s.sku())).isEqualTo(8);
    }

    @Test
    void access_follows_the_policy_support_reads_and_catalogue_roles_never_reach_orders() {
        Shopper s = shopper();
        String id = placed(s, false);
        String agent = staff(AGENT);
        assertThat(call(HttpMethod.GET, STAFF + "/" + id, agent, null, null).getStatusCode().value()).as("support reads").isEqualTo(200);
        assertThat(transition(agent, id, "OUT_FOR_DELIVERY", 2, null).getStatusCode().value()).as("support cannot transition").isEqualTo(403);
        for (String t : new String[]{staff(GoogleIdTokens.WRITER), "cms-test-token", "read-test-token"}) {
            assertThat(call(HttpMethod.GET, STAFF + "/" + id, t, null, null).getStatusCode().value()).isEqualTo(403);
            assertThat(transition(t, id, "OUT_FOR_DELIVERY", 2, null).getStatusCode().value()).isEqualTo(403);
        }
        assertThat(call(HttpMethod.GET, STAFF + "/" + id, null, null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(db.getCollection("orders").find(new Document("_id", id)).first().getString("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void the_queue_filters_by_status_pages_newest_first_and_uses_its_index() {
        Shopper s1 = shopper();
        String first = placed(s1, false);
        Shopper s2 = shopper();
        String second = placed(s2, false);
        String ops = staff(OPS);
        transition(ops, first, "OUT_FOR_DELIVERY", 2, null);
        JsonNode out = call(HttpMethod.GET, STAFF + "?status=OUT_FOR_DELIVERY", ops, null, null).getBody();
        assertThat(out.get("items")).allSatisfy(i -> assertThat(i.get("status").asText()).isEqualTo("OUT_FOR_DELIVERY"));
        assertThat(out.toString()).contains(first).doesNotContain(second);
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode p = call(HttpMethod.GET, STAFF + "?page_size=1" + (cursor == null ? "" : "&cursor=" + cursor), ops, null, null).getBody();
            p.get("items").forEach(i -> seen.add(i.get("orderId").asText()));
            cursor = p.hasNonNull("nextCursor") ? p.get("nextCursor").asText() : null;
            assertThat(++pages).isLessThanOrEqualTo(300);
        } while (cursor != null);
        assertThat(seen).doesNotHaveDuplicates().contains(first, second);
        assertThat(seen.indexOf(second)).as("newest first").isLessThan(seen.indexOf(first));
        for (String q : new String[]{"?status=CREATED", "?status=NOPE", "?page_size=0", "?page_size=51", "?cursor=bad", "?customerId=x"}) {
            assertThat(call(HttpMethod.GET, STAFF + q, ops, null, null).getStatusCode().value()).as(q).isEqualTo(400);
        }
        Document plan = db.getCollection("orders").find(new Document("status", "OUT_FOR_DELIVERY")).sort(new Document("createdAt", -1).append("_id", -1))
                .limit(5).explain();
        String winning = plan.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
        assertThat(winning).contains("order_by_status_recent").doesNotContain("\"SORT\"").doesNotContain("COLLSCAN");
    }

    List<Document> outbox(String orderId) {
        return db.getCollection("notification_outbox").find(new Document("subject_id", orderId)).into(new ArrayList<>());
    }

    @Test
    void every_fulfilment_step_notifies_the_customer_once_inside_its_transaction() {
        Shopper s = shopper();
        String id = placed(s, false);
        String ops = staff(OPS);
        assertThat(outbox(id)).extracting(d -> d.getString("type")).containsExactly("ORDER_CONFIRMED");

        assertThat(transition(ops, id, "OUT_FOR_DELIVERY", 9, null).getStatusCode().value()).as("stale").isEqualTo(409);
        assertThat(transition(ops, id, "DELIVERED", 2, null).getStatusCode().value()).as("invalid").isEqualTo(409);
        assertThat(outbox(id)).as("refused transitions enqueue nothing").hasSize(1);

        transition(ops, id, "OUT_FOR_DELIVERY", 2, null);
        transition(ops, id, "OUT_FOR_DELIVERY", 2, null);                       // a stale retry of the same step
        transition(ops, id, "DELIVERED", 3, null);
        assertThat(outbox(id)).extracting(d -> d.getString("_id")).containsExactlyInAnyOrder(
                "ORDER_CONFIRMED:" + id, "ORDER_OUT_FOR_DELIVERY:" + id, "ORDER_DELIVERED:" + id);
        assertThat(outbox(id)).allSatisfy(d -> {
            assertThat(d.getString("customer_id")).isEqualTo(s.customerId());
            assertThat(d.toJson()).doesNotContain(s.pin()).doesNotContain("Ravi").doesNotContain("+91");
        });

        Shopper c = shopper();
        String cancelled = placed(c, false);
        transition(ops, cancelled, "CANCELLED", 2, "OUT_OF_STOCK");
        Document row = db.getCollection("notification_outbox").find(new Document("_id", "ORDER_CANCELLED:" + cancelled)).first();
        assertThat(row).isNotNull();
        assertThat(row.get("params", Document.class)).containsEntry("cancelled_by", "STAFF").containsEntry("reason_code", "OUT_OF_STOCK");
    }
}
