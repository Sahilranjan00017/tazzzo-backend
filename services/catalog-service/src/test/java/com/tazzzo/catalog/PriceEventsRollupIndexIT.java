package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.migration.IndexCatalog;
import com.tazzzo.catalog.migration.IndexSpec;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hourly price rollup (migration V0019) on a real MongoDB with a ledger dominated by paise-shape price changes: the scan of
 * the not-yet-rolled LEGACY offer events is an IXSCAN of the partial index that holds only legacy-shape events (with a small in-memory SORT bounded by the unrolled events), so it reads
 * exactly the unrolled legacy events and none of the paise rows (before V0019 it read the whole ledger through
 * {@code product_id_1_ts_1}). The index returns the same rows as a collection scan. Removing the spec from the catalog fails this test.
 */
class PriceEventsRollupIndexIT extends AbstractMongoIT {

    static final int PAISE_ROWS = 20_000;
    static final int LEGACY_UNROLLED = 400;
    static final int LEGACY_ROLLED = 400;

    /** The filter of {@code RollupService.rollup(upTo)}, verbatim. */
    private static Bson rollupFilter(Date upTo) {
        return Filters.and(Filters.lte("ts", upTo), Filters.ne("rolled", true),
                Filters.and(Filters.type("product_id", BsonType.STRING), Filters.type("seller", BsonType.STRING),
                        Filters.type("price", BsonType.INT32)));
    }

    @BeforeAll
    void seed() {
        db.getCollection("price_events").drop();
        db.getCollection("price_events").createIndex(new Document("product_id", 1).append("ts", 1));
        db.getCollection("price_events").createIndex(new Document("rolled", 1).append("ts", 1));
        IndexCatalog.PRICE_EVENTS_LEGACY_UNROLLED_SPEC.create(db);
        long base = System.currentTimeMillis() - 86_400_000L;
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < PAISE_ROWS; i++) {
            docs.add(new Document("product_id", "TZP-" + (i % 5000)).append("sku_id", "TZP-" + (i % 5000)).append("currency", "INR")
                    .append("selling_price_paise", 1000L + i).append("mrp_paise", 1200L + i).append("version", 1L + i)
                    .append("ts", new Date(base + i * 3L)));
        }
        for (int i = 0; i < LEGACY_UNROLLED + LEGACY_ROLLED; i++) {
            Document e = new Document("product_id", "TZP-" + (i % 5000)).append("source", "S1").append("seller", "SELLER-" + (i % 7))
                    .append("channel", "web").append("price", 100 + i).append("ts", new Date(base + i * 5L));
            if (i >= LEGACY_UNROLLED) e.append("rolled", true);
            docs.add(e);
        }
        // a legacy-looking row with a non-int32 price and one with no seller are not legacy offer events: never in the index
        docs.add(new Document("product_id", "TZP-1").append("seller", "S").append("price", 1.5d).append("ts", new Date(base)));
        docs.add(new Document("product_id", "TZP-2").append("price", 5).append("ts", new Date(base)));
        db.getCollection("price_events").insertMany(docs);
    }

    private Document explain(Bson hint) {
        Document find = new Document("find", "price_events")
                .append("filter", rollupFilter(new Date()).toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry()))
                .append("sort", new Document("ts", 1).append("_id", 1));
        if (hint != null) find.append("hint", hint);
        return db.runCommand(new Document("explain", find).append("verbosity", "executionStats"));
    }

    @Test
    void the_rollup_scan_reads_only_the_unrolled_legacy_events_through_the_partial_index() {
        Document ex = explain(null);
        String plan = ex.get("queryPlanner", Document.class).get("winningPlan").toString();
        assertThat(plan).contains("IXSCAN").contains("price_events_legacy_unrolled");
        assertThat(plan).as("never a collection scan of the ledger").doesNotContain("COLLSCAN");
        Document stats = ex.get("executionStats", Document.class);
        assertThat(stats.get("nReturned", Number.class).intValue()).isEqualTo(LEGACY_UNROLLED);
        assertThat(((Number) stats.get("totalDocsExamined")).longValue()).as("fetches exactly the rows it returns").isEqualTo(LEGACY_UNROLLED);
        assertThat(((Number) stats.get("totalKeysExamined")).longValue()).as("walks the unrolled keys, not the %d paise rows", PAISE_ROWS)
                .isLessThanOrEqualTo(LEGACY_UNROLLED + 5);
    }

    @Test
    void the_partial_index_returns_the_same_rows_as_a_collection_scan() {
        List<Object> viaIndex = db.getCollection("price_events").find(rollupFilter(new Date()))
                .hint(new Document("rolled", 1).append("ts", 1).append("_id", 1)).sort(new Document("ts", 1).append("_id", 1))
                .map(d -> d.get("_id")).into(new ArrayList<>());
        List<Object> viaScan = db.getCollection("price_events").find(rollupFilter(new Date()))
                .hint(new Document("$natural", 1)).sort(new Document("ts", 1).append("_id", 1))
                .map(d -> d.get("_id")).into(new ArrayList<>());
        assertThat(viaIndex).hasSize(LEGACY_UNROLLED).isEqualTo(viaScan);
        assertThat(explain(new Document("$natural", 1)).get("executionStats", Document.class).get("totalDocsExamined", Number.class).longValue())
                .as("without the index the same query reads the whole ledger").isGreaterThan(PAISE_ROWS);
    }

    @Test
    void the_catalog_declares_exactly_this_partial_index_and_it_is_not_a_ttl() {
        IndexSpec s = IndexCatalog.PRICE_EVENTS_LEGACY_UNROLLED_SPEC;
        assertThat(s.collection()).isEqualTo("price_events");
        assertThat(s.name()).isEqualTo("price_events_legacy_unrolled");
        assertThat(s.unique()).isFalse();
        assertThat(s.ttlSeconds()).as("price_events is the retained price ledger: never a TTL (R1)").isNull();
        assertThat(new ArrayList<>(s.keys().entrySet()).stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.toList()))
                .containsExactly("rolled:1", "ts:1", "_id:1");
        assertThat(s.partialFilter()).isEqualTo(new Document("product_id", new Document("$type", "string"))
                .append("seller", new Document("$type", "string")).append("price", new Document("$type", "int")));
        assertThat(IndexCatalog.MANAGED).contains(s);
        assertThat(IndexCatalog.BASELINE).doesNotContain(s);
    }
}
