package com.tazzzo.inventory;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Production Inventory foundation (PR-04, ADR-004). Authoritative for stock per
 * {@code (sku_id, fulfillment_location_id)}. A module in the existing deployable; no public
 * endpoint, no Spring wiring yet (the PR that first needs a bean adds scanning — same policy
 * as Pricing).
 *
 * <p><b>Transaction boundary (Phase 3.1 STEP 10/21):</b> inventory mutations touch ONLY the
 * {@code inventory} collection plus the C-4 audit event — never ProductCardBaseProjection
 * (stock is runtime location enrichment; a stock change must not rewrite global projections)
 * and never Pricing state.
 *
 * <p><b>Oversell safety:</b> every mutation is a single atomic conditional update — the filter
 * carries the invariant (version match, reserved coverage, availability) and the update applies
 * the change + {@code version++} in one server-side operation. There is no read-modify-write
 * anywhere in this class.
 *
 * <p><b>Identity seam:</b> the C-4 audit {@link EventPayload} requires a productId; at launch
 * {@code skuId == productId} and the skuId is passed. When variants make them diverge, callers
 * supply the parent productId for the audit while every inventory KEY remains
 * {@code (sku_id, fulfillment_location_id)} — the collection schema does not change.
 *
 * <p><b>Duplicate delivery (ADR-015):</b> CAS + the unique key make duplicate deliveries
 * mutation-safe (the second delivery conflicts; version never double-increments; no duplicate
 * audit row survives rollback) — this is NOT return-original-result idempotency. An
 * {@code idempotencyKey} + dedupe store is REQUIRED in the PR that first exposes these writes
 * on a retrying/redelivering transport (HTTP, queue, worker).
 *
 * <p><b>Observability hooks:</b> inventory_write_success, inventory_write_validation_failure,
 * inventory_write_conflict, inventory_read_missing, inventory_read_inactive (structured logs;
 * metrics attach when the exporter is chosen).
 */
public class InventoryService implements InventoryReadPort {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    static final String COLLECTION = "inventory";

    /**
     * Sanity ceiling for any single quantity value: one million units of one SKU at one dark
     * store. A FAT-FINGER / unit-confusion guard (e.g. grams written as units), NOT a business
     * stocking policy — raise deliberately if a real catalogue ever needs to.
     */
    static final long MAX_QUANTITY = 1_000_000L;

    private final Tx tx;
    private final WritePath writePath;
    private final MongoDatabase db;
    private final Clock clock;

    public InventoryService(Tx tx, WritePath writePath, Clock clock) {
        this.tx = Objects.requireNonNull(tx);
        this.writePath = Objects.requireNonNull(writePath);
        this.db = writePath.database();
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Absolute set of onHand/threshold/cap for one inventory row. Create when
     * {@code expectedVersion} is null (reserved starts at 0); CAS update otherwise. The update
     * filter atomically requires {@code reserved <= newOnHand}, so available can never go
     * negative — setting onHand below live reservations is rejected as {@link InvalidInventoryException}.
     *
     * @return the new version.
     */
    public long setInventory(SetInventoryCommand cmd) {
        validateCommand(cmd);
        long newVersion;
        try {
            newVersion = (cmd.expectedVersion() == null) ? 1L : Math.addExact(cmd.expectedVersion(), 1);
        } catch (ArithmeticException e) {
            // A Long.MAX_VALUE expectedVersion can only be caller corruption; never wrap silently.
            throw new InvalidInventoryException("expectedVersion overflow: " + cmd.expectedVersion());
        }
        Date now = Date.from(clock.instant());
        EventPayload event = new EventPayload("INVENTORY_SET", cmd.skuId(), auditDetail(cmd, newVersion));

        try {
            tx.run(session -> {
                if (cmd.expectedVersion() == null) {
                    writePath.auxWrite(session, COLLECTION, event, c -> c.insertOne(session,
                            new Document("sku_id", cmd.skuId())
                                    .append("fulfillment_location_id", cmd.fulfillmentLocationId())
                                    .append("on_hand", cmd.onHand())
                                    .append("reserved", 0L)
                                    .append("low_stock_threshold", cmd.lowStockThreshold())
                                    .append("max_purchasable", cmd.maxPurchasable())
                                    .append("version", newVersion)
                                    .append("active", true)
                                    .append("source", cmd.source())
                                    .append("created_at", now)
                                    .append("updated_at", now)));
                } else {
                    UpdateResult[] r = new UpdateResult[1];
                    writePath.auxWrite(session, COLLECTION, event, c -> r[0] = c.updateOne(session,
                            Filters.and(keyFilter(cmd.skuId(), cmd.fulfillmentLocationId()),
                                    Filters.eq("version", cmd.expectedVersion()),
                                    Filters.lte("reserved", cmd.onHand())),   // atomic reserved-coverage guard
                            Updates.combine(
                                    Updates.set("on_hand", cmd.onHand()),
                                    Updates.set("low_stock_threshold", cmd.lowStockThreshold()),
                                    Updates.set("max_purchasable", cmd.maxPurchasable()),
                                    Updates.set("version", newVersion),
                                    Updates.set("source", cmd.source()),
                                    Updates.set("updated_at", now))));
                    if (r[0].getModifiedCount() == 0) {
                        // Distinguish the three failure causes inside the same transaction.
                        Document existing = db.getCollection(COLLECTION)
                                .find(session, keyFilter(cmd.skuId(), cmd.fulfillmentLocationId())).first();
                        if (existing == null) {
                            throw new InventoryNotFoundException("no inventory row for "
                                    + cmd.skuId() + "@" + cmd.fulfillmentLocationId());
                        }
                        long reserved = ((Number) existing.get("reserved")).longValue();
                        long currentVersion = ((Number) existing.get("version")).longValue();
                        if (currentVersion == cmd.expectedVersion() && reserved > cmd.onHand()) {
                            throw new InvalidInventoryException("onHand " + cmd.onHand()
                                    + " would fall below live reserved " + reserved);
                        }
                        throw new InventoryConflictException("stale update for " + cmd.skuId()
                                + "@" + cmd.fulfillmentLocationId() + " expectedVersion=" + cmd.expectedVersion());
                    }
                }
            });
        } catch (InventoryConflictException e) {
            log.info("inventory_write_conflict sku={} loc={} reason=stale_version expected={}",
                    cmd.skuId(), cmd.fulfillmentLocationId(), cmd.expectedVersion());
            throw e;
        } catch (InvalidInventoryException e) {
            log.info("inventory_write_validation_failure sku={} loc={} reason={}",
                    cmd.skuId(), cmd.fulfillmentLocationId(), e.getMessage());
            throw e;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                log.info("inventory_write_conflict sku={} loc={} reason=duplicate_create",
                        cmd.skuId(), cmd.fulfillmentLocationId());
                throw new InventoryConflictException("inventory already exists for " + cmd.skuId()
                        + "@" + cmd.fulfillmentLocationId() + "; use expectedVersion to update");
            }
            throw e;
        }
        log.info("inventory_write_success sku={} loc={} version={}",
                cmd.skuId(), cmd.fulfillmentLocationId(), newVersion);
        return newVersion;
    }

    /**
     * INTERNAL FUTURE SEAM (STEP 9/10) — the oversell-safe reservation primitive checkout will
     * orchestrate later. Atomically reserves {@code qty} iff the row is active and
     * {@code available >= qty}; returns false (mutating nothing, leaving no audit residue) when
     * stock is insufficient or the row is missing/inactive. Deliberately availability-conditioned,
     * not version-conditioned: a concurrent threshold edit must not fail a legitimate reservation;
     * atomicity comes from the single conditional update, and {@code version} still increments.
     *
     * <p><b>PACKAGE-PRIVATE BY DESIGN (PR-04 review, Option A).</b> A reservation without a
     * reservationId, expiry, release path, recovery/reconciliation and an idempotency key can
     * strand {@code reserved} stock forever if called twice or abandoned. Until that lifecycle
     * exists (checkout phase), NOTHING outside {@code com.tazzzo.inventory} may invoke this —
     * the atomic mechanics stay proven by tests through a same-package test bridge only.
     * Widening this to public is a review-gated change, not a convenience edit.
     */
    boolean tryReserve(String skuId, String fulfillmentLocationId, long qty) {
        new InventoryKey(skuId, fulfillmentLocationId);
        if (qty < 1 || qty > MAX_QUANTITY) {
            log.info("inventory_write_validation_failure sku={} loc={} reason=bad_reserve_qty {}",
                    skuId, fulfillmentLocationId, qty);
            throw new InvalidInventoryException("reserve qty must be in [1," + MAX_QUANTITY + "]: " + qty);
        }
        Date now = Date.from(clock.instant());
        EventPayload event = new EventPayload("INVENTORY_RESERVED", skuId,
                Map.of("fulfillment_location_id", fulfillmentLocationId, "qty", qty));
        try {
            tx.run(session -> {
                UpdateResult[] r = new UpdateResult[1];
                // Event-before-state (C-3) is preserved: the audit event is appended first, and if
                // the conditional update matches nothing the control exception aborts the
                // transaction so NO event survives a failed reservation.
                writePath.auxWrite(session, COLLECTION, event, c -> r[0] = c.updateOne(session,
                        Filters.and(keyFilter(skuId, fulfillmentLocationId),
                                Filters.eq("active", true),
                                Filters.expr(new Document("$gte", java.util.List.of(
                                        new Document("$subtract", java.util.List.of("$on_hand", "$reserved")),
                                        qty)))),
                        Updates.combine(
                                Updates.inc("reserved", qty),
                                Updates.inc("version", 1L),
                                Updates.set("updated_at", now))));
                if (r[0].getModifiedCount() == 0) {
                    throw ReservationUnavailable.INSTANCE;
                }
            });
        } catch (ReservationUnavailable unavailable) {
            log.info("inventory_reserve_unavailable sku={} loc={} qty={}", skuId, fulfillmentLocationId, qty);
            return false;
        }
        log.info("inventory_reserve_success sku={} loc={} qty={}", skuId, fulfillmentLocationId, qty);
        return true;
    }

    /**
     * ONE query for a page of SKUs at one location (PR-08, STEP 19/20). INDEX-SHAPE REASONING
     * (no explain() was captured — this is design reasoning, not a measured query plan): the
     * filter {@code sku_id IN (...) AND fulfillment_location_id = X} matches the existing unique
     * {@code (sku_id, fulfillment_location_id)} index prefix-per-IN-value, i.e. at most |ids|
     * bounded index seeks in one round trip; for page sizes ≤50 no additional index is justified.
     * Requested SKUs with no row are reported as {@code MISSING} (never silently absent);
     * inactive rows as {@code INACTIVE}; nothing outside the supplied location can appear.
     *
     * <p>VALIDATION PARITY (STEP 5): each distinct SKU is validated through the SAME
     * {@link InventoryKey} rule as the point read — malformed internal input fails typed and is
     * never laundered into a business {@code MISSING}.
     */
    @Override
    public java.util.Map<String, InventoryLookup> findInventoryBatch(
            java.util.Collection<String> skuIds, String fulfillmentLocationId) {
        java.util.Objects.requireNonNull(skuIds, "skuIds required");
        if (fulfillmentLocationId == null || fulfillmentLocationId.isBlank()) {
            // Same exception TYPE as the point read's InventoryKey rejection — read-path identity
            // errors are IllegalArgumentException; InvalidInventoryException stays a WRITE-command
            // signal. Checked up front because an empty skuIds would skip the per-key validation.
            throw new IllegalArgumentException("fulfillmentLocationId required");
        }
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>(skuIds);
        java.util.Map<String, InventoryLookup> out = new java.util.LinkedHashMap<>();
        for (String id : distinct) {
            new InventoryKey(id, fulfillmentLocationId); // same validator as the point read
            out.put(id, InventoryLookup.missing()); // default: MISSING until a row proves otherwise
        }
        if (distinct.isEmpty()) {
            return out;
        }
        for (Document d : db.getCollection(COLLECTION).find(Filters.and(
                Filters.in("sku_id", distinct),
                Filters.eq("fulfillment_location_id", fulfillmentLocationId)))) {
            String skuId = d.getString("sku_id");
            InventoryRecord record = new InventoryRecord(
                    skuId, fulfillmentLocationId,
                    asLong(d.get("on_hand")), asLong(d.get("reserved")),
                    asLong(d.get("low_stock_threshold")), asLong(d.get("max_purchasable")),
                    asLong(d.get("version")), d.getBoolean("active", false));
            out.put(skuId, record.active()
                    ? InventoryLookup.of(InventoryLookup.Status.PRESENT, record)
                    : InventoryLookup.of(InventoryLookup.Status.INACTIVE, record));
        }
        return out;
    }

    @Override
    public InventoryLookup findInventory(String skuId, String fulfillmentLocationId) {
        new InventoryKey(skuId, fulfillmentLocationId);
        Document d = db.getCollection(COLLECTION).find(keyFilter(skuId, fulfillmentLocationId)).first();
        if (d == null) {
            log.debug("inventory_read_missing sku={} loc={}", skuId, fulfillmentLocationId);
            return InventoryLookup.missing();
        }
        InventoryRecord record = new InventoryRecord(
                skuId, fulfillmentLocationId,
                asLong(d.get("on_hand")), asLong(d.get("reserved")),
                asLong(d.get("low_stock_threshold")), asLong(d.get("max_purchasable")),
                asLong(d.get("version")), d.getBoolean("active", false));
        if (!record.active()) {
            log.debug("inventory_read_inactive sku={} loc={}", skuId, fulfillmentLocationId);
            return InventoryLookup.of(InventoryLookup.Status.INACTIVE, record);
        }
        return InventoryLookup.of(InventoryLookup.Status.PRESENT, record);
    }

    // --- validation (STEP 20) ---------------------------------------------------

    static void validateCommand(SetInventoryCommand cmd) {
        try {
            Objects.requireNonNull(cmd, "command required");
            new InventoryKey(cmd.skuId(), cmd.fulfillmentLocationId());
            if (cmd.onHand() < 0) throw new InvalidInventoryException("onHand must be >= 0: " + cmd.onHand());
            if (cmd.lowStockThreshold() < 0) {
                throw new InvalidInventoryException("lowStockThreshold must be >= 0: " + cmd.lowStockThreshold());
            }
            if (cmd.maxPurchasable() < 0) {
                throw new InvalidInventoryException("maxPurchasable must be >= 0: " + cmd.maxPurchasable());
            }
            if (cmd.onHand() > MAX_QUANTITY) {
                throw new InvalidInventoryException("onHand exceeds sanity ceiling " + MAX_QUANTITY
                        + " (fat-finger guard, see MAX_QUANTITY)");
            }
            if (cmd.expectedVersion() != null && cmd.expectedVersion() < 1) {
                throw new InvalidInventoryException("expectedVersion must be positive: " + cmd.expectedVersion());
            }
        } catch (InvalidInventoryException e) {
            log.info("inventory_write_validation_failure sku={} reason={}", safeSku(cmd), e.getMessage());
            throw e;
        } catch (IllegalArgumentException | NullPointerException e) {
            log.info("inventory_write_validation_failure sku={} reason={}", safeSku(cmd), e.getMessage());
            throw new InvalidInventoryException(e.getMessage());
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static Bson keyFilter(String skuId, String fulfillmentLocationId) {
        return Filters.and(Filters.eq("sku_id", skuId),
                Filters.eq("fulfillment_location_id", fulfillmentLocationId));
    }

    private static Map<String, Object> auditDetail(SetInventoryCommand cmd, long version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fulfillment_location_id", cmd.fulfillmentLocationId());
        m.put("on_hand", cmd.onHand());
        m.put("version", version);
        return m;
    }

    private static long asLong(Object v) {
        return ((Number) Objects.requireNonNull(v, "numeric field missing")).longValue();
    }

    private static String safeSku(SetInventoryCommand cmd) {
        return cmd == null ? "?" : String.valueOf(cmd.skuId());
    }

    /** Control-flow signal that aborts the reserve transaction (rolling back its audit event). */
    private static final class ReservationUnavailable extends RuntimeException {
        static final ReservationUnavailable INSTANCE = new ReservationUnavailable();
        private ReservationUnavailable() {
            super(null, null, false, false);
        }
    }
}
