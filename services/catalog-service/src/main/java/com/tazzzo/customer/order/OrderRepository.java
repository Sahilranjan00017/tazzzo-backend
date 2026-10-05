package com.tazzzo.customer.order;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * PR-14B/PR-15A-1 — {@code orders}, one document per committed Order (PR-15A-1 introduces the strict
 * schema — see {@link Order}; nothing mutates a row in this PR), {@code _id} the opaque
 * {@code ORD_...} id. The unique {@code (customerId, quoteId)} index (see {@code SchemaBootstrap})
 * is BOTH the structural one-quote-to-one-order guarantee AND the concurrent-create race guard —
 * the same idiom {@code InventoryReservationRepository.orderId} and
 * {@code CheckoutQuoteRepository}'s idempotency index already establish. Deliberately NO status or
 * customer-history index in this PR — no query needs one yet, and a speculative index would be
 * write cost paid for nothing.
 */
@Component
public class OrderRepository {

    public static final String COLLECTION = "orders";

    private final MongoDatabase db;

    public OrderRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    public Document findByCustomerAndQuote(String customerId, String quoteId) {
        return collection().find(byCustomerAndQuote(customerId, quoteId)).first();
    }

    public Document findByCustomerAndQuote(ClientSession session, String customerId, String quoteId) {
        return collection().find(session, byCustomerAndQuote(customerId, quoteId)).first();
    }

    /**
     * PR-15A-2 — the owned read: ONE query on {@code _id} AND {@code customerId}. A foreign Order
     * simply does not match, so its existence is never observable (no load-then-authorize). {@code _id}
     * already has its unique index; no new index is needed.
     */
    public Document findOwnedById(String orderId, String customerId) {
        return collection().find(Filters.and(Filters.eq("_id", orderId), Filters.eq("customerId", customerId)))
                .first();
    }

    /** Session-aware owned read (the cancellation transaction must observe its own snapshot). */
    public Document findOwnedById(ClientSession session, String orderId, String customerId) {
        return collection().find(session, Filters.and(Filters.eq("_id", orderId), Filters.eq("customerId", customerId)))
                .first();
    }

    /**
     * CAS CONFIRMED(v2) -> CANCELLED(v3), recording the cancellation. {@code true} iff THIS call applied it: the caller
     * then, and only then, releases the slot and returns the stock, so a lost race can never restock twice.
     */
    public boolean cancelConfirmed(ClientSession session, String orderId, String customerId, OrderCancellation c) {
        return collection().updateOne(session,
                Filters.and(Filters.eq("_id", orderId), Filters.eq("customerId", customerId),
                        Filters.eq("status", OrderStatus.CONFIRMED.name()), Filters.eq("version", 2L)),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("status", OrderStatus.CANCELLED.name()),
                        com.mongodb.client.model.Updates.set("version", 3L),
                        com.mongodb.client.model.Updates.set("cancellation", new Document("cancelledAt", Date.from(c.cancelledAt()))
                                .append("cancelledBy", c.cancelledBy().name()).append("reasonCode", c.reasonCode())),
                        com.mongodb.client.model.Updates.set("updatedAt", Date.from(c.cancelledAt()))))
                .getModifiedCount() == 1;
    }

    /**
     * One page of a customer's customer-visible orders (CONFIRMED and CANCELLED; never the internal CREATED), newest first,
     * strictly after the {@code (beforeCreatedAt, beforeId)} position. Served by {@code order_by_customer_recent}.
     */
    public List<Document> findPage(String customerId, Instant beforeCreatedAt, String beforeId, int limit) {
        List<org.bson.conversions.Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("customerId", customerId));
        filters.add(Filters.in("status", CUSTOMER_VISIBLE));
        if (beforeCreatedAt != null) {
            filters.add(Filters.or(Filters.lt("createdAt", Date.from(beforeCreatedAt)),
                    Filters.and(Filters.eq("createdAt", Date.from(beforeCreatedAt)), Filters.lt("_id", beforeId))));
        }
        return collection().find(Filters.and(filters))
                .sort(new Document("createdAt", -1).append("_id", -1)).limit(limit).into(new ArrayList<>());
    }

    /** Every status a customer (and staff) may see: never the internal CREATED. */
    static final List<String> CUSTOMER_VISIBLE = List.of(OrderStatus.CONFIRMED.name(), OrderStatus.OUT_FOR_DELIVERY.name(),
            OrderStatus.DELIVERED.name(), OrderStatus.CANCELLED.name());

    public Document findById(ClientSession session, String orderId) {
        return collection().find(session, Filters.eq("_id", orderId)).first();
    }

    public Document findById(String orderId) {
        return collection().find(Filters.eq("_id", orderId)).first();
    }

    /**
     * Staff CAS transition: from exactly {@code (fromStatus, fromVersion)} to {@code toStatus} at version + 1, setting the given
     * fields in the same update. {@code true} iff THIS call applied it.
     */
    public boolean staffTransition(ClientSession session, String orderId, OrderStatus fromStatus, long fromVersion,
                                   OrderStatus toStatus, Instant now, org.bson.conversions.Bson extra) {
        List<org.bson.conversions.Bson> u = new ArrayList<>(List.of(
                com.mongodb.client.model.Updates.set("status", toStatus.name()),
                com.mongodb.client.model.Updates.set("version", fromVersion + 1),
                com.mongodb.client.model.Updates.set("updatedAt", Date.from(now))));
        if (extra != null) u.add(extra);
        return collection().updateOne(session, Filters.and(Filters.eq("_id", orderId), Filters.eq("status", fromStatus.name()),
                Filters.eq("version", fromVersion)), com.mongodb.client.model.Updates.combine(u)).getModifiedCount() == 1;
    }

    /** Staff queue: newest first, keyset by (createdAt, _id); optional status. Served by V0012's indexes. */
    public List<Document> staffPage(String status, Instant beforeCreatedAt, String beforeId, int limit) {
        List<org.bson.conversions.Bson> filters = new ArrayList<>();
        filters.add(status == null ? Filters.in("status", CUSTOMER_VISIBLE) : Filters.eq("status", status));
        if (beforeCreatedAt != null) {
            filters.add(Filters.or(Filters.lt("createdAt", Date.from(beforeCreatedAt)),
                    Filters.and(Filters.eq("createdAt", Date.from(beforeCreatedAt)), Filters.lt("_id", beforeId))));
        }
        return collection().find(Filters.and(filters)).sort(new Document("createdAt", -1).append("_id", -1)).limit(limit)
                .into(new ArrayList<>());
    }

    public void insert(ClientSession session, Order order) {
        collection().insertOne(session, toDocument(order));
    }

    private static org.bson.conversions.Bson byCustomerAndQuote(String customerId, String quoteId) {
        return Filters.and(Filters.eq("customerId", customerId), Filters.eq("quoteId", quoteId));
    }

    static Document toDocument(Order o) {
        List<Document> lines = new ArrayList<>(o.lines().size());
        for (OrderLine l : o.lines()) {
            lines.add(new Document("skuId", l.skuId()).append("title", l.title())
                    .append("brandCode", l.brandCode()).append("quantity", l.quantity())
                    .append("unitPricePaise", l.unitPricePaise()).append("lineTotalPaise", l.lineTotalPaise()));
        }
        OrderAddressSnapshot a = o.addressSnapshot();
        Document addressSnapshot = new Document("label", a.label()).append("recipientName", a.recipientName())
                .append("recipientPhone", a.recipientPhone()).append("addressLine1", a.addressLine1())
                .append("addressLine2", a.addressLine2()).append("landmark", a.landmark())
                .append("city", a.city()).append("state", a.state()).append("postalCode", a.postalCode())
                .append("latitude", a.latitude()).append("longitude", a.longitude());
        Document d = new Document("_id", o.orderId().value()).append("customerId", o.customerId())
                .append("quoteId", o.quoteId()).append("status", o.status().name())
                .append("paymentMethod", o.paymentMethod().name()).append("version", o.version())
                .append("addressId", o.addressId()).append("addressVersion", o.addressVersion())
                .append("addressSnapshot", addressSnapshot).append("lines", lines)
                .append("itemCount", o.itemCount()).append("subtotalPaise", o.subtotalPaise())
                .append("currency", o.currency()).append("reservationId", o.reservationId())
                .append("createdAt", Date.from(o.createdAt())).append("updatedAt", Date.from(o.updatedAt()));
        if (o.benefitSnapshot() != null) { // absent ONLY on a legacy Order; never written as null/placeholder
            d.append(OrderBenefitSnapshotCodec.FIELD, OrderBenefitSnapshotCodec.toDocument(o.benefitSnapshot()));
        }
        if (o.moneySnapshot() != null) { // absent ONLY on a legacy (pre-money-model) Order; never written as null
            d.append(OrderMoneySnapshotCodec.FIELD, OrderMoneySnapshotCodec.toDocument(o.moneySnapshot()));
        }
        if (o.deliverySlot() != null) { // absent when no slot was chosen; never written as null
            OrderDeliverySlot slot = o.deliverySlot();
            d.append("deliverySlot", new Document("serviceAreaId", slot.serviceAreaId()).append("windowId", slot.windowId())
                    .append("date", slot.date().toString()).append("label", slot.label())
                    .append("startsAt", Date.from(slot.startsAt())).append("endsAt", Date.from(slot.endsAt())));
        }
        if (o.fulfilment().outForDeliveryAt() != null) { // present once staff handed the order to delivery
            Document f = new Document("outForDeliveryAt", Date.from(o.fulfilment().outForDeliveryAt()));
            if (o.fulfilment().deliveredAt() != null) f.append("deliveredAt", Date.from(o.fulfilment().deliveredAt()));
            d.append("fulfilment", f);
        }
        if (o.cancellation() != null) { // present ONLY on a CANCELLED order
            OrderCancellation c = o.cancellation();
            d.append("cancellation", new Document("cancelledAt", Date.from(c.cancelledAt()))
                    .append("cancelledBy", c.cancelledBy().name()).append("reasonCode", c.reasonCode()));
        }
        if (o.confirmedAt() != null) { // present ONLY on a CONFIRMED order (Order's constructor enforces it)
            d.append("confirmedPaymentCondition", o.confirmedPaymentCondition().name())
                    .append("confirmedAt", Date.from(o.confirmedAt()));
        }
        return d;
    }

    /**
     * PR-14B — no fallback for a malformed/legacy row: fails loud, the same convention
     * {@code CheckoutQuoteRepository.toQuote}/{@code InventoryReservationRepository.toReservation}
     * already follow. This is an internal domain type with no customer HTTP endpoint in this PR, so
     * a caller maps the resulting exception however its own boundary requires.
     */
    static Order toOrder(Document d) {
        List<OrderLine> lines = new ArrayList<>();
        for (Document l : d.getList("lines", Document.class)) {
            lines.add(new OrderLine(l.getString("skuId"), l.getString("title"), l.getString("brandCode"),
                    l.get("quantity", Number.class).intValue(), l.get("unitPricePaise", Number.class).longValue(),
                    l.get("lineTotalPaise", Number.class).longValue()));
        }
        Document a = d.get("addressSnapshot", Document.class);
        OrderAddressSnapshot addressSnapshot = new OrderAddressSnapshot(a.getString("label"),
                a.getString("recipientName"), a.getString("recipientPhone"), a.getString("addressLine1"),
                a.getString("addressLine2"), a.getString("landmark"), a.getString("city"), a.getString("state"),
                a.getString("postalCode"), nullableDouble(a, "latitude"), nullableDouble(a, "longitude"));
        OrderStatus status = OrderStatus.valueOf(requireString(d, "status"));
        Object rawCondition = d.get("confirmedPaymentCondition");
        Object rawConfirmedAt = d.get("confirmedAt");
        // PRESENT => strictly reconstructed (an explicit null is corruption); ABSENT => a legacy Order, never "no benefit"
        OrderBenefitSnapshot benefitSnapshot = d.containsKey(OrderBenefitSnapshotCodec.FIELD)
                ? OrderBenefitSnapshotCodec.fromDocument(d.get(OrderBenefitSnapshotCodec.FIELD)) : null;
        // PRESENT => strictly reconstructed (an explicit null is corruption); ABSENT => a legacy Order, never a
        // synthesized payable
        OrderMoneySnapshot moneySnapshot = d.containsKey(OrderMoneySnapshotCodec.FIELD)
                ? OrderMoneySnapshotCodec.fromDocument(d.get(OrderMoneySnapshotCodec.FIELD)) : null;
        // PRESENT => strictly reconstructed; ABSENT => an order placed without a slot
        OrderDeliverySlot deliverySlot = null;
        if (d.containsKey("deliverySlot")) {
            Document slot = d.get("deliverySlot", Document.class);
            deliverySlot = new OrderDeliverySlot(requireString(slot, "serviceAreaId"), requireString(slot, "windowId"),
                    java.time.LocalDate.parse(requireString(slot, "date")), requireString(slot, "label"),
                    slot.getDate("startsAt").toInstant(), slot.getDate("endsAt").toInstant());
        }
        OrderCancellation cancellation = null;
        if (d.containsKey("cancellation")) {
            Document c = d.get("cancellation", Document.class);
            cancellation = new OrderCancellation(c.getDate("cancelledAt").toInstant(),
                    OrderCancellation.CancelledBy.valueOf(requireString(c, "cancelledBy")), requireString(c, "reasonCode"));
        }
        OrderFulfilment fulfilment = OrderFulfilment.NONE;
        if (d.containsKey("fulfilment")) {
            Document f = d.get("fulfilment", Document.class);
            fulfilment = new OrderFulfilment(f.getDate("outForDeliveryAt").toInstant(),
                    f.getDate("deliveredAt") == null ? null : f.getDate("deliveredAt").toInstant());
        }
        return new Order(new OrderId(d.getString("_id")), d.getString("customerId"), d.getString("quoteId"),
                status, PaymentMethod.valueOf(requireString(d, "paymentMethod")), requireLong(d, "version"),
                d.getString("addressId"), d.get("addressVersion", Number.class).longValue(), addressSnapshot,
                List.copyOf(lines), d.get("itemCount", Number.class).intValue(),
                d.get("subtotalPaise", Number.class).longValue(), d.getString("currency"),
                d.getString("reservationId"),
                rawCondition == null ? null : ConfirmedPaymentCondition.valueOf(requireString(d, "confirmedPaymentCondition")),
                d.getDate("createdAt").toInstant(),
                rawConfirmedAt == null ? null : d.getDate("confirmedAt").toInstant(),
                d.getDate("updatedAt").toInstant(), benefitSnapshot, moneySnapshot, deliverySlot, cancellation, fulfilment);
    }

    /** PR-15A-2 — coordinates are nullable as a PAIR (enforced by {@link OrderAddressSnapshot}); a present
     *  but non-numeric value is corruption and fails loud (ClassCastException), never defaulted. */
    private static Double nullableDouble(Document d, String field) {
        Number n = d.get(field, Number.class);
        return n == null ? null : n.doubleValue();
    }

    /** PR-15A-1 — strict schema: no default for a missing required field, ever. */
    private static String requireString(Document d, String field) {
        Object v = d.get(field);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("order document missing/invalid required field: " + field);
        }
        return s;
    }

    private static long requireLong(Document d, String field) {
        Object v = d.get(field);
        if (!(v instanceof Number n)) {
            throw new IllegalStateException("order document missing/invalid required field: " + field);
        }
        return n.longValue();
    }
}
