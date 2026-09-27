package com.tazzzo.auth.session;

import com.tazzzo.auth.CustomerAuthErrorDto;
import com.tazzzo.auth.CustomerAuthFailure;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PR-11C — public error boundary for {@link SessionController} ONLY (mirrors
 * {@code OtpExceptionHandler}'s {@code assignableTypes} scoping). Every {@link SessionAuthFailure}
 * — unknown/consumed/expired/wrong-purpose grant, unknown/expired/revoked/rotated-away/mismatched
 * refresh token — collapses to the SAME generic {@code UNAUTHENTICATED}, never revealing which.
 * {@link CustomerAuthFailure} (thrown by {@link SessionController}'s own inline logout
 * authentication) is mapped to the IDENTICAL flat shape {@code CustomerAuthFilter} itself produces,
 * so a client sees byte-identical 401s whether the filter or this controller rejected it.
 */
@RestControllerAdvice(assignableTypes = SessionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SessionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionExceptionHandler.class);

    @ExceptionHandler(SessionAuthFailure.class)
    public ResponseEntity<SessionErrorDto> sessionFailure(SessionAuthFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("session_auth_rejected reason={} request_id={}", e.reason(), requestId);
        return switch (e.reason()) {
            case INVALID_REQUEST -> body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", requestId);
            case INVALID -> unauthenticated(requestId);
            case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "service unavailable", requestId);
        };
    }

    /** Logout's inline bearer authentication — same flat shape {@code CustomerAuthFilter} produces. */
    @ExceptionHandler(CustomerAuthFailure.class)
    public ResponseEntity<CustomerAuthErrorDto> customerAuthFailure(CustomerAuthFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("session_logout_auth_rejected reason={} request_id={}", e.reason(), requestId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CustomerAuthErrorDto("UNAUTHENTICATED", "authentication required", requestId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SessionErrorDto> internal(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.error("session_request_internal type={} request_id={}", e.getClass().getSimpleName(), requestId);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", requestId);
    }

    private static ResponseEntity<SessionErrorDto> unauthenticated(String requestId) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SessionErrorDto("UNAUTHENTICATED", "authentication required", requestId));
    }

    private static ResponseEntity<SessionErrorDto> body(HttpStatus status, String code, String message,
                                                         String requestId) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SessionErrorDto(code, message, requestId));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
