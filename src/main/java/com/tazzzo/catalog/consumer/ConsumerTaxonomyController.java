package com.tazzzo.catalog.consumer;

import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.ClientIpUnresolvableException;
import com.tazzzo.catalog.ratelimit.InstallationIdResolver;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public catalogue transport (CLIENT-CONTRACT-1). Q4-b: this namespace is PUBLIC BY DECISION —
 * {@link com.tazzzo.catalog.api.SurfaceClassifier} bypasses authentication for it entirely, so a
 * bearer header is irrelevant to authority here. Anonymous, bogus, reader and cms callers all reach
 * the same capability.
 *
 * <p><b>Q4-c:</b> transport only. It resolves request identity, calls the consumer service, and
 * returns consumer DTOs. It never constructs {@code ApiDtos.*}, never returns a {@code Document},
 * and never reaches persistence — enforced by {@code ConsumerTransportGuardIT}.
 */
@RestController
@RequestMapping("/catalog/v1")
public class ConsumerTaxonomyController {

    private final ConsumerTaxonomyService taxonomy;
    private final ClientIpResolver clientIps;
    private final ConsumerObservability observe;

    public ConsumerTaxonomyController(ConsumerTaxonomyService taxonomy, ClientIpResolver clientIps,
                                      ConsumerObservability observe) {
        this.taxonomy = taxonomy;
        this.clientIps = clientIps;
        this.observe = observe;
    }

    /**
     * ROOT-1. {@code release} omitted resolves the current pointer ONCE (TR2-CURRENT-1).
     *
     * <p><b>This is the request clock boundary (Q5-OBS-1).</b> The timer starts before client
     * identity is resolved and stops after the service returns or throws, so every outcome —
     * success, not_found, rate_limited, unavailable — is measured from the same point with a REAL
     * elapsed duration. The outcome is derived from the typed failure that escapes, so the metric
     * cannot disagree with the status the caller sees. Exactly one request observation per request.
     */
    @GetMapping("/categories")
    public ConsumerDtos.RootResponse categories(
            @RequestParam(name = "release", required = false) String release,
            HttpServletRequest request) {
        long started = System.nanoTime();
        ConsumerObservability.Outcome outcome = ConsumerObservability.Outcome.UNAVAILABLE;
        try {
            ConsumerDtos.RootResponse response = taxonomy.root(release, clientIp(request),
                    InstallationIdResolver.resolve(request.getHeader(InstallationIdResolver.HEADER)));
            outcome = ConsumerObservability.Outcome.SUCCESS;
            return response;
        } catch (ConsumerFailures.NotFound e) {
            outcome = ConsumerObservability.Outcome.NOT_FOUND;
            throw e;
        } catch (ConsumerFailures.RateLimited e) {
            outcome = ConsumerObservability.Outcome.RATE_LIMITED;
            throw e;
        } finally {
            observe.request(ConsumerObservability.Route.ROOT, outcome,
                    Duration.ofNanos(System.nanoTime() - started));
        }
    }

    /**
     * Q5-IP-2a. An unresolvable client identity is an infrastructure fault, not a rate decision:
     * the alternative — falling back to the proxy's address — would put every client behind it in
     * ONE bucket. It surfaces as Unavailable and is timed by the boundary above like any other
     * outcome; nothing here records a placeholder duration.
     */
    private String clientIp(HttpServletRequest request) {
        try {
            return clientIps.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"));
        } catch (ClientIpUnresolvableException e) {
            throw new ConsumerFailures.Unavailable("client identity unresolvable");
        }
    }
}
