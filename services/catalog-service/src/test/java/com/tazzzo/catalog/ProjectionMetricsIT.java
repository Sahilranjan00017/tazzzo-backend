package com.tazzzo.catalog;

import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardReadPort;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.commerce.read.ProjectionConflictException;
import com.tazzzo.commerce.read.ProjectionMetrics;
import com.tazzzo.commerce.read.ProjectionQueueGauges;
import com.tazzzo.commerce.read.ProjectionRebuildWorker;
import com.tazzzo.commerce.read.RebuildOutcome;
import com.tazzzo.common.money.Currency;
import com.tazzzo.media.MediaService;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Projection meters through the real services and a real MongoDB: the retry counters of a rebuild that lost a race, the
 * failure kinds, and the rebuild-queue gauges against the real {@code work_queue} that the real queue producer and worker
 * fill and drain.
 */
class ProjectionMetricsIT extends AbstractMongoIT {

    static final class MovingClock extends Clock {
        volatile Instant now = Instant.parse("2026-06-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    final MovingClock clock = new MovingClock();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ProjectionMetrics metrics = new ProjectionMetrics(registry);

    @BeforeEach
    void clean() {
        db.getCollection("work_queue").deleteMany(new Document());
    }

    PricingService pricing() {
        return new PricingService(new Tx(client), new WritePath(db), clock);
    }

    ProductCardProjectionService projector(CatalogCardReadPort catalog, com.tazzzo.pricing.PriceReadPort prices) {
        return new ProductCardProjectionService(catalog, prices, new MediaService(new Tx(client), new WritePath(db), clock), db, clock)
                .withMetrics(metrics);
    }

    void seed(String id) {
        db.getCollection("products").insertOne(new Document("_id", id).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", "B").append("title", "Metric " + id).append("lifecycle", "active")
                .append("classification", new Document("vertical_id", "TZV-000037").append("release_id", "R1").append("status", "confirmed"))
                .append("attributes", new Document()).append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", Date.from(clock.now)));
    }

    double count(String name) {
        double sum = 0;
        for (var c : registry.find(name).counters()) sum += c.count();
        return sum;
    }

    @Test
    void a_rebuild_that_lost_a_race_counts_the_retry_by_cause_and_still_converges() {
        seed("TZP-PM-1");
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PM-1", 10000L, 20000L, Currency.INR, null, null, "s", null));
        projector(new CatalogCardReader(db), pricing()).rebuildOne("TZP-PM-1");   // row v1 @ 10000
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PM-1", 13000L, 20000L, Currency.INR, null, null, "s", 1L));
        assertThat(count(ProjectionMetrics.CONFLICTS)).as("an uncontended rebuild retries nothing").isZero();

        PricingService real = pricing();
        AtomicInteger calls = new AtomicInteger();
        com.tazzzo.pricing.PriceReadPort stale = sku -> {
            if (calls.incrementAndGet() == 1) {
                var old = real.findCurrentPrice(sku);
                real.upsertPrice(new UpsertPriceCommand("TZP-PM-1", 15000L, 20000L, Currency.INR, null, null, "s", 2L));
                projector(new CatalogCardReader(db), pricing()).rebuildOne("TZP-PM-1");   // a concurrent rebuilder lands first
                return old;
            }
            return real.findCurrentPrice(sku);
        };
        assertThat(projector(new CatalogCardReader(db), stale).rebuildOne("TZP-PM-1")).isEqualTo(RebuildOutcome.NOOP);

        assertThat(registry.find(ProjectionMetrics.CONFLICTS).tag("reason", "stale_cas").counter().count()).isEqualTo(1);
        assertThat(count(ProjectionMetrics.FAILURES)).isZero();
    }

    @Test
    void failures_are_counted_by_kind_and_still_propagate() {
        seed("TZP-PM-2");
        CatalogCardReadPort throwing = sku -> { throw new IllegalStateException("source down"); };
        assertThatThrownBy(() -> projector(throwing, pricing()).rebuildOne("TZP-PM-2")).isInstanceOf(IllegalStateException.class);
        CatalogCardReadPort never = sku -> { throw new ProjectionConflictException("did not converge"); };
        assertThatThrownBy(() -> projector(never, pricing()).rebuildOne("TZP-PM-2")).isInstanceOf(ProjectionConflictException.class);

        assertThat(registry.find(ProjectionMetrics.FAILURES).tag("kind", "error").counter().count()).isEqualTo(1);
        assertThat(registry.find(ProjectionMetrics.FAILURES).tag("kind", "non_converged").counter().count()).isEqualTo(1);
        for (Meter m : registry.getMeters()) {
            for (Tag t : m.getId().getTags()) {
                assertThat(t.getValue()).doesNotContain("TZP-").doesNotContain("source down");
            }
        }
    }

    @Test
    void the_queue_gauges_follow_the_real_queue_through_enqueue_claim_and_drain() {
        ProjectionRebuildQueue queue = new ProjectionRebuildQueue(db, clock);
        SimpleMeterRegistry local = new SimpleMeterRegistry();
        new ProjectionQueueGauges(db, clock, local, Duration.ofSeconds(15));
        assertThat(local.get("projection_rebuild_queue_depth").tag("state", "due").gauge().value()).isZero();
        assertThat(local.get("projection_rebuild_queue_oldest_due_age_seconds").gauge().value()).isZero();

        seed("TZP-PM-3");
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PM-3", 100L, 200L, Currency.INR, null, null, "s", null));
        queue.requestRebuild("TZP-PM-3", "test");
        queue.requestRebuild("TZP-PM-X1", "test");
        clock.now = clock.now.plusSeconds(15);
        queue.requestRebuild("TZP-PM-X2", "test");
        // an item of ANOTHER type in the shared collection, a live lease and a lapsed one
        db.getCollection("work_queue").insertOne(new Document("_id", "other:1").append("type", "merge").append("status", "pending")
                .append("requested_at", Date.from(clock.now.minusSeconds(9999))));
        db.getCollection("work_queue").insertOne(new Document("_id", "card_rebuild:live").append("type", ProjectionRebuildQueue.TYPE)
                .append("status", "leased").append("lease_until", Date.from(clock.now.plusSeconds(60))).append("requested_at", Date.from(clock.now)));
        db.getCollection("work_queue").insertOne(new Document("_id", "card_rebuild:lapsed").append("type", ProjectionRebuildQueue.TYPE)
                .append("status", "leased").append("lease_until", Date.from(clock.now.minusSeconds(5))).append("requested_at", Date.from(clock.now)));

        clock.now = clock.now.plusSeconds(15);
        assertThat(local.get("projection_rebuild_queue_depth").tag("state", "due").gauge().value()).as("3 pending + 1 lapsed lease; the merge item is not ours").isEqualTo(4);
        assertThat(local.get("projection_rebuild_queue_depth").tag("state", "leased").gauge().value()).isEqualTo(1);
        assertThat(local.get("projection_rebuild_queue_oldest_due_age_seconds").gauge().value())
                .as("the oldest pending item was requested 30 s ago").isEqualTo(30.0);

        // drain what exists for the real SKU, then let the snapshot refresh
        ProjectionRebuildWorker worker = new ProjectionRebuildWorker(db,
                projector(new CatalogCardReader(db), pricing()), clock);
        worker.drain(10);
        db.getCollection("work_queue").deleteMany(new Document("type", "merge"));
        clock.now = clock.now.plusSeconds(15);
        assertThat(local.get("projection_rebuild_queue_depth").tag("state", "due").gauge().value())
                .as("drain claims every due item; missing products are cleared too").isLessThan(4);
    }
}
