package com.tazzzo.membership;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.tx.Tx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * PR-16A-3 — Membership TERMINATION: exactly two internal, standalone lifecycle commands.
 *
 * <ul>
 *   <li>{@link #cancelAtPeriodEnd(CustomerId)} — customer-intent cancellation of the customer's single current/open
 *       term. Records {@code cancelRequestedAt}; it does NOT shorten {@code validUntil}, change the status or touch
 *       {@code openTerm}: entitlement continues until the window ends. Idempotent: the original timestamp is never
 *       rewritten and a repeat performs no mutation. There is no un-cancel.</li>
 *   <li>{@link #revoke(MembershipId)} — immediate administrative termination of an ACTIVE term: status
 *       {@code REVOKED}, {@code revokedAt}, {@code openTerm} REMOVED. Entitlement ends at once. A prior
 *       {@code cancelRequestedAt} is preserved as history. Idempotent on an already REVOKED term.</li>
 * </ul>
 *
 * <p><b>Internal domain API, like {@code grant}.</b> No controller, no HTTP, no actor/reason/audit (none is
 * designed), no refund and no Payment coupling; nothing outside this package may depend on it (ArchUnit). Time is the
 * injected {@code Clock}, read inside each attempt and truncated to milliseconds — callers never supply a timestamp.
 *
 * <p><b>Transaction and CAS.</b> Each command owns exactly one {@code Tx.call}; the callback may re-run, so every
 * decision is rebuilt from fresh strict reads and an authoritative same-session read precedes a CAS guarded on
 * {@code _id}, status, expected version, the {@code openTerm} marker, the live window (and, for cancel, the absence
 * of a previous request). A concurrent writer surfaces as a transient write conflict that the driver resolves by
 * re-running the whole callback, which then re-reads and lands on the stable domain result (idempotent success,
 * {@code NOT_FOUND} or {@code INVALID_TRANSITION}); no version-conflict failure exists. A CAS miss after an
 * authoritative same-session read is therefore unexplainable by durable state: {@code INTEGRITY_FAILURE}.
 *
 * <p><b>Entitlement.</b> The runtime predicate stays {@link Membership#isEntitlingAt}: a term is terminable only
 * while it is entitling. A time-ended or not-yet-started ACTIVE term is {@code INVALID_TRANSITION} — never rewritten
 * as REVOKED, never silently expired here (lazy expiry stays the grant path's). A customer with no current/open
 * term — including one whose latest term was just revoked (REVOKED rows carry no open marker, by design) — is
 * {@code NOT_FOUND}.
 *
 * <p><b>Observability.</b> Success, failure and the {@code ACTIVE -> REVOKED} transition are recorded once, AFTER
 * {@code Tx.call} returns (never inside the callback, never per attempt); tags are closed enums only.
 */
@Component
public class MembershipTerminationService {

    private static final Logger log = LoggerFactory.getLogger(MembershipTerminationService.class);

    private final MembershipRepository repository;
    private final MembershipObservability observability;
    private final Clock clock;
    private final Tx tx;

    public MembershipTerminationService(MembershipRepository repository, MembershipObservability observability,
                                        Clock clock, Tx tx) {
        this.repository = repository;
        this.observability = observability;
        this.clock = clock;
        this.tx = tx;
    }

    /** What one command resolved to, so metrics can be recorded AFTER commit. */
    private record Outcome(Membership membership, boolean mutated) {
    }

    /**
     * Cancel-at-period-end for the customer's current/open term.
     *
     * @return the current term (the cancelled one; unchanged if a request was already recorded)
     */
    public Membership cancelAtPeriodEnd(CustomerId customerId) {
        Outcome outcome;
        try {
            if (customerId == null) {
                throw failure(MembershipFailure.Reason.INVALID_REQUEST, "customerId required");
            }
            outcome = run(MembershipObservability.Operation.CANCEL_AT_PERIOD_END,
                    session -> cancelInSession(session, customerId));
        } catch (MembershipFailure e) {
            observability.failure(MembershipObservability.Operation.CANCEL_AT_PERIOD_END, e.reason());
            throw e;
        }
        observability.success(MembershipObservability.Operation.CANCEL_AT_PERIOD_END);
        return outcome.membership();
    }

    /**
     * Immediate revoke of the term {@code membershipId}.
     *
     * @return the REVOKED term (unchanged, with its first {@code revokedAt}, if it was already revoked)
     */
    public Membership revoke(MembershipId membershipId) {
        Outcome outcome;
        try {
            if (membershipId == null) {
                throw failure(MembershipFailure.Reason.INVALID_REQUEST, "membershipId required");
            }
            outcome = run(MembershipObservability.Operation.REVOKE, session -> revokeInSession(session, membershipId));
        } catch (MembershipFailure e) {
            observability.failure(MembershipObservability.Operation.REVOKE, e.reason());
            throw e;
        }
        observability.success(MembershipObservability.Operation.REVOKE);
        if (outcome.mutated()) { // the transaction containing the transition has committed
            observability.transition(MembershipStatus.ACTIVE, MembershipStatus.REVOKED);
        }
        return outcome.membership();
    }

    private Outcome run(MembershipObservability.Operation operation,
                        java.util.function.Function<ClientSession, Outcome> body) {
        try {
            return tx.call(body);
        } catch (MembershipFailure e) {
            throw e;
        } catch (MongoException e) {
            log.error("membership_datastore_failed operation={} type={}", operation.name().toLowerCase(java.util.Locale.ROOT),
                    e.getClass().getSimpleName());
            throw failure(MembershipFailure.Reason.UNAVAILABLE, "datastore unavailable during termination");
        }
    }

    /** One attempt of the cancel transaction: records NO metric, mutates at most once. */
    private Outcome cancelInSession(ClientSession session, CustomerId customerId) {
        Instant now = MembershipBillingCalendar.truncate(clock.instant()); // fresh on EVERY attempt
        // the established current/open candidate read: a corrupt row that is or claims the open slot fails loud here
        Optional<Membership> current = repository.findCurrentCandidateByCustomer(session, customerId);
        if (current.isEmpty()) {
            throw failure(MembershipFailure.Reason.NOT_FOUND, "customer has no current membership");
        }
        Membership term = current.get();
        if (term.cancelRequestedAt() != null) {
            return new Outcome(term, false); // idempotent: the original timestamp stays, no mutation, no version bump
        }
        if (!term.isEntitlingAt(now)) {
            throw failure(MembershipFailure.Reason.INVALID_TRANSITION, "membership is not within its window");
        }
        if (!repository.markCancelRequested(session, term.membershipId(), term.version(), now)) {
            throw failure(MembershipFailure.Reason.INTEGRITY_FAILURE, "membership cancel CAS missed");
        }
        return new Outcome(reread(session, term.membershipId()), true);
    }

    /** One attempt of the revoke transaction: records NO metric, mutates at most once. */
    private Outcome revokeInSession(ClientSession session, MembershipId membershipId) {
        Instant now = MembershipBillingCalendar.truncate(clock.instant());
        Optional<Membership> found = repository.findById(session, membershipId);
        if (found.isEmpty()) {
            throw failure(MembershipFailure.Reason.NOT_FOUND, "membership not found");
        }
        Membership term = found.get();
        switch (term.status()) {
            case REVOKED:
                return new Outcome(term, false); // idempotent: the first revokedAt stays
            case EXPIRED:
                throw failure(MembershipFailure.Reason.INVALID_TRANSITION, "membership already expired");
            case ACTIVE:
                break;
        }
        if (!term.isEntitlingAt(now)) { // stale (window ended) or not yet started: never rewritten as REVOKED
            throw failure(MembershipFailure.Reason.INVALID_TRANSITION, "membership is not within its window");
        }
        if (!repository.markRevoked(session, term.membershipId(), term.version(), now)) {
            throw failure(MembershipFailure.Reason.INTEGRITY_FAILURE, "membership revoke CAS missed");
        }
        return new Outcome(reread(session, term.membershipId()), true);
    }

    /** The post-write state, strictly reconstructed from the same session (sees the attempt's own write). */
    private Membership reread(ClientSession session, MembershipId id) {
        return repository.findById(session, id).orElseThrow(
                () -> failure(MembershipFailure.Reason.INTEGRITY_FAILURE, "membership vanished within its transaction"));
    }

    private static MembershipFailure failure(MembershipFailure.Reason reason, String message) {
        return new MembershipFailure(reason, message);
    }
}
