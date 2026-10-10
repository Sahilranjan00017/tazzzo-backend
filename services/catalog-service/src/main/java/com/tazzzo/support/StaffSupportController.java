package com.tazzzo.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.api.AdminActors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * {@code /api/v1/admin/support/cases}: the staff view. Reachable only by the support namespace roles ({@code AdminAccessPolicy}:
 * support-agent read+write, order-ops read-only). The audit actor is the authenticated principal, never a body field.
 */
@RestController
@RequestMapping("/api/v1/admin/support/cases")
public class StaffSupportController {

    private final SupportService service;

    public StaffSupportController(SupportService service) {
        this.service = service;
    }

    @GetMapping
    public SupportDtos.StaffPage list(HttpServletRequest request, @RequestParam(name = "status", required = false) String status,
                                      @RequestParam(name = "page_size", required = false) String pageSize,
                                      @RequestParam(name = "cursor", required = false) String cursor) {
        for (String name : request.getParameterMap().keySet()) {
            if (!Set.of("status", "page_size", "cursor").contains(name)) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        }
        if (pageSize != null && !pageSize.matches("[1-9][0-9]{0,2}") || cursor != null && cursor.length() > 128) {
            throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        }
        SupportService.Page page = service.staffList(status, cursor, pageSize == null ? SupportService.DEFAULT_PAGE : Integer.parseInt(pageSize));
        return new SupportDtos.StaffPage(page.cases().stream().map(SupportDtos.StaffSummary::of).toList(), page.nextCursor());
    }

    @GetMapping("/{caseId}")
    public SupportDtos.StaffCase get(@PathVariable String caseId) {
        return SupportDtos.StaffCase.of(service.staffGet(caseId));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.tazzzo.catalog.api.docs.DocumentedRequestBodies.SupportReply.class)))
    @PostMapping("/{caseId}/messages")
    public SupportDtos.StaffCase reply(HttpServletRequest request, @PathVariable String caseId, @RequestBody(required = false) JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("message").isTextual()) {
            throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        }
        return SupportDtos.StaffCase.of(service.staffReply(AdminActors.require(request), caseId, body.get("message").asText()));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.tazzzo.catalog.api.docs.DocumentedRequestBodies.StaffCaseAssign.class)))
    @PostMapping("/{caseId}/assign")
    public SupportDtos.StaffCase assign(HttpServletRequest request, @PathVariable String caseId, @RequestBody(required = false) JsonNode body) {
        return SupportDtos.StaffCase.of(service.assignToSelf(AdminActors.require(request), caseId, expectedVersion(body, 1)));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.tazzzo.catalog.api.docs.DocumentedRequestBodies.StaffCaseStatus.class)))
    @PostMapping("/{caseId}/status")
    public SupportDtos.StaffCase status(HttpServletRequest request, @PathVariable String caseId, @RequestBody(required = false) JsonNode body) {
        if (body == null || !body.path("to").isTextual()) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        return SupportDtos.StaffCase.of(service.setStatus(AdminActors.require(request), caseId, expectedVersion(body, 2), body.get("to").asText()));
    }

    private static long expectedVersion(JsonNode body, int fields) {
        if (body == null || !body.isObject() || body.size() > fields || !body.path("expectedVersion").isIntegralNumber()
                || body.get("expectedVersion").asLong() < 1) {
            throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        }
        return body.get("expectedVersion").asLong();
    }
}
