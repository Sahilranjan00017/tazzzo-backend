package com.tazzzo.customer.profile;

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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PR-12A — public error boundary for {@link CustomerProfileController} ONLY (mirrors
 * {@code SessionExceptionHandler}/{@code OtpExceptionHandler}'s {@code assignableTypes} scoping).
 * Authentication failures never reach here — {@code CustomerAuthFilter} already rejected those
 * before this controller ever runs.
 */
@RestControllerAdvice(assignableTypes = CustomerProfileController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CustomerProfileExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileExceptionHandler.class);

    @ExceptionHandler(CustomerProfileFailure.class)
    public ResponseEntity<CustomerProfileErrorDto> profileFailure(CustomerProfileFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("customer_profile_request_rejected reason={} request_id={}", e.reason(), requestId);
        return switch (e.reason()) {
            case INVALID_REQUEST -> body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", requestId);
            case PRECONDITION_REQUIRED -> body(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                    "If-Match header required", requestId);
            case PRECONDITION_FAILED -> body(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED",
                    "profile has changed since it was last read", requestId);
            case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "service unavailable", requestId);
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomerProfileErrorDto> internal(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        ClientRequestErrors.Kind kind = ClientRequestErrors.classify(e);
        if (kind != null) {
            log.warn("customer_profile_request_rejected reason={} request_id={}", kind, requestId);
            return body(kind.status(), "INVALID_REQUEST", kind.message(), requestId);
        }
        log.error("customer_profile_request_internal type={} request_id={}", e.getClass().getSimpleName(),
                requestId);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", requestId);
    }

    private static ResponseEntity<CustomerProfileErrorDto> body(HttpStatus status, String code, String message,
                                                                 String requestId) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON) // never negotiated: an Accept header cannot turn an error into a 500
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CustomerProfileErrorDto(code, message, requestId));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
