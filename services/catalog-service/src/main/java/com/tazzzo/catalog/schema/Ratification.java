package com.tazzzo.catalog.schema;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WP-0 — one vertical's ratified identity configuration.
 *
 * Discriminators and vocabularies travel TOGETHER, deliberately (E-4): F-4 requires both before
 * an attribute may enter a key, so co-locating them makes it structurally impossible to ratify a
 * list whose vocabularies are missing.
 */
public record Ratification(String verticalId, String version, List<String> discriminators,
                           Map<String, Vocabulary> vocabularies) {

    public Ratification {
        if (discriminators == null || discriminators.isEmpty()) {
            throw new IllegalArgumentException("a ratified list may not be empty: " + verticalId);
        }
        discriminators = List.copyOf(discriminators);
        vocabularies = vocabularies == null ? Map.of() : Map.copyOf(vocabularies);
    }

    public Optional<Vocabulary> vocabularyFor(String attributeKey) {
        return Optional.ofNullable(vocabularies.get(attributeKey));
    }

    /**
     * A ratified value vocabulary for ONE identity attribute. Identity-only: an unmapped value
     * remains a perfectly valid attribute under AttributeGovernanceService's open world (H-11),
     * it simply cannot participate in identity.
     */
    public record Vocabulary(Set<String> canonical, Map<String, String> synonyms) {

        public Vocabulary {
            canonical = canonical == null ? Set.of() : Set.copyOf(canonical);
            synonyms = synonyms == null ? Map.of() : Map.copyOf(synonyms);
        }

        /** @return the canonical form, or empty when the value is outside the vocabulary. */
        public Optional<String> resolve(String rawValue) {
            if (rawValue == null) return Optional.empty();
            String v = java.text.Normalizer.normalize(rawValue.trim(), java.text.Normalizer.Form.NFKC)
                    .toLowerCase();
            if (canonical.contains(v)) return Optional.of(v);
            return Optional.ofNullable(synonyms.get(v));
        }
    }
}
