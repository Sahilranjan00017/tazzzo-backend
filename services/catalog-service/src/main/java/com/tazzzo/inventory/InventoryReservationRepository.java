package com.tazzzo.inventory;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * PR-14A — {@code inventory_reservations}. Headers are NEVER deleted (no Mongo TTL): an expired or
 * released reservation must stay inspectable, distinguishable from one that never existed. The
 * unique {@code orderId} index (see {@code SchemaBootstrap}) is the ONE-reservation-per-order
 * invariant AND the concurrent-create race guard.
 */
@Component
public class InventoryReservationRepository {

    public static final String COLLECTION = "inventory_reservations";

    private final MongoDatabase db;

    public InventoryReservationRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    public Document findByOrderId(ClientSession session, String orderId) {
        return collection().find(session, Filters.eq("orderId", orderId)).first();
    }

    public Document findByOrderId(String orderId) {
        return collection().find(Filters.eq("orderId", orderId)).first();
    }

    public Document findById(ClientSession session, String reservationId) {
        return collection().find(session, Filters.eq("_id", reservationId)).first();
    }

    public Document findById(String reservationId) {
        return collection().find(Filters.eq("_id", reservationId)).first();
    }

    /** Bounded batch of RESERVED headers past their expiry, oldest first — never unbounded. */
    public List<Document> findExpiredBatch(Instant now, int limit) {
        List<Document> out = new ArrayList<>();
        collection().find(Filters.and(Filters.eq("status", InventoryReservationStatus.RESERVED.name()),
                        Filters.lte("expiresAt", Date.from(now))))
                .sort(new Document("expiresAt", 1)).limit(limit).into(out);
        return out;
    }

    /** {@code fingerprint} is the idempotency authority (see {@code InventoryReservationService});
     *  it lives only in the persisted document, never in the {@link InventoryReservation} domain
     *  type a future Order consumes — the same split {@code CheckoutQuoteRepository} already uses
     *  for its own idempotency digest/fingerprint. */
    public void insert(ClientSession session, InventoryReservation reservation, String fingerprint) {
        collection().insertOne(session, toDocument(reservation).append("fingerprint", fingerprint));
    }

    /** CAS: only transitions FROM the exact expected status. @return true iff it applied. */
    public boolean transitionStatus(ClientSession session, String reservationId,
                                    InventoryReservationStatus from, InventoryReservationStatus to, Instant now) {
        UpdateResult r = collection().updateOne(session,
                Filters.and(Filters.eq("_id", reservationId), Filters.eq("status", from.name())),
                Updates.combine(Updates.set("status", to.name()), Updates.set("updatedAt", Date.from(now))));
        return r.getModifiedCount() > 0;
    }

    static Document toDocument(InventoryReservation r) {
        List<Document> items = new ArrayList<>(r.items().size());
        for (InventoryReservationItem i : r.items()) {
            items.add(new Document("skuId", i.skuId()).append("quantity", i.quantity()));
        }
        return new Document("_id", r.reservationId()).append("orderId", r.orderId())
                .append("fulfillmentLocationId", r.fulfillmentLocationId()).append("items", items)
                .append("status", r.status().name()).append("createdAt", Date.from(r.createdAt()))
                .append("expiresAt", Date.from(r.expiresAt())).append("updatedAt", Date.from(r.updatedAt()));
    }

    /**
     * PR-14A — no fallback for a malformed/legacy row: fails loud, the same convention
     * {@code CheckoutQuoteRepository.toQuote} already follows. This is an internal domain type with
     * no customer HTTP endpoint in this PR, so a caller maps the resulting exception however its
     * own boundary requires (a future Order-facing surface would map it the way Checkout maps its
     * own corrupt-quote case: a safe 500, never a leaked value).
     */
    static InventoryReservation toReservation(Document d) {
        List<InventoryReservationItem> items = new ArrayList<>();
        for (Document i : d.getList("items", Document.class)) {
            items.add(new InventoryReservationItem(i.getString("skuId"), i.get("quantity", Number.class).longValue()));
        }
        return new InventoryReservation(d.getString("_id"), d.getString("orderId"),
                d.getString("fulfillmentLocationId"), List.copyOf(items),
                InventoryReservationStatus.valueOf(d.getString("status")), d.getDate("createdAt").toInstant(),
                d.getDate("expiresAt").toInstant(), d.getDate("updatedAt").toInstant());
    }
}
