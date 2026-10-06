package com.tazzzo.customer.cart;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.springframework.stereotype.Component;

/** Account deletion: the customer's cart (intent only, no PII, but customer-linked) is deleted in the caller's transaction. */
@Component
public class CartErasure {

    private final MongoDatabase db;

    public CartErasure(MongoDatabase db) {
        this.db = db;
    }

    public long erase(ClientSession session, String customerId) {
        return db.getCollection(CartRepository.COLLECTION).deleteOne(session, Filters.eq("_id", customerId)).getDeletedCount();
    }
}
