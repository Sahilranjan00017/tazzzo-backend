package com.tazzzo.membership;

import com.mongodb.ReadPreference;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.common.money.Currency;
import com.tazzzo.common.money.Money;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * PR-16A-1 — {@code memberships}, one document per Membership term, {@code _id} the opaque
 * {@code MBR_...} id. Two unique indexes (see {@code SchemaBootstrap}) make the invariants structural:
 * {@code membership_one_open_per_customer} (partial on {@code openTerm: true}) and
 * {@code membership_one_per_grant_reference}.
 *
 * <p><b>{@code openTerm} encoding.</b> {@code ACTIVE} rows carry the BSON boolean {@code true};
 * {@code EXPIRED} rows have the field ABSENT (the expiry CAS {@code $unset}s it) — never
 * {@code false}/{@code null}, which would fall outside the partial filter and silently bypass the
 * one-open-term guarantee. Reconstruction rejects every other encoding. Every open-term query includes
 * the literal {@code openTerm: true} so the planner can use the partial index.
 *
 * <p><b>Primary reads.</b> The non-session reads (duplicate-key recovery, and the future standalone
 * entitlement read) go through {@link #primaryReads()}, pinned to {@link ReadPreference#primary()}:
 * recovery is a proof from durable committed rows, and a secondary could be stale and turn a legitimate
 * winner into a false "no proof". Global Mongo read/write settings are deliberately untouched;
 * session-bound reads follow the transaction.
 *
 * <p>Strict reconstruction: no default for a missing field, no compatibility shim, no normalisation.
 * A corrupt row surfaces as {@code INTEGRITY_FAILURE}, never a raw exception and never a leaked value.
 */
@Component
public class MembershipRepository {

    public static final String COLLECTION = "memberships";

    private static final Logger log = LoggerFactory.getLogger(MembershipRepository.class);

    private final MongoDatabase db;

    public MembershipRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    /** The read handle for every non-transactional read: explicitly pinned to the primary. */
    MongoCollection<Document> primaryReads() {
        return collection().withReadPreference(ReadPreference.primary());
    }

    public Optional<Membership> findByGrantReference(MembershipGrantReference reference) {
        return reconstruct(primaryReads().find(byGrantReference(reference)).first());
    }

    public Optional<Membership> findByGrantReference(ClientSession session, MembershipGrantReference reference) {
        return reconstruct(collection().find(session, byGrantReference(reference)).first());
    }

    public Optional<Membership> findOpenByCustomer(CustomerId customerId) {
        return reconstruct(primaryReads().find(openTermOf(customerId)).first());
    }

    public Optional<Membership> findOpenByCustomer(ClientSession session, CustomerId customerId) {
        return reconstruct(collection().find(session, openTermOf(customerId)).first());
    }

    public void insert(ClientSession session, Membership membership) {
        collection().insertOne(session, toDocument(membership));
    }

    /**
     * CAS ACTIVE -> EXPIRED. The filter pins {@code _id}, {@code status}, the expected {@code version}
     * AND {@code validUntil <= now}, so expiry can never be persisted before the window ends (even
     * under multi-node clock skew). Unsets {@code openTerm}, increments the version.
     *
     * @return true iff it applied
     */
    public boolean expireIfDue(ClientSession session, MembershipId id, long expectedVersion, Instant now) {
        UpdateResult r = collection().updateOne(session,
                Filters.and(Filters.eq("_id", id.value()), Filters.eq("status", MembershipStatus.ACTIVE.name()),
                        Filters.eq("version", expectedVersion), Filters.lte("validUntil", Date.from(now))),
                Updates.combine(Updates.set("status", MembershipStatus.EXPIRED.name()),
                        Updates.set("version", expectedVersion + 1), Updates.set("updatedAt", Date.from(now)),
                        Updates.unset("openTerm")));
        return r.getModifiedCount() == 1;
    }

    private static org.bson.conversions.Bson byGrantReference(MembershipGrantReference reference) {
        return Filters.and(Filters.eq("grantSource", reference.source().name()),
                Filters.eq("grantRef", reference.reference()));
    }

    private static org.bson.conversions.Bson openTermOf(CustomerId customerId) {
        return Filters.and(Filters.eq("customerId", customerId.value()), Filters.eq("openTerm", true));
    }

    private static Optional<Membership> reconstruct(Document d) {
        return d == null ? Optional.empty() : Optional.of(toMembership(d));
    }

    static Document toDocument(Membership m) {
        Document d = new Document("_id", m.membershipId().value()).append("customerId", m.customerId().value())
                .append("status", m.status().name());
        if (m.status() == MembershipStatus.ACTIVE) {
            d.append("openTerm", true); // present ONLY while ACTIVE; absent (never false/null) when terminal
        }
        return d.append("version", m.version())
                .append("grantSource", m.grantReference().source().name())
                .append("grantRef", m.grantReference().reference())
                .append("planId", m.planId()).append("planVersion", m.planVersion())
                .append("planPricePaise", m.planPrice().paise()).append("planCurrency", m.planPrice().currency().name())
                .append("planPeriodMonths", m.planPeriodMonths()).append("billingZoneId", m.billingZoneId())
                .append("periodCount", m.periodCount())
                .append("validFrom", Date.from(m.validFrom())).append("validUntil", Date.from(m.validUntil()))
                .append("createdAt", Date.from(m.createdAt())).append("updatedAt", Date.from(m.updatedAt()));
    }

    /** Strict reconstruction: ANY defect becomes {@code INTEGRITY_FAILURE}; nothing is defaulted. */
    static Membership toMembership(Document d) {
        try {
            MembershipStatus status = MembershipStatus.valueOf(requireString(d, "status"));
            checkOpenTerm(d, status);
            return new Membership(new MembershipId(requireString(d, "_id")),
                    new CustomerId(requireString(d, "customerId")), status, requireIntegral(d, "version"),
                    new MembershipGrantReference(GrantSource.valueOf(requireString(d, "grantSource")),
                            requireString(d, "grantRef")),
                    requireString(d, "planId"), Math.toIntExact(requireIntegral(d, "planVersion")),
                    new Money(requireIntegral(d, "planPricePaise"), Currency.valueOf(requireString(d, "planCurrency"))),
                    Math.toIntExact(requireIntegral(d, "planPeriodMonths")), requireString(d, "billingZoneId"),
                    requireIntegral(d, "periodCount"), requireInstant(d, "validFrom"),
                    requireInstant(d, "validUntil"), requireInstant(d, "createdAt"), requireInstant(d, "updatedAt"));
        } catch (RuntimeException e) {
            log.error("membership_document_corrupt type={}", e.getClass().getSimpleName());
            throw new MembershipFailure(MembershipFailure.Reason.INTEGRITY_FAILURE,
                    "corrupt membership document");
        }
    }

    private static void checkOpenTerm(Document d, MembershipStatus status) {
        if (status == MembershipStatus.ACTIVE) {
            if (!(d.get("openTerm") instanceof Boolean b) || !b) {
                throw new IllegalStateException("ACTIVE membership must carry openTerm=true");
            }
        } else if (d.containsKey("openTerm")) {
            throw new IllegalStateException("terminal membership must not carry openTerm");
        }
    }

    private static String requireString(Document d, String field) {
        if (!(d.get(field) instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("membership document missing/invalid required field: " + field);
        }
        return s;
    }

    /** Integral BSON numbers only (int32/int64): a double or string is corruption, never coerced. */
    private static long requireIntegral(Document d, String field) {
        Object v = d.get(field);
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Long l) {
            return l;
        }
        throw new IllegalStateException("membership document missing/invalid required field: " + field);
    }

    private static Instant requireInstant(Document d, String field) {
        if (!(d.get(field) instanceof Date date)) {
            throw new IllegalStateException("membership document missing/invalid required field: " + field);
        }
        return date.toInstant();
    }
}
