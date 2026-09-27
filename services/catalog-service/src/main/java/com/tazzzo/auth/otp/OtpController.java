package com.tazzzo.auth.otp;

import com.tazzzo.catalog.api.RequestIdFilter;
import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.ClientIpUnresolvableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PR-11B — {@code /v1/auth/otp/**}. Already {@code PUBLIC_CONSUMER} by
 * {@code SurfaceClassifier}'s explicit {@code /v1/auth/**} allowlist entry (PR-11A) — no
 * classifier change needed. {@code CustomerAuthFilter} does not run here (wrong surface) and
 * {@code ApiAuthFilter} does not demand a service token here (also wrong surface); any
 * {@code Authorization} header present is irrelevant to these endpoints.
 *
 * <p>Does NOT create a customer, a session, or issue any access/refresh token — see
 * {@code OtpService}'s and {@code OtpVerifiedGrantRepository}'s class-level documentation.
 */
@RestController
@RequestMapping("/v1/auth/otp")
public class OtpController {

    private final OtpService service;
    private final ClientIpResolver ipResolver;

    public OtpController(OtpService service, ClientIpResolver ipResolver) {
        this.service = service;
        this.ipResolver = ipResolver;
    }

    @PostMapping("/request")
    public ResponseEntity<OtpRequestResponseDto> request(@RequestBody OtpRequestRequestDto body,
                                                          HttpServletRequest req) {
        String clientIp = resolveIp(req);
        OtpRequestResult result = service.request(body.phone(), clientIp);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new OtpRequestResponseDto(result.challengeId(), result.expiresInSeconds(),
                        result.resendAfterSeconds(), requestId(req)));
    }

    @PostMapping("/verify")
    public ResponseEntity<OtpVerifyResponseDto> verify(@RequestBody OtpVerifyRequestDto body,
                                                        HttpServletRequest req) {
        String clientIp = resolveIp(req);
        OtpVerifyResult result = service.verify(body.challengeId(), body.otp(), clientIp);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new OtpVerifyResponseDto(result.challengeId(), true, result.grantId(), requestId(req)));
    }

    private String resolveIp(HttpServletRequest req) {
        try {
            return ipResolver.resolve(req.getRemoteAddr(), req.getHeader("X-Forwarded-For"));
        } catch (ClientIpUnresolvableException e) {
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
    }

    private static String requestId(HttpServletRequest req) {
        Object value = req.getAttribute(RequestIdFilter.REQUEST_ID);
        return value == null ? "unknown" : value.toString();
    }
}
