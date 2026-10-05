package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-F: the admin product list (and the 500 it fixed), request-binding errors, node creation and node listing. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, CatalogAdminCompletionIT.NeedsParam.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogAdminCompletionIT extends AbstractApiIT {

    /** A test-only INTERNAL endpoint with a REQUIRED request parameter, to pin how a binding failure is answered. */
    @RestController
    static class NeedsParam {
        @GetMapping("/api/v1/_test/needs-param")
        String needs(@RequestParam("q") String q) {
            return q;
        }
    }

    static final String BASMATI = "TZV-000001";
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
        for (int i = 1; i <= 5; i++) {
            assertThat(post("/api/v1/products", product("TZP-LST-" + i, "list|" + i), CMS_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(201);
        }
    }

    private Map<String, Object> product(String id, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", key);
        m.put("brandCode", "BR-LST");
        m.put("title", "List " + id);
        m.put("verticalId", BASMATI);
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        return m;
    }

    private void assertCode(ResponseEntity<JsonNode> res, int status, String code) {
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(status);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo(code);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod m, String path, Object body, String token) {
        return rest.exchange(url(path), m, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    // ------------------------------------------------------------ products list

    @Test @Order(1)
    void the_product_list_exists_and_a_bare_get_is_no_longer_a_500() {
        ResponseEntity<JsonNode> res = get("/api/v1/products", READ, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("items")).hasSize(5);
        assertThat(res.getBody().has("nextCursor")).isFalse();
        JsonNode first = res.getBody().get("items").get(0);
        assertThat(first.get("id").asText()).isEqualTo("TZP-LST-1");
        assertThat(first.get("verticalId").asText()).isEqualTo(BASMATI);
        assertThat(first.get("lifecycle").asText()).isNotBlank();
        assertThat(first.has("attributes")).as("a summary, not the full document").isFalse();
    }

    @Test @Order(2)
    void the_list_is_keyset_paged_in_id_order_without_gaps_or_repeats() {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode body = get("/api/v1/products?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), READ, JsonNode.class).getBody();
            body.get("items").forEach(i -> ids.add(i.get("id").asText()));
            cursor = body.hasNonNull("nextCursor") ? body.get("nextCursor").asText() : null;
            pages++;
            assertThat(pages).as("a cursor that never advances must fail, not loop").isLessThanOrEqualTo(10);
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(ids).containsExactly("TZP-LST-1", "TZP-LST-2", "TZP-LST-3", "TZP-LST-4", "TZP-LST-5");
    }

    @Test @Order(3)
    void filters_combine_and_secondary_filters_require_a_vertical() {
        assertThat(get("/api/v1/products?verticalId=" + BASMATI, READ, JsonNode.class).getBody().get("items")).hasSize(5);
        assertThat(get("/api/v1/products?verticalId=TZV-000002", READ, JsonNode.class).getBody().get("items")).isEmpty();
        String lifecycle = get("/api/v1/products", READ, JsonNode.class).getBody().get("items").get(0).get("lifecycle").asText();
        assertThat(get("/api/v1/products?verticalId=" + BASMATI + "&lifecycle=" + lifecycle, READ, JsonNode.class).getBody().get("items")).hasSize(5);
        assertThat(get("/api/v1/products?verticalId=" + BASMATI + "&lifecycle=nope", READ, JsonNode.class).getBody().get("items")).isEmpty();
        assertThat(get("/api/v1/products?verticalId=" + BASMATI + "&status=provisional", READ, JsonNode.class).getBody().get("items")).hasSize(5);
        assertCode(get("/api/v1/products?lifecycle=active", READ, JsonNode.class), 400, "MALFORMED_REQUEST");
        assertCode(get("/api/v1/products?status=provisional", READ, JsonNode.class), 400, "MALFORMED_REQUEST");
    }

    @Test @Order(4)
    void the_list_grammar_is_closed() {
        for (String q : new String[]{"?sort=title", "?limit=0", "?limit=201", "?limit=abc", "?limit=-1", "?limit=01", "?cursor=", "?cursor=a%20b",
                "?verticalId=", "?verticalId=a&verticalId=b", "?verticalId=%7B%22%24ne%22%3Anull%7D", "?limit=2&limit=3", "?x=1"}) {
            assertCode(get("/api/v1/products" + q, READ, JsonNode.class), 400, "MALFORMED_REQUEST");
        }
    }

    @Test @Order(5)
    void the_list_requires_authentication_and_the_canonical_key_lookup_is_unchanged() {
        assertThat(get("/api/v1/products", null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(get("/api/v1/products", "wrong", JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertCode(get("/api/v1/products?canonicalKey=nope", READ, JsonNode.class), 404, "NOT_FOUND");
    }

    // --------------------------------------------------------- binding errors

    @Test @Order(6)
    void a_missing_required_parameter_is_a_400_not_a_500() {
        assertCode(get("/api/v1/_test/needs-param", CMS_TOKEN, JsonNode.class), 400, "MALFORMED_REQUEST");
        ResponseEntity<String> ok = get("/api/v1/_test/needs-param?q=x", CMS_TOKEN, String.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
    }

    // ------------------------------------------------------------ node create

    private Map<String, Object> node(String type, String name, String parent, String schema) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeType", type);
        m.put("name", name);
        m.put("parentId", parent);
        if (schema != null) m.put("attributeSchemaId", schema);
        return m;
    }

    private ResponseEntity<JsonNode> createNode(String type, String name, String parent, String schema) {
        return post("/api/v1/taxonomy/nodes", node(type, name, parent, schema), CMS_TOKEN, JsonNode.class);
    }

    @Test @Order(10)
    void creating_a_node_needs_an_open_release() {
        assertCode(createNode("super_category", "Early Bird", null, null), 409, "NO_OPEN_RELEASE");
        assertThat(db.getCollection("taxonomy_nodes").countDocuments(Filters.eq("name", "Early Bird"))).isZero();
        assertThat(post("/api/v1/taxonomy/releases", Map.of("releaseId", "0.9.1", "basedOn", "0.9.0"), CMS_TOKEN, JsonNode.class)
                .getStatusCode().value()).isEqualTo(201);
    }

    @Test @Order(11)
    void a_full_branch_can_be_created_top_down_with_prefixed_ids_and_audit() {
        ResponseEntity<JsonNode> sup = createNode("super_category", "Test Super", null, null);
        assertThat(sup.getStatusCode().value()).isEqualTo(201);
        String superId = sup.getBody().get("id").asText();
        assertThat(superId).matches("TZS-1000\\d\\d");
        assertThat(sup.getBody().get("status").asText()).isEqualTo("active");
        assertThat(sup.getBody().get("version").asInt()).isEqualTo(1);

        String catId = createNode("category", "Test Category", superId, null).getBody().get("id").asText();
        assertThat(catId).startsWith("TZC-");
        String subId = createNode("sub_category", "Test Sub", catId, "rice").getBody().get("id").asText();
        assertThat(subId).startsWith("TZG-");
        ResponseEntity<JsonNode> vert = createNode("vertical", "Test Vertical", subId, "rice");
        assertThat(vert.getStatusCode().value()).isEqualTo(201);
        assertThat(vert.getBody().get("id").asText()).startsWith("TZV-1");
        assertThat(vert.getBody().get("attributeSchemaId").asText()).isEqualTo("rice");
        assertThat(get("/api/v1/taxonomy/nodes/" + vert.getBody().get("id").asText() + "/path", READ, JsonNode.class).getBody().get("nodes")).hasSize(4);

        Document ev = db.getCollection("node_events").find(Filters.and(Filters.eq("node_id", superId), Filters.eq("event", "created"))).first();
        assertThat(ev).isNotNull();
        assertThat(ev.get("actor", Document.class).getString("type")).isEqualTo("SERVICE_ACCOUNT");
        assertThat(ev.getString("release_id")).isEqualTo("0.9.1");
    }

    @Test @Order(12)
    void invalid_creations_are_rejected_with_stable_codes_and_write_nothing() {
        long before = db.getCollection("taxonomy_nodes").countDocuments();
        String cat = db.getCollection("taxonomy_nodes").find(Filters.eq("name", "Test Category")).first().getString("_id");
        String sup = db.getCollection("taxonomy_nodes").find(Filters.eq("name", "Test Super")).first().getString("_id");
        String sub = db.getCollection("taxonomy_nodes").find(Filters.eq("name", "Test Sub")).first().getString("_id");
        assertCode(createNode("planet", "X", null, null), 422, "INVALID_NODE");
        assertCode(createNode("super_category", "X", sup, null), 422, "INVALID_NODE");
        assertCode(createNode("category", "X", null, null), 422, "INVALID_NODE");
        assertCode(createNode("category", "X", cat, null), 422, "INVALID_NODE");          // category under a category
        assertCode(createNode("vertical", "X", cat, "rice"), 422, "INVALID_NODE");        // vertical must hang under a sub_category
        assertCode(createNode("vertical", "X", sub, null), 422, "INVALID_NODE");          // schema required
        assertCode(createNode("category", "X", sup, "rice"), 422, "INVALID_NODE");        // categories carry no schema
        assertCode(createNode("vertical", "X", sub, "no-such-schema"), 404, "UNKNOWN_SCHEMA");
        assertCode(createNode("category", "X", "TZS-999999", null), 404, "NODE_NOT_FOUND");
        assertCode(createNode("category", "Test Category", sup, null), 409, "DUPLICATE_NODE");
        for (String bad : new String[]{"", " ", " lead", "trail ", "x".repeat(121), "tab\tname"}) {
            assertCode(createNode("category", bad, sup, null), 422, "INVALID_NODE");
        }
        assertThat(db.getCollection("taxonomy_nodes").countDocuments()).isEqualTo(before);
    }

    @Test @Order(13)
    void a_deprecated_parent_cannot_receive_children_and_a_reader_cannot_create() {
        String sup = db.getCollection("taxonomy_nodes").find(Filters.eq("name", "Test Super")).first().getString("_id");
        String cat = createNode("category", "Doomed", sup, null).getBody().get("id").asText();
        assertThat(post("/api/v1/taxonomy/nodes/" + cat + "/deprecate", Map.of("expectedVersion", 1), CMS_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(200);
        assertCode(createNode("sub_category", "Orphan", cat, null), 409, "NODE_NOT_ACTIVE");
        assertThat(post("/api/v1/taxonomy/nodes", node("category", "ReaderTry", sup, null), READ, JsonNode.class).getStatusCode().value()).isEqualTo(403);
        assertThat(post("/api/v1/taxonomy/nodes", node("category", "NoAuth", sup, null), null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
    }

    // -------------------------------------------------------------- node list

    @Test @Order(14)
    void nodes_list_filters_and_pages_for_real() {
        ResponseEntity<JsonNode> bad = get("/api/v1/taxonomy/nodes?limit=500", READ, JsonNode.class);
        assertCode(bad, 400, "MALFORMED_REQUEST");
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            assertThat(++guard).as("a cursor that never advances must fail, not loop").isLessThanOrEqualTo(50);
            JsonNode body = get("/api/v1/taxonomy/nodes?nodeType=super_category&limit=2" + (cursor == null ? "" : "&cursor=" + cursor), READ, JsonNode.class).getBody();
            body.get("items").forEach(n -> {
                assertThat(n.get("nodeType").asText()).isEqualTo("super_category");
                ids.add(n.get("id").asText());
            });
            cursor = body.hasNonNull("nextCursor") ? body.get("nextCursor").asText() : null;
        } while (cursor != null);
        assertThat(ids).isSorted().doesNotHaveDuplicates().contains("TZS-000001");
        assertThat(ids.size()).isEqualTo((int) db.getCollection("taxonomy_nodes").countDocuments(Filters.eq("node_type", "super_category")));

        String sup = db.getCollection("taxonomy_nodes").find(Filters.eq("name", "Test Super")).first().getString("_id");
        JsonNode children = get("/api/v1/taxonomy/nodes?parentId=" + sup, READ, JsonNode.class).getBody();
        assertThat(children.get("items")).hasSize(2);
        assertThat(children.get("items").get(0).get("name").asText()).isEqualTo("Test Category");
        JsonNode active = get("/api/v1/taxonomy/nodes?parentId=" + sup + "&status=active", READ, JsonNode.class).getBody();
        assertThat(active.get("items")).hasSize(1);
        assertThat(active.get("items").get(0).get("name").asText()).isEqualTo("Test Category");
        assertThat(get("/api/v1/taxonomy/nodes?parentId=" + sup + "&status=deprecated", READ, JsonNode.class).getBody().get("items")
                .get(0).get("name").asText()).isEqualTo("Doomed");
        assertCode(get("/api/v1/taxonomy/nodes?bogus=1", READ, JsonNode.class), 400, "MALFORMED_REQUEST");
    }

    static final String READ = READ_TOKEN;
}
