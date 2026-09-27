package com.tazzzo.auth.otp;

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
 * PR-11B — public error boundary for {@link OtpController} ONLY (mirrors
 * {@code CommerceExceptionHandler}'s {@code assignableTypes} scoping). Every {@link OtpFailure}
 * becomes a flat, generic {@link OtpErrorDto} — never the internal reason detail (whether a
 * challenge existed, how many attempts remain, which provider failed). Every response — success or
 * failure — is {@code Cache-Control: no-store}; the controller itself sets it on success, this
 * handler sets it on every failure path independently.
 */
@RestControllerAdvice(assignableTypes = OtpController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OtpExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OtpExceptionHandler.class);

    @ExceptionHandler(OtpFailure.class)
    public ResponseEntity<OtpErrorDto> otpFailure(OtpFailure e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.warn("otp_request_rejected reason={} request_id={}", e.reason(), requestId);
        return switch (e.reason()) {
            case INVALID_REQUEST -> body(HttpStatus.BAD_REQUEST, "OTP_INVALID_REQUEST",
                    "invalid request", requestId, null);
            case INVALID -> body(HttpStatus.BAD_REQUEST, "OTP_INVALID", "invalid or expired challenge",
                    requestId, null);
            case EXPIRED -> body(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "challenge has expired",
                    requestId, null);
            case RATE_LIMITED -> {
                int retryAfter = (int) Math.max(1,
                        (long) Math.ceil(e.retryAfter().toMillis() / 1000.0));
                yield ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfter))
                        .body(new OtpErrorDto("OTP_RATE_LIMITED", "too many requests", requestId, retryAfter));
            }
            case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "service unavailable", requestId, null);
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OtpErrorDto> internal(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        log.error("otp_request_internal type={} request_id={}", e.getClass().getSimpleName(), requestId);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", requestId, null);
    }

    private static ResponseEntity<OtpErrorDto> body(HttpStatus status, String code, String message,
                                                     String requestId, Integer retryAfterSeconds) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new OtpErrorDto(code, message, requestId, retryAfterSeconds));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
