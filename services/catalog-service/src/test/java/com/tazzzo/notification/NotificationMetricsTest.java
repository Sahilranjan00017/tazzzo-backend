package com.tazzzo.notification;

import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Gauge behaviour that needs no database: failure handling and the cap label. */
@SuppressWarnings({"unchecked", "rawtypes"})
class NotificationMetricsTest {

    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void a_failing_database_is_NaN_first_then_keeps_the_last_snapshot_and_retries_once_per_ttl() {
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable find = mock(FindIterable.class, Answers.RETURNS_SELF);
        when(db.getCollection(NotificationOutbox.COLLECTION)).thenReturn(coll);
        when(coll.find(any(org.bson.conversions.Bson.class))).thenReturn(find);
        when(find.first()).thenReturn(null);
        AtomicInteger calls = new AtomicInteger();
        boolean[] fail = {true, false, true};
        int[] step = {0};
        when(coll.countDocuments(any(org.bson.conversions.Bson.class), any(com.mongodb.client.model.CountOptions.class)))
                .thenAnswer(i -> {
                    calls.incrementAndGet();
                    if (fail[step[0]]) {
                        throw step[0] == 0 ? new MongoException("down") : new IllegalStateException("boom");
                    }
                    return 3L;
                });
        MovingClock clock = new MovingClock();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NotificationMetrics(db, clock, registry, Duration.ofSeconds(15));

        assertThat(registry.get("notification_outbox_pending").gauge().value()).isNaN();
        int afterFirst = calls.get();
        assertThat(registry.get("notification_outbox_failed").gauge().value()).as("not re-queried within ttl").isNaN();
        assertThat(calls.get()).isEqualTo(afterFirst);

        step[0] = 1;
        clock.now = clock.now.plusSeconds(15);
        assertThat(registry.get("notification_outbox_pending").gauge().value()).isEqualTo(6);   // PENDING + lapsed SENDING
        assertThat(registry.get("notification_outbox_failed").gauge().value()).isEqualTo(3);

        step[0] = 2;                                                    // a non-Mongo RuntimeException also keeps the snapshot
        clock.now = clock.now.plusSeconds(15);
        assertThat(registry.get("notification_outbox_pending").gauge().value()).isEqualTo(6);
        int afterFail = calls.get();
        registry.get("notification_outbox_failed").gauge().value();
        assertThat(calls.get()).as("a failed refresh still advances loadedAt").isEqualTo(afterFail);
    }

    @Test
    void the_cap_is_applied_and_stated_in_the_gauge_help_text() {
        MongoDatabase db = mock(MongoDatabase.class);
        MongoCollection<Document> coll = mock(MongoCollection.class);
        FindIterable find = mock(FindIterable.class, Answers.RETURNS_SELF);
        when(db.getCollection(NotificationOutbox.COLLECTION)).thenReturn(coll);
        when(coll.find(any(org.bson.conversions.Bson.class))).thenReturn(find);
        int[] seenLimit = {-1};
        when(coll.countDocuments(any(org.bson.conversions.Bson.class), any(com.mongodb.client.model.CountOptions.class)))
                .thenAnswer(i -> {
                    seenLimit[0] = i.getArgument(1, com.mongodb.client.model.CountOptions.class).getLimit();
                    return 0L;
                });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NotificationMetrics(db, new MovingClock(), registry, Duration.ofSeconds(15));
        registry.get("notification_outbox_pending").gauge().value();
        assertThat(seenLimit[0]).isEqualTo(100_000).isEqualTo(NotificationMetrics.COUNT_CAP);
        assertThat(registry.get("notification_outbox_pending").gauge().getId().getDescription()).contains("100000");
        assertThat(registry.get("notification_outbox_failed").gauge().getId().getDescription()).contains("100000");
    }
}
