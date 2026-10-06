package com.tazzzo.customer.order;

import com.mongodb.MongoException;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import com.tazzzo.notification.NotificationEnqueuer;
import com.tazzzo.notification.NotificationRequest;
import com.tazzzo.notification.NotificationType;
import com.tazzzo.delivery.DeliverySlotService;
import com.tazzzo.inventory.InventoryReservationFailure;
import com.tazzzo.inventory.InventoryReservationId;
import com.tazzzo.inventory.InventoryReservationPort;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Customer order history and cancellation.
 *
 * <p><b>Cancel</b> is ONE transaction: CAS {@code CONFIRMED(v2) -> CANCELLED(v3)} (who, when, closed reason code), then --
 * only if THIS call won that CAS -- release the delivery slot hold (keyed by the order id) and return the consumed stock
 * through the inventory port's exactly-once restock, and append an audit event. Any failure rolls all of it back, so an
 * order can never be CANCELLED with its stock still taken or its slot still held, and a lost race can never restock
 * twice. Cancelling an already CANCELLED order is an idempotent 200 with no second restock. A customer may cancel only
 * within {@code tazzzo.orders.customer-cancel-window-seconds} of confirmation; the default {@code 0} DISABLES customer
 * cancellation until the business sets a window (a policy decision this code does not invent).
 *
 * <p><b>List</b> is keyset-paged newest-first over the caller's own customer-visible orders (CONFIRMED, CANCELLED); the
 * cursor only positions within the caller's own rows (the query always filters on the verified principal), so it needs no
 * signature, only strict shape validation.
 */
@Service
public class OrderLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);
    private static final Actor CUSTOMER_CANCEL_ACTOR = Actor.system("system:customer-order-cancel");
    private static final Pattern CURSOR = Pattern.compile("v1\\|([0-9]{1,15})\\|(ORD_[A-Za-z0-9_-]{6,64})");
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    public record Page(List<Order> orders, String nextCursor) { }

    private final OrderRepository orders;
    private final DeliverySlotService slots;
    private final InventoryReservationPort reservations;
    private final Tx tx;
    private final Clock clock;
    private final DomainAudit audit;
    private final OrderObservability observability;
    private final long cancelWindowSeconds;
    private final NotificationEnqueuer notifications;

    public OrderLifecycleService(OrderRepository orders, DeliverySlotService slots, InventoryReservationPort reservations,
                                 Tx tx, Clock clock, MongoDatabase db, OrderObservability observability,
                                 @Value("${tazzzo.orders.customer-cancel-window-seconds:0}") long cancelWindowSeconds,
                                 NotificationEnqueuer notifications) {
        this.notifications = notifications;
        if (cancelWindowSeconds < 0 || cancelWindowSeconds > 7 * 24 * 3600L) {
            throw new IllegalArgumentException("tazzzo.orders.customer-cancel-window-seconds must be within 0..604800");
        }
        this.orders = orders;
        this.slots = slots;
        this.reservations = reservations;
        this.tx = tx;
        this.clock = clock;
        this.audit = new DomainAudit(db, clock);
        this.observability = observability;
        this.cancelWindowSeconds = cancelWindowSeconds;
    }

    // ------------------------------------------------------------------ list

    public Page list(CustomerId customerId, String cursorRaw, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        Instant beforeAt = null;
        String beforeId = null;
        if (cursorRaw != null) {
            String[] parts = decodeCursor(cursorRaw);
            beforeAt = Instant.ofEpochMilli(Long.parseLong(parts[0]));
            beforeId = parts[1];
        }
        List<Document> rows;
        try {
            rows = orders.findPage(customerId.value(), beforeAt, beforeId, limit + 1);
        } catch (MongoException e) {
            log.error("customer_order_list_failed type={}", e.getClass().getSimpleName());
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE, "datastore unavailable during order list");
        }
        boolean more = rows.size() > limit;
        List<Document> page = more ? rows.subList(0, limit) : rows;
        List<Order> out = new ArrayList<>(page.size());
        for (Document d : page) {
            out.add(OrderRepository.toOrder(d));
        }
        String next = null;
        if (more) {
            Order last = out.get(out.size() - 1);
            next = encodeCursor(last.createdAt(), last.orderId().value());
        }
        return new Page(out, next);
    }

    static String encodeCursor(Instant createdAt, String orderId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1|" + createdAt.toEpochMilli() + "|" + orderId).getBytes(StandardCharsets.US_ASCII));
    }

    /** Strict: exactly one spelling, exactly the shape we mint; anything else is an invalid request, never a guess. */
    static String[] decodeCursor(String raw) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        var m = CURSOR.matcher(new String(bytes, StandardCharsets.US_ASCII));
        if (!m.matches()) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        return new String[]{m.group(1), m.group(2)};
    }

    // ---------------------------------------------------------------- cancel

    public Order cancelByCustomer(CustomerId customerId, String orderIdRaw, String reasonCode) {
        if (reasonCode == null || !OrderCancellation.CUSTOMER_REASONS.contains(reasonCode)) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        if (!OrderId.isValid(orderIdRaw)) {
            throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);
        }
        try {
            Order result = tx.call(session -> {
                Document stored = orders.findOwnedById(session, orderIdRaw, customerId.value());
                if (stored == null) {
                    throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);
                }
                Order order = OrderRepository.toOrder(stored);
                if (order.status() == OrderStatus.CANCELLED) {
                    return order;                                    // idempotent: nothing is touched again
                }
                if (order.status() == OrderStatus.CREATED) {
                    throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);   // CREATED is never customer-visible
                }
                if (order.status() != OrderStatus.CONFIRMED) {
                    throw new OrderFailure(OrderFailure.Reason.NOT_CANCELLABLE);   // out for delivery or delivered: staff only
                }
                Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                Instant deadline = order.confirmedAt().plus(Duration.ofSeconds(cancelWindowSeconds));
                if (cancelWindowSeconds == 0 || now.isAfter(deadline)) {
                    throw new OrderFailure(OrderFailure.Reason.CANCELLATION_WINDOW_CLOSED);
                }
                OrderCancellation cancellation = new OrderCancellation(now, OrderCancellation.CancelledBy.CUSTOMER, reasonCode);
                if (!orders.cancelConfirmed(session, orderIdRaw, customerId.value(), cancellation)) {
                    // the row changed under us inside this snapshot: abort and let the retry/replay observe the winner
                    throw new OrderFailure(OrderFailure.Reason.NOT_CANCELLABLE);
                }
                boolean slotReleased = false;
                if (order.deliverySlot() != null) {
                    OrderDeliverySlot slot = order.deliverySlot();
                    slotReleased = slots != null
                            && slots.release(session, slot.serviceAreaId(), slot.windowId(), slot.date(), orderIdRaw);
                }
                boolean restocked;
                try {
                    restocked = reservations.restockConsumed(session, new InventoryReservationId(order.reservationId()));
                } catch (InventoryReservationFailure e) {
                    log.error("customer_order_cancel_restock_failed reason={}", e.reason());
                    throw new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE, "stock could not be returned");
                }
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("by", "CUSTOMER");
                detail.put("reason", reasonCode);
                detail.put("restocked", restocked);
                detail.put("slot_released", slotReleased);
                audit.append(session, new DomainEvent("order", orderIdRaw, "ORDER_CANCELLED", detail, CUSTOMER_CANCEL_ACTOR));
                notifications.enqueue(session, new NotificationRequest(NotificationType.ORDER_CANCELLED, customerId.value(),
                        orderIdRaw, reasonCode == null ? Map.of("cancelled_by", "CUSTOMER")
                                : Map.of("cancelled_by", "CUSTOMER", "reason_code", reasonCode)));
                return OrderRepository.toOrder(orders.findOwnedById(session, orderIdRaw, customerId.value()));
            });
            observability.cancelSuccess();
            return result;
        } catch (OrderFailure e) {
            observability.cancelFailure(e.reason());
            throw e;
        } catch (MongoException e) {
            log.error("customer_order_cancel_failed type={}", e.getClass().getSimpleName());
            observability.cancelFailure(OrderFailure.Reason.UNAVAILABLE);
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE, "datastore unavailable during order cancellation");
        }
    }

    Optional<Duration> cancelWindow() {
        return cancelWindowSeconds == 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(cancelWindowSeconds));
    }
}
