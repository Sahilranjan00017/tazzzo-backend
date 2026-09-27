package com.tazzzo.auth.otp;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * PR-11B — the one-time login grant a successful OTP verification produces. This is the CONTRACT
 * PR-11C consumes: the client receives only the opaque {@code grantId} (never the phone number), and
 * {@link #consume} is the atomic, exactly-once seam PR-11C's session-creation flow will call to
 * obtain the underlying phone.
 *
 * <p><b>Deliberately not wired to any HTTP endpoint in this PR</b> — there is no public "consume
 * grant" route. Successful OTP verification means only "this phone has been challenge-verified and a
 * one-time login grant now exists"; it does NOT create a customer, a session, or an access token.
 * That transition belongs entirely to PR-11C.
 */
@Component
public class OtpVerifiedGrantRepository {

    public static final String COLLECTION = "customer_otp_verified_grants";

    private final MongoDatabase db;

    public OtpVerifiedGrantRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    /**
     * Executed as part of the caller's transaction (see {@code OtpService#verify}), together with
     * the challenge's ACTIVE-&gt;VERIFIED transition — durability hardening §1: either both writes
     * commit or neither does.
     *
     * @throws com.mongodb.MongoWriteException duplicate key — either {@code grantId} (astronomically
     *         unlikely to collide) or {@code challengeId} (the unique index added in the hardening
     *         pass: defense-in-depth against ever creating a second grant for one challenge). The
     *         caller disambiguates via {@link #findByChallengeId} rather than assuming success.
     */
    public Document insert(ClientSession session, String grantId, String challengeId, Phone phone,
                           OtpPurpose purpose, Instant now, Instant expiresAt) {
        Document doc = new Document("_id", grantId)
                .append("challengeId", challengeId)
                .append("phoneNormalized", phone.value())
                .append("purpose", purpose.name())
                .append("createdAt", now)
                .append("expiresAt", expiresAt)
                .append("consumedAt", null);
        collection().insertOne(session, doc);
        return doc;
    }

    /** Non-transactional convenience overload — used by tests and defense-in-depth checks. */
    public Document insert(String grantId, String challengeId, Phone phone, OtpPurpose purpose,
                           Instant now, Instant expiresAt) {
        Document doc = new Document("_id", grantId)
                .append("challengeId", challengeId)
                .append("phoneNormalized", phone.value())
                .append("purpose", purpose.name())
                .append("createdAt", now)
                .append("expiresAt", expiresAt)
                .append("consumedAt", null);
        collection().insertOne(doc);
        return doc;
    }

    /**
     * Durability hardening §4 — the lookup used to disambiguate a duplicate-key hit on
     * {@code challengeId}: is this the SAME logical grant (safe to treat as idempotent), or a
     * conflicting one (integrity violation)? See {@code OtpService#insertGrantOrReconcile}.
     */
    public Document findByChallengeId(ClientSession session, String challengeId) {
        return collection().find(session, Filters.eq("challengeId", challengeId)).first();
    }

    /**
     * Atomically consumes an unexpired, not-yet-consumed grant for the given purpose, returning the
     * document (which carries {@code phoneNormalized}) on success or {@code null} if the grant is
     * unknown, already consumed, expired, or issued for a different purpose — all of which PR-11C
     * must treat as the SAME generic failure so a caller cannot enumerate grant state.
     *
     * <p>PR-11C: run as the FIRST write inside the session-establishment transaction (see
     * {@code CustomerSessionService#establishSession}) — a {@code null} result here means nothing
     * has been written yet in that transaction, so the caller may simply throw to abort with no
     * rollback burden (mirrors the PR-11B lesson: only a write AFTER this one would need the
     * throw-inside-the-callback discipline).
     */
    public Document consume(ClientSession session, String grantId, OtpPurpose purpose, Instant now) {
        return collection().findOneAndUpdate(session,
                Filters.and(Filters.eq("_id", grantId), Filters.eq("purpose", purpose.name()),
                        Filters.eq("consumedAt", null), Filters.gt("expiresAt", now)),
                Updates.set("consumedAt", now),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /**
     * Non-transactional convenience overload — used by tests seeding/consuming a grant directly
     * without a live transaction.
     *
     * <p>Intentionally package-private in spirit (public only because this repository style has no
     * narrower Java visibility across the {@code auth.otp} package boundary) — PR-11B/11C expose no
     * public HTTP surface for this operation.
     */
    public Document consume(String grantId, OtpPurpose purpose, Instant now) {
        return collection().findOneAndUpdate(
                Filters.and(Filters.eq("_id", grantId), Filters.eq("purpose", purpose.name()),
                        Filters.eq("consumedAt", null), Filters.gt("expiresAt", now)),
                Updates.set("consumedAt", now),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }
}
