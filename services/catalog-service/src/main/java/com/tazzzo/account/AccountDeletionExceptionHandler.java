package com.tazzzo.account;

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

/** Error boundary for {@link AccountDeletionController} only; authentication failures never reach here. */
@RestControllerAdvice(assignableTypes = AccountDeletionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountDeletionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionExceptionHandler.class);

    @ExceptionHandler(AccountDeletionFailure.class)
    public ResponseEntity<AccountDeletionErrorDto> failure(AccountDeletionFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("customer_account_deletion_rejected reason={} request_id={}", e.reason(), requestId);
        return switch (e.reason()) {
            case INVALID_REQUEST -> body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "deletion requires the confirmation {\"confirm\":\"DELETE\"}", requestId);
            case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "service unavailable", requestId);
        };
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<AccountDeletionErrorDto> unreadable(Exception e, HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "deletion requires the confirmation {\"confirm\":\"DELETE\"}", requestId(req));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AccountDeletionErrorDto> internal(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.error("customer_account_deletion_internal type={} request_id={}", e.getClass().getSimpleName(), requestId);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", requestId);
    }

    private static ResponseEntity<AccountDeletionErrorDto> body(HttpStatus status, String code, String message, String requestId) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AccountDeletionErrorDto(code, message, requestId));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
