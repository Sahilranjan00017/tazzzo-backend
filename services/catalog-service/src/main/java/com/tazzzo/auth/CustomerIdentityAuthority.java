package com.tazzzo.auth;

/**
 * PR-12A hardening (Finding 1) — the foundation-level seam a downstream domain (e.g.
 * {@code customer.profile}) uses to confirm an authenticated {@link CustomerId} actually
 * corresponds to a real, persisted customer identity, WITHOUT depending on the concrete
 * {@code auth.session.CustomerRepository} implementation. Mirrors {@link SessionAuthority}'s
 * dependency-inversion shape exactly: the interface lives here (the foundation), the
 * implementation lives in {@code com.tazzzo.auth.session} — never the reverse.
 */
public interface CustomerIdentityAuthority {

    boolean exists(CustomerId customerId);
}
