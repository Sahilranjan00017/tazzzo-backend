package com.tazzzo.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.CustomerPrincipalResolver;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/customer/account/deletion} — the authenticated customer deletes their own account. The customer is
 * always the bearer of the token (never a body field: no identifier is accepted from the request), and the request must
 * carry the explicit intent {@code {"confirm":"DELETE"}}. Naturally idempotent: a repeat answers the same
 * {@code DELETED}; once the first call has committed, the token itself is revoked, so a later retry meets 401, which is
 * also "done". Lives on the CUSTOMER_AUTHENTICATED surface ({@code /v1/customer/**}): {@code CustomerAuthFilter} has
 * already verified the session before this runs.
 */
@RestController
public class AccountDeletionController {

    public static final String PATH = "/v1/customer/account/deletion";
    static final String CONFIRMATION = "DELETE";

    private final AccountDeletionService service;
    private final AccountDeletionObservability observability;

    public AccountDeletionController(AccountDeletionService service, AccountDeletionObservability observability) {
        this.service = service;
        this.observability = observability;
    }

    @PostMapping(PATH)
    public ResponseEntity<AccountDeletionResponseDto> delete(HttpServletRequest request,
                                                             @RequestBody(required = false) JsonNode body) {
        CustomerPrincipal principal = CustomerPrincipalResolver.require(request);
        requireConfirmation(body);
        service.delete(principal.customerId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AccountDeletionResponseDto("DELETED", requestId(request)));
    }

    private void requireConfirmation(JsonNode body) {
        if (body == null || !body.isObject() || !body.has("confirm") || !body.get("confirm").isTextual()
                || !CONFIRMATION.equals(body.get("confirm").textValue())) {
            observability.failure(AccountDeletionFailure.Reason.INVALID_REQUEST);
            throw new AccountDeletionFailure(AccountDeletionFailure.Reason.INVALID_REQUEST);
        }
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
