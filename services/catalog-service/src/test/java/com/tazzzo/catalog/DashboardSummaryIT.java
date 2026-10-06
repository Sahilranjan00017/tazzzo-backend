package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.dashboard.DashboardTestAccess;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The bounded operations dashboard: exact counts under the cap, the capped flag, auth, no personal data. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardSummaryIT extends AbstractApiIT {

    static final String PATH = "/api/v1/admin/dashboard/summary";

    void product(String id, String lifecycle) {
        db.getCollection("products").insertOne(new Document("_id", id).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", "BR").append("title", "T " + id).append("lifecycle", lifecycle)
                .append("classification", new Document("vertical_id", "TZV-000001").append("release_id", "R1").append("status", "confirmed"))
                .append("attributes", new Document()).append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date()));
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        Instant now = Instant.now();
        Date recent = Date.from(now.minus(1, ChronoUnit.HOURS)), old = Date.from(now.minus(3, ChronoUnit.DAYS));
        int q = 0;
        for (Object[] o : new Object[][]{{"CONFIRMED", recent}, {"CONFIRMED", old}, {"OUT_FOR_DELIVERY", recent},
                {"DELIVERED", recent}, {"DELIVERED", old}, {"CANCELLED", recent}}) {
            db.getCollection("orders").insertOne(new Document("status", o[0]).append("createdAt", o[1])
                    .append("customerId", "CUS_dash0001").append("quoteId", "CHKQ_dash" + (q++)).append("addressSnapshot", new Document("recipientPhone", "+919811111111")));
        }
        db.getCollection("inventory").insertOne(new Document("sku_id", "TZP-1").append("active", true).append("on_hand", 0).append("low_stock_threshold", 5));
        db.getCollection("inventory").insertOne(new Document("sku_id", "TZP-2").append("active", true).append("on_hand", 3).append("low_stock_threshold", 5));
        db.getCollection("inventory").insertOne(new Document("sku_id", "TZP-3").append("active", true).append("on_hand", 50).append("low_stock_threshold", 5));
        db.getCollection("inventory").insertOne(new Document("sku_id", "TZP-4").append("active", false).append("on_hand", 0).append("low_stock_threshold", 5));
        product("TZP-91", "active");
        product("TZP-92", "active");
        product("TZP-93", "draft");
        db.getCollection("service_areas").insertOne(new Document("_id", "SA-1").append("pincode", "560001").append("active", true));
        db.getCollection("service_areas").insertOne(new Document("_id", "SA-2").append("pincode", "560002").append("active", false));
        db.getCollection("support_cases").insertOne(new Document("status", "OPEN").append("customerId", "CUS_dash0001"));
        db.getCollection("notification_outbox").insertOne(new Document("_id", "X:1").append("status", "PENDING"));
    }

    long value(JsonNode n, String ptr) {
        assertThat(n.at(ptr + "/capped").asBoolean()).as(ptr).isFalse();
        return n.at(ptr + "/value").asLong();
    }

    @Test
    void every_count_is_exact_under_the_cap_and_nothing_personal_is_returned() {
        ResponseEntity<JsonNode> r = get(PATH, READ_TOKEN, JsonNode.class);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
        JsonNode b = r.getBody();
        assertThat(value(b, "/orders/open_confirmed")).isEqualTo(2);
        assertThat(value(b, "/orders/open_out_for_delivery")).isEqualTo(1);
        assertThat(value(b, "/orders/last24h_confirmed")).isEqualTo(1);
        assertThat(value(b, "/orders/last24h_delivered")).isEqualTo(1);
        assertThat(value(b, "/orders/last24h_cancelled")).isEqualTo(1);
        assertThat(value(b, "/inventory/out_of_stock")).as("inactive rows excluded").isEqualTo(1);
        assertThat(value(b, "/inventory/low_stock")).isEqualTo(1);
        assertThat(value(b, "/catalog/active")).isEqualTo(2);
        assertThat(value(b, "/catalog/draft")).isEqualTo(1);
        assertThat(value(b, "/catalog/products_total")).isEqualTo(3);
        assertThat(value(b, "/serviceability/service_areas_total")).isEqualTo(2);
        assertThat(value(b, "/serviceability/active")).isEqualTo(1);
        assertThat(value(b, "/support/open")).isEqualTo(1);
        assertThat(value(b, "/notifications/pending")).isEqualTo(1);
        assertThat(b.at("/bounds/cap").asLong()).isEqualTo(10_000);
        assertThat(b.toString()).doesNotContain("CUS_dash0001").doesNotContain("+91");
    }

    @Test
    void a_count_that_reaches_its_cap_is_reported_as_capped() {
        Map<String, Object> s = DashboardTestAccess.summaryWithCap(db, 2);
        assertThat(s.get("orders").toString()).contains("open_confirmed=Count[value=2, capped=true]");
        assertThat(s.get("orders").toString()).contains("open_out_for_delivery=Count[value=1, capped=false]");
    }

    @Test
    void only_admin_readers_see_it() {
        assertThat(get(PATH, null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(get(PATH, "not-a-token", JsonNode.class).getStatusCode().value()).isEqualTo(401);
    }
}
