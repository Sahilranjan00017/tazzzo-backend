package com.tazzzo.customer.order;

import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import com.tazzzo.delivery.DeliverySlotService;
import com.tazzzo.inventory.InventoryReservationFailure;
import com.tazzzo.inventory.InventoryReservationId;
import com.tazzzo.inventory.InventoryReservationPort;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Order operations for staff ({@code order-ops}, through {@code /api/v1/admin/orders}): the queue, one order, and the
 * fulfilment state machine -- CONFIRMED → OUT_FOR_DELIVERY → DELIVERED, and CANCELLED from CONFIRMED or OUT_FOR_DELIVERY
 * (a failed or refused delivery). Every transition is a CAS on {@code (status, version)} with the audit row (authenticated
 * actor) in the SAME transaction; a cancel also returns the stock (exactly-once restock) and releases the delivery slot hold,
 * all-or-nothing. A DELIVERED order is never cancelled here (that is a return, not modelled). Cash collection is a payment
 * concern and is not recorded.
 */
@Service
public class StaffOrderService {

    private static final Logger log = LoggerFactory.getLogger(StaffOrderService.class);
    public static final int DEFAULT_PAGE = 20;
    public static final int MAX_PAGE = 50;

    public record Page(List<Order> orders, String nextCursor) { }

    private final OrderRepository orders;
    private final DeliverySlotService slots;
    private final InventoryReservationPort reservations;
    private final Tx tx;
    private final Clock clock;
    private final DomainAudit audit;

    public StaffOrderService(OrderRepository orders, DeliverySlotService slots, InventoryReservationPort reservations, Tx tx,
                             Clock clock, MongoDatabase db) {
        this.orders = orders;
        this.slots = slots;
        this.reservations = reservations;
        this.tx = tx;
        this.clock = clock;
        this.audit = new DomainAudit(db, clock);
    }

    public Page list(String status, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE) throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        if (status != null && !OrderRepository.CUSTOMER_VISIBLE.contains(status)) throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        Instant beforeAt = null;
        String beforeId = null;
        if (cursor != null) {
            String[] p = OrderLifecycleService.decodeCursor(cursor);
            beforeAt = Instant.ofEpochMilli(Long.parseLong(p[0]));
            beforeId = p[1];
        }
        try {
            List<Document> rows = orders.staffPage(status, beforeAt, beforeId, limit + 1);
            boolean more = rows.size() > limit;
            List<Order> out = new ArrayList<>();
            for (Document d : more ? rows.subList(0, limit) : rows) out.add(OrderRepository.toOrder(d));
            String next = more ? OrderLifecycleService.encodeCursor(out.get(out.size() - 1).createdAt(), out.get(out.size() - 1).orderId().value()) : null;
            return new Page(out, next);
        } catch (MongoException e) {
            log.error("staff_order_list_failed type={}", e.getClass().getSimpleName());
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE, "datastore unavailable");
        }
    }

    public Order get(String orderId) {
        Document d = OrderId.isValid(orderId) ? orders.findById(orderId) : null;
        if (d == null) throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);
        Order o = OrderRepository.toOrder(d);
        if (o.status() == OrderStatus.CREATED) throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);
        return o;
    }

    /** {@code to} is OUT_FOR_DELIVERY, DELIVERED or CANCELLED (the latter with a staff reason code). */
    public Order transition(Actor actor, String orderId, String to, long expectedVersion, String reasonCode) {
        OrderStatus target;
        try {
            target = OrderStatus.valueOf(to == null ? "" : to);
        } catch (IllegalArgumentException e) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        if (target == OrderStatus.CREATED || target == OrderStatus.CONFIRMED) throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        if ((target == OrderStatus.CANCELLED) != (reasonCode != null)) throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        if (reasonCode != null && !OrderCancellation.STAFF_REASONS.contains(reasonCode)) throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        if (!OrderId.isValid(orderId)) throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);
        try {
            return tx.call(session -> {
                Document stored = orders.findById(session, orderId);
                if (stored == null) throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);
                Order order = OrderRepository.toOrder(stored);
                if (order.status() == OrderStatus.CREATED) throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);
                if (order.version() != expectedVersion) throw new OrderFailure(OrderFailure.Reason.STALE_VERSION);
                OrderStatus from = order.status();
                boolean allowed = switch (target) {
                    case OUT_FOR_DELIVERY -> from == OrderStatus.CONFIRMED;
                    case DELIVERED -> from == OrderStatus.OUT_FOR_DELIVERY;
                    case CANCELLED -> from == OrderStatus.CONFIRMED || from == OrderStatus.OUT_FOR_DELIVERY;
                    default -> false;
                };
                if (!allowed) throw new OrderFailure(OrderFailure.Reason.INVALID_TRANSITION);
                Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("from", from.name());
                detail.put("to", target.name());
                org.bson.conversions.Bson extra = switch (target) {
                    case OUT_FOR_DELIVERY -> Updates.set("fulfilment", new Document("outForDeliveryAt", Date.from(now)));
                    case DELIVERED -> Updates.set("fulfilment.deliveredAt", Date.from(now));
                    default -> Updates.set("cancellation", new Document("cancelledAt", Date.from(now))
                            .append("cancelledBy", OrderCancellation.CancelledBy.STAFF.name()).append("reasonCode", reasonCode));
                };
                if (!orders.staffTransition(session, orderId, from, expectedVersion, target, now, extra)) {
                    throw new OrderFailure(OrderFailure.Reason.STALE_VERSION);
                }
                if (target == OrderStatus.CANCELLED) {
                    detail.put("reason", reasonCode);
                    boolean slotReleased = order.deliverySlot() != null && slots != null && slots.release(session,
                            order.deliverySlot().serviceAreaId(), order.deliverySlot().windowId(), order.deliverySlot().date(), orderId);
                    boolean restocked;
                    try {
                        restocked = reservations.restockConsumed(session, new InventoryReservationId(order.reservationId()));
                    } catch (InventoryReservationFailure e) {
                        log.error("staff_order_cancel_restock_failed reason={}", e.reason());
                        throw new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE, "stock could not be returned");
                    }
                    detail.put("restocked", restocked);
                    detail.put("slot_released", slotReleased);
                }
                audit.append(session, new DomainEvent("order", orderId, "ORDER_" + target.name(), detail, actor));
                return OrderRepository.toOrder(orders.findById(session, orderId));
            });
        } catch (MongoException e) {
            log.error("staff_order_transition_failed type={}", e.getClass().getSimpleName());
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE, "datastore unavailable");
        }
    }
}
