package com.tazzzo.catalog.schema;

import com.tazzzo.catalog.domain.PackOf;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CAT-ID-3 — derives the canonical identity key from GOVERNED values only.
 *
 *   canonical_key = brand_code | vertical_id | <term>|<term>|… [| packof=N]
 *
 * Deliberately pure and Mongo-free: identity derivation must be a total function of the
 * observation, so two sources — and the CMS, and the importer — compute the same key without
 * communicating. That property is what makes cross-source convergence work at all.
 *
 * C-3 (pack-only Phase 1) was REVERSED on 2026-08-30 after the full suite proved it unsafe: with
 * brand|vertical|pack as the entire identity, Lay's Classic 100g and Lay's Magic Masala 100g are
 * one product. Identity is therefore minted ONLY for verticals WP-0 has ratified, and there is NO
 * fallback to a weaker key.
 *
 * W-F1 (2026-08-30): every ratified discriminator contributes EXACTLY ONE term, and a ratified
 * discriminator that cannot be rendered SUPPRESSES THE WHOLE KEY. It is never skipped — skipping
 * is the opportunistic omission the freeze forbids, and it is how a rich ratification would
 * silently degrade into a weaker identity.
 */
@Component
public class CanonicalKeyService {

    /** The pseudo-discriminator standing for the paired pack_size + pack_unit. */
    public static final String PACK = "pack";

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

    private final DiscriminatingAttributeRegistry registry;

    public CanonicalKeyService(DiscriminatingAttributeRegistry registry) {
        this.registry = registry;
    }

    public Optional<CanonicalKey> derive(String verticalId, String brandCode, String productType,
                                         Map<String, Object> attributes, PackOf packOf) {
        if (isBlank(verticalId) || UNKEYABLE_VERTICALS.contains(verticalId)) return Optional.empty();
        // A bundle has no vertical and no pack of its own; its identity is not brand|vertical|…
        if ("bundle".equals(productType)) return Optional.empty();
        // Rule N-1: absence of brand is an honest "unknown", never a wildcard.
        if (isBlank(brandCode)) return Optional.empty();
        if (attributes == null) return Optional.empty();

        // Gate 2 clause 1: an unratified vertical is not keyable. No fallback.
        Optional<Ratification> maybe = registry.forVertical(verticalId);
        if (maybe.isEmpty()) return Optional.empty();
        Ratification ratification = maybe.get();

        // W-F1: sorted by ATTRIBUTE KEY, not by the ratified list's order — sorting by list order
        // would let a cosmetic reordering of the ratification silently change every key.
        List<String> discriminators = new ArrayList<>(ratification.discriminators());
        java.util.Collections.sort(discriminators);

        StringBuilder key = new StringBuilder().append(brandCode).append('|').append(verticalId);
        for (String discriminator : discriminators) {
            Optional<String> term = term(discriminator, attributes, ratification);
            if (term.isEmpty()) return Optional.empty();   // suppress, never skip
            key.append('|').append(term.get());
        }

        if ("variant_pack".equals(productType)) {
            // U-4-h: the pack carries its own TOTAL, so the key is self-derived — no component
            // lookup. packof is MANDATORY and is a structural suffix, not a discriminator:
            // without it a 6x250ml pack and a 1500ml single would collide on pack=1500ml.
            if (packOf == null || packOf.qty() < 2) return Optional.empty();
            key.append("|packof=").append(packOf.qty());
        }
        return Optional.of(new CanonicalKey(key.toString(), ratification.version()));
    }

    /** One term per ratified discriminator, or empty to suppress the whole key. */
    private Optional<String> term(String discriminator, Map<String, Object> attributes,
                                  Ratification ratification) {
        if (PACK.equals(discriminator)) return packTerm(attributes);

        Object value = attributes.get(discriminator);
        if (value == null) return Optional.empty();

        if (value instanceof Boolean b) {
            return Optional.of(discriminator + "=" + b);
        }
        if (value instanceof Number n) {
            return decimal(new BigDecimal(n.toString()))
                    .map(rendered -> discriminator + "=" + rendered);
        }
        if (value instanceof String s) {
            // W-F3 / E-4: a free string cannot be identity-normalized. A string discriminator
            // REQUIRES a ratified vocabulary; without one, no key.
            return ratification.vocabularyFor(discriminator)
                    .flatMap(v -> v.resolve(s))
                    .map(canonical -> discriminator + "=" + canonical);
        }
        return Optional.empty();
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
        // pieces is a count: a fractional count is not representable.
        if ("pieces".equals(unit.base()) && scaled.stripTrailingZeros().scale() > 0) {
            return Optional.empty();
        }
        return decimal(scaled).map(rendered -> PACK + "=" + rendered + unit.base());
    }

    /** U-4-c/U-4-d: >3 dp is NOT approximated — it yields no term, and so no key. */
    private Optional<String> decimal(BigDecimal value) {
        if (value.stripTrailingZeros().scale() > 3) return Optional.empty();
        return Optional.of(value.setScale(3, RoundingMode.HALF_EVEN)
                .stripTrailingZeros().toPlainString());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
