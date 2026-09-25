package com.tazzzo.catalog;

import com.tazzzo.catalog.domain.BundleComponent;
import com.tazzzo.catalog.tx.BundleService;
import com.tazzzo.catalog.tx.MergeService;
import com.tazzzo.catalog.tx.MintService;
import org.bson.Document;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * K-1: merge worker "crashes" after the core transaction commits (we simply do not run the
 * finalizer). The outbox must survive; the finalizer must resume idempotently.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MergeCrashIT extends AbstractMongoIT {

    @Autowired MintService mintService;
    @Autowired MergeService mergeService;
    @Autowired BundleService bundleService;

    private static final String LOSER = "TZP-M-LOSER";
    private static final String SURVIVOR = "TZP-M-SURV";
    private static final String THIRD = "TZP-M-THIRD";
    private static final String BUNDLE3 = "TZP-M-BUNDLE3";
    private static final String BUNDLE2 = "TZP-M-BUNDLE2";

    @Test @Order(1)
    void setup_products_offers_bundles() {
        mintService.mint(TestFixtures.internalSingle(LOSER, "k|loser"));
        mintService.mint(TestFixtures.internalSingle(SURVIVOR, "k|surv"));
        mintService.mint(TestFixtures.internalSingle(THIRD, "k|third"));
        bundleService.activate(LOSER);
        bundleService.activate(SURVIVOR);
        bundleService.activate(THIRD);
        // loser-only offer (should repoint) + colliding offer (survivor-wins delete)
        db.getCollection("offers_current").insertOne(offer(LOSER, "tazzzo", "S1", "retail", 100));
        db.getCollection("offers_current").insertOne(offer(LOSER, "tazzzo", "S2", "retail", 110));
        db.getCollection("offers_current").insertOne(offer(SURVIVOR, "tazzzo", "S2", "retail", 120));
        // 3-component bundle -> normal repoint; 2-component {loser,survivor} -> Law-4 fail-closed
        bundleService.writeBundle(TestFixtures.bundle(BUNDLE3, List.of(
                new BundleComponent(LOSER, 1, null), new BundleComponent(SURVIVOR, 1, null),
                new BundleComponent(THIRD, 2, null))));
        bundleService.writeBundle(TestFixtures.bundle(BUNDLE2, List.of(
                new BundleComponent(LOSER, 1, null), new BundleComponent(SURVIVOR, 1, null))));
    }

    @Test @Order(2)
    void crash_window_state_is_failclosed_and_outbox_survives() {
        mergeService.startMerge(LOSER, SURVIVOR);
        // "crash": finalizer never runs
        assertThat(product(LOSER).getString("lifecycle")).isEqualTo("merging");
        assertThat(product(SURVIVOR).getString("lifecycle")).isEqualTo("merging");
        Document outbox = db.getCollection("work_queue")
                .find(eq("_id", "merge:" + LOSER + ":" + SURVIVOR)).first();
        assertThat(outbox).as("outbox item written INSIDE the core txn must survive").isNotNull();
        assertThat(outbox.getString("status")).isEqualTo("pending");
        assertThat(db.getCollection("offers_current").countDocuments(eq("product_id", LOSER)))
                .as("offers not yet repointed — safe stall, not corruption").isEqualTo(2);
        assertThat(db.getCollection("identity_keys").find(eq("_id", "k|loser")).first()
                .getString("status")).isEqualTo("redirected");
    }

    @Test @Order(3)
    void finalizer_resumes_and_completes() {
        mergeService.runFinalizer();
        assertThat(product(LOSER).getString("lifecycle")).isEqualTo("merged");
        assertThat(product(LOSER).getString("merged_into")).isEqualTo(SURVIVOR);
        assertThat(product(SURVIVOR).getString("lifecycle")).isEqualTo("active");
        // offers: loser-only repointed; colliding one resolved survivor-wins
        assertThat(db.getCollection("offers_current").countDocuments(eq("product_id", LOSER))).isZero();
        assertThat(db.getCollection("offers_current").countDocuments(
                and(eq("product_id", SURVIVOR), eq("seller", "S1")))).isEqualTo(1);
        assertThat(db.getCollection("offers_current").countDocuments(
                and(eq("product_id", SURVIVOR), eq("seller", "S2")))).isEqualTo(1);
        assertThat(db.getCollection("offers_current").find(
                and(eq("product_id", SURVIVOR), eq("seller", "S2"))).first().getInteger("price"))
                .as("survivor's own row wins (M4)").isEqualTo(120);
        // 3-bundle rewritten with qty-merge: loser->survivor merged into one entry qty 2
        List<Document> contents3 = product(BUNDLE3).getList("bundle_contents", Document.class);
        assertThat(contents3).hasSize(2);
        Document survComp = contents3.stream()
                .filter(c -> c.getString("component_product_id").equals(SURVIVOR)).findFirst().orElseThrow();
        assertThat(survComp.getInteger("qty")).isEqualTo(2);
        // 2-bundle would collapse below minItems -> Law-4 fail-closed work item, bundle untouched
        assertThat(db.getCollection("work_queue").find(and(eq("type", "bundle_integrity"),
                eq("bundle_id", BUNDLE2))).first()).isNotNull();
        assertThat(product(BUNDLE2).getList("bundle_contents", Document.class)).hasSize(2);
        assertThat(db.getCollection("work_queue")
                .find(eq("_id", "merge:" + LOSER + ":" + SURVIVOR)).first().getString("status"))
                .isEqualTo("completed");
        assertThat(db.getCollection("product_events").countDocuments(
                and(eq("type", "MERGE_COMPLETED"), eq("product_id", LOSER)))).isGreaterThanOrEqualTo(1);
    }

    @Test @Order(4)
    void finalizer_rerun_is_idempotent() {
        String before = snapshot();
        mergeService.runFinalizer();
        assertThat(snapshot()).isEqualTo(before);
    }

    private Document product(String id) {
        return db.getCollection("products").find(eq("_id", id)).first();
    }

    private Document offer(String productId, String source, String seller, String channel, int price) {
        return new Document("product_id", productId).append("source", source)
                .append("seller", seller).append("channel", channel)
                .append("price", price).append("available", true).append("last_seen_at", new Date());
    }

    private String snapshot() {
        List<String> s = new ArrayList<>();
        db.getCollection("products").find().forEach(d -> s.add(d.toJson()));
        db.getCollection("offers_current").find().forEach(d -> s.add(d.toJson()));
        s.add("work_queue_count=" + db.getCollection("work_queue").countDocuments());
        s.add("event_count=" + db.getCollection("product_events").countDocuments());
        s.sort(String::compareTo);
        return String.join("|", s);
    }
}
