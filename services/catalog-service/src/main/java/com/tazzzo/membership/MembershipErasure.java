package com.tazzzo.membership;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Account deletion: the customer's open membership term, if it is currently entitling, is revoked in the CALLER's
 * transaction (the public {@code MembershipTerminationService.revoke} opens its own, so it cannot take part in an
 * erasure). A term that is not within its window is left for the ordinary expiry path: the row carries no personal data
 * (the customer id is opaque), so retaining it is a commercial-record decision, not a privacy one. Idempotent.
 */
@Component
public class MembershipErasure {

    public enum Outcome { REVOKED, NONE, NOT_ENTITLING }

    private final MembershipRepository repository;

    public MembershipErasure(MembershipRepository repository) {
        this.repository = repository;
    }

    public Outcome revokeOpenTerm(ClientSession session, CustomerId customerId, Instant now) {
        Instant at = MembershipBillingCalendar.truncate(now);
        Optional<Membership> open = repository.findOpenByCustomer(session, customerId);
        if (open.isEmpty() || open.get().status() != MembershipStatus.ACTIVE) {
            return Outcome.NONE;
        }
        Membership term = open.get();
        if (!term.isEntitlingAt(at)) {
            return Outcome.NOT_ENTITLING;
        }
        if (!repository.markRevoked(session, term.membershipId(), term.version(), at)) {
            throw new IllegalStateException("membership revoke CAS missed during account deletion");
        }
        return Outcome.REVOKED;
    }
}
