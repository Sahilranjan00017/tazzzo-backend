package com.tazzzo.auth.session;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import org.springframework.stereotype.Component;

/**
 * PR-12A hardening (Finding 1) — implements the {@code com.tazzzo.auth} foundation package's
 * {@link CustomerIdentityAuthority} seam against the real, auth-owned {@code customers} collection.
 * A downstream domain (e.g. {@code customer.profile}) depends only on the interface; this is the
 * ONE place that turns "does this customer identity actually exist" into a real database check.
 */
@Component
public class CustomerIdentityAuthorityImpl implements CustomerIdentityAuthority {

    private final CustomerRepository customers;

    public CustomerIdentityAuthorityImpl(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public boolean exists(CustomerId customerId) {
        return customers.findById(customerId.value()) != null;
    }

    @Override
    public boolean exists(ClientSession session, CustomerId customerId) {
        return customers.findById(session, customerId.value()) != null;
    }
}
