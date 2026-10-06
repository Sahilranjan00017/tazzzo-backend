package com.tazzzo.support;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** {@code support_cases}: one document per case, messages embedded and bounded ({@link SupportCase#MAX_MESSAGES}). */
@Component
public class SupportCaseRepository {

    public static final String COLLECTION = "support_cases";

    private final MongoDatabase db;

    public SupportCaseRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> c() {
        return db.getCollection(COLLECTION);
    }

    public void insert(ClientSession session, SupportCase s) {
        List<Document> msgs = new ArrayList<>();
        for (SupportCase.Message m : s.messages()) {
            msgs.add(message(m));
        }
        Document d = new Document("_id", s.caseId()).append("customerId", s.customerId()).append("category", s.category().name())
                .append("subject", s.subject()).append("status", s.status().name()).append("messages", msgs)
                .append("messageCount", msgs.size()).append("version", s.version())
                .append("createdAt", Date.from(s.createdAt())).append("updatedAt", Date.from(s.updatedAt()));
        if (s.orderId() != null) d.append("orderId", s.orderId());
        c().insertOne(session, d);
    }

    public Document findById(String id) {
        return c().find(Filters.eq("_id", id)).first();
    }

    public Document findOwned(String id, String customerId) {
        return c().find(Filters.and(Filters.eq("_id", id), Filters.eq("customerId", customerId))).first();
    }

    public long countOpen(String customerId) {
        return c().countDocuments(Filters.and(Filters.eq("customerId", customerId), Filters.in("status", "OPEN", "IN_PROGRESS")));
    }

    /** Newest-updated first, keyset by (updatedAt, _id). {@code customerId} and/or {@code status} optional. */
    public List<Document> page(String customerId, String status, Instant beforeAt, String beforeId, int limit) {
        List<Bson> f = new ArrayList<>();
        if (customerId != null) f.add(Filters.eq("customerId", customerId));
        if (status != null) f.add(Filters.eq("status", status));
        if (beforeAt != null) {
            f.add(Filters.or(Filters.lt("updatedAt", Date.from(beforeAt)),
                    Filters.and(Filters.eq("updatedAt", Date.from(beforeAt)), Filters.lt("_id", beforeId))));
        }
        return c().find(f.isEmpty() ? new Document() : Filters.and(f)).sort(new Document("updatedAt", -1).append("_id", -1))
                .limit(limit).into(new ArrayList<>());
    }

    /**
     * Append one message. {@code allowedStatuses} guard the transition; the count guard keeps the thread bounded;
     * {@code newStatus} (nullable) is set in the same atomic update. Returns true iff applied.
     */
    public boolean appendMessage(ClientSession session, String id, String customerIdOrNull, List<String> allowedStatuses, SupportCase.Message m,
                                 String newStatus, Instant now) {
        List<Bson> f = new ArrayList<>(List.of(Filters.eq("_id", id), Filters.in("status", allowedStatuses),
                Filters.lt("messageCount", SupportCase.MAX_MESSAGES), Filters.eq("messageCount", m.id() - 1)));
        if (customerIdOrNull != null) f.add(Filters.eq("customerId", customerIdOrNull));
        List<Bson> u = new ArrayList<>(List.of(Updates.push("messages", message(m)), Updates.inc("messageCount", 1),
                Updates.inc("version", 1L), Updates.set("updatedAt", Date.from(now))));
        if (newStatus != null) u.add(Updates.set("status", newStatus));
        return c().updateOne(session, Filters.and(f), Updates.combine(u)).getModifiedCount() == 1;
    }

    /** CAS status/assignment change. Returns true iff applied. */
    public boolean transition(ClientSession session, String id, long expectedVersion, List<String> fromStatuses, String toStatus, String assignedTo,
                              Instant now) {
        List<Bson> u = new ArrayList<>(List.of(Updates.set("status", toStatus), Updates.inc("version", 1L),
                Updates.set("updatedAt", Date.from(now))));
        if (assignedTo != null) u.add(Updates.set("assignedTo", assignedTo));
        return c().updateOne(session, Filters.and(Filters.eq("_id", id), Filters.eq("version", expectedVersion),
                Filters.in("status", fromStatuses)), Updates.combine(u)).getModifiedCount() == 1;
    }

    public boolean customerClose(ClientSession session, String id, String customerId, Instant now) {
        return c().updateOne(session, Filters.and(Filters.eq("_id", id), Filters.eq("customerId", customerId),
                Filters.in("status", "OPEN", "IN_PROGRESS", "RESOLVED")),
                Updates.combine(Updates.set("status", "CLOSED"), Updates.inc("version", 1L), Updates.set("updatedAt", Date.from(now))))
                .getModifiedCount() == 1;
    }

    /** Account erasure: the free text is personal data, so the customer's cases are deleted. */
    public long deleteForCustomer(ClientSession session, String customerId) {
        return c().deleteMany(session, Filters.eq("customerId", customerId)).getDeletedCount();
    }

    private static Document message(SupportCase.Message m) {
        Document d = new Document("id", m.id()).append("author", m.author().name()).append("text", m.text()).append("at", Date.from(m.at()));
        if (m.staffId() != null) d.append("staffId", m.staffId());
        return d;
    }

    static SupportCase toCase(Document d) {
        List<SupportCase.Message> msgs = new ArrayList<>();
        for (Document m : d.getList("messages", Document.class)) {
            msgs.add(new SupportCase.Message(m.getInteger("id"), SupportCase.Author.valueOf(m.getString("author")),
                    m.getString("staffId"), m.getString("text"), m.getDate("at").toInstant()));
        }
        return new SupportCase(d.getString("_id"), d.getString("customerId"), SupportCase.Category.valueOf(d.getString("category")),
                d.getString("orderId"), d.getString("subject"), SupportCase.Status.valueOf(d.getString("status")), msgs,
                d.getString("assignedTo"), ((Number) d.get("version")).longValue(), d.getDate("createdAt").toInstant(),
                d.getDate("updatedAt").toInstant());
    }
}
