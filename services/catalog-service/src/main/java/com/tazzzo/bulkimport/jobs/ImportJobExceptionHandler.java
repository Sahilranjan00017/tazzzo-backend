package com.tazzzo.bulkimport.jobs;

import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** The admin error envelope for the job API; everything else (auth, request shape) falls through to the platform advices. */
@RestControllerAdvice(assignableTypes = ImportJobController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class ImportJobExceptionHandler {

    @ExceptionHandler(ImportJobException.class)
    ResponseEntity<Map<String, Map<String, String>>> refused(ImportJobException e, HttpServletRequest req) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", e.code);
        error.put("message", e.getMessage());
        error.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
        return ResponseEntity.status(e.status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", error));
    }
}
