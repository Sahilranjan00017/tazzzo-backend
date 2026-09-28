package com.tazzzo.catalog.tx;

import com.mongodb.MongoException;
import com.tazzzo.catalog.AbstractMongoIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-11D — the {@link Tx#call} contract against the REAL driver retry loop: the callback may run
 * more than once, and the value returned is exactly the last (committed) attempt's result.
 */
class TxCallRetryContractIT extends AbstractMongoIT {

    @Autowired Tx tx;

    private static MongoException transientError() {
        MongoException e = new MongoException("simulated transient transaction error");
        e.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return e;
    }

    @Test void call_reinvokes_the_callback_on_a_transient_error_and_returns_the_last_attempts_result() {
        AtomicInteger attempts = new AtomicInteger();
        String result = tx.call(session -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                throw transientError();
            }
            return "attempt-" + n;
        });
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(result).isEqualTo("attempt-3");
    }

    @Test void call_result_of_an_attempt_that_was_rolled_back_never_escapes() {
        AtomicInteger attempts = new AtomicInteger();
        String result = tx.call(session -> {
            int n = attempts.incrementAndGet();
            String value = "attempt-" + n;
            if (n == 1) {
                throw transientError(); // the value computed by attempt 1 is discarded with its rollback
            }
            return value;
        });
        assertThat(result).isEqualTo("attempt-2");
    }
}
