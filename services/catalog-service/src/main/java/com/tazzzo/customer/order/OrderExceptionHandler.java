package com.tazzzo.customer.order;

import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PR-15A-2 — the public error boundary for {@link OrderController} ONLY. Body is always
 * {@code {code, message, requestId}}; messages are generic. Nothing from an {@link OrderFailure}'s own
 * message, from Mongo, from Inventory, or from a stack trace ever reaches a client.
 *
 * <p><b>Metric ownership (no double counting):</b> a POST {@link OrderFailure} was already counted by
 * {@code OrderService.placeCodOrder} ({@code order_place_cod_failure{reason}}) and is NOT recounted here.
 * This class counts only what the domain never sees: request-shape rejections, unexpected 500s, and
 * every GET failure (the read records no domain metric).
 */
@RestControllerAdvice(assignableTypes = OrderController.class)
@org.springframework.core.annotation.Order(Ordered.HIGHEST_PRECEDENCE)
public class OrderExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderExceptionHandler.class);

    private final OrderHttpObservability observability;

    public OrderExceptionHandler(OrderHttpObservability observability) {
        this.observability = observability;
    }

    @ExceptionHandler(OrderFailure.class)
    public ResponseEntity<OrderErrorDto> orderFailure(OrderFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        OrderHttpObservability.Operation op = operationFor(req);
        log.warn("customer_order_request_rejected reason={} request_id={}", e.reason(), requestId);
        // the domain's own message is deliberately never used in a response or a log line here
        return switch (e.reason()) {
            case INVALID_REQUEST -> reject(op, OrderHttpObservability.Reason.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST", "invalid request", requestId);
            case QUOTE_NOT_FOUND, ORDER_NOT_FOUND -> reject(op, OrderHttpObservability.Reason.NOT_FOUND,
                    HttpStatus.NOT_FOUND, "NOT_FOUND", "not found", requestId);
            case QUOTE_EXPIRED -> reject(op, null, HttpStatus.GONE, "QUOTE_EXPIRED", "quote has expired", requestId);
            case ADDRESS_CHANGED -> reject(op, null, HttpStatus.CONFLICT, "ADDRESS_CHANGED",
                    "the delivery address has changed since the quote", requestId);
            case NOT_SERVICEABLE -> reject(op, null, HttpStatus.CONFLICT, "NOT_SERVICEABLE",
                    "the delivery address is not serviceable", requestId);
            case PRICE_CHANGED -> reject(op, null, HttpStatus.CONFLICT, "PRICE_CHANGED",
                    "a price has changed since the quote", requestId);
            case PRODUCT_UNAVAILABLE -> reject(op, null, HttpStatus.CONFLICT, "PRODUCT_UNAVAILABLE",
                    "one or more items are no longer available", requestId);
            case STOCK_UNAVAILABLE -> reject(op, null, HttpStatus.CONFLICT, "STOCK_UNAVAILABLE",
                    "one or more items are out of stock", requestId);
            case RESERVATION_EXPIRED -> reject(op, null, HttpStatus.CONFLICT, "RESERVATION_EXPIRED",
                    "the stock hold expired before the order could be placed", requestId);
            case CART_VERSION_ALREADY_PURCHASED -> reject(op, null, HttpStatus.CONFLICT,
                    "CART_VERSION_ALREADY_PURCHASED", "this cart has already been ordered", requestId);
            case SLOT_UNAVAILABLE -> reject(op, null, HttpStatus.CONFLICT, "DELIVERY_SLOT_UNAVAILABLE",
                    "the chosen delivery slot is not available", requestId);
            case INTEGRITY_FAILURE -> {
                log.error("customer_order_integrity_failure request_id={}", requestId);
                yield reject(op, OrderHttpObservability.Reason.INTERNAL, HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL", "internal error", requestId);
            }
            case UNAVAILABLE -> reject(op, OrderHttpObservability.Reason.UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "service unavailable", requestId);
        };
    }

    @ExceptionHandler(OrderRequestFailure.class)
    public ResponseEntity<OrderErrorDto> requestFailure(OrderRequestFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("customer_order_request_rejected reason={} request_id={}", e.reason(), requestId);
        return switch (e.reason()) {
            case INVALID_REQUEST -> httpFailure(OrderHttpObservability.Reason.INVALID_REQUEST,
                    HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", requestId, req);
            case PAYMENT_METHOD_UNSUPPORTED -> httpFailure(OrderHttpObservability.Reason.PAYMENT_METHOD_UNSUPPORTED,
                    HttpStatus.BAD_REQUEST, "PAYMENT_METHOD_UNSUPPORTED", "unsupported payment method", requestId,
                    req);
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<OrderErrorDto> malformedBody(HttpMessageNotReadableException e, HttpServletRequest req) {
        return requestFailure(new OrderRequestFailure(OrderRequestFailure.Reason.INVALID_REQUEST), req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<OrderErrorDto> unsupportedMediaType(HttpMediaTypeNotSupportedException e,
                                                              HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("customer_order_request_rejected reason=UNSUPPORTED_MEDIA_TYPE request_id={}", requestId);
        return httpFailure(OrderHttpObservability.Reason.UNSUPPORTED_MEDIA_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE", "unsupported media type", requestId, req);
    }

    /** A corrupt persisted Order or any unexpected defect: a safe 500; the cause is logged by class only. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<OrderErrorDto> internal(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.error("customer_order_request_internal type={} request_id={}", e.getClass().getSimpleName(), requestId);
        return httpFailure(OrderHttpObservability.Reason.INTERNAL, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
                "internal error", requestId, req);
    }

    /**
     * A domain failure: counted here ONLY for a GET (the domain counted a POST failure itself). A null
     * {@code reason} is a POST-only domain outcome the domain already counted.
     */
    private ResponseEntity<OrderErrorDto> reject(OrderHttpObservability.Operation op,
                                                 OrderHttpObservability.Reason reason, HttpStatus status,
                                                 String code, String message, String requestId) {
        if (op == OrderHttpObservability.Operation.READ && reason != null) {
            observability.failure(op, reason);
        }
        return body(status, code, message, requestId);
    }

    /** A failure the domain never saw: always counted at the HTTP boundary. */
    private ResponseEntity<OrderErrorDto> httpFailure(OrderHttpObservability.Reason reason, HttpStatus status,
                                                      String code, String message, String requestId,
                                                      HttpServletRequest req) {
        observability.failure(operationFor(req), reason);
        return body(status, code, message, requestId);
    }

    static OrderHttpObservability.Operation operationFor(HttpServletRequest req) {
        return "POST".equals(req.getMethod()) ? OrderHttpObservability.Operation.PLACE
                : OrderHttpObservability.Operation.READ;
    }

    private static ResponseEntity<OrderErrorDto> body(HttpStatus status, String code, String message,
                                                      String requestId) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new OrderErrorDto(code, message, requestId));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
