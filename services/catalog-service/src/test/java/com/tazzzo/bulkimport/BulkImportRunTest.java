package com.tazzzo.bulkimport;

import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.inventory.InventoryNotFoundException;
import com.tazzzo.pricing.InvalidPriceException;
import com.tazzzo.pricing.PriceConflictException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The apply loop without Mongo: outcome per row, failure codes, the stop-on-datastore-failure rule and dry runs. */
class BulkImportRunTest {

    final Tx tx = mock(Tx.class);
    final BulkImportService service = new BulkImportService(null, null, null, mock(DomainAudit.class), tx);
    final List<String> attempted = new ArrayList<>();

    Function<String, Long> apply(Function<String, Long> f) {
        return k -> {
            attempted.add(k);
            return f.apply(k);
        };
    }

    @Test
    void domain_failures_fail_their_row_only_with_a_mapped_code() {
        BulkImportDtos.ImportReport r = service.run("prices", false, List.of("a", "b", "c", "d"), k -> k, apply(k -> switch (k) {
            case "b" -> throw new PriceConflictException("stale");
            case "c" -> throw new InvalidPriceException("bad");
            case "d" -> throw new InventoryNotFoundException("gone");
            default -> 3L;
        }), TestActors.TEST);
        assertThat(attempted).containsExactly("a", "b", "c", "d");
        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(3);
        assertThat(r.notAttempted()).isZero();
        assertThat(r.results()).extracting(BulkImportDtos.RowResult::code).containsExactly(null, "STALE_VERSION", "INVALID_ROW", "NOT_FOUND");
        assertThat(r.results().get(0).version()).isEqualTo(3L);
        verify(tx, times(1)).run(any());
    }

    @Test
    void a_datastore_failure_stops_the_run_and_the_rest_are_not_attempted() {
        BulkImportDtos.ImportReport r = service.run("inventory", false, List.of("a", "b", "c", "d"), k -> k, apply(k -> {
            if (k.equals("b")) throw new IllegalStateException("mongo down");
            return 1L;
        }), TestActors.TEST);
        assertThat(attempted).containsExactly("a", "b");
        assertThat(r.results()).extracting(BulkImportDtos.RowResult::outcome)
                .containsExactly("APPLIED", "FAILED", "NOT_ATTEMPTED", "NOT_ATTEMPTED");
        assertThat(r.results().get(1).code()).isEqualTo("UNAVAILABLE");
        assertThat(r.results().get(1).message()).doesNotContain("mongo");
        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.notAttempted()).isEqualTo(2);
    }

    @Test
    void a_dry_run_attempts_nothing_and_audits_nothing() {
        BulkImportDtos.ImportReport r = service.run("prices", true, List.of("a", "b"), k -> k, apply(k -> 1L), TestActors.TEST);
        assertThat(attempted).isEmpty();
        assertThat(r.results()).extracting(BulkImportDtos.RowResult::outcome).containsExactly("VALID", "VALID");
        verify(tx, never()).run(any());
    }

    @Test
    void a_failed_summary_audit_does_not_turn_an_applied_run_into_an_error() {
        doThrow(new IllegalStateException("audit down")).when(tx).run(any());
        BulkImportDtos.ImportReport r = service.run("prices", false, List.of("a"), k -> k, apply(k -> 1L), TestActors.TEST);
        assertThat(r.applied()).isEqualTo(1);
    }
}
