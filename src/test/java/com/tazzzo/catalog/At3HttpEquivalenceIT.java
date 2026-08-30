package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.bson.Document;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-3 (the Step-3 acceptance test): run the AT-1 and AT-2 operations through HTTP and prove
 * the resulting database state, events, validators, indexes and releases are IDENTICAL to
 * direct service invocation. REST must be another entrance into the same engine, never a
 * second implementation of the rules.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class At3HttpEquivalenceIT extends AbstractApiIT {

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;

    static final String BASMATI = "TZV-000001";

    /** Runs the identical scenario twice: once via services, once via HTTP, in two databases. */
    @Test @Order(1)
    void at3_http_and_direct_service_produce_identical_state() {
        // ---------- arm A: direct service calls (the AT-1/AT-2 path, already proven)
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline("0.9.0");
        runViaServices();
        Snapshot direct = snapshot();

        // ---------- arm B: same scenario, HTTP only
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline("0.9.0");
        runViaHttp();
        Snapshot http = snapshot();

        assertThat(http.products).as("product documents identical").isEqualTo(direct.products);
        assertThat(http.attributeDefs).as("attribute definitions identical").isEqualTo(direct.attributeDefs);
        assertThat(http.attributeSchemas).as("attribute schemas identical").isEqualTo(direct.attributeSchemas);
        assertThat(http.releaseStatuses).as("release states identical").isEqualTo(direct.releaseStatuses);
        assertThat(http.taxonomyNodes).as("taxonomy identical").isEqualTo(direct.taxonomyNodes);
        assertThat(http.eventTypes).as("same audit event types emitted, same counts")
                .isEqualTo(direct.eventTypes);
        assertThat(http.validators).as("no validator drift on either path").isEqualTo(direct.validators);
        assertThat(http.indexes).as("no index drift on either path").isEqualTo(direct.indexes);
        assertThat(http.workQueueTypes).as("same work items enqueued").isEqualTo(direct.workQueueTypes);
    }

    // ---- the scenario, expressed twice ----

    private void runViaServices() {
        var authoring = applicationAuthoring();
        releases.openRelease("4.0.0", "0.9.0");
        authoring.createDefinition("shelf_life_days", "number", "descriptive", null);
        authoring.addSchemaField("rice", "shelf_life_days", false, false);
        releases.activateRelease("4.0.0");
        mint("TZP-AT3", "at3|k", Map.of("pack_size", 5, "pack_unit", "kg", "shelf_life_days", 180));
    }

    private void runViaHttp() {
        post("/api/v1/taxonomy/releases", Map.of("releaseId", "4.0.0", "basedOn", "0.9.0"),
                CMS_TOKEN, JsonNode.class);
        post("/api/v1/attributes", Map.of("key", "shelf_life_days", "type", "number",
                "governance", "descriptive"), CMS_TOKEN, JsonNode.class);
        post("/api/v1/attribute-schemas/rice/fields", Map.of("key", "shelf_life_days",
                "required", false, "allowBreaking", false), CMS_TOKEN, JsonNode.class);
        post("/api/v1/taxonomy/releases/4.0.0/publish", Map.of(), CMS_TOKEN, JsonNode.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "TZP-AT3");
        body.put("productType", "single");
        body.put("identityType", "internal");
        body.put("internalKey", "at3|k");
        body.put("brandCode", "BR-AT3");
        body.put("title", "AT3 product");
        body.put("verticalId", BASMATI);
        body.put("releaseId", "0.9.0");
        body.put("classificationStatus", "provisional");
        body.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg", "shelf_life_days", 180));
        body.put("evidenceRefs", List.of());
        ResponseEntity<JsonNode> res = post("/api/v1/products", body, CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private com.tazzzo.catalog.tx.AttributeAuthoringService applicationAuthoring() {
        return authoring;
    }

    @Autowired com.tazzzo.catalog.tx.AttributeAuthoringService authoring;
    @Autowired com.tazzzo.catalog.tx.MintService mintService;

    private void mint(String id, String key, Map<String, Object> attrs) {
        mintService.mint(new com.tazzzo.catalog.domain.ProductDraft(id, "single", "internal", key,
                null, "BR-AT3", "AT3 product", BASMATI, "0.9.0", "provisional", attrs,
                List.of(), null));
    }

    // ---- state capture ----

    private record Snapshot(List<String> products, List<String> attributeDefs,
                            List<String> attributeSchemas, List<String> releaseStatuses,
                            List<String> taxonomyNodes, Map<String, Integer> eventTypes,
                            List<String> validators, List<String> indexes,
                            Map<String, Integer> workQueueTypes) { }

    private Snapshot snapshot() {
        return new Snapshot(
                docs("products", d -> { d.remove("created_at"); d.remove("updated_at"); return canonical(d); }),
                docs("attribute_definitions", d -> { d.remove("created_at"); d.remove("_id"); return canonical(d); }),
                docs("attribute_schemas", d -> { d.remove("created_at"); d.remove("_id"); return canonical(d); }),
                docs("catalogue_releases", d -> d.getString("_id") + "=" + d.getString("status")),
                docs("taxonomy_nodes", d -> d.getString("_id") + ":" + d.getString("name")
                        + ":" + d.getString("status") + ":" + d.get("version")),
                countBy("product_events", "type"),
                validatorMeta(), indexMeta(),
                countBy("work_queue", "type"));
    }

    private List<String> docs(String coll, java.util.function.Function<Document, String> f) {
        List<String> out = new ArrayList<>();
        db.getCollection(coll).find().forEach(d -> out.add(f.apply(new Document(d))));
        out.sort(String::compareTo);
        return out;
    }

    /** Canonical form: MongoDB does not guarantee field ORDER for upserted documents, so
     *  equivalence is compared on sorted key/value content, not raw JSON byte order. */
    private static String canonical(Document d) {
        return canonicalValue(d);
    }

    @SuppressWarnings("unchecked")
    private static String canonicalValue(Object v) {
        if (v instanceof Document doc) {
            return new java.util.TreeMap<>(doc).entrySet().stream()
                    .map(e -> e.getKey() + "=" + canonicalValue(e.getValue()))
                    .reduce((a, b) -> a + "," + b).orElse("");
        }
        if (v instanceof List<?> list) {
            return "[" + list.stream().map(At3HttpEquivalenceIT::canonicalValue)
                    .reduce((a, b) -> a + "," + b).orElse("") + "]";
        }
        return String.valueOf(v);
    }

    private Map<String, Integer> countBy(String coll, String field) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        db.getCollection(coll).find().forEach(d ->
                counts.merge(String.valueOf(d.get(field)), 1, Integer::sum));
        return counts;
    }

    private List<String> validatorMeta() {
        List<String> out = new ArrayList<>();
        for (Document c : client.getDatabase("tazzzo_api_it").listCollections()) {
            c.remove("info");
            out.add(canonical(c));
        }
        out.sort(String::compareTo);
        return out;
    }

    private List<String> indexMeta() {
        List<String> out = new ArrayList<>();
        for (String coll : List.of("products", "taxonomy_nodes", "attribute_definitions",
                "attribute_schemas", "work_queue")) {
            db.getCollection(coll).listIndexes().forEach(i -> out.add(coll + ":" + canonical(i)));
        }
        out.sort(String::compareTo);
        return out;
    }
}
