package com.tazzzo.catalog;

import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixture for the consumer transport suites: a real Mongo, a real Redis, and a COMMAND
 * LISTENER that counts {@code find} commands against {@code products}.
 *
 * <p>The counter is what makes "Q5 is charged BEFORE any live product read" testable as a fact
 * rather than an intention: a rejected request must produce EXACTLY ZERO product finds, and an
 * admitted one exactly one per candidate.
 */
public abstract class AbstractConsumerIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        MONGO.start();
        REDIS.start();
    }

    /** Counts product reads across the whole driver, so nothing can probe unobserved. */
    public static final AtomicInteger PRODUCT_FINDS = new AtomicInteger();

    @TestConfiguration
    public static class ProbeCounting {
        @Bean
        public MongoClientSettingsBuilderCustomizer countProductFinds() {
            return builder -> builder.addCommandListener(new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    if ("find".equals(event.getCommandName())
                            && event.getCommand().containsKey("find")
                            && "products".equals(event.getCommand().getString("find").getValue())) {
                        PRODUCT_FINDS.incrementAndGet();
                    }
                }
            });
        }
    }

    @LocalServerPort protected int port;
    @Autowired protected TestRestTemplate rest;
    @Autowired protected MongoDatabase db;
    @Autowired protected SchemaBootstrap schemaBootstrap;
    @Autowired protected TaxonomyLoader loader;
    @Autowired protected TaxonomyChangeService changes;

    /**
     * Apache HttpClient 5 retries automatically AND HONOURS {@code Retry-After}. A 429 whose
     * Retry-After is large — which a slow refill rate makes it — would put the TEST CLIENT to
     * sleep for that long, so the suite hangs instead of asserting. Retries are disabled and hard
     * timeouts set, so a future stall fails fast and visibly rather than looking like slowness.
     */
    @org.junit.jupiter.api.BeforeAll
    void useNonRetryingClient() {
        org.apache.hc.client5.http.impl.classic.CloseableHttpClient client =
                org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                        .disableAutomaticRetries()
                        .setDefaultRequestConfig(
                                org.apache.hc.client5.http.config.RequestConfig.custom()
                                        .setConnectionRequestTimeout(
                                                org.apache.hc.core5.util.Timeout.ofSeconds(10))
                                        .setResponseTimeout(
                                                org.apache.hc.core5.util.Timeout.ofSeconds(20))
                                        .build())
                        .build();
        rest.getRestTemplate().setRequestFactory(
                new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(client));
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected <T> ResponseEntity<T> get(String path, HttpHeaders headers, Class<T> type) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), type);
    }

    protected <T> ResponseEntity<T> get(String path, Class<T> type) {
        return get(path, new HttpHeaders(), type);
    }

    protected HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    /** A validator-conformant product, inserted directly so its eligibility state is exact. */
    protected void product(String id, String verticalId, String lifecycle, String status,
                           String productType) {
        Document classification = new Document("vertical_id", verticalId)
                .append("release_id", "R1").append("status", status);
        Document doc = new Document("_id", id).append("product_type", productType)
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", "BR").append("title", "T " + id)
                .append("lifecycle", lifecycle).append("classification", classification)
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date());
        if ("variant_pack".equals(productType)) {
            doc.append("pack_of", new Document("component_product_id", "TZP-CMP").append("qty", 4));
        }
        if ("bundle".equals(productType)) {
            doc.put("classification", new Document("vertical_id", null)
                    .append("release_id", "R1").append("status", status));
            doc.append("bundle_contents", java.util.List.of(
                    new Document("component_product_id", "TZP-C1").append("qty", 1),
                    new Document("component_product_id", "TZP-C2").append("qty", 1)));
        }
        db.getCollection("products").insertOne(doc);
    }

    protected void eligibleProduct(String id, String verticalId) {
        product(id, verticalId, "active", "confirmed", "single");
    }
}
