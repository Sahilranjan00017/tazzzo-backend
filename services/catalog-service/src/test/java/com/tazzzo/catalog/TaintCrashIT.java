package com.tazzzo.catalog;

import com.tazzzo.catalog.tx.TaintService;
import org.bson.Document;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/** K-3: taint worker dies mid-cascade; checkpoint survives; resume completes exactly-once. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaintCrashIT extends AbstractMongoIT {

    @Autowired TaintService taintService;

    private static final int LINKS = 25;

    @Test @Order(1)
    void setup_evidence_with_25_citing_products() {
        db.getCollection("evidence").insertOne(new Document("_id", "EV-K3")
                .append("sha256", "x").append("evidence_type", "pdp").append("source", "feedX")
                .append("validity", "active").append("payload_state", "readable").append("fence", 0));
        for (int i = 0; i < LINKS; i++) {
            db.getCollection("evidence_links").insertOne(new Document("evidence_id", "EV-K3")
                    .append("product_id", "TZP-K3-" + i).append("link_type", "claim").append("active", true));
        }
    }

    @Test @Order(2)
    void k3_worker_dies_after_one_batch_checkpoint_survives() {
        taintService.retractEvidence("EV-K3", "retracted");
        taintService.runTaintWorker(10, 1); // dies after 1 batch of 10
        Document item = db.getCollection("work_queue").find(eq("_id", "taint:EV-K3")).first();
        assertThat(item.getString("status")).as("item leased by dead worker; lease expiry re-claims").isEqualTo("leased");
        assertThat(item.getObjectId("checkpoint")).as("durable checkpoint recorded").isNotNull();
        long emitted = db.getCollection("work_queue").countDocuments(eq("type", "attribute_revalidation"));
        assertThat(emitted).isEqualTo(10);
    }

    @Test @Order(3)
    void k3_resume_completes_exactly_once() {
        // simulate lease expiry (the reaper's clock): dead worker's lease times out
        db.getCollection("work_queue").updateOne(eq("_id", "taint:EV-K3"),
                com.mongodb.client.model.Updates.set("lease_until", new java.util.Date(0)));
        taintService.runTaintWorker(10, -1); // resume, no fault injection
        assertThat(db.getCollection("work_queue").find(eq("_id", "taint:EV-K3")).first()
                .getString("status")).isEqualTo("completed");
        long emitted = db.getCollection("work_queue").countDocuments(eq("type", "attribute_revalidation"));
        assertThat(emitted).as("every citing product exactly once, no duplicates from the crash window")
                .isEqualTo(LINKS);
        assertThat(db.getCollection("work_queue").countDocuments(and(
                eq("type", "attribute_revalidation"), eq("product_id", "TZP-K3-9")))).isEqualTo(1);
        // idempotent rerun
        taintService.runTaintWorker(10, -1);
        assertThat(db.getCollection("work_queue").countDocuments(eq("type", "attribute_revalidation")))
                .isEqualTo(LINKS);
    }
}
