package com.tazzzo.auth.session;

import com.tazzzo.auth.CustomerAccessTokenCodec;
import com.tazzzo.auth.CustomerAuthFailure;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.catalog.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PR-11C — {@code /v1/auth/session}, {@code /v1/auth/refresh}, {@code /v1/auth/logout}. All three
 * land on the PRE-EXISTING {@code /v1/auth/**} {@code PUBLIC_CONSUMER} allowlist entry from PR-11A —
 * no {@code SurfaceClassifier} change needed, and session/refresh MUST be reachable unauthenticated
 * (that is how a client obtains its first token).
 *
 * <p><b>Logout is the one exception</b>: {@code CustomerAuthFilter} explicitly SKIPS the
 * {@code PUBLIC_CONSUMER} surface (it owns {@code CUSTOMER_AUTHENTICATED} only), so this controller
 * must NOT assume the public surface protects it — it performs its OWN bearer-token authentication
 * inline, using the SAME {@link CustomerAccessTokenCodec} the filter uses, before doing anything
 * else. An invalid/missing bearer never reaches {@link CustomerSessionService#logout}.
 */
@RestController
@RequestMapping("/v1/auth")
public class SessionController {

    private final CustomerSessionService service;
    private final CustomerAccessTokenCodec accessCodec;

    public SessionController(CustomerSessionService service, CustomerAccessTokenCodec accessCodec) {
        this.service = service;
        this.accessCodec = accessCodec;
    }

    @PostMapping("/session")
    public ResponseEntity<SessionEstablishResponseDto> establish(@RequestBody SessionEstablishRequestDto body,
                                                                  HttpServletRequest req) {
        CustomerSessionService.SessionEstablishResult result = service.establishSession(body.grantId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SessionEstablishResponseDto(result.customerId(), result.accessToken(),
                        result.accessTokenExpiresIn(), result.refreshToken(), requestId(req)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponseDto> refresh(@RequestBody RefreshRequestDto body, HttpServletRequest req) {
        CustomerSessionService.RefreshResult result = service.refresh(body.refreshToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new RefreshResponseDto(result.accessToken(), result.accessTokenExpiresIn(),
                        result.refreshToken(), requestId(req)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        CustomerPrincipal principal = authenticate(req);
        service.logout(principal);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /**
     * §21 — explicit, controller-owned authentication for the ONE endpoint on this deliberately
     * public surface that must not be anonymous. Mirrors {@code CustomerAuthFilter} exactly: Bearer
     * only, no query/cookie/installationId fallback.
     *
     * @throws CustomerAuthFailure MISSING/MALFORMED/... — caught by {@link SessionExceptionHandler}
     *         and mapped to the SAME flat 401 shape {@code CustomerAuthFilter} produces.
     */
    private CustomerPrincipal authenticate(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new CustomerAuthFailure(CustomerAuthFailure.Reason.MISSING);
        }
        return accessCodec.verify(header.substring(7));
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
