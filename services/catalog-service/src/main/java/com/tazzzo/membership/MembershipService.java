package com.tazzzo.membership;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.tx.Tx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;

/**
 * PR-16A-1 — the Membership WRITE foundation: an internal, idempotent, payment-free {@link #grant}.
 *
 * <p><b>Trust boundary.</b> This is an INTERNAL domain API for a trusted orchestrator that has already
 * established a real customer. Membership checks {@link CustomerId} SHAPE only and never queries
 * customer existence. There is no controller and no customer-facing caller; the term carries no
 * payment facts (its plan price is the list price at grant, not money collected).
 *
 * <p><b>Grant.</b> (A) validate; (B) a durable grant-reference replay check — a replay wins over
 * everything after it, including a plan retired since the original grant, an expired term, or a
 * customer who now holds another term; (C) resolve the immutable plan; (D) ONE transaction that
 * repeats the reference check in-session, checks plan effectiveness at the attempt's fresh clock
 * instant, inspects the customer's open term ({@code now < validUntil} => {@code ALREADY_ACTIVE}; a
 * time-ended one is CAS-moved ACTIVE -> EXPIRED) and inserts the new ACTIVE term — the stale
 * expiry and the replacement commit together or not at all.
 *
 * <p><b>Retry safety.</b> {@code Tx.call} may re-run the callback: the {@link MembershipId} is minted
 * once OUTSIDE it, the reference is stable, every decision is rebuilt from fresh reads and a fresh
 * clock read per attempt, the callback returns an immutable value (no captured holder), and nothing in
 * it records a metric — metrics are recorded once, after the whole operation resolves.
 *
 * <p><b>Duplicate-key recovery.</b> Two unique invariants exist (one reference row; one open term per
 * customer). A duplicate key (code 11000 — never the message or index name) that escapes the aborted
 * transaction is resolved from durable primary reads: a reference row => replay or
 * {@code GRANT_REF_CONFLICT}; else an open term still holding the slot ({@code now < validUntil}) =>
 * {@code ALREADY_ACTIVE}; else (a stale open row, or nothing — e.g. the winner already moved on) ONE
 * bounded whole-grant retry; a second duplicate key that still proves nothing is
 * {@code INTEGRITY_FAILURE}. No loop is unbounded.
 */
@Component
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

    private static final int DUPLICATE_KEY = 11000;

    private final MembershipRepository repository;
    private final MembershipPlanSource plans;
    private final MembershipObservability observability;
    private final Clock clock;
    private final Tx tx;

    public MembershipService(MembershipRepository repository, MembershipPlanSource plans,
                             MembershipObservability observability, Clock clock, Tx tx) {
        this.repository = repository;
        this.plans = plans;
        this.observability = observability;
        this.clock = clock;
        this.tx = tx;
    }

    /** What one resolved grant returned, so metrics can be recorded AFTER commit. */
    private record GrantOutcome(Membership membership, boolean expiredStaleTerm) {
        static GrantOutcome replayed(Membership m) {
            return new GrantOutcome(m, false);
        }
    }

    public Membership grant(CustomerId customerId, String planId, int planVersion, String internalReference) {
        GrantOutcome outcome;
        try {
            outcome = resolveGrant(customerId, planId, planVersion, internalReference);
        } catch (MembershipFailure e) {
            observability.failure(MembershipObservability.Operation.GRANT, e.reason());
            throw e;
        }
        observability.success(MembershipObservability.Operation.GRANT);
        if (outcome.expiredStaleTerm()) { // the transaction containing it has committed
            observability.transition(MembershipStatus.ACTIVE, MembershipStatus.EXPIRED);
        }
        return outcome.membership();
    }

    private GrantOutcome resolveGrant(CustomerId customerId, String planId, int planVersion,
                                      String internalReference) {
        // A — input shape (CustomerId's own constructor already proved the customer-id shape)
        if (customerId == null) {
            throw failure(MembershipFailure.Reason.INVALID_REQUEST, "customerId required");
        }
        if (planId == null || !MembershipPlan.PLAN_ID.matcher(planId).matches() || planVersion < 1) {
            throw failure(MembershipFailure.Reason.INVALID_REQUEST, "invalid plan id or version");
        }
        if (!MembershipGrantReference.isValidReference(internalReference)) {
            throw failure(MembershipFailure.Reason.INVALID_REQUEST, "invalid grant reference");
        }
        MembershipGrantReference reference =
                new MembershipGrantReference(GrantSource.INTERNAL_GRANT, internalReference);

        // B — durable replay wins before plan effectiveness
        Optional<Membership> existing = readOrUnavailable(() -> repository.findByGrantReference(reference));
        if (existing.isPresent()) {
            return GrantOutcome.replayed(resolveReplay(existing.get(), customerId, planId, planVersion));
        }

        // C — the immutable plan must exist (effectiveness is judged per attempt inside the transaction)
        MembershipPlan plan = plans.find(planId, planVersion).orElseThrow(
                () -> failure(MembershipFailure.Reason.INVALID_REQUEST, "unknown membership plan or version"));

        // identity minted ONCE, outside every transaction attempt
        MembershipId membershipId = MembershipId.generate();

        // D — the transaction, with at most ONE recovery retry after a duplicate key
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.call(session -> grantInSession(session, membershipId, customerId, reference, plan));
            } catch (MembershipFailure e) {
                throw e;
            } catch (MongoWriteException e) {
                if (e.getError().getCode() != DUPLICATE_KEY) {
                    throw unavailable(e);
                }
                Optional<Membership> replay = recoverFromDuplicateKey(reference, customerId, planId, planVersion);
                if (replay.isPresent()) {
                    return GrantOutcome.replayed(replay.get());
                }
                if (attempt >= 2) {
                    throw failure(MembershipFailure.Reason.INTEGRITY_FAILURE,
                            "duplicate key without durable proof after the bounded retry");
                }
            } catch (MongoException e) {
                throw unavailable(e);
            }
        }
    }

    /** One attempt of the grant transaction. Idempotent per attempt; records NO metric. */
    private GrantOutcome grantInSession(ClientSession session, MembershipId membershipId, CustomerId customerId,
                                        MembershipGrantReference reference, MembershipPlan plan) {
        Instant now = MembershipBillingCalendar.truncate(clock.instant()); // fresh on EVERY attempt

        Optional<Membership> byReference = repository.findByGrantReference(session, reference);
        if (byReference.isPresent()) {
            return GrantOutcome.replayed(
                    resolveReplay(byReference.get(), customerId, plan.planId(), plan.version()));
        }
        if (!plan.isEffectiveAt(now)) {
            throw failure(MembershipFailure.Reason.PLAN_NOT_ACTIVE, "membership plan is not effective");
        }

        boolean expiredStaleTerm = false;
        Optional<Membership> open = repository.findOpenByCustomer(session, customerId);
        if (open.isPresent()) {
            Membership slotHolder = open.get();
            if (!slotHolder.windowEndedAt(now)) {
                throw failure(MembershipFailure.Reason.ALREADY_ACTIVE, "customer already holds an open membership");
            }
            // An authoritative same-session read just proved this row ACTIVE at this version and
            // time-ended; a CAS miss here is not explained by any durable state.
            if (!repository.expireIfDue(session, slotHolder.membershipId(), slotHolder.version(), now)) {
                throw failure(MembershipFailure.Reason.INTEGRITY_FAILURE, "stale membership expiry CAS missed");
            }
            expiredStaleTerm = true;
        }

        Membership granted;
        try {
            granted = Membership.newGrant(membershipId, customerId, reference, plan, now);
        } catch (ArithmeticException | DateTimeException | IllegalArgumentException e) {
            log.error("membership_window_computation_failed type={}", e.getClass().getSimpleName());
            throw failure(MembershipFailure.Reason.INTEGRITY_FAILURE, "membership window could not be computed");
        }
        repository.insert(session, granted);
        return new GrantOutcome(granted, expiredStaleTerm);
    }

    /**
     * Resolves a duplicate key purely from durable primary reads. Present => the replayed term.
     * Empty => no proof either way; the caller performs its single bounded retry.
     */
    private Optional<Membership> recoverFromDuplicateKey(MembershipGrantReference reference, CustomerId customerId,
                                                         String planId, int planVersion) {
        try {
            Optional<Membership> byReference = repository.findByGrantReference(reference);
            if (byReference.isPresent()) {
                return Optional.of(resolveReplay(byReference.get(), customerId, planId, planVersion));
            }
            Optional<Membership> open = repository.findOpenByCustomer(customerId);
            if (open.isPresent() && !open.get().windowEndedAt(MembershipBillingCalendar.truncate(clock.instant()))) {
                throw failure(MembershipFailure.Reason.ALREADY_ACTIVE, "customer already holds an open membership");
            }
            return Optional.empty();
        } catch (MongoException e) {
            throw unavailable(e);
        }
    }

    private static Membership resolveReplay(Membership found, CustomerId customerId, String planId, int planVersion) {
        if (!found.matchesGrantInput(customerId, planId, planVersion)) {
            throw failure(MembershipFailure.Reason.GRANT_REF_CONFLICT,
                    "grant reference already used with different input");
        }
        return found;
    }

    private Optional<Membership> readOrUnavailable(java.util.function.Supplier<Optional<Membership>> read) {
        try {
            return read.get();
        } catch (MongoException e) {
            throw unavailable(e);
        }
    }

    private static MembershipFailure failure(MembershipFailure.Reason reason, String message) {
        return new MembershipFailure(reason, message);
    }

    private static MembershipFailure unavailable(MongoException e) {
        log.error("membership_datastore_failed operation=grant type={}", e.getClass().getSimpleName());
        return new MembershipFailure(MembershipFailure.Reason.UNAVAILABLE, "datastore unavailable during grant");
    }
}
