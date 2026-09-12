package com.tazzzo.catalog.consumer;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.tazzzo.catalog.schema.AttributeGovernanceService;
import com.tazzzo.catalog.tx.AttributeAuthoringService;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders products into their {@link ConsumerProductResponse} under each vertical's projection
 * policy (RESP-PROJ RP-2 … RP-6d), with a PAGE-LEVEL read plan (PHASE-5-BATCH-1).
 *
 * <p><b>The read plan is bounded per call, not per product or per attribute:</b>
 * <pre>
 *   projectAll(products)
 *     distinct vertical ids of the page
 *     ONE find on consumer_projection_policy   (skipped when no product carries a vertical)
 *     ONE find on attribute_definitions        (skipped when no policy opts anything in)
 *     render each product, in input order, from those two in-memory indexes
 * </pre>
 * Neither count grows with the number of products or the number of projected attributes. The
 * previous shape — one policy lookup per product, one definition lookup and one paired-unit
 * lookup per projected attribute — was an {@code O(products × attributes)} round-trip multiplier
 * on any paginated list.
 *
 * <p><b>There is ONE implementation of projection truth.</b> {@link #project(Document)} is
 * {@code projectAll(List.of(product)).get(0)}; the rendering core below is pure over the two
 * indexes and never touches the database.
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
 * projection assumes already-eligible products so the two concerns cannot drift into each other.
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

    /**
     * Projects one product — through the page path, so a single item and a page can never disagree.
     * With no policy the result is a SUCCESSFUL response carrying an empty attribute list and a
     * null {@code projectionVersion} (RP-6b) — not an error, and never a synthesized version 0.
     */
    public ConsumerProductResponse project(Document product) {
        return projectAll(List.of(product)).get(0);
    }

    /**
     * Projects a page. Input order is preserved exactly and every input yields exactly one output
     * (a product listed twice is rendered twice). Each item's {@code projectionVersion} is its
     * own vertical's (RP-6c): there is no page-level version and no snapshot across pages —
     * projection stays LIVE, and a later page may truthfully report a newer policy.
     *
     * @throws ConsumerProjectionPolicyException when ANY policy the page needs is malformed
     *         (RP-6d). The whole page fails; nothing is partially rendered or silently repaired.
     */
    public List<ConsumerProductResponse> projectAll(List<Document> products) {
        if (products.isEmpty()) {
            return List.of();                              // and no read of any kind
        }
        Set<String> verticalIds = new LinkedHashSet<>();
        for (Document product : products) {
            String verticalId = verticalIdOf(product);
            if (verticalId != null) {
                verticalIds.add(verticalId);
            }
        }
        Map<String, ConsumerProjectionPolicy> policies = policiesFor(verticalIds);

        boolean anythingOptedIn = false;
        for (ConsumerProjectionPolicy policy : policies.values()) {
            anythingOptedIn |= !policy.attributes().isEmpty();
        }
        DefinitionIndex definitions = anythingOptedIn ? activeDefinitions() : DefinitionIndex.NONE;

        List<ConsumerProductResponse> rendered = new ArrayList<>(products.size());
        for (Document product : products) {
            String verticalId = verticalIdOf(product);
            // A bundle carries no vertical (its classification.vertical_id is null by contract):
            // no policy can apply, and nothing is looked up for it.
            ConsumerProjectionPolicy policy = verticalId == null ? null : policies.get(verticalId);
            rendered.add(render(product, policy, definitions));
        }
        return List.copyOf(rendered);
    }

    // ---------------------------------------------------------------- the two page-level reads

    /**
     * The authored policies for a set of verticals, in ONE read. A vertical with no document is
     * simply absent from the map (RP-6b: a valid state). Every document found is parsed under
     * RP-6d strict integrity before anything is rendered.
     */
    private Map<String, ConsumerProjectionPolicy> policiesFor(Set<String> verticalIds) {
        if (verticalIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ConsumerProjectionPolicy> byVertical = new HashMap<>();
        for (Document doc : db.getCollection("consumer_projection_policy")
                .find(Filters.in("vertical_id", verticalIds))
                .into(new ArrayList<>())) {
            ConsumerProjectionPolicy policy = ConsumerProjectionPolicy.from(doc);
            byVertical.put(policy.verticalId(), policy);
        }
        return byVertical;
    }

    /**
     * The active-or-legacy definition registry, in ONE read, projected to the fields projection
     * consults. The whole active set is read rather than only the policy keys because a paired
     * unit's OWN definition is named by the quantity's definition — it cannot be known before the
     * first read, and a second read per page is the round trip this plan exists to remove. The
     * registry is small (110 seed definitions) and the read is constant per page regardless of
     * how many products or attributes the page carries.
     *
     * <p>Same meaning as {@link AttributeAuthoringService#latestActiveIn}: per key, the highest
     * version among active-or-legacy documents; and a key is "somebody's paired unit" only if an
     * ACTIVE-or-legacy definition names it — a stale or superseded one does not count.
     */
    private DefinitionIndex activeDefinitions() {
        Map<String, Document> latestByKey = new HashMap<>();
        Set<String> pairedUnitKeys = new HashSet<>();
        for (Document definition : db.getCollection("attribute_definitions")
                .find(AttributeAuthoringService.activeOrLegacy())
                .projection(Projections.include("key", "version", "type", "governance",
                        "known_values", "paired_unit"))
                .into(new ArrayList<>())) {
            if (definition.get("key") instanceof String key) {
                Document current = latestByKey.get(key);
                if (current == null || versionOf(definition) > versionOf(current)) {
                    latestByKey.put(key, definition);
                }
            }
            if (definition.get("paired_unit") instanceof String unitKey) {
                pairedUnitKeys.add(unitKey);
            }
        }
        return new DefinitionIndex(Map.copyOf(latestByKey), Set.copyOf(pairedUnitKeys));
    }

    /** {@code latestActiveIn} sorts {@code version} descending; a missing version sorts last. */
    private static long versionOf(Document definition) {
        return definition.get("version") instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

    /** What one page needs to know about definitions, resolved once and then consulted in memory. */
    record DefinitionIndex(Map<String, Document> latestActiveByKey, Set<String> pairedUnitKeys) {

        /** For a page in which no policy opts anything in: nothing is consulted, nothing is read. */
        static final DefinitionIndex NONE = new DefinitionIndex(Map.of(), Set.of());

        Document activeDefinition(String key) {
            return latestActiveByKey.get(key);
        }

        /**
         * True when an ACTIVE definition names this key as its paired unit (e.g. {@code pack_unit}).
         * The active constraint is load-bearing: a stale or superseded definition still carrying a
         * {@code paired_unit} would otherwise suppress an attribute that is perfectly projectable
         * today.
         */
        boolean isAPairedUnit(String key) {
            return pairedUnitKeys.contains(key);
        }
    }

    // ---------------------------------------------------------------- the pure rendering core

    private static String verticalIdOf(Document product) {
        Document classification = product.get("classification", Document.class);
        return classification == null ? null : classification.getString("vertical_id");
    }

    private static ConsumerProductResponse render(Document product, ConsumerProjectionPolicy policy,
                                                  DefinitionIndex definitions) {
        String id = product.getString("_id");
        String title = product.getString("title");
        String brand = product.getString("brand_code");
        if (policy == null) {
            return new ConsumerProductResponse(id, title, brand, List.of(), null);
        }
        Document attributes = product.get("attributes", Document.class);
        List<ConsumerAttributeResponse> projected = new ArrayList<>();
        for (ConsumerProjectionPolicy.Entry entry : policy.attributes()) {
            ConsumerAttributeResponse item = projectOne(entry, attributes, definitions);
            if (item != null) {
                projected.add(item);
            }
        }
        return new ConsumerProductResponse(id, title, brand, List.copyOf(projected),
                policy.projectionVersion());
    }

    private static ConsumerAttributeResponse projectOne(ConsumerProjectionPolicy.Entry entry,
                                                        Document attributes,
                                                        DefinitionIndex definitions) {
        String key = entry.attributeKey();
        if (key == null || attributes == null) {
            return null;
        }
        if (PROHIBITED_KEYS.contains(key)) {
            return null;                                   // RP-5 — above the configuration
        }
        Document definition = definitions.activeDefinition(key);
        if (definition == null) {
            return null;                                   // unverifiable, therefore not published
        }
        if (PROHIBITED_GOVERNANCE.equals(definition.getString("governance"))) {
            return null;                                   // RP-3 — above the configuration
        }
        if (definitions.isAPairedUnit(key)) {
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
            Document unitDefinition = definitions.activeDefinition(pairedUnitKey);
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
    private static boolean valueIsPublishable(Document definition, Object value) {
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
}
