package com.tazzzo.catalog.consumer;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.bson.Document;
import org.springframework.stereotype.Component;

/**
 * TR2-CURRENT-1 — resolves the taxonomy release for one consumer request, ONCE.
 *
 * <p><b>"Current" is an explicit pointer, never an inference.</b> The schema enforces at most one
 * OPEN release; it does not enforce exactly one historical ACTIVE release. So the latest timestamp,
 * the highest lexical id and "whichever active row Mongo returns first" are all guesses dressed as
 * lookups, and Law 4 forbids exactly that. {@code system_config/consumer_taxonomy_release} carries
 * the answer, written in the same transaction as the activation that made it true.
 */
@Component
public class ConsumerReleaseResolver {

    private final MongoDatabase db;

    public ConsumerReleaseResolver(MongoDatabase db) {
        this.db = db;
    }

    /**
     * @param explicitRelease a caller-supplied release, or null/blank for "current"
     * @return a concrete, verified-active release id
     * @throws ConsumerFailures.NotFound an explicit release that is absent or not active
     * @throws ConsumerFailures.Unavailable the pointer is missing or names a release that is not
     *     active — a broken pointer is an infrastructure fault, and the fallback is NOT to guess
     */
    public String resolve(String explicitRelease) {
        if (explicitRelease != null && !explicitRelease.isBlank()) {
            String requested = explicitRelease.trim();
            if (!isActive(requested)) {
                throw new ConsumerFailures.NotFound("release not found or not active: " + requested);
            }
            return requested;
        }
        Document pointer = db.getCollection("system_config")
                .find(Filters.eq("_id", TaxonomyChangeService.CONSUMER_RELEASE_POINTER)).first();
        if (pointer == null) {
            throw new ConsumerFailures.Unavailable("no current consumer release pointer");
        }
        String current = pointer.getString("release_id");
        if (current == null || current.isBlank()) {
            throw new ConsumerFailures.Unavailable("current consumer release pointer is empty");
        }
        if (!isActive(current)) {
            // The pointer exists but names something unusable. Falling back to "some other active
            // release" would be the inference this whole decision exists to prevent.
            throw new ConsumerFailures.Unavailable(
                    "current consumer release pointer names a release that is not active");
        }
        return current;
    }

    private boolean isActive(String releaseId) {
        Document release = db.getCollection("catalogue_releases")
                .find(Filters.eq("_id", releaseId)).first();
        return release != null && "active".equals(release.getString("status"));
    }
}
