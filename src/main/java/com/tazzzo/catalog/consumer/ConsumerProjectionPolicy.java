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
        String verticalId = doc.getString("vertical_id");
        if (isBlank(verticalId)) {
            throw new ConsumerProjectionPolicyException("policy has no vertical_id: " + doc.get("_id"));
        }
        String version = doc.getString("projection_version");
        if (isBlank(version)) {
            // RP-6b's null version means NO POLICY. An authored policy without a version would
            // make an authoring fault indistinguishable from an unauthored vertical.
            throw new ConsumerProjectionPolicyException(
                    "policy for " + verticalId + " has no projection_version");
        }

        List<Entry> entries = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        Set<Integer> seenOrders = new HashSet<>();
        List<Document> raw = doc.getList("attributes", Document.class);
        if (raw != null) {
            for (Document a : raw) {
                String key = a.getString("attribute_key");
                if (isBlank(key)) {
                    throw new ConsumerProjectionPolicyException(
                            "policy for " + verticalId + " has an entry with no attribute_key");
                }
                Integer order = a.getInteger("display_order");
                if (order == null) {
                    throw new ConsumerProjectionPolicyException(
                            "policy for " + verticalId + ": '" + key + "' has no display_order");
                }
                String label = a.getString("display_label");
                if (isBlank(label)) {
                    throw new ConsumerProjectionPolicyException(
                            "policy for " + verticalId + ": '" + key + "' has no display_label");
                }
                if (!seenKeys.add(key)) {
                    throw new ConsumerProjectionPolicyException(
                            "policy for " + verticalId + ": duplicate attribute_key '" + key + "'");
                }
                if (!seenOrders.add(order)) {
                    throw new ConsumerProjectionPolicyException(
                            "policy for " + verticalId + ": duplicate display_order " + order
                                    + " (two entries claim one display position)");
                }
                entries.add(new Entry(key, order, label));
            }
        }
        // display_order is unique by the check above, so this ordering is total on its own.
        entries.sort(Comparator.comparingInt(Entry::displayOrder));
        return new ConsumerProjectionPolicy(verticalId, version, List.copyOf(entries));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
