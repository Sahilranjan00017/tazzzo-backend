package com.tazzzo.bulkimport.jobs;

import com.mongodb.MongoSocketReadException;
import com.mongodb.ServerAddress;
import com.tazzzo.bulkimport.ImportMetrics;
import com.tazzzo.bulkimport.ProductImportValidator;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.Tx;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every place the worker stops because it lost its lease, pauses, or ends a tick increments its meter, with the repository
 * mocked so each branch is reached on purpose (the Mongo-backed flows are in ImportMetricsIT).
 */
class ImportJobWorkerMetricsTest {

    final ImportJobRepository repo = mock(ImportJobRepository.class);
    final ImportJobService service = mock(ImportJobService.class);
    final ProductImportValidator validator = mock(ProductImportValidator.class);
    final MintService mint = mock(MintService.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ProductDraft draft = mock(ProductDraft.class);
    ImportJobWorker worker;

    @BeforeEach
    void wire() {
        worker = new ImportJobWorker(repo, service, validator, mint, mock(Tx.class), Clock.systemUTC(), 100, 60_000, 30_000,
                new ImportMetrics(registry));
        when(draft.id()).thenReturn("TZP-W-1");
    }

    ImportJob job(ImportJob.Status status, long rows, boolean approved) {
        ImportJob.Actor by = approved ? new ImportJob.Actor("SERVICE_ACCOUNT", "svc", "cred", "req_x") : null;
        return new ImportJob("IMPJ-w", ImportJob.Kind.PRODUCTS, status, null, by, by, rows, ImportJob.Counts.ZERO, 0, 1, null,
                Instant.now(), Instant.now(), null, null, 5);
    }

    Document row(long n, String validationOutcome) {
        return new Document("row", n).append("payload", new Document()).append("validation", new Document("outcome", validationOutcome));
    }

    void claims(ImportJob job, Document... rows) {
        when(repo.claim(anyString(), anyLong())).thenReturn(job);
        when(repo.page(eq("IMPJ-w"), anyLong(), anyInt(), anyLong())).thenReturn(List.of(rows));
        when(validator.validateRows(any())).thenReturn(new ProductImportValidator.RowChecks(Map.of(0, draft), Set.of(), List.of()));
    }

    double count(String name, String... tags) {
        double n = 0;
        for (var c : registry.find(name).tags(tags).counters()) n += c.count();
        return n;
    }

    long ticks(String result) {
        var t = registry.find(ImportMetrics.TICK_DURATION).tag("result", result).timer();
        return t == null ? 0 : t.count();
    }

    @Test
    void validate_phase_lease_losses_are_counted_at_the_renewal_and_at_the_verdict_write() {
        claims(job(ImportJob.Status.VALIDATING, 1, false), row(0, "VALID"));
        when(repo.renewLease(anyString(), anyString(), anyLong())).thenReturn(false);
        worker.tick();
        assertThat(count(ImportMetrics.LEASE_LOST, "phase", "validate")).isEqualTo(1);

        when(repo.renewLease(anyString(), anyString(), anyLong())).thenReturn(true);
        when(repo.recordValidation(anyString(), anyString(), any(), anyLong(), any(), anyLong(), any())).thenReturn(false);
        worker.tick();
        assertThat(count(ImportMetrics.LEASE_LOST, "phase", "validate")).isEqualTo(2);
        assertThat(count(ImportMetrics.LEASE_LOST, "phase", "apply")).isZero();
        assertThat(ticks("worked")).isEqualTo(2);
    }

    @Test
    void apply_phase_lease_losses_are_counted_at_each_of_the_four_write_points_and_no_row_is_counted_for_a_dropped_verdict() {
        claims(job(ImportJob.Status.APPLYING, 2, true), row(0, "VALID"), row(1, "INVALID"));

        // 1. the renewal before the batch
        when(repo.renewLease(anyString(), anyString(), anyLong())).thenReturn(false);
        worker.tick();
        assertThat(count(ImportMetrics.LEASE_LOST, "phase", "apply")).isEqualTo(1);

        // 2. the renewal before the mint
        when(repo.renewLease(anyString(), anyString(), anyLong())).thenReturn(true, false);
        worker.tick();
        assertThat(count(ImportMetrics.LEASE_LOST, "phase", "apply")).isEqualTo(2);

        // 3. the verdict write after the mint
        when(repo.renewLease(anyString(), anyString(), anyLong())).thenReturn(true);
        when(repo.recordApply(anyString(), anyString(), anyLong(), anyString(), any(), any(), anyLong(), any(), anyLong(), any())).thenReturn(false);
        worker.tick();
        assertThat(count(ImportMetrics.LEASE_LOST, "phase", "apply")).isEqualTo(3);
        assertThat(count(ImportMetrics.ROWS_APPLIED)).as("a verdict nobody recorded is not an applied row").isZero();

        // 4. the cursor write that passes the trailing non-candidate row
        when(repo.recordApply(anyString(), anyString(), anyLong(), anyString(), any(), any(), anyLong(), any(), anyLong(), any())).thenReturn(true);
        when(repo.progress(anyString(), anyString(), anyLong(), any(), anyLong())).thenReturn(false);
        worker.tick();
        assertThat(count(ImportMetrics.LEASE_LOST, "phase", "apply")).isEqualTo(4);
        assertThat(count(ImportMetrics.ROWS_APPLIED)).as("this time the verdict was recorded").isEqualTo(1);
    }

    @Test
    void a_datastore_failure_counts_pause_and_transition_only_when_the_pause_was_recorded_otherwise_it_is_a_lost_lease() {
        claims(job(ImportJob.Status.APPLYING, 1, true), row(0, "VALID"));
        when(repo.renewLease(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mint.mint(any(), any())).thenThrow(new MongoSocketReadException("gone", new ServerAddress()));

        when(repo.recordPause(anyString(), anyString(), anyLong(), anyString(), any())).thenReturn(false);
        worker.tick();
        assertThat(count(ImportMetrics.PAUSED)).isZero();
        assertThat(count(ImportMetrics.JOB_TRANSITIONS, "to", "paused")).isZero();
        assertThat(count(ImportMetrics.LEASE_LOST, "phase", "apply")).isEqualTo(1);

        when(repo.recordPause(anyString(), anyString(), anyLong(), anyString(), any())).thenReturn(true);
        assertThat(worker.tick().paused()).isTrue();
        assertThat(count(ImportMetrics.PAUSED, "reason", "datastore_failure")).isEqualTo(1);
        assertThat(count(ImportMetrics.JOB_TRANSITIONS, "to", "paused")).isEqualTo(1);
        assertThat(ticks("paused")).isEqualTo(1);
    }

    @Test
    void the_other_pause_reasons_and_the_tick_results_are_counted() {
        // no approver
        when(repo.claim(anyString(), anyLong())).thenReturn(job(ImportJob.Status.APPLYING, 1, false));
        assertThat(worker.tick().paused()).isTrue();
        assertThat(count(ImportMetrics.PAUSED, "reason", "no_approver")).isEqualTo(1);

        // the row ledger is shorter than rows_total
        when(repo.claim(anyString(), anyLong())).thenReturn(job(ImportJob.Status.APPLYING, 3, true));
        when(repo.page(eq("IMPJ-w"), anyLong(), anyInt(), anyLong())).thenReturn(List.of());
        assertThat(worker.tick().paused()).isTrue();
        assertThat(count(ImportMetrics.PAUSED, "reason", "ledger_short")).isEqualTo(1);

        // nothing to claim, a failing claim, and a tick that throws
        when(repo.claim(anyString(), anyLong())).thenReturn(null);
        worker.tick();
        assertThat(ticks("idle")).isEqualTo(1);
        when(repo.claim(anyString(), anyLong())).thenThrow(new IllegalStateException("db down"));
        worker.tick();
        assertThat(ticks("claim_failed")).isEqualTo(1);
        org.mockito.Mockito.doReturn(job(ImportJob.Status.VALIDATING, 1, false)).when(repo).claim(anyString(), anyLong());
        org.mockito.Mockito.doThrow(new IllegalStateException("page failed")).when(repo).page(eq("IMPJ-w"), anyLong(), anyInt(), anyLong());
        worker.tick();
        assertThat(ticks("error")).isEqualTo(1);
        assertThat(ticks("paused")).isEqualTo(2);
    }
}
