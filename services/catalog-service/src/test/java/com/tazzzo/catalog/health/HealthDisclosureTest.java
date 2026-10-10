package com.tazzzo.catalog.health;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mongodb.MongoException;
import com.tazzzo.catalog.datastore.DatastoreReadiness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The probe paths are unauthenticated, so what they say when a dependency fails must be bounded words only: no host, port,
 * credential or exception text in the body, and none in the log line either (component name and exception CLASS only).
 */
class HealthDisclosureTest {

    static final String SECRET_URI = "mongodb://svc-user:s3cr3t-Pa55@db-prod-1.internal.example:27017/tazzzo?replicaSet=rs0";
    static final String REDIS_URI = "rediss://:r3dis-t0ken@cache.internal.example:6380";

    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger(HealthService.class);

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private static DatastoreReadiness open() {
        DatastoreReadiness r = new DatastoreReadiness();
        r.markVerified();
        r.openWorkers();
        return r;
    }

    @Test
    void failing_dependencies_report_DOWN_with_no_host_credential_or_exception_text_in_body_or_log() {
        BooleanSupplier mongo = () -> { throw new MongoException("Timed out connecting to " + SECRET_URI); };
        BooleanSupplier redis = () -> { throw new IllegalStateException("cannot connect " + REDIS_URI); };
        HealthService s = new HealthService(open(), mongo, redis, true, true, Clock.systemUTC());

        HealthReport ready = s.ready();
        assertThat(ready.up()).isFalse();
        assertThat(ready.mongo()).isEqualTo("DOWN");
        assertThat(ready.rateLimiter()).isEqualTo("DOWN");

        String everything = ready.toBody().toString() + s.live().toBody() + String.join("\n",
                logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                + logs.list.stream().map(e -> String.valueOf(e.getThrowableProxy())).toList();
        assertThat(everything).doesNotContain("s3cr3t").doesNotContain("Pa55").doesNotContain("svc-user").doesNotContain("db-prod-1")
                .doesNotContain("internal.example").doesNotContain("27017").doesNotContain("6380").doesNotContain("r3dis")
                .doesNotContain("mongodb://").doesNotContain("rediss://").doesNotContain("Timed out");
        assertThat(logs.list).as("the failure is still logged, by component and exception class only").anySatisfy(e ->
                assertThat(e.getFormattedMessage()).isEqualTo("health_probe_down component=mongo reason=ExecutionException"));
    }

    @Test
    void the_body_vocabulary_is_closed_for_every_state_the_service_can_report() {
        BooleanSupplier up = () -> true;
        BooleanSupplier down = () -> false;
        java.util.Set<String> words = java.util.Set.of("UP", "DOWN", "OPEN", "STARTING", "REFUSED", "JOB", "SKIPPED", "DISABLED");
        DatastoreReadiness[] gates = {new DatastoreReadiness(), open()};
        for (DatastoreReadiness gate : gates) {
            for (BooleanSupplier mongo : new BooleanSupplier[]{up, down}) {
                for (BooleanSupplier limiter : new BooleanSupplier[]{up, down, null}) {
                    HealthReport r = new HealthService(gate, mongo, limiter, true, false, Clock.systemUTC()).ready();
                    assertThat(r.toBody().keySet()).containsExactly("status", "components");
                    assertThat(r.datastore()).isIn(words);
                    assertThat(r.mongo()).isIn(words);
                    assertThat(r.rateLimiter()).isIn(words);
                    assertThat(r.toBody().get("status")).isIn("UP", "DOWN");
                }
            }
        }
    }
}
