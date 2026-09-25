package com.tazzzo.common.audit;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.time.Clock;
import java.util.Date;
import java.util.Objects;

/**
 * Appends {@link DomainEvent}s to the neutral {@code domain_events} collection.
 *
 * <p>Callers MUST preserve the C-3 discipline themselves: append the event FIRST, then perform
 * the state write, inside the SAME transaction/session — a failed state write then rolls the
 * event back, exactly like the product rail. {@code product_events} and its {@code WritePath}
 * remain untouched; product-owned domains keep using them. (Documented LOW debt: unlike C-4's
 * compile-time EventPayload parameter, this discipline is not compiler-enforced — acceptable at
 * one caller; add a rail if adopters multiply.)
 *
 * <p><b>Event identity (PR-06 review, STEP 6 decision):</b> the Mongo {@code _id} of each
 * {@code domain_events} row IS the event id — deliberate; no UUID infrastructure until a consumer
 * (outbox/replication) actually needs portable ids. Rows are insert-only and never updated.
 */
public class DomainAudit {

    public static final String COLLECTION = "domain_events";

    private final MongoDatabase db;
    private final Clock clock;

    public DomainAudit(MongoDatabase db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Event-before-state: call this before the state mutation, same session. */
    public void append(ClientSession session, DomainEvent event) {
        db.getCollection(COLLECTION).insertOne(session,
                new Document("aggregate_type", event.aggregateType())
                        .append("aggregate_id", event.aggregateId())
                        .append("type", event.type())
                        .append("detail", new Document(event.detail()))
                        .append("at", Date.from(clock.instant())));
    }
}
