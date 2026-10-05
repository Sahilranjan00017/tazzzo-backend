package com.tazzzo.auth.session;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * The identity module's part of account deletion, all in the caller's transaction: the customer's sessions are
 * revoked (tokens stop working at the next request) and the customer row becomes a tombstone that no longer carries the
 * phone. The module that owns the data performs the erasure; the orchestrator only sequences it.
 */
@Component
public class CustomerAccountErasure {

    public enum AccountState { ACTIVE, DELETED, MISSING }

    public record Result(long sessionsRevoked, Optional<String> phone) { }

    private final CustomerRepository customers;
    private final CustomerSessionRepository sessions;

    public CustomerAccountErasure(CustomerRepository customers, CustomerSessionRepository sessions) {
        this.customers = customers;
        this.sessions = sessions;
    }

    public AccountState state(ClientSession session, CustomerId customerId) {
        Document row = customers.findById(session, customerId.value());
        if (row == null) {
            return AccountState.MISSING;
        }
        return CustomerRepository.STATUS_DELETED.equals(row.getString("status")) ? AccountState.DELETED : AccountState.ACTIVE;
    }

    /** Revokes every session, then tombstones the row; returns the phone the row carried (for the OTP clean-up). */
    public Result erase(ClientSession session, CustomerId customerId, Instant now) {
        long revoked = sessions.revokeAll(session, customerId, now);
        Optional<String> phone = customers.tombstone(session, customerId.value(), now);
        return new Result(revoked, phone);
    }
}
