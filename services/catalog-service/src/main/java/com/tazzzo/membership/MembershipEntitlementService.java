package com.tazzzo.membership;

import com.mongodb.MongoException;
import com.tazzzo.auth.CustomerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * PR-16A-2 — the standalone entitlement read ({@link MembershipEntitlementPort}). It owns no transaction (a
 * single primary-pinned read), so it may record its own bounded failure metric; a normal empty result is NOT a
 * failure and records nothing, and there is deliberately no entitlement-result metric.
 */
@Component
public class MembershipEntitlementService implements MembershipEntitlementPort {

    private static final Logger log = LoggerFactory.getLogger(MembershipEntitlementService.class);

    private final MembershipRepository repository;
    private final MembershipObservability observability;
    private final Clock clock;

    public MembershipEntitlementService(MembershipRepository repository, MembershipObservability observability,
                                        Clock clock) {
        this.repository = repository;
        this.observability = observability;
        this.clock = clock;
    }

    @Override
    public Optional<MembershipEntitlement> currentEntitlement(CustomerId customerId) {
        try {
            if (customerId == null) {
                throw new MembershipFailure(MembershipFailure.Reason.INVALID_REQUEST, "customerId required");
            }
            Optional<Membership> open;
            try {
                open = repository.findActiveByCustomer(customerId); // pinned to ReadPreference.primary()
            } catch (MongoException e) {
                log.error("membership_datastore_failed operation=entitlement_read type={}", e.getClass().getSimpleName());
                throw new MembershipFailure(MembershipFailure.Reason.UNAVAILABLE,
                        "datastore unavailable during entitlement read");
            }
            return MembershipEntitlementReader.evaluate(open, MembershipBillingCalendar.truncate(clock.instant()));
        } catch (MembershipFailure e) {
            observability.failure(MembershipObservability.Operation.ENTITLEMENT_READ, e.reason());
            throw e;
        }
    }
}
