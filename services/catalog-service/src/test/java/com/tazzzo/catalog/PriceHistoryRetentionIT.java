package com.tazzzo.catalog;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.OffersService;
import com.tazzzo.catalog.tx.RollupService;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceStatus;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import org.bson.BsonInt64;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R1: PRICE HISTORY IS RETAINED. The price roll-up used to flag legacy offer events as rolled and then a purge hard-deleted
 * every flagged row hourly, and paise-ledger rows (which have no seller and no price) were swept into the same path. These
 * tests run the real writers and the real roll-up against MongoDB 7 and require that no run, however it ends, reduces the
 * ledger; that paise rows are never touched or aggregated; and that the roll-up stays exactly-once and restart-safe now
 * that deletion is no longer what prevents re-processing.
 */
class PriceHistoryRetentionIT extends AbstractMongoIT {

    @Autowired OffersService offers;

    private RollupService rollup() {
        return new RollupService(new Tx(client), new WritePath(db));
    }

    private PricingService pricing() {
        return new PricingService(new Tx(client), new WritePath(db), Clock.systemUTC());
    }

    private static final Date T0 = new Date(1_790_000_000_000L);

    private static Date at(long seconds) {
        return new Date(T0.getTime() + seconds * 1000);
    }

    /** A legacy offer event exactly as OffersService writes it (int32 price), with a chosen timestamp. */
    private void legacy(String product, String seller, int price, Date ts) {
        db.getCollection("price_events").insertOne(new Document("product_id", product).append("source", "crawler")
                .append("seller", seller).append("channel", "web").append("price", price).append("ts", ts));
    }

    private long paise(String sku, long selling, long mrp, Long expectedVersion) {
        return pricing().upsertPrice(new UpsertPriceCommand(sku, selling, mrp, Currency.INR, null, null, "seed", expectedVersion),
                TestActors.TEST); // attributed overload: no new use of the unattributed one
    }

    private List<Document> events(Bson filter) {
        return db.getCollection("price_events").find(filter).sort(new Document("_id", 1)).into(new ArrayList<>());
    }

    private long count(Bson filter) {
        return db.getCollection("price_events").countDocuments(filter);
    }

    private Document rollupDoc(String product, String seller) {
        return db.getCollection("price_rollups").find(and(eq("product_id", product), eq("seller", seller))).first();
    }

    // ---- the exact R1 case ----

    @Test
    void the_r1_case_a_paise_ledger_survives_the_rollup_untouched_and_emits_no_null_aggregate() {
        long v1 = paise("TZP-R1-PAISE", 10_000L, 12_000L, null);
        paise("TZP-R1-PAISE", 9_000L, 12_000L, v1);
        legacy("TZP-R1-LEG", "S1", 120, at(1));
        legacy("TZP-R1-LEG", "S1", 100, at(2));
        List<Document> paiseBefore = events(eq("sku_id", "TZP-R1-PAISE"));
        assertThat(paiseBefore).hasSize(2);

        RollupService svc = rollup();
        svc.rollup(new Date());

        assertThat(events(eq("sku_id", "TZP-R1-PAISE"))).as("paise history: byte-identical, never flagged, never deleted").isEqualTo(paiseBefore);
        assertThat(paiseBefore).allSatisfy(d -> assertThat(d).doesNotContainKey("rolled"));
        assertThat(rollupDoc("TZP-R1-PAISE", null)).as("no derived row for a paise sku").isNull();
        assertThat(db.getCollection("price_rollups").countDocuments(eq("product_id", "TZP-R1-PAISE"))).isZero();
        assertThat(db.getCollection("price_rollups").countDocuments(new Document("min_price", null))).as("no null aggregate anywhere").isZero();
        assertThat(db.getCollection("price_rollups").countDocuments(new Document("min_price", new Document("$exists", false)))).isZero();
        Document legacyAgg = rollupDoc("TZP-R1-LEG", "S1");
        assertThat(legacyAgg.getInteger("min_price")).isEqualTo(100);
        assertThat(legacyAgg.getInteger("max_price")).isEqualTo(120);
        assertThat(legacyAgg.getInteger("count")).isEqualTo(2);
    }

    @Test
    void no_purge_or_delete_capability_remains_on_the_rollup_service() {
        // "then any purge/cleanup method still present": there is none to call
        List<String> names = Arrays.stream(RollupService.class.getDeclaredMethods()).map(Method::getName).toList();
        assertThat(names).noneMatch(n -> n.toLowerCase().contains("purge") || n.toLowerCase().contains("delete")
                || n.toLowerCase().contains("clean"));
    }

    @Test
    void every_legacy_event_is_retained_and_flagged_as_rolled() {
        for (int i = 0; i < 5; i++) {
            offers.upsertOffer("TZP-R1-RET", "tazzzo", "S1", "retail", 100 + i, true); // the real legacy writer
        }
        assertThat(count(eq("product_id", "TZP-R1-RET"))).isEqualTo(5);
        rollup().rollup(new Date());
        assertThat(count(eq("product_id", "TZP-R1-RET"))).as("history count not decreased").isEqualTo(5);
        assertThat(count(and(eq("product_id", "TZP-R1-RET"), eq("rolled", true)))).isEqualTo(5);
        assertThat(rollupDoc("TZP-R1-RET", "S1").getInteger("count")).isEqualTo(5);
    }

    // ---- aggregation correctness: several events, products, sellers ----

    @Test
    void several_changes_products_and_sellers_aggregate_separately() {
        legacy("TZP-R1-A", "S1", 100, at(1));
        legacy("TZP-R1-A", "S1", 90, at(2));
        legacy("TZP-R1-A", "S1", 120, at(3));
        legacy("TZP-R1-A", "S2", 50, at(4));
        legacy("TZP-R1-B", "S1", 7, at(5));
        rollup().rollup(new Date());
        assertThat(rollupDoc("TZP-R1-A", "S1").getInteger("min_price")).isEqualTo(90);
        assertThat(rollupDoc("TZP-R1-A", "S1").getInteger("max_price")).isEqualTo(120);
        assertThat(rollupDoc("TZP-R1-A", "S1").getInteger("count")).isEqualTo(3);
        assertThat(rollupDoc("TZP-R1-A", "S2").getInteger("count")).isEqualTo(1);
        assertThat(rollupDoc("TZP-R1-B", "S1").getInteger("min_price")).isEqualTo(7);
        assertThat(count(in("product_id", "TZP-R1-A", "TZP-R1-B"))).isEqualTo(5);
    }

    // ---- idempotency, restart, no reprocessing ----

    @Test
    void a_second_run_with_no_new_events_changes_nothing_and_writes_nothing() {
        legacy("TZP-R1-IDEM", "S1", 100, at(1));
        legacy("TZP-R1-IDEM", "S1", 110, at(2));
        rollup().rollup(new Date());
        List<Document> eventsAfterFirst = events(eq("product_id", "TZP-R1-IDEM"));
        Document aggAfterFirst = rollupDoc("TZP-R1-IDEM", "S1");
        long rollupEventsAfterFirst = db.getCollection("product_events").countDocuments(eq("type", "PRICE_ROLLUP"));

        rollup().rollup(new Date());
        rollup().rollup(new Date());

        assertThat(rollupDoc("TZP-R1-IDEM", "S1")).as("no double count").isEqualTo(aggAfterFirst);
        assertThat(events(eq("product_id", "TZP-R1-IDEM"))).as("history not mutated again").isEqualTo(eventsAfterFirst);
        assertThat(db.getCollection("product_events").countDocuments(eq("type", "PRICE_ROLLUP")))
                .as("a no-op run re-processes nothing: not even an audit event is appended").isEqualTo(rollupEventsAfterFirst);
    }

    @Test
    void a_new_event_after_a_completed_rollup_adds_only_that_event() {
        legacy("TZP-R1-NEW", "S1", 100, at(1));
        legacy("TZP-R1-NEW", "S1", 110, at(2));
        rollup().rollup(new Date());
        legacy("TZP-R1-NEW", "S1", 80, at(3));
        rollup().rollup(new Date());
        Document agg = rollupDoc("TZP-R1-NEW", "S1");
        assertThat(agg.getInteger("count")).as("exactly one more, the earlier two not re-counted").isEqualTo(3);
        assertThat(agg.getInteger("min_price")).isEqualTo(80);
        assertThat(agg.getInteger("max_price")).isEqualTo(110);
        assertThat(count(eq("product_id", "TZP-R1-NEW"))).isEqualTo(3);
    }

    @Test
    void an_out_of_order_older_event_is_still_processed_exactly_once_and_a_future_one_waits() {
        legacy("TZP-R1-OOO", "S1", 100, at(100));
        rollup().rollup(new Date(T0.getTime() + 200_000));
        legacy("TZP-R1-OOO", "S1", 60, at(10));           // committed late, with an OLDER timestamp
        legacy("TZP-R1-OOO", "S1", 500, at(900));          // after the next boundary
        rollup().rollup(new Date(T0.getTime() + 300_000));
        assertThat(rollupDoc("TZP-R1-OOO", "S1").getInteger("count")).isEqualTo(2);
        assertThat(rollupDoc("TZP-R1-OOO", "S1").getInteger("min_price")).isEqualTo(60);
        assertThat(rollupDoc("TZP-R1-OOO", "S1").getInteger("max_price")).as("the post-boundary event is not applied yet").isEqualTo(100);
        rollup().rollup(new Date(T0.getTime() + 1_000_000));
        assertThat(rollupDoc("TZP-R1-OOO", "S1").getInteger("count")).isEqualTo(3);
        rollup().rollup(new Date(T0.getTime() + 1_000_000));
        assertThat(rollupDoc("TZP-R1-OOO", "S1").getInteger("count")).as("and then it is not applied twice").isEqualTo(3);
        assertThat(count(eq("product_id", "TZP-R1-OOO"))).isEqualTo(3);
    }

    @Test
    void a_restart_resumes_from_the_stored_state_with_no_reprocessing() {
        legacy("TZP-R1-RST", "S1", 100, at(1));
        new RollupService(new Tx(client), new WritePath(db)).rollup(new Date()); // "before the restart"
        legacy("TZP-R1-RST", "S1", 90, at(2));
        new RollupService(new Tx(client), new WritePath(db)).rollup(new Date()); // a brand-new instance: all state is in the database
        assertThat(rollupDoc("TZP-R1-RST", "S1").getInteger("count")).isEqualTo(2);
        assertThat(rollupDoc("TZP-R1-RST", "S1").getInteger("min_price")).isEqualTo(90);
    }

    // ---- shapes that are not rolled up are neither read for aggregation nor modified ----

    @Test
    void malformed_and_unsupported_shapes_are_neither_aggregated_nor_modified() {
        db.getCollection("price_events").insertMany(List.of(
                new Document("product_id", "TZP-R1-MAL1").append("ts", at(1)),                                              // minimal fixture shape
                new Document("product_id", "TZP-R1-MAL2").append("seller", "S").append("price", new BsonInt64(5)).append("ts", at(2)), // int64
                new Document("product_id", "TZP-R1-MAL3").append("seller", null).append("price", 5).append("ts", at(3)),   // null seller
                new Document("product_id", "TZP-R1-MAL4").append("price", 5).append("ts", at(4)),                           // no seller
                new Document("product_id", "TZP-R1-MAL5").append("seller", "S").append("price", "12").append("ts", at(5)), // string price
                new Document("product_id", "TZP-R1-MAL6").append("seller", "S").append("price", 1.5d).append("ts", at(6)))); // double price
        List<Document> before = events(in("product_id", "TZP-R1-MAL1", "TZP-R1-MAL2", "TZP-R1-MAL3", "TZP-R1-MAL4", "TZP-R1-MAL5", "TZP-R1-MAL6"));
        rollup().rollup(new Date());
        assertThat(events(in("product_id", "TZP-R1-MAL1", "TZP-R1-MAL2", "TZP-R1-MAL3", "TZP-R1-MAL4", "TZP-R1-MAL5", "TZP-R1-MAL6")))
                .isEqualTo(before);
        assertThat(db.getCollection("price_rollups").countDocuments(new Document("product_id", new Document("$regex", "^TZP-R1-MAL")))).isZero();
    }

    // ---- failure safety ----

    /** A write path whose Nth derived-state write fails, to model "projection failed after the history was read". */
    static final class FailingWritePath extends WritePath {
        private final AtomicInteger rollupWrites = new AtomicInteger();
        private final int failOn;

        FailingWritePath(MongoDatabase db, int failOn) {
            super(db);
            this.failOn = failOn;
        }

        @Override
        public void auxWrite(ClientSession session, String collection, EventPayload event, Consumer<MongoCollection<Document>> write) {
            if ("price_rollups".equals(collection) && rollupWrites.incrementAndGet() == failOn) {
                throw new IllegalStateException("injected projection failure");
            }
            super.auxWrite(session, collection, event, write);
        }
    }

    @Test
    void a_projection_failure_leaves_the_whole_history_intact_and_a_retry_is_safe() {
        for (int i = 0; i < 4; i++) {
            legacy("TZP-R1-FAIL", "S1", 100 + i, at(i + 1));
        }
        long paiseV = paise("TZP-R1-FAIL-P", 5_000L, 6_000L, null);
        assertThat(paiseV).isEqualTo(1L);
        List<Document> before = events(in("product_id", "TZP-R1-FAIL", "TZP-R1-FAIL-P"));

        RollupService failing = new RollupService(new Tx(client), new FailingWritePath(db, 3));
        assertThatThrownBy(() -> failing.rollup(new Date())).hasStackTraceContaining("injected projection failure");

        assertThat(events(in("product_id", "TZP-R1-FAIL", "TZP-R1-FAIL-P"))).as("nothing deleted, nothing flagged (the transaction aborted)").isEqualTo(before);
        assertThat(db.getCollection("price_rollups").countDocuments(eq("product_id", "TZP-R1-FAIL"))).as("no partial projection").isZero();

        rollup().rollup(new Date()); // the retry
        assertThat(rollupDoc("TZP-R1-FAIL", "S1").getInteger("count")).isEqualTo(4);
        assertThat(count(eq("product_id", "TZP-R1-FAIL"))).isEqualTo(4);
        assertThat(count(eq("product_id", "TZP-R1-FAIL-P"))).isEqualTo(1);
    }

    // ---- concurrency ----

    @Test
    void overlapping_runs_never_double_count() throws Exception {
        int n = 40;
        for (int i = 0; i < n; i++) {
            legacy("TZP-R1-CONC", "S1", 100 + i, at(i + 1));
        }
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> runs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    rollup().rollup(new Date());
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : runs) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rollupDoc("TZP-R1-CONC", "S1").getInteger("count")).as("each event aggregated exactly once").isEqualTo(n);
        assertThat(count(and(eq("product_id", "TZP-R1-CONC"), eq("rolled", true)))).isEqualTo(n);
        assertThat(count(eq("product_id", "TZP-R1-CONC"))).as("history intact").isEqualTo(n);
        assertThat(db.getCollection("price_rollups").countDocuments(eq("product_id", "TZP-R1-CONC"))).as("no duplicate derived row").isEqualTo(1);
    }

    // ---- price_current and customer price reads are unaffected ----

    @Test
    void the_rollup_does_not_touch_price_current_or_customer_price_reads() {
        long v1 = paise("TZP-R1-CUR", 26_500L, 30_000L, null);
        paise("TZP-R1-CUR", 25_000L, 30_000L, v1);
        legacy("TZP-R1-CUR", "S1", 10, at(1));
        Document current = db.getCollection("price_current").find(eq("sku_id", "TZP-R1-CUR")).first();
        PriceLookup before = pricing().findCurrentPrice("TZP-R1-CUR");

        rollup().rollup(new Date());

        assertThat(db.getCollection("price_current").find(eq("sku_id", "TZP-R1-CUR")).first()).isEqualTo(current);
        PriceLookup after = pricing().findCurrentPrice("TZP-R1-CUR");
        assertThat(after.status()).isEqualTo(PriceStatus.ACTIVE).isEqualTo(before.status());
        assertThat(after.price().sellingPricePaise()).isEqualTo(25_000L).isEqualTo(before.price().sellingPricePaise());
        assertThat(after.price().discountAmountPaise()).isEqualTo(5_000L);
        assertThat(paise("TZP-R1-CUR", 24_000L, 30_000L, 2L)).as("CAS versioning still works after a rollup").isEqualTo(3L);
        assertThat(events(eq("sku_id", "TZP-R1-CUR"))).hasSize(3);
    }

    // ---- retention at the schema level ----

    @Test
    void price_events_has_no_ttl_or_expiry_index() {
        for (Document index : db.getCollection("price_events").listIndexes()) {
            assertThat(index).as("price_events index " + index.get("name")).doesNotContainKey("expireAfterSeconds");
        }
    }
}
