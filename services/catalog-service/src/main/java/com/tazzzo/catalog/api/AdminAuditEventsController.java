package com.tazzzo.catalog.api;

import com.tazzzo.admin.audit.AuditEventPage;
import com.tazzzo.admin.audit.AuditEventQuery;
import com.tazzzo.admin.audit.AuditEventReader;
import com.tazzzo.admin.audit.AuditQueryRejected;
import com.tazzzo.admin.audit.AuditReadObservability;
import com.tazzzo.admin.audit.RawQuerySyntax;
import com.tazzzo.admin.auth.AdminPrincipal;
import com.tazzzo.admin.auth.AdminPrincipalResolver;
import com.tazzzo.catalog.api.ApiDtos.AuditEventDto;
import com.tazzzo.catalog.api.ApiDtos.AuditEventsResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/admin/audit-events}: the READ-ONLY, paginated, filterable view of the persisted audit ledgers for
 * per-person human admins holding {@code audit-reader}. INTERNAL surface, so {@link ApiAuthFilter} has authenticated the
 * caller (401 never reaches here). Authorization is checked FIRST, before any parameter is read, so a caller without the
 * permission learns nothing about the query grammar. GET is the only mapping: every other method is refused (403 for a
 * principal that may not write, 405 otherwise) and nothing here can change an audit row.
 *
 * <p>Reads are not themselves written to an audit ledger (no read-audit policy exists in this backend; see the docs); they
 * are counted by a bounded metric and logged with the request id and result size only.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuditEventsController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditEventsController.class);

    private final AuditEventReader reader;
    private final AuditReadObservability observability;

    public AdminAuditEventsController(AuditEventReader reader, AuditReadObservability observability) {
        this.reader = reader;
        this.observability = observability;
    }

    @GetMapping("/audit-events")
    public AuditEventsResponse list(HttpServletRequest request) {
        AdminPrincipal principal = AdminPrincipalResolver.require(request);
        if (!principal.canReadAudit()) {
            observability.record(AuditReadObservability.Outcome.FORBIDDEN);
            log.warn("admin_audit_read_denied actor_type={} request_id={}", principal.actorType(),
                    request.getAttribute(RequestIdFilter.REQUEST_ID));
            throw new AuditReadForbiddenException();
        }
        AuditEventPage page;
        try {
            // raw syntax first: the container silently drops an undecodable parameter, which must fail closed, not widen
            java.util.Map<String, String[]> parameters = RawQuerySyntax.bind(request::getParameterMap);
            RawQuerySyntax.requireWellFormed(request.getQueryString(), parameters);
            page = reader.read(AuditEventQuery.parse(parameters));
        } catch (AuditQueryRejected e) {
            observability.record(AuditReadObservability.Outcome.INVALID);
            throw e;
        }
        observability.record(AuditReadObservability.Outcome.SERVED);
        log.info("admin_audit_read results={} has_more={} request_id={}", page.items().size(),
                page.nextCursor().isPresent(), request.getAttribute(RequestIdFilter.REQUEST_ID));
        return new AuditEventsResponse(page.items().stream().map(r -> new AuditEventDto(r.id(),
                r.occurredAt().toString(), r.action(), r.targetType(), r.targetId(), r.actorType().name(), r.actorId(),
                r.credentialId(), r.requestId())).toList(), page.nextCursor().orElse(null));
    }
}
