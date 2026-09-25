package com.tazzzo.serviceability;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Production Serviceability foundation (PR-06). Owns the mapping
 * {@code pincode → serviceAreaId (public) + fulfillmentLocationId (INTERNAL)} in the
 * {@code service_areas} collection. A module in the existing deployable; no controller, no
 * Spring wiring yet (first consuming PR adds scanning — standing policy).
 *
 * <p><b>Boundary (STEP 4):</b> {@code serviceAreaId} is the safe public routing abstraction;
 * {@code fulfillmentLocationId} is internal, produced ONLY by this resolution, never accepted
 * from or returned to clients. Inventory keeps keying stock by
 * {@code (skuId, fulfillmentLocationId)}; this module owns the mapping and NEVER calls
 * Inventory/Pricing/Media — Commerce Read composes them later.
 *
 * <p><b>Audit (STEP 20/21):</b> Serviceability has no product identity, so it does NOT reuse the
 * product-coupled {@code EventPayload}/{@code product_events}/{@code WritePath} rail — faking a
 * productId with a service-area id would corrupt product audit semantics. It uses the neutral
 * {@link DomainAudit} ({@code domain_events}, aggregate_type=service_area) with the same
 * event-before-state discipline inside the same {@link Tx} transaction.
 *
 * <p><b>Duplicate delivery (ADR-015):</b> CAS + unique(pincode) make duplicates mutation-safe
 * (typed conflict, no double increment, no audit residue after rollback) — NOT idempotency. An
 * {@code idempotencyKey} + dedupe store is REQUIRED in the PR that first exposes these writes on
 * HTTP/CMS/queue/worker transports.
 *
 * <p><b>Observability hooks:</b> serviceability_resolve_success / _unserviceable / _inactive /
 * _no_active_route, serviceability_write_success / _validation_failure / _conflict. PINs are
 * MASKED in logs (first 3 digits only) — coarse geography, no precise location data.
 */
public class ServiceabilityService implements ServiceabilityReadPort {

    private static final Logger log = LoggerFactory.getLogger(ServiceabilityService.class);
    static final String COLLECTION = "service_areas";
    static final String AGGREGATE_TYPE = "service_area";

    private final Tx tx;
    private final MongoDatabase db;
    private final DomainAudit audit;
    private final Clock clock;

    public ServiceabilityService(Tx tx, MongoDatabase db, DomainAudit audit, Clock clock) {
        this.tx = Objects.requireNonNull(tx);
        this.db = Objects.requireNonNull(db);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ServiceabilityResolution resolveByPincode(Pincode pin) {
        Objects.requireNonNull(pin, "pin required");
        Document d = db.getCollection(COLLECTION).find(Filters.eq("pincode", pin.value())).first();
        if (d == null) {
            log.debug("serviceability_resolve_unserviceable pin={}", mask(pin));
            return ServiceabilityResolution.unserviceable();
        }
        ServiceArea area = fromDocument(d);
        if (!area.active()) {
            log.debug("serviceability_resolve_inactive pin={} area={}", mask(pin), area.serviceAreaId());
            return ServiceabilityResolution.inactive(area.serviceAreaId());
        }
        return area.activeRoute()
                .map(route -> {
                    log.debug("serviceability_resolve_success pin={} area={}", mask(pin), area.serviceAreaId());
                    return ServiceabilityResolution.serviceable(area.serviceAreaId(), route.fulfillmentLocationId());
                })
                .orElseGet(() -> {
                    // CONFIG ERROR signal, never silently routed (STEP 17).
                    log.warn("serviceability_resolve_no_active_route pin={} area={}", mask(pin), area.serviceAreaId());
                    return ServiceabilityResolution.noActiveRoute(area.serviceAreaId());
                });
    }

    /**
     * Atomically create or CAS-replace one pincode's complete routing config.
     *
     * @return the new version.
     */
    public long upsertServiceArea(UpsertServiceAreaCommand cmd) {
        ServiceArea validated = validateCommand(cmd);
        long newVersion;
        try {
            newVersion = (cmd.expectedVersion() == null) ? 1L : Math.addExact(cmd.expectedVersion(), 1);
        } catch (ArithmeticException e) {
            throw new InvalidServiceabilityException("expectedVersion overflow: " + cmd.expectedVersion());
        }
        Date now = Date.from(clock.instant());
        DomainEvent event = new DomainEvent(AGGREGATE_TYPE, cmd.serviceAreaId(),
                "SERVICE_AREA_UPDATED", auditDetail(cmd, newVersion));

        try {
            tx.run(session -> {
                // event-before-state on the NEUTRAL rail, same transaction (C-3 discipline).
                audit.append(session, event);
                if (cmd.expectedVersion() == null) {
                    db.getCollection(COLLECTION).insertOne(session,
                            new Document("pincode", cmd.pincode())
                                    .append("service_area_id", cmd.serviceAreaId())
                                    .append("active", true)
                                    .append("routes", routeDocs(validated.routes()))
                                    .append("version", newVersion)
                                    .append("source", cmd.source())
                                    .append("created_at", now)
                                    .append("updated_at", now));
                } else {
                    UpdateResult r = db.getCollection(COLLECTION).updateOne(session,
                            Filters.and(Filters.eq("pincode", cmd.pincode()),
                                    Filters.eq("version", cmd.expectedVersion())),
                            Updates.combine(
                                    Updates.set("service_area_id", cmd.serviceAreaId()),
                                    Updates.set("routes", routeDocs(validated.routes())),
                                    Updates.set("version", newVersion),
                                    Updates.set("source", cmd.source()),
                                    Updates.set("updated_at", now)));
                    if (r.getModifiedCount() == 0) {
                        Document existing = db.getCollection(COLLECTION)
                                .find(session, Filters.eq("pincode", cmd.pincode())).first();
                        if (existing == null) {
                            throw new ServiceabilityNotFoundException("no service area for pin");
                        }
                        throw new ServiceabilityConflictException("stale update for pin config"
                                + " expectedVersion=" + cmd.expectedVersion());
                    }
                }
            });
        } catch (ServiceabilityConflictException e) {
            log.info("serviceability_write_conflict pin={} reason=stale_version expected={}",
                    mask(cmd.pincode()), cmd.expectedVersion());
            throw e;
        } catch (com.mongodb.MongoException e) {
            if (isDuplicateKey(e)) {
                log.info("serviceability_write_conflict pin={} reason=duplicate_create", mask(cmd.pincode()));
                throw new ServiceabilityConflictException("service area already exists for pin;"
                        + " use expectedVersion to update");
            }
            throw e;
        }
        log.info("serviceability_write_success pin={} area={} version={} routes={}",
                mask(cmd.pincode()), cmd.serviceAreaId(), newVersion, validated.routes().size());
        return newVersion;
    }

    // --- validation ---------------------------------------------------------------

    /** Validates by constructing the domain ServiceArea (all invariants live there). */
    static ServiceArea validateCommand(UpsertServiceAreaCommand cmd) {
        try {
            Objects.requireNonNull(cmd, "command required");
            if (cmd.expectedVersion() != null && cmd.expectedVersion() < 1) {
                throw new InvalidServiceabilityException(
                        "expectedVersion must be positive: " + cmd.expectedVersion());
            }
            long version = (cmd.expectedVersion() == null) ? 1L : cmd.expectedVersion();
            return new ServiceArea(cmd.serviceAreaId(), cmd.pincode(), true,
                    cmd.routes() == null ? null : cmd.routes(), version);
        } catch (InvalidServiceabilityException e) {
            log.info("serviceability_write_validation_failure pin={} reason={}",
                    mask(cmd == null ? null : cmd.pincode()), e.getMessage());
            throw e;
        } catch (IllegalArgumentException | NullPointerException e) {
            log.info("serviceability_write_validation_failure pin={} reason={}",
                    mask(cmd == null ? null : cmd.pincode()), e.getMessage());
            throw new InvalidServiceabilityException(e.getMessage());
        }
    }

    // --- helpers -------------------------------------------------------------------

    private static List<Document> routeDocs(List<ServiceabilityRoute> routes) {
        List<Document> docs = new ArrayList<>(routes.size());
        for (ServiceabilityRoute r : routes) {
            docs.add(new Document("fulfillment_location_id", r.fulfillmentLocationId())
                    .append("priority", r.priority())
                    .append("active", r.active()));
        }
        return docs;
    }

    private static ServiceArea fromDocument(Document d) {
        List<Document> routeDocs = d.getList("routes", Document.class, List.of());
        List<ServiceabilityRoute> routes = new ArrayList<>(routeDocs.size());
        for (Document r : routeDocs) {
            routes.add(new ServiceabilityRoute(
                    r.getString("fulfillment_location_id"),
                    ((Number) r.get("priority")).intValue(),
                    r.getBoolean("active", false)));
        }
        return new ServiceArea(d.getString("service_area_id"), d.getString("pincode"),
                d.getBoolean("active", false), routes, ((Number) d.get("version")).longValue());
    }

    private static Map<String, Object> auditDetail(UpsertServiceAreaCommand cmd, long version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pincode", cmd.pincode());
        m.put("route_count", cmd.routes() == null ? 0 : cmd.routes().size());
        m.put("version", version);
        return m;
    }

    /** Coarse PIN masking for logs: first 3 digits only (region, not locality). */
    private static String mask(Pincode pin) {
        return pin == null ? "?" : mask(pin.value());
    }

    private static String mask(String pin) {
        return (pin == null || pin.length() < 6) ? "?" : pin.substring(0, 3) + "XXX";
    }

    private static boolean isDuplicateKey(com.mongodb.MongoException e) {
        if (e instanceof MongoWriteException w) {
            return w.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY;
        }
        if (com.mongodb.ErrorCategory.fromErrorCode(e.getCode()) == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.contains("E11000");
    }
}
