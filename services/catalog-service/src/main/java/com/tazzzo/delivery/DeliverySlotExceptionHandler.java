package com.tazzzo.delivery;

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

/** Customer error boundary for {@link DeliverySlotController}: generic messages, never a stack, a PIN or a driver text. */
@RestControllerAdvice(assignableTypes = DeliverySlotController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class DeliverySlotExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DeliverySlotExceptionHandler.class);

    record ErrorDto(String code, String message, String requestId) { }

    @ExceptionHandler(DeliverySlotFailure.class)
    ResponseEntity<ErrorDto> failure(DeliverySlotFailure e, HttpServletRequest req) {
        return switch (e.reason()) {
            case INVALID_REQUEST -> body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", req);
            case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "service unavailable", req);
        };
    }

    @ExceptionHandler(DeliverySlotException.Invalid.class)
    ResponseEntity<ErrorDto> invalid(HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid request", req);
    }

    @ExceptionHandler({com.mongodb.MongoException.class, com.tazzzo.serviceability.ServiceabilityException.class})
    ResponseEntity<ErrorDto> unavailable(RuntimeException e, HttpServletRequest req) {
        log.warn("delivery_slots_unavailable type={} request_id={}", e.getClass().getSimpleName(), requestId(req));
        return body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "service unavailable", req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorDto> internal(Exception e, HttpServletRequest req) {
        log.error("delivery_slots_internal type={} request_id={}", e.getClass().getSimpleName(), requestId(req));
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error", req);
    }

    private static ResponseEntity<ErrorDto> body(HttpStatus status, String code, String message, HttpServletRequest req) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorDto(code, message, requestId(req)));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
