package com.tazzzo.delivery;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Window administration, availability and the atomic capacity holds, against real MongoDB with a controllable clock. */
@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = com.tazzzo.catalog.CatalogApplication.class)
class DeliverySlotServiceIT extends AbstractApiIT {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Actor ACTOR = Actor.system("system:test");
    /** Monday 2026-10-05 06:00 IST. */
    static final Instant START = Instant.parse("2026-10-05T00:30:00Z");

    final AtomicReference<Instant> now = new AtomicReference<>(START);
    final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    DeliverySlotService service;

    @BeforeEach
    void setUp() {
        schemaBootstrap.bootstrap(db);
        for (String c : List.of("service_areas", "delivery_slot_windows", "delivery_slot_usage", "domain_events")) {
            db.getCollection(c).deleteMany(new Document());
        }
        now.set(START);
        ServiceabilityService serviceability = new ServiceabilityService(new Tx(client), db, new DomainAudit(db, clock), clock);
        serviceability.upsertServiceArea(new UpsertServiceAreaCommand("560001", "SA-1",
                List.of(new ServiceabilityRoute("FL-1", 0, true)), "seed", null));
        service = new DeliverySlotService(new Tx(client), db, new DomainAudit(db, clock), serviceability, clock, IST, 3);
    }

    private SlotWindow window(String id, int start, int end, int cutoff, int capacity, Integer... days) {
        return new SlotWindow(id, id, start, end, cutoff, capacity, Set.of(days));
    }

    private static final Integer[] ALL = {1, 2, 3, 4, 5, 6, 7};

    private SlotReservation reserve(String window, LocalDate date, String hold) {
        return new Tx(client).call(s -> service.reserve(s, "SA-1", window, date, hold));
    }

    private boolean release(String window, LocalDate date, String hold) {
        return new Tx(client).call(s -> service.release(s, "SA-1", window, date, hold));
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    @Test
    void admin_writes_are_cas_audited_and_validated_against_a_real_service_area() {
        assertThat(service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 5, ALL), null)).isEqualTo(1);
        assertThatThrownBy(() -> service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 5, ALL), null))
                .isInstanceOf(DeliverySlotException.Conflict.class);
        assertThatThrownBy(() -> service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 9, ALL), 7L))
                .isInstanceOf(DeliverySlotException.Conflict.class);
        assertThatThrownBy(() -> service.upsertWindow(ACTOR, "SA-1", window("zz", 480, 600, 60, 9, ALL), 1L))
                .isInstanceOf(DeliverySlotException.NotFound.class);
        assertThatThrownBy(() -> service.upsertWindow(ACTOR, "SA-NOPE", window("am", 480, 600, 60, 5, ALL), null))
                .isInstanceOf(DeliverySlotException.NotFound.class);
        assertThatThrownBy(() -> service.upsertWindow(ACTOR, "bad|area", window("am", 480, 600, 60, 5, ALL), null))
                .isInstanceOf(DeliverySlotException.Invalid.class);
        assertThat(service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 9, ALL), 1L)).isEqualTo(2);
        assertThat(service.find("SA-1", "am").orElseThrow().window().capacity()).isEqualTo(9);
        assertThat(service.setActive(ACTOR, "SA-1", "am", 2, false)).isEqualTo(3);
        assertThatThrownBy(() -> service.setActive(ACTOR, "SA-1", "am", 2, true)).isInstanceOf(DeliverySlotException.Conflict.class);
        assertThat(service.find("SA-1", "am").orElseThrow().active()).isFalse();

        List<Document> events = db.getCollection("domain_events").find(Filters.eq("aggregate_type", "delivery_slot_window")).into(new ArrayList<>());
        assertThat(events).extracting(d -> d.getString("type")).containsExactlyInAnyOrder(
                "DELIVERY_WINDOW_UPDATED", "DELIVERY_WINDOW_UPDATED", "DELIVERY_WINDOW_DEACTIVATED");
        assertThat(events).allSatisfy(d -> assertThat(d.get("actor", Document.class).getString("id")).isEqualTo("system:test"));
    }

    @Test
    void availability_uses_the_delivery_zone_cutoff_and_live_capacity() {
        service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 1, ALL), null);       // 08:00-10:00, closes 07:00
        service.upsertWindow(ACTOR, "SA-1", window("pm", 1080, 1200, 0, 2, 1, 2), null);      // 18:00-20:00 Mon+Tue only
        service.upsertWindow(ACTOR, "SA-1", window("off", 600, 700, 0, 2, ALL), null);
        service.setActive(ACTOR, "SA-1", "off", 1, false);

        List<SlotOffer> offers = service.availability("SA-1", 2);
        assertThat(offers).extracting(SlotOffer::slotId).containsExactly("am~2026-10-05", "pm~2026-10-05", "am~2026-10-06", "pm~2026-10-06");
        assertThat(offers).allSatisfy(o -> assertThat(o.status()).isEqualTo(SlotOffer.Status.AVAILABLE));
        assertThat(offers.get(0).startsAt().toString()).isEqualTo("2026-10-05T08:00+05:30[Asia/Kolkata]");

        assertThat(reserve("am", TODAY, "H1")).isEqualTo(SlotReservation.RESERVED);
        assertThat(service.availability("SA-1", 1).get(0).status()).isEqualTo(SlotOffer.Status.FULL);

        now.set(Instant.parse("2026-10-05T01:30:00Z"));  // 07:00 IST: exactly at the cutoff of "am" -> closed
        assertThat(service.availability("SA-1", 1)).filteredOn(o -> o.windowId().equals("am"))
                .singleElement().extracting(SlotOffer::status).isEqualTo(SlotOffer.Status.CLOSED);

        // 20:00 UTC on Monday is 01:30 IST on TUESDAY: "today" is the delivery-zone date, not the server's
        now.set(Instant.parse("2026-10-05T20:00:00Z"));
        assertThat(service.availability("SA-1", 1)).extracting(SlotOffer::date).containsOnly(LocalDate.of(2026, 10, 6));

        assertThatThrownBy(() -> service.availability("SA-1", 0)).isInstanceOf(DeliverySlotException.Invalid.class);
        assertThatThrownBy(() -> service.availability("SA-1", 4)).isInstanceOf(DeliverySlotException.Invalid.class);
        assertThat(service.availability("SA-OTHER", 1)).isEmpty();
    }

    @Test
    void holds_are_idempotent_and_release_is_idempotent() {
        service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 2, ALL), null);
        assertThat(reserve("am", TODAY, "H1")).isEqualTo(SlotReservation.RESERVED);
        assertThat(reserve("am", TODAY, "H1")).as("a retry never takes a second unit").isEqualTo(SlotReservation.ALREADY_HELD);
        assertThat(reserve("am", TODAY, "H2")).isEqualTo(SlotReservation.RESERVED);
        assertThat(reserve("am", TODAY, "H3")).isEqualTo(SlotReservation.FULL);
        assertThat(reserve("am", TODAY, "H1")).as("a holder is still a holder when full").isEqualTo(SlotReservation.ALREADY_HELD);

        assertThat(release("am", TODAY, "H1")).isTrue();
        assertThat(release("am", TODAY, "H1")).as("second release changes nothing").isFalse();
        assertThat(release("am", TODAY, "NEVER")).isFalse();
        Document usage = db.getCollection("delivery_slot_usage").find().first();
        assertThat(usage.getInteger("used")).isEqualTo(1);
        assertThat(reserve("am", TODAY, "H3")).isEqualTo(SlotReservation.RESERVED);
        assertThat(reserve("am", TODAY, "H4")).isEqualTo(SlotReservation.FULL);
    }

    @Test
    void closed_inactive_unknown_out_of_horizon_and_wrong_day_are_unavailable_and_write_nothing() {
        service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 5, 1, 2), null);   // Mon, Tue
        assertThat(reserve("nope", TODAY, "H")).isEqualTo(SlotReservation.UNAVAILABLE);
        assertThat(reserve("am", TODAY.minusDays(1), "H")).as("past").isEqualTo(SlotReservation.UNAVAILABLE);
        service.upsertWindow(ACTOR, "SA-1", window("every", 480, 600, 60, 5, ALL), null);
        assertThat(reserve("every", TODAY.plusDays(2), "H")).as("the last day inside the horizon").isEqualTo(SlotReservation.RESERVED);
        assertThat(reserve("every", TODAY.plusDays(3), "H")).as("beyond the 3-day horizon").isEqualTo(SlotReservation.UNAVAILABLE);
        assertThat(reserve("am", TODAY.plusDays(2), "H")).as("Wednesday is not a running day").isEqualTo(SlotReservation.UNAVAILABLE);
        now.set(Instant.parse("2026-10-05T01:30:00Z"));
        assertThat(reserve("am", TODAY, "H")).as("at the cutoff").isEqualTo(SlotReservation.UNAVAILABLE);
        now.set(START);
        service.setActive(ACTOR, "SA-1", "am", 1, false);
        assertThat(reserve("am", TODAY, "H")).as("inactive").isEqualTo(SlotReservation.UNAVAILABLE);
        assertThat(db.getCollection("delivery_slot_usage").countDocuments()).as("only the one in-horizon hold wrote anything").isEqualTo(1);
    }

    @Test
    void capacity_is_per_occurrence_not_shared_across_dates_or_windows() {
        service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 1, ALL), null);
        service.upsertWindow(ACTOR, "SA-1", window("pm", 1080, 1200, 60, 1, ALL), null);
        assertThat(reserve("am", TODAY, "H1")).isEqualTo(SlotReservation.RESERVED);
        assertThat(reserve("am", TODAY.plusDays(1), "H2")).as("another date of the same window").isEqualTo(SlotReservation.RESERVED);
        assertThat(reserve("pm", TODAY, "H3")).as("another window on the same date").isEqualTo(SlotReservation.RESERVED);
        assertThat(reserve("am", TODAY, "H4")).isEqualTo(SlotReservation.FULL);
        assertThat(db.getCollection("delivery_slot_usage").countDocuments()).isEqualTo(3);
    }

    @Test
    void concurrent_holds_never_oversell_and_a_duplicate_hold_id_counts_once() throws Exception {
        service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 5, ALL), null);
        int n = 24;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SlotReservation>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String hold = "H" + (i % 20);   // four ids are used twice: a duplicate must not take a second unit
            futures.add(pool.submit(() -> {
                go.await();
                return reserve("am", TODAY, hold);
            }));
        }
        go.countDown();
        int reserved = 0;
        for (Future<SlotReservation> f : futures) {
            if (f.get() == SlotReservation.RESERVED) reserved++;
        }
        pool.shutdown();
        Document usage = db.getCollection("delivery_slot_usage").find().first();
        assertThat(reserved).isEqualTo(5);
        assertThat(usage.getInteger("used")).isEqualTo(5);
        assertThat(usage.getList("holds", String.class)).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void the_counter_row_expires_a_week_after_its_slot_date() {
        service.upsertWindow(ACTOR, "SA-1", window("am", 480, 600, 60, 5, ALL), null);
        reserve("am", TODAY, "H1");
        Instant expire = db.getCollection("delivery_slot_usage").find().first().getDate("expire_at").toInstant();
        assertThat(expire).isEqualTo(TODAY.plusDays(1).atStartOfDay(IST).toInstant().plus(java.time.Duration.ofDays(7)));
    }
}
