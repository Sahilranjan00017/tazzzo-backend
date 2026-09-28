package com.tazzzo.catalog.tx;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * PR-11D test double: a REAL {@link Tx} (real session, real Mongo transaction, the driver's OWN
 * {@code withTransaction} retry loop) that deterministically simulates "the commit failed with a
 * TransientTransactionError" by throwing a labeled {@link MongoException} AFTER the callback body
 * ran and its writes were applied, so the driver aborts that attempt (rolling the writes back) and
 * re-invokes the callback. No sleeps, no server failpoints (Testcontainers Mongo has no
 * {@code enableTestCommands}).
 *
 * <p>{@link #arm} is per-call and single-shot: it is consumed by the next {@code call}/{@code run}.
 * {@code beforeAttempt(n)} runs at the START of attempt {@code n} (1-based), after the previous
 * attempt was aborted — the place to commit a competing change so the retry follows a different path.
 */
public class RetryInjectingTx extends Tx {

    private final MongoClient client;
    private volatile int failAfterBodyAttempts;
    private volatile IntConsumer beforeAttempt = n -> { };
    private final List<Object> attemptResults = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger attempts = new AtomicInteger();

    public RetryInjectingTx(MongoClient client) {
        super(client);
        this.client = client;
    }

    /** The next transaction fails (transient, post-body) on its first {@code failAfterBody} attempts. */
    public RetryInjectingTx arm(int failAfterBody, IntConsumer beforeAttempt) {
        this.failAfterBodyAttempts = failAfterBody;
        this.beforeAttempt = beforeAttempt;
        this.attemptResults.clear();
        this.attempts.set(0);
        return this;
    }

    public RetryInjectingTx arm(int failAfterBody) {
        return arm(failAfterBody, n -> { });
    }

    /** What each callback attempt returned, in order (including attempts that were rolled back). */
    public List<Object> attemptResults() {
        return List.copyOf(attemptResults);
    }

    public int attempts() {
        return attempts.get();
    }

    @Override
    public <T> T call(Function<ClientSession, T> body) {
        int failFor = failAfterBodyAttempts;
        IntConsumer before = beforeAttempt;
        disarm();
        try (ClientSession session = client.startSession()) {
            return session.withTransaction(() -> {
                int n = attempts.incrementAndGet();
                before.accept(n);
                T result = body.apply(session);
                attemptResults.add(result);
                if (n <= failFor) {
                    MongoException transientError = new MongoException("simulated transient commit failure");
                    transientError.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                    throw transientError;
                }
                return result;
            });
        }
    }

    @Override
    public void run(Consumer<ClientSession> body) {
        call(session -> {
            body.accept(session);
            return Boolean.TRUE;
        });
    }

    private void disarm() {
        failAfterBodyAttempts = 0;
        beforeAttempt = n -> { };
    }
}
