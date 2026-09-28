package com.tazzzo.customer.cart;

import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PR-12C — public error boundary for {@link CartController} ONLY, and the ONE place cart failures
 * are counted (exactly once each, including request-shape failures that never reach a handler body).
 */
@RestControllerAdvice(assignableTypes = CartController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CartExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CartExceptionHandler.class);

    private final CartObservability observability;

    public CartExceptionHandler(CartObservability observability) {
        this.observability = observability;
    }

    @ExceptionHandler(CartFailure.class)
    public ResponseEntity<CartErrorDto> cartFailure(CartFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("customer_cart_request_rejected reason={} request_id={}", e.reason(), requestId);
        observability.failure(operationFor(req), e.reason());
        return switch (e.reason()) {
            case INVALID_REQUEST -> body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", requestId);
            case UNSUPPORTED_MEDIA_TYPE -> body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "unsupported media type", requestId);
            case NOT_FOUND -> body(HttpStatus.NOT_FOUND, "NOT_FOUND", "not found", requestId);
            case PRECONDITION_REQUIRED -> body(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                    "If-Match header required", requestId);
            case PRECONDITION_FAILED -> body(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                    "cart has changed since it was last read", requestId);
            case CART_ITEM_LIMIT_REACHED -> body(HttpStatus.CONFLICT, "CART_ITEM_LIMIT_REACHED",
                    "cart item limit reached", requestId);
            case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "service unavailable", requestId);
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CartErrorDto> malformedBody(HttpMessageNotReadableException e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("customer_cart_request_rejected reason=INVALID_REQUEST request_id={}", requestId);
        observability.failure(operationFor(req), CartFailure.Reason.INVALID_REQUEST);
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", requestId);
    }

    /** Client sent a body Content-Type the cart routes do not accept: a client error, never a 500. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<CartErrorDto> unsupportedMediaType(HttpMediaTypeNotSupportedException e,
                                                              HttpServletRequest req) {
        return cartFailure(new CartFailure(CartFailure.Reason.UNSUPPORTED_MEDIA_TYPE), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CartErrorDto> internal(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        observability.internalFailure(operationFor(req)); // the ONLY place an unexpected 500 is counted
        log.error("customer_cart_request_internal type={} request_id={}", e.getClass().getSimpleName(), requestId);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", requestId);
    }

    /** Bounded operation inferred from (method, path); this advice only ever sees cart routes. */
    static CartObservability.Operation operationFor(HttpServletRequest req) {
        return switch (req.getMethod()) {
            case "PUT" -> CartObservability.Operation.SET_ITEM;
            case "DELETE" -> req.getRequestURI().contains("/items/")
                    ? CartObservability.Operation.REMOVE_ITEM : CartObservability.Operation.CLEAR;
            default -> CartObservability.Operation.READ;
        };
    }

    private static ResponseEntity<CartErrorDto> body(HttpStatus status, String code, String message,
                                                      String requestId) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CartErrorDto(code, message, requestId));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
