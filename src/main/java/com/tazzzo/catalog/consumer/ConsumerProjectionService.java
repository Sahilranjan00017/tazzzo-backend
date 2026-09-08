package com.tazzzo.catalog.consumer;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.schema.AttributeGovernanceService;
import com.tazzzo.catalog.tx.AttributeAuthoringService;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Renders a product into its {@link ConsumerProductResponse} under the vertical's projection policy
 * (RESP-PROJ RP-2 … RP-6c).
 *
 * <p><b>Default deny, at BOTH levels (RP-2).</b> An attribute is emitted only when the vertical's
 * policy opts into the KEY <em>and</em> the VALUE is safe to publish. Key-level opt-in alone would
 * still leak unnormalised supplier strings: 61 of 67 {@code enum_open} definitions carry no
 * {@code known_values}, and H-11 accepts unknown values rather than rejecting them.
 * <b>H-11 governs what the catalogue ACCEPTS; this class governs what the storefront PUBLISHES.</b>
 *
 * <p><b>RP-3 and RP-5 are enforced HERE, not delegated to policy authorship.</b> There is no
 * authoring surface yet, and direct or test data can still name those keys — a prohibition that
 * only holds if the policy author remembers it is not a prohibition. A policy listing a claim
 * attribute or {@code form} is silently ignored for those keys.
 *
 * <p>This class does NOT evaluate eligibility. Callers apply {@link ConsumerEligibility} first;
 * projection assumes an already-eligible product so the two concerns cannot drift into each other.
 */
@Service
public class ConsumerProjectionService {

    /** RP-5: `form` is not consumer-projectable while its semantics remain open under T-3. */
    static final Set<String> PROHIBITED_KEYS = Set.of("form");

    /** RP-3: governance=claim carries regulatory/religious exposure and has no verification model. */
    static final String PROHIBITED_GOVERNANCE = "claim";

    private final MongoDatabase db;

    public ConsumerProjectionService(MongoDatabase db) {
        this.db = db;
    }

    /** The stored policy for a vertical, or null when none is authored (a valid state, RP-6b). */
    public ConsumerProjectionPolicy policyFor(String verticalId) {
        if (verticalId == null) {
            return null;
        }
        Document doc = db.getCollection("consumer_projection_policy")
                .find(Filters.eq("vertical_id", verticalId)).first();
        return doc == null ? null : ConsumerProjectionPolicy.from(doc);
    }

    /**
     * Projects one product. With no policy the result is a SUCCESSFUL response carrying an empty
     * attribute list and a null {@code projectionVersion} (RP-6b) — not an error, and never a
     * synthesized version 0.
     */
    public ConsumerProductResponse project(Document product) {
        Document classification = product.get("classification", Document.class);
        String verticalId = classification == null ? null : classification.getString("vertical_id");
        ConsumerProjectionPolicy policy = policyFor(verticalId);

        if (policy == null) {
            return new ConsumerProductResponse(product.getString("_id"), product.getString("title"),
                    product.getString("brand_code"), List.of(), null);
        }

        Document attributes = product.get("attributes", Document.class);
        List<ConsumerAttributeResponse> projected = new ArrayList<>();
        for (ConsumerProjectionPolicy.Entry entry : policy.attributes()) {
            ConsumerAttributeResponse item = projectOne(entry, attributes);
            if (item != null) {
                projected.add(item);
            }
        }
        return new ConsumerProductResponse(product.getString("_id"), product.getString("title"),
                product.getString("brand_code"), List.copyOf(projected),
                policy.projectionVersion());
    }

    private ConsumerAttributeResponse projectOne(ConsumerProjectionPolicy.Entry entry,
                                                 Document attributes) {
        String key = entry.attributeKey();
        if (key == null || attributes == null) {
            return null;
        }
        if (PROHIBITED_KEYS.contains(key)) {
            return null;                                   // RP-5 — above the configuration
        }
        Document definition = activeDefinition(key);
        if (definition == null) {
            return null;                                   // unverifiable, therefore not published
        }
        if (PROHIBITED_GOVERNANCE.equals(definition.getString("governance"))) {
            return null;                                   // RP-3 — above the configuration
        }
        if (isAPairedUnit(key)) {
            return null;                                   // RP-4 — a unit is never a standalone item
        }
        Object value = attributes.get(key);
        if (value == null || !valueIsPublishable(definition, value)) {
            return null;                                   // RP-2 value level
        }

        String unit = null;
        String pairedUnitKey = definition.getString("paired_unit");
        if (pairedUnitKey != null) {
            Object unitValue = attributes.get(pairedUnitKey);
            if (unitValue == null) {
                return null;                               // RP-4 — never a bare quantity
            }
            Document unitDefinition = activeDefinition(pairedUnitKey);
            if (unitDefinition == null || !valueIsPublishable(unitDefinition, unitValue)) {
                return null;                               // an unpublishable unit omits the pair
            }
            unit = String.valueOf(unitValue);
        }
        return new ConsumerAttributeResponse(key, entry.displayLabel(), value, unit);
    }

    /**
     * RP-2 value level, FAIL CLOSED on three counts.
     *
     * <p>1. The declared type must be one governance recognises, and 2. the STORED VALUE must
     * actually match it — checked with {@link AttributeGovernanceService#valueMatchesType}, the
     * same semantics the catalogue applies on write, not a second type system. The runtime check
     * matters because {@code products.attributes} is a generic BSON object: a value written
     * directly, or by a path that predates a type, can disagree with its definition, and a
     * type NAME alone proves nothing about what is stored.
     *
     * <p>3. An {@code enum_open} value must be in the definition's ratified vocabulary — being
     * accepted under H-11 is not publication.
     */
    private boolean valueIsPublishable(Document definition, Object value) {
        String type = definition.getString("type");
        if (!AttributeGovernanceService.isKnownType(type)) {
            return false;
        }
        if (!AttributeGovernanceService.valueMatchesType(type, value)) {
            return false;
        }
        if ("enum_open".equals(type)) {
            List<String> known = definition.getList("known_values", String.class);
            return known != null && known.contains(String.valueOf(value));
        }
        return true;
    }

    /**
     * True when an ACTIVE definition names this key as its paired unit (e.g. {@code pack_unit}).
     *
     * <p>The active constraint is load-bearing: a stale or superseded definition still carrying a
     * {@code paired_unit} would otherwise suppress an attribute that is perfectly projectable
     * today.
     */
    private boolean isAPairedUnit(String key) {
        return AttributeAuthoringService.latestActiveIn(db, null, "attribute_definitions",
                Filters.eq("paired_unit", key)) != null;
    }

    private Document activeDefinition(String key) {
        return AttributeAuthoringService.latestActiveIn(db, null, "attribute_definitions",
                Filters.eq("key", key));
    }
}
