package com.tazzzo.catalog.consumer;

import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ERR-1 for {@code /catalog/v1/**} — the FLAT consumer envelope.
 *
 * <p>Scoped to the consumer controllers by {@code assignableTypes} and ordered ahead of the CMS
 * advice, so the two contracts stay separate: CMS keeps its nested {@code {"error": {…}}}, and the
 * consumer surface never inherits it (ERR-1-CONFIRMATION).
 *
 * <p>The catch-all matters as much as the typed handlers: without it a framework-generated failure
 * on a consumer route would fall through to the CMS advice, and ERR-1 would hold for the errors we
 * wrote while failing for the ones the framework writes — which are the ones a client hits first.
 */
@RestControllerAdvice(assignableTypes = ConsumerTaxonomyController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConsumerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsumerExceptionHandler.class);

    @ExceptionHandler(ConsumerFailures.NotFound.class)
    public ResponseEntity<ConsumerDtos.ConsumerError> notFound(ConsumerFailures.NotFound ex,
                                                               HttpServletRequest req) {
        return flat(HttpStatus.NOT_FOUND, "NOT_FOUND", "not found", req);
    }

    @ExceptionHandler(ConsumerFailures.RateLimited.class)
    public ResponseEntity<ConsumerDtos.ConsumerError> rateLimited(ConsumerFailures.RateLimited ex,
                                                                   HttpServletRequest req) {
        long seconds = Math.max(1, (long) Math.ceil(ex.retryAfter().toMillis() / 1000.0));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(seconds))
                .body(body("RATE_LIMITED", "too many requests", req));
    }

    @ExceptionHandler(ConsumerFailures.Unavailable.class)
    public ResponseEntity<ConsumerDtos.ConsumerError> unavailable(ConsumerFailures.Unavailable ex,
                                                                   HttpServletRequest req) {
        log.warn("consumer_unavailable reason={} request_id={}", ex.getMessage(),
                req.getAttribute(RequestIdFilter.REQUEST_ID));
        return flat(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "service unavailable", req);
    }

    /** Nothing on a consumer route may reach the CMS envelope. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ConsumerDtos.ConsumerError> anythingElse(Exception ex,
                                                                    HttpServletRequest req) {
        log.warn("consumer_error type={} request_id={}", ex.getClass().getSimpleName(),
                req.getAttribute(RequestIdFilter.REQUEST_ID));
        return flat(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "service unavailable", req);
    }

    /**
     * Messages are GENERIC by design (Q5-d, L-5): the body reveals nothing about whether a node
     * exists, whether it is empty, which bucket rejected, or what failed internally. Diagnosis
     * happens through request_id in the logs, not through the response.
     */
    private ResponseEntity<ConsumerDtos.ConsumerError> flat(HttpStatus status, String code,
                                                            String message, HttpServletRequest req) {
        return ResponseEntity.status(status).body(body(code, message, req));
    }

    private ConsumerDtos.ConsumerError body(String code, String message, HttpServletRequest req) {
        return new ConsumerDtos.ConsumerError(code, message,
                String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
    }
}
