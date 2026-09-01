package com.tazzzo.catalog.schema;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WP-0 — the ratified discriminating attributes per vertical. This is the seam CAT-ID's identity
 * derivation depends on, kept OUT of CanonicalKeyService so that service stays a pure function.
 *
 * EMPTY BY DEFAULT, AND THAT IS THE SAFE STATE.
 *
 * C-3 was REVERSED on 2026-08-30 after the full suite proved pack-only identity unsafe: with
 * brand|vertical|pack as the whole identity, Lay's Classic 100g and Lay's Magic Masala 100g are
 * one product. An insufficient discriminator set must leave identity UNRESOLVED (null key +
 * identity_incomplete), never produce a confidently wrong key. There is deliberately no
 * fallback to brand|vertical|pack.
 *
 * A vertical becomes keyable only when WP-0 ratifies its discriminator list AND F-4 ratifies the
 * value vocabulary of every non-pack attribute in it. Ratifying a vertical then running the
 * existing backfill worker populates identity for its eligible products — no code change.
 */
@Component
public class DiscriminatingAttributeRegistry {

    private final Map<String, List<String>> ratified = new ConcurrentHashMap<>();

    /** Empty ⇒ the vertical is unratified ⇒ no canonical key is minted for it. */
    public List<String> forVertical(String verticalId) {
        if (verticalId == null) return List.of();
        return ratified.getOrDefault(verticalId, List.of());
    }

    /** Records a WP-0 ratification. Phase 1 ships with nothing ratified. */
    public void ratify(String verticalId, List<String> discriminators) {
        if (discriminators == null || discriminators.isEmpty()) {
            throw new IllegalArgumentException("a ratified list may not be empty: " + verticalId);
        }
        ratified.put(verticalId, List.copyOf(discriminators));
    }

    public void clear() {
        ratified.clear();
    }
}
