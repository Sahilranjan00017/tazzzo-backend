package com.tazzzo.customer.address;

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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PR-12B — public error boundary for {@link AddressController} ONLY (mirrors
 * {@code CustomerProfileExceptionHandler}'s {@code assignableTypes} scoping). Authentication
 * failures never reach here -- {@code CustomerAuthFilter} already rejected those before this
 * controller ever runs.
 */
@RestControllerAdvice(assignableTypes = AddressController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AddressExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AddressExceptionHandler.class);

    private final AddressObservability observability;

    public AddressExceptionHandler(AddressObservability observability) {
        this.observability = observability;
    }

    @ExceptionHandler(AddressFailure.class)
    public ResponseEntity<AddressErrorDto> addressFailure(AddressFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("customer_address_request_rejected reason={} request_id={}", e.reason(), requestId);
        observability.failure(operationFor(req), e.reason());
        return switch (e.reason()) {
            case INVALID_REQUEST -> body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", requestId);
            case NOT_FOUND -> body(HttpStatus.NOT_FOUND, "NOT_FOUND", "address not found", requestId);
            case PRECONDITION_REQUIRED -> body(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                    "If-Match header required", requestId);
            case PRECONDITION_FAILED -> body(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                    "address has changed since it was last read", requestId);
            case ADDRESS_LIMIT_REACHED -> body(HttpStatus.CONFLICT, "ADDRESS_LIMIT_REACHED",
                    "saved address limit reached", requestId);
            case IDEMPOTENCY_CONFLICT -> body(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "idempotency key was already used for a different request, or its address no longer exists", requestId);
            case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "service unavailable", requestId);
        };
    }

    /** A malformed JSON body (including a non-JSON literal like NaN/Infinity for a numeric field)
     *  is a request-shape problem -- 400, never a raw 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AddressErrorDto> malformedBody(HttpMessageNotReadableException e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("customer_address_request_rejected reason=INVALID_REQUEST request_id={}", requestId);
        observability.failure(operationFor(req), AddressFailure.Reason.INVALID_REQUEST);
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", requestId);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AddressErrorDto> internal(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.error("customer_address_request_internal type={} request_id={}", e.getClass().getSimpleName(),
                requestId);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", requestId);
    }

    /**
     * The bounded {@code operation} tag, inferred from the (method, path) of the request -- this
     * advice is scoped to {@link AddressController} only, so every request seen here is an address
     * route. Inference (rather than per-method wrapping) is what lets this ONE place count failures
     * that never reach a controller method body at all (e.g. malformed JSON), exactly once.
     */
    static AddressObservability.Operation operationFor(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri.endsWith("/default")) {
            return AddressObservability.Operation.SET_DEFAULT;
        }
        boolean hasAddressId = !uri.replaceFirst(".*/addresses/?", "").isBlank();
        return switch (req.getMethod()) {
            case "POST" -> AddressObservability.Operation.CREATE;
            case "PATCH" -> AddressObservability.Operation.UPDATE;
            case "DELETE" -> AddressObservability.Operation.DELETE;
            case "PUT" -> AddressObservability.Operation.SET_DEFAULT;
            default -> hasAddressId ? AddressObservability.Operation.READ : AddressObservability.Operation.LIST;
        };
    }

    private static ResponseEntity<AddressErrorDto> body(HttpStatus status, String code, String message,
                                                         String requestId) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AddressErrorDto(code, message, requestId));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
