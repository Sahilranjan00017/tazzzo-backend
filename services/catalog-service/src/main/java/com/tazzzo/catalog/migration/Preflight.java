package com.tazzzo.catalog.migration;

import java.util.List;

/**
 * Read-only assessment of one migration against the CURRENT database state. A preflight never mutates.
 *
 * @param status     READY (apply would mutate), ALREADY_SATISFIED (target state already holds, nothing to do)
 *                   or BLOCKED (must stop; an operator decision is required)
 * @param operations the intended operations, in order (empty unless READY)
 * @param blockers   why it is blocked (non-empty only when BLOCKED); never contains secrets
 * @param notes      informational detail
 */
public record Preflight(Status status, List<String> operations, List<String> blockers, List<String> notes) {

    public enum Status { READY, ALREADY_SATISFIED, BLOCKED }

    public Preflight {
        operations = List.copyOf(operations);
        blockers = List.copyOf(blockers);
        notes = List.copyOf(notes);
    }

    public static Preflight ready(List<String> operations, List<String> notes) {
        return new Preflight(Status.READY, operations, List.of(), notes);
    }

    public static Preflight satisfied(String... notes) {
        return new Preflight(Status.ALREADY_SATISFIED, List.of(), List.of(), List.of(notes));
    }

    public static Preflight blocked(List<String> blockers) {
        return new Preflight(Status.BLOCKED, List.of(), blockers, List.of());
    }

    /** True when applying would change the database. */
    public boolean wouldMutate() {
        return status == Status.READY;
    }
}
