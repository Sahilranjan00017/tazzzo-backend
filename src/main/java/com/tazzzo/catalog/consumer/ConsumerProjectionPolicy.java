package com.tazzzo.catalog.consumer;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
     * Reads a stored policy document. Entries are returned in {@code display_order}, with
     * {@code attribute_key} as a deterministic tiebreaker so two entries sharing an order cannot
     * render in an arbitrary sequence — the same reasoning as TR-3's terminal id tiebreaker.
     */
    public static ConsumerProjectionPolicy from(Document doc) {
        List<Entry> entries = new ArrayList<>();
        List<Document> raw = doc.getList("attributes", Document.class);
        if (raw != null) {
            for (Document a : raw) {
                Integer order = a.getInteger("display_order");
                entries.add(new Entry(a.getString("attribute_key"),
                        order == null ? Integer.MAX_VALUE : order,
                        a.getString("display_label")));
            }
        }
        entries.sort(Comparator.comparingInt(Entry::displayOrder)
                .thenComparing(Entry::attributeKey, Comparator.nullsLast(Comparator.naturalOrder())));
        return new ConsumerProjectionPolicy(doc.getString("vertical_id"),
                doc.getString("projection_version"), List.copyOf(entries));
    }
}
