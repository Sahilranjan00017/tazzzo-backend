package com.tazzzo.catalog;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.ops.CatalogSchedulers;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.*;
import org.awaitility.Awaitility;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes F5: proves the workers are actually INVOKED by the scheduler in a running context,
 * and that scheduled (repeated, possibly overlapping) execution stays safe.
 * Fast intervals so the test does not wait on production cadence.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class SchedulerIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static { MONGO.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_sched_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "true");     // scheduler ON for this suite
        // Q5-c: the limiter mode has NO production default, so every Spring context must state
        // it. DISABLED is the fail-closed state; these suites exercise no consumer surface.
        r.add("tazzzo.consumer-rate-limit.mode", () -> "DISABLED");
        r.add("tazzzo.scheduler.merge-finalizer-ms", () -> "300");
        r.add("tazzzo.scheduler.taint-ms", () -> "300");
        r.add("tazzzo.scheduler.stamp-ms", () -> "300");
        r.add("tazzzo.scheduler.rollup-ms", () -> "300");
        r.add("tazzzo.scheduler.rollup-lag-seconds", () -> "0");
    }

    @Autowired MongoClient client;
    @Autowired MongoDatabase db;
    @Autowired SchemaBootstrap schemaBootstrap;
    @Autowired TaxonomyLoader loader;
    @Autowired MintService mintService;
    @Autowired ProductLifecycleService lifecycle;
    @Autowired MergeService mergeService;
    @Autowired TaintService taintService;
    @Autowired OffersService offersService;
    @Autowired EvidenceService evidenceService;
    @Autowired PublishService publishService;
    @Autowired TaxonomyChangeService changes;
    @Autowired CatalogSchedulers schedulers;   // fails to inject if the bean is not wired

    static final String BASMATI = "TZV-000001";

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("0.9.0");
    }

    private void mint(String id, String key) {
        mintService.mint(new ProductDraft(id, "single", "internal", key, null, "BR-SCH",
                "Sched " + id, BASMATI, "0.9.0", "provisional",
                Map.of("pack_size", 1, "pack_unit", "kg"), List.of(), null));
    }

    private int version(String id) {
        return db.getCollection("products").find(eq("_id", id)).first().getInteger("version");
    }

    @Test @org.junit.jupiter.api.Order(1)
    void scheduler_bean_is_wired() {
        assertThat(schedulers).as("F5: the scheduler bean must exist in the running context")
                .isNotNull();
    }

    @Test @org.junit.jupiter.api.Order(2)
    void merge_finalizes_without_anyone_calling_the_worker() {
        mint("TZP-SCH-1", "sch|1");
        mint("TZP-SCH-2", "sch|2");
        lifecycle.activate("TZP-SCH-1", version("TZP-SCH-1"));
        lifecycle.activate("TZP-SCH-2", version("TZP-SCH-2"));
        mergeService.startMerge("TZP-SCH-1", "TZP-SCH-2");
        assertThat(db.getCollection("products").find(eq("_id", "TZP-SCH-1")).first()
                .getString("lifecycle")).isEqualTo("merging");

        // NOTHING calls runFinalizer() here — the scheduler must do it
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Document loser = db.getCollection("products").find(eq("_id", "TZP-SCH-1")).first();
                    assertThat(loser.getString("lifecycle")).isEqualTo("merged");
                    assertThat(loser.getString("merged_into")).isEqualTo("TZP-SCH-2");
                });
        assertThat(db.getCollection("work_queue")
                .find(eq("_id", "merge:TZP-SCH-1:TZP-SCH-2")).first().getString("status"))
                .isEqualTo("completed");
    }

    @Test @org.junit.jupiter.api.Order(3)
    void repeated_ticks_are_idempotent_no_duplicate_events_or_mutations() throws Exception {
        Thread.sleep(1500);   // several scheduler ticks over already-completed work
        long events = db.getCollection("product_events")
                .countDocuments(and(eq("type", "MERGE_COMPLETED"), eq("product_id", "TZP-SCH-1")));
        long completed = db.getCollection("work_queue")
                .countDocuments(and(eq("type", "merge_repoint"), eq("status", "completed")));
        Thread.sleep(1500);   // more ticks
        assertThat(db.getCollection("product_events").countDocuments(
                and(eq("type", "MERGE_COMPLETED"), eq("product_id", "TZP-SCH-1"))))
                .as("repeated scheduled runs must not re-emit events").isEqualTo(events);
        assertThat(db.getCollection("work_queue").countDocuments(
                and(eq("type", "merge_repoint"), eq("status", "completed")))).isEqualTo(completed);
        assertThat(db.getCollection("products").find(eq("_id", "TZP-SCH-1")).first()
                .getString("lifecycle")).isEqualTo("merged");
    }

    @Test @org.junit.jupiter.api.Order(4)
    void taint_cascade_is_processed_by_the_scheduler() {
        mint("TZP-SCH-EV", "sch|ev");
        evidenceService.create("EV-SCH", "lab_report", "supplier", null, null, "x", null, null);
        publishService.publishClaim("TZP-SCH-EV", "sched_claim", List.of("EV-SCH"));
        taintService.retractEvidence("EV-SCH", "retracted");
        // no manual runTaintWorker call
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(db.getCollection("work_queue")
                        .find(eq("_id", "attr_reval:EV-SCH:TZP-SCH-EV")).first()).isNotNull());
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(db.getCollection("work_queue")
                        .find(eq("_id", "taint:EV-SCH")).first().getString("status"))
                        .isEqualTo("completed"));
    }

    @Test @org.junit.jupiter.api.Order(5)
    void taxonomy_stamp_work_is_processed_by_the_scheduler() {
        mint("TZP-SCH-TX", "sch|tx");
        changes.openRelease("sched-1.0", "0.9.0");
        Document leaf = db.getCollection("taxonomy_nodes").find(and(
                eq("node_type", "vertical"), eq("status", "active"), eq("_id", BASMATI))).first();
        changes.moveNode(BASMATI, leaf.getInteger("version"), "TZG-000002");   // enqueues a stamp scan
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(db.getCollection("work_queue").find(and(
                        eq("_id", "stamp_scan:attribute_revalidation:" + BASMATI + ":sched-1.0"),
                        eq("status", "completed"))).first())
                        .as("the specific scan enqueued by this move was completed by the scheduler")
                        .isNotNull());
        changes.activateRelease("sched-1.0");
    }

    @Test @org.junit.jupiter.api.Order(6)
    void price_rollup_runs_and_purges_only_rolled_events() {
        offersService.upsertOffer("TZP-SCH-2", "tazzzo", "S1", "retail", 100, true);
        offersService.upsertOffer("TZP-SCH-2", "tazzzo", "S1", "retail", 110, true);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(db.getCollection("price_rollups")
                        .find(and(eq("product_id", "TZP-SCH-2"), eq("seller", "S1"))).first())
                        .isNotNull());
        // current state survives; only rolled ledger rows are purged
        assertThat(db.getCollection("offers_current").find(eq("product_id", "TZP-SCH-2")).first()
                .getInteger("price")).isEqualTo(110);
    }

    /**
     * SCHED-FLAKE-1 (2026-09-08). This suite deliberately runs the REAL scheduler with a 300 ms
     * merge-finalizer cadence, so there are not four contenders for the lease but FIVE: the four
     * threads below plus Spring's scheduled invocation of the same {@code runFinalizer()}.
     *
     * <p>The original assertion waited only on the four tracked Futures and then read the product
     * immediately. When the SCHEDULED thread won the lease, all four manual runs correctly saw the
     * item already leased and returned instantly, no Future threw, and the assertion could observe
     * {@code lifecycle == "merging"} while the scheduler was still mid-transaction. That is a test
     * synchronisation defect, not a merge-correctness defect: the finalizer's own mutation is
     * transactional.
     *
     * <p>The fix is neither a sleep nor disabling the scheduler — overlapping execution is exactly
     * what this test exists to prove. It awaits the INVARIANT instead, so the test proves
     * "whoever claims it, the work happens once and completes" rather than the weaker and
     * never-guaranteed "one of MY four threads owns the lease".
     */
    @Test @org.junit.jupiter.api.Order(7)
    void concurrent_worker_invocations_do_not_duplicate_work() throws Exception {
        // simulate two instances ticking at once: leases must serialize them
        mint("TZP-SCH-C1", "sch|c1");
        mint("TZP-SCH-C2", "sch|c2");
        lifecycle.activate("TZP-SCH-C1", version("TZP-SCH-C1"));
        lifecycle.activate("TZP-SCH-C2", version("TZP-SCH-C2"));
        mergeService.startMerge("TZP-SCH-C1", "TZP-SCH-C2");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                try { go.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                mergeService.runFinalizer();   // exceptions propagate into the Future (M4)
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();   // M4: a swallowed crash must fail the test, not be masked by the scheduler
        }
        // NOTE: event granularity is per-WRITE (documented accepted debt), so one finalize
        // emits several MERGE_COMPLETED rows. The invariant that matters is that concurrent
        // runs perform the work ONCE: the outbox item completes once, the lifecycle flips
        // once, and further runs add nothing.
        //
        // Awaited, not asserted immediately: the winner may be the SCHEDULED invocation, which
        // no Future tracks. Completion is the invariant; lease ownership is not.
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(db.getCollection("products").find(eq("_id", "TZP-SCH-C1")).first()
                            .getString("lifecycle")).isEqualTo("merged");
                    assertThat(db.getCollection("work_queue").countDocuments(and(
                            eq("_id", "merge:TZP-SCH-C1:TZP-SCH-C2"), eq("status", "completed"))))
                            .as("outbox processed exactly once despite concurrent runs")
                            .isEqualTo(1);
                });
        long eventsAfterConcurrent = db.getCollection("product_events").countDocuments(and(
                eq("type", "MERGE_COMPLETED"), eq("product_id", "TZP-SCH-C1")));
        mergeService.runFinalizer();
        mergeService.runFinalizer();
        assertThat(db.getCollection("product_events").countDocuments(and(
                eq("type", "MERGE_COMPLETED"), eq("product_id", "TZP-SCH-C1"))))
                .as("further runs are a true no-op — no duplicate mutations or events")
                .isEqualTo(eventsAfterConcurrent);
    }
}
