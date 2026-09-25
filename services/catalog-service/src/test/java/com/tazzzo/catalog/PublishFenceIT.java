package com.tazzzo.catalog;

import com.mongodb.MongoCommandException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.tx.EvidenceGateException;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.PublishService;
import org.bson.Document;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T3 fence semantics (M1): the $inc fence write makes a concurrent evidence mutation a real
 * write-write conflict — the write-skew hole that a read-only recheck cannot close.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublishFenceIT extends AbstractMongoIT {

    @Autowired MintService mintService;
    @Autowired PublishService publishService;
    @Autowired com.tazzzo.catalog.tx.TaintService taintService;

    @Test @Order(1)
    void gate_passes_on_active_readable_evidence() {
        mintService.mint(TestFixtures.internalSingle("TZP-P1", "fence|p1"));
        evidence("EV-100001", "active", "readable");
        publishService.publishClaim("TZP-P1", "diabetic_friendly", List.of("EV-100001"));
        Document p = db.getCollection("products").find(eq("_id", "TZP-P1")).first();
        assertThat(p.get("attributes", Document.class).getBoolean("diabetic_friendly_published")).isTrue();
    }

    @Test @Order(2)
    void gate_blocks_retracted_and_shredded_evidence() {
        evidence("EV-100002", "active", "readable");
        taintService.retractEvidence("EV-100002", "retracted"); // THE single retraction path
        assertThatThrownBy(() -> publishService.publishClaim("TZP-P1", "c2", List.of("EV-100002")))
                .isInstanceOf(EvidenceGateException.class);
        evidence("EV-100003", "active", "shredded");
        assertThatThrownBy(() -> publishService.publishClaim("TZP-P1", "c3", List.of("EV-100003")))
                .isInstanceOf(EvidenceGateException.class);
        Document p = db.getCollection("products").find(eq("_id", "TZP-P1")).first();
        assertThat(p.get("attributes", Document.class).get("c2_published")).isNull();
        assertThat(p.get("attributes", Document.class).get("c3_published")).isNull();
    }

    @Test @Order(3)
    void overlapping_sessions_on_the_fence_conflict_write_write() {
        evidence("EV-100004", "active", "readable");
        try (ClientSession s1 = client.startSession(); ClientSession s2 = client.startSession()) {
            s1.startTransaction();
            db.getCollection("evidence").updateOne(s1, Filters.eq("_id", "EV-100004"),
                    Updates.inc("fence", 1)); // publisher's fence write, txn open
            s2.startTransaction();
            assertThatThrownBy(() -> db.getCollection("evidence").updateOne(s2,
                    Filters.eq("_id", "EV-100004"),
                    Updates.combine(Updates.set("validity", "retracted"), Updates.inc("fence", 1))))
                    .as("concurrent validity flip must hit WriteConflict, not proceed")
                    .isInstanceOfSatisfying(MongoCommandException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(112)); // WriteConflict
            s2.abortTransaction();
            s1.abortTransaction();
        }
    }

    private void evidence(String id, String validity, String payloadState) {
        db.getCollection("evidence").insertOne(new Document("_id", id)
                .append("sha256", "x").append("evidence_type", "pdp").append("source", "test")
                .append("validity", validity).append("payload_state", payloadState)
                .append("fence", 0));
    }
}
