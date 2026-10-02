package com.tazzzo.common.audit;

/**
 * Who performed an audited mutation, by KIND. Exactly the kinds the current architecture needs:
 * <ul>
 *   <li>{@link #HUMAN_ADMIN} — a named person authenticated by a per-person credential (reserved: no per-person admin
 *       authentication exists yet, so nothing produces it today);</li>
 *   <li>{@link #SERVICE_ACCOUNT} — a non-human credential, including the current SHARED admin tokens. A shared token is
 *       a service account even when a person happens to use it: it never identifies WHICH person;</li>
 *   <li>{@link #SYSTEM} — the application itself (workers, schedulers, startup tasks), with a stable {@code system:...} id.</li>
 * </ul>
 */
public enum ActorType {
    HUMAN_ADMIN,
    SERVICE_ACCOUNT,
    SYSTEM
}
