package com.tazzzo.customer.checkout;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.springframework.stereotype.Component;

/**
 * Account deletion: the customer's checkout quotes are deleted in the caller's transaction. A quote is a short-lived
 * pre-order snapshot (address id, lines, money); the durable commercial record is the order, which is anonymised instead.
 */
@Component
public class CheckoutQuoteErasure {

    private final MongoDatabase db;

    public CheckoutQuoteErasure(MongoDatabase db) {
        this.db = db;
    }

    public long erase(ClientSession session, String customerId) {
        return db.getCollection(CheckoutQuoteRepository.COLLECTION)
                .deleteMany(session, Filters.eq("customerId", customerId)).getDeletedCount();
    }
}
