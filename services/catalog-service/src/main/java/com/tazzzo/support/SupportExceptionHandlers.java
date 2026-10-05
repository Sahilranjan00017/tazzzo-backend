package com.tazzzo.support;

import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
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

import java.util.LinkedHashMap;
import java.util.Map;

/** The two error boundaries of the support surface: a flat customer envelope and the nested admin envelope. Generic messages only. */
final class SupportExceptionHandlers {

    private SupportExceptionHandlers() { }

    private static final Logger log = LoggerFactory.getLogger(SupportExceptionHandlers.class);

    private static HttpStatus status(SupportFailure.Reason r) {
        return switch (r) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STATE_CONFLICT, STALE_VERSION, TOO_MANY_OPEN, MESSAGE_LIMIT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static String rid(HttpServletRequest req) {
        Object v = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return v == null ? "unknown" : v.toString();
    }

    record CustomerError(String code, String message, String requestId) { }

    @RestControllerAdvice(assignableTypes = CustomerSupportController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Customer {
        @ExceptionHandler(SupportFailure.class)
        ResponseEntity<CustomerError> failure(SupportFailure e, HttpServletRequest req) {
            return body(status(e.reason()), e.reason().name(), req);
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        ResponseEntity<CustomerError> unreadable(HttpServletRequest req) {
            return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", req);
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<CustomerError> internal(Exception e, HttpServletRequest req) {
            log.error("support_customer_internal type={} request_id={}", e.getClass().getSimpleName(), rid(req));
            return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", req);
        }

        private static ResponseEntity<CustomerError> body(HttpStatus s, String code, HttpServletRequest req) {
            return ResponseEntity.status(s).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new CustomerError(code, s.is5xxServerError() ? "service error" : "request could not be completed", rid(req)));
        }
    }

    @RestControllerAdvice(assignableTypes = StaffSupportController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Staff {
        @ExceptionHandler(SupportFailure.class)
        ResponseEntity<ErrorBody> failure(SupportFailure e, HttpServletRequest req) {
            return envelope(status(e.reason()), e.reason().name(), req);
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        ResponseEntity<ErrorBody> unreadable(HttpServletRequest req) {
            return envelope(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", req);
        }

        private static ResponseEntity<ErrorBody> envelope(HttpStatus s, String code, HttpServletRequest req) {
            Map<String, String> b = new LinkedHashMap<>();
            b.put("code", code);
            b.put("message", "request could not be completed");
            b.put("request_id", rid(req));
            return ResponseEntity.status(s).body(new ErrorBody(b));
        }
    }
}
