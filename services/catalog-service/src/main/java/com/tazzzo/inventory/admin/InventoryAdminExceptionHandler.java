package com.tazzzo.inventory.admin;

import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.catalog.tx.ProductNotFoundException;
import com.tazzzo.inventory.InvalidInventoryException;
import com.tazzzo.inventory.InventoryConflictException;
import com.tazzzo.inventory.InventoryNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** The admin error envelope for the inventory admin API only. */
@RestControllerAdvice(assignableTypes = {InventoryAdminController.class, InventoryAdminListController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
class InventoryAdminExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ErrorBody> noProduct(HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such product", req);
    }

    @ExceptionHandler(InventoryNotFoundException.class)
    ResponseEntity<ErrorBody> noRow(HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "no inventory row for this sku and location", req);
    }

    @ExceptionHandler(InventoryConflictException.class)
    ResponseEntity<ErrorBody> conflict(InventoryConflictException e, HttpServletRequest req) {
        return envelope(HttpStatus.CONFLICT, "STALE_VERSION", e.getMessage(), req);
    }

    @ExceptionHandler(com.tazzzo.inventory.InventoryListTimeoutException.class)
    ResponseEntity<ErrorBody> listTimeout(RuntimeException e, HttpServletRequest req) {
        return envelope(HttpStatus.SERVICE_UNAVAILABLE, "LIST_TIMEOUT", e.getMessage(), req);
    }

    @ExceptionHandler({InvalidInventoryException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorBody> invalid(RuntimeException e, HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INVENTORY", e.getMessage(), req);
    }

    private static ResponseEntity<ErrorBody> envelope(HttpStatus status, String code, String message, HttpServletRequest req) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        body.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
        return ResponseEntity.status(status).body(new ErrorBody(body));
    }
}
