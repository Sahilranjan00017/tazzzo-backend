package com.tazzzo.notification;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.springframework.stereotype.Component;

/**
 * Account deletion: every outbox row of the customer, whatever its state, is DELETED in the caller's transaction, so a
 * pending notification is never sent to an erased account.
 */
@Component
public class NotificationErasure {

    private final MongoDatabase db;

    public NotificationErasure(MongoDatabase db) {
        this.db = db;
    }

    public long erase(ClientSession session, String customerId) {
        return db.getCollection(NotificationOutbox.COLLECTION).deleteMany(session, Filters.eq("customer_id", customerId))
                .getDeletedCount();
    }
}
