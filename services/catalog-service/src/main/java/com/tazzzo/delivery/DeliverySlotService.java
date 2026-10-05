package com.tazzzo.delivery;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.serviceability.ServiceabilityResolution;
import com.tazzzo.serviceability.ServiceabilityService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Delivery slots: admin-managed recurring windows per service area, a customer-facing availability view computed fresh
 * on every read, and atomic per-occurrence capacity holds that the checkout/order flow takes inside ITS transaction.
 *
 * <p>Capacity model: one {@code delivery_slot_usage} document per (area, window, date), created lazily, holding the
 * count and the set of hold ids. A hold is idempotent (a retry never takes a second unit) and a release is idempotent
 * (a second release changes nothing). The conditional {@code used < capacity} increment is the only thing that decides
 * "full", so concurrent reservations can never oversell. Reads never reserve anything.
 *
 * <p>All wall-clock reasoning (day of week, window start, cutoff, "today") uses the configured delivery zone, never the
 * server's. Admin writes are audit-before-state with the authenticated actor; no personal data is stored here.
 */
public class DeliverySlotService {

    private static final Logger log = LoggerFactory.getLogger(DeliverySlotService.class);

    static final String WINDOWS = "delivery_slot_windows";
    static final String USAGE = "delivery_slot_usage";
    static final String AGGREGATE_TYPE = "delivery_slot_window";
    /** A usage row is purge-eligible this long after the slot date (the TTL index on expire_at); capacity is meaningless afterwards. */
    static final Duration USAGE_RETENTION = Duration.ofDays(7);

    private final Tx tx;
    private final MongoDatabase db;
    private final DomainAudit audit;
    private final ServiceabilityService serviceability;
    private final Clock clock;
    private final ZoneId zone;
    private final int horizonDays;

    public DeliverySlotService(Tx tx, MongoDatabase db, DomainAudit audit, ServiceabilityService serviceability,
                               Clock clock, ZoneId zone, int horizonDays) {
        this.tx = Objects.requireNonNull(tx);
        this.db = Objects.requireNonNull(db);
        this.audit = Objects.requireNonNull(audit);
        this.serviceability = Objects.requireNonNull(serviceability);
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
        if (horizonDays < 1 || horizonDays > 14) {
            throw new IllegalArgumentException("horizonDays must be between 1 and 14");
        }
        this.horizonDays = horizonDays;
    }

    public ZoneId zone() {
        return zone;
    }

    public int horizonDays() {
        return horizonDays;
    }

    // ------------------------------------------------------------------ admin

    /**
     * Create ({@code expectedVersion == null}) or compare-and-set replace one window of an area.
     *
     * @return the new version
     */
    public long upsertWindow(Actor actor, String serviceAreaId, SlotWindow window, Long expectedVersion) {
        Objects.requireNonNull(actor, "actor required");
        validateAreaId(serviceAreaId);
        Objects.requireNonNull(window, "window required");
        if (expectedVersion != null && expectedVersion < 1) {
            throw new DeliverySlotException.Invalid("expectedVersion must be positive");
        }
        if (!serviceability.areaExists(serviceAreaId)) {
            throw new DeliverySlotException.NotFound("no such service area");
        }
        String id = docId(serviceAreaId, window.windowId());
        long newVersion = expectedVersion == null ? 1L : expectedVersion + 1;
        Date now = Date.from(clock.instant());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("capacity", window.capacity());
        detail.put("start_minute", window.startMinute());
        detail.put("end_minute", window.endMinute());
        detail.put("cutoff_minutes", window.cutoffMinutes());
        detail.put("version", newVersion);
        DomainEvent event = new DomainEvent(AGGREGATE_TYPE, id, "DELIVERY_WINDOW_UPDATED", detail, actor);
        try {
            tx.run(session -> {
                audit.append(session, event);
                if (expectedVersion == null) {
                    db.getCollection(WINDOWS).insertOne(session, new Document("_id", id)
                            .append("service_area_id", serviceAreaId)
                            .append("window_id", window.windowId())
                            .append("label", window.label())
                            .append("start_minute", window.startMinute())
                            .append("end_minute", window.endMinute())
                            .append("cutoff_minutes", window.cutoffMinutes())
                            .append("capacity", window.capacity())
                            .append("days", new ArrayList<>(window.days()))
                            .append("active", true)
                            .append("version", newVersion)
                            .append("created_at", now)
                            .append("updated_at", now));
                } else {
                    UpdateResult r = db.getCollection(WINDOWS).updateOne(session,
                            Filters.and(Filters.eq("_id", id), Filters.eq("version", expectedVersion)),
                            Updates.combine(Updates.set("label", window.label()),
                                    Updates.set("start_minute", window.startMinute()),
                                    Updates.set("end_minute", window.endMinute()),
                                    Updates.set("cutoff_minutes", window.cutoffMinutes()),
                                    Updates.set("capacity", window.capacity()),
                                    Updates.set("days", new ArrayList<>(window.days())),
                                    Updates.set("version", newVersion),
                                    Updates.set("updated_at", now)));
                    if (r.getMatchedCount() == 0) {
                        throw missingOrStale(session, id, expectedVersion);
                    }
                }
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new DeliverySlotException.Conflict("window already exists; use expectedVersion to update");
            }
            throw e;
        }
        log.info("delivery_window_written area_hash={} window={} version={}", Integer.toHexString(serviceAreaId.hashCode()),
                window.windowId(), newVersion);
        return newVersion;
    }

    /** Activate or deactivate a window (CAS, audited). Existing holds are untouched; new reservations stop. */
    public long setActive(Actor actor, String serviceAreaId, String windowId, long expectedVersion, boolean active) {
        Objects.requireNonNull(actor, "actor required");
        validateAreaId(serviceAreaId);
        requireWindowId(windowId);
        if (expectedVersion < 1) {
            throw new DeliverySlotException.Invalid("expectedVersion must be positive");
        }
        String id = docId(serviceAreaId, windowId);
        long newVersion = expectedVersion + 1;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("active", active);
        detail.put("version", newVersion);
        DomainEvent event = new DomainEvent(AGGREGATE_TYPE, id,
                active ? "DELIVERY_WINDOW_ACTIVATED" : "DELIVERY_WINDOW_DEACTIVATED", detail, actor);
        tx.run(session -> {
            audit.append(session, event);
            UpdateResult r = db.getCollection(WINDOWS).updateOne(session,
                    Filters.and(Filters.eq("_id", id), Filters.eq("version", expectedVersion)),
                    Updates.combine(Updates.set("active", active), Updates.set("version", newVersion),
                            Updates.set("updated_at", Date.from(clock.instant()))));
            if (r.getMatchedCount() == 0) {
                throw missingOrStale(session, id, expectedVersion);
            }
        });
        return newVersion;
    }

    public List<DeliveryWindow> list(String serviceAreaId) {
        validateAreaId(serviceAreaId);
        List<DeliveryWindow> out = new ArrayList<>();
        for (Document d : db.getCollection(WINDOWS).find(Filters.eq("service_area_id", serviceAreaId))
                .sort(Sorts.ascending("start_minute", "window_id"))) {
            out.add(fromDoc(d));
        }
        return out;
    }

    public Optional<DeliveryWindow> find(String serviceAreaId, String windowId) {
        validateAreaId(serviceAreaId);
        requireWindowId(windowId);
        Document d = db.getCollection(WINDOWS).find(Filters.eq("_id", docId(serviceAreaId, windowId))).first();
        return d == null ? Optional.empty() : Optional.of(fromDoc(d));
    }

    // ------------------------------------------------------------ availability

    /** The next {@code days} local days (1..horizon, today included) of every active window, with live capacity state. */
    public List<SlotOffer> availability(String serviceAreaId, int days) {
        validateAreaId(serviceAreaId);
        if (days < 1 || days > horizonDays) {
            throw new DeliverySlotException.Invalid("days must be between 1 and " + horizonDays);
        }
        ZonedDateTime now = clock.instant().atZone(zone);
        List<DeliveryWindow> windows = list(serviceAreaId).stream().filter(DeliveryWindow::active).toList();
        if (windows.isEmpty()) {
            return List.of();
        }
        List<String> usageIds = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = now.toLocalDate().plusDays(i);
            for (DeliveryWindow w : windows) {
                if (w.window().days().contains(date.getDayOfWeek().getValue())) {
                    usageIds.add(usageId(serviceAreaId, w.window().windowId(), date));
                }
            }
        }
        Map<String, Integer> used = new java.util.HashMap<>();
        if (!usageIds.isEmpty()) {
            for (Document u : db.getCollection(USAGE).find(Filters.in("_id", usageIds))) {
                used.put(u.getString("_id"), u.getInteger("used", 0));
            }
        }
        List<SlotOffer> offers = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = now.toLocalDate().plusDays(i);
            for (DeliveryWindow w : windows) {
                SlotWindow sw = w.window();
                if (!sw.days().contains(date.getDayOfWeek().getValue())) {
                    continue;
                }
                ZonedDateTime start = date.atStartOfDay(zone).plusMinutes(sw.startMinute());
                ZonedDateTime end = date.atStartOfDay(zone).plusMinutes(sw.endMinute());
                int remaining = Math.max(0, sw.capacity() - used.getOrDefault(usageId(serviceAreaId, sw.windowId(), date), 0));
                SlotOffer.Status status = !now.isBefore(start.minusMinutes(sw.cutoffMinutes())) ? SlotOffer.Status.CLOSED
                        : remaining == 0 ? SlotOffer.Status.FULL : SlotOffer.Status.AVAILABLE;
                offers.add(new SlotOffer(SlotOffer.slotId(sw.windowId(), date), sw.windowId(), sw.label(), date, start, end,
                        status, remaining));
            }
        }
        offers.sort(java.util.Comparator.comparing(SlotOffer::startsAt).thenComparing(SlotOffer::windowId));
        return offers;
    }

    // ----------------------------------------------------------------- holds

    /**
     * Take one unit of capacity for {@code holdId} on an occurrence, inside the caller's transaction. Idempotent per hold
     * id. Never throws for business refusals: it answers {@link SlotReservation#FULL} or {@link SlotReservation#UNAVAILABLE}.
     */
    public SlotReservation reserve(ClientSession session, String serviceAreaId, String windowId, LocalDate date, String holdId) {
        Objects.requireNonNull(session, "session required: a hold belongs to the caller's transaction");
        validateAreaId(serviceAreaId);
        requireWindowId(windowId);
        requireHoldId(holdId);
        Objects.requireNonNull(date, "date required");
        Document w = db.getCollection(WINDOWS).find(session, Filters.eq("_id", docId(serviceAreaId, windowId))).first();
        if (w == null || !w.getBoolean("active", false)) {
            return SlotReservation.UNAVAILABLE;
        }
        DeliveryWindow window = fromDoc(w);
        ZonedDateTime now = clock.instant().atZone(zone);
        if (!isBookable(window.window(), date, now)) {
            return SlotReservation.UNAVAILABLE;
        }
        String uid = usageId(serviceAreaId, windowId, date);
        Document existing = db.getCollection(USAGE).find(session, Filters.eq("_id", uid)).first();
        if (existing != null && existing.getList("holds", String.class, List.of()).contains(holdId)) {
            return SlotReservation.ALREADY_HELD;
        }
        // ensure the counter row exists (an upsert on _id equality is retried by the server on a race), then take the unit
        // with a conditional increment: that single filter is what decides "full".
        Date expireAt = Date.from(date.plusDays(1).atStartOfDay(zone).toInstant().plus(USAGE_RETENTION));
        db.getCollection(USAGE).updateOne(session, Filters.eq("_id", uid),
                Updates.combine(Updates.setOnInsert("used", 0), Updates.setOnInsert("holds", new ArrayList<String>()),
                        Updates.setOnInsert("expire_at", expireAt)),
                new UpdateOptions().upsert(true));
        UpdateResult r = db.getCollection(USAGE).updateOne(session,
                Filters.and(Filters.eq("_id", uid), Filters.lt("used", window.window().capacity()),
                        Filters.ne("holds", holdId)),
                Updates.combine(Updates.inc("used", 1), Updates.push("holds", holdId)));
        if (r.getModifiedCount() == 1) {
            return SlotReservation.RESERVED;
        }
        Document again = db.getCollection(USAGE).find(session, Filters.eq("_id", uid)).first();
        if (again != null && again.getList("holds", String.class, List.of()).contains(holdId)) {
            return SlotReservation.ALREADY_HELD;
        }
        return SlotReservation.FULL;
    }

    private static final java.util.regex.Pattern SLOT_ID =
            java.util.regex.Pattern.compile("([a-z0-9][a-z0-9-]{0,31})~([0-9]{4}-[0-9]{2}-[0-9]{2})");

    /**
     * Order placement's one call: resolve the address PIN's service area, reserve the occurrence named by
     * {@code slotId} for {@code orderId} inside the caller's transaction, and return what to snapshot on the order.
     * Idempotent per order id (a retried placement keeps its one unit).
     *
     * @throws SlotRefusedException invalid id, PIN not serviceable, occurrence full or not bookable -- the caller aborts
     *         its whole transaction, so nothing partial is ever committed
     */
    public SlotChoice reserveForOrder(ClientSession session, Pincode pin, String slotId, String orderId) {
        Objects.requireNonNull(session, "session required: the hold belongs to the order's transaction");
        java.util.regex.Matcher m = slotId == null ? null : SLOT_ID.matcher(slotId);
        if (m == null || !m.matches()) {
            throw new SlotRefusedException(SlotRefusedException.Reason.INVALID);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(m.group(2));
        } catch (java.time.format.DateTimeParseException e) {
            throw new SlotRefusedException(SlotRefusedException.Reason.INVALID);
        }
        ServiceabilityResolution area = serviceability.resolveByPincode(session, pin);
        if (area.status() != ServiceabilityResolution.Status.SERVICEABLE) {
            throw new SlotRefusedException(SlotRefusedException.Reason.NOT_SERVICEABLE);
        }
        String areaId = area.serviceAreaId();
        SlotReservation outcome = reserve(session, areaId, m.group(1), date, orderId);
        if (outcome == SlotReservation.FULL) {
            throw new SlotRefusedException(SlotRefusedException.Reason.FULL);
        }
        if (outcome == SlotReservation.UNAVAILABLE) {
            throw new SlotRefusedException(SlotRefusedException.Reason.UNAVAILABLE);
        }
        SlotWindow w = fromDoc(db.getCollection(WINDOWS).find(session, Filters.eq("_id", docId(areaId, m.group(1)))).first()).window();
        ZonedDateTime start = date.atStartOfDay(zone).plusMinutes(w.startMinute());
        ZonedDateTime end = date.atStartOfDay(zone).plusMinutes(w.endMinute());
        return new SlotChoice(areaId, w.windowId(), date, w.label(), start.toInstant(), end.toInstant());
    }

    /** Give the unit back. Idempotent: releasing a hold that does not exist changes nothing and returns false. */
    public boolean release(ClientSession session, String serviceAreaId, String windowId, LocalDate date, String holdId) {
        Objects.requireNonNull(session, "session required: a release belongs to the caller's transaction");
        validateAreaId(serviceAreaId);
        requireWindowId(windowId);
        requireHoldId(holdId);
        Objects.requireNonNull(date, "date required");
        UpdateResult r = db.getCollection(USAGE).updateOne(session,
                Filters.and(Filters.eq("_id", usageId(serviceAreaId, windowId, date)), Filters.eq("holds", holdId)),
                Updates.combine(Updates.inc("used", -1), Updates.pull("holds", holdId)));
        return r.getModifiedCount() == 1;
    }

    // --------------------------------------------------------------- helpers

    boolean isBookable(SlotWindow w, LocalDate date, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        if (date.isBefore(today) || date.isAfter(today.plusDays(horizonDays - 1L))) {
            return false;
        }
        if (!w.days().contains(date.getDayOfWeek().getValue())) {
            return false;
        }
        ZonedDateTime start = date.atStartOfDay(zone).plusMinutes(w.startMinute());
        return now.isBefore(start.minusMinutes(w.cutoffMinutes()));
    }

    private RuntimeException missingOrStale(ClientSession session, String id, long expectedVersion) {
        if (db.getCollection(WINDOWS).find(session, Filters.eq("_id", id)).first() == null) {
            return new DeliverySlotException.NotFound("no such window");
        }
        return new DeliverySlotException.Conflict("stale update expectedVersion=" + expectedVersion);
    }

    private static DeliveryWindow fromDoc(Document d) {
        Set<Integer> days = new TreeSet<>();
        for (Object o : d.getList("days", Object.class, List.of())) {
            days.add(((Number) o).intValue());
        }
        return new DeliveryWindow(d.getString("service_area_id"),
                new SlotWindow(d.getString("window_id"), d.getString("label"), d.getInteger("start_minute"),
                        d.getInteger("end_minute"), d.getInteger("cutoff_minutes"), d.getInteger("capacity"), days),
                d.getBoolean("active", false), ((Number) d.get("version")).longValue());
    }

    static String docId(String serviceAreaId, String windowId) {
        return serviceAreaId + "|" + windowId;
    }

    static String usageId(String serviceAreaId, String windowId, LocalDate date) {
        return serviceAreaId + "|" + windowId + "|" + date;
    }

    static void validateAreaId(String id) {
        if (id == null || id.isBlank() || id.length() > 128 || !id.equals(id.trim()) || id.indexOf('|') >= 0
                || id.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new DeliverySlotException.Invalid("serviceAreaId invalid");
        }
    }

    private static void requireWindowId(String id) {
        if (id == null || !SlotWindow.ID.matcher(id).matches()) {
            throw new DeliverySlotException.Invalid("windowId invalid");
        }
    }

    private static void requireHoldId(String id) {
        if (id == null || id.isBlank() || id.length() > 128 || id.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException("holdId invalid");
        }
    }
}
