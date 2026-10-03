package com.tazzzo.admin.audit;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.ActorDocuments;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Read-only access to the attributed audit events of the persisted ledgers ({@link AuditSource}). It never writes.
 *
 * <p><b>Query shape (fixed).</b> Per ledger: {@code actor} is a document (attributed events only) AND the validated equality
 * / range filters AND the cursor position, sorted {@code at DESC, _id DESC}, limited to {@code limit + 1}, projected to the
 * allowlisted fields. The per-ledger results are merged in the total order (at DESC, source rank ASC, _id DESC) and cut at
 * {@code limit}; the extra row only tells whether a next page exists. Keyset (not offset) paging: identical timestamps never
 * duplicate or skip a row, and events committed after page 1 (newer {@code at}) sort BEFORE the cursor, so they never
 * shift later pages.
 */
@Component
public class AuditEventReader {

    static final long MAX_TIME_MS = 5_000;
    static final String ACTOR_TYPE = ActorDocuments.FIELD + ".type";
    static final String ACTOR_ID = ActorDocuments.FIELD + ".id";
    static final String ACTOR_REQUEST_ID = ActorDocuments.FIELD + ".request_id";

    /** The planned query against one ledger: exposed so tests can explain() exactly what is executed. */
    public record LedgerQuery(AuditSource source, Bson filter, Bson sort, int limit, Bson projection) { }

    private static final Comparator<Row> ORDER = Comparator.comparingLong((Row r) -> r.atMillis).reversed()
            .thenComparingInt(r -> r.source.rank)
            .thenComparing((Row r) -> r.id, Comparator.reverseOrder());

    private record Row(long atMillis, AuditSource source, ObjectId id, AuditEventRecord record) { }

    private final MongoDatabase db;

    public AuditEventReader(MongoDatabase db) {
        this.db = db;
    }

    public AuditEventPage read(AuditEventQuery query) {
        List<Row> rows = new ArrayList<>();
        for (LedgerQuery lq : plan(query)) {
            for (Document d : db.getCollection(lq.source().collection).find(lq.filter()).projection(lq.projection())
                    .sort(lq.sort()).limit(lq.limit()).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)) {
                rows.add(project(lq.source(), d));
            }
        }
        rows.sort(ORDER);
        boolean more = rows.size() > query.limit();
        List<Row> page = more ? rows.subList(0, query.limit()) : rows;
        Optional<String> next = Optional.empty();
        if (more) {
            Row last = page.get(page.size() - 1);
            next = Optional.of(new AuditCursor(last.atMillis, last.source, last.id, AuditCursor.fingerprint(query)).encode());
        }
        return new AuditEventPage(page.stream().map(Row::record).toList(), next);
    }

    /** The per-ledger queries for {@code query}; ledgers that cannot match (target type) are not queried at all. */
    public List<LedgerQuery> plan(AuditEventQuery query) {
        Optional<AuditCursor> cursor = query.cursor().map(c -> AuditCursor.decode(c, query));
        List<LedgerQuery> out = new ArrayList<>();
        for (AuditSource source : AuditSource.values()) {
            if (query.targetType().isPresent() && !source.admitsTargetType(query.targetType().get())) {
                continue;
            }
            List<Bson> and = new ArrayList<>();
            and.add(attributed());
            query.actorType().ifPresent(t -> and.add(Filters.eq(ACTOR_TYPE, t.name())));
            query.actorId().ifPresent(id -> and.add(Filters.eq(ACTOR_ID, id)));
            query.requestId().ifPresent(id -> and.add(Filters.eq(ACTOR_REQUEST_ID, id)));
            query.action().ifPresent(a -> and.add(Filters.eq(source.actionField, a)));
            if (source.fixedTargetType == null) {
                query.targetType().ifPresent(t -> and.add(Filters.eq(AuditSource.DOMAIN_TARGET_TYPE_FIELD, t)));
            }
            query.targetId().ifPresent(id -> and.add(Filters.eq(source.targetIdField, id)));
            query.from().ifPresent(f -> and.add(Filters.gte("at", Date.from(f))));
            query.to().ifPresent(t -> and.add(Filters.lte("at", Date.from(t))));
            cursor.ifPresent(c -> and.add(after(source, c)));
            out.add(new LedgerQuery(source, Filters.and(and), Sorts.descending("at", "_id"), query.limit() + 1,
                    projection(source)));
        }
        return out;
    }

    /** Matches the partial filter of the audit-read indexes, so the planner may use them. */
    public static Bson attributed() {
        return Filters.type(ActorDocuments.FIELD, "object");
    }

    /** Strictly after the cursor in (at DESC, rank ASC, _id DESC). */
    private static Bson after(AuditSource source, AuditCursor c) {
        Date at = new Date(c.atMillis());
        if (source.rank > c.source().rank) {
            return Filters.lte("at", at);
        }
        if (source.rank < c.source().rank) {
            return Filters.lt("at", at);
        }
        return Filters.or(Filters.lt("at", at), Filters.and(Filters.eq("at", at), Filters.lt("_id", c.id())));
    }

    private static Bson projection(AuditSource source) {
        List<String> fields = new ArrayList<>(List.of("_id", "at", source.actionField, source.targetIdField,
                ACTOR_TYPE, ACTOR_ID, ActorDocuments.FIELD + ".credential_id", ACTOR_REQUEST_ID));
        if (source.fixedTargetType == null) {
            fields.add(AuditSource.DOMAIN_TARGET_TYPE_FIELD);
        }
        return Projections.include(fields);
    }

    private static Row project(AuditSource source, Document d) {
        try {
            if (!(d.get("_id") instanceof ObjectId id) || !(d.get("at") instanceof Date at)) {
                throw new AuditRecordCorrupt(source.collection);
            }
            Actor actor = ActorDocuments.fromEvent(d).orElseThrow(() -> new AuditRecordCorrupt(source.collection));
            String action = requireString(d, source.actionField, source);
            String targetType = source.fixedTargetType != null ? source.fixedTargetType
                    : requireString(d, AuditSource.DOMAIN_TARGET_TYPE_FIELD, source);
            String targetId = requireString(d, source.targetIdField, source);
            AuditEventRecord record = new AuditEventRecord(source.code + "_" + id.toHexString(), at.toInstant(), action,
                    targetType, targetId, actor.type(), actor.id(), actor.credentialId(), actor.requestId());
            return new Row(at.getTime(), source, id, record);
        } catch (IllegalArgumentException e) {
            // A malformed persisted actor is a data fault, not a client error: never let it become a 400.
            throw new AuditRecordCorrupt(source.collection);
        }
    }

    private static String requireString(Document d, String field, AuditSource source) {
        if (!(d.get(field) instanceof String s) || s.isBlank()) {
            throw new AuditRecordCorrupt(source.collection);
        }
        return s;
    }
}
