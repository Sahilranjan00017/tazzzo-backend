package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * Evidence METADATA ownership (approved Evidence API contract). Catalogue owns the record,
 * its validity and its lifecycle; a future Media service owns the payload bytes — this
 * service stores only a `payload_ref`. Retraction is NOT implemented here: it delegates to
 * the existing TaintService so there is exactly one implementation of validity flips and
 * cascade enqueueing.
 *
 * Immutability: metadata never changes after creation. The caller-supplied _id IS the
 * idempotency key — an identical replay is a no-op (returns EXISTING), a differing replay is
 * refused (EvidenceImmutableException).
 */
@Service
public class EvidenceService {

    public enum CreateOutcome { CREATED, EXISTING }

    private static final Set<String> TYPES = Set.of("pdp", "ingredients", "lab_report",
            "supplier_doc", "marketplace_path", "human", "title");

    /** Fields that define identity for the idempotent-replay comparison. */
    private static final Set<String> IDENTITY_FIELDS = Set.of("evidence_type", "source",
            "source_version", "excerpt", "url");

    private final Tx tx;
    private final WritePath writePath;

    public EvidenceService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    public CreateOutcome create(String id, String evidenceType, String source, String sourceVersion,
                                Document payloadRef, String excerpt, String url, Date observedAt) {
        final boolean observedAtSupplied = observedAt != null;
        if (id == null || !id.startsWith("EV-")) {
            throw new IllegalArgumentException("evidence id must start with EV-");
        }
        if (evidenceType == null) {
            throw new IllegalArgumentException("evidenceType is required");
        }
        if (!TYPES.contains(evidenceType)) {
            throw new IllegalArgumentException("unknown evidenceType: " + evidenceType);
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        if (payloadRef != null
                && (payloadRef.getString("store") == null || payloadRef.getString("object_id") == null)) {
            throw new IllegalArgumentException("payloadRef requires store and objectId");
        }
        Document candidate = new Document("_id", id)
                .append("evidence_type", evidenceType)
                .append("source", source)
                .append("source_version", sourceVersion)
                .append("payload_ref", payloadRef)
                .append("excerpt", excerpt)
                .append("url", url)
                .append("validity", "active")
                .append("payload_state", "readable")
                .append("fence", 0)
                .append("observed_at", observedAt == null ? new Date() : observedAt)
                .append("created_at", new Date());

        CreateOutcome[] outcome = new CreateOutcome[1];
        tx.run(session -> {
            Document existing = writePath.database().getCollection("evidence")
                    .find(session, Filters.eq("_id", id)).first();
            if (existing != null) {
                assertSameContent(existing, candidate, id, observedAtSupplied);
                outcome[0] = CreateOutcome.EXISTING;   // idempotent replay: nothing written
                return;
            }
            writePath.insertWithEvent(session, "evidence", candidate,
                    new EventPayload("EVIDENCE_CREATED", "TZP-SYSTEM",
                            Map.of("evidence", id, "type", evidenceType, "source", source)));
            outcome[0] = CreateOutcome.CREATED;
        });
        return outcome[0];
    }

    public Document find(String id) {
        return writePath.database().getCollection("evidence")
                .find(Filters.eq("_id", id)).first();
    }

    private void assertSameContent(Document existing, Document candidate, String id,
                                   boolean compareObservedAt) {
        if (compareObservedAt) {
            // caller-supplied observed_at is part of identity; server-defaulted is not
            Object a = existing.get("observed_at");
            Object b = candidate.get("observed_at");
            if (a == null ? b != null : !a.equals(b)) {
                throw new EvidenceImmutableException(id + " (field 'observed_at' differs)");
            }
        }
        for (String f : IDENTITY_FIELDS) {
            Object a = existing.get(f);
            Object b = candidate.get(f);
            if (a == null ? b != null : !a.equals(b)) {
                throw new EvidenceImmutableException(id + " (field '" + f + "' differs)");
            }
        }
        Document a = existing.get("payload_ref", Document.class);
        Document b = candidate.get("payload_ref", Document.class);
        if (a == null ? b != null : !a.equals(b)) {
            throw new EvidenceImmutableException(id + " (payload_ref differs)");
        }
    }
}
