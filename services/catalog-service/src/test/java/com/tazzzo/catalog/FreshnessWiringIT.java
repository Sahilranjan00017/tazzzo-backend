package com.tazzzo.catalog;

import com.tazzzo.common.audit.TestActors;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.ProductUpdateService;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-10A review (HIGH #4): proves the freshness source hook is WIRED IN PRODUCTION, not merely
 * test-wired. With {@code tazzzo.freshness.enabled=true}, {@code CommerceFreshnessConfig} attaches
 * the shared {@code ProjectionRebuildQueue} to the singleton {@link WritePath} bean, so a real
 * Catalog product mutation through the production write path enqueues a rebuild. The commerce
 * projection scheduler stays off (its own {@code tazzzo.scheduler.card-projection-enabled} flag is
 * unset), so the enqueued item is not drained out from under the assertion.
 */
@TestPropertySource(properties = "tazzzo.freshness.enabled=true")
class FreshnessWiringIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired WritePath writePath;

    @BeforeEach
    void clean() {
        db.getCollection("work_queue").deleteMany(new Document());
        db.getCollection("products").deleteMany(new Document());
    }

    @Test void freshness_enabled_attaches_the_queue_so_a_real_catalog_mutation_enqueues() {
        db.getCollection("products").insertOne(new Document("_id", "TZP-WIRE")
                .append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", "TZP-WIRE"))
                .append("brand_code", "BR").append("title", "T WIRE")
                .append("lifecycle", "active")
                .append("classification", new Document("vertical_id", "TZV-000037")
                        .append("release_id", "R1").append("status", "confirmed"))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", Date.from(NOW)));

        // A real Catalog mutation through the SINGLETON WritePath bean (attached by the freshness
        // config at startup) — not a test-constructed WritePath.
        new ProductUpdateService(new Tx(client), writePath).updateTitle(TestActors.TEST, "TZP-WIRE", 1, "Renamed WIRE");

        assertNotNull(db.getCollection("work_queue").find(Filters.eq("_id", "card_rebuild:TZP-WIRE")).first(),
                "freshness.enabled attached the queue to the production WritePath bean");
    }
}
