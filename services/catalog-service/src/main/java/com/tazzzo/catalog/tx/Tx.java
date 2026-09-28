package com.tazzzo.catalog.tx;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.function.Function;

/** Explicit ClientSession transactions. All T1-T7 services run through this. */
@Component
public class Tx {

    private final MongoClient client;

    public Tx(MongoClient client) {
        this.client = client;
    }

    /**
     * Side-effect-only transaction. {@code ClientSession.withTransaction} may invoke {@code body}
     * MORE THAN ONCE (a transient-transaction-error retry), so the callback must be retry-safe.
     * Do NOT smuggle a result out of {@code body} through an external holder (array, atomic,
     * captured field): a value stored by an attempt whose commit was then rolled back can survive
     * into a retry that returns without overwriting it. Anything needed after commit must be
     * returned through {@link #call}.
     */
    public void run(Consumer<ClientSession> body) {
        try (ClientSession session = client.startSession()) {
            session.withTransaction(() -> {
                body.accept(session);
                return null;
            });
        }
    }

    /**
     * A retry-safe transaction that RETURNS a value. {@code ClientSession.withTransaction} may
     * invoke {@code body} more than once (a transient-transaction-error retry) — the value returned
     * here is always the LAST successful execution's result, straight from the driver, never a
     * mutable holder mutated across attempts (the PR-11B/PR-11C lesson: a holder invites subtle
     * stale-read bugs across retries; returning T directly from the driver's own retry loop does not).
     */
    public <T> T call(Function<ClientSession, T> body) {
        try (ClientSession session = client.startSession()) {
            return session.withTransaction(() -> body.apply(session));
        }
    }
}
