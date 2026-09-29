package com.tazzzo.inventory;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * PR-14A — the ONE production reservation lifecycle: {@code reserve}/{@code release}/
 * {@code consume}, retry-safe, all-or-nothing across every SKU in one order, idempotent by
 * {@code orderId}. Supersedes {@link InventoryService#tryReserve} for anything order-facing (see
 * that method's own javadoc).
 *
 * <p><b>Two call shapes, one algorithm:</b> the {@link InventoryReservationPort} methods
 * (session-aware) do the actual work and participate in WHATEVER transaction their caller owns —
 * they never start one themselves. This class's own {@code reserve(...)}/{@code release(...)}/
 * {@code consume(InventoryReservationId)} are STANDALONE wrappers: {@link #prepare} (for reserve)
 * or a fixed {@code now} (for release/consume) is computed BEFORE {@link Tx#call}, then delegated
 * to the session-aware method inside it — the same "fix everything before the transaction, return
 * the committed attempt's value" discipline {@code CheckoutService}/{@code OtpService} already
 * established.
 *
 * <p><b>Multi-SKU all-or-nothing (the load-bearing guarantee):</b> {@code reserve} performs one
 * atomic conditional update per SKU ({@link InventoryService#reserveOneSkuInSession}), in a
 * deterministic sorted-by-skuId order (MongoDB multi-document transaction deadlock-avoidance
 * discipline), inside ONE Mongo transaction. If any line's update matches zero documents, this
 * class throws — MongoDB itself rolls back every earlier line's increment in this SAME attempt,
 * together with the reservation header (which is inserted LAST, only after every line succeeded).
 * There is no manual "undo" anywhere in this class; the transaction engine provides it.
 *
 * <p><b>PR-14A hardening (M3) — failure classification.</b> Business outcomes are always a typed
 * {@link InventoryReservationFailure}; the future Order-facing contract never needs to know a
 * Mongo exception class. Session-aware methods deliberately let any {@link MongoException}
 * propagate UNTOUCHED — a transient/write-conflict error carries the label
 * {@code TransientTransactionError}, and {@link Tx#call}'s underlying
 * {@code ClientSession.withTransaction} inspects exactly that label to decide whether to retry the
 * whole callback; converting or swallowing it here would silently defeat that retry. Only the
 * STANDALONE wrappers, which own the transaction boundary, map a {@code MongoException} that
 * escapes {@code Tx.call} (i.e. the transaction ultimately failed, retries exhausted or not) to
 * {@link InventoryReservationFailure.Reason#UNAVAILABLE} — never re-thrown as a raw Mongo type.
 *
 * <p><b>PR-14A hardening (M2) — observability timing.</b> {@code success}/{@code failure}/
 * {@code transition} are recorded ONLY by the standalone wrappers, ONLY after their own
 * {@code Tx.call} has returned (committed). The session-aware port methods never touch
 * {@link InventoryReservationObservability} — a session-aware call is one step inside a CALLER's
 * outer transaction, whose eventual commit or rollback this class cannot observe.
 */
@Service
public class InventoryReservationService implements InventoryReservationPort {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationService.class);
    private static final String FINGERPRINT_VERSION = "v1";

    private final InventoryService inventory;
    private final InventoryReservationRepository reservations;
    private final InventoryReservationProperties properties;
    private final InventoryReservationObservability observability;
    private final Clock clock;
    private final Tx tx;

    public InventoryReservationService(InventoryService inventory, InventoryReservationRepository reservations,
                                       InventoryReservationProperties properties,
                                       InventoryReservationObservability observability, Clock clock, Tx tx) {
        this.inventory = inventory;
        this.reservations = reservations;
        this.properties = properties;
        this.observability = observability;
        this.clock = clock;
        this.tx = tx;
    }

    // ---------- preparation (Inventory-owned identity/TTL; no Mongo access) ----------

    @Override
    public InventoryReservationCommand prepare(InventoryReservationRequest request) {
        if (request == null) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.INVALID_REQUEST,
                    "request required");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofSeconds(properties.getTtlSeconds()));
        return new InventoryReservationCommand(request.orderId(), request.fulfillmentLocationId(), request.items(),
                InventoryReservationId.generate(), now, expiresAt);
    }

    // ---------- session-aware port (participates in the CALLER's transaction) ----------

    @Override
    public InventoryReservation reserve(ClientSession session, InventoryReservationCommand prepared) {
        String fingerprint = fingerprintOf(prepared);

        Document existing = reservations.findByOrderId(session, prepared.orderId());
        if (existing != null) {
            return resolveExisting(existing, fingerprint);
        }

        List<InventoryReservationItem> sorted = prepared.items().stream()
                .sorted(java.util.Comparator.comparing(InventoryReservationItem::skuId)).toList();
        for (InventoryReservationItem item : sorted) {
            boolean applied = inventory.reserveOneSkuInSession(session, item.skuId(),
                    prepared.fulfillmentLocationId(), item.quantity(), prepared.preparedAt());
            if (!applied) {
                // MongoDB rolls back every EARLIER line's increment in this attempt, and the header
                // is never inserted — nothing partial survives.
                throw new InventoryReservationFailure(InventoryReservationFailure.Reason.RESERVATION_UNAVAILABLE,
                        "insufficient or inactive stock for " + item.skuId());
            }
        }

        InventoryReservation reservation = new InventoryReservation(prepared.reservationId().value(),
                prepared.orderId(), prepared.fulfillmentLocationId(), prepared.items(),
                InventoryReservationStatus.RESERVED, prepared.preparedAt(), prepared.expiresAt(),
                prepared.preparedAt());
        reservations.insert(session, reservation, fingerprint);
        return reservation;
    }

    private static InventoryReservation resolveExisting(Document existing, String fingerprint) {
        if (!fingerprint.equals(existing.getString("fingerprint"))) {
            throw new InventoryReservationFailure(
                    InventoryReservationFailure.Reason.ALREADY_RESERVED_DIFFERENT_INPUT,
                    "order already has a reservation with different input");
        }
        // Same fingerprint: the durable header IS the idempotency authority, whatever its current
        // status (RESERVED/RELEASED/CONSUMED) — never re-reserve, never increment again.
        return InventoryReservationRepository.toReservation(existing);
    }

    @Override
    public InventoryReservation release(ClientSession session, InventoryReservationId reservationId, Instant now) {
        return releaseInternal(session, reservationId, now).reservation();
    }

    @Override
    public InventoryReservation consume(ClientSession session, InventoryReservationId reservationId, Instant now) {
        return consumeInternal(session, reservationId, now).reservation();
    }

    private InventoryReservationLifecycleResult releaseInternal(ClientSession session,
                                                                 InventoryReservationId reservationId, Instant now) {
        Document doc = reservations.findById(session, reservationId.value());
        if (doc == null) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.NOT_FOUND,
                    "no reservation " + reservationId.value());
        }
        InventoryReservation current = InventoryReservationRepository.toReservation(doc);
        if (current.status() == InventoryReservationStatus.RELEASED) {
            return new InventoryReservationLifecycleResult(current, false); // idempotent no-op
        }
        if (current.status() == InventoryReservationStatus.CONSUMED) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.INVALID_TRANSITION,
                    "cannot release a CONSUMED reservation");
        }
        for (InventoryReservationItem item : current.items()) {
            boolean applied = inventory.releaseOneSkuInSession(session, item.skuId(),
                    current.fulfillmentLocationId(), item.quantity(), now);
            if (!applied) {
                // abort the WHOLE transaction — no partial release, ever.
                throw new InventoryReservationFailure(InventoryReservationFailure.Reason.INTEGRITY_FAILURE,
                        "reservation " + reservationId.value() + " inconsistent with inventory for "
                                + item.skuId());
            }
        }
        if (!reservations.transitionStatus(session, reservationId.value(), InventoryReservationStatus.RESERVED,
                InventoryReservationStatus.RELEASED, now)) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.INTEGRITY_FAILURE,
                    "reservation " + reservationId.value() + " status changed unexpectedly during release");
        }
        InventoryReservation released = new InventoryReservation(current.reservationId(), current.orderId(),
                current.fulfillmentLocationId(), current.items(), InventoryReservationStatus.RELEASED,
                current.createdAt(), current.expiresAt(), now);
        return new InventoryReservationLifecycleResult(released, true);
    }

    private InventoryReservationLifecycleResult consumeInternal(ClientSession session,
                                                                 InventoryReservationId reservationId, Instant now) {
        Document doc = reservations.findById(session, reservationId.value());
        if (doc == null) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.NOT_FOUND,
                    "no reservation " + reservationId.value());
        }
        InventoryReservation current = InventoryReservationRepository.toReservation(doc);
        if (current.status() == InventoryReservationStatus.CONSUMED) {
            return new InventoryReservationLifecycleResult(current, false); // idempotent no-op
        }
        if (current.status() == InventoryReservationStatus.RELEASED) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.INVALID_TRANSITION,
                    "cannot consume a RELEASED reservation");
        }
        for (InventoryReservationItem item : current.items()) {
            boolean applied = inventory.consumeOneSkuInSession(session, item.skuId(),
                    current.fulfillmentLocationId(), item.quantity(), now);
            if (!applied) {
                throw new InventoryReservationFailure(InventoryReservationFailure.Reason.INTEGRITY_FAILURE,
                        "reservation " + reservationId.value() + " inconsistent with inventory for "
                                + item.skuId());
            }
        }
        if (!reservations.transitionStatus(session, reservationId.value(), InventoryReservationStatus.RESERVED,
                InventoryReservationStatus.CONSUMED, now)) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.INTEGRITY_FAILURE,
                    "reservation " + reservationId.value() + " status changed unexpectedly during consume");
        }
        InventoryReservation consumed = new InventoryReservation(current.reservationId(), current.orderId(),
                current.fulfillmentLocationId(), current.items(), InventoryReservationStatus.CONSUMED,
                current.createdAt(), current.expiresAt(), now);
        return new InventoryReservationLifecycleResult(consumed, true);
    }

    // ---------- standalone wrappers (own their own Tx.call; record metrics after commit) ----------

    /** Convenience: {@code prepare} then reserve, in one call. */
    public InventoryReservation reserve(String orderId, String fulfillmentLocationId,
                                        List<InventoryReservationItem> items) {
        InventoryReservationCommand prepared = prepare(new InventoryReservationRequest(orderId,
                fulfillmentLocationId, items));
        try {
            // tx.call returns the LAST attempt's immutable value straight from the driver retry loop.
            InventoryReservation result = tx.call(session -> reserve(session, prepared));
            observability.success(InventoryReservationObservability.Operation.RESERVE);
            return result;
        } catch (InventoryReservationFailure e) {
            observability.failure(InventoryReservationObservability.Operation.RESERVE, e.reason());
            throw e;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                // lost a concurrent same-order create race: resolve to the winner's ONE durable header
                Document winner = reservations.findByOrderId(orderId);
                if (winner != null) {
                    try {
                        InventoryReservation resolved = resolveExisting(winner, fingerprintOf(prepared));
                        observability.success(InventoryReservationObservability.Operation.RESERVE);
                        return resolved;
                    } catch (InventoryReservationFailure e2) {
                        observability.failure(InventoryReservationObservability.Operation.RESERVE, e2.reason());
                        throw e2;
                    }
                }
            }
            observability.failure(InventoryReservationObservability.Operation.RESERVE,
                    InventoryReservationFailure.Reason.UNAVAILABLE);
            log.error("inventory_reservation_datastore_failed operation=reserve type={}",
                    e.getClass().getSimpleName());
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.UNAVAILABLE,
                    "datastore error during reserve");
        } catch (MongoException e) {
            observability.failure(InventoryReservationObservability.Operation.RESERVE,
                    InventoryReservationFailure.Reason.UNAVAILABLE);
            log.error("inventory_reservation_datastore_failed operation=reserve type={}",
                    e.getClass().getSimpleName());
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.UNAVAILABLE,
                    "datastore unavailable during reserve");
        }
    }

    public InventoryReservation release(InventoryReservationId reservationId) {
        return releaseWithOutcome(reservationId).reservation();
    }

    public InventoryReservation consume(InventoryReservationId reservationId) {
        return consumeWithOutcome(reservationId).reservation();
    }

    /** Package-private: exposes whether THIS call caused the transition, for
     *  {@link InventoryReservationExpiryWorker}'s own expiry-specific accounting. */
    InventoryReservationLifecycleResult releaseWithOutcome(InventoryReservationId reservationId) {
        Instant now = clock.instant();
        try {
            InventoryReservationLifecycleResult result = tx.call(session -> releaseInternal(session, reservationId,
                    now));
            if (result.transitioned()) {
                observability.transition(InventoryReservationStatus.RESERVED, InventoryReservationStatus.RELEASED);
            }
            observability.success(InventoryReservationObservability.Operation.RELEASE);
            return result;
        } catch (InventoryReservationFailure e) {
            observability.failure(InventoryReservationObservability.Operation.RELEASE, e.reason());
            throw e;
        } catch (MongoException e) {
            observability.failure(InventoryReservationObservability.Operation.RELEASE,
                    InventoryReservationFailure.Reason.UNAVAILABLE);
            log.error("inventory_reservation_datastore_failed operation=release type={}",
                    e.getClass().getSimpleName());
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.UNAVAILABLE,
                    "datastore unavailable during release");
        }
    }

    InventoryReservationLifecycleResult consumeWithOutcome(InventoryReservationId reservationId) {
        Instant now = clock.instant();
        try {
            InventoryReservationLifecycleResult result = tx.call(session -> consumeInternal(session, reservationId,
                    now));
            if (result.transitioned()) {
                observability.transition(InventoryReservationStatus.RESERVED, InventoryReservationStatus.CONSUMED);
            }
            observability.success(InventoryReservationObservability.Operation.CONSUME);
            return result;
        } catch (InventoryReservationFailure e) {
            observability.failure(InventoryReservationObservability.Operation.CONSUME, e.reason());
            throw e;
        } catch (MongoException e) {
            observability.failure(InventoryReservationObservability.Operation.CONSUME,
                    InventoryReservationFailure.Reason.UNAVAILABLE);
            log.error("inventory_reservation_datastore_failed operation=consume type={}",
                    e.getClass().getSimpleName());
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.UNAVAILABLE,
                    "datastore unavailable during consume");
        }
    }

    public Optional<InventoryReservation> findByOrderId(String orderId) {
        Document d;
        try {
            d = reservations.findByOrderId(orderId);
        } catch (MongoException e) {
            observability.failure(InventoryReservationObservability.Operation.READ,
                    InventoryReservationFailure.Reason.UNAVAILABLE);
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.UNAVAILABLE,
                    "datastore unavailable during read");
        }
        return d == null ? Optional.empty() : Optional.of(InventoryReservationRepository.toReservation(d));
    }

    public Optional<InventoryReservation> findById(InventoryReservationId reservationId) {
        Document d;
        try {
            d = reservations.findById(reservationId.value());
        } catch (MongoException e) {
            observability.failure(InventoryReservationObservability.Operation.READ,
                    InventoryReservationFailure.Reason.UNAVAILABLE);
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.UNAVAILABLE,
                    "datastore unavailable during read");
        }
        return d == null ? Optional.empty() : Optional.of(InventoryReservationRepository.toReservation(d));
    }

    // ---------- fingerprint ----------

    /**
     * PR-14A — {@code v1|orderId|fulfillmentLocationId|sku:qty|sku:qty...}, items sorted by
     * skuId so the caller's original ordering never changes identity. Bumping
     * {@code FINGERPRINT_VERSION} is REQUIRED before adding any new meaning-bearing field to the
     * fingerprint input — an unbumped version could let an old and a new semantic meaning collide.
     */
    String fingerprintOf(InventoryReservationCommand command) {
        return sha256Hex(command.canonicalFingerprintInput(FINGERPRINT_VERSION));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
