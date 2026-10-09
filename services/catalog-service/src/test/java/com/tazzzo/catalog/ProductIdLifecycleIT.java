package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoClient;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.common.money.Currency;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.media.MediaService;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A product created through the admin API with a mixed-case id ({@code TZP-Med-3}) keeps its case end to end: it can be
 * carted, put in a PRODUCT_RAIL content block, and fetched from the storefront under that exact id. (The made-eligible
 * step is seeded through the domain services; the lifecycle transitions are not under test here.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class ProductIdLifecycleIT extends AbstractConsumerIT {

    static final String ID = "TZP-Med-3";
    static final String VERTICAL = "TZV-000001";
    static final String PIN = "560034";
    static final String LOC = "FL-LIFECYCLE";
    static final String CMS = "cms-test-token";

    static String key(String seed) {
        return Base64.getEncoder().encodeToString(seed.getBytes(StandardCharsets.UTF_8));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_id_lifecycle");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> CMS);
        r.add("tazzzo.auth.read-token", () -> "read-test-token");
        r.add("tazzzo.freshness.enabled", () -> "true");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> key("lifecycle-cursor-fixture-key-32b!"));
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> key("lifecycle-access-fixture-key-32!"));
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> key("lifecycle-refresh-fixture-key-32"));
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    @Autowired MongoClient client;
    @Autowired OtpVerifiedGrantRepository grants;

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        Clock clock = Clock.systemUTC();
        Tx tx = new Tx(client);
        new ServiceabilityService(tx, db, new DomainAudit(db, clock), clock).upsertServiceArea(new UpsertServiceAreaCommand(
                PIN, "SA-LIFECYCLE", List.of(new ServiceabilityRoute(LOC, 0, true)), "seed", null));
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, String bearer, Map<String, String> headers, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) h.setBearerAuth(bearer);
        if (headers != null) headers.forEach(h::set);
        return rest.exchange(url(path), method, new HttpEntity<>(body, h), JsonNode.class);
    }

    String customerToken() {
        String phone = "+9198" + String.format("%08d", (System.nanoTime() / 7) % 100_000_000);
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return call(HttpMethod.POST, "/v1/auth/session", null, null, Map.of("grantId", grantId)).getBody()
                .get("accessToken").asText();
    }

    @Test
    void a_created_mixed_case_id_can_be_fetched_carted_and_merchandised_with_its_case_preserved() {
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("id", ID);
        create.put("productType", "single");
        create.put("identityType", "internal");
        create.put("internalKey", "lifecycle|" + ID);
        create.put("brandCode", "BR-LIFECYCLE");
        create.put("title", "Mixed case rice");
        create.put("verticalId", VERTICAL);
        create.put("releaseId", "R1");
        create.put("classificationStatus", "provisional");
        create.put("attributes", Map.of("pack_size", 3, "pack_unit", "kg"));
        create.put("evidenceRefs", List.of());
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, "/api/v1/products", CMS, null, create);
        assertThat(created.getStatusCode().value()).as(String.valueOf(created.getBody())).isEqualTo(201);
        assertThat(created.getBody().get("id").asText()).isEqualTo(ID);

        // eligible, priced, stocked and projected (the lifecycle transitions themselves are not under test)
        db.getCollection("products").updateOne(new Document("_id", ID), new Document("$set", new Document("lifecycle", "active")
                .append("classification.status", "confirmed").append("attributes_meta", new Document("validated_release", "R1"))));
        Clock clock = Clock.systemUTC();
        Tx tx = new Tx(client);
        PricingService pricing = new PricingService(tx, new WritePath(db), clock);
        pricing.upsertPrice(new UpsertPriceCommand(ID, 24900, 29900, Currency.INR, null, null, "seed", null));
        new InventoryService(tx, new WritePath(db), clock).setInventory(new SetInventoryCommand(ID, LOC, 20, 2, 10, "seed", null));
        new ProductCardProjectionService(new CatalogCardReader(db), pricing, new MediaService(tx, new WritePath(db), clock), db,
                clock).rebuildOne(ID);

        // fetched under that exact id; another case is another (unknown) id
        ResponseEntity<JsonNode> pdp = call(HttpMethod.GET, "/v1/products/" + ID, null, null, null);
        assertThat(pdp.getStatusCode().value()).as(String.valueOf(pdp.getBody())).isEqualTo(200);
        assertThat(pdp.getBody().toString()).contains("\"" + ID + "\"");
        assertThat(call(HttpMethod.GET, "/v1/products/TZP-med-3", null, null, null).getStatusCode().value())
                .as("no case folding").isEqualTo(404);

        // carted
        String token = customerToken();
        ResponseEntity<JsonNode> carted = call(HttpMethod.PUT, "/v1/customer/cart/items/" + ID, token,
                Map.of("If-Match", "\"cart-0\""), Map.of("quantity", 1));
        assertThat(carted.getStatusCode().value()).as(String.valueOf(carted.getBody())).isEqualTo(200);
        assertThat(carted.getBody().get("items").findValuesAsText("skuId")).containsExactly(ID);

        // merchandised in a PRODUCT_RAIL block, and the block can be published
        ResponseEntity<JsonNode> block = call(HttpMethod.POST, "/api/v1/admin/content/blocks", CMS, null,
                Map.of("type", "PRODUCT_RAIL", "title", "Rail", "sort", 1, "payload", Map.of("ids", List.of(ID))));
        assertThat(block.getStatusCode().value()).as(String.valueOf(block.getBody())).isEqualTo(201);
        assertThat(block.getBody().toString()).contains("\"" + ID + "\"");
        ResponseEntity<JsonNode> published = call(HttpMethod.POST,
                "/api/v1/admin/content/blocks/" + block.getBody().get("blockId").asText() + "/status", CMS, null,
                Map.of("to", "PUBLISHED", "expectedVersion", block.getBody().get("version").asLong()));
        assertThat(published.getStatusCode().value()).as(String.valueOf(published.getBody())).isEqualTo(200);
    }
}
