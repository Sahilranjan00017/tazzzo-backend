package com.tazzzo.support;

import com.mongodb.client.ClientSession;
import org.springframework.stereotype.Component;

/**
 * Account deletion: every support case of the customer is DELETED in the caller's transaction. Case text is free-form
 * personal data written by the customer and staff; nothing in it is a commercial record (orders carry those).
 */
@Component
public class SupportErasure {

    private final SupportCaseRepository cases;

    public SupportErasure(SupportCaseRepository cases) {
        this.cases = cases;
    }

    public long erase(ClientSession session, String customerId) {
        return cases.deleteForCustomer(session, customerId);
    }
}
