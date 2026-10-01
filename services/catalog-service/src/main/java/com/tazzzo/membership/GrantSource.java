package com.tazzzo.membership;

/**
 * PR-16A-1 — the structural namespace of a grant reference. Deliberately a single constant: no
 * producer other than the internal grant path exists, and a constant is added only together with the
 * entry point that constructs it (the caller never supplies a source), so two producers' identifiers
 * can never collide by convention failure.
 */
public enum GrantSource {
    INTERNAL_GRANT
}
