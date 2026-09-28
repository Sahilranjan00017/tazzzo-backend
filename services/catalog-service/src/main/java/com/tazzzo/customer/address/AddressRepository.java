package com.tazzzo.customer.address;

import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * PR-12B — {@code customer_addresses}, one document per saved address, {@code _id} the opaque
 * {@code ADDR_*} id. Every lookup/update/delete filters on BOTH {@code _id} AND {@code customerId}
 * — never {@code _id} alone in a customer-facing path — so a foreign address can never be reached
 * by guessing/enumerating an id (IDOR closed by construction, never by an afterthought check).
 *
 * <p>Deliberately does NOT store {@code isDefault} — see {@link CustomerAddressStateRepository}'s
 * class-level rationale.
 */
@Component
public class AddressRepository {

    public static final String COLLECTION = "customer_addresses";

    private final MongoDatabase db;

    public AddressRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    public void insert(ClientSession session, Document address) {
        collection().insertOne(session, address);
    }

    /** Ownership-scoped lookup -- the ONLY way this repository ever looks up one address. */
    public Document findOwnedById(ClientSession session, String customerId, String addressId) {
        return collection().find(session, ownedFilter(customerId, addressId)).first();
    }

    public Document findOwnedById(String customerId, String addressId) {
        return collection().find(ownedFilter(customerId, addressId)).first();
    }

    public List<Document> findAllByCustomer(String customerId) {
        return drain(collection().find(Filters.eq("customerId", customerId))
                .sort(Sorts.orderBy(Sorts.descending("updatedAt"), Sorts.ascending("_id"))));
    }

    public List<Document> findAllByCustomer(ClientSession session, String customerId) {
        return drain(collection().find(session, Filters.eq("customerId", customerId))
                .sort(Sorts.orderBy(Sorts.descending("updatedAt"), Sorts.ascending("_id"))));
    }

    private static List<Document> drain(FindIterable<Document> cursor) {
        List<Document> out = new ArrayList<>();
        for (Document d : cursor) {
            out.add(d);
        }
        return out;
    }

    /** @return the document AFTER the update, or {@code null} if {@code expectedVersion} no longer
     *          matches (defense-in-depth -- the caller already confirmed ownership/version with a
     *          fresh read earlier in the SAME transaction). Never upserts: PATCH never creates. */
    public Document patch(ClientSession session, String customerId, String addressId, long expectedVersion,
                          PatchField<AddressLabel> label, PatchField<String> recipientName,
                          PatchField<String> recipientPhone, PatchField<String> addressLine1,
                          PatchField<String> addressLine2, PatchField<String> landmark, PatchField<String> city,
                          PatchField<String> state, PatchField<String> postalCode,
                          PatchField<Coordinates.Pair> coordinates, Instant now) {
        List<Bson> updates = new ArrayList<>();
        if (label.isPresent()) updates.add(Updates.set("label", label.value().name()));
        if (recipientName.isPresent()) updates.add(Updates.set("recipientName", recipientName.value()));
        if (recipientPhone.isPresent()) updates.add(Updates.set("recipientPhone", recipientPhone.value()));
        if (addressLine1.isPresent()) updates.add(Updates.set("addressLine1", addressLine1.value()));
        if (addressLine2.isPresent()) updates.add(Updates.set("addressLine2", addressLine2.value()));
        if (landmark.isPresent()) updates.add(Updates.set("landmark", landmark.value()));
        if (city.isPresent()) updates.add(Updates.set("city", city.value()));
        if (state.isPresent()) updates.add(Updates.set("state", state.value()));
        if (postalCode.isPresent()) updates.add(Updates.set("postalCode", postalCode.value()));
        if (coordinates.isPresent()) {
            updates.add(Updates.set("latitude", coordinates.value().latitude()));
            updates.add(Updates.set("longitude", coordinates.value().longitude()));
        }
        updates.add(Updates.set("updatedAt", Date.from(now)));
        updates.add(Updates.inc("version", 1L));

        return collection().findOneAndUpdate(session,
                Filters.and(ownedFilter(customerId, addressId), Filters.eq("version", expectedVersion)),
                Updates.combine(updates),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /** @return {@code true} if a document was actually deleted. Defense-in-depth version filter,
     *          same rationale as {@link #patch}. */
    public boolean deleteOwned(ClientSession session, String customerId, String addressId, long expectedVersion) {
        long deleted = collection().deleteOne(session,
                Filters.and(ownedFilter(customerId, addressId), Filters.eq("version", expectedVersion)))
                .getDeletedCount();
        return deleted > 0;
    }

    private static Bson ownedFilter(String customerId, String addressId) {
        return Filters.and(Filters.eq("_id", addressId), Filters.eq("customerId", customerId));
    }
}
