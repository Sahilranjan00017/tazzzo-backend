package com.tazzzo.catalog.schema;

import com.tazzzo.catalog.domain.PackOf;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CAT-ID-3 — derives the canonical identity key from GOVERNED values only.
 *
 *   canonical_key = brand_code | vertical_id | <discriminating attrs, sorted> [| packof=N]
 *
 * Deliberately pure and Mongo-free: identity derivation must be a total function of the
 * observation, so two sources (and the CMS, and the importer) compute the same key without
 * communicating. That property is what makes cross-source convergence work at all.
 *
 * C-3 (pack-only Phase 1) was REVERSED on 2026-08-30. The full suite proved it unsafe: with
 * brand|vertical|pack as the entire identity, two genuinely different products sharing those
 * three collapse into one — Lay's Classic 100g and Lay's Magic Masala 100g, India Gate Basmati
 * 5kg aged and non-aged. That is not an occasional collision; it is the norm in grocery.
 *
 * So identity is minted ONLY for verticals WP-0 has ratified (DiscriminatingAttributeRegistry).
 * An unratified vertical yields no key. There is NO fallback to brand|vertical|pack: an
 * insufficient discriminator set must leave identity unresolved, never produce a confidently
 * wrong key.
 *
 * GATE 2: every failure returns EMPTY. There are no partial keys, no opportunistic omission of
 * a missing discriminator, and no fallback value. A key missing a discriminating attribute is a
 * WRONG key, and wrong keys merge distinct products.
 */
@Component
public class CanonicalKeyService {

    /** Stamped onto every product keyed under these rules; a change is a re-key migration. */
    public static final String KEY_VERSION = "ck-phase1";

    private final DiscriminatingAttributeRegistry registry;

    public CanonicalKeyService(DiscriminatingAttributeRegistry registry) {
        this.registry = registry;
    }

    private static final Set<String> UNKEYABLE_VERTICALS =
            Set.of("TZV-UNCLASSIFIED", "TZV-SCOPE-BLOCKED");

    /** U-4-d: the only units that normalize. dozen/pair/set are absent BY DECISION (U-4-a). */
    private static final Map<String, Unit> UNITS = Map.of(
            "kg", new Unit("g", new BigDecimal("1000")),
            "g", new Unit("g", BigDecimal.ONE),
            "l", new Unit("ml", new BigDecimal("1000")),
            "ml", new Unit("ml", BigDecimal.ONE),
            "pieces", new Unit("pieces", BigDecimal.ONE));

    private record Unit(String base, BigDecimal factor) { }

    public Optional<String> derive(String verticalId, String brandCode, String productType,
                                   Map<String, Object> attributes, PackOf packOf) {
        if (isBlank(verticalId) || UNKEYABLE_VERTICALS.contains(verticalId)) return Optional.empty();
        // A bundle has no vertical and no pack of its own; its identity is not brand|vertical|pack.
        if ("bundle".equals(productType)) return Optional.empty();
        // Rule N-1 / V2 §2.3: absence of brand is an honest "unknown", never a wildcard.
        if (isBlank(brandCode)) return Optional.empty();
        // Gate 2 clause 1: an unratified vertical is not keyable. No fallback.
        List<String> discriminators = registry.forVertical(verticalId);
        if (discriminators.isEmpty()) return Optional.empty();
        if (attributes == null) return Optional.empty();

        Optional<String> packTerm = packTerm(attributes);
        if (packTerm.isEmpty()) return Optional.empty();

        StringBuilder key = new StringBuilder()
                .append(brandCode).append('|').append(verticalId).append('|').append(packTerm.get());

        if ("variant_pack".equals(productType)) {
            // U-4-h: the pack carries its own TOTAL, so the key is self-derived — no component
            // lookup. packof is MANDATORY: without it a 6x250ml pack and a 1500ml single would
            // collide on pack=1500ml.
            if (packOf == null || packOf.qty() < 2) return Optional.empty();
            key.append("|packof=").append(packOf.qty());
        }
        return Optional.of(key.toString());
    }

    /** U-4: pack_size + pack_unit normalize TOGETHER into one term. */
    private Optional<String> packTerm(Map<String, Object> attributes) {
        Object rawSize = attributes.get("pack_size");
        Object rawUnit = attributes.get("pack_unit");
        if (!(rawSize instanceof Number n) || !(rawUnit instanceof String u)) return Optional.empty();

        Unit unit = UNITS.get(u.trim().toLowerCase());
        if (unit == null) return Optional.empty();   // U-4-a: dozen/pair/set are not aliased

        BigDecimal value;
        try {
            value = new BigDecimal(n.toString());     // decimal, never binary floating point
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (value.signum() <= 0) return Optional.empty();

        BigDecimal scaled = value.multiply(unit.factor());
        // U-4-c: a value needing more than 3 dp is NOT approximated — it yields no key.
        if (scaled.stripTrailingZeros().scale() > 3) return Optional.empty();
        // pieces is a count: a fractional count is not representable.
        if ("pieces".equals(unit.base()) && scaled.stripTrailingZeros().scale() > 0) {
            return Optional.empty();
        }
        String rendered = scaled.setScale(3, RoundingMode.HALF_EVEN)
                .stripTrailingZeros().toPlainString();
        return Optional.of("pack=" + rendered + unit.base());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
