package com.tazzzo.commerce.read;

import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The rebuild-queue gauges without a database: fake clock, caching, error path, bounded queries and tags. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ProjectionQueueGaugesTest {

    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    final MongoDatabase db = mock(MongoDatabase.class);
    final MongoCollection<Document> coll = mock(MongoCollection.class);
    final FindIterable find = mock(FindIterable.class, Answers.RETURNS_SELF);
    final MovingClock clock = new MovingClock();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final AtomicInteger counts = new AtomicInteger();
    final CountOptions[] seen = new CountOptions[1];
    boolean fail;
    Document next;   // returned by the next .first()

    void build() {
        when(db.getCollection("work_queue")).thenReturn(coll);
        when(coll.find(any(org.bson.conversions.Bson.class))).thenReturn(find);
        when(find.first()).thenAnswer(i -> next);
        when(coll.countDocuments(any(org.bson.conversions.Bson.class), any(CountOptions.class))).thenAnswer(i -> {
            counts.incrementAndGet();
            seen[0] = i.getArgument(1);
            if (fail) throw new MongoException("down");
            return 4L;
        });
        new ProjectionQueueGauges(db, clock, registry, Duration.ofSeconds(15), 1000);
    }

    double depth(String state) {
        return registry.get("projection_rebuild_queue_depth").tag("state", state).gauge().value();
    }

    @Test
    void due_is_pending_plus_lapsed_leases_leased_is_live_leases_and_the_age_is_the_earliest_due() {
        next = new Document("requested_at", Date.from(clock.now.minusSeconds(120))).append("lease_until", Date.from(clock.now.minusSeconds(30)));
        build();
        assertThat(depth("due")).as("4 pending + 4 lapsed").isEqualTo(8);
        assertThat(depth("leased")).isEqualTo(4);
        assertThat(registry.get("projection_rebuild_queue_oldest_due_age_seconds").gauge().value())
                .as("the pending item at 120 s is older than the lapsed lease at 30 s").isEqualTo(120.0);
        assertThat(seen[0].getLimit()).isEqualTo(1000);
        assertThat(seen[0].getMaxTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(ProjectionQueueGauges.MAX_TIME_MS);
    }

    @Test
    void an_empty_queue_is_age_zero_and_the_snapshot_is_cached_for_the_ttl() {
        build();
        assertThat(registry.get("projection_rebuild_queue_oldest_due_age_seconds").gauge().value()).isZero();
        int first = counts.get();
        for (int i = 0; i < 30; i++) depth("due");
        assertThat(counts.get()).isEqualTo(first);
        clock.now = clock.now.plusSeconds(15);
        depth("due");
        assertThat(counts.get()).isGreaterThan(first);
    }

    @Test
    void a_failing_database_is_NaN_first_then_keeps_the_last_good_snapshot() {
        fail = true;
        build();
        assertThat(depth("due")).isNaN();
        assertThat(registry.get("projection_rebuild_queue_oldest_due_age_seconds").gauge().value()).isNaN();
        fail = false;
        clock.now = clock.now.plusSeconds(15);
        assertThat(depth("leased")).isEqualTo(4);
        fail = true;
        clock.now = clock.now.plusSeconds(15);
        assertThat(depth("leased")).isEqualTo(4);
    }

    @Test
    void only_two_states_exist_as_tag_values() {
        build();
        for (Meter m : registry.getMeters()) {
            for (Tag t : m.getId().getTags()) {
                assertThat(t.getKey()).isEqualTo("state");
                assertThat(t.getValue()).isIn("due", "leased");
            }
        }
    }
}
