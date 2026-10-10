package com.tazzzo.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credential-free lifecycle: enqueue in a transaction, dispatch through the REAL dispatcher with the sandbox sender,
 * every terminal state, two dispatchers racing, the outbox gauges and the no-PII log guarantee.
 */
@SpringBootTest(classes = CatalogApplication.class, properties = "tazzzo.notifications.provider=sandbox")
@Timeout(120)
class NotificationSandboxIT extends AbstractMongoIT {

    static final class MovingClock extends Clock {
        volatile Instant now = Instant.parse("2026-06-01T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration d) { now = now.plus(d); }
    }

    /** Test-only failure injection: the sandbox itself has no such switch. */
    static final class ScriptedSandbox extends SandboxNotificationSender {
        volatile Function<OutboxNotification, Outcome> rule = n -> Outcome.SENT;

        @Override
        protected Outcome decide(OutboxNotification n) {
            return rule.apply(n);
        }
    }

    static final String CUSTOMER = "CUS_secretpii9";

    @Autowired NotificationSender wiredSender;

    MovingClock clock;
    SimpleMeterRegistry registry;
    NotificationOutbox outbox;
    ScriptedSandbox sender;
    NotificationDispatcher dispatcher;
    ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void reset() {
        db.getCollection(NotificationOutbox.COLLECTION).deleteMany(new Document());
        clock = new MovingClock();
        registry = new SimpleMeterRegistry();
        outbox = new NotificationOutbox(db, clock, registry);
        sender = new ScriptedSandbox();
        dispatcher = new NotificationDispatcher(outbox, sender, clock, registry, Duration.ofSeconds(60), Duration.ofHours(1),
                Duration.ofSeconds(30), 3);
        logs = new ListAppender<>();
        logs.start();
        for (Class<?> c : List.of(SandboxNotificationSender.class, NotificationDispatcher.class)) {
            ((Logger) LoggerFactory.getLogger(c)).addAppender(logs);
        }
    }

    @AfterEach
    void detach() {
        for (Class<?> c : List.of(SandboxNotificationSender.class, NotificationDispatcher.class)) {
            ((Logger) LoggerFactory.getLogger(c)).detachAppender(logs);
        }
    }

    void enqueue(String subject) {
        new Tx(client).run(s -> outbox.enqueue(s, new NotificationRequest(NotificationType.ORDER_CONFIRMED, CUSTOMER, subject,
                Map.of("item_count", "7", "payable_paise", "123456"))));
    }

    Document row(String subject) {
        return db.getCollection(NotificationOutbox.COLLECTION).find(new Document("_id", "ORDER_CONFIRMED:" + subject)).first();
    }

    double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    double dispatched(String outcome) {
        var c = registry.find("notification_dispatch").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void the_sandbox_is_what_the_property_wires_in_this_context() {
        assertThat(wiredSender).isInstanceOf(SandboxNotificationSender.class);
        assertThat(wiredSender.delivers()).isTrue();
    }

    @Test
    void enqueue_then_dispatch_ends_SENT_with_metrics_and_idempotent_re_enqueue() {
        enqueue("ORD_1");
        enqueue("ORD_1");                                             // replayed business transaction
        assertThat(registry.get("notification_enqueued").tag("type", "ORDER_CONFIRMED").counter().count())
                .as("only the row actually written is counted").isEqualTo(1);

        clock.advance(Duration.ofSeconds(5));
        assertThat(dispatcher.dispatchDue(10)).isEqualTo(1);
        Document d = row("ORD_1");
        assertThat(d.getString("status")).isEqualTo("SENT");
        assertThat(d.getInteger("attempts")).isEqualTo(1);
        assertThat(sender.recent()).containsExactly(
                new SandboxNotificationSender.Delivery("ORDER_CONFIRMED:ORD_1", 1, NotificationSender.Outcome.SENT));
        assertThat(dispatched("sent")).isEqualTo(1);
        assertThat(registry.get("notification_dispatch_latency").tag("type", "ORDER_CONFIRMED").timer().totalTime(TimeUnit.SECONDS))
                .isEqualTo(5.0);

        enqueue("ORD_1");                                             // re-enqueue after SENT must not resurrect it
        assertThat(dispatcher.dispatchDue(10)).isZero();
        assertThat(row("ORD_1").getString("status")).isEqualTo("SENT");
        assertThat(sender.total(NotificationSender.Outcome.SENT)).isEqualTo(1);
    }

    @Test
    void retry_backs_off_then_fails_terminally_at_max_attempts() {
        sender.rule = n -> NotificationSender.Outcome.RETRY;
        enqueue("ORD_1");

        dispatcher.dispatchDue(10);
        assertThat(row("ORD_1").getString("status")).isEqualTo("PENDING");
        assertThat(row("ORD_1").getDate("next_attempt_at").toInstant()).isEqualTo(clock.now.plusSeconds(30));
        clock.advance(Duration.ofSeconds(30));
        dispatcher.dispatchDue(10);
        assertThat(row("ORD_1").getDate("next_attempt_at").toInstant()).isEqualTo(clock.now.plusSeconds(60));
        clock.advance(Duration.ofSeconds(60));
        dispatcher.dispatchDue(10);                                   // attempt 3 = max

        assertThat(row("ORD_1").getString("status")).isEqualTo("FAILED");
        assertThat(sender.recent()).extracting(SandboxNotificationSender.Delivery::attempt).containsExactly(1, 2, 3);
        clock.advance(Duration.ofHours(1));
        assertThat(dispatcher.dispatchDue(10)).as("FAILED is terminal").isZero();
        assertThat(dispatched("retry")).isEqualTo(2);
        assertThat(dispatched("failed")).isEqualTo(1);
    }

    @Test
    void a_rejection_is_terminal_at_once_and_a_stale_row_expires_unsent() {
        sender.rule = n -> NotificationSender.Outcome.REJECTED;
        enqueue("ORD_1");
        dispatcher.dispatchDue(10);
        assertThat(row("ORD_1").getString("status")).isEqualTo("FAILED");
        assertThat(row("ORD_1").getInteger("attempts")).isEqualTo(1);
        assertThat(dispatched("rejected")).isEqualTo(1);

        enqueue("ORD_2");
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        dispatcher.dispatchDue(10);
        assertThat(row("ORD_2").getString("status")).isEqualTo("EXPIRED");
        assertThat(sender.recent()).extracting(SandboxNotificationSender.Delivery::notificationId)
                .containsExactly("ORDER_CONFIRMED:ORD_1");
        assertThat(dispatched("expired")).isEqualTo(1);
    }

    @Test
    void two_dispatchers_on_one_outbox_send_every_row_exactly_once() throws Exception {
        int rows = 40;
        for (int i = 0; i < rows; i++) {
            enqueue("ORD_" + i);
        }
        ScriptedSandbox other = new ScriptedSandbox();
        NotificationDispatcher second = new NotificationDispatcher(new NotificationOutbox(db, clock, registry), other, clock,
                registry, Duration.ofSeconds(60), Duration.ofHours(1), Duration.ofSeconds(30), 3);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> f = new ArrayList<>();
            for (NotificationDispatcher d : List.of(dispatcher, second)) {
                f.add(pool.submit(() -> {
                    go.await();
                    int total = 0;
                    int n;
                    while ((n = d.dispatchDue(5)) > 0) {
                        total += n;
                    }
                    return total;
                }));
            }
            go.countDown();
            int claimed = f.get(0).get(60, TimeUnit.SECONDS) + f.get(1).get(60, TimeUnit.SECONDS);
            assertThat(claimed).isEqualTo(rows);
        } finally {
            pool.shutdownNow();
        }
        List<String> ids = new ArrayList<>();
        sender.recent().forEach(x -> ids.add(x.notificationId()));
        other.recent().forEach(x -> ids.add(x.notificationId()));
        assertThat(ids).hasSize(rows).doesNotHaveDuplicates();
        assertThat(db.getCollection(NotificationOutbox.COLLECTION).countDocuments(new Document("status", "SENT"))).isEqualTo(rows);
        assertThat(db.getCollection(NotificationOutbox.COLLECTION).countDocuments(new Document("attempts", new Document("$ne", 1))))
                .isZero();
    }

    @Test
    void outbox_gauges_report_pending_failed_and_oldest_pending_age_from_a_cached_snapshot() {
        NotificationMetrics metrics = new NotificationMetrics(db, clock, registry, Duration.ofSeconds(15));
        assertThat(metrics).isNotNull();
        assertThat(gauge("notification_outbox_pending")).isZero();
        assertThat(gauge("notification_outbox_oldest_pending_age_seconds")).isZero();

        enqueue("ORD_1");                                             // due at T0
        clock.advance(Duration.ofSeconds(10));
        enqueue("ORD_2");                                             // due at T0+10
        assertThat(gauge("notification_outbox_pending")).as("cached: the snapshot is younger than 15 s").isZero();

        clock.advance(Duration.ofSeconds(20));                        // T0+30
        assertThat(gauge("notification_outbox_pending")).isEqualTo(2);
        assertThat(gauge("notification_outbox_failed")).isZero();
        assertThat(gauge("notification_outbox_oldest_pending_age_seconds")).isEqualTo(30);

        sender.rule = n -> n.subjectId().equals("ORD_1") ? NotificationSender.Outcome.REJECTED : NotificationSender.Outcome.SENT;
        dispatcher.dispatchDue(10);
        clock.advance(Duration.ofSeconds(15));
        assertThat(gauge("notification_outbox_pending")).isZero();
        assertThat(gauge("notification_outbox_failed")).isEqualTo(1);
        assertThat(gauge("notification_outbox_oldest_pending_age_seconds")).isZero();

        enqueue("ORD_3");                                             // a backed-off row is not "behind"
        sender.rule = n -> NotificationSender.Outcome.RETRY;
        dispatcher.dispatchDue(10);
        clock.advance(Duration.ofSeconds(15));
        assertThat(gauge("notification_outbox_pending")).isEqualTo(1);
        assertThat(gauge("notification_outbox_oldest_pending_age_seconds")).isZero();
        clock.advance(Duration.ofSeconds(30));                        // T0+90; the retry fell due at T0+75
        assertThat(gauge("notification_outbox_oldest_pending_age_seconds")).isEqualTo(15);
    }

    @Test
    void nothing_in_the_logs_carries_recipient_ids_params_or_subjects() {
        sender.rule = n -> n.subjectId().equals("ORD_R") ? NotificationSender.Outcome.RETRY
                : n.subjectId().equals("ORD_X") ? NotificationSender.Outcome.REJECTED : NotificationSender.Outcome.SENT;
        enqueue("ORD_S");
        enqueue("ORD_R");
        enqueue("ORD_X");
        dispatcher.dispatchDue(10);

        assertThat(logs.list).isNotEmpty();
        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anyMatch(l -> l.contains("type=ORDER_CONFIRMED") && l.contains("outcome=SENT"));
        assertThat(lines).anyMatch(l -> l.contains("outcome=retry"));
        assertThat(lines).anyMatch(l -> l.contains("outcome=rejected"));
        for (String l : lines) {
            assertThat(l).doesNotContain(CUSTOMER).doesNotContain("ORD_").doesNotContain("123456").doesNotContain("payable");
        }
    }
}
