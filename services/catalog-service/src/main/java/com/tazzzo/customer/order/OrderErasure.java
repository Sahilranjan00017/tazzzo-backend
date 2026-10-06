package com.tazzzo.customer.order;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

/**
 * Account deletion: orders are RETAINED as commercial records (what was sold, for how much, when, to which postal area)
 * but every personal datum in the address snapshot is replaced. Kept: the opaque customer id (orders are keyed by it),
 * label, city, state and postal code (coarse fulfilment geography, not identifying on their own). Replaced with
 * {@value #REDACTED}: recipient name, recipient phone, address line 1. Removed: address line 2, landmark, coordinates.
 * The result still satisfies {@link OrderAddressSnapshot}'s invariants, so every existing read of the order keeps working.
 * Idempotent: a second run matches the same rows and writes the same values.
 */
@Component
public class OrderErasure {

    public static final String REDACTED = "[deleted]";
    public static final String ERASED_AT = "customerDataErasedAt";

    private final MongoDatabase db;

    public OrderErasure(MongoDatabase db) {
        this.db = db;
    }

    /** @return orders whose snapshot was anonymised by this call */
    public long anonymise(ClientSession session, String customerId, Instant now) {
        return db.getCollection(OrderRepository.COLLECTION).updateMany(session,
                Filters.and(Filters.eq("customerId", customerId), Filters.exists(ERASED_AT, false)),
                Updates.combine(
                        Updates.set("addressSnapshot.recipientName", REDACTED),
                        Updates.set("addressSnapshot.recipientPhone", REDACTED),
                        Updates.set("addressSnapshot.addressLine1", REDACTED),
                        Updates.set("addressSnapshot.addressLine2", null),
                        Updates.set("addressSnapshot.landmark", null),
                        Updates.set("addressSnapshot.latitude", null),
                        Updates.set("addressSnapshot.longitude", null),
                        Updates.set(ERASED_AT, Date.from(now)),
                        Updates.set("updatedAt", Date.from(now)))).getModifiedCount();
    }
}
