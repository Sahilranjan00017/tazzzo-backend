package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.FreshnessObservability;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import com.tazzzo.commerce.read.CommerceProjectionScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduler's reconcile tick runs the PACED passes: with a floor of 5 and a 20-minute full-pass target over 90
 * eligible products, one tick enqueues 23 (the paced limit), not 5 (the floor the unpaced call would use).
 */
class CommerceProjectionSchedulerPacingIT extends AbstractMongoIT {

    @BeforeEach
    void clean() {
        for (String c : List.of("work_queue", "product_card_base", "products")) {
            db.getCollection(c).deleteMany(new Document());
        }
    }

    @Test
    void the_reconcile_tick_enqueues_the_paced_limit_not_the_floor() {
        for (int i = 0; i < 100; i++) {
            String sku = String.format("TZP-S%04d", i);
            db.getCollection("products").insertOne(new Document("_id", sku)
                    .append("product_type", "single")
                    .append("identity", new Document("type", "internal").append("internal_key", sku))
                    .append("brand_code", "BR").append("title", "T " + sku)
                    .append("lifecycle", i % 10 == 9 ? "discontinued" : "active")
                    .append("classification", new Document("vertical_id", "TZV-000037").append("release_id", "R1").append("status", "confirmed"))
                    .append("attributes", new Document())
                    .append("attributes_meta", new Document("validated_release", "R1"))
                    .append("version", 1).append("created_at", new java.util.Date()));
        }
        CommerceProjectionScheduler scheduler = new CommerceProjectionScheduler(client, db,
                new FreshnessObservability(new SimpleMeterRegistry()), 200, 5, 1000, 300_000, 4 * 300_000);
        scheduler.reconcile();
        long enqueued = db.getCollection("work_queue").countDocuments(Filters.eq("type", ProjectionRebuildQueue.TYPE));
        assertThat(enqueued).as("ceil(90 eligible * 5 min / 20 min) = 23, above the floor of 5").isEqualTo(23);
        scheduler.reconcile();
        scheduler.reconcile();
        scheduler.reconcile();
        assertThat(db.getCollection("work_queue").countDocuments(Filters.eq("type", ProjectionRebuildQueue.TYPE)))
                .as("the whole catalogue within the four passes the target promises").isEqualTo(90);
    }
}
