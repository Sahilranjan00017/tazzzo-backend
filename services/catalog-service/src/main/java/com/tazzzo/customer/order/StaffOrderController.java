package com.tazzzo.customer.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.api.AdminActors;
import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /api/v1/admin/orders}: staff order operations. Reachable only by the orders namespace roles
 * ({@code AdminAccessPolicy}: order-ops read+write, support-agent read-only). Shows customer personal data (the delivery
 * address snapshot) because fulfilment needs it; the audit actor is the authenticated principal, never a body field.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class StaffOrderController {

    record Line(String skuId, String title, int quantity, long unitPricePaise, long lineTotalPaise) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StaffOrder(String orderId, String customerId, String status, long version, String paymentMethod, String paymentCondition,
                      List<Line> lines, int itemCount, long subtotalPaise, Long payablePaise, OrderAddressSnapshot deliveryAddress,
                      CustomerOrderDto.DeliverySlot deliverySlot, String createdAt, String confirmedAt, String outForDeliveryAt,
                      String deliveredAt, String cancelledAt, String cancelledBy, String cancelReason) {
        static StaffOrder of(com.tazzzo.customer.order.Order o) {
            boolean cancelled = o.cancellation() != null;
            return new StaffOrder(o.orderId().value(), o.customerId(), o.status().name(), o.version(), o.paymentMethod().name(),
                    o.confirmedPaymentCondition() == null ? null : o.confirmedPaymentCondition().name(),
                    o.lines().stream().map(l -> new Line(l.skuId(), l.title(), l.quantity(), l.unitPricePaise(), l.lineTotalPaise())).toList(),
                    o.itemCount(), o.subtotalPaise(), o.moneyView().map(OrderMoneyView::payablePaise).orElse(null), o.addressSnapshot(),
                    o.deliverySlot() == null ? null : new CustomerOrderDto.DeliverySlot(o.deliverySlot().slotId(), o.deliverySlot().label(),
                            o.deliverySlot().startsAt().toString(), o.deliverySlot().endsAt().toString()),
                    o.createdAt().toString(), o.confirmedAt() == null ? null : o.confirmedAt().toString(),
                    o.fulfilment().outForDeliveryAt() == null ? null : o.fulfilment().outForDeliveryAt().toString(),
                    o.fulfilment().deliveredAt() == null ? null : o.fulfilment().deliveredAt().toString(),
                    cancelled ? o.cancellation().cancelledAt().toString() : null,
                    cancelled ? o.cancellation().cancelledBy().name() : null, cancelled ? o.cancellation().reasonCode() : null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @io.swagger.v3.oas.annotations.media.Schema(name = "StaffOrderPage")
    record StaffPage(List<StaffOrder> items, String nextCursor) { }

    private final StaffOrderService service;

    public StaffOrderController(StaffOrderService service) {
        this.service = service;
    }

    @GetMapping
    public StaffPage list(HttpServletRequest request, @RequestParam(name = "status", required = false) String status,
                          @RequestParam(name = "page_size", required = false) String pageSize,
                          @RequestParam(name = "cursor", required = false) String cursor) {
        for (String name : request.getParameterMap().keySet()) {
            if (!Set.of("status", "page_size", "cursor").contains(name)) throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        if (pageSize != null && !pageSize.matches("[1-9][0-9]{0,2}") || cursor != null && cursor.length() > 128) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        StaffOrderService.Page page = service.list(status, cursor, pageSize == null ? StaffOrderService.DEFAULT_PAGE : Integer.parseInt(pageSize));
        return new StaffPage(page.orders().stream().map(StaffOrder::of).toList(), page.nextCursor());
    }

    @GetMapping("/{orderId}")
    public StaffOrder get(@PathVariable String orderId) {
        return StaffOrder.of(service.get(orderId));
    }

    /** Body {@code {"to": OUT_FOR_DELIVERY|DELIVERED|CANCELLED, "expectedVersion": n, "reason": <staff reason, CANCELLED only>}}. */
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.tazzzo.catalog.api.docs.DocumentedRequestBodies.StaffOrderTransition.class)))
    @PostMapping("/{orderId}/transition")
    public StaffOrder transition(HttpServletRequest request, @PathVariable String orderId, @RequestBody(required = false) JsonNode body) {
        if (body == null || !body.isObject() || !body.path("to").isTextual() || !body.path("expectedVersion").isIntegralNumber()) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        body.fieldNames().forEachRemaining(n -> {
            if (!Set.of("to", "expectedVersion", "reason").contains(n)) throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        });
        if (body.has("reason") && !body.get("reason").isTextual()) throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        return StaffOrder.of(service.transition(AdminActors.require(request), orderId, body.get("to").asText(),
                body.get("expectedVersion").asLong(), body.has("reason") ? body.get("reason").asText() : null));
    }

    /** The admin (nested) error envelope for this controller only; generic messages. */
    @RestControllerAdvice(assignableTypes = StaffOrderController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Errors {
        @ExceptionHandler(OrderFailure.class)
        ResponseEntity<ErrorBody> failure(OrderFailure e, HttpServletRequest req) {
            HttpStatus status = switch (e.reason()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                case INTEGRITY_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
                default -> HttpStatus.CONFLICT;
            };
            return envelope(status, e.reason().name(), req);
        }

        @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
        ResponseEntity<ErrorBody> unreadable(HttpServletRequest req) {
            return envelope(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", req);
        }

        private static ResponseEntity<ErrorBody> envelope(HttpStatus s, String code, HttpServletRequest req) {
            Map<String, String> b = new LinkedHashMap<>();
            b.put("code", code);
            b.put("message", "request could not be completed");
            b.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
            return ResponseEntity.status(s).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(b)); // never negotiated by Accept
        }
    }
}
