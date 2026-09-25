package com.tazzzo.catalog.schema;

/**
 * A derived identity together with the ratification version that produced it (E-3). The version
 * is per-vertical, not a global constant: different verticals legitimately carry different
 * ratification versions during a partial rollout, and each key records its own provenance.
 */
public record CanonicalKey(String key, String version) { }
