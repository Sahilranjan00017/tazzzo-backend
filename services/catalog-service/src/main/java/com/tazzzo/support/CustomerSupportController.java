package com.tazzzo.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.CustomerPrincipalResolver;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * {@code /v1/customer/support/cases}: the signed-in customer's own cases. The customer is always the verified principal; no
 * identifier is read from the body; every response is no-store; bodies carry only the documented fields.
 */
@RestController
@RequestMapping("/v1/customer/support/cases")
public class CustomerSupportController {

    private static final Set<String> CREATE_FIELDS = Set.of("category", "orderId", "subject", "message");

    private final SupportService service;

    public CustomerSupportController(SupportService service) {
        this.service = service;
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.tazzzo.catalog.api.docs.DocumentedRequestBodies.CustomerSupportOpen.class)))
    @PostMapping
    public ResponseEntity<SupportDtos.CustomerCase> open(HttpServletRequest request, @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        requireObject(body, CREATE_FIELDS);
        SupportCase s = service.open(p.customerId().value(), text(body, "category", true), text(body, "orderId", false),
                text(body, "subject", true), text(body, "message", true));
        return respond(HttpStatus.CREATED, SupportDtos.CustomerCase.of(s, requestId(request)));
    }

    @GetMapping
    public ResponseEntity<SupportDtos.CustomerPage> list(HttpServletRequest request,
                                                        @RequestParam(name = "page_size", required = false) String pageSize,
                                                        @RequestParam(name = "cursor", required = false) String cursor) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        for (String name : request.getParameterMap().keySet()) {
            if (!name.equals("page_size") && !name.equals("cursor")) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        }
        if (pageSize != null && !pageSize.matches("[1-9][0-9]{0,2}") || cursor != null && cursor.length() > 128) {
            throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        }
        SupportService.Page page = service.listOwn(p.customerId().value(), cursor, pageSize == null ? SupportService.DEFAULT_PAGE : Integer.parseInt(pageSize));
        return respond(HttpStatus.OK, new SupportDtos.CustomerPage(page.cases().stream().map(SupportDtos.CustomerSummary::of).toList(),
                page.nextCursor(), requestId(request)));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<SupportDtos.CustomerCase> get(HttpServletRequest request, @PathVariable String caseId) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        return respond(HttpStatus.OK, SupportDtos.CustomerCase.of(service.getOwn(p.customerId().value(), caseId), requestId(request)));
    }

    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @io.swagger.v3.oas.annotations.media.Content(
            mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    implementation = com.tazzzo.catalog.api.docs.DocumentedRequestBodies.SupportReply.class)))
    @PostMapping("/{caseId}/messages")
    public ResponseEntity<SupportDtos.CustomerCase> reply(HttpServletRequest request, @PathVariable String caseId,
                                                         @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        requireObject(body, Set.of("message"));
        return respond(HttpStatus.OK, SupportDtos.CustomerCase.of(
                service.customerReply(p.customerId().value(), caseId, text(body, "message", true)), requestId(request)));
    }

    @PostMapping("/{caseId}/close")
    public ResponseEntity<SupportDtos.CustomerCase> close(HttpServletRequest request, @PathVariable String caseId) {
        CustomerPrincipal p = CustomerPrincipalResolver.require(request);
        return respond(HttpStatus.OK, SupportDtos.CustomerCase.of(service.customerClose(p.customerId().value(), caseId), requestId(request)));
    }

    private static void requireObject(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        body.fieldNames().forEachRemaining(n -> {
            if (!allowed.contains(n)) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);   // no identifier, status or role is ever accepted
        });
    }

    private static String text(JsonNode body, String field, boolean required) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) {
            if (required) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
            return null;
        }
        if (!n.isTextual()) throw new SupportFailure(SupportFailure.Reason.INVALID_REQUEST);
        return n.asText();
    }

    private static <T> ResponseEntity<T> respond(HttpStatus status, T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static String requestId(HttpServletRequest request) {
        Object v = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return v == null ? "unknown" : v.toString();
    }
}
