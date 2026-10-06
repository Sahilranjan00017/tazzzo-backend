package com.tazzzo.serviceability.admin;

import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.serviceability.InvalidServiceabilityException;
import com.tazzzo.serviceability.ServiceabilityConflictException;
import com.tazzzo.serviceability.ServiceabilityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** The admin error envelope ({@code {"error":{code,message,request_id}}}) for the service-area admin API only. */
@RestControllerAdvice(assignableTypes = ServiceAreaAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ServiceAreaAdminExceptionHandler {

    @ExceptionHandler(ServiceabilityNotFoundException.class)
    ResponseEntity<ErrorBody> missing(HttpServletRequest req) {
        return envelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such service area", req);
    }

    @ExceptionHandler(ServiceabilityConflictException.class)
    ResponseEntity<ErrorBody> conflict(ServiceabilityConflictException e, HttpServletRequest req) {
        return envelope(HttpStatus.CONFLICT, "STALE_VERSION", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidServiceabilityException.class)
    ResponseEntity<ErrorBody> invalid(InvalidServiceabilityException e, HttpServletRequest req) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SERVICE_AREA", e.getMessage(), req);
    }

    private static ResponseEntity<ErrorBody> envelope(HttpStatus status, String code, String message, HttpServletRequest req) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        body.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
        return ResponseEntity.status(status).body(new ErrorBody(body));
    }
}
