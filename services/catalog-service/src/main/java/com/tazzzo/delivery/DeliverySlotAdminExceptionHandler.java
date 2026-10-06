package com.tazzzo.delivery;

import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** The admin error envelope for the delivery-slot admin API only. */
@RestControllerAdvice(assignableTypes = DeliverySlotAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class DeliverySlotAdminExceptionHandler {

    @ExceptionHandler(DeliverySlotException.NotFound.class)
    ResponseEntity<ErrorBody> missing(DeliverySlotException.NotFound e, HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), req);
    }

    @ExceptionHandler(DeliverySlotException.Conflict.class)
    ResponseEntity<ErrorBody> conflict(DeliverySlotException.Conflict e, HttpServletRequest req) {
        return envelope(HttpStatus.CONFLICT, "STALE_VERSION", e.getMessage(), req);
    }

    @ExceptionHandler(DeliverySlotException.Invalid.class)
    ResponseEntity<ErrorBody> invalid(DeliverySlotException.Invalid e, HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DELIVERY_WINDOW", e.getMessage(), req);
    }

    private static ResponseEntity<ErrorBody> envelope(HttpStatus status, String code, String message, HttpServletRequest req) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        body.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
        return ResponseEntity.status(status).body(new ErrorBody(body));
    }
}
