package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.serviceability.InvalidServiceabilityException;
import com.tazzzo.serviceability.ServiceabilityConflictException;
import com.tazzzo.serviceability.ServiceabilityNotFoundException;
import com.tazzzo.serviceability.ServiceabilityResolution;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-06 Serviceability foundation — Testcontainers integration against Mongo 7. Proves
 * pincode-unique config, deterministic multi-route resolution, distinct resolution states,
 * CAS + neutral-audit rollback, concurrency, and BSON shape.
 */
class ServiceabilityFoundationIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private ServiceabilityService service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new ServiceabilityService(new Tx(client), db, new DomainAudit(db, clock), clock);
    }

    private ServiceabilityRoute route(String loc, int priority, boolean active) {
        return new ServiceabilityRoute(loc, priority, active);
    }

    private UpsertServiceAreaCommand create(String pin, String area, List<ServiceabilityRoute> routes) {
        return new UpsertServiceAreaCommand(pin, area, routes, "seed", null);
    }

    private long auditCountForPin(String pin) {
        return db.getCollection("domain_events").countDocuments(Filters.and(
                Filters.eq("aggregate_type", "serviceability_pin"),
                Filters.eq("aggregate_id", pin),
                Filters.eq("type", "SERVICE_AREA_UPDATED")));
    }

    @Test void serviceable_pin_resolves_area_and_internal_location() {
        ServiceabilityService svc = service();
        assertEquals(1L, svc.upsertServiceArea(create("560047", "SA-BLR-01",
                List.of(route("FL-BLR-01", 0, true)))));
        ServiceabilityResolution r = svc.resolveByPincode(new Pincode("560047"));
        assertTrue(r.isServiceable());
        assertEquals("SA-BLR-01", r.serviceAreaId());
        assertEquals("FL-BLR-01", r.fulfillmentLocationId());
    }

    @Test void unconfigured_pin_is_unserviceable() {
        ServiceabilityResolution r = service().resolveByPincode(new Pincode("999999"));
        assertEquals(ServiceabilityResolution.Status.UNSERVICEABLE, r.status());
        assertNull(r.serviceAreaId());
        assertNull(r.fulfillmentLocationId());
    }

    @Test void inactive_area_distinct_from_unserviceable() {
        ServiceabilityService svc = service();
        svc.upsertServiceArea(create("560001", "SA-BLR-02", List.of(route("FL-BLR-01", 0, true))));
        db.getCollection("service_areas").updateOne(Filters.eq("pincode", "560001"),
                new Document("$set", new Document("active", false)));
        ServiceabilityResolution r = svc.resolveByPincode(new Pincode("560001"));
        assertEquals(ServiceabilityResolution.Status.INACTIVE, r.status());
        assertEquals("SA-BLR-02", r.serviceAreaId());
        assertNull(r.fulfillmentLocationId(), "inactive area must never expose a route");
    }

    @Test void active_area_without_active_route_is_config_error_not_routed() {
        ServiceabilityService svc = service();
        svc.upsertServiceArea(create("560002", "SA-BLR-03", List.of(route("FL-X", 0, false))));
        ServiceabilityResolution r = svc.resolveByPincode(new Pincode("560002"));
        assertEquals(ServiceabilityResolution.Status.NO_ACTIVE_ROUTE, r.status());
        assertNull(r.fulfillmentLocationId(), "inactive route must never be selected");
    }

    @Test void multi_route_resolution_is_deterministic_lowest_priority() {
        ServiceabilityService svc = service();
        svc.upsertServiceArea(create("560003", "SA-BLR-04", List.of(
                route("FL-C", 7, true), route("FL-A", 1, false), route("FL-B", 3, true))));
        // FL-A has the lowest priority but is inactive; FL-B (3) beats FL-C (7).
        assertEquals("FL-B", svc.resolveByPincode(new Pincode("560003")).fulfillmentLocationId());
    }

    @Test void cas_update_increments_version_and_stale_writer_rolls_back_audit() {
        ServiceabilityService svc = service();
        svc.upsertServiceArea(create("560004", "SA-BLR-05", List.of(route("FL-1", 0, true))));
        long v2 = svc.upsertServiceArea(new UpsertServiceAreaCommand("560004", "SA-BLR-05",
                List.of(route("FL-2", 0, true)), "ops", 1L));
        assertEquals(2L, v2);
        assertEquals("FL-2", svc.resolveByPincode(new Pincode("560004")).fulfillmentLocationId());

        long auditBefore = auditCountForPin("560004");
        assertThrows(ServiceabilityConflictException.class,
                () -> svc.upsertServiceArea(new UpsertServiceAreaCommand("560004", "SA-BLR-05",
                        List.of(route("FL-3", 0, true)), "ops", 1L)));
        // state unchanged + the losing write left NO neutral-audit residue (txn rollback)
        assertEquals("FL-2", svc.resolveByPincode(new Pincode("560004")).fulfillmentLocationId());
        assertEquals(auditBefore, auditCountForPin("560004"));
    }

    @Test void duplicate_create_rejected_without_orphan_audit() {
        ServiceabilityService svc = service();
        svc.upsertServiceArea(create("560005", "SA-BLR-06", List.of()));
        long auditBefore = auditCountForPin("560005");
        assertThrows(ServiceabilityConflictException.class,
                () -> svc.upsertServiceArea(create("560005", "SA-BLR-06", List.of())));
        assertEquals(auditBefore, auditCountForPin("560005"));
    }

    @Test void update_of_missing_pin_is_not_found() {
        assertThrows(ServiceabilityNotFoundException.class,
                () -> service().upsertServiceArea(new UpsertServiceAreaCommand(
                        "560006", "SA-GHOST", List.of(), "ops", 1L)));
    }

    @Test void same_fulfillment_location_may_serve_multiple_pins() {
        ServiceabilityService svc = service();
        svc.upsertServiceArea(create("560007", "SA-BLR-07", List.of(route("FL-SHARED", 0, true))));
        svc.upsertServiceArea(create("560008", "SA-BLR-07", List.of(route("FL-SHARED", 0, true))));
        assertEquals("FL-SHARED", svc.resolveByPincode(new Pincode("560007")).fulfillmentLocationId());
        assertEquals("FL-SHARED", svc.resolveByPincode(new Pincode("560008")).fulfillmentLocationId());
        // service_area_id shared across pins by design (an area may span many PINs)
        assertEquals("SA-BLR-07", svc.resolveByPincode(new Pincode("560008")).serviceAreaId());
        // STEP 3 fix: sharing one serviceAreaId does NOT merge audit streams — each PIN
        // aggregate has its own independent history.
        assertEquals(1, auditCountForPin("560007"));
        assertEquals(1, auditCountForPin("560008"));
    }

    @Test void relabelling_a_pin_keeps_one_continuous_audit_stream() {
        // STEP 9: PIN moves SA-A -> SA-B; same canonical row, same version stream, ONE audit
        // aggregate; the label change is visible IN the stream's detail.
        ServiceabilityService svc = service();
        svc.upsertServiceArea(create("560012", "SA-A", List.of(route("FL-1", 0, true))));      // v1
        long v2 = svc.upsertServiceArea(new UpsertServiceAreaCommand("560012", "SA-B",
                List.of(route("FL-1", 0, true)), "ops", 1L));                                   // v2
        assertEquals(2L, v2);
        assertEquals(1, db.getCollection("service_areas").countDocuments(
                Filters.eq("pincode", "560012")), "no second canonical row");
        assertEquals("SA-B", svc.resolveByPincode(new Pincode("560012")).serviceAreaId());
        assertEquals(2, auditCountForPin("560012"), "one continuous per-PIN stream");
        List<String> labels = db.getCollection("domain_events")
                .find(Filters.and(Filters.eq("aggregate_type", "serviceability_pin"),
                        Filters.eq("aggregate_id", "560012")))
                .map(d -> ((Document) d.get("detail")).getString("service_area_id"))
                .into(new java.util.ArrayList<>());
        assertTrue(labels.contains("SA-A") && labels.contains("SA-B"),
                "label transition recorded inside the single stream: " + labels);
    }

    @Test void concurrent_create_race_exactly_one_winner_one_audit() throws Exception {
        // STEP 13: SIMULTANEOUS creates for the same PIN.
        ServiceabilityService svc = service();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable attempt = () -> {
                try {
                    start.await();
                    svc.upsertServiceArea(create("560013", "SA-CRACE",
                            List.of(route("FL-" + Thread.currentThread().getName(), 0, true))));
                    wins.incrementAndGet();
                } catch (ServiceabilityConflictException e) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Future<?> a = pool.submit(attempt);
            Future<?> b = pool.submit(attempt);
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "exactly one create may win");
        assertEquals(1, conflicts.get(), "the loser gets the TYPED conflict");
        assertEquals(1, db.getCollection("service_areas").countDocuments(Filters.eq("pincode", "560013")));
        Document d = db.getCollection("service_areas").find(Filters.eq("pincode", "560013")).first();
        assertEquals(1L, ((Number) d.get("version")).longValue());
        assertEquals(1, auditCountForPin("560013"), "loser's audit event rolled back");
    }

    @Test void expected_version_overflow_rejected_and_state_unchanged() {
        ServiceabilityService svc = service();
        svc.upsertServiceArea(create("560009", "SA-BLR-08", List.of()));
        assertThrows(InvalidServiceabilityException.class,
                () -> svc.upsertServiceArea(new UpsertServiceAreaCommand(
                        "560009", "SA-BLR-08", List.of(), "ops", Long.MAX_VALUE)));
        Document d = db.getCollection("service_areas").find(Filters.eq("pincode", "560009")).first();
        assertEquals(1L, ((Number) d.get("version")).longValue());
    }

    @Test void concurrent_cas_writers_produce_exactly_one_winner() throws Exception {
        ServiceabilityService svc = service();
        svc.upsertServiceArea(create("560010", "SA-BLR-09", List.of(route("FL-1", 0, true)))); // v1
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable attempt = () -> {
                try {
                    start.await();
                    svc.upsertServiceArea(new UpsertServiceAreaCommand("560010", "SA-BLR-09",
                            List.of(route("FL-" + Thread.currentThread().getName(), 0, true)), "ops", 1L));
                    wins.incrementAndGet();
                } catch (ServiceabilityConflictException e) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Future<?> a = pool.submit(attempt);
            Future<?> b = pool.submit(attempt);
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "exactly one writer may advance v1 -> v2");
        assertEquals(1, conflicts.get(), "the stale writer gets a typed conflict");
        Document d = db.getCollection("service_areas").find(Filters.eq("pincode", "560010")).first();
        assertEquals(2L, ((Number) d.get("version")).longValue());
    }

    @Test void bson_shape_and_neutral_audit_row() {
        service().upsertServiceArea(create("560011", "SA-BSON", List.of(route("FL-1", 0, true))));
        Document d = db.getCollection("service_areas").find(Filters.eq("pincode", "560011")).first();
        assertInstanceOf(Long.class, d.get("version"));
        assertInstanceOf(String.class, d.get("pincode"));
        Document r = d.getList("routes", Document.class).get(0);
        assertInstanceOf(Integer.class, r.get("priority"));
        assertEquals("FL-1", r.getString("fulfillment_location_id"));
        // neutral audit rail: the PER-PIN aggregate (STEP 3 fix), NOT product_events
        Document ev = db.getCollection("domain_events").find(
                Filters.eq("aggregate_id", "560011")).first();
        assertNotNull(ev);
        assertEquals("serviceability_pin", ev.getString("aggregate_type"));
        assertEquals("SERVICE_AREA_UPDATED", ev.getString("type"));
        assertEquals("SA-BSON", ((Document) ev.get("detail")).getString("service_area_id"));
        assertNotNull(ev.getDate("at"));
        // product_events must stay product-only: no service-area rows there
        assertEquals(0, db.getCollection("product_events").countDocuments(
                Filters.eq("product_id", "SA-BSON")));
    }

    @Test void bootstrap_idempotent_and_pincode_unique_index_present() {
        assertDoesNotThrow(() -> schemaBootstrap.bootstrap(db));
        boolean unique = false;
        for (Document ix : db.getCollection("service_areas").listIndexes()) {
            Document key = (Document) ix.get("key");
            if (key != null && key.containsKey("pincode")) {
                unique = Boolean.TRUE.equals(ix.getBoolean("unique"));
            }
        }
        assertTrue(unique, "(pincode) unique index present");
    }
}
