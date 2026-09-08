package com.tazzzo.catalog.consumer;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A vertical's consumer projection policy (RESP-PROJ RP-6). Keyed by {@code vertical_id} — NOT a
 * {@code consumer_visible} flag on the global attribute definition, because the same key occurs
 * across many verticals with different usefulness, and a flag there would mix shopper presentation
 * policy into the governance registry.
 *
 * <p>Presentation metadata ONLY. It does not modify attribute semantics, validation, governance,
 * identity discrimination or schema requiredness.
 *
 * <p>Absence is a valid, successful state: no policy document means no governed attributes are
 * exposed for that vertical (RP-6b).
 */
public record ConsumerProjectionPolicy(String verticalId, String projectionVersion,
                                       List<Entry> attributes) {

    /** One opted-in attribute key with its presentation metadata. */
    public record Entry(String attributeKey, int displayOrder, String displayLabel) { }

    /**
     * Reads a stored policy document under RP-6d STRICT INTEGRITY. Nothing is repaired: a missing
     * {@code display_order}, a missing {@code display_label}, a blank key, a duplicate key and a
     * duplicate order are all configuration failures, not shapes to be normalised.
     *
     * <p>The previous implementation defaulted a missing order to {@code Integer.MAX_VALUE} and
     * broke ties alphabetically. TR-3's terminal tiebreaker is not a precedent for that: TR-3 is
     * TRANSPORT ordering over data nobody authored, whereas {@code display_order} is an authored
     * shopper-presentation decision. <b>Two entries claiming the same display position are an
     * authoring ambiguity, and resolving them alphabetically would silently invent a merchandising
     * answer.</b> Likewise a missing label is not repairable by falling back to the attribute key —
     * {@code grain_length} is exactly why a governed key is not a shopper label.
     *
     * @throws ConsumerProjectionPolicyException when the stored document violates RP-6d
     */
    public static ConsumerProjectionPolicy from(Document doc) {
        String verticalId = requireString(doc, "vertical_id", "policy " + doc.get("_id"));
        // RP-6b's null version means NO POLICY. An authored policy without a version would make an
        // authoring fault indistinguishable from an unauthored vertical.
        String version = requireString(doc, "projection_version", "policy for " + verticalId);

        // RP-6d: the array must be PRESENT. "Missing" and "empty" are different authored states,
        // and normalising one into the other is the same silent repair RP-6d removed elsewhere.
        Object rawAttributes = doc.get("attributes");
        if (rawAttributes == null) {
            throw new ConsumerProjectionPolicyException(
                    "policy for " + verticalId + " has no attributes array (an EMPTY array is"
                            + " required to author an empty policy)");
        }
        if (!(rawAttributes instanceof List<?> members)) {
            throw new ConsumerProjectionPolicyException(
                    "policy for " + verticalId + ": attributes is not an array but "
                            + rawAttributes.getClass().getSimpleName());
        }

        List<Entry> entries = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        Set<Integer> seenOrders = new HashSet<>();
        for (Object member : members) {
            if (!(member instanceof Document a)) {
                throw new ConsumerProjectionPolicyException(
                        "policy for " + verticalId + ": attributes entry is not a document but "
                                + (member == null ? "null" : member.getClass().getSimpleName()));
            }
            String context = "policy for " + verticalId;
            String key = requireString(a, "attribute_key", context);
            int order = requireInt(a, "display_order", context + ": '" + key + "'");
            String label = requireString(a, "display_label", context + ": '" + key + "'");
            if (!seenKeys.add(key)) {
                throw new ConsumerProjectionPolicyException(
                        context + ": duplicate attribute_key '" + key + "'");
            }
            if (!seenOrders.add(order)) {
                throw new ConsumerProjectionPolicyException(
                        context + ": duplicate display_order " + order
                                + " (two entries claim one display position)");
            }
            entries.add(new Entry(key, order, label));
        }
        // display_order is unique by the check above, so this ordering is total on its own.
        entries.sort(Comparator.comparingInt(Entry::displayOrder));
        return new ConsumerProjectionPolicy(verticalId, version, List.copyOf(entries));
    }

    /**
     * Reads a required non-blank string WITHOUT {@code Document.getString}, which throws a raw
     * {@link ClassCastException} on a wrong BSON type. RP-6d made a malformed policy a DEDICATED
     * configuration failure, so a wrong type must arrive as one too — not as a driver exception
     * that happens to share the same HTTP status.
     */
    private static String requireString(Document doc, String field, String context) {
        Object value = doc.get(field);
        if (value == null) {
            throw new ConsumerProjectionPolicyException(context + " has no " + field);
        }
        if (!(value instanceof String s)) {
            throw new ConsumerProjectionPolicyException(context + ": " + field
                    + " is not a string but " + value.getClass().getSimpleName());
        }
        if (s.isBlank()) {
            throw new ConsumerProjectionPolicyException(context + " has a blank " + field);
        }
        return s;
    }

    /** BSON integral types only — a Double or a numeric string is a malformed display_order. */
    private static int requireInt(Document doc, String field, String context) {
        Object value = doc.get(field);
        if (value == null) {
            throw new ConsumerProjectionPolicyException(context + " has no " + field);
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            return Math.toIntExact(l);
        }
        throw new ConsumerProjectionPolicyException(context + ": " + field
                + " is not an integer but " + value.getClass().getSimpleName());
    }

}
