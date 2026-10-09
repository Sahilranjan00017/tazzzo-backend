package com.tazzzo.bulkimport;

import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The admin error envelope plus, for a rejected file, every row error. */
@RestControllerAdvice(assignableTypes = BulkImportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class BulkImportExceptionHandler {

    record RejectedBody(Map<String, String> error, List<BulkImportDtos.RowError> rowErrors) { }

    @ExceptionHandler(ImportRejectedException.class)
    ResponseEntity<RejectedBody> rejected(ImportRejectedException e, HttpServletRequest req) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("code", "INVALID_IMPORT");
        error.put("message", e.getMessage());
        error.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON) // never negotiated by Accept
                .body(new RejectedBody(error, e.errors));
    }
}
