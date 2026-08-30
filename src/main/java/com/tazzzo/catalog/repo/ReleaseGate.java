package com.tazzzo.catalog.repo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.tx.TaxonomyChangeException;
import org.bson.Document;
import org.springframework.stereotype.Component;

/**
 * The single fenced release gate (Step-1 M2 mechanism, shared by taxonomy AND attribute
 * authoring): requiring an open release is a WRITE ($inc change_seq on the release doc),
 * so every change transaction's write set intersects with a concurrent freeze/activation —
 * snapshot-isolation write skew on the gate is impossible, and all catalogue changes
 * serialize against release lifecycle transitions.
 */
@Component
public class ReleaseGate {

    private final WritePath writePath;

    public ReleaseGate(WritePath writePath) {
        this.writePath = writePath;
    }

    public String requireOpen(ClientSession session, EventPayload e) {
        Document rel = writePath.database().getCollection("catalogue_releases")
                .find(session, Filters.eq("status", "publishing")).first();
        if (rel == null) {
            throw new TaxonomyChangeException("NO_OPEN_RELEASE",
                    "catalogue changes require an open (publishing) release");
        }
        writePath.auxWrite(session, "catalogue_releases", e, c -> {
            long n = c.updateOne(session,
                    Filters.and(Filters.eq("_id", rel.getString("_id")),
                            Filters.eq("status", "publishing")),
                    Updates.inc("change_seq", 1)).getModifiedCount();
            if (n == 0) {
                throw new TaxonomyChangeException("NO_OPEN_RELEASE",
                        "release closed concurrently: " + rel.getString("_id"));
            }
        });
        return rel.getString("_id");
    }
}
