package com.tazzzo.catalog;

import com.tazzzo.catalog.migration.IndexCatalog;
import com.tazzzo.catalog.migration.IndexSpec;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rebuild-queue gauges' "oldest due item" lookups (migration V0018) on a real MongoDB with a large backlog: each is an
 * ordered IXSCAN of its own partial index, with no SORT stage and no fetch of the backlog. Dropping an index spec from the
 * catalog makes this test fail (the plan falls back to the (status, type) index plus an in-memory sort).
 */
class WorkQueueRebuildIndexIT extends AbstractMongoIT {

    static final int BACKLOG = 4000;

    @BeforeAll
    void seed() {
        db.getCollection("work_queue").drop();
        db.getCollection("work_queue").createIndex(new Document("status", 1).append("type", 1));
        for (IndexSpec spec : IndexCatalog.WORK_QUEUE_REBUILD_SPECS) spec.create(db);
        long base = System.currentTimeMillis() - 3_600_000L;
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < BACKLOG; i++) {
            docs.add(new Document("_id", "card_rebuild:p" + i).append("type", "product_card_rebuild").append("status", "pending")
                    .append("requested_at", new Date(base + i * 7L)));
            docs.add(new Document("_id", "card_rebuild:l" + i).append("type", "product_card_rebuild").append("status", "leased")
                    .append("lease_until", new Date(base + i * 5L)).append("requested_at", new Date(base)));
            if (i % 8 == 0) {
                docs.add(new Document("_id", "other:" + i).append("type", "merge").append("status", "pending")
                        .append("requested_at", new Date(base - 100_000L)));
            }
        }
        db.getCollection("work_queue").insertMany(docs);
    }

    private Document explain(Document filter, Document sort, String field) {
        Document find = new Document("find", "work_queue").append("filter", filter).append("sort", sort)
                .append("projection", new Document(field, 1)).append("limit", 1);
        return db.runCommand(new Document("explain", find).append("verbosity", "executionStats"));
    }

    private void assertOrderedIndexWalk(Document explain, String index) {
        String plan = explain.get("queryPlanner", Document.class).get("winningPlan").toString();
        assertThat(plan).contains("IXSCAN").contains(index);
        assertThat(plan).as("no in-memory sort of the backlog").doesNotContain("SORT");
        Document stats = explain.get("executionStats", Document.class);
        assertThat(((Number) stats.get("totalDocsExamined")).longValue()).as("fetches at most the one row it returns").isLessThanOrEqualTo(2);
        assertThat(((Number) stats.get("totalKeysExamined")).longValue()).as("walks a handful of keys, not the backlog").isLessThanOrEqualTo(5);
        assertThat(stats.get("nReturned", Number.class).intValue()).isEqualTo(1);
    }

    @Test
    void the_oldest_pending_lookup_walks_its_partial_index() {
        assertOrderedIndexWalk(explain(new Document("type", "product_card_rebuild").append("status", "pending"),
                new Document("requested_at", 1), "requested_at"), "projection_rebuild_pending_by_requested");
    }

    @Test
    void the_oldest_lapsed_lease_lookup_walks_its_partial_index() {
        Document filter = new Document("type", "product_card_rebuild").append("status", "leased")
                .append("lease_until", new Document("$lte", new Date()));
        assertOrderedIndexWalk(explain(filter, new Document("lease_until", 1), "lease_until"), "projection_rebuild_leased_by_lease");
    }

    @Test
    void the_catalog_declares_exactly_these_two_partial_indexes() {
        assertThat(IndexCatalog.WORK_QUEUE_REBUILD_SPECS).hasSize(2).allSatisfy(s -> {
            assertThat(s.collection()).isEqualTo("work_queue");
            assertThat(s.unique()).isFalse();
            assertThat(s.partialFilter()).isEqualTo(new Document("type", "product_card_rebuild"));
            assertThat(s.name().length()).isLessThan(80);
        });
        assertThat(IndexCatalog.MANAGED).containsAll(IndexCatalog.WORK_QUEUE_REBUILD_SPECS);
    }
}
