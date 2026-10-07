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
                Clock.systemUTC(), 500, 60_000, 30_000);
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
}
