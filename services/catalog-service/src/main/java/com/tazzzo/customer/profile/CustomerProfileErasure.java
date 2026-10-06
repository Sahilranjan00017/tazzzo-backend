package com.tazzzo.customer.profile;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.springframework.stereotype.Component;

/** Account deletion: the profile row (display name, email) is deleted in the caller's transaction. Idempotent. */
@Component
public class CustomerProfileErasure {

    private final MongoDatabase db;

    public CustomerProfileErasure(MongoDatabase db) {
        this.db = db;
    }

    public long erase(ClientSession session, String customerId) {
        return db.getCollection(CustomerProfileRepository.COLLECTION)
                .deleteOne(session, Filters.eq("_id", customerId)).getDeletedCount();
    }
}
