package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The service-area admin API over real HTTP and Mongo: auth, validation, CAS, lifecycle, attributed audit. */
class ServiceAreaAdminIT extends AbstractApiIT {

    static final String BASE = "/api/v1/admin/service-areas";

    @org.junit.jupiter.api.BeforeAll
    void indexes() {
        schemaBootstrap.bootstrap(db);   // the unique(pincode) index is what makes a second create a conflict
    }

    @BeforeEach
    void clean() {
        db.getCollection("service_areas").deleteMany(new Document());
        db.getCollection("domain_events").deleteMany(new Document());
    }

    private static Map<String, Object> route(String loc, int priority, boolean active) {
        return Map.of("fulfillmentLocationId", loc, "priority", priority, "active", active);
    }

    private static Map<String, Object> body(String area, Long expected, Map<String, Object>... routes) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("serviceAreaId", area);
        m.put("routes", List.of(routes));
        if (expected != null) m.put("expectedVersion", expected);
        return m;
    }

    private ResponseEntity<JsonNode> put(String pin, Object b, String token) {
        return rest.exchange(url(BASE + "/" + pin), HttpMethod.PUT, new HttpEntity<>(b, headers(token)), JsonNode.class);
    }

    @SafeVarargs
    private ResponseEntity<JsonNode> create(String pin, String area, Map<String, Object>... routes) {
        return put(pin, body(area, null, routes), CMS_TOKEN);
    }

    @Test
    void create_read_update_and_list() {
        ResponseEntity<JsonNode> created = create("560001", "SA-1", route("FL-1", 0, true));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("version").asLong()).isEqualTo(1);
        assertThat(created.getBody().get("active").asBoolean()).isTrue();

        ResponseEntity<JsonNode> read = get(BASE + "/560001", READ_TOKEN, JsonNode.class);
        assertThat(read.getStatusCode().value()).as("the read-only role may read").isEqualTo(200);
        assertThat(read.getBody().at("/routes/0/fulfillmentLocationId").asText()).isEqualTo("FL-1");

        ResponseEntity<JsonNode> updated = put("560001", body("SA-1", 1L, route("FL-1", 0, true), route("FL-2", 1, true)), CMS_TOKEN);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody().get("version").asLong()).isEqualTo(2);
        assertThat(updated.getBody().get("routes")).hasSize(2);

        create("560002", "SA-1", route("FL-1", 0, true));
        create("560003", "SA-2", route("FL-3", 0, true));
        JsonNode page1 = get(BASE + "?limit=2", READ_TOKEN, JsonNode.class).getBody();
        assertThat(page1.get("items")).hasSize(2);
        assertThat(page1.get("items").get(0).get("pincode").asText()).isEqualTo("560001");
        String cursor = page1.get("nextCursor").asText();
        assertThat(cursor).isEqualTo("560002");
        JsonNode page2 = get(BASE + "?limit=2&after=" + cursor, READ_TOKEN, JsonNode.class).getBody();
        assertThat(page2.get("items")).hasSize(1);
        assertThat(page2.has("nextCursor")).as("last page has no cursor").isFalse();
    }

    @Test
    void authentication_and_the_read_only_role_are_enforced() {
        assertThat(get(BASE, null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(put("560001", body("SA-1", null, route("FL-1", 0, true)), null).getStatusCode().value()).isEqualTo(401);
        assertThat(put("560001", body("SA-1", null, route("FL-1", 0, true)), READ_TOKEN).getStatusCode().value())
                .as("a read-only credential cannot write").isEqualTo(403);
        assertThat(post(BASE + "/560001/deactivate", Map.of("expectedVersion", 1), READ_TOKEN, JsonNode.class)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(db.getCollection("service_areas").countDocuments()).isZero();
    }

    @Test
    void create_is_not_an_update_and_stale_versions_conflict() {
        assertThat(create("560001", "SA-1", route("FL-1", 0, true)).getStatusCode().value()).isEqualTo(201);
        ResponseEntity<JsonNode> again = create("560001", "SA-OTHER", route("FL-9", 0, true));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().at("/error/code").asText()).isEqualTo("STALE_VERSION");

        assertThat(put("560001", body("SA-1", 7L, route("FL-1", 0, true)), CMS_TOKEN).getStatusCode().value()).isEqualTo(409);
        assertThat(put("560009", body("SA-1", 1L, route("FL-1", 0, true)), CMS_TOKEN).getStatusCode().value())
                .as("updating a PIN that was never created").isEqualTo(404);
        assertThat(get(BASE + "/560001", READ_TOKEN, JsonNode.class).getBody().get("serviceAreaId").asText()).isEqualTo("SA-1");
    }

    @Test
    void invalid_input_is_422_or_400_and_writes_nothing() {
        assertThat(put("56001", body("SA-1", null, route("FL-1", 0, true)), CMS_TOKEN).getStatusCode().value()).isEqualTo(422);
        assertThat(put("560001", body("SA-1", null, route("FL-1", 0, true), route("FL-1", 1, true)), CMS_TOKEN)
                .getStatusCode().value()).as("duplicate location").isEqualTo(422);
        assertThat(put("560001", body("SA-1", null, route("FL-1", 0, true), route("FL-2", 0, true)), CMS_TOKEN)
                .getStatusCode().value()).as("duplicate priority").isEqualTo(422);
        assertThat(put("560001", body("SA-1", null, route("FL-1", -1, true)), CMS_TOKEN).getStatusCode().value()).isEqualTo(422);
        assertThat(put("560001", Map.of("serviceAreaId", "SA-1"), CMS_TOKEN).getStatusCode().value()).as("no routes").isEqualTo(422);
        assertThat(put("560001", Map.of("serviceAreaId", "SA-1", "routes", List.of(Map.of("fulfillmentLocationId", "FL-1"))), CMS_TOKEN)
                .getStatusCode().value()).as("route missing priority/active").isEqualTo(422);
        assertThat(put("560001", "{not json", CMS_TOKEN).getStatusCode().value()).isEqualTo(400);
        assertThat(get(BASE + "?limit=0", READ_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(422);
        assertThat(get(BASE + "?limit=1000", READ_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(422);
        assertThat(get(BASE + "?after=abc", READ_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(422);
        assertThat(get(BASE + "/12345", READ_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(422);
        assertThat(get(BASE + "/560001", READ_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(404);
        assertThat(db.getCollection("service_areas").countDocuments()).isZero();
        assertThat(db.getCollection("domain_events").countDocuments()).isZero();
    }

    @Test
    void deactivate_and_activate_are_cas_audited_and_change_what_the_public_sees() {
        create("560001", "SA-1", route("FL-1", 0, true));
        ResponseEntity<JsonNode> off = post(BASE + "/560001/deactivate", Map.of("expectedVersion", 1), CMS_TOKEN, JsonNode.class);
        assertThat(off.getStatusCode().value()).isEqualTo(200);
        assertThat(off.getBody().get("active").asBoolean()).isFalse();
        assertThat(off.getBody().get("version").asLong()).isEqualTo(2);
        assertThat(post(BASE + "/560001/deactivate", Map.of("expectedVersion", 1), CMS_TOKEN, JsonNode.class)
                .getStatusCode().value()).as("replay with a stale version").isEqualTo(409);
        assertThat(post(BASE + "/560001/activate", Map.of(), CMS_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(422);
        assertThat(post(BASE + "/560777/activate", Map.of("expectedVersion", 1), CMS_TOKEN, JsonNode.class).getStatusCode().value())
                .isEqualTo(404);
        ResponseEntity<JsonNode> on = post(BASE + "/560001/activate", Map.of("expectedVersion", 2), CMS_TOKEN, JsonNode.class);
        assertThat(on.getBody().get("active").asBoolean()).isTrue();
        assertThat(on.getBody().get("version").asLong()).isEqualTo(3);

        List<String> types = db.getCollection("domain_events").find(Filters.eq("aggregate_id", "560001"))
                .map(d -> d.getString("type")).into(new java.util.ArrayList<>());
        assertThat(types).containsExactlyInAnyOrder("SERVICE_AREA_UPDATED", "SERVICE_AREA_DEACTIVATED", "SERVICE_AREA_ACTIVATED");
    }

    @Test
    void every_write_is_attributed_to_the_authenticated_actor_never_the_body() {
        Map<String, Object> b = new java.util.LinkedHashMap<>(body("SA-1", null, route("FL-1", 0, true)));
        b.put("actor", Map.of("type", "HUMAN_ADMIN", "id", "google:evil"));
        assertThat(put("560001", b, CMS_TOKEN).getStatusCode().value()).isIn(201, 400, 422);
        db.getCollection("service_areas").deleteMany(new Document());
        db.getCollection("domain_events").deleteMany(new Document());

        create("560001", "SA-1", route("FL-1", 0, true));
        post(BASE + "/560001/deactivate", Map.of("expectedVersion", 1), CMS_TOKEN, JsonNode.class);
        List<Document> events = db.getCollection("domain_events").find().into(new java.util.ArrayList<>());
        assertThat(events).hasSize(2);
        for (Document e : events) {
            Document actor = e.get("actor", Document.class);
            assertThat(actor).as("attributed: " + e.getString("type")).isNotNull();
            assertThat(actor.toJson()).doesNotContain("evil");
            assertThat(actor.getString("type")).isEqualTo("SERVICE_ACCOUNT");
            assertThat(e.toJson()).as("no fulfilment ids, tokens or secrets in the ledger").doesNotContain(CMS_TOKEN);
        }
    }
}
