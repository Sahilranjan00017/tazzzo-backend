package com.tazzzo.catalog.migration;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Durable record of what ran: one document per migration id in {@code schema_migrations}.
 * Fields: {@code _id}, description, kind, checksum, status (APPLYING | APPLIED | FAILED | BLOCKED), attempts,
 * startedAt, finishedAt, appliedAt, durationMs, buildVersion, environment, database, operator, runId, fence,
 * adopted, note, error (sanitized, no secrets), blockers, rollbackInfo.
 *
 * <p>Every write is FENCED: a writer holding an older lock fence cannot overwrite a newer holder's record
 * (a stalled process that lost its lease cannot rewrite history). The collection is created lazily by the
 * runner, not by {@code SchemaBootstrap}; read paths never create it.
 */
public final class MigrationHistory {

    public static final String COLLECTION = "schema_migrations";

    public record RunInfo(String runId, long fence, MigrationTarget target) { }

    private final MongoDatabase db;

    public MigrationHistory(MongoDatabase db) {
        this.db = db;
    }

    public boolean exists() {
        for (String c : db.listCollectionNames()) if (COLLECTION.equals(c)) return true;
        return false;
    }

    public void ensureExists() {
        if (!exists()) {
            try {
                db.createCollection(COLLECTION);
            } catch (MongoException e) {
                if (!exists()) throw e; // a concurrent creator won the race; that is fine
            }
        }
    }

    public Optional<Document> find(String id) {
        if (!exists()) return Optional.empty();
        return Optional.ofNullable(db.getCollection(COLLECTION).find(Filters.eq("_id", id)).first());
    }

    public List<Document> all() {
        if (!exists()) return List.of();
        return db.getCollection(COLLECTION).find().sort(new Document("_id", 1)).into(new ArrayList<>());
    }

    private MongoCollection<Document> coll() {
        return db.getCollection(COLLECTION);
    }

    private static Bson fenced(String id, long fence) {
        return Filters.and(Filters.eq("_id", id),
                Filters.or(Filters.exists("fence", false), Filters.lte("fence", fence)));
    }

    private void write(String id, long fence, Bson update) {
        try {
            coll().updateOne(fenced(id, fence), update, new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            if (e.getCode() == 11000) {
                throw new LockLostException("history for " + id + " was written by a newer lock holder; this run no longer owns it");
            }
            throw e;
        }
    }

    private List<Bson> common(Migration m, RunInfo run, Date now, String status) {
        List<Bson> u = new ArrayList<>();
        u.add(Updates.set("description", m.description()));
        u.add(Updates.set("kind", m.kind().name()));
        u.add(Updates.set("checksum", m.checksum()));
        u.add(Updates.set("status", status));
        u.add(Updates.set("buildVersion", run.target().buildVersion()));
        u.add(Updates.set("environment", run.target().environment()));
        u.add(Updates.set("database", run.target().database()));
        u.add(Updates.set("operator", run.target().operator()));
        u.add(Updates.set("runId", run.runId()));
        u.add(Updates.set("fence", run.fence()));
        u.add(Updates.set("updatedAt", now));
        return u;
    }

    public void markApplying(Migration m, RunInfo run, Date now) {
        List<Bson> u = common(m, run, now, "APPLYING");
        u.add(Updates.set("startedAt", now));
        u.add(Updates.unset("finishedAt"));
        u.add(Updates.unset("error"));
        u.add(Updates.inc("attempts", 1));
        write(m.id(), run.fence(), Updates.combine(u));
    }

    public void markApplied(Migration m, RunInfo run, Date now, String note, Document rollbackInfo, long durationMs,
                            boolean adopted) {
        List<Bson> u = common(m, run, now, "APPLIED");
        u.add(Updates.set("appliedAt", now));
        u.add(Updates.set("finishedAt", now));
        u.add(Updates.set("durationMs", durationMs));
        u.add(Updates.set("adopted", adopted));
        u.add(Updates.set("note", MigrationSanitizer.sanitize(note)));
        u.add(Updates.unset("error"));
        u.add(Updates.unset("blockers"));
        if (rollbackInfo != null) u.add(Updates.set("rollbackInfo", rollbackInfo));
        if (adopted) u.add(Updates.setOnInsert("attempts", 0));
        write(m.id(), run.fence(), Updates.combine(u));
    }

    public void markFailed(Migration m, RunInfo run, Date now, String error) {
        List<Bson> u = common(m, run, now, "FAILED");
        u.add(Updates.set("finishedAt", now));
        u.add(Updates.set("error", MigrationSanitizer.sanitize(error)));
        write(m.id(), run.fence(), Updates.combine(u));
    }

    public void markBlocked(Migration m, RunInfo run, Date now, List<String> blockers) {
        List<Bson> u = common(m, run, now, "BLOCKED");
        u.add(Updates.set("finishedAt", now));
        u.add(Updates.set("blockers", blockers.stream().map(MigrationSanitizer::sanitize).toList()));
        write(m.id(), run.fence(), Updates.combine(u));
    }
}
