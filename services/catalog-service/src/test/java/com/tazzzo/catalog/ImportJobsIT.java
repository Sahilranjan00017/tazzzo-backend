package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.bulkimport.ProductImportValidator;
import com.tazzzo.bulkimport.jobs.ImportJob;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asynchronous import jobs end to end over HTTP, with the worker driven by hand (the scheduler is off in tests): streamed
 * CSV ingestion with DB-enforced per-job duplicates, validation to VALIDATED/REJECTED, a row correction, explicit approval,
 * apply through mint attributed to the approver, UNCHANGED re-runs, pause on a datastore failure and resume from the
 * cursor without re-applying, cancel, the lease, authorisation and the error file.
 */
@Timeout(300)
class ImportJobsIT extends AbstractApiIT {

    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    static final String JOBS = "/api/v1/admin/imports/jobs";
    static final String HEADER = "id,title,brand,vertical,release,key,attr.pack_size,attr.pack_unit\n";

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    @Autowired ImportJobWorker worker;
    @Autowired ImportJobRepository repo;
    @Autowired ImportJobService service;
    @Autowired AttributeGovernanceService governance;
    @Autowired CanonicalKeyService canonicalKeys;
    @Autowired MintService mint;
    @Autowired MigrationRunner migrationRunner;
    @Autowired com.tazzzo.catalog.tx.Tx tx;
    @Autowired com.tazzzo.catalog.tx.MintService realMint;

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        // the V0016 indexes are migration-only; the per-job duplicate rule is one of them
        MigrationRunner.RunReport r = migrationRunner.apply(
                new MigrationTarget("test", db.getName(), List.of("localhost:27017"), "tests", "test"),
                MigrationRunner.Selection.schemaOnly(), MigrationRunner.ApplyOptions.forTests());
        if (!r.ok()) throw new AssertionError("schema migrations failed in test setup: " + r.render());
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
    }

    static String line(String id, int n) {
        return id + ",Job rice " + n + " kg,BR-JOB,TZV-000001,0.9.0,job|" + id + "," + n + ",kg\n";
    }

    ResponseEntity<JsonNode> csv(String jobId, String body, String token) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.parseMediaType("text/csv"));
        return rest.exchange(url(JOBS + "/" + jobId + "/rows"), HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    ResponseEntity<JsonNode> act(String jobId, String action, String token) {
        return post(JOBS + "/" + jobId + "/" + action, Map.of(), token, JsonNode.class);
    }

    String create(String note) {
        ResponseEntity<JsonNode> r = post(JOBS, Map.of("kind", "products", "note", note), W, JsonNode.class);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        assertThat(r.getBody().get("status").asText()).isEqualTo("OPEN");
        return r.getBody().get("id").asText();
    }

    JsonNode job(String id) {
        return get(JOBS + "/" + id, R, JsonNode.class).getBody();
    }

    long products() {
        return db.getCollection("products").countDocuments();
    }

    long productEvents() {
        return db.getCollection("product_events").countDocuments();
    }

    @Test
    void csv_to_rejected_to_corrected_to_validated_to_applied_to_unchanged_rerun() {
        String id = create("launch file");
        ResponseEntity<JsonNode> added = csv(id, HEADER + line("TZP-IJ-001", 1) + line("TZP-IJ-002", 2) + line("TZP-IJ-003", 3)
                + line("TZP-IJ-002", 4), W);
        assertThat(added.getStatusCode().value()).as(String.valueOf(added.getBody())).isEqualTo(200);
        assertThat(added.getBody().get("rowsAdded").asLong()).isEqualTo(4);
        assertThat(added.getBody().get("duplicates").asLong()).as("the second TZP-IJ-002 is a DB-enforced duplicate").isEqualTo(1);

        assertThat(act(id, "apply", W).getStatusCode().value()).as("apply needs VALIDATED").isEqualTo(409);
        ResponseEntity<JsonNode> v = act(id, "validate", W);
        assertThat(v.getStatusCode().value()).isEqualTo(200);
        assertThat(v.getBody().get("status").asText()).isEqualTo("VALIDATING");
        assertThat(csv(id, HEADER + line("TZP-IJ-009", 9), W).getStatusCode().value()).as("no rows while validating").isEqualTo(409);
        long p0 = products();
        ImportJobWorker.Tick t = worker.tick();
        assertThat(t.endedAs()).isEqualTo(ImportJob.Status.REJECTED);
        assertThat(products()).as("validation writes nothing").isEqualTo(p0);
        JsonNode j = job(id);
        assertThat(j.get("counts").get("valid").asLong()).isEqualTo(3);
        assertThat(j.get("counts").get("duplicate").asLong()).isEqualTo(1);
        assertThat(j.get("counts").get("invalid").asLong()).isZero();

        ResponseEntity<String> errors = rest.exchange(url(JOBS + "/" + id + "/errors.csv"), HttpMethod.GET, new HttpEntity<>(headers(R)), String.class);
        assertThat(errors.getStatusCode().value()).isEqualTo(200);
        assertThat(errors.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(errors.getBody()).startsWith("row,line,id,phase,outcome,code,message").contains("3,4,TZP-IJ-002,validation,DUPLICATE,DUPLICATE_ROW");

        // correct the duplicate row: REJECTED -> OPEN, then validate again
        Map<String, Object> fixed = BulkProductImportIT.row("TZP-IJ-004", 4);
        fixed.put("brandCode", "BR-JOB");
        fixed.put("internalKey", "job|TZP-IJ-004");
        fixed.put("title", "Job rice 4 kg");   // the same product the CSV re-run below describes
        ResponseEntity<JsonNode> put = rest.exchange(url(JOBS + "/" + id + "/rows/3"), HttpMethod.PUT, new HttpEntity<>(fixed, headers(W)), JsonNode.class);
        assertThat(put.getStatusCode().value()).as(String.valueOf(put.getBody())).isEqualTo(200);
        assertThat(put.getBody().get("status").asText()).isEqualTo("OPEN");
        assertThat(act(id, "validate", W).getBody().get("status").asText()).isEqualTo("VALIDATING");
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        j = job(id);
        assertThat(j.get("counts").get("valid").asLong()).isEqualTo(4);
        assertThat(j.get("counts").get("duplicate").asLong()).isZero();

        // explicit approval, then apply
        ResponseEntity<JsonNode> a = act(id, "apply", W);
        assertThat(a.getStatusCode().value()).isEqualTo(200);
        assertThat(a.getBody().get("status").asText()).isEqualTo("APPLYING");
        assertThat(a.getBody().get("approvedBy").get("id").asText()).isNotBlank();
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        j = job(id);
        assertThat(j.get("counts").get("applied").asLong()).isEqualTo(4);
        assertThat(j.get("counts").get("failed").asLong()).isZero();
        assertThat(products()).isEqualTo(p0 + 4);
        assertThat(get("/api/v1/products/TZP-IJ-003", R, JsonNode.class).getBody().get("title").asText()).isEqualTo("Job rice 3 kg");
        Document event = db.getCollection("product_events").find(new Document("product_id", "TZP-IJ-001")).first();
        assertThat(event.get("actor", Document.class).getString("id")).as("attributed to the approver").isEqualTo(a.getBody().get("approvedBy").get("id").asText());
        assertThat(db.getCollection("domain_events").countDocuments(new Document("aggregate_type", "import_job").append("aggregate_id", id)
                .append("type", "IMPORT_JOB_APPROVED"))).isEqualTo(1);
        JsonNode rows = get(JOBS + "/" + id + "/rows?from=2&limit=10", R, JsonNode.class).getBody();
        assertThat(rows.get("rows")).hasSize(2);
        assertThat(rows.get("rows").get(0).get("apply").get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(rows.get("rows").get(1).get("id").asText()).isEqualTo("TZP-IJ-004");

        // the same file again: every row UNCHANGED, nothing rewritten
        long e1 = productEvents();
        String again = create("re-run");
        csv(again, HEADER + line("TZP-IJ-001", 1) + line("TZP-IJ-002", 2) + line("TZP-IJ-003", 3) + line("TZP-IJ-004", 4), W);
        act(again, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        assertThat(job(again).get("counts").get("unchanged").asLong()).isEqualTo(4);
        act(again, "apply", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(job(again).get("counts").get("applied").asLong()).isZero();
        assertThat(job(again).get("counts").get("failed").asLong()).as("an UNCHANGED row is never minted again").isZero();
        assertThat(job(again).get("counts").get("unchanged").asLong()).isEqualTo(4);
        assertThat(productEvents()).isEqualTo(e1);
        assertThat(products()).isEqualTo(p0 + 4);
        assertThat(worker.tick().jobId()).as("nothing left to claim").isNull();
    }

    @Test
    void invalid_rows_are_reported_per_row_and_a_multi_batch_file_validates_and_applies_across_ticks() {
        String id = create("big file");
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 0; i < 620; i++) sb.append(line(String.format("TZP-IB-%03d", i), 100 + i));
        sb.append("TZP-IB-BAD,no vertical,BR-JOB,TZV-999999,0.9.0,job|bad,1,kg\n");
        ResponseEntity<JsonNode> added = csv(id, sb.toString(), W);
        assertThat(added.getBody().get("rowsTotal").asLong()).isEqualTo(621);
        act(id, "validate", W);
        assertThat(worker.tick().endedAs()).as("one tick covers both 500-row batches within the budget").isEqualTo(ImportJob.Status.REJECTED);
        JsonNode j = job(id);
        assertThat(j.get("counts").get("valid").asLong()).isEqualTo(620);
        assertThat(j.get("counts").get("invalid").asLong()).isEqualTo(1);
        JsonNode bad = get(JOBS + "/" + id + "/rows?from=620&limit=1", R, JsonNode.class).getBody().get("rows").get(0);
        assertThat(bad.get("validation").get("outcome").asText()).isEqualTo("INVALID");
        assertThat(bad.get("validation").get("code").asText()).isNotBlank();
        assertThat(act(id, "apply", W).getStatusCode().value()).as("a REJECTED job cannot be applied").isEqualTo(409);
        // cancel it: rows stay readable, nothing was written
        ResponseEntity<JsonNode> c = act(id, "cancel", W);
        assertThat(c.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(act(id, "validate", W).getStatusCode().value()).isEqualTo(409);
        assertThat(db.getCollection("products").countDocuments(new Document("_id", new Document("$regex", "^TZP-IB-")))).isZero();
    }

    @Test
    void a_datastore_failure_pauses_at_the_cursor_and_resume_applies_the_rest_exactly_once() {
        String id = create("pause");
        csv(id, HEADER + line("TZP-IP-001", 1) + line("TZP-IP-002", 2) + line("TZP-IP-003", 3), W);
        act(id, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        act(id, "apply", W);
        // a worker whose mint fails on the second row with a non-domain failure (the datastore going away)
        AtomicInteger calls = new AtomicInteger();
        MintService failing = new MintService(null, null, null, null) {
            @Override
            public String mint(Actor actor, ProductDraft d) {
                if (calls.incrementAndGet() == 2) throw new com.mongodb.MongoSocketReadException("gone", new com.mongodb.ServerAddress());
                return mint.mint(actor, d);
            }
        };
        ImportJobWorker flaky = new ImportJobWorker(repo, service, new ProductImportValidator(db, client, governance, canonicalKeys), failing,
                tx, Clock.systemUTC(), 500, 60_000, 30_000);
        ImportJobWorker.Tick t = flaky.tick();
        assertThat(t.paused()).isTrue();
        JsonNode j = job(id);
        assertThat(j.get("status").asText()).isEqualTo("PAUSED");
        assertThat(j.get("nextRow").asLong()).as("the cursor stops at the row that was not applied").isEqualTo(1);
        assertThat(j.get("counts").get("applied").asLong()).isEqualTo(1);
        assertThat(j.get("lastError").asText()).isEqualTo("MongoSocketReadException");
        assertThat(worker.tick().jobId()).as("a PAUSED job is not claimed").isNull();

        ResponseEntity<JsonNode> r = act(id, "resume", W);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("status").asText()).isEqualTo("APPLYING");
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        j = job(id);
        assertThat(j.get("counts").get("applied").asLong()).isEqualTo(3);
        assertThat(j.get("counts").get("unchanged").asLong()).isZero();
        assertThat(db.getCollection("products").countDocuments(new Document("_id", new Document("$regex", "^TZP-IP-")))).isEqualTo(3);
        JsonNode verdicts = get(JOBS + "/" + id + "/rows?from=0&limit=3", R, JsonNode.class).getBody().get("rows");
        assertThat(verdicts.findValues("apply").stream().map(a -> a.get("outcome").asText()))
                .as("resume continues from the cursor: row 0 keeps its APPLIED verdict, it is not re-read as UNCHANGED")
                .containsExactly("APPLIED", "APPLIED", "APPLIED");
        assertThat(db.getCollection("product_events").countDocuments(new Document("product_id", "TZP-IP-001"))).as("never re-applied: the same events as a product minted once")
                .isEqualTo(db.getCollection("product_events").countDocuments(new Document("product_id", "TZP-IP-003")));
    }

    @Test
    void the_lease_keeps_a_second_worker_off_a_claimed_job() {
        String id = create("lease");
        csv(id, HEADER + line("TZP-IL-001", 1), W);
        act(id, "validate", W);
        ImportJob claimed = repo.claim("worker-a", 60_000);
        assertThat(claimed.id()).isEqualTo(id);
        assertThat(repo.claim("worker-b", 60_000)).as("leased").isNull();
        assertThat(repo.progress(id, "worker-b", 1, ImportJob.Counts.ZERO, 60_000)).as("the wrong token cannot advance").isFalse();
        assertThat(repo.releaseLease(id, "worker-a")).isTrue();
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        act(id, "cancel", W);
        assertThat(job(id).get("status").asText()).isEqualTo("CANCELLED");

        // cancelling a job a worker holds takes the lease away: the worker's next progress write is refused
        String held = create("lease-cancel");
        csv(held, HEADER + line("TZP-IL-002", 2), W);
        act(held, "validate", W);
        assertThat(repo.claim("worker-c", 60_000).id()).isEqualTo(held);
        assertThat(act(held, "cancel", W).getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(repo.progress(held, "worker-c", 1, ImportJob.Counts.ZERO, 60_000)).as("the lease died with the cancel").isFalse();
    }

    @Test
    void cancel_while_a_worker_is_minting_stops_it_at_the_next_row_with_exact_counts() {
        String id = create("cancel-running");
        csv(id, HEADER + line("TZP-IC-001", 1) + line("TZP-IC-002", 2) + line("TZP-IC-003", 3), W);
        act(id, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        act(id, "apply", W);
        // the admin cancels while the FIRST product is being minted
        MintService cancelling = new MintService(null, null, null, null) {
            @Override
            public String mint(Actor actor, ProductDraft d) {
                String r = realMint.mint(actor, d);
                if (d.id().equals("TZP-IC-001")) assertThat(act(id, "cancel", W).getStatusCode().value()).isEqualTo(200);
                return r;
            }
        };
        ImportJobWorker w = new ImportJobWorker(repo, service, new ProductImportValidator(db, client, governance, canonicalKeys), cancelling,
                tx, Clock.systemUTC(), 500, 60_000, 30_000);
        ImportJobWorker.Tick t = w.tick();
        assertThat(t.endedAs()).as("the worker noticed the lost lease, it did not finish the phase").isEqualTo(ImportJob.Status.APPLYING);
        JsonNode j = job(id);
        assertThat(j.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(db.getCollection("products").countDocuments(new Document("_id", new Document("$regex", "^TZP-IC-"))))
                .as("at most the row in flight is minted after the cancel; the next row never is").isEqualTo(1);
        // the one row minted after the cancel could not be recorded (the lease was gone): counts and verdicts agree with each other
        assertThat(j.get("counts").get("applied").asLong()).isZero();
        JsonNode rows = get(JOBS + "/" + id + "/rows?from=0&limit=3", R, JsonNode.class).getBody().get("rows");
        assertThat(rows.findValues("apply")).allMatch(JsonNode::isNull);
        assertThat(worker.tick().jobId()).as("a CANCELLED job is never claimed").isNull();
    }

    @Test
    void a_lost_lease_mid_apply_records_nothing_and_the_next_holder_finds_the_row_unchanged() {
        String id = create("lease-lost");
        csv(id, HEADER + line("TZP-IE-001", 1) + line("TZP-IE-002", 2) + line("TZP-IE-003", 3), W);
        act(id, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        act(id, "apply", W);
        // worker A: its lease is taken away while it mints row 0 (as if it had expired and been re-claimed)
        String[] stolen = {null};
        MintService slow = new MintService(null, null, null, null) {
            @Override
            public String mint(Actor actor, ProductDraft d) {
                String r = realMint.mint(actor, d);
                if (d.id().equals("TZP-IE-001")) {
                    db.getCollection("import_jobs").updateOne(new Document("_id", id), new Document("$set", new Document("lease_until", new java.util.Date(0))));
                    ImportJob taken = repo.claim("worker-b", 60_000);
                    stolen[0] = taken == null ? null : taken.id();
                }
                return r;
            }
        };
        ImportJobWorker a = new ImportJobWorker(repo, service, new ProductImportValidator(db, client, governance, canonicalKeys), slow,
                tx, Clock.systemUTC(), 500, 60_000, 30_000);
        assertThat(a.tick().endedAs()).isEqualTo(ImportJob.Status.APPLYING);
        assertThat(stolen[0]).isEqualTo(id);
        JsonNode j = job(id);
        assertThat(j.get("counts").get("applied").asLong()).as("A recorded nothing for the row it minted after losing the lease").isZero();
        assertThat(get(JOBS + "/" + id + "/rows?from=0&limit=3", R, JsonNode.class).getBody().get("rows").findValues("apply")).allMatch(JsonNode::isNull);
        // worker B finishes from the cursor: row 0 is UNCHANGED (minted by A), rows 1-2 applied; no row FAILED, no product twice
        assertThat(repo.releaseLease(id, "worker-b")).isTrue();
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        j = job(id);
        assertThat(j.get("counts").get("applied").asLong()).isEqualTo(2);
        assertThat(j.get("counts").get("failed").asLong()).isZero();
        assertCountsMatchVerdicts(j);
        assertThat(j.get("counts").get("unchanged").asLong()).as("the row found UNCHANGED at apply is counted as such").isEqualTo(1);
        JsonNode rows = get(JOBS + "/" + id + "/rows?from=0&limit=3", R, JsonNode.class).getBody().get("rows");
        assertThat(rows.findValues("apply").stream().map(x -> x.get("outcome").asText()).toList()).containsExactly("UNCHANGED", "APPLIED", "APPLIED");
        assertThat(db.getCollection("products").countDocuments(new Document("_id", new Document("$regex", "^TZP-IE-")))).isEqualTo(3);
    }

    /** A finished job's counters agree with its rows: valid/unchanged/invalid/duplicate partition them, applied + failed == valid. */
    void assertCountsMatchVerdicts(JsonNode j) {
        JsonNode c = j.get("counts");
        assertThat(c.get("valid").asLong() + c.get("unchanged").asLong() + c.get("invalid").asLong() + c.get("duplicate").asLong())
                .as("every row in exactly one bucket: " + c).isEqualTo(j.get("rowsTotal").asLong());
        assertThat(c.get("applied").asLong() + c.get("failed").asLong()).as("every VALID row applied or failed: " + c).isEqualTo(c.get("valid").asLong());
    }

    @Test
    void a_renewal_in_the_same_millisecond_as_the_claim_keeps_the_lease() {
        String id = create("same-ms");
        csv(id, HEADER + line("TZP-IM-001", 1), W);
        act(id, "validate", W);
        // a frozen clock makes the renewal write exactly the values the claim wrote: Mongo then modifies nothing, yet the
        // lease is still held (ownership is the token matching, not the document changing)
        ImportJobRepository frozen = new ImportJobRepository(db, Clock.fixed(java.time.Instant.now(), java.time.ZoneOffset.UTC));
        assertThat(frozen.claim("worker-f", 60_000).id()).isEqualTo(id);
        assertThat(frozen.renewLease(id, "worker-f", 60_000)).isTrue();
        assertThat(frozen.progress(id, "worker-f", 0, ImportJob.Counts.ZERO, 60_000)).isTrue();
        assertThat(frozen.renewLease(id, "worker-x", 60_000)).as("a different token still cannot renew").isFalse();
        assertThat(frozen.releaseLease(id, "worker-f")).isTrue();
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
    }

    @Test
    void a_datastore_failure_after_the_lease_was_lost_writes_no_verdict_and_does_not_pause() {
        String id = create("pause-lease-lost");
        csv(id, HEADER + line("TZP-IQ-001", 1) + line("TZP-IQ-002", 2), W);
        act(id, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        act(id, "apply", W);
        // worker A's mint of row 0 outlives its lease (B claims the job) and then fails like a datastore outage
        MintService late = new MintService(null, null, null, null) {
            @Override
            public String mint(Actor actor, ProductDraft d) {
                db.getCollection("import_jobs").updateOne(new Document("_id", id), new Document("$set", new Document("lease_until", new java.util.Date(0))));
                assertThat(repo.claim("worker-b", 60_000).id()).isEqualTo(id);
                throw new com.mongodb.MongoSocketReadException("gone", new com.mongodb.ServerAddress());
            }
        };
        ImportJobWorker a = new ImportJobWorker(repo, service, new ProductImportValidator(db, client, governance, canonicalKeys), late,
                tx, Clock.systemUTC(), 500, 60_000, 30_000);
        ImportJobWorker.Tick t = a.tick();
        assertThat(t.paused()).as("A no longer holds the job: it cannot pause it").isFalse();
        JsonNode j = job(id);
        assertThat(j.get("status").asText()).isEqualTo("APPLYING");
        assertThat(j.get("nextRow").asLong()).isZero();
        assertThat(get(JOBS + "/" + id + "/rows?from=0&limit=2", R, JsonNode.class).getBody().get("rows").findValues("apply"))
                .as("A wrote no NOT_ATTEMPTED over the row the new holder owns").allMatch(JsonNode::isNull);
        // B finishes normally
        assertThat(repo.releaseLease(id, "worker-b")).isTrue();
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        j = job(id);
        assertThat(j.get("counts").get("applied").asLong()).isEqualTo(2);
        assertCountsMatchVerdicts(j);
    }

    @Test
    void a_product_that_appears_between_revalidation_and_mint_is_unchanged_not_a_collision() {
        String id = create("collision-recheck");
        csv(id, HEADER + line("TZP-IR-001", 1) + line("TZP-IR-002", 2), W);
        act(id, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        act(id, "apply", W);
        // row 0's product is minted by someone else (an earlier lease holder) just before this worker's own mint
        MintService raced = new MintService(null, null, null, null) {
            @Override
            public String mint(Actor actor, ProductDraft d) {
                if (d.id().equals("TZP-IR-001")) realMint.mint(actor, d);
                return realMint.mint(actor, d);
            }
        };
        ImportJobWorker w = new ImportJobWorker(repo, service, new ProductImportValidator(db, client, governance, canonicalKeys), raced,
                tx, Clock.systemUTC(), 500, 60_000, 30_000);
        assertThat(w.tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        JsonNode j = job(id);
        assertThat(j.get("counts").get("failed").asLong()).as("the job's own product is not an identity collision").isZero();
        assertThat(j.get("counts").get("applied").asLong()).isEqualTo(1);
        assertThat(j.get("counts").get("unchanged").asLong()).isEqualTo(1);
        assertCountsMatchVerdicts(j);
        assertThat(get(JOBS + "/" + id + "/rows?from=0&limit=2", R, JsonNode.class).getBody().get("rows").findValues("apply").stream()
                .map(x -> x.get("outcome").asText()).toList()).containsExactly("UNCHANGED", "APPLIED");
    }

    @Test
    void a_collision_with_another_product_at_mint_time_fails_the_row() {
        String id = create("collision-genuine");
        csv(id, HEADER + line("TZP-IY-001", 1) + line("TZP-IY-002", 2), W);
        act(id, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        act(id, "apply", W);
        // between the re-validation and the mint of row 0, ANOTHER product takes row 0's internal key
        MintService contested = new MintService(null, null, null, null) {
            @Override
            public String mint(Actor actor, ProductDraft d) {
                if (d.id().equals("TZP-IY-001")) {
                    Map<String, Object> other = new java.util.LinkedHashMap<>(BulkProductImportIT.row("TZP-IY-901", 9));
                    other.put("internalKey", "job|TZP-IY-001");
                    ResponseEntity<JsonNode> r = post(BulkProductImportIT.PATH, BulkProductImportIT.file(false, List.of(other)), W, JsonNode.class);
                    assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
                }
                return realMint.mint(actor, d);
            }
        };
        ImportJobWorker w = new ImportJobWorker(repo, service, new ProductImportValidator(db, client, governance, canonicalKeys), contested,
                tx, Clock.systemUTC(), 500, 60_000, 30_000);
        assertThat(w.tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        JsonNode j = job(id);
        assertThat(j.get("counts").get("failed").asLong()).as("another product's identity is a real collision, never UNCHANGED").isEqualTo(1);
        assertThat(j.get("counts").get("applied").asLong()).isEqualTo(1);
        assertCountsMatchVerdicts(j);
        JsonNode rows = get(JOBS + "/" + id + "/rows?from=0&limit=2", R, JsonNode.class).getBody().get("rows");
        assertThat(rows.get(0).get("apply").get("outcome").asText()).isEqualTo("FAILED");
        assertThat(db.getCollection("products").countDocuments(new Document("_id", "TZP-IY-001"))).isZero();
    }

    @Test
    void any_state_change_ends_an_expired_uploads_claim_and_a_correction_keeps_the_other_verdicts() {
        String id = create("lock-dies");
        csv(id, HEADER + line("TZP-IV-001", 1) + "TZP-IV-002,Bad row,BR-JOB,TZV-NOPE,0.9.0,job|TZP-IV-002,2,kg\n", W);
        // an upload whose lock has expired (still streaming somewhere) must not publish rows once the job has moved on
        assertThat(repo.lockAppend(id, "upload-slow", 1)).isTrue();
        java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
        assertThat(act(id, "validate", W).getStatusCode().value()).as("an expired lock does not block validation").isEqualTo(200);
        Document doc = db.getCollection("import_jobs").find(new Document("_id", id)).first();
        assertThat(doc.containsKey("append_lock_token")).as("the lock died with the OPEN state").isFalse();
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.REJECTED);
        assertThat(repo.setRowsTotal(id, "upload-slow", 99)).as("the expired upload cannot publish rows").isFalse();
        // correcting the bad row reopens the job; the good row's verdict stays readable, the corrected row's is cleared
        ResponseEntity<JsonNode> fix = rest.exchange(url(JOBS + "/" + id + "/rows/1"), HttpMethod.PUT,
                new HttpEntity<>(BulkProductImportIT.row("TZP-IV-002", 2), headers(W)), JsonNode.class);
        assertThat(fix.getStatusCode().value()).as(String.valueOf(fix.getBody())).isEqualTo(200);
        JsonNode rows = get(JOBS + "/" + id + "/rows?from=0&limit=2", R, JsonNode.class).getBody().get("rows");
        assertThat(rows.get(0).get("validation").get("outcome").asText()).isEqualTo("VALID");
        assertThat(rows.get(1).get("validation").isNull()).isTrue();
        assertThat(act(id, "validate", W).getStatusCode().value()).isEqualTo(200);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        JsonNode c = job(id).get("counts");
        assertThat(c.get("valid").asLong()).as("the second pass counts every row afresh: " + c).isEqualTo(2);
        assertThat(c.get("invalid").asLong()).isZero();
    }

    @Test
    void a_correction_is_serialised_with_uploads_and_validation() {
        String id = create("correct-lock");
        csv(id, HEADER + line("TZP-IW-001", 1), W);
        HttpEntity<Object> fix = new HttpEntity<>(BulkProductImportIT.row("TZP-IW-001", 1), headers(W));
        // while an upload (or another correction) holds the lock, a correction is refused instead of interleaving
        assertThat(repo.lockAppend(id, "upload-x", 60_000)).isTrue();
        assertThat(rest.exchange(url(JOBS + "/" + id + "/rows/0"), HttpMethod.PUT, fix, JsonNode.class).getStatusCode().value()).isEqualTo(409);
        repo.unlockAppend(id, "upload-x");
        assertThat(rest.exchange(url(JOBS + "/" + id + "/rows/0"), HttpMethod.PUT, fix, JsonNode.class).getStatusCode().value()).isEqualTo(200);
        Document doc = db.getCollection("import_jobs").find(new Document("_id", id)).first();
        assertThat(doc.containsKey("append_lock_token")).as("the correction released its lock").isFalse();
        assertThat(act(id, "validate", W).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void validation_waits_for_an_upload_and_an_upload_whose_job_left_open_adds_nothing() {
        String id = create("upload-race");
        csv(id, HEADER + line("TZP-IU-001", 1), W);
        // while an upload holds the append lock, validation cannot start (it would freeze a rows_total the upload is still growing)
        assertThat(repo.lockAppend(id, "upload-a", 60_000)).isTrue();
        assertThat(act(id, "validate", W).getStatusCode().value()).isEqualTo(409);
        assertThat(job(id).get("status").asText()).isEqualTo("OPEN");
        repo.unlockAppend(id, "upload-a");

        // the job is cancelled while an upload is streaming: the upload is refused and leaves no row behind
        byte[] head = (HEADER + line("TZP-IU-002", 2)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail = line("TZP-IU-003", 3).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.InputStream cancelMidway = new java.io.InputStream() {
            int pos;
            boolean cancelled;
            @Override
            public int read() {
                if (pos == head.length && !cancelled) {
                    cancelled = true;
                    assertThat(act(id, "cancel", W).getStatusCode().value()).isEqualTo(200);
                }
                if (pos < head.length) return head[pos++] & 0xff;
                int i = pos++ - head.length;
                return i < tail.length ? tail[i] & 0xff : -1;
            }
        };
        assertThatThrownBy(() -> service.appendCsv(id, cancelMidway))
                .isInstanceOf(com.tazzzo.bulkimport.jobs.ImportJobException.class).hasMessageContaining("left OPEN");
        JsonNode j = job(id);
        assertThat(j.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(j.get("rowsTotal").asLong()).as("rows_total is not raised after the job left OPEN").isEqualTo(1);
        assertThat(db.getCollection("import_rows").countDocuments(new Document("job_id", id))).as("the abandoned rows are removed").isEqualTo(1);
    }

    @Test
    void a_failed_csv_upload_leaves_the_job_unchanged_and_the_corrected_file_can_be_uploaded_again() {
        String id = create("atomic-csv");
        csv(id, HEADER + line("TZP-AT-001", 1), W);
        long version = job(id).get("version").asLong();
        String good = line("TZP-AT-002", 2) + line("TZP-AT-003", 3) + line("TZP-AT-004", 4);
        // failure at line 4: an unbalanced quote after three good rows
        ResponseEntity<JsonNode> bad = csv(id, HEADER + good + "TZP-AT-005,\"never closed,BR-JOB,TZV-000001,0.9.0,k,5,kg\n", W);
        assertThat(bad.getStatusCode().value()).as(String.valueOf(bad.getBody())).isEqualTo(422);
        JsonNode j = job(id);
        assertThat(j.get("rowsTotal").asLong()).as("rows_total restored").isEqualTo(1);
        assertThat(j.get("version").asLong()).as("the job is untouched").isEqualTo(version);
        assertThat(db.getCollection("import_rows").countDocuments(new Document("job_id", id))).as("this upload's rows are gone").isEqualTo(1);
        assertThat(db.getCollection("import_jobs").find(new Document("_id", id)).first().containsKey("append_lock_token")).isFalse();
        // other failures: an oversize cell and too many columns
        assertThat(csv(id, HEADER + good + "TZP-AT-006," + "x".repeat(4_001) + ",BR-JOB,TZV-000001,0.9.0,k,5,kg\n", W).getStatusCode().value()).isEqualTo(422);
        assertThat(csv(id, HEADER + good + "TZP-AT-007" + ",".repeat(70) + "\n", W).getStatusCode().value()).isEqualTo(422);
        assertThat(job(id).get("rowsTotal").asLong()).isEqualTo(1);
        assertThat(db.getCollection("import_rows").countDocuments(new Document("job_id", id))).isEqualTo(1);
        // the corrected file succeeds: nothing is flagged DUPLICATE_ROW by a leftover of the failed attempts
        ResponseEntity<JsonNode> ok = csv(id, HEADER + good, W);
        assertThat(ok.getStatusCode().value()).as(String.valueOf(ok.getBody())).isEqualTo(200);
        assertThat(ok.getBody().get("rowsAdded").asLong()).isEqualTo(3);
        assertThat(ok.getBody().get("duplicates").asLong()).isZero();
        assertThat(ok.getBody().get("rowsTotal").asLong()).isEqualTo(4);
        // the row cap fails the same way
        ImportJobService tight = new ImportJobService(repo, new com.tazzzo.common.audit.DomainAudit(db, Clock.systemUTC()), tx,
                new com.fasterxml.jackson.databind.ObjectMapper(), 5, 50);
        String capId = create("atomic-cap");
        assertThatThrownBy(() -> tight.appendCsv(capId, new java.io.ByteArrayInputStream(
                (HEADER + line("TZP-AT-101", 1) + line("TZP-AT-102", 2) + line("TZP-AT-103", 3) + line("TZP-AT-104", 4) + line("TZP-AT-105", 5)
                        + line("TZP-AT-106", 6)).getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(com.tazzzo.bulkimport.jobs.ImportJobException.class).hasMessageContaining("at most 5 rows");
        assertThat(job(capId).get("rowsTotal").asLong()).isZero();
        assertThat(db.getCollection("import_rows").countDocuments(new Document("job_id", capId))).isZero();
        act(id, "cancel", W);
        act(capId, "cancel", W);
    }

    @Test
    void a_failed_upload_after_the_lock_was_taken_over_deletes_nothing_of_the_new_holder() {
        String id = create("atomic-lost-lock");
        // the first upload streams two rows, then the lock is taken over by someone else (expired + re-locked) before it fails
        byte[] head = (HEADER + line("TZP-AL-001", 1) + line("TZP-AL-002", 2)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.InputStream stream = new java.io.InputStream() {
            int pos;
            @Override
            public int read() throws java.io.IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                if (pos < head.length) {
                    int n = Math.min(len, head.length - pos);
                    System.arraycopy(head, pos, b, off, n);
                    pos += n;
                    return n;
                }
                // steal the lock, plant a row of the new holder in the same range, then fail
                db.getCollection("import_jobs").updateOne(new Document("_id", id),
                        new Document("$set", new Document("append_lock_token", "new-holder")));
                db.getCollection("import_rows").insertOne(new Document("_id", id + ":planted").append("job_id", id).append("row", 99L));
                throw new java.io.IOException("connection reset");
            }
        };
        assertThatThrownBy(() -> service.appendCsv(id, stream)).isInstanceOf(java.io.IOException.class);
        assertThat(db.getCollection("import_rows").countDocuments(new Document("_id", id + ":planted")))
                .as("the new holder's row survives the failed upload").isEqualTo(1);
        assertThat(job(id).get("rowsTotal").asLong()).isZero();
        assertThat(db.getCollection("import_jobs").find(new Document("_id", id)).first().getString("append_lock_token"))
                .as("and so does its lock").isEqualTo("new-holder");
        act(id, "cancel", W);
    }

    @Test
    void a_successful_parse_that_lost_its_lock_to_an_uploader_who_published_deletes_nothing_and_gets_409() {
        String id = create("finish-lost-lock");
        byte[] head = (HEADER + line("TZP-FL-001", 1) + line("TZP-FL-002", 2)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.InputStream stream = new java.io.InputStream() {
            int pos;
            @Override
            public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos < head.length) {
                    int n = Math.min(len, head.length - pos);
                    System.arraycopy(head, pos, b, off, n);
                    pos += n;
                    return n;
                }
                // at EOF: a second uploader took the expired lock, wrote rows 0..2 and published rows_total=3, then unlocked
                db.getCollection("import_rows").insertOne(new Document("_id", id + ":planted").append("job_id", id).append("row", 2L));
                db.getCollection("import_jobs").updateOne(new Document("_id", id), new Document("$set", new Document("rows_total", 3L)
                        .append("append_lock_token", "new-holder")).append("$inc", new Document("version", 1L)));
                return -1;
            }
        };
        assertThatThrownBy(() -> service.appendCsv(id, stream)).isInstanceOf(com.tazzzo.bulkimport.jobs.ImportJobException.class);
        assertThat(db.getCollection("import_rows").countDocuments(new Document("job_id", id)))
                .as("all of the new holder's published rows survive").isEqualTo(3);
        assertThat(db.getCollection("import_rows").countDocuments(new Document("_id", id + ":planted"))).isEqualTo(1);
        assertThat(job(id).get("rowsTotal").asLong()).as("and so does its rows_total").isEqualTo(3);
        act(id, "cancel", W);
    }

    @Test
    void a_correction_is_not_rejected_by_unpublished_leftovers_of_a_failed_rollback() {
        String id = create("correct-leftover");
        csv(id, HEADER + line("TZP-CL-001", 1), W);
        // a leftover row past rows_total that carries the identity the corrected payload will use
        db.getCollection("import_rows").insertOne(new Document("_id", id + ":leftover").append("job_id", id).append("row", 1L)
                .append("dedup_key", "TZP-CL-002").append("identity_keys", List.of("id:TZP-CL-002")));
        Map<String, Object> fixed = BulkProductImportIT.row("TZP-CL-002", 2);
        fixed.put("brandCode", "BR-JOB");
        fixed.put("internalKey", "job|TZP-CL-002");
        ResponseEntity<JsonNode> put = rest.exchange(url(JOBS + "/" + id + "/rows/0"), HttpMethod.PUT, new HttpEntity<>(fixed, headers(W)), JsonNode.class);
        assertThat(put.getStatusCode().value()).as(String.valueOf(put.getBody())).isEqualTo(200);
        assertThat(db.getCollection("import_rows").countDocuments(new Document("_id", id + ":leftover"))).isZero();
        act(id, "cancel", W);
    }

    @Test
    void gtin_identity_rows_sharing_an_internal_key_are_not_duplicates() {
        String id = create("gtin-key");
        Map<String, Object> g1 = new java.util.HashMap<>(BulkProductImportIT.gtinRow("TZP-IK-001", 1, "8901234567906"));
        Map<String, Object> g2 = new java.util.HashMap<>(BulkProductImportIT.gtinRow("TZP-IK-002", 2, "8901234567913"));
        g1.put("internalKey", "family|IK");
        g2.put("internalKey", "family|IK");
        ResponseEntity<JsonNode> r = post(JOBS + "/" + id + "/rows", Map.of("rows", List.of(g1, g2)), W, JsonNode.class);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(r.getBody().get("duplicates").asLong()).as("the catalogue ignores a GTIN product's internal key").isZero();
    }

    @Test
    void identities_are_unique_across_appends_and_ids_are_case_sensitive() {
        String id = create("identities");
        csv(id, HEADER + "TZP-ID-001,Rice A,BR-JOB,TZV-000001,0.9.0,job|ID-A,1,kg\n", W);
        ResponseEntity<JsonNode> second = csv(id, HEADER
                + "TZP-ID-002,Rice B,BR-JOB,TZV-000001,0.9.0,job|ID-A,2,kg\n"      // same internal key as row 0 (earlier append)
                + "TZP-ID-001,Rice C,BR-JOB,TZV-000001,0.9.0,job|ID-C,3,kg\n"      // same product id, same case
                + "tzp-id-003,Rice E,BR-JOB,TZV-000001,0.9.0,job|ID-E,5,kg\n"      // lower-cased: a DIFFERENT id, no normalisation
                + "TZP-ID-003,Rice D,BR-JOB,TZV-000001,0.9.0,,,\n", W);
        assertThat(second.getBody().get("duplicates").asLong()).isEqualTo(2);
        assertThat(second.getBody().get("rowsTotal").asLong()).isEqualTo(5);
        JsonNode rows = get(JOBS + "/" + id + "/rows?from=1&limit=4", R, JsonNode.class).getBody().get("rows");
        assertThat(rows.get(0).get("validation").get("code").asText()).isEqualTo("DUPLICATE_IDENTITY");
        assertThat(rows.get(1).get("validation").get("code").asText()).isEqualTo("DUPLICATE_ROW");
        assertThat(rows.get(2).get("validation").isNull()).as("the third row is not a duplicate").isTrue();
        assertThat(rows.get(2).get("id").asText()).isEqualTo("tzp-id-003");
        assertThat(rows.get(3).get("validation").isNull()).as("a lower-cased id is a different id").isTrue();
        assertThat(rows.get(3).get("id").asText()).isEqualTo("TZP-ID-003");
        // a GTIN seen in an earlier append is a duplicate in a later JSON append too
        Map<String, Object> g1 = BulkProductImportIT.gtinRow("TZP-ID-004", 4, "8901234567890");
        Map<String, Object> g2 = BulkProductImportIT.gtinRow("TZP-ID-005", 5, "8901234567890");
        assertThat(post(JOBS + "/" + id + "/rows", Map.of("rows", List.of(g1)), W, JsonNode.class).getBody().get("duplicates").asLong()).isZero();
        assertThat(post(JOBS + "/" + id + "/rows", Map.of("rows", List.of(g2)), W, JsonNode.class).getBody().get("duplicates").asLong()).isEqualTo(1);
    }

    @Test
    void stale_versions_limits_expired_leases_and_the_append_lock_are_enforced() {
        String id = create("guards");
        csv(id, HEADER + line("TZP-IG-001", 1), W);
        long v = job(id).get("version").asLong();
        ResponseEntity<JsonNode> stale = post(JOBS + "/" + id + "/validate", Map.of("version", v - 1), W, JsonNode.class);
        assertThat(stale.getStatusCode().value()).as("a stale decision is refused").isEqualTo(409);
        assertThat(job(id).get("status").asText()).isEqualTo("OPEN");
        // limits: a service with room for 1 row / 1 active job
        ImportJobService tight = new ImportJobService(repo, new com.tazzzo.common.audit.DomainAudit(db, Clock.systemUTC()), tx,
                new com.fasterxml.jackson.databind.ObjectMapper(), 1, 1);
        com.tazzzo.catalog.api.ApiDtos.CreateProductRequest extra = new com.tazzzo.catalog.api.ApiDtos.CreateProductRequest("TZP-IG-002", "single",
                "internal", "job|IG-2", null, "BR-JOB", "Job rice 2 kg", "TZV-000001", "0.9.0", "provisional", Map.of("pack_size", 2, "pack_unit", "kg"),
                List.of(), null, null);
        assertThatThrownBy(() -> tight.appendRows(id, List.of(extra)))
                .isInstanceOf(com.tazzzo.bulkimport.jobs.ImportJobException.class).hasMessageContaining("at most 1 rows");
        assertThatThrownBy(() -> tight.create("products", "one too many", TestActors.TEST))
                .isInstanceOf(com.tazzzo.bulkimport.jobs.ImportJobException.class).hasMessageContaining("too many active import jobs");
        assertThat(get(JOBS + "/IMPJ-000000000000000000000000/errors.csv", R, String.class).getStatusCode().value()).isEqualTo(404);
        // the append lock: while one upload holds it, another is refused; it does not outlive the OPEN state
        assertThat(repo.lockAppend(id, "upload-a", 60_000)).isTrue();
        assertThat(csv(id, HEADER + line("TZP-IG-003", 3), W).getStatusCode().value()).isEqualTo(409);
        repo.unlockAppend(id, "upload-a");
        assertThat(csv(id, HEADER + line("TZP-IG-003", 3), W).getStatusCode().value()).isEqualTo(200);
        // an expired lease is reclaimable; a live one renews
        act(id, "validate", W);
        ImportJobRepository past = new ImportJobRepository(db, Clock.offset(Clock.systemUTC(), java.time.Duration.ofMinutes(-10)));
        assertThat(past.claim("worker-old", 60_000).id()).as("claimed 10 minutes ago, lease of 1 minute").isEqualTo(id);
        assertThat(repo.claim("worker-new", 60_000).id()).as("the old lease has expired by now").isEqualTo(id);
        assertThat(repo.renewLease(id, "worker-old", 60_000)).as("the old holder cannot renew").isFalse();
        assertThat(repo.renewLease(id, "worker-new", 60_000)).isTrue();
        java.util.Date until = db.getCollection("import_jobs").find(new Document("_id", id)).first().getDate("lease_until");
        assertThat(until.getTime()).isGreaterThan(System.currentTimeMillis() + 50_000);
        assertThat(repo.releaseLease(id, "worker-new")).isTrue();
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        // a ledger shorter than rows_total (a row document lost) ends the phase with a reason instead of being claimed forever
        String shortId = create("short");
        csv(shortId, HEADER + line("TZP-IG-004", 4) + line("TZP-IG-005", 5), W);
        db.getCollection("import_rows").deleteOne(new Document("_id", shortId + ":000000001"));
        act(shortId, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.REJECTED);
        assertThat(job(shortId).get("lastError").asText()).contains("row ledger short");
        assertThat(worker.tick().jobId()).isNull();
    }

    @Test
    void authorisation_and_shape() {
        assertThat(post(JOBS, Map.of("kind", "products"), R, JsonNode.class).getStatusCode().value()).as("reader cannot write").isEqualTo(403);
        assertThat(post(JOBS, Map.of("kind", "products"), null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        ResponseEntity<JsonNode> badKind = post(JOBS, Map.of("kind", "prices"), W, JsonNode.class);
        assertThat(badKind.getStatusCode().value()).isEqualTo(422);
        assertThat(badKind.getBody().get("error").get("code").asText()).isEqualTo("INVALID_IMPORT");
        ResponseEntity<JsonNode> missing = get(JOBS + "/IMPJ-000000000000000000000000", R, JsonNode.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody().get("error").get("code").asText()).isEqualTo("IMPORT_JOB_NOT_FOUND");
        String id = create("shape");
        ResponseEntity<JsonNode> badFile = csv(id, "id,title\nTZP-1,x\n", W);
        assertThat(badFile.getStatusCode().value()).isEqualTo(422);
        assertThat(badFile.getBody().get("error").get("message").asText()).contains("brandCode");
        assertThat(act(id, "validate", W).getStatusCode().value()).as("no rows yet").isEqualTo(422);
        ResponseEntity<JsonNode> json = post(JOBS + "/" + id + "/rows", Map.of("rows", List.of(BulkProductImportIT.row("TZP-IS-001", 1))), W, JsonNode.class);
        assertThat(json.getStatusCode().value()).as(String.valueOf(json.getBody())).isEqualTo(200);
        assertThat(json.getBody().get("rowsTotal").asLong()).isEqualTo(1);
        JsonNode list = get(JOBS + "?status=open&limit=5", R, JsonNode.class).getBody();
        assertThat(list.get("jobs").findValuesAsText("id")).contains(id);
        assertThat(get(JOBS + "/" + id + "/errors.csv", R, String.class).getStatusCode().value()).isEqualTo(200);
    }

    ResponseEntity<String> chunkedCsv(String jobId, byte[] body, String token) throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url(JOBS + "/" + jobId + "/rows")))
                .header("Content-Type", "text/csv")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body)));  // no Content-Length: chunked
        if (token != null) b.header("Authorization", "Bearer " + token);
        java.net.http.HttpResponse<String> r = client.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        return ResponseEntity.status(r.statusCode()).body(r.body());
    }

    @Test
    void a_chunked_csv_over_the_body_limit_is_413_in_the_platform_envelope_and_leaves_the_job_unchanged() throws Exception {
        String id = create("chunked-413");
        csv(id, HEADER + line("TZP-CK-001", 1), W);
        long version = job(id).get("version").asLong();
        // just over the 2 MiB bulk bound (by less than Tomcat's 64 KiB max-swallow-size, so the client reliably reads the 413 instead of a reset):
        // hundreds of valid-shape rows are stored before the bound trips, then all of them are rolled back
        StringBuilder big = new StringBuilder(HEADER);
        String wide = "w".repeat(3_000);   // wide valid-shape rows: the bound trips after a few hundred inserts, not tens of thousands
        for (int i = 2; big.length() < 2_097_152 + 8_192; i++) big.append(line("TZP-CK-" + i, i).replace("Job rice", wide));
        ResponseEntity<String> over = chunkedCsv(id, big.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), W);
        assertThat(over.getStatusCode().value()).as(over.getBody()).isEqualTo(413);
        JsonNode err = new com.fasterxml.jackson.databind.ObjectMapper().readTree(over.getBody());
        assertThat(err.at("/error/code").asText()).isEqualTo(com.tazzzo.catalog.api.RequestBodyLimitFilter.CODE);
        assertThat(err.at("/error/request_id").asText()).isNotBlank();
        assertThat(over.getBody()).doesNotContain("Exception").doesNotContain("TZP-CK");
        JsonNode j = job(id);
        assertThat(j.get("rowsTotal").asLong()).isEqualTo(1);
        assertThat(j.get("version").asLong()).as("the job is untouched").isEqualTo(version);
        assertThat(db.getCollection("import_rows").countDocuments(new Document("job_id", id))).as("no partial rows").isEqualTo(1);
        assertThat(db.getCollection("import_jobs").find(new Document("_id", id)).first().containsKey("append_lock_token")).isFalse();
        // authentication still comes first, and a chunked body under the bound still succeeds
        assertThat(chunkedCsv(id, (HEADER + line("TZP-CK-900", 9)).getBytes(java.nio.charset.StandardCharsets.UTF_8), null).getStatusCode().value()).isEqualTo(401);
        assertThat(chunkedCsv(id, (HEADER + line("TZP-CK-900", 9)).getBytes(java.nio.charset.StandardCharsets.UTF_8), R).getStatusCode().value()).isEqualTo(403);
        ResponseEntity<String> ok = chunkedCsv(id, (HEADER + line("TZP-CK-900", 9)).getBytes(java.nio.charset.StandardCharsets.UTF_8), W);
        assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(200);
        assertThat(job(id).get("rowsTotal").asLong()).isEqualTo(2);
        act(id, "cancel", W);
    }

    @Test
    void a_malformed_query_on_the_job_routes_gets_the_platform_filters_fixed_400() throws java.io.IOException {
        for (String path : new String[]{JOBS + "?limit=%ZZ", JOBS + "/IMPJ-000000000000000000000000/rows?from=%ZZ"}) {
            try (java.net.Socket socket = new java.net.Socket("localhost", port)) {   // java.net.URI refuses %ZZ: go raw
                socket.setSoTimeout(15000);
                socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                        + "Authorization: Bearer " + R + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                String all = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(all.substring(9, 12)).as(path).isEqualTo("400");
                String body = all.substring(all.indexOf('{'), all.lastIndexOf('}') + 1);
                assertThat(body).contains("\"MALFORMED_REQUEST\"").contains("query string is malformed")
                        .doesNotContain("%ZZ").doesNotContain("limit").doesNotContain("Exception");
            }
        }
    }

    @Test
    void csv_ids_with_embedded_or_trailing_whitespace_are_invalid_rows_not_silently_trimmed() {
        String id = create("csv id whitespace");
        ResponseEntity<JsonNode> added = csv(id, HEADER + line("TZP-WS-1", 1).replaceFirst("^TZP-WS-1,", "\"TZP-WS-1\n\",")
                + line("TZP-WS-2", 2).replaceFirst("^TZP-WS-2,", "\" TZP-WS-2\",")
                + line("TZP-WS-3", 3).replace("\n", "\r\n"), W);
        assertThat(added.getStatusCode().value()).as(String.valueOf(added.getBody())).isEqualTo(200);
        act(id, "validate", W);
        for (int n = 0; n < 5 && "VALIDATING".equals(job(id).get("status").asText()); n++) worker.tick();   // other tests may leave older jobs to claim first
        assertThat(job(id).get("status").asText()).isEqualTo("REJECTED");
        JsonNode page = get(JOBS + "/" + id + "/rows?from=0&limit=3", R, JsonNode.class).getBody().get("rows");
        assertThat(page.get(0).get("validation").get("code").asText()).isEqualTo("INVALID_ROW");
        assertThat(page.get(1).get("validation").get("code").asText()).isEqualTo("INVALID_ROW");
        assertThat(page.get(2).get("validation").get("outcome").asText()).as("CRLF row end outside quotes still works").isEqualTo("VALID");
    }

    @Test
    void invalid_product_ids_are_per_row_invalid_block_apply_and_are_corrected_with_case_preserved() {
        String id = create("id grammar");
        String[] bad = {"TZP-a_b", "TZP-", "TZP-" + "x".repeat(41), "TZP-1\n", "tzp-1", "TZP-a b"};
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < bad.length; i++) rows.add(grammarRow(bad[i], i + 1));
        rows.add(grammarRow("TZP-med-3", 7));      // valid, and distinct from the next row and from TZP-Med-3
        rows.add(grammarRow("TZP-MED-3", 8));
        ResponseEntity<JsonNode> added = post(JOBS + "/" + id + "/rows", Map.of("rows", rows), W, JsonNode.class);
        assertThat(added.getStatusCode().value()).as(String.valueOf(added.getBody())).isEqualTo(200);
        assertThat(added.getBody().get("rowsTotal").asLong()).isEqualTo(8);
        assertThat(added.getBody().get("duplicates").asLong()).as("med-3 and MED-3 are different ids").isZero();

        act(id, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.REJECTED);
        JsonNode j = job(id);
        assertThat(j.get("counts").get("invalid").asLong()).isEqualTo(6);
        assertThat(j.get("counts").get("valid").asLong()).isEqualTo(2);
        JsonNode page = get(JOBS + "/" + id + "/rows?from=0&limit=8", R, JsonNode.class).getBody().get("rows");
        for (int i = 0; i < 6; i++) {
            assertThat(page.get(i).get("validation").get("outcome").asText()).as("row " + i).isEqualTo("INVALID");
            assertThat(page.get(i).get("validation").get("code").asText()).as("row " + i).isEqualTo("INVALID_ROW");
            assertThat(page.get(i).get("validation").get("message").asText()).contains("^TZP-[A-Za-z0-9-]{1,40}$");
        }
        assertThat(page.get(6).get("validation").get("outcome").asText()).isEqualTo("VALID");
        assertThat(page.get(7).get("validation").get("outcome").asText()).isEqualTo("VALID");
        String errors = rest.exchange(url(JOBS + "/" + id + "/errors.csv"), HttpMethod.GET, new HttpEntity<>(headers(R)), String.class).getBody();
        assertThat(errors.split(",validation,INVALID,INVALID_ROW,", -1).length - 1).as("six INVALID_ROW lines in errors.csv").isEqualTo(6);
        assertThat(errors).contains("TZP-a_b").contains("tzp-1").contains("TZP-a b");
        assertThat(act(id, "apply", W).getStatusCode().value()).as("a REJECTED job cannot be applied").isEqualTo(409);
        assertThat(db.getCollection("products").countDocuments(new Document("_id", new Document("$regex", "^(?i)tzp-(med-3|a_b|1|a b)")))).isZero();

        String[] fixed = {"TZP-Med-3", "TZP-PG-2", "TZP-PG-3", "TZP-PG-4", "TZP-PG-5", "TZP-PG-6"};
        for (int i = 0; i < 6; i++) {
            ResponseEntity<JsonNode> put = rest.exchange(url(JOBS + "/" + id + "/rows/" + i), HttpMethod.PUT,
                    new HttpEntity<>(grammarRow(fixed[i], i + 1), headers(W)), JsonNode.class);
            assertThat(put.getStatusCode().value()).as("row " + i + " " + put.getBody()).isEqualTo(200);
        }
        act(id, "validate", W);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.VALIDATED);
        assertThat(job(id).get("counts").get("valid").asLong()).isEqualTo(8);
        assertThat(act(id, "apply", W).getStatusCode().value()).isEqualTo(200);
        assertThat(worker.tick().endedAs()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(job(id).get("counts").get("applied").asLong()).isEqualTo(8);
        for (String created : new String[]{"TZP-Med-3", "TZP-med-3", "TZP-MED-3"}) {
            assertThat(db.getCollection("products").find(new Document("_id", created)).first()).as(created).isNotNull();
        }
        assertThat(db.getCollection("products").countDocuments(new Document("_id", new Document("$regex", "^TZP-(Med|med|MED)-3$"))))
                .as("three distinct, case-preserved products").isEqualTo(3);
    }

    static Map<String, Object> grammarRow(String productId, int n) {
        Map<String, Object> m = BulkProductImportIT.row(productId, n);
        m.put("brandCode", "BR-JOB");
        m.put("internalKey", "grammar|" + n);
        return m;
    }
}
