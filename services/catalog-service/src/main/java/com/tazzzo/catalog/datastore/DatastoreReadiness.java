package com.tazzzo.catalog.datastore;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The one authoritative answer to "may a scheduled business worker act yet?" (DB-4 hardening, M1).
 *
 * <p>{@code @Scheduled} work starts at context refresh, BEFORE any {@code ApplicationRunner}, so the startup
 * verifier cannot run ahead of it by ordering alone. Instead every scheduled task is routed through
 * {@link GatedTaskScheduler}, which runs it only while {@link #workersPermitted()} is true. The gate is closed from
 * the first instant and opens exactly once, at the very end of a successful startup of a SERVING process:
 *
 * <pre>
 *   PENDING --verifier ok--> VERIFIED --migration startup runner ok (serving mode)--> OPEN
 *   PENDING --verifier refuses--> REFUSED   (never opens)
 *   PENDING --migration job mode (DRY_RUN / APPLY)--> JOB   (never opens: a migration job runs no business workers)
 * </pre>
 *
 * Refusal and job states are terminal and win over every later transition. Nothing here touches the database.
 */
public final class DatastoreReadiness {

    public enum State { PENDING, VERIFIED, OPEN, REFUSED, JOB }

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    public State state() {
        return state.get();
    }

    /** True only when the datastore is verified AND startup finished in a serving mode. */
    public boolean workersPermitted() {
        return state.get() == State.OPEN;
    }

    /** The datastore verification finished and did not refuse the process. */
    public void markVerified() {
        state.compareAndSet(State.PENDING, State.VERIFIED);
    }

    /** Terminal: the verifier refused startup; no worker may ever act in this process. */
    public void markRefused() {
        state.set(State.REFUSED);
    }

    /** Terminal: this process is a migration job (DRY_RUN / APPLY); it runs no business workers. */
    public void markJob() {
        state.updateAndGet(s -> s == State.REFUSED ? s : State.JOB);
    }

    /** Opens the gate; a no-op unless the datastore was verified (fail closed) and the process is not a job. */
    public void openWorkers() {
        state.compareAndSet(State.VERIFIED, State.OPEN);
    }
}
