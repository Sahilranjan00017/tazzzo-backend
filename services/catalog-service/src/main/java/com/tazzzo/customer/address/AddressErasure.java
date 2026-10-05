package com.tazzzo.customer.address;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.springframework.stereotype.Component;

/** Account deletion: every saved address and the per-customer address-state row are deleted in the caller's transaction. */
@Component
public class AddressErasure {

    private final MongoDatabase db;

    public AddressErasure(MongoDatabase db) {
        this.db = db;
    }

    /** @return addresses deleted (the state row is deleted alongside) */
    public long erase(ClientSession session, String customerId) {
        long addresses = db.getCollection(AddressRepository.COLLECTION)
                .deleteMany(session, Filters.eq("customerId", customerId)).getDeletedCount();
        db.getCollection(CustomerAddressStateRepository.COLLECTION).deleteOne(session, Filters.eq("_id", customerId));
        return addresses;
    }
}
