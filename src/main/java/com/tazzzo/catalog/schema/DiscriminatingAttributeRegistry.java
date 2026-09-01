package com.tazzzo.catalog.schema;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WP-0 — the ratified identity configuration per vertical, loaded from the
 * `discriminating_attributes` collection at bootstrap (E-2).
 *
 * EMPTY BY DEFAULT, AND THAT IS THE SAFE STATE. C-3 was reversed on 2026-08-30 after the full
 * suite proved pack-only identity unsafe: with brand|vertical|pack as the whole identity, Lay's
 * Classic 100g and Lay's Magic Masala 100g are one product. An insufficient discriminator set
 * must leave identity UNRESOLVED, never produce a confidently wrong key.
 *
 * A ratification takes effect on RESTART, not instantly (E-2). For a governed, taxonomy-grade
 * event that is deliberate: a read-through cache would apply immediately but would let two
 * instances disagree mid-rollout, which is a split-brain identity model.
 */
@Component
public class DiscriminatingAttributeRegistry {

    private final Map<String, Ratification> ratified = new ConcurrentHashMap<>();

    /** Empty ⇒ the vertical is unratified ⇒ no canonical key is minted for it. */
    public Optional<Ratification> forVertical(String verticalId) {
        if (verticalId == null) return Optional.empty();
        return Optional.ofNullable(ratified.get(verticalId));
    }

    public void ratify(Ratification r) {
        ratified.put(r.verticalId(), r);
    }

    public void clear() {
        ratified.clear();
    }

    /** Bootstrap load of every active ratification. @return how many were loaded. */
    public int load(MongoDatabase db) {
        List<Document> rows = db.getCollection("discriminating_attributes")
                .find(Filters.eq("status", "active")).into(new ArrayList<>());
        for (Document row : rows) {
            Map<String, Ratification.Vocabulary> vocabularies = new HashMap<>();
            Document vocabs = row.get("vocabularies", Document.class);
            if (vocabs != null) {
                for (String attributeKey : vocabs.keySet()) {
                    Document v = vocabs.get(attributeKey, Document.class);
                    Map<String, String> synonyms = new HashMap<>();
                    Document syn = v.get("synonyms", Document.class);
                    if (syn != null) {
                        for (String k : syn.keySet()) synonyms.put(k, syn.getString(k));
                    }
                    vocabularies.put(attributeKey, new Ratification.Vocabulary(
                            new java.util.HashSet<>(v.getList("canonical", String.class, List.of())),
                            synonyms));
                }
            }
            ratify(new Ratification(row.getString("vertical_id"), row.getString("version"),
                    row.getList("discriminators", String.class), vocabularies));
        }
        return rows.size();
    }
}
