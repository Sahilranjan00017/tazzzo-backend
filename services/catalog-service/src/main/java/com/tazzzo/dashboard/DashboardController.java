package com.tazzzo.dashboard;

import com.tazzzo.catalog.api.ApiExceptionHandler.ErrorBody;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/v1/admin/dashboard/summary}: bounded operational counts for the CMS home (read access per the admin
 * access policy). No query parameters, nothing personal, never cached by intermediaries.
 */
@RestController
class DashboardController {

    private final DashboardSummaryService service;

    DashboardController(DashboardSummaryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/dashboard/summary")
    ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary());
    }

    @ExceptionHandler(DashboardSummaryService.DashboardUnavailableException.class)
    ResponseEntity<ErrorBody> unavailable(HttpServletRequest req) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("code", "SERVICE_UNAVAILABLE");
        e.put("message", "dashboard unavailable");
        e.put("request_id", String.valueOf(req.getAttribute(RequestIdFilter.REQUEST_ID)));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(new ErrorBody(e));
    }
}
