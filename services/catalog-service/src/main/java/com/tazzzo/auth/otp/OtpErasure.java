package com.tazzzo.auth.otp;

import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.MongoDatabase;
import org.springframework.stereotype.Component;

/**
 * Account deletion: the phone-keyed OTP rows (challenges and verified-but-unconsumed grants) of the deleted phone are
 * removed in the caller's transaction. They would expire by TTL anyway; removing them also closes the window in which
 * an in-flight login grant for that phone could still be consumed.
 */
@Component
public class OtpErasure {

    private final MongoDatabase db;

    public OtpErasure(MongoDatabase db) {
        this.db = db;
    }

    /** @return challenges + grants removed */
    public long purgeForPhone(ClientSession session, String phoneNormalized) {
        long challenges = db.getCollection(OtpChallengeRepository.COLLECTION)
                .deleteMany(session, Filters.eq("phoneNormalized", phoneNormalized)).getDeletedCount();
        long grants = db.getCollection(OtpVerifiedGrantRepository.COLLECTION)
                .deleteMany(session, Filters.eq("phoneNormalized", phoneNormalized)).getDeletedCount();
        return challenges + grants;
    }
}
