package com.tazzzo.customer.checkout;

import com.tazzzo.catalog.api.ClientRequestErrors;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PR-13A — public error boundary for {@link CheckoutController} ONLY, and the ONE place checkout
 * failures are counted (exactly once each, request-shape failures and unexpected 500s included).
 */
@RestControllerAdvice(assignableTypes = CheckoutController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CheckoutExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CheckoutExceptionHandler.class);

    private final CheckoutObservability observability;

    public CheckoutExceptionHandler(CheckoutObservability observability) {
        this.observability = observability;
    }

    @ExceptionHandler(CheckoutFailure.class)
    public ResponseEntity<CheckoutErrorDto> checkoutFailure(CheckoutFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("customer_checkout_request_rejected reason={} request_id={}", e.reason(), requestId);
        observability.failure(operationFor(req), e.reason());
        e.rejections().forEach(r -> observability.itemRejection(r.reason()));
        return switch (e.reason()) {
            case INVALID_REQUEST -> body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", requestId);
            case UNSUPPORTED_MEDIA_TYPE -> body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "unsupported media type", requestId);
            case NOT_FOUND -> body(HttpStatus.NOT_FOUND, "NOT_FOUND", "not found", requestId);
            case PRECONDITION_REQUIRED -> body(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                    "If-Match header required", requestId);
            case PRECONDITION_FAILED -> body(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                    "cart has changed since it was last read", requestId);
            case IDEMPOTENCY_REQUIRED -> body(HttpStatus.PRECONDITION_REQUIRED, "IDEMPOTENCY_REQUIRED",
                    "Idempotency-Key header required", requestId);
            case IDEMPOTENCY_CONFLICT -> body(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "idempotency key was already used for a different request", requestId);
            case CHECKOUT_CART_EMPTY -> body(HttpStatus.CONFLICT, "CHECKOUT_CART_EMPTY", "cart is empty", requestId);
            case CHECKOUT_UNSERVICEABLE -> body(HttpStatus.CONFLICT, "CHECKOUT_UNSERVICEABLE",
                    "the delivery address is not serviceable", requestId);
            case CHECKOUT_ITEM_UNAVAILABLE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new CheckoutErrorDto("CHECKOUT_ITEM_UNAVAILABLE",
                            "one or more items cannot be checked out", requestId,
                            e.rejections().stream().map(r -> new CheckoutErrorDto.Item(r.skuId(), r.reason().name()))
                                    .toList()));
            case QUOTE_EXPIRED -> body(HttpStatus.GONE, "QUOTE_EXPIRED", "quote has expired", requestId);
            case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "service unavailable", requestId);
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CheckoutErrorDto> malformedBody(HttpMessageNotReadableException e, HttpServletRequest req) {
        return checkoutFailure(new CheckoutFailure(CheckoutFailure.Reason.INVALID_REQUEST), req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<CheckoutErrorDto> unsupportedMediaType(HttpMediaTypeNotSupportedException e,
                                                                 HttpServletRequest req) {
        return checkoutFailure(new CheckoutFailure(CheckoutFailure.Reason.UNSUPPORTED_MEDIA_TYPE), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CheckoutErrorDto> internal(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        ClientRequestErrors.Kind kind = ClientRequestErrors.classify(e);
        if (kind != null) {
            // a request-shape failure is the client's, counted once under the closed reason set -- never as a 500
            CheckoutFailure.Reason reason = kind == ClientRequestErrors.Kind.UNSUPPORTED_MEDIA_TYPE
                    ? CheckoutFailure.Reason.UNSUPPORTED_MEDIA_TYPE : CheckoutFailure.Reason.INVALID_REQUEST;
            log.warn("customer_checkout_request_rejected reason={} request_id={}", kind, requestId);
            observability.failure(operationFor(req), reason);
            return body(kind.status(), reason.name(), kind.message(), requestId);
        }
        observability.internalFailure(operationFor(req)); // the ONLY place an unexpected 500 is counted
        log.error("customer_checkout_request_internal type={} request_id={}", e.getClass().getSimpleName(), requestId);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", requestId);
    }

    static CheckoutObservability.Operation operationFor(HttpServletRequest req) {
        return "POST".equals(req.getMethod()) ? CheckoutObservability.Operation.CREATE_QUOTE
                : CheckoutObservability.Operation.READ_QUOTE;
    }

    private static ResponseEntity<CheckoutErrorDto> body(HttpStatus status, String code, String message,
                                                         String requestId) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON) // never negotiated by Accept
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CheckoutErrorDto(code, message, requestId, null));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
