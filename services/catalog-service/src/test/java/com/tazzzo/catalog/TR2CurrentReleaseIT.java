package com.tazzzo.catalog;

import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.catalog.consumer.ConsumerReleaseResolver;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TR2-CURRENT-1 — "current consumer release" is an explicit pointer moved inside the activation
 * transaction, never an inference.
 *
 * <p>The schema enforces at most one OPEN release but NOT exactly one historical ACTIVE one, so
 * "latest timestamp", "highest id" and "whichever active row Mongo returns first" are all guesses.
 * These tests hold the pointer to being the only answer.
 */
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class TR2CurrentReleaseIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService changes;
    @Autowired ConsumerReleaseResolver resolver;

    private static final String BASMATI = "TZV-000001";

    @BeforeAll
    void seedBaseline() {
        loader.load(db);
        changes.recordBaseline("R1");
    }

    private String pointer() {
        Document p = db.getCollection("system_config")
                .find(eq("_id", TaxonomyChangeService.CONSUMER_RELEASE_POINTER)).first();
        return p == null ? null : p.getString("release_id");
    }

    private int version(String nodeId) {
        return db.getCollection("taxonomy_nodes").find(eq("_id", nodeId)).first().getInteger("version");
    }

    @Test @org.junit.jupiter.api.Order(1)
    void a_successful_activation_moves_the_pointer() {
        assertThat(pointer()).as("the baseline activation set it").isEqualTo("R1");
        assertThat(resolver.resolve(null)).isEqualTo("R1");

        changes.openRelease("R2", "R1");
        changes.renameNode(BASMATI, version(BASMATI), "Basmati Rice R2");
        changes.activateRelease("R2");

        assertThat(pointer()).isEqualTo("R2");
        assertThat(resolver.resolve(null)).isEqualTo("R2");
    }

    /**
     * The crash hook returns before the final activation transaction. The pointer must not move —
     * it is written in that transaction precisely so a half-finished release cannot become current.
     *
     * <p>Ordered LAST: a crashed activation deliberately leaves the release gate held (fail-closed,
     * so no further change can slip in), which would block any later test that opens a release.
     */
    @Test @org.junit.jupiter.api.Order(9)
    void an_incomplete_activation_does_NOT_move_the_pointer() {
        String before = pointer();
        changes.openRelease("R-CRASH", before);
        changes.renameNode(BASMATI, version(BASMATI), "Basmati Rice CRASH");

        changes.activateRelease("R-CRASH", 50, 1);   // simulated crash mid-snapshot

        assertThat(pointer())
                .as("a release that never completed activation must never become current")
                .isEqualTo(before);
        assertThat(db.getCollection("catalogue_releases").find(eq("_id", "R-CRASH")).first()
                .getString("status")).isNotEqualTo("active");
        assertThat(resolver.resolve(null)).isEqualTo(before);
    }

    @Test @org.junit.jupiter.api.Order(2)
    void an_explicit_historical_active_release_remains_usable() {
        assertThat(resolver.resolve("R1"))
                .as("prior releases stay active and explicitly queryable")
                .isEqualTo("R1");
        assertThat(resolver.resolve(null)).isNotEqualTo("R1");
    }

    @Test @org.junit.jupiter.api.Order(3)
    void an_unknown_or_non_active_explicit_release_is_NOT_FOUND() {
        assertThatThrownBy(() -> resolver.resolve("NEVER-EXISTED"))
                .isInstanceOf(ConsumerFailures.NotFound.class);
        db.getCollection("catalogue_releases").insertOne(
                new Document("_id", "R-PUBLISHING").append("status", "publishing"));
        assertThatThrownBy(() -> resolver.resolve("R-PUBLISHING"))
                .as("a release that never completed activation is not a release you may browse")
                .isInstanceOf(ConsumerFailures.NotFound.class);
    }

    // ---------- a broken pointer is an infrastructure fault, never a guess ----------

    @Test @org.junit.jupiter.api.Order(4)
    void a_missing_pointer_is_unavailable_and_never_falls_back_to_some_active_release() {
        Document saved = db.getCollection("system_config")
                .find(eq("_id", TaxonomyChangeService.CONSUMER_RELEASE_POINTER)).first();
        db.getCollection("system_config")
                .deleteOne(eq("_id", TaxonomyChangeService.CONSUMER_RELEASE_POINTER));
        try {
            assertThat(db.getCollection("catalogue_releases").countDocuments(eq("status", "active")))
                    .as("active releases DO exist — the resolver still must not pick one")
                    .isGreaterThan(0);
            assertThatThrownBy(() -> resolver.resolve(null))
                    .isInstanceOf(ConsumerFailures.Unavailable.class);
        } finally {
            db.getCollection("system_config").insertOne(saved);
        }
    }

    @Test @org.junit.jupiter.api.Order(5)
    void a_pointer_naming_a_non_active_release_is_unavailable() {
        String saved = pointer();
        db.getCollection("system_config").updateOne(
                eq("_id", TaxonomyChangeService.CONSUMER_RELEASE_POINTER),
                new Document("$set", new Document("release_id", "R-PUBLISHING")));
        try {
            assertThatThrownBy(() -> resolver.resolve(null))
                    .as("a pointer to an unusable release is a fault, not a reason to improvise")
                    .isInstanceOf(ConsumerFailures.Unavailable.class);
        } finally {
            db.getCollection("system_config").updateOne(
                    eq("_id", TaxonomyChangeService.CONSUMER_RELEASE_POINTER),
                    new Document("$set", new Document("release_id", saved)));
        }
    }

    @Test @org.junit.jupiter.api.Order(6)
    void an_empty_pointer_value_is_unavailable() {
        String saved = pointer();
        db.getCollection("system_config").updateOne(
                eq("_id", TaxonomyChangeService.CONSUMER_RELEASE_POINTER),
                new Document("$set", new Document("release_id", "")));
        try {
            assertThatThrownBy(() -> resolver.resolve(null))
                    .isInstanceOf(ConsumerFailures.Unavailable.class);
        } finally {
            db.getCollection("system_config").updateOne(
                    eq("_id", TaxonomyChangeService.CONSUMER_RELEASE_POINTER),
                    new Document("$set", new Document("release_id", saved)));
        }
    }
}
