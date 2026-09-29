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
 * {@code consume(InventoryReservationId)} are STANDALONE wrappers: {@link #prepare} fixes
 * {@code reservationId}/{@code preparedAt}/{@code expiresAt} BEFORE {@link Tx#call} (for
 * {@code reserve}), then delegates to the session-aware method inside it — the same "fix identity
 * before the transaction, return the committed attempt's value" discipline
 * {@code CheckoutService}/{@code OtpService} already established. {@code release}/{@code consume}
 * take NO precomputed {@code now} at all: their session-aware implementations
 * ({@code releaseInternal}/{@code consumeInternal}) read {@code clock.instant()} THEMSELVES, fresh
 * on every invocation of the callback — including every {@code Tx.call} driver-retry re-invocation
 * — so a transaction retry always judges validity against the CURRENT authoritative time, never a
 * value fixed before the retry began (see the clock-authority hardening below).
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
 *
 * <p><b>PR-14A hardening (H1) — expiry is RUNTIME-AUTHORITATIVE.</b> The expiry-reconciliation
 * worker is cleanup, never the authority for whether a {@code RESERVED} allocation is still valid:
 * {@code reserve} (a fresh, already-stale prepared command, or an idempotent replay of a durable
 * header whose OWN {@code expiresAt} has passed) and {@code consume} (an expired hold must never be
 * silently honoured) both check {@code expiresAt} against Inventory's LIVE {@link Clock} — never
 * against {@code preparedAt}, and never merely "has the worker gotten to it yet" — and throw
 * {@link InventoryReservationFailure.Reason#RESERVATION_EXPIRED} before touching any inventory row
 * or reservation status. {@code release} is deliberately NOT expiry-gated: releasing an expired
 * reservation (whether triggered explicitly or by the worker) is exactly the recovery path.
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
    public PreparedInventoryReservation prepare(InventoryReservationRequest request) {
        if (request == null) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.INVALID_REQUEST,
                    "request required");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofSeconds(properties.getTtlSeconds()));
        return new PreparedInventoryReservation(request.orderId(), request.fulfillmentLocationId(), request.items(),
                InventoryReservationId.generate(), now, expiresAt);
    }

    // ---------- session-aware port (participates in the CALLER's transaction) ----------

    /**
     * PR-14A hardening (H1) — expiry is RUNTIME-AUTHORITATIVE, never merely "whatever the expiry
     * worker hasn't released yet". Order of checks matters: the EXISTING-reservation lookup runs
     * first (so a driver retry that finds its own already-committed header — the "ambiguous commit"
     * case — always resolves to that durable header, regardless of whether the freshly-generated
     * {@code prepared.expiresAt()} has since lapsed); only when creating a genuinely NEW reservation
     * is {@code prepared}'s own freshness checked, against Inventory's LIVE {@link Clock} — never
     * {@code prepared.preparedAt()}, which is historical provenance, not validity. This check reruns
     * on every {@code Tx.call} retry attempt by construction (it reads {@code clock.instant()} fresh
     * each time), which is correct: a retry that crosses expiry must fail and roll back, never commit
     * an already-stale allocation.
     */
    @Override
    public InventoryReservation reserve(ClientSession session, PreparedInventoryReservation prepared) {
        String fingerprint = fingerprintOf(prepared);
        Instant authoritativeNow = clock.instant();

        Document existing = reservations.findByOrderId(session, prepared.orderId());
        if (existing != null) {
            return resolveExisting(existing, fingerprint, authoritativeNow);
        }

        if (!prepared.expiresAt().isAfter(authoritativeNow)) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.RESERVATION_EXPIRED,
                    "prepared reservation for order " + prepared.orderId() + " expired before it could be reserved");
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

    /**
     * PR-14A hardening (H1B) — a fingerprint match is necessary but not sufficient: a
     * {@code RESERVED} durable header whose OWN stored {@code expiresAt} has passed is NOT a valid
     * active allocation, regardless of how fresh the CALLER's newly re-{@code prepare}d command is.
     * It is neither re-reserved, nor replaced, nor silently extended — one-order-one-reservation
     * stays intact; the caller must wait for the expiry worker (or an explicit release) before a NEW
     * reservation can ever exist for this order. {@code RELEASED}/{@code CONSUMED} are unaffected —
     * both are returned as-is (they answer "what happened", which is exactly what an idempotent
     * replay of a terminal reservation means; neither is a valid ACTIVE allocation either, but that
     * distinction is already implicit in their status).
     */
    private static InventoryReservation resolveExisting(Document existing, String fingerprint, Instant now) {
        if (!fingerprint.equals(existing.getString("fingerprint"))) {
            throw new InventoryReservationFailure(
                    InventoryReservationFailure.Reason.ALREADY_RESERVED_DIFFERENT_INPUT,
                    "order already has a reservation with different input");
        }
        InventoryReservation current = InventoryReservationRepository.toReservation(existing);
        if (current.status() == InventoryReservationStatus.RESERVED && !current.expiresAt().isAfter(now)) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.RESERVATION_EXPIRED,
                    "reservation " + current.reservationId() + " for order " + current.orderId()
                            + " has expired and was never consumed");
        }
        // Same fingerprint, still active (or already terminal): the durable header IS the
        // idempotency authority — never re-reserve, never increment again.
        return current;
    }

    @Override
    public InventoryReservation release(ClientSession session, InventoryReservationId reservationId) {
        return releaseInternal(session, reservationId).reservation();
    }

    @Override
    public InventoryReservation consume(ClientSession session, InventoryReservationId reservationId) {
        return consumeInternal(session, reservationId).reservation();
    }

    /**
     * PR-14A hardening (clock authority) — {@code now} is read from Inventory's OWN injected
     * {@link Clock} HERE, never accepted as a parameter: a caller-supplied {@code Instant} could be
     * stale relative to Inventory's real clock, which for {@code consume} specifically would let an
     * already-expired reservation be silently honoured. Read fresh on every invocation (including a
     * {@code Tx.call} driver retry) — if a retry crosses {@code expiresAt} mid-flight, the LATER
     * attempt must see the LATER time.
     */
    private InventoryReservationLifecycleResult releaseInternal(ClientSession session,
                                                                 InventoryReservationId reservationId) {
        Instant now = clock.instant();
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

    /** Same clock-authority discipline as {@link #releaseInternal}: {@code now} is read fresh from
     *  Inventory's OWN {@link Clock} here, never a caller-supplied value. */
    private InventoryReservationLifecycleResult consumeInternal(ClientSession session,
                                                                 InventoryReservationId reservationId) {
        Instant now = clock.instant();
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
        // PR-14A hardening (H1C) — a RESERVED hold whose expiresAt has already passed must NEVER be
        // silently honoured just because the expiry worker hasn't gotten to it yet: no on_hand/
        // reserved decrement, no status transition. It stays RESERVED (still holding stock) until an
        // explicit release() or the expiry worker's own release() cleans it up — release, unlike
        // consume, is deliberately NOT expiry-gated, since releasing an expired hold IS the recovery
        // path.
        if (!current.expiresAt().isAfter(now)) {
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.RESERVATION_EXPIRED,
                    "reservation " + reservationId.value() + " expired and can no longer be consumed");
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
        PreparedInventoryReservation prepared = prepare(new InventoryReservationRequest(orderId,
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
                // lost a concurrent same-order create race: resolve to the winner's ONE durable
                // header. The winner-lookup itself is wrapped in ITS OWN try/catch (see
                // resolveDuplicateWinner) — a MongoException thrown here, inside this
                // MongoWriteException handler, would NOT be caught by the sibling `catch
                // (MongoException e)` below (Java does not fall through to a sibling catch), so
                // without that inner wrapping a raw Mongo type could still escape this method. A
                // business failure discovered on the winner's own header (e.g. a fingerprint
                // mismatch, or the winner itself already expired) is caught HERE and recorded —
                // it is thrown from inside this catch block, so it would otherwise propagate
                // straight past the sibling `catch (InventoryReservationFailure e)` above without
                // ever being counted.
                try {
                    InventoryReservation resolved = resolveDuplicateWinner(orderId, prepared);
                    if (resolved != null) {
                        observability.success(InventoryReservationObservability.Operation.RESERVE);
                        return resolved;
                    }
                } catch (InventoryReservationFailure e2) {
                    observability.failure(InventoryReservationObservability.Operation.RESERVE, e2.reason());
                    throw e2;
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

    /**
     * PR-14A hardening (M1) — the winner re-read after a lost duplicate-key race, isolated in its
     * own try/catch so a datastore failure DURING this recovery read is mapped to the same typed
     * {@code UNAVAILABLE} contract as everything else, never allowed to escape as a raw
     * {@link MongoException}. A business {@link InventoryReservationFailure} from
     * {@code resolveExisting} (e.g. {@code ALREADY_RESERVED_DIFFERENT_INPUT} or
     * {@code RESERVATION_EXPIRED}, discovered on the winner's own header) is a genuine domain
     * outcome and is deliberately NOT caught here — it propagates and is recorded by the caller's
     * existing {@code catch (InventoryReservationFailure e)}, never double-counted.
     *
     * @return the resolved reservation, or {@code null} if no winner header exists (re-thrown by the
     *         caller as {@code UNAVAILABLE} — a duplicate-key error with no winning row is itself a
     *         data-integrity anomaly, not a business outcome).
     */
    private InventoryReservation resolveDuplicateWinner(String orderId, PreparedInventoryReservation prepared) {
        Document winner;
        try {
            winner = reservations.findByOrderId(orderId);
        } catch (MongoException e) {
            // Metric recording is deliberately left to the ONE call site's catch (InventoryReservationFailure)
            // below — recording here too would double-count this exact failure.
            log.error("inventory_reservation_datastore_failed operation=reserve_winner_read type={}",
                    e.getClass().getSimpleName());
            throw new InventoryReservationFailure(InventoryReservationFailure.Reason.UNAVAILABLE,
                    "datastore error reading the duplicate-key winner");
        }
        return winner == null ? null : resolveExisting(winner, fingerprintOf(prepared), clock.instant());
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
        try {
            // No "now" fixed here: releaseInternal reads clock.instant() itself, fresh on every
            // Tx.call attempt (release is not expiry-gated, so this is purely about updatedAt
            // ownership, not a correctness-critical check the way consume's is).
            InventoryReservationLifecycleResult result = tx.call(session -> releaseInternal(session, reservationId));
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
        try {
            // No "now" fixed here EITHER: consumeInternal reads clock.instant() itself, fresh on
            // every Tx.call attempt — this is the correctness-critical case. If a driver retry
            // crosses expiresAt mid-flight, the later attempt must see the later time and correctly
            // reject RESERVATION_EXPIRED rather than committing a stale attempt's earlier "now".
            InventoryReservationLifecycleResult result = tx.call(session -> consumeInternal(session, reservationId));
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
    String fingerprintOf(PreparedInventoryReservation prepared) {
        return sha256Hex(prepared.canonicalFingerprintInput(FINGERPRINT_VERSION));
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
