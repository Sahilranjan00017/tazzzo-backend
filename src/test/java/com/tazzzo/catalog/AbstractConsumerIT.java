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
    /** Every {@code find}, by collection — the projection read gate (PHASE-5-BATCH-1) under LIST. */
    public static final java.util.Map<String, AtomicInteger> FINDS_BY_COLLECTION =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Each products {@code find} as sent: its {@code limit} and projected field names (LIST-1 §7). */
    public static final java.util.List<Document> PRODUCT_FIND_COMMANDS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @TestConfiguration
    public static class ProbeCounting {
        @Bean
        public MongoClientSettingsBuilderCustomizer countProductFinds() {
            return builder -> builder.addCommandListener(new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    if (!"find".equals(event.getCommandName()) || !event.getCommand().containsKey("find")) {
                        return;
                    }
                    String collection = event.getCommand().getString("find").getValue();
                    FINDS_BY_COLLECTION.computeIfAbsent(collection, k -> new AtomicInteger()).incrementAndGet();
                    if ("products".equals(collection)) {
                        PRODUCT_FINDS.incrementAndGet();
                        Document sent = new Document();
                        if (event.getCommand().containsKey("limit")) {
                            sent.append("limit", event.getCommand().getNumber("limit").intValue());
                        }
                        if (event.getCommand().containsKey("projection")) {
                            sent.append("projection", new java.util.ArrayList<>(
                                    event.getCommand().getDocument("projection").keySet()));
                        }
                        sent.append("has_skip", event.getCommand().containsKey("skip"));
                        PRODUCT_FIND_COMMANDS.add(sent);
                    }
                }
            });
        }
    }

    /**
     * The token buckets are keyed by client IP and live in the ONE Redis every consumer suite in
     * this JVM shares, so a bucket drained (or flooded) by a previous test class is what the next
     * class starts from. A suite that pins capacity to an exact cost must begin from an empty
     * store, or its first request depends on which class ran before it and how long ago.
     * Test-side isolation only; no server behaviour is involved.
     */
    protected static void flushRateLimitBuckets() {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                                REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushAll();
        } finally {
            factory.destroy();
        }
    }

    protected static int finds(String collection) {
        AtomicInteger n = FINDS_BY_COLLECTION.get(collection);
        return n == null ? 0 : n.get();
    }

    protected static void resetCounters() {
        PRODUCT_FINDS.set(0);
        FINDS_BY_COLLECTION.clear();
        PRODUCT_FIND_COMMANDS.clear();
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
