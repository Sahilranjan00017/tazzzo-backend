package com.tazzzo.pricing.admin;

import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.catalog.tx.ProductNotFoundException;
import com.tazzzo.pricing.InvalidPriceException;
import com.tazzzo.pricing.PriceConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** The admin error envelope for the price admin API only. */
@RestControllerAdvice(assignableTypes = PriceAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PriceAdminExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ErrorBody> noProduct(HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such product", req);
    }

    @ExceptionHandler(PriceAdminController.PriceNotSetException.class)
    ResponseEntity<ErrorBody> noPrice(HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "no price set for this product", req);
    }

    @ExceptionHandler(PriceConflictException.class)
    ResponseEntity<ErrorBody> conflict(PriceConflictException e, HttpServletRequest req) {
        return envelope(HttpStatus.CONFLICT, "STALE_VERSION", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidPriceException.class)
    ResponseEntity<ErrorBody> invalid(InvalidPriceException e, HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRICE", e.getMessage(), req);
    }

    private static ResponseEntity<ErrorBody> envelope(HttpStatus status, String code, String message, HttpServletRequest req) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        body.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
        return ResponseEntity.status(status).body(new ErrorBody(body));
    }
}
