package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.ImmutableFieldException;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.ProductUpdateService;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-1b — the preventive rail on the single write path.
 *
 * D-1 itself is NOT an application defect: no service writes product_type after mint, so there
 * is no service path to close. The demonstrated bypass is a direct MongoDB write that never
 * reaches WritePath, and the real control for that is operational (D-1a, Atlas roles).
 *
 * What these tests pin is the rail that stops a FUTURE service reopening the class of problem.
 * They therefore drive WritePath directly, standing in for that hypothetical future writer.
 */
class ImmutableFieldGuardIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader taxonomyLoader;
    @Autowired MintService mintService;
    @Autowired ProductUpdateService productUpdateService;
    @Autowired WritePath writePath;
    @Autowired Tx tx;

    static final String BASMATI = "TZV-000001";

    @BeforeAll
    void setUp() {
        taxonomyLoader.load(db);
        mintService.mint(new ProductDraft("TZP-D1B", "single", "internal", "d1b|1", null,
                "BR-TEST", "Guard fixture", BASMATI, "0.9.0", "provisional",
                Map.of("pack_size", 5, "pack_unit", "kg"), List.of(), null));
    }

    /** Drives the write path the way a future service would. */
    private void attempt(Bson update) {
        tx.run(session -> writePath.casUpdateWithEvent(session, "products", "TZP-D1B", 1, update,
                new EventPayload("TEST_UPDATE", "TZP-D1B", Map.of())));
    }

    @Test
    void d1b_1_product_type_cannot_be_changed_through_the_write_path() {
        assertThatThrownBy(() -> attempt(Updates.set("product_type", "variant_pack")))
                .isInstanceOf(ImmutableFieldException.class)
                .hasMessageContaining("product_type");
    }

    @Test
    void d1b_2_identity_subtree_is_guarded_including_dotted_paths() {
        assertThatThrownBy(() -> attempt(Updates.set("identity", new Document("type", "gtin"))))
                .isInstanceOf(ImmutableFieldException.class);
        assertThatThrownBy(() -> attempt(Updates.set("identity.internal_key", "forged")))
                .isInstanceOf(ImmutableFieldException.class)
                .hasMessageContaining("identity.internal_key");
    }

    @Test
    void d1b_3_id_cannot_be_rewritten() {
        assertThatThrownBy(() -> attempt(Updates.set("_id", "TZP-OTHER")))
                .isInstanceOf(ImmutableFieldException.class);
    }

    @Test
    void d1b_4_unset_and_rename_are_guarded_including_the_rename_TARGET() {
        assertThatThrownBy(() -> attempt(Updates.unset("product_type")))
                .isInstanceOf(ImmutableFieldException.class);
        // renaming a harmless field ONTO an immutable one must also be refused
        assertThatThrownBy(() -> attempt(Updates.rename("title", "product_type")))
                .isInstanceOf(ImmutableFieldException.class)
                .hasMessageContaining("product_type");
    }

    @Test
    void d1b_5_a_rejected_update_leaves_no_audit_event_behind() {
        long before = db.getCollection("product_events").countDocuments();
        assertThatThrownBy(() -> attempt(Updates.set("product_type", "bundle")))
                .isInstanceOf(ImmutableFieldException.class);
        assertThat(db.getCollection("product_events").countDocuments())
                .as("the guard must run BEFORE appendEvent, so no phantom event is written")
                .isEqualTo(before);
    }

    @Test
    void d1b_6_legitimate_updates_are_unaffected() {
        assertThatCode(() -> productUpdateService.updateTitle("TZP-D1B", 1, "Renamed"))
                .doesNotThrowAnyException();
        assertThat(db.getCollection("products").find(Filters.eq("_id", "TZP-D1B")).first()
                .getString("title")).isEqualTo("Renamed");
    }

    @Test
    void d1b_7_the_guard_does_NOT_close_d1_direct_mongo_writes_still_succeed() {
        // Honest-register discipline: pin what the rail does NOT do, so nobody mistakes it for
        // the fix. This is exactly the D-1 bypass, and it still works. Only D-1a closes it.
        long n = db.getCollection("products").updateOne(Filters.eq("_id", "TZP-D1B"),
                Updates.combine(Updates.set("product_type", "variant_pack"),
                        Updates.set("pack_of", new Document("component_product_id", "TZP-D1B")
                                .append("qty", 6)))).getModifiedCount();
        assertThat(n).as("direct Mongo write bypasses WritePath entirely — D-1a is the control")
                .isEqualTo(1);
    }
}
