package com.tazzzo.support;

import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import com.tazzzo.notification.NotificationEnqueuer;
import com.tazzzo.notification.NotificationRequest;
import com.tazzzo.notification.NotificationType;
import com.tazzzo.customer.order.OrderId;
import com.tazzzo.customer.order.OrderRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Support cases. A customer opens a case (optionally about one of THEIR OWN orders), adds messages and may close it;
 * staff (order-ops / support-agent through {@code /api/v1/admin/support}) reply, assign and resolve it. Ownership is always
 * the verified principal (a foreign case is simply not found); every state change is a guarded single-document update
 * (CAS or status guard), so concurrent replies/closes can never corrupt a thread; the thread is bounded; every STAFF
 * action is audited with the authenticated actor. Case text is personal data: it is deleted by account erasure
 * ({@link SupportErasure}).
 */
@Service
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CASE_ID = Pattern.compile("SUP_[A-Za-z0-9_-]{20,40}");
    private static final Pattern CURSOR = Pattern.compile("v1\\|([0-9]{1,15})\\|(SUP_[A-Za-z0-9_-]{20,40})");
    public static final int DEFAULT_PAGE = 20;
    public static final int MAX_PAGE = 50;

    public record Page(List<SupportCase> cases, String nextCursor) { }

    private final SupportCaseRepository cases;
    private final OrderRepository orders;
    private final Tx tx;
    private final Clock clock;
    private final DomainAudit audit;
    private final NotificationEnqueuer notifications;

    public SupportService(SupportCaseRepository cases, OrderRepository orders, Tx tx, Clock clock, MongoDatabase db,
                          NotificationEnqueuer notifications) {
        this.notifications = notifications;
        this.cases = cases;
        this.orders = orders;
        this.tx = tx;
        this.clock = clock;
        this.audit = new DomainAudit(db, clock);
    }

    // ---------------------------------------------------------------- customer

    public SupportCase open(String customerId, String category, String orderId, String subject, String text) {
        return guard(() -> {
            SupportCase.Category cat = parseEnum(SupportCase.Category.class, category);
            String subj = clean(subject, SupportCase.MAX_SUBJECT);
            String body = clean(text, SupportCase.MAX_TEXT);
            if (orderId != null) {
                if (!OrderId.isValid(orderId) || orders.findOwnedById(orderId, customerId) == null) {
                    throw new SupportFailure(SupportFailure.Reason.NOT_FOUND);   // foreign == unknown
                }
            }
            if (cases.countOpen(customerId) >= SupportCase.MAX_OPEN_PER_CUSTOMER) {
                throw new SupportFailure(SupportFailure.Reason.TOO_MANY_OPEN);
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            SupportCase s = new SupportCase(newId(), customerId, cat, orderId, subj, SupportCase.Status.OPEN,
                    List.of(new SupportCase.Message(1, SupportCase.Author.CUSTOMER, null, body, now)), null, 1L, now, now);
            tx.run(session -> cases.insert(session, s));
            return s;
        });
    }

    public Page listOwn(String customerId, String cursor, int limit) {
        return guard(() -> page(customerId, null, cursor, limit));
    }

    public SupportCase getOwn(String customerId, String caseId) {
        return guard(() -> {
            Document d = validId(caseId) ? cases.findOwned(caseId, customerId) : null;
            if (d == null) throw new SupportFailure(SupportFailure.Reason.NOT_FOUND);
            return SupportCaseRepository.toCase(d);
        });
    }

    /** A customer message. Allowed on an open or RESOLVED case (which it reopens); a CLOSED case is final. */
    public SupportCase customerReply(String customerId, String caseId, String text) {
        return guard(() -> {
            SupportCase current = getOwnUnguarded(customerId, caseId);
            String body = clean(text, SupportCase.MAX_TEXT);
            if (current.status() == SupportCase.Status.CLOSED) throw new SupportFailure(SupportFailure.Reason.STATE_CONFLICT);
            requireRoom(current);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            String next = current.status() == SupportCase.Status.RESOLVED ? SupportCase.Status.OPEN.name() : null;
            SupportCase.Message msg = new SupportCase.Message(current.messages().size() + 1, SupportCase.Author.CUSTOMER, null, body, now);
            boolean ok = Boolean.TRUE.equals(tx.call(session -> cases.appendMessage(session, caseId, customerId,
                    List.of("OPEN", "IN_PROGRESS", "RESOLVED"), msg, next, now)));
            if (!ok) throw new SupportFailure(SupportFailure.Reason.STATE_CONFLICT);   // lost a race: the caller re-reads
            return getOwnUnguarded(customerId, caseId);
        });
    }

    public SupportCase customerClose(String customerId, String caseId) {
        return guard(() -> {
            SupportCase current = getOwnUnguarded(customerId, caseId);
            if (current.status() == SupportCase.Status.CLOSED) return current;   // idempotent
            Instant closeAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            if (!Boolean.TRUE.equals(tx.call(session -> cases.customerClose(session, caseId, customerId, closeAt)))) {
                SupportCase again = getOwnUnguarded(customerId, caseId);
                if (again.status() == SupportCase.Status.CLOSED) return again;
                throw new SupportFailure(SupportFailure.Reason.STATE_CONFLICT);
            }
            return getOwnUnguarded(customerId, caseId);
        });
    }

    // ------------------------------------------------------------------- staff

    public Page staffList(String status, String cursor, int limit) {
        return guard(() -> {
            if (status != null) parseEnum(SupportCase.Status.class, status);
            return page(null, status, cursor, limit);
        });
    }

    public SupportCase staffGet(String caseId) {
        return guard(() -> staffGetUnguarded(caseId));
    }

    public SupportCase staffReply(Actor actor, String caseId, String text) {
        return guard(() -> {
            SupportCase current = staffGetUnguarded(caseId);
            String body = clean(text, SupportCase.MAX_TEXT);
            if (current.status() == SupportCase.Status.CLOSED) throw new SupportFailure(SupportFailure.Reason.STATE_CONFLICT);
            requireRoom(current);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            String next = current.status() == SupportCase.Status.OPEN ? SupportCase.Status.IN_PROGRESS.name() : null;
            SupportCase.Message msg = new SupportCase.Message(current.messages().size() + 1, SupportCase.Author.STAFF, actor.id(), body, now);
            tx.run(session -> {
                audit.append(session, new DomainEvent("support_case", caseId, "SUPPORT_STAFF_REPLIED", Map.of(), actor));
                if (!cases.appendMessage(session, caseId, null, List.of("OPEN", "IN_PROGRESS", "RESOLVED"), msg, next, now)) {
                    throw new SupportFailure(SupportFailure.Reason.STATE_CONFLICT);   // aborts the transaction: no audit row either
                }
                // never the message text (personal data): the customer opens the case in the app
                notifications.enqueue(session, new NotificationRequest(NotificationType.SUPPORT_REPLY, current.customerId(),
                        caseId + "-m" + msg.id(), Map.of("category", current.category().name())));
            });
            return staffGetUnguarded(caseId);
        });
    }

    /** Assign the case to the calling staff member (CAS). OPEN becomes IN_PROGRESS. */
    public SupportCase assignToSelf(Actor actor, String caseId, long expectedVersion) {
        return guard(() -> {
            SupportCase current = staffGetUnguarded(caseId);
            String to = current.status() == SupportCase.Status.OPEN ? SupportCase.Status.IN_PROGRESS.name() : current.status().name();
            if (current.status() == SupportCase.Status.CLOSED) throw new SupportFailure(SupportFailure.Reason.STATE_CONFLICT);
            applyTransition(actor, caseId, expectedVersion, to, actor.id(), "SUPPORT_ASSIGNED", Map.of(), s -> { });
            return staffGetUnguarded(caseId);
        });
    }

    /** Staff status change: IN_PROGRESS, RESOLVED or CLOSED (CAS). A CLOSED case is final. */
    public SupportCase setStatus(Actor actor, String caseId, long expectedVersion, String to) {
        return guard(() -> {
            SupportCase.Status target = parseEnum(SupportCase.Status.class, to);
            if (target == SupportCase.Status.OPEN) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
            SupportCase current = staffGetUnguarded(caseId);
            if (current.status() == SupportCase.Status.CLOSED || current.status() == target) {
                throw new SupportFailure(SupportFailure.Reason.STATE_CONFLICT);
            }
            java.util.function.Consumer<com.mongodb.client.ClientSession> notify = target != SupportCase.Status.RESOLVED ? s -> { }
                    : s -> notifications.enqueue(s, new NotificationRequest(NotificationType.SUPPORT_CASE_RESOLVED,
                            current.customerId(), caseId + "-v" + (expectedVersion + 1), Map.of("category", current.category().name())));
            applyTransition(actor, caseId, expectedVersion, target.name(), null, "SUPPORT_STATUS_CHANGED", Map.of("to", target.name()), notify);
            return staffGetUnguarded(caseId);
        });
    }

    // ----------------------------------------------------------------- helpers

    /** Audit row and CAS in ONE transaction: a stale version rolls the audit row back too. */
    private void applyTransition(Actor actor, String caseId, long expectedVersion, String to, String assignedTo, String eventType,
                                 Map<String, Object> detail, java.util.function.Consumer<com.mongodb.client.ClientSession> inTx) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        tx.run(session -> {
            audit.append(session, new DomainEvent("support_case", caseId, eventType, new LinkedHashMap<>(detail), actor));
            if (!cases.transition(session, caseId, expectedVersion, List.of("OPEN", "IN_PROGRESS", "RESOLVED"), to, assignedTo, now)) {
                throw new SupportFailure(SupportFailure.Reason.STALE_VERSION);       // aborts the transaction: no audit row either
            }
            inTx.accept(session);
        });
    }

    private SupportCase getOwnUnguarded(String customerId, String caseId) {
        Document d = validId(caseId) ? cases.findOwned(caseId, customerId) : null;
        if (d == null) throw new SupportFailure(SupportFailure.Reason.NOT_FOUND);
        return SupportCaseRepository.toCase(d);
    }

    private SupportCase staffGetUnguarded(String caseId) {
        Document d = validId(caseId) ? cases.findById(caseId) : null;
        if (d == null) throw new SupportFailure(SupportFailure.Reason.NOT_FOUND);
        return SupportCaseRepository.toCase(d);
    }

    private static void requireRoom(SupportCase s) {
        if (s.messages().size() >= SupportCase.MAX_MESSAGES) throw new SupportFailure(SupportFailure.Reason.MESSAGE_LIMIT);
    }

    private Page page(String customerId, String status, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        Instant beforeAt = null;
        String beforeId = null;
        if (cursor != null) {
            String[] p = decodeCursor(cursor);
            beforeAt = Instant.ofEpochMilli(Long.parseLong(p[0]));
            beforeId = p[1];
        }
        List<Document> rows = cases.page(customerId, status, beforeAt, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        List<SupportCase> out = new ArrayList<>();
        for (Document d : more ? rows.subList(0, limit) : rows) out.add(SupportCaseRepository.toCase(d));
        String next = more ? encodeCursor(out.get(out.size() - 1).updatedAt(), out.get(out.size() - 1).caseId()) : null;
        return new Page(out, next);
    }

    static String encodeCursor(Instant at, String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1|" + at.toEpochMilli() + "|" + id).getBytes(StandardCharsets.US_ASCII));
    }

    static String[] decodeCursor(String raw) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        var m = CURSOR.matcher(new String(bytes, StandardCharsets.US_ASCII));
        if (!m.matches()) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        return new String[]{m.group(1), m.group(2)};
    }

    private static boolean validId(String id) {
        return id != null && CASE_ID.matcher(id).matches();
    }

    private static String newId() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return "SUP_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String clean(String raw, int max) {
        try {
            return SupportCase.cleanText(raw, max);
        } catch (IllegalArgumentException e) {
            throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw);
        } catch (IllegalArgumentException e) {
            throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        }
    }

    private <T> T guard(java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (MongoException e) {
            log.error("support_datastore_failure type={}", e.getClass().getSimpleName());
            throw new SupportFailure(SupportFailure.Reason.UNAVAILABLE);
        }
    }
}
