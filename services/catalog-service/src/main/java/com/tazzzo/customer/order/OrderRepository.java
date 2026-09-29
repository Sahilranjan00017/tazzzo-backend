package com.tazzzo.customer.order;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * PR-14B — {@code orders}, one immutable document per committed Order, {@code _id} the opaque
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
        return new Document("_id", o.orderId().value()).append("customerId", o.customerId())
                .append("quoteId", o.quoteId()).append("status", o.status().name())
                .append("addressId", o.addressId()).append("addressVersion", o.addressVersion())
                .append("addressSnapshot", addressSnapshot).append("lines", lines)
                .append("itemCount", o.itemCount()).append("subtotalPaise", o.subtotalPaise())
                .append("currency", o.currency()).append("reservationId", o.reservationId())
                .append("createdAt", Date.from(o.createdAt())).append("updatedAt", Date.from(o.updatedAt()));
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
                a.getString("postalCode"), a.get("latitude", Number.class).doubleValue(),
                a.get("longitude", Number.class).doubleValue());
        return new Order(new OrderId(d.getString("_id")), d.getString("customerId"), d.getString("quoteId"),
                OrderStatus.valueOf(d.getString("status")), d.getString("addressId"),
                d.get("addressVersion", Number.class).longValue(), addressSnapshot, List.copyOf(lines),
                d.get("itemCount", Number.class).intValue(), d.get("subtotalPaise", Number.class).longValue(),
                d.getString("currency"), d.getString("reservationId"), d.getDate("createdAt").toInstant(),
                d.getDate("updatedAt").toInstant());
    }
}
