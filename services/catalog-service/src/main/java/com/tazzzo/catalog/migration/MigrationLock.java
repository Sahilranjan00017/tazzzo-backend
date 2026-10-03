package com.tazzzo.catalog.migration;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Single-runner lock for migrations (R5): a lease document in {@code schema_migration_lock}.
 *
 * <p>Correctness properties:
 * <ul>
 *   <li><b>atomic acquire</b> — one compare-and-set {@code findOneAndUpdate}: it matches only an unheld or expired
 *       lease, so MongoDB serialises concurrent attempts and exactly one wins;</li>
 *   <li><b>server time</b> — lease expiry is evaluated with the database's {@code $$NOW}, so clock skew between
 *       application instances cannot make two holders believe they own the lease;</li>
 *   <li><b>fencing token</b> — {@code fence} increases on every acquisition; history writes carry it, so a stalled
 *       holder that lost its lease cannot overwrite a newer holder's records;</li>
 *   <li><b>release after failure</b> — release is explicit (and in the runner's {@code finally}); a crashed holder
 *       is superseded when its lease expires.</li>
 * </ul>
 */
public final class MigrationLock {

    public static final String COLLECTION = "schema_migration_lock";
    public static final String LOCK_ID = "catalog-service-migrations";

    public record Held(String owner, long fence) { }

    private final MongoDatabase db;

    public MigrationLock(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> coll() {
        return db.getCollection(COLLECTION);
    }

    private static Document plusLease(long leaseMs) {
        return new Document("$dateAdd", new Document("startDate", "$$NOW").append("unit", "millisecond").append("amount", leaseMs));
    }

    /** Idempotently creates the lock document (expired, unowned). A concurrent creator winning the race is fine. */
    private void ensureDocument() {
        try {
            coll().updateOne(Filters.eq("_id", LOCK_ID),
                    Updates.combine(Updates.setOnInsert("ownerId", null), Updates.setOnInsert("expiresAt", new Date(0)),
                            Updates.setOnInsert("fence", 0L)),
                    new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            if (e.getCode() != 11000) throw e; // the document already exists
        }
    }

    /**
     * Atomically takes the lease iff it is expired (server time) or already ours. This is a plain compare-and-set
     * on one document — MongoDB serialises concurrent attempts, so exactly one wins. {@code $expr} is not allowed
     * in an upsert filter, which is why the document is ensured separately first.
     */
    public Optional<Held> tryAcquire(String owner, String runId, Duration lease) {
        ensureDocument();
        Bson filter = Filters.and(Filters.eq("_id", LOCK_ID), Filters.expr(new Document("$or", List.of(
                new Document("$lte", List.of("$expiresAt", "$$NOW")),
                new Document("$eq", List.of("$ownerId", owner))))));
        List<Bson> pipeline = List.of(new Document("$set", new Document("ownerId", new Document("$literal", owner))
                .append("runId", new Document("$literal", runId))
                .append("acquiredAt", "$$NOW")
                .append("expiresAt", plusLease(lease.toMillis()))
                .append("fence", new Document("$add", List.of(new Document("$ifNull", List.of("$fence", 0L)), 1L)))));
        Document after = coll().findOneAndUpdate(filter, pipeline,
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return after == null ? Optional.empty() : Optional.of(new Held(owner, ((Number) after.get("fence")).longValue()));
    }

    /** Extends the lease; false means the lease was lost (expired and taken over). */
    public boolean renew(Held held, Duration lease) {
        List<Bson> pipeline = List.of(new Document("$set", new Document("expiresAt", plusLease(lease.toMillis()))));
        return coll().updateOne(Filters.and(Filters.eq("_id", LOCK_ID), Filters.eq("ownerId", held.owner()),
                Filters.eq("fence", held.fence())), pipeline).getMatchedCount() == 1;
    }

    public void release(Held held) {
        List<Bson> pipeline = List.of(new Document("$set", new Document("ownerId", null)
                .append("expiresAt", "$$NOW").append("releasedAt", "$$NOW")));
        coll().updateOne(Filters.and(Filters.eq("_id", LOCK_ID), Filters.eq("ownerId", held.owner()),
                Filters.eq("fence", held.fence())), pipeline);
    }

    public Optional<Document> current() {
        for (String c : db.listCollectionNames()) {
            if (COLLECTION.equals(c)) return Optional.ofNullable(coll().find(Filters.eq("_id", LOCK_ID)).first());
        }
        return Optional.empty();
    }
}
