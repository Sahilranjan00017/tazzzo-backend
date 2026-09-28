package com.tazzzo.auth;

import com.mongodb.client.ClientSession;

/**
 * PR-12A hardening (Finding 1) — the foundation-level seam a downstream domain (e.g.
 * {@code customer.profile}) uses to confirm an authenticated {@link CustomerId} actually
 * corresponds to a real, persisted customer identity, WITHOUT depending on the concrete
 * {@code auth.session.CustomerRepository} implementation. Mirrors {@link SessionAuthority}'s
 * dependency-inversion shape exactly: the interface lives here (the foundation), the
 * implementation lives in {@code com.tazzzo.auth.session} — never the reverse.
 *
 * <p>PR-12A hardening (TOCTOU close) — {@link #exists(ClientSession, CustomerId)} lets a caller
 * fold this existence check into its OWN multi-document transaction, so "the identity exists" and
 * "the dependent state is written" are decided from the SAME transactional snapshot, never two
 * separate round-trips with an unbounded gap between them. {@link #exists(CustomerId)} remains for
 * read-only callers (e.g. a plain GET) where no persistent state is ever created, so a momentary
 * race is inherently non-persistent and does not need transactional closure.
 */
public interface CustomerIdentityAuthority {

    boolean exists(CustomerId customerId);

    boolean exists(ClientSession session, CustomerId customerId);
}
