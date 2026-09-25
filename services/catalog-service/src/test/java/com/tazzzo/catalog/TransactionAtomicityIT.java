package com.tazzzo.catalog;

import com.mongodb.MongoWriteException;
import com.tazzzo.catalog.domain.BundleComponent;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.tx.BundleComponentException;
import com.tazzzo.catalog.tx.BundleService;
import com.tazzzo.catalog.tx.ClassifyService;
import com.tazzzo.catalog.tx.MintService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TX failure-state tests: atomic absence after abort, full presence after commit. */
class TransactionAtomicityIT extends AbstractMongoIT {

    @Autowired MintService mintService;
    @Autowired BundleService bundleService;
    @Autowired ClassifyService classifyService;

    @Test
    void tx_t1a_abort_after_identity_write_leaves_nothing() {
        // internal identity key insert succeeds INSIDE the txn, then the product doc violates
        // the oneOf validator (single + null vertical) -> whole transaction must roll back.
        ProductDraft bad = new ProductDraft("TZP-A1", "single", "internal", "BR-T|TZV-1|atomic",
                null, "BR-TEST", "Atomicity", null /* null vertical => validator reject */,
                "1.0.0", "provisional", Map.of(), List.of(), null);
        assertThatThrownBy(() -> mintService.mint(bad)).isInstanceOf(MongoWriteException.class);
        assertThat(db.getCollection("identity_keys").find(eq("_id", "BR-T|TZV-1|atomic")).first())
                .as("identity key must NOT survive the aborted mint (no burned keys, M10)").isNull();
        assertThat(db.getCollection("products").find(eq("_id", "TZP-A1")).first()).isNull();
        assertThat(db.getCollection("product_events").find(eq("product_id", "TZP-A1")).first())
                .as("no event may survive an aborted transaction").isNull();
        assertThat(db.getCollection("classification_history").find(eq("product_id", "TZP-A1")).first()).isNull();
    }

    @Test
    void tx_t1b_happy_mint_writes_all_four() {
        mintService.mint(TestFixtures.internalSingle("TZP-A2", "BR-T|TZV-1|happy"));
        assertThat(db.getCollection("products").find(eq("_id", "TZP-A2")).first()).isNotNull();
        assertThat(db.getCollection("identity_keys").find(eq("_id", "BR-T|TZV-1|happy")).first()).isNotNull();
        assertThat(db.getCollection("classification_history").find(eq("product_id", "TZP-A2")).first()).isNotNull();
        assertThat(db.getCollection("product_events").find(eq("product_id", "TZP-A2")).first()).isNotNull();
    }

    @Test
    void tx_t1c_unclassified_mint_enqueues_review_in_same_txn() {
        ProductDraft holding = new ProductDraft("TZP-A3", "single", "internal", "BR-T|TZV-1|hold",
                null, "BR-TEST", "Holding", MintService.UNCLASSIFIED, "1.0.0", "review",
                Map.of(), List.of(), null);
        mintService.mint(holding);
        // An UNCLASSIFIED mint legitimately enqueues TWO obligations: classification_review
        // (holding vertical) and identity_incomplete (CAT-ID: unratified vertical => no key).
        // Assert the intended item explicitly rather than relying on document order.
        Document item = db.getCollection("work_queue")
                .find(com.mongodb.client.model.Filters.and(eq("product_id", "TZP-A3"),
                        eq("type", "classification_review"))).first();
        assertThat(item).isNotNull();
        assertThat(item.getString("type")).isEqualTo("classification_review");
    }

    @Test
    void tx_t7a_bundle_with_nonactive_component_writes_nothing() {
        mintService.mint(TestFixtures.internalSingle("TZP-A4", "BR-T|TZV-1|c1")); // stays draft
        assertThatThrownBy(() -> bundleService.writeBundle(TestFixtures.bundle("TZP-A5",
                List.of(new BundleComponent("TZP-A4", 1, null),
                        new BundleComponent("TZP-MISSING", 1, null)))))
                .isInstanceOf(BundleComponentException.class);
        assertThat(db.getCollection("products").find(eq("_id", "TZP-A5")).first()).isNull();
        assertThat(db.getCollection("product_events").find(eq("product_id", "TZP-A5")).first()).isNull();
    }

    @Test
    void r1_r2_identity_mint_race_second_mint_collides() {
        mintService.mint(TestFixtures.internalSingle("TZP-R1", "BR-T|TZV-1|race"));
        assertThatThrownBy(() -> mintService.mint(TestFixtures.internalSingle("TZP-R2", "BR-T|TZV-1|race")))
                .isInstanceOf(com.tazzzo.catalog.tx.IdentityCollisionException.class);
        assertThat(db.getCollection("products").find(eq("_id", "TZP-R2")).first())
                .as("loser of the mint race must write nothing").isNull();
        assertThat(db.getCollection("identity_keys").find(eq("_id", "BR-T|TZV-1|race")).first()
                .getString("product_id")).isEqualTo("TZP-R1");
    }

    @Test
    void tx_t4a_invalid_status_writes_nothing() {
        mintService.mint(TestFixtures.internalSingle("TZP-A6", "BR-T|TZV-1|cls"));
        assertThatThrownBy(() -> classifyService.classify("TZP-A6", "TZV-000200", "1.0.0",
                "definitely_not_a_status", 0.9, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Document p = db.getCollection("products").find(eq("_id", "TZP-A6")).first();
        assertThat(p.get("classification", Document.class).getString("vertical_id"))
                .isEqualTo("TZV-000123"); // untouched
        assertThat(db.getCollection("classification_history")
                .countDocuments(eq("product_id", "TZP-A6"))).isEqualTo(1); // only the mint row
    }
}
