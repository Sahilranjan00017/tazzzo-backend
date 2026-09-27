package com.tazzzo.auth;

/**
 * PR-11C — the server-side revocation authority {@link CustomerAuthFilter} consults AFTER
 * cryptographic/time verification succeeds. A structurally valid, unexpired access token proves
 * only that this server minted it (see {@code CustomerAccessTokenCodec}'s PR-11A boundary) — it
 * does NOT prove the session it names is still active. This interface is the dependency-inversion
 * seam: {@code com.tazzzo.auth} (foundation) defines it, {@code com.tazzzo.auth.session} (PR-11C)
 * implements it against real session persistence, so the foundation package never depends downward
 * on the session-lifecycle package.
 *
 * <p>Deliberately narrow: a boolean, not a full session document — the filter needs an authority
 * decision, never internal session fields (refresh digest, generation, revocation timestamp).
 */
public interface SessionAuthority {

    /** True only if a session with this id, belonging to this customer, is neither revoked nor expired. */
    boolean isSessionActive(CustomerId customerId, SessionId sessionId);
}
