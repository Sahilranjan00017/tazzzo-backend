package com.tazzzo.auth;

/**
 * PR-11A — the minimal immutable customer identity a verified access token proves. Deliberately
 * small: no phone number, no installation id, no IP, no email, no raw token. Only what a
 * downstream controller needs to know WHO is calling and WHICH session — everything else
 * (profile, addresses, cart) is looked up from {@link #customerId()}, never carried here.
 */
public record CustomerPrincipal(CustomerId customerId, SessionId sessionId) {

    public CustomerPrincipal {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId required");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId required");
        }
    }
}
