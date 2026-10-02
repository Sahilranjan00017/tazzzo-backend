package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.ProductUpdateService;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.ActorDocuments;
import com.tazzzo.common.audit.ActorType;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Admin actor-attributed audit over real HTTP and a real MongoDB: every admin mutation made with the shared tokens is
 * attributed, in the SAME transaction, to a named SERVICE_ACCOUNT principal and the request id returned as
 * {@code X-Request-Id}. A shared token is never presented as a person.
 */
class AdminActorAuditIT extends AbstractApiIT {

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    @Autowired MeterRegistry registry;

    static final String BASMATI = "TZV-000001";

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
    }

    private Map<String, Object> product(String id, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", key);
        m.put("brandCode", "BR-AUD");
        m.put("title", "Audit " + id);
        m.put("verticalId", BASMATI);
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        return m;
    }

    private List<Document> productEvents(String productId) {
        return db.getCollection("product_events").find(Filters.eq("product_id", productId)).into(new ArrayList<>());
    }

    private double rejected(String reason) {
        var c = registry.find("admin_auth_rejected").tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    private static Actor cmsActor(String requestId) {
        return new Actor(ActorType.SERVICE_ACCOUNT, "service:cms-writer", "shared-token:cms-writer", requestId);
    }

    @Test
    void a_product_mutation_over_http_is_attributed_to_the_cms_service_account_and_its_request_id() {
        ResponseEntity<JsonNode> created = post("/api/v1/products", product("TZP-AUD-1", "aud|1"), CMS_TOKEN, JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = created.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).startsWith("req_");
        List<Document> events = productEvents("TZP-AUD-1");
        assertThat(events).as("the mint writes its events in the same transaction").isNotEmpty();
        for (Document e : events) {
            assertThat(ActorDocuments.fromEvent(e)).as(e.toJson()).contains(cmsActor(requestId));
            Document actor = (Document) e.get("actor");
            assertThat(actor.getString("type")).isEqualTo("SERVICE_ACCOUNT");
            assertThat(actor.getString("id")).isEqualTo("service:cms-writer");
            assertThat(actor.getString("request_id")).as("the SAME request id the client received").isEqualTo(requestId);
            assertThat(e.toJson()).as("no token material is ever persisted").doesNotContain(CMS_TOKEN);
        }
    }

    @Test
    void a_taxonomy_mutation_over_http_writes_the_actor_on_its_node_event() {
        assertThat(post("/api/v1/taxonomy/releases", Map.of("releaseId", "5.0.0", "basedOn", "0.9.0"), CMS_TOKEN,
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        int version = db.getCollection("taxonomy_nodes").find(Filters.eq("_id", BASMATI)).first().getInteger("version");

        ResponseEntity<JsonNode> renamed = post("/api/v1/taxonomy/nodes/" + BASMATI + "/rename",
                Map.of("name", "Basmati Rice (audited)", "expectedVersion", version), CMS_TOKEN, JsonNode.class);

        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String requestId = renamed.getHeaders().getFirst("X-Request-Id");
        Document nodeEvent = db.getCollection("node_events")
                .find(Filters.and(Filters.eq("node_id", BASMATI), Filters.eq("event", "renamed"))).first();
        assertThat(nodeEvent).isNotNull();
        assertThat(ActorDocuments.fromEvent(nodeEvent)).contains(cmsActor(requestId));
        Document productEvent = db.getCollection("product_events").find(Filters.eq("type", "NODE_RENAMED")).first();
        assertThat(ActorDocuments.fromEvent(productEvent)).contains(cmsActor(requestId));
    }

    @Test
    void a_reader_write_is_403_counted_and_writes_nothing() {
        long events = db.getCollection("product_events").countDocuments();
        double before = rejected("forbidden");

        ResponseEntity<JsonNode> denied = post("/api/v1/products", product("TZP-AUD-R", "aud|r"), READ_TOKEN, JsonNode.class);

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected("forbidden")).isEqualTo(before + 1);
        assertThat(db.getCollection("product_events").countDocuments()).isEqualTo(events);
        assertThat(db.getCollection("products").countDocuments(Filters.eq("_id", "TZP-AUD-R"))).isZero();
        assertThat(get("/api/v1/taxonomy/nodes/" + BASMATI, READ_TOKEN, JsonNode.class).getStatusCode())
                .as("the reader still reads").isEqualTo(HttpStatus.OK);
    }

    @Test
    void an_invalid_token_is_401_counted_and_writes_nothing() {
        long events = db.getCollection("product_events").countDocuments();
        double before = rejected("unauthenticated");

        ResponseEntity<JsonNode> denied = post("/api/v1/products", product("TZP-AUD-X", "aud|x"), "not-a-token", JsonNode.class);

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rejected("unauthenticated")).isEqualTo(before + 1);
        assertThat(db.getCollection("product_events").countDocuments()).isEqualTo(events);
        assertThat(denied.getBody().toString()).doesNotContain("not-a-token");
    }

    @Test
    void the_actor_cannot_be_chosen_by_the_client() {
        Map<String, Object> body = product("TZP-AUD-S", "aud|s");
        body.put("actor", Map.of("type", "HUMAN_ADMIN", "id", "admin:ceo", "request_id", "req_forged"));
        body.put("actorId", "admin:ceo");
        HttpHeaders h = headers(CMS_TOKEN);
        h.add("X-Actor-Id", "admin:ceo");
        h.add("X-Request-Id", "req_forged");

        ResponseEntity<JsonNode> created = rest.exchange(url("/api/v1/products"), HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String serverRequestId = created.getHeaders().getFirst("X-Request-Id");
        assertThat(serverRequestId).isNotEqualTo("req_forged");
        for (Document e : productEvents("TZP-AUD-S")) {
            assertThat(ActorDocuments.fromEvent(e)).contains(cmsActor(serverRequestId));
        }
    }

    @Test
    void an_event_write_failure_rolls_back_the_attributed_state_change() {
        post("/api/v1/products", product("TZP-AUD-F", "aud|f"), CMS_TOKEN, JsonNode.class);
        Document before = db.getCollection("products").find(Filters.eq("_id", "TZP-AUD-F")).first();
        long eventsBefore = productEvents("TZP-AUD-F").size();
        // a validator no event can satisfy: the audit write itself fails inside the transaction
        db.runCommand(new Document("collMod", "product_events").append("validator",
                new Document("$jsonSchema", new Document("required", List.of("__never_present__")))));
        try {
            ProductUpdateService updates = new ProductUpdateService(new Tx(client), new WritePath(db));
            assertThatThrownBy(() -> updates.updateTitle(cmsActor("req_fail_000000000001"), "TZP-AUD-F",
                    before.getInteger("version"), "Must Not Persist")).isInstanceOf(RuntimeException.class);
        } finally {
            db.runCommand(new Document("collMod", "product_events").append("validator", new Document()));
        }
        assertThat(db.getCollection("products").find(Filters.eq("_id", "TZP-AUD-F")).first())
                .as("no partial state: the title change rolled back with its audit event").isEqualTo(before);
        assertThat(productEvents("TZP-AUD-F")).hasSize((int) eventsBefore);
    }

    @Test
    void the_actor_survives_a_transaction_retry_unchanged() {
        post("/api/v1/products", product("TZP-AUD-T", "aud|t"), CMS_TOKEN, JsonNode.class);
        int version = db.getCollection("products").find(Filters.eq("_id", "TZP-AUD-T")).first().getInteger("version");
        RetryInjectingTx tx = new RetryInjectingTx(client).arm(1);
        Actor actor = cmsActor("req_retry_00000000001");

        new ProductUpdateService(tx, new WritePath(db)).updateTitle(actor, "TZP-AUD-T", version, "Retried Title");

        assertThat(tx.attempts()).as("the first attempt was rolled back and retried").isEqualTo(2);
        List<Document> updates = db.getCollection("product_events").find(Filters.and(
                Filters.eq("product_id", "TZP-AUD-T"), Filters.eq("type", "PRODUCT_TITLE_UPDATED"))).into(new ArrayList<>());
        assertThat(updates).as("exactly the committed attempt's event").hasSize(1);
        assertThat(ActorDocuments.fromEvent(updates.get(0))).as("same actor and request id across the retry").contains(actor);
    }

    @Test
    void a_price_write_records_its_actor_when_supplied_and_stays_unattributed_otherwise() {
        post("/api/v1/products", product("TZP-AUD-P", "aud|p"), CMS_TOKEN, JsonNode.class);
        PricingService pricing = new PricingService(new Tx(client), new WritePath(db), Clock.systemUTC());
        Actor actor = Actor.system("system:price-import");

        long v1 = pricing.upsertPrice(new UpsertPriceCommand("TZP-AUD-P", 10_000L, 12_000L, Currency.INR, null, null,
                "seed", null), actor);
        pricing.upsertPrice(new UpsertPriceCommand("TZP-AUD-P", 9_000L, 12_000L, Currency.INR, null, null,
                "seed", v1));

        List<Document> ledger = db.getCollection("price_events").find(Filters.eq("sku_id", "TZP-AUD-P"))
                .sort(new Document("version", 1)).into(new ArrayList<>());
        assertThat(ledger).hasSize(2);
        assertThat(ActorDocuments.fromEvent(ledger.get(0))).contains(actor);
        assertThat(ledger.get(0).getString("source")).as("source stays provenance, not identity").isEqualTo("seed");
        assertThat(ActorDocuments.fromEvent(ledger.get(1))).as("unattributed: never a guessed actor").isEmpty();
    }

    @Test
    void domain_audit_records_an_actor_when_present_and_none_otherwise() {
        DomainAudit audit = new DomainAudit(db, Clock.systemUTC());
        Actor actor = Actor.system("system:serviceability-import");

        new Tx(client).run(s -> audit.append(s, new DomainEvent("service_area", "SA-AUD-1", "TEST", Map.of(), actor)));
        new Tx(client).run(s -> audit.append(s, new DomainEvent("service_area", "SA-AUD-2", "TEST", Map.of())));

        Document attributed = db.getCollection("domain_events").find(Filters.eq("aggregate_id", "SA-AUD-1")).first();
        Document legacyShape = db.getCollection("domain_events").find(Filters.eq("aggregate_id", "SA-AUD-2")).first();
        assertThat(ActorDocuments.fromEvent(attributed)).contains(actor);
        assertThat(legacyShape.containsKey("actor")).isFalse();
    }
}
