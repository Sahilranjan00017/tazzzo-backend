package com.tazzzo.inventory;

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

/**
 * PR-14A — the ONE production reservation lifecycle: {@code reserve}/{@code release}/
 * {@code consume}, retry-safe, all-or-nothing across every SKU in one order, idempotent by
 * {@code orderId}. Supersedes {@link InventoryService#tryReserve} for anything order-facing (see
 * that method's own javadoc).
 *
 * <p><b>Two call shapes, one algorithm:</b> the {@link InventoryReservationPort} methods
 * (session-aware) do the actual work and participate in WHATEVER transaction their caller owns —
 * they never start one themselves. This class's own {@code reserve(InventoryReservationCommand)}/
 * {@code release(InventoryReservationId)}/{@code consume(InventoryReservationId)} are STANDALONE
 * wrappers: they generate the retry-stable values ({@code reservationId}, {@code expiresAt}, a
 * fixed {@code now}) BEFORE calling {@link Tx#call}, then delegate to the session-aware method
 * inside it — the exact same "fix everything before the transaction, return the committed
 * attempt's value" discipline {@code CheckoutService}/{@code OtpService} already established.
 *
 * <p><b>Multi-SKU all-or-nothing (the load-bearing guarantee):</b> {@code reserve} performs one
 * atomic conditional update per SKU ({@link InventoryService#reserveOneSkuInSession}), in a
 * deterministic sorted-by-skuId order (MongoDB multi-document transaction deadlock-avoidance
 * discipline), inside ONE Mongo transaction. If any line's update matches zero documents, this
 * class throws — MongoDB itself rolls back every earlier line's increment in this SAME attempt,
 * together with the reservation header (which is inserted LAST, only after every line succeeded).
 * There is no manual "undo" anywhere in this class; the transaction engine provides it.
 */
@Service
public class InventoryReservationService implements InventoryReservationPort {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationService.class);
    private static final String FINGERPRINT_VERSION = "v1";

    private final InventoryService inventory;
    private final InventoryReservationRepository reservations;
    private final InventoryReservationProperties properties;
    private final Clock clock;
    private final Tx tx;

    public InventoryReservationService(InventoryService inventory, InventoryReservationRepository reservations,
                                       InventoryReservationProperties properties, Clock clock, Tx tx) {
        this.inventory = inventory;
        this.reservations = reservations;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
    }

    // ---------- session-aware port (participates in the CALLER's transaction) ----------

    @Override
    public InventoryReservation reserve(ClientSession session, InventoryReservationCommand command, Instant now) {
        String fingerprint = fingerprintOf(command);

        Document existing = reservations.findByOrderId(session, command.orderId());
        if (existing != null) {
            return resolveExisting(existing, fingerprint);
        }

        List<InventoryReservationItem> sorted = command.items().stream()
                .sorted(java.util.Comparator.comparing(InventoryReservationItem::skuId)).toList();
        for (InventoryReservationItem item : sorted) {
            boolean applied = inventory.reserveOneSkuInSession(session, item.skuId(),
                    command.fulfillmentLocationId(), item.quantity(), now);
            if (!applied) {
                // MongoDB rolls back every EARLIER line's increment in this attempt, and the header
                // is never inserted — nothing partial survives.
                throw new InventoryReservationFailure(InventoryReservationFailure.Reason.RESERVATION_UNAVAILABLE,
                        "insufficient or inactive stock for " + item.skuId());
            }
        }

        InventoryReservation reservation = new InventoryReservation(command.reservationId().value(),
                command.orderId(), command.fulfillmentLocationId(), command.items(),
                InventoryReservationStatus.RESERVED, now, command.expiresAt(), now);
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
        Document doc = reservations.findById(session, reservationId.value());
        if (doc == null) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.NOT_FOUND,
                    "no reservation " + reservationId.value());
        }
        InventoryReservation current = InventoryReservationRepository.toReservation(doc);
        if (current.status() == InventoryReservationStatus.RELEASED) {
            return current; // idempotent no-op
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
        return new InventoryReservation(current.reservationId(), current.orderId(),
                current.fulfillmentLocationId(), current.items(), InventoryReservationStatus.RELEASED,
                current.createdAt(), current.expiresAt(), now);
    }

    @Override
    public InventoryReservation consume(ClientSession session, InventoryReservationId reservationId, Instant now) {
        Document doc = reservations.findById(session, reservationId.value());
        if (doc == null) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.NOT_FOUND,
                    "no reservation " + reservationId.value());
        }
        InventoryReservation current = InventoryReservationRepository.toReservation(doc);
        if (current.status() == InventoryReservationStatus.CONSUMED) {
            return current; // idempotent no-op
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
        return new InventoryReservation(current.reservationId(), current.orderId(),
                current.fulfillmentLocationId(), current.items(), InventoryReservationStatus.CONSUMED,
                current.createdAt(), current.expiresAt(), now);
    }

    // ---------- standalone wrappers (own their own Tx.call; safe to call with no outer session) ----------

    /**
     * Reserves stock for one order. {@code reservationId}/{@code expiresAt}/{@code now} are all
     * fixed HERE, before {@link Tx#call}, so a driver retry of this SAME logical call is stable.
     */
    public InventoryReservation reserve(String orderId, String fulfillmentLocationId,
                                        List<InventoryReservationItem> items) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofSeconds(properties.getTtlSeconds()));
        InventoryReservationCommand command = new InventoryReservationCommand(orderId, fulfillmentLocationId, items,
                InventoryReservationId.generate(), expiresAt);
        try {
            // tx.call returns the LAST attempt's immutable value straight from the driver retry loop.
            return tx.call(session -> reserve(session, command, now));
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                // lost a concurrent same-order create race: resolve to the winner's ONE durable header
                Document winner = reservations.findByOrderId(orderId);
                if (winner != null) {
                    return resolveExisting(winner, fingerprintOf(command));
                }
            }
            throw e;
        }
    }

    public InventoryReservation release(InventoryReservationId reservationId) {
        Instant now = clock.instant();
        return tx.call(session -> release(session, reservationId, now));
    }

    public InventoryReservation consume(InventoryReservationId reservationId) {
        Instant now = clock.instant();
        return tx.call(session -> consume(session, reservationId, now));
    }

    public java.util.Optional<InventoryReservation> findByOrderId(String orderId) {
        Document d = reservations.findByOrderId(orderId);
        return d == null ? java.util.Optional.empty() : java.util.Optional.of(InventoryReservationRepository
                .toReservation(d));
    }

    public java.util.Optional<InventoryReservation> findById(InventoryReservationId reservationId) {
        Document d = reservations.findById(reservationId.value());
        return d == null ? java.util.Optional.empty() : java.util.Optional.of(InventoryReservationRepository
                .toReservation(d));
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
