package com.tazzzo.catalog;

import com.tazzzo.catalog.tx.OffersService;
import com.tazzzo.catalog.tx.RollupService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/** K-2: a stalled rollup must make purge() delete NOTHING — loss of price history is unrepresentable. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RollupStallIT extends AbstractMongoIT {

    @Autowired OffersService offersService;
    @Autowired RollupService rollupService;

    @Test @Order(1)
    void k2_stalled_rollup_purge_deletes_nothing() {
        for (int i = 0; i < 5; i++) {
            offersService.upsertOffer("TZP-K2", "tazzzo", "S1", "retail", 100 + i, true);
        }
        assertThat(db.getCollection("price_events").countDocuments(eq("product_id", "TZP-K2"))).isEqualTo(5);
        long deleted = rollupService.purge(); // rollup NEVER ran — the stall
        assertThat(deleted).as("no rolled watermark -> zero deletion (M9 fail-closed)").isZero();
        assertThat(db.getCollection("price_events").countDocuments(eq("product_id", "TZP-K2"))).isEqualTo(5);
    }

    @Test @Order(2)
    void k2b_after_rollup_purge_deletes_only_rolled_range() {
        rollupService.rollup(new Date());
        offersService.upsertOffer("TZP-K2", "tazzzo", "S1", "retail", 999, true); // post-watermark event
        long deleted = rollupService.purge();
        assertThat(deleted).isEqualTo(5);
        assertThat(db.getCollection("price_events").countDocuments(eq("product_id", "TZP-K2")))
                .as("the un-rolled event survives").isEqualTo(1);
        assertThat(db.getCollection("price_rollups").find(eq("product_id", "TZP-K2")).first()
                .getInteger("count")).isEqualTo(5);
        // C-3 residue check: current price exists and equals the ledger's latest
        assertThat(db.getCollection("offers_current").find(eq("product_id", "TZP-K2")).first()
                .getInteger("price")).isEqualTo(999);
    }
}
