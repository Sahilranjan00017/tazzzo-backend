package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.bulkimport.ImportMetrics;
import com.tazzzo.bulkimport.ProductImportValidator;
import com.tazzzo.bulkimport.jobs.ImportJob;
import com.tazzzo.bulkimport.jobs.ImportJobGauges;
import com.tazzzo.bulkimport.jobs.ImportJobRepository;
import com.tazzzo.bulkimport.jobs.ImportJobService;
import com.tazzzo.bulkimport.jobs.ImportJobWorker;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.migration.MigrationRunner;
import com.tazzzo.catalog.migration.MigrationTarget;
import com.tazzzo.catalog.schema.AttributeGovernanceService;
import com.tazzzo.catalog.schema.CanonicalKeyService;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.TestActors;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import meters incrementing through the real flows: an async job CSV to VALIDATED to COMPLETED over HTTP with the worker
 * driven by hand, a pause on a datastore failure, a cancel taking the lease away, a genuine collision failing a row, the
 * synchronous price and product imports (dry run, applied, rejected), the backlog gauges against a real collection, and a
 * registry-wide proof that no tag value carries an id.
 */
@Timeout(300)
class ImportMetricsIT extends AbstractApiIT {

    static final String W = "cms-test-token";
    static final String JOBS = "/api/v1/admin/imports/jobs";
    static final String HEADER = ImportJobsIT.HEADER;

    @Autowired MeterRegistry meters;
    @Autowired ImportMetrics metrics;
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    @Autowired ImportJobWorker worker;
    @Autowired ImportJobRepository repo;
    @Autowired ImportJobService service;
    @Autowired AttributeGovernanceService governance;
    @Autowired CanonicalKeyService canonicalKeys;
    @Autowired MigrationRunner migrationRunner;
    @Autowired com.tazzzo.catalog.tx.Tx tx;
    @Autowired MintService realMint;

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        MigrationRunner.RunReport r = migrationRunner.apply(
                new MigrationTarget("test", db.getName(), List.of("localhost:27017"), "tests", "test"),
                MigrationRunner.Selection.schemaOnly(), MigrationRunner.ApplyOptions.forTests());
        if (!r.ok()) throw new AssertionError("schema migrations failed in test setup: " + r.render());
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
        for (String sku : List.of("TZP-OBS-1", "TZP-OBS-2")) {
            Map<String, Object> m = new LinkedHashMap<>(BulkProductImportIT.row(sku, sku.endsWith("1") ? 1 : 2));
            m.put("brandCode", "BR-OBS");
            m.put("internalKey", "obs|" + sku);
            assertThat(post("/api/v1/products", m, W, JsonNode.class).getStatusCode().value()).isEqualTo(201);
        }
    }

    // ---------------------------------------------------------------- helpers

    double count(String name, String... tags) {
        double sum = 0;
        for (var c : meters.find(name).tags(tags).counters()) sum += c.count();
        return sum;
    }

    long timers(String name, String... tags) {
        long sum = 0;
        for (var t : meters.find(name).tags(tags).timers()) sum += t.count();
        return sum;
    }

    ResponseEntity<JsonNode> csv(String jobId, String body) {
        HttpHeaders h = headers(W);
        h.setContentType(MediaType.parseMediaType("text/csv"));
        return rest.exchange(url(JOBS + "/" + jobId + "/rows"), HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    ResponseEntity<JsonNode> act(String jobId, String action) {
        return post(JOBS + "/" + jobId + "/" + action, Map.of(), W, JsonNode.class);
    }

    String create() {
        ResponseEntity<JsonNode> r = post(JOBS, Map.of("kind", "products", "note", "metrics"), W, JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        return r.getBody().get("id").asText();
    }

    ImportJobWorker workerWith(MintService mint) {
        return new ImportJobWorker(repo, service, new ProductImportValidator(db, client, governance, canonicalKeys), mint, tx,
                Clock.systemUTC(), 500, 60_000, 30_000, metrics);
    }

    String validated(String csvBody) {
        String id = create();
        csv(id, csvBody);
        act(id, "validate");
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        return id;
    }

    // ---------------------------------------------------------------- async jobs

    @Test
    void a_job_from_csv_to_completed_counts_every_transition_every_row_and_every_tick() {
        double open = count(ImportMetrics.JOB_TRANSITIONS, "to", "open"), validating = count(ImportMetrics.JOB_TRANSITIONS, "to", "validating"),
                validatedT = count(ImportMetrics.JOB_TRANSITIONS, "to", "validated"), applying = count(ImportMetrics.JOB_TRANSITIONS, "to", "applying"),
                completed = count(ImportMetrics.JOB_TRANSITIONS, "to", "completed"), applied = count(ImportMetrics.ROWS_APPLIED),
                failed = count(ImportMetrics.ROWS_FAILED);
        long worked = timers(ImportMetrics.TICK_DURATION, "result", "worked"), idle = timers(ImportMetrics.TICK_DURATION, "result", "idle");

        String id = validated(HEADER + ImportJobsIT.line("TZP-IM-001", 1) + ImportJobsIT.line("TZP-IM-002", 2) + ImportJobsIT.line("TZP-IM-003", 3));
        act(id, "apply");
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(worker.tick().jobId()).isNull();

        assertThat(count(ImportMetrics.JOB_TRANSITIONS, "to", "open") - open).isEqualTo(1);
        assertThat(count(ImportMetrics.JOB_TRANSITIONS, "to", "validating") - validating).isEqualTo(1);
        assertThat(count(ImportMetrics.JOB_TRANSITIONS, "to", "validated") - validatedT).isEqualTo(1);
        assertThat(count(ImportMetrics.JOB_TRANSITIONS, "to", "applying") - applying).isEqualTo(1);
        assertThat(count(ImportMetrics.JOB_TRANSITIONS, "to", "completed") - completed).isEqualTo(1);
        assertThat(count(ImportMetrics.ROWS_APPLIED) - applied).isEqualTo(3);
        assertThat(count(ImportMetrics.ROWS_FAILED) - failed).isZero();
        assertThat(timers(ImportMetrics.TICK_DURATION, "result", "worked") - worked).as("validate tick + apply tick").isEqualTo(2);
        assertThat(timers(ImportMetrics.TICK_DURATION, "result", "idle") - idle).isEqualTo(1);
        assertThat(meters.find(ImportMetrics.TICK_DURATION).tag("result", "worked").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .isGreaterThan(0);
    }

    @Test
    void a_datastore_failure_counts_a_pause_and_a_paused_transition_and_a_cancelled_lease_counts_lease_lost() {
        double paused = count(ImportMetrics.PAUSED, "reason", "datastore_failure"), pausedT = count(ImportMetrics.JOB_TRANSITIONS, "to", "paused"),
                tickPaused = timers(ImportMetrics.TICK_DURATION, "result", "paused");
        String id = validated(HEADER + ImportJobsIT.line("TZP-IM-011", 1) + ImportJobsIT.line("TZP-IM-012", 2));
        act(id, "apply");
        AtomicInteger calls = new AtomicInteger();
        MintService failing = new MintService(null, null, null, null) {
            @Override public String mint(Actor actor, ProductDraft d) {
                if (calls.incrementAndGet() == 2) throw new com.mongodb.MongoSocketReadException("gone", new com.mongodb.ServerAddress());
                return realMint.mint(actor, d);
            }
        };
        assertThat(workerWith(failing).tick().paused()).isTrue();
        assertThat(count(ImportMetrics.PAUSED, "reason", "datastore_failure") - paused).isEqualTo(1);
        assertThat(count(ImportMetrics.JOB_TRANSITIONS, "to", "paused") - pausedT).isEqualTo(1);
        assertThat(timers(ImportMetrics.TICK_DURATION, "result", "paused") - tickPaused).isEqualTo(1);

        // a different job: the admin cancels while the first product is being minted
        double lost = count(ImportMetrics.LEASE_LOST, "phase", "apply"), cancelled = count(ImportMetrics.JOB_TRANSITIONS, "to", "cancelled");
        String id2 = validated(HEADER + ImportJobsIT.line("TZP-IM-021", 1) + ImportJobsIT.line("TZP-IM-022", 2));
        act(id2, "apply");
        MintService cancelling = new MintService(null, null, null, null) {
            @Override public String mint(Actor actor, ProductDraft d) {
                String r = realMint.mint(actor, d);
                if (d.id().equals("TZP-IM-021")) act(id2, "cancel");
                return r;
            }
        };
        workerWith(cancelling).tick();
        assertThat(count(ImportMetrics.LEASE_LOST, "phase", "apply") - lost).isEqualTo(1);
        assertThat(count(ImportMetrics.JOB_TRANSITIONS, "to", "cancelled") - cancelled).isEqualTo(1);
    }

    @Test
    void a_genuine_collision_at_mint_time_counts_a_failed_row() {
        double failed = count(ImportMetrics.ROWS_FAILED), applied = count(ImportMetrics.ROWS_APPLIED);
        String id = validated(HEADER + ImportJobsIT.line("TZP-IM-031", 1) + ImportJobsIT.line("TZP-IM-032", 2));
        act(id, "apply");
        MintService contested = new MintService(null, null, null, null) {
            @Override public String mint(Actor actor, ProductDraft d) {
                if (d.id().equals("TZP-IM-031")) {
                    Map<String, Object> other = new LinkedHashMap<>(BulkProductImportIT.row("TZP-IM-901", 9));
                    other.put("internalKey", "job|TZP-IM-031");
                    assertThat(post(BulkProductImportIT.PATH, BulkProductImportIT.file(false, List.of(other)), W, JsonNode.class)
                            .getStatusCode().value()).isEqualTo(200);
                }
                return realMint.mint(actor, d);
            }
        };
        assertThat(workerWith(contested).tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(count(ImportMetrics.ROWS_FAILED) - failed).isEqualTo(1);
        assertThat(count(ImportMetrics.ROWS_APPLIED) - applied).isEqualTo(1);
    }

    // ---------------------------------------------------------------- synchronous bulk import

    @Test
    void the_synchronous_imports_count_runs_by_kind_and_outcome_and_rows_by_outcome() {
        double dry = count(ImportMetrics.BULK_RUNS, "kind", "prices", "outcome", "dry_run"),
                applied = count(ImportMetrics.BULK_RUNS, "kind", "prices", "outcome", "applied"),
                partial = count(ImportMetrics.BULK_RUNS, "kind", "prices", "outcome", "partial"),
                rejected = count(ImportMetrics.BULK_RUNS, "kind", "prices", "outcome", "rejected"),
                rowsApplied = count(ImportMetrics.BULK_ROWS, "kind", "prices", "outcome", "applied"),
                rowsFailed = count(ImportMetrics.BULK_ROWS, "kind", "prices", "outcome", "failed"),
                rowsValidated = count(ImportMetrics.BULK_ROWS, "kind", "prices", "outcome", "validated"),
                products = count(ImportMetrics.BULK_RUNS, "kind", "products", "outcome", "applied"),
                productsRejected = count(ImportMetrics.BULK_RUNS, "kind", "products", "outcome", "rejected");
        String path = "/api/v1/admin/imports/prices";

        post(path, BulkImportIT.file(true, List.of(BulkImportIT.price("TZP-OBS-1", 9900, 12000, null), BulkImportIT.price("TZP-OBS-2", 500, 500, null))), W, JsonNode.class);
        post(path, BulkImportIT.file(false, List.of(BulkImportIT.price("TZP-OBS-1", 9900, 12000, null), BulkImportIT.price("TZP-OBS-2", 500, 500, null))), W, JsonNode.class);
        post(path, BulkImportIT.file(false, List.of(BulkImportIT.price("TZP-OBS-1", 9500, 12000, 1L), BulkImportIT.price("TZP-OBS-2", 450, 500, 7L))), W, JsonNode.class);
        ResponseEntity<JsonNode> bad = post(path, BulkImportIT.file(false, List.of(BulkImportIT.price("TZP-NOPE-9", 100, 100, null))), W, JsonNode.class);
        assertThat(bad.getStatusCode().value()).as("an unknown product rejects the whole file").isGreaterThanOrEqualTo(400);

        assertThat(count(ImportMetrics.BULK_RUNS, "kind", "prices", "outcome", "dry_run") - dry).isEqualTo(1);
        assertThat(count(ImportMetrics.BULK_RUNS, "kind", "prices", "outcome", "applied") - applied).isEqualTo(1);
        assertThat(count(ImportMetrics.BULK_RUNS, "kind", "prices", "outcome", "partial") - partial).isEqualTo(1);
        assertThat(count(ImportMetrics.BULK_RUNS, "kind", "prices", "outcome", "rejected") - rejected).isEqualTo(1);
        assertThat(count(ImportMetrics.BULK_ROWS, "kind", "prices", "outcome", "applied") - rowsApplied).isEqualTo(3);
        assertThat(count(ImportMetrics.BULK_ROWS, "kind", "prices", "outcome", "failed") - rowsFailed).isEqualTo(1);
        assertThat(count(ImportMetrics.BULK_ROWS, "kind", "prices", "outcome", "validated") - rowsValidated).isEqualTo(2);

        ResponseEntity<JsonNode> p = post(BulkProductImportIT.PATH, BulkProductImportIT.file(false, List.of(BulkProductImportIT.row("TZP-IM-701", 70))), W, JsonNode.class);
        assertThat(p.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> invalid = new LinkedHashMap<>(BulkProductImportIT.row("TZP-IM-702", 71));
        invalid.put("verticalId", "TZV-999999");
        assertThat(post(BulkProductImportIT.PATH, BulkProductImportIT.file(false, List.of(invalid)), W, JsonNode.class).getStatusCode().value())
                .isGreaterThanOrEqualTo(400);
        assertThat(count(ImportMetrics.BULK_RUNS, "kind", "products", "outcome", "applied") - products).isEqualTo(1);
        assertThat(count(ImportMetrics.BULK_RUNS, "kind", "products", "outcome", "rejected") - productsRejected).isEqualTo(1);
    }

    // ---------------------------------------------------------------- backlog gauges

    static final class MovingClock extends Clock {
        volatile Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void the_backlog_gauges_read_the_real_collection_through_a_bounded_snapshot() {
        db.getCollection("import_jobs").deleteMany(new Document());
        MovingClock clock = new MovingClock();
        SimpleMeterRegistry local = new SimpleMeterRegistry();
        new ImportJobGauges(db, clock, local, Duration.ofSeconds(15));

        Date old = Date.from(clock.now.minusSeconds(600));
        insertJob("IMPJ-g1", "OPEN", old);
        insertJob("IMPJ-g2", "OPEN", old);
        insertJob("IMPJ-g3", "VALIDATING", old);
        insertJob("IMPJ-g4", "APPLYING", Date.from(clock.now.minusSeconds(60)));
        insertJob("IMPJ-g5", "PAUSED", old);
        insertJob("IMPJ-g6", "COMPLETED", old);
        insertJob("IMPJ-g7", "CANCELLED", old);

        assertThat(local.get("import_jobs_active").tag("status", "open").gauge().value()).isEqualTo(2);
        assertThat(local.get("import_jobs_active").tag("status", "validating").gauge().value()).isEqualTo(1);
        assertThat(local.get("import_jobs_active").tag("status", "applying").gauge().value()).isEqualTo(1);
        assertThat(local.get("import_jobs_active").tag("status", "paused").gauge().value()).isEqualTo(1);
        assertThat(local.get("import_jobs_active").tag("status", "validated").gauge().value()).isZero();
        assertThat(local.find("import_jobs_active").gauges()).as("terminal statuses are not a backlog").hasSize(6);
        assertThat(local.get("import_job_oldest_active_age_seconds").gauge().value())
                .as("least recently progressed VALIDATING/APPLYING job: the validating one, 600 s").isEqualTo(600.0);

        db.getCollection("import_jobs").deleteOne(new Document("_id", "IMPJ-g3"));
        assertThat(local.get("import_jobs_active").tag("status", "validating").gauge().value()).as("served from the snapshot").isEqualTo(1);
        clock.now = clock.now.plusSeconds(15);
        assertThat(local.get("import_jobs_active").tag("status", "validating").gauge().value()).isZero();
        assertThat(local.get("import_job_oldest_active_age_seconds").gauge().value()).as("now the applying job: 60 s + 15 s").isEqualTo(75.0);
        db.getCollection("import_jobs").deleteMany(new Document());
    }

    private void insertJob(String id, String status, Date updatedAt) {
        db.getCollection("import_jobs").insertOne(new Document("_id", id).append("kind", "PRODUCTS").append("status", status)
                .append("updated_at", updatedAt).append("created_at", updatedAt).append("lease_until", new Date(0)));
    }

    @Test
    void the_application_context_registers_the_gauges_and_no_tag_value_in_the_whole_registry_is_an_id_or_a_uri() {
        assertThat(meters.find("import_jobs_active").gauges()).hasSize(6);
        assertThat(meters.find("import_job_oldest_active_age_seconds").gauge()).isNotNull();
        List<String> violations = new java.util.ArrayList<>();
        for (Meter m : meters.getMeters()) {
            if (!(m.getId().getName().startsWith("import_") || m.getId().getName().startsWith("bulk_import_"))) continue;
            for (Tag t : m.getId().getTags()) {
                if (!ImportMetrics.ALLOWED_TAG_KEYS.contains(t.getKey()) && !t.getKey().equals("status")) violations.add(m.getId().getName() + " key " + t.getKey());
                if (!t.getValue().matches("[a-z_]{1,20}")) violations.add(m.getId().getName() + " " + t.getKey() + "=" + t.getValue());
            }
        }
        assertThat(violations).isEmpty();
    }
}
