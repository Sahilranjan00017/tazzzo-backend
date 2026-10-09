package com.tazzzo.inventory;

import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
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
        return setInventory(cmd, null);
    }

    /**
     * As {@link #setInventory(SetInventoryCommand)}, attributed to {@code actor}: the audit event records WHO changed the
     * stock. The admin API always uses this form; the actor-less form is a fixture/seed seam that no production class
     * may call (pinned by ModuleBoundaryTest).
     */
    public long setInventory(SetInventoryCommand cmd, com.tazzzo.common.audit.Actor actor) {
        validateCommand(cmd);
        long newVersion;
        try {
            newVersion = (cmd.expectedVersion() == null) ? 1L : Math.addExact(cmd.expectedVersion(), 1);
        } catch (ArithmeticException e) {
            // A Long.MAX_VALUE expectedVersion can only be caller corruption; never wrap silently.
            throw new InvalidInventoryException("expectedVersion overflow: " + cmd.expectedVersion());
        }
        Date now = Date.from(clock.instant());
        EventPayload event = new EventPayload("INVENTORY_SET", cmd.skuId(), auditDetail(cmd, newVersion), actor);

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
     * Delist or relist a SKU at one location: CAS on {@code expectedVersion}, version +1, audited with the actor. This is
     * the explicit lifecycle command {@link SetInventoryCommand} defers to; an inactive row reads as INACTIVE (not
     * purchasable) and keeps its counters, so relisting restores the exact stock.
     *
     * @return the new version
     */
    public long setActive(com.tazzzo.common.audit.Actor actor, String skuId, String fulfillmentLocationId,
                          long expectedVersion, boolean active) {
        Objects.requireNonNull(actor, "actor");
        new InventoryKey(skuId, fulfillmentLocationId);
        if (expectedVersion < 1) {
            throw new InvalidInventoryException("expectedVersion must be positive: " + expectedVersion);
        }
        long newVersion = expectedVersion + 1;
        Date now = Date.from(clock.instant());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fulfillment_location_id", fulfillmentLocationId);
        detail.put("active", active);
        detail.put("version", newVersion);
        EventPayload event = new EventPayload(active ? "INVENTORY_ACTIVATED" : "INVENTORY_DEACTIVATED", skuId, detail, actor);
        tx.run(session -> {
            UpdateResult[] r = new UpdateResult[1];
            writePath.auxWrite(session, COLLECTION, event, c -> r[0] = c.updateOne(session,
                    Filters.and(keyFilter(skuId, fulfillmentLocationId), Filters.eq("version", expectedVersion)),
                    Updates.combine(Updates.set("active", active), Updates.set("version", newVersion),
                            Updates.set("updated_at", now))));
            if (r[0].getMatchedCount() == 0) {
                if (db.getCollection(COLLECTION).find(session, keyFilter(skuId, fulfillmentLocationId)).first() == null) {
                    throw new InventoryNotFoundException("no inventory row for " + skuId + "@" + fulfillmentLocationId);
                }
                throw new InventoryConflictException("stale update for " + skuId + "@" + fulfillmentLocationId
                        + " expectedVersion=" + expectedVersion);
            }
        });
        log.info("inventory_active_changed sku={} loc={} active={} version={}", skuId, fulfillmentLocationId, active, newVersion);
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
     * strand {@code reserved} stock forever if called twice or abandoned. NOTHING outside
     * {@code com.tazzzo.inventory} may invoke this raw primitive directly.
     *
     * <p><b>PR-14A superseded this as THE production reservation path.</b> Order-facing
     * reservations go through {@link InventoryReservationService} (a real lifecycle: opaque id,
     * TTL, release, consume, reconciliation, idempotency by {@code orderId}), which shares this
     * class's per-row atomic mechanics via {@link #reserveOneSkuInSession} rather than
     * reimplementing them — there is exactly ONE conditional-update shape for "reserve one SKU
     * row", never two subtly different ones. This method remains only as a single-SKU,
     * no-lifecycle convenience for existing same-package tests.
     */
    boolean tryReserve(String skuId, String fulfillmentLocationId, long qty) {
        validateReserveQty(skuId, fulfillmentLocationId, qty);
        Instant now = clock.instant();
        try {
            tx.run(session -> {
                if (!reserveOneSkuInSession(session, skuId, fulfillmentLocationId, qty, now)) {
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

    private void validateReserveQty(String skuId, String fulfillmentLocationId, long qty) {
        new InventoryKey(skuId, fulfillmentLocationId);
        if (qty < 1 || qty > MAX_QUANTITY) {
            log.info("inventory_write_validation_failure sku={} loc={} reason=bad_reserve_qty {}",
                    skuId, fulfillmentLocationId, qty);
            throw new InvalidInventoryException("reserve qty must be in [1," + MAX_QUANTITY + "]: " + qty);
        }
    }

    /**
     * PR-14A — the ONE atomic "reserve one SKU row" primitive, usable INSIDE a caller-owned
     * transaction (no {@code tx.run}/{@code tx.call} here — see {@link Tx}'s multiple-invocation
     * contract, which this method's caller alone is responsible for satisfying). Package-private:
     * only {@link InventoryReservationService}, in the SAME package, may compose several calls to
     * this (one per SKU) inside its own multi-document reservation transaction.
     *
     * @return true iff the row was active and had enough available stock (mutated); false
     *         otherwise (nothing mutated, no audit residue for this call).
     */
    boolean reserveOneSkuInSession(ClientSession session, String skuId, String fulfillmentLocationId, long qty,
                                   Instant now) {
        new InventoryKey(skuId, fulfillmentLocationId);
        Date nowDate = Date.from(now);
        EventPayload event = new EventPayload("INVENTORY_RESERVED", skuId,
                Map.of("fulfillment_location_id", fulfillmentLocationId, "qty", qty));
        UpdateResult[] r = new UpdateResult[1];
        // Event-before-state (C-3): appended first, so a failed conditional update (0 modified)
        // leaves the event to be rolled back with the rest of the CALLER's transaction — never
        // surviving alone, since this method never commits anything itself.
        writePath.auxWrite(session, COLLECTION, event, c -> r[0] = c.updateOne(session,
                Filters.and(keyFilter(skuId, fulfillmentLocationId), Filters.eq("active", true),
                        Filters.expr(new Document("$gte", java.util.List.of(
                                new Document("$subtract", java.util.List.of("$on_hand", "$reserved")), qty)))),
                Updates.combine(Updates.inc("reserved", qty), Updates.inc("version", 1L),
                        Updates.set("updated_at", nowDate))));
        return r[0].getModifiedCount() > 0;
    }

    /**
     * PR-14A — the ONE atomic "release (un-reserve) one SKU row" primitive, session-aware, same
     * discipline as {@link #reserveOneSkuInSession}. The guard ({@code reserved >= qty}) makes
     * {@code reserved} structurally unable to go negative; a {@code false} return is a
     * data-integrity signal for the caller (which owns the transaction and decides whether to
     * abort it), never silently ignored here.
     */
    boolean releaseOneSkuInSession(ClientSession session, String skuId, String fulfillmentLocationId, long qty,
                                   Instant now) {
        new InventoryKey(skuId, fulfillmentLocationId);
        Date nowDate = Date.from(now);
        EventPayload event = new EventPayload("INVENTORY_RESERVATION_RELEASED", skuId,
                Map.of("fulfillment_location_id", fulfillmentLocationId, "qty", qty));
        UpdateResult[] r = new UpdateResult[1];
        writePath.auxWrite(session, COLLECTION, event, c -> r[0] = c.updateOne(session,
                Filters.and(keyFilter(skuId, fulfillmentLocationId), Filters.gte("reserved", qty)),
                Updates.combine(Updates.inc("reserved", -qty), Updates.inc("version", 1L),
                        Updates.set("updated_at", nowDate))));
        return r[0].getModifiedCount() > 0;
    }

    /**
     * PR-14A — the ONE atomic "consume (decrement on_hand and reserved) one SKU row" primitive,
     * session-aware, same discipline as {@link #reserveOneSkuInSession}. Both guards
     * ({@code reserved >= qty} and {@code on_hand >= qty}) apply in the SAME conditional update,
     * so a row can never be left with {@code reserved > on_hand}.
     */
    boolean consumeOneSkuInSession(ClientSession session, String skuId, String fulfillmentLocationId, long qty,
                                   Instant now) {
        new InventoryKey(skuId, fulfillmentLocationId);
        Date nowDate = Date.from(now);
        EventPayload event = new EventPayload("INVENTORY_RESERVATION_CONSUMED", skuId,
                Map.of("fulfillment_location_id", fulfillmentLocationId, "qty", qty));
        UpdateResult[] r = new UpdateResult[1];
        writePath.auxWrite(session, COLLECTION, event, c -> r[0] = c.updateOne(session,
                Filters.and(keyFilter(skuId, fulfillmentLocationId), Filters.gte("reserved", qty),
                        Filters.gte("on_hand", qty)),
                Updates.combine(Updates.inc("on_hand", -qty), Updates.inc("reserved", -qty),
                        Updates.inc("version", 1L), Updates.set("updated_at", nowDate))));
        return r[0].getModifiedCount() > 0;
    }

    /**
     * Cancellation restock: put {@code qty} units of a CONSUMED reservation back on hand for one SKU row, session-aware,
     * in the caller's transaction. Guarded so the row can never exceed {@link #MAX_QUANTITY}. Idempotency is NOT decided
     * here: the caller proves "first and only restock" through the reservation header's one-shot marker.
     */
    boolean restockOneSkuInSession(ClientSession session, String skuId, String fulfillmentLocationId, long qty,
                                   Instant now) {
        new InventoryKey(skuId, fulfillmentLocationId);
        if (qty < 1) {
            throw new IllegalArgumentException("restock quantity must be positive: " + qty);
        }
        EventPayload event = new EventPayload("INVENTORY_RESTOCKED", skuId,
                Map.of("fulfillment_location_id", fulfillmentLocationId, "qty", qty));
        UpdateResult[] r = new UpdateResult[1];
        writePath.auxWrite(session, COLLECTION, event, c -> r[0] = c.updateOne(session,
                Filters.and(keyFilter(skuId, fulfillmentLocationId), Filters.lte("on_hand", MAX_QUANTITY - qty)),
                Updates.combine(Updates.inc("on_hand", qty), Updates.inc("version", 1L),
                        Updates.set("updated_at", Date.from(now)))));
        return r[0].getModifiedCount() > 0;
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

    /** One page of the admin list; {@code next*} is the position of the last row READ (valid or not), null on the last page. */
    public record ListPage(List<InventoryRecord> rows, String nextSku, String nextLocation, int skipped) { }

    /**
     * A page of inventory rows for the admin list, in {@code (sku_id, fulfillment_location_id)} order — the order of the
     * unique index, read with an index scan and no in-memory sort; a keyset position {@code after} resumes with an index
     * seek. Optional filters: one fulfilment location, and a derived stock state ({@code IN_STOCK}, {@code LOW_STOCK},
     * {@code OUT_OF_STOCK} over ACTIVE rows, or {@code INACTIVE} = not active). The state is computed by the server from the
     * persisted counters with the SAME arithmetic as {@link InventoryRecord#stockState()}, so the feed agrees with a point
     * read. Neither filter can seek on the {@code (sku_id, fulfillment_location_id)} index: both are residual predicates
     * over the scanned range, so a sparse filter examines up to the rest of the collection — bounded by
     * {@link #LIST_MAX_TIME_MS} (then {@link InventoryListTimeoutException}). A stored row that breaks the record invariants
     * (a legacy or corrupt document) is left out of the page and counted in {@code skipped}; the position still moves past
     * it, so one bad row never blocks the pages after it. A counter that is not a whole number (e.g. {@code on_hand: 5.5},
     * which the state query would still match) counts as such a row. Because rows are left out AFTER the page is read, a
     * page can be short or even empty and still carry a {@code next*} position: callers follow it until it is null.
     */
    public ListPage list(String fulfillmentLocationId, String state, String afterSku, String afterLocation, int limit) {
        if (limit < 1 || limit > LIST_MAX_LIMIT) {
            throw new InvalidInventoryException("limit must be between 1 and " + LIST_MAX_LIMIT);
        }
        // only rows whose two ids can be a cursor position are scanned: each a real string (not missing, not an array that
        // merely contains one), non-empty, at most a key's length. Anything else could never be paged past — a cursor built
        // from it would be wrong, unreadable or refused — so it is outside the list altogether.
        List<Bson> filters = new ArrayList<>(List.of(POSITIONABLE));
        if (fulfillmentLocationId != null) {
            new InventoryKey("TZP-0", fulfillmentLocationId);   // the same location rule as every point read
            filters.add(Filters.eq("fulfillment_location_id", fulfillmentLocationId));
        }
        if ((afterSku == null) != (afterLocation == null)) {
            throw new InvalidInventoryException("invalid cursor");
        }
        if (afterSku != null) {
            // a position only (typed string comparisons), so it may be any stored value, even one a key would refuse
            filters.add(Filters.or(Filters.gt("sku_id", afterSku),
                    Filters.and(Filters.eq("sku_id", afterSku), Filters.gt("fulfillment_location_id", afterLocation))));
        }
        if (state != null) {
            filters.add(stateFilter(state));
        }
        List<Document> docs = new ArrayList<>(limit + 1);
        try {
            // one row past the page tells whether a next page exists, without a count
            db.getCollection(COLLECTION).find(Filters.and(filters))
                    .sort(Sorts.ascending("sku_id", "fulfillment_location_id")).limit(limit + 1)
                    .maxTime(LIST_MAX_TIME_MS, java.util.concurrent.TimeUnit.MILLISECONDS).into(docs);
        } catch (com.mongodb.MongoExecutionTimeoutException e) {
            throw new InventoryListTimeoutException();
        }
        boolean more = docs.size() > limit;
        List<Document> page = more ? docs.subList(0, limit) : docs;
        List<InventoryRecord> out = new ArrayList<>(page.size());
        int skipped = 0;
        for (Document d : page) {
            try {
                out.add(new InventoryRecord(
                        d.getString("sku_id"), d.getString("fulfillment_location_id"),
                        asWholeLong(d.get("on_hand")), asWholeLong(d.get("reserved")),
                        asWholeLong(d.get("low_stock_threshold")), asWholeLong(d.get("max_purchasable")),
                        asWholeLong(d.get("version")), Boolean.TRUE.equals(d.get("active"))));
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("inventory_list_rows_skipped count={} reason=record_invariant", skipped);
        }
        if (!more) return new ListPage(out, null, null, skipped);
        Document last = page.get(page.size() - 1);
        return new ListPage(out, last.getString("sku_id"), last.getString("fulfillment_location_id"), skipped);
    }

    static final long LIST_MAX_TIME_MS = 2_000;

    private static final Bson POSITIONABLE = Filters.expr(new Document("$and", List.of(
            positionable("$sku_id"), positionable("$fulfillment_location_id"))));

    /** A string of 1..MAX_ID code points; {@code $and} short-circuits, so {@code $strLenCP} only ever sees a string. */
    private static Document positionable(String field) {
        return new Document("$and", List.of(
                new Document("$eq", List.of(new Document("$type", field), "string")),
                new Document("$gt", List.of(new Document("$strLenCP", field), 0)),
                new Document("$lte", List.of(new Document("$strLenCP", field), InventoryKey.MAX_ID))));
    }

    public static final int LIST_MAX_LIMIT = 200;

    /**
     * The derived state as a query: the SAME arithmetic as {@link InventoryRecord#stockState()}, over the persisted counters.
     * The active states first require the three counters to be numbers ({@code $and} short-circuits), so a corrupt stored
     * value makes that row match nothing instead of failing the whole query.
     */
    static Bson stateFilter(String state) {
        Document available = new Document("$subtract", List.of("$on_hand", "$reserved"));
        return switch (state) {
            case "INACTIVE" -> Filters.ne("active", true);
            case "OUT_OF_STOCK" -> activeWhere(new Document("$eq", List.of(available, 0L)));
            case "LOW_STOCK" -> activeWhere(new Document("$and", List.of(
                    new Document("$gt", List.of(available, 0L)),
                    new Document("$lte", List.of(available, "$low_stock_threshold")))));
            case "IN_STOCK" -> activeWhere(new Document("$gt", List.of(available, "$low_stock_threshold")));
            default -> throw new InvalidInventoryException("state must be one of IN_STOCK, LOW_STOCK, OUT_OF_STOCK, INACTIVE");
        };
    }

    private static Bson activeWhere(Document condition) {
        List<Object> all = new ArrayList<>();
        for (String f : List.of("$on_hand", "$reserved", "$low_stock_threshold")) all.add(new Document("$isNumber", f));
        all.add(condition);
        return Filters.and(Filters.eq("active", true), Filters.expr(new Document("$and", all)));
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

    /** Public for dry runs (bulk import): the same checks a write performs, without touching the database. */
    public static void validateCommand(SetInventoryCommand cmd) {
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

    /**
     * For the admin list: like {@link #asLong} but a stored counter that is not a whole number (5.5, NaN, Infinity) is
     * refused, not truncated — the state filter does its arithmetic on the exact stored value, so truncating here would
     * label a row differently from the query that selected it. The caller treats the refusal as a corrupt row.
     */
    private static long asWholeLong(Object v) {
        Number n = (Number) Objects.requireNonNull(v, "numeric field missing");
        boolean whole = switch (n) {
            case Double x -> Double.isFinite(x) && x == Math.rint(x);
            case Float x -> Float.isFinite(x) && x == Math.rint(x);
            case org.bson.types.Decimal128 x -> x.isFinite() && x.bigDecimalValue().stripTrailingZeros().scale() <= 0;
            default -> true;
        };
        if (!whole) throw new IllegalArgumentException("counter is not a whole number");
        return n.longValue();
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
