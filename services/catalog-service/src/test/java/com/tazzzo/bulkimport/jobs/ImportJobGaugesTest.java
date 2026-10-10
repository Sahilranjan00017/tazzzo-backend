package com.tazzzo.bulkimport.jobs;

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

/** The import backlog gauges without a database: fake clock, caching, error path, bounded queries and tags. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ImportJobGaugesTest {

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
    long perStatus = 2;
    boolean fail;
    Document oldest;

    ImportJobGauges gauges() {
        when(db.getCollection(ImportJobRepository.JOBS)).thenReturn(coll);
        when(coll.find(any(org.bson.conversions.Bson.class))).thenReturn(find);
        when(find.first()).thenAnswer(i -> oldest);
        when(coll.countDocuments(any(org.bson.conversions.Bson.class), any(CountOptions.class))).thenAnswer(i -> {
            counts.incrementAndGet();
            seen[0] = i.getArgument(1);
            if (fail) throw new MongoException("down");
            return perStatus;
        });
        return new ImportJobGauges(db, clock, registry, Duration.ofSeconds(15), 500);
    }

    double active(String status) {
        return registry.get("import_jobs_active").tag("status", status).gauge().value();
    }

    @Test
    void counts_per_status_and_the_age_of_the_most_neglected_worker_owned_job() {
        oldest = new Document("updated_at", Date.from(clock.now.minusSeconds(90)));
        gauges();
        assertThat(active("validating")).isEqualTo(2);
        assertThat(active("paused")).isEqualTo(2);
        assertThat(registry.get("import_job_oldest_active_age_seconds").gauge().value()).isEqualTo(90.0);
        assertThat(seen[0].getLimit()).as("every count is capped").isEqualTo(500);
        assertThat(seen[0].getMaxTime(java.util.concurrent.TimeUnit.MILLISECONDS)).as("and time-bounded").isEqualTo(ImportJobGauges.MAX_TIME_MS);
    }

    @Test
    void no_worker_owned_job_is_age_zero_and_a_clock_skewed_future_timestamp_is_floored_at_zero() {
        gauges();
        assertThat(registry.get("import_job_oldest_active_age_seconds").gauge().value()).isZero();
        oldest = new Document("updated_at", Date.from(clock.now.plusSeconds(500)));
        clock.now = clock.now.plusSeconds(15);
        assertThat(registry.get("import_job_oldest_active_age_seconds").gauge().value()).isZero();
    }

    @Test
    void is_queried_at_most_once_per_ttl_whatever_the_scrape_rate() {
        gauges();
        active("open");
        int first = counts.get();
        for (int i = 0; i < 40; i++) active("applying");
        assertThat(counts.get()).isEqualTo(first);
        assertThat(first).as("one count per backlog status").isEqualTo(ImportJobGauges.BACKLOG.size());
    }

    @Test
    void a_failing_database_is_NaN_first_then_keeps_the_last_good_snapshot() {
        fail = true;
        gauges();
        assertThat(active("open")).isNaN();
        assertThat(registry.get("import_job_oldest_active_age_seconds").gauge().value()).isNaN();

        fail = false;
        clock.now = clock.now.plusSeconds(15);
        assertThat(active("open")).isEqualTo(2);

        fail = true;
        perStatus = 99;
        clock.now = clock.now.plusSeconds(15);
        assertThat(active("open")).as("kept").isEqualTo(2);
    }

    @Test
    void the_tag_set_is_the_six_backlog_statuses_and_never_a_terminal_status_or_an_id() {
        gauges();
        for (Meter m : registry.getMeters()) {
            for (Tag t : m.getId().getTags()) {
                assertThat(t.getKey()).isEqualTo("status");
                assertThat(t.getValue()).isIn("open", "validating", "validated", "rejected", "applying", "paused");
            }
        }
        assertThat(registry.find("import_jobs_active").gauges()).hasSize(6);
    }
}
