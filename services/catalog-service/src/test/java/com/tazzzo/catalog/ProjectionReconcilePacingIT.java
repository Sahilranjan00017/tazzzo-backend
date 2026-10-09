package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.ProjectionReconciler;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The paced reconcile passes against Mongo: the per-pass limit is derived from the eligible count at the start of a
 * full pass (one count per wrap, not per tick), floors at the configured limit, and a full pass completes in the number
 * of passes the formula promises.
 */
class ProjectionReconcilePacingIT extends AbstractMongoIT {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final long FIVE_MIN = 300_000;

    @BeforeEach
    void clean() {
        for (String c : List.of("work_queue", "product_card_base", "products", "price_current", "price_events")) {
            db.getCollection(c).deleteMany(new Document());
        }
    }

    private ProjectionReconciler reconciler() {
        return new ProjectionReconciler(db, new ProjectionRebuildQueue(db, CLOCK));
    }

    private void seedEligible(int n) {
        PricingService pricing = new PricingService(new Tx(client), new WritePath(db), CLOCK);
        for (int i = 0; i < n; i++) {
            String sku = String.format("TZP-P%04d", i);
            db.getCollection("products").insertOne(new Document("_id", sku)
                    .append("product_type", "single")
                    .append("identity", new Document("type", "internal").append("internal_key", sku))
                    .append("brand_code", "BR").append("title", "T " + sku)
                    .append("lifecycle", i % 10 == 9 ? "discontinued" : "active")   // every tenth is NOT eligible
                    .append("classification", new Document("vertical_id", "TZV-000037").append("release_id", "R1").append("status", "confirmed"))
                    .append("attributes", new Document())
                    .append("attributes_meta", new Document("validated_release", "R1"))
                    .append("version", 1).append("created_at", java.util.Date.from(CLOCK.instant())));
            pricing.upsertPrice(new UpsertPriceCommand(sku, 100, 200, Currency.INR, null, null, "seed", null));
        }
    }

    private long enqueued() {
        return db.getCollection("work_queue").countDocuments(Filters.eq("type", ProjectionRebuildQueue.TYPE));
    }

    @Test
    void a_full_pass_completes_within_the_target_whatever_the_floor() {
        seedEligible(100);   // 90 eligible
        // target: a full pass within 4 passes of "5 minutes": ceil(90 * 5min / 20min) = 23 per pass, above a floor of 5
        ProjectionReconciler r = reconciler();
        int p1 = r.reconcileDriftPaced(FIVE_MIN, 4 * FIVE_MIN, 5, 1000);
        assertThat(p1).as("paced above the floor").isEqualTo(23);
        int p2 = r.reconcileDriftPaced(FIVE_MIN, 4 * FIVE_MIN, 5, 1000);
        int p3 = r.reconcileDriftPaced(FIVE_MIN, 4 * FIVE_MIN, 5, 1000);
        int p4 = r.reconcileDriftPaced(FIVE_MIN, 4 * FIVE_MIN, 5, 1000);
        assertThat(p1 + p2 + p3 + p4).as("every eligible product enqueued within the target number of passes").isEqualTo(90);
        assertThat(enqueued()).isEqualTo(90);
        assertThat(r.reconcileDriftPaced(FIVE_MIN, 4 * FIVE_MIN, 5, 1000)).as("the wrap pass").isZero();
    }

    @Test
    void the_floor_applies_to_a_small_catalogue_and_the_ceiling_bounds_a_pass() {
        seedEligible(30);   // 27 eligible
        assertThat(reconciler().reconcileDriftPaced(FIVE_MIN, 4 * 3_600_000, 500, 20_000)).as("floor: all 27 in one pass").isEqualTo(27);
        db.getCollection("work_queue").deleteMany(new Document());
        assertThat(reconciler().reconcileDriftPaced(FIVE_MIN, FIVE_MIN, 1, 10)).as("needs 27, capped at 10").isEqualTo(10);
    }

    @Test
    void the_count_is_taken_once_per_wrap_not_per_pass() {
        seedEligible(40);   // 36 eligible; target 2 passes -> 18 per pass
        ProjectionReconciler r = reconciler();
        assertThat(r.reconcileDriftPaced(FIVE_MIN, 2 * FIVE_MIN, 1, 1000)).isEqualTo(18);
        // products added mid-pass do not change this pass's limit; the next wrap recounts
        seedMore(20);
        assertThat(r.reconcileDriftPaced(FIVE_MIN, 2 * FIVE_MIN, 1, 1000)).isEqualTo(18);
        int third = r.reconcileDriftPaced(FIVE_MIN, 2 * FIVE_MIN, 1, 1000);   // 36 + 18 eligible = 54 total; 18 remain after 36
        assertThat(third).isEqualTo(18);
        assertThat(r.reconcileDriftPaced(FIVE_MIN, 2 * FIVE_MIN, 1, 1000)).as("wrap").isZero();
        assertThat(r.reconcileDriftPaced(FIVE_MIN, 2 * FIVE_MIN, 1, 1000)).as("recounted: 54 eligible -> 27 per pass").isEqualTo(27);
    }

    @Test
    void the_orphan_pass_is_paced_over_the_projection_rows() {
        for (int i = 0; i < 12; i++) {
            db.getCollection("product_card_base").insertOne(new Document("sku_id", "TZP-O" + i).append("vertical_id", "TZV-000037")
                    .append("title", "orphan").append("projection_version", 1L));
        }
        ProjectionReconciler r = reconciler();
        // 12 rows, target 3 passes -> 4 scanned per pass; none has an eligible product, so all are enqueued for removal
        assertThat(r.reconcileOrphansPaced(FIVE_MIN, 3 * FIVE_MIN, 1, 1000)).isEqualTo(4);
        assertThat(r.reconcileOrphansPaced(FIVE_MIN, 3 * FIVE_MIN, 1, 1000)).isEqualTo(4);
        assertThat(r.reconcileOrphansPaced(FIVE_MIN, 3 * FIVE_MIN, 1, 1000)).isEqualTo(4);
        assertThat(r.reconcileOrphansPaced(FIVE_MIN, 3 * FIVE_MIN, 1, 1000)).as("wrap").isZero();
    }

    private void seedMore(int n) {
        for (int i = 0; i < n; i++) {
            String sku = String.format("TZP-Q%04d", i);   // sorts AFTER TZP-P…, so it is reached in a later pass
            db.getCollection("products").insertOne(new Document("_id", sku)
                    .append("product_type", "single")
                    .append("identity", new Document("type", "internal").append("internal_key", sku))
                    .append("brand_code", "BR").append("title", "T " + sku)
                    .append("lifecycle", i % 10 == 9 ? "discontinued" : "active")
                    .append("classification", new Document("vertical_id", "TZV-000037").append("release_id", "R1").append("status", "confirmed"))
                    .append("attributes", new Document())
                    .append("attributes_meta", new Document("validated_release", "R1"))
                    .append("version", 1).append("created_at", java.util.Date.from(CLOCK.instant())));
        }
    }
}
