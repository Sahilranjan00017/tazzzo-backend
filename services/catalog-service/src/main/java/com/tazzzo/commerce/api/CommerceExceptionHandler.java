package com.tazzzo.commerce.api;

import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.commerce.api.dto.ErrorEnvelopeDto;
import com.tazzzo.commerce.contract.PublicErrorCode;
import com.tazzzo.commerce.read.ProductDetailCompositionException;
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
 * Public error boundary for the commerce controller (PR-10B), scoped by {@code assignableTypes} so
 * it governs ONLY {@link CommerceReadController}. Every typed failure becomes the frozen
 * {@link ErrorEnvelopeDto} with a SAFE, generic message — {@code exception.getMessage()} is never
 * exposed (it goes only to a WARN log for 5xx). Codes/retryability per the ratified contract.
 *
 * <p><b>PR-10C cache-safety fix:</b> {@code CommerceReadController} sets a public, long-lived
 * {@code Cache-Control} on categories/children BEFORE the request is known to succeed — so an
 * error thrown after that point (404/429/503/500) would otherwise inherit and PUBLICLY CACHE the
 * error response for up to 5 minutes. Every error response built here explicitly overrides
 * {@code Cache-Control} to {@code no-store}, which Spring writes over any value the controller
 * already set on the raw {@code HttpServletResponse}.
 */
@RestControllerAdvice(assignableTypes = CommerceReadController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommerceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CommerceExceptionHandler.class);
    private static final String CACHE_CONTROL_NO_STORE = "no-store";

    @ExceptionHandler(ConsumerFailures.InvalidRequest.class)
    public ResponseEntity<ErrorEnvelopeDto> invalidRequest(HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, PublicErrorCode.INVALID_REQUEST, "invalid request", false, null, req);
    }

    @ExceptionHandler(ConsumerFailures.InvalidCursor.class)
    public ResponseEntity<ErrorEnvelopeDto> invalidCursor(HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, PublicErrorCode.INVALID_CURSOR, "invalid cursor", false, null, req);
    }

    @ExceptionHandler(ConsumerFailures.NotFound.class)
    public ResponseEntity<ErrorEnvelopeDto> notFound(HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, PublicErrorCode.NOT_FOUND, "not found", false, null, req);
    }

    @ExceptionHandler(ConsumerFailures.RateLimited.class)
    public ResponseEntity<ErrorEnvelopeDto> rateLimited(ConsumerFailures.RateLimited e, HttpServletRequest req) {
        int retryAfter = (int) Math.max(1, (long) Math.ceil(e.retryAfter().toMillis() / 1000.0));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfter))
                .body(envelope(PublicErrorCode.RATE_LIMITED, "too many requests", true, retryAfter, req));
    }

    @ExceptionHandler({ConsumerFailures.Unavailable.class, ProductDetailCompositionException.class,
            com.tazzzo.commerce.read.CommerceReadUnavailableException.class,
            com.mongodb.MongoException.class})
    public ResponseEntity<ErrorEnvelopeDto> unavailable(RuntimeException e, HttpServletRequest req) {
        // Compatibility gate final review #2: com.mongodb.MongoException (and every driver subtype
        // -- socket, timeout, not-primary, ...) is a NEUTRAL third-party type, not a domain internal
        // ArchUnit forbids commerce.api from touching, so it is handled here directly. This is the
        // ONE place that covers every /v1 route's raw datastore reads uniformly -- release
        // resolution, snapshot taxonomy reads (categories/children), product membership reads and
        // product_card_base reads (list), and the PDP/serviceability composers -- without needing a
        // domain-internal import. Domain-typed failures (Pricing/Inventory/Media/Serviceability
        // exceptions) are translated to ConsumerFailures.Unavailable at the commerce.read seam
        // (DomainReadGuard) before they would ever reach here, since commerce.api cannot import
        // those domain exception types itself.
        log.warn("commerce_request_unavailable type={} request_id={}",
                e.getClass().getSimpleName(), requestId(req));
        return body(HttpStatus.SERVICE_UNAVAILABLE, PublicErrorCode.SERVICE_UNAVAILABLE,
                "service unavailable", true, null, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelopeDto> internal(Exception e, HttpServletRequest req) {
        log.error("commerce_request_internal type={} request_id={}",
                e.getClass().getSimpleName(), requestId(req));
        return body(HttpStatus.INTERNAL_SERVER_ERROR, PublicErrorCode.INTERNAL, "internal error", false, null, req);
    }

    private ResponseEntity<ErrorEnvelopeDto> body(HttpStatus status, PublicErrorCode code, String message,
                                                  boolean retryable, Integer retryAfterSeconds,
                                                  HttpServletRequest req) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .body(envelope(code, message, retryable, retryAfterSeconds, req));
    }

    private ErrorEnvelopeDto envelope(PublicErrorCode code, String message, boolean retryable,
                                      Integer retryAfterSeconds, HttpServletRequest req) {
        return new ErrorEnvelopeDto(code, message, requestId(req), retryable, retryAfterSeconds, null);
    }

    /**
     * PR-10B final review #7: {@code RequestIdFilter} is mandatory and always runs first, so this
     * is always populated; fail fast rather than let the literal string {@code "null"} masquerade
     * as a real server-authoritative request id inside an error envelope.
     */
    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        if (!(value instanceof String id) || id.isBlank()) {
            throw new IllegalStateException("request id not populated by RequestIdFilter");
        }
        return id;
    }
}
