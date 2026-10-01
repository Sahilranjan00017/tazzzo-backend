package com.tazzzo.membership;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * PR-16A-2 — the session-aware entitlement read ({@link TransactionalMembershipEntitlementPort}) and the ONE
 * shared entitlement decision ({@link #evaluate}) the standalone service reuses.
 *
 * <p>Structurally incapable of the things the port forbids: it has no {@code Tx} (cannot open a transaction), no
 * observability or registry (cannot emit a metric), no plan source (an entitlement is the stored TERM snapshot,
 * never reinterpreted against the current plan configuration) and no write method on the repository in sight
 * (ArchUnit-enforced). It reads through the lifecycle query {@code customerId + status = ACTIVE} (served by
 * {@code membership_active_by_customer}) and the repository's strict reconstruction, so a corrupt ACTIVE row —
 * including one with a missing or malformed {@code openTerm} marker — stays fail-loud and {@code empty} keeps
 * meaning "authoritatively no entitlement".
 */
@Component
public class MembershipEntitlementReader implements TransactionalMembershipEntitlementPort {

    private static final Logger log = LoggerFactory.getLogger(MembershipEntitlementReader.class);

    private final MembershipRepository repository;
    private final Clock clock;

    public MembershipEntitlementReader(MembershipRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Optional<MembershipEntitlement> currentEntitlement(ClientSession session, CustomerId customerId) {
        if (session == null) {
            throw new IllegalArgumentException("session required");
        }
        if (customerId == null) {
            throw new MembershipFailure(MembershipFailure.Reason.INVALID_REQUEST, "customerId required");
        }
        Optional<Membership> active;
        try {
            active = repository.findActiveByCustomer(session, customerId);
        } catch (MongoException e) {
            if (e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                throw e; // the caller's Tx.call inspects exactly this label to retry; never convert it
            }
            log.error("membership_datastore_failed operation=entitlement_read type={}", e.getClass().getSimpleName());
            throw new MembershipFailure(MembershipFailure.Reason.UNAVAILABLE, "datastore unavailable during entitlement read");
        }
        // fresh clock read for THIS call, taken after the read so a window that ended meanwhile is judged ended
        return evaluate(active, MembershipBillingCalendar.truncate(clock.instant()));
    }

    /**
     * Runtime entitlement truth: {@code status == ACTIVE AND validFrom <= now < validUntil}. Persisted state is
     * not runtime truth: a stale ACTIVE (window ended) or a not-yet-started ACTIVE (clock skew) is empty and is
     * NOT mutated; the row keeps holding its open slot until a later grant lazily expires it.
     */
    static Optional<MembershipEntitlement> evaluate(Optional<Membership> active, Instant now) {
        return active.filter(term -> term.isEntitlingAt(now)).map(MembershipEntitlement::of);
    }
}
