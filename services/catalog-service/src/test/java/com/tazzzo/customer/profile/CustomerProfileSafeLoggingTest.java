package com.tazzzo.customer.profile;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-12A hardening (Finding 2) — this domain processes PII (displayName, email); a driver/runtime
 * exception's message or stack context must never be assumed safe to log verbatim. Proves the
 * ACTUAL emitted log output — not just the HTTP response body — never contains injected sensitive
 * values, by attaching a real Logback {@link ListAppender} to {@link CustomerProfileService}'s
 * logger and driving both failure paths (repository outage, identity-authority outage) with an
 * exception whose message deliberately embeds fake PII.
 */
class CustomerProfileSafeLoggingTest {

    private static final String FAKE_PII_MESSAGE = "pii@example.com CUS_sensitive Secret Name";

    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logbackLogger = (Logger) LoggerFactory.getLogger(CustomerProfileService.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbackLogger.detachAppender(appender);
    }

    private static final class FixedProvider<T> implements ObjectProvider<T> {
        private final T value;

        FixedProvider(T value) {
            this.value = value;
        }

        @Override public T getObject() throws BeansException {
            return value;
        }

        @Override public T getObject(Object... args) throws BeansException {
            return value;
        }

        @Override public T getIfAvailable() throws BeansException {
            return value;
        }

        @Override public T getIfUnique() throws BeansException {
            return value;
        }
    }

    private static final class ThrowingRepository extends CustomerProfileRepository {
        ThrowingRepository() {
            super(null);
        }

        @Override public org.bson.Document findById(String customerId) {
            throw new RuntimeException(FAKE_PII_MESSAGE);
        }

        @Override public org.bson.Document patch(String customerId, long expectedVersion,
                                                  PatchField<String> displayName, PatchField<String> email,
                                                  Instant now) {
            throw new RuntimeException(FAKE_PII_MESSAGE);
        }
    }

    private static CustomerProfileService serviceWith(CustomerProfileRepository repository,
                                                       CustomerIdentityAuthority authority) {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        return new CustomerProfileService(repository, fixedClock,
                new CustomerProfileObservability(new SimpleMeterRegistry()), new FixedProvider<>(authority));
    }

    private String allLoggedText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                sb.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    @Test void a_repository_outage_never_logs_the_raw_exception_message_on_get() {
        CustomerProfileService service = serviceWith(new ThrowingRepository(), id -> true);

        assertThatThrownBy(() -> service.get(new CustomerId("CUS_loggingtest001")))
                .isInstanceOf(CustomerProfileFailure.class);

        String logged = allLoggedText();
        assertThat(logged).doesNotContain(FAKE_PII_MESSAGE).doesNotContain("pii@example.com")
                .doesNotContain("CUS_sensitive").doesNotContain("Secret Name");
        assertThat(logged).contains("RuntimeException"); // the bounded exception TYPE is fine
        // No log event on this path should carry an attached Throwable -- only the type name string.
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getThrowableProxy()).as("never attach the raw exception object").isNull();
        }
    }

    @Test void a_repository_outage_never_logs_the_raw_exception_message_on_patch() {
        CustomerProfileService service = serviceWith(new ThrowingRepository(), id -> true);

        assertThatThrownBy(() -> service.patch(new CustomerId("CUS_loggingtest002"), 0,
                PatchField.of("X"), PatchField.absent()))
                .isInstanceOf(CustomerProfileFailure.class);

        String logged = allLoggedText();
        assertThat(logged).doesNotContain(FAKE_PII_MESSAGE).doesNotContain("pii@example.com")
                .doesNotContain("CUS_sensitive").doesNotContain("Secret Name");
    }

    @Test void an_identity_authority_outage_never_logs_the_raw_exception_message() {
        CustomerIdentityAuthority throwing = id -> {
            throw new RuntimeException(FAKE_PII_MESSAGE);
        };
        CustomerProfileService service = serviceWith(new ThrowingRepository(), throwing);

        assertThatThrownBy(() -> service.get(new CustomerId("CUS_loggingtest003")))
                .isInstanceOf(CustomerProfileFailure.class);

        String logged = allLoggedText();
        assertThat(logged).doesNotContain(FAKE_PII_MESSAGE).doesNotContain("pii@example.com")
                .doesNotContain("CUS_sensitive").doesNotContain("Secret Name");
        assertThat(logged).contains("customer_identity_authority_failed").contains("RuntimeException");
    }
}
