package com.tazzzo.catalog.consumer;

import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.ClientIpUnresolvableException;
import com.tazzzo.catalog.ratelimit.InstallationIdResolver;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ConsumerProductListService products;
    private final ConsumerProductDetailService details;
    private final ClientIpResolver clientIps;
    private final ConsumerObservability observe;

    public ConsumerTaxonomyController(ConsumerTaxonomyService taxonomy,
                                      ConsumerProductListService products,
                                      ConsumerProductDetailService details,
                                      ClientIpResolver clientIps,
                                      ConsumerObservability observe) {
        this.taxonomy = taxonomy;
        this.products = products;
        this.details = details;
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
    public ConsumerDtos.NodeListResponse categories(
            @RequestParam(name = "release", required = false) String release,
            HttpServletRequest request) {
        return measured(ConsumerObservability.Route.ROOT, () ->
                taxonomy.root(release, identity(request)));
    }

    /** CHILD-1. Any taxonomy node id; a visible vertical answers 200 with empty items. */
    @GetMapping("/categories/{nodeId}/children")
    public ConsumerDtos.NodeListResponse children(
            @PathVariable("nodeId") String nodeId,
            @RequestParam(name = "release", required = false) String release,
            HttpServletRequest request) {
        return measured(ConsumerObservability.Route.CHILDREN, () ->
                taxonomy.children(nodeId, release, identity(request)));
    }

    /**
     * LIST-1. {@code page_size} and {@code cursor} arrive as RAW strings on purpose: a value the
     * framework could not bind would fail before this method runs and escape the measured
     * boundary, so the service parses them and a bad one is this route's own
     * {@code invalid_request} / {@code invalid_cursor} outcome (Q5-OBS-1b).
     */
    @GetMapping("/categories/{nodeId}/products")
    public ConsumerDtos.ProductListResponse productsUnder(
            @PathVariable("nodeId") String nodeId,
            @RequestParam(name = "release", required = false) String release,
            @RequestParam(name = "page_size", required = false) String pageSize,
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpServletRequest request) {
        return measured(ConsumerObservability.Route.LIST, () ->
                products.list(nodeId, release, pageSize, cursor, identity(request)));
    }

    /**
     * PDP-1 (PDP-CTRL-1: on this controller, so identity resolution, the measured boundary, the
     * consumer advice and the transport guard all already apply). {@code release} omitted resolves
     * the current pointer ONCE.
     */
    @GetMapping("/products/{productId}")
    public ConsumerDtos.ProductDetailResponse product(
            @PathVariable("productId") String productId,
            @RequestParam(name = "release", required = false) String release,
            HttpServletRequest request) {
        return measured(ConsumerObservability.Route.PDP, () ->
                details.detail(productId, release, identity(request)));
    }

    /**
     * The ONE request clock boundary for every consumer route (Q5-OBS-1): started before identity
     * resolution, recorded exactly once in {@code finally}, outcome derived from the typed failure
     * that escapes so the metric cannot disagree with the status the caller sees.
     */
    private <T> T measured(ConsumerObservability.Route route, java.util.function.Supplier<T> call) {
        long started = System.nanoTime();
        ConsumerObservability.Outcome outcome = ConsumerObservability.Outcome.UNAVAILABLE;
        try {
            T response = call.get();
            outcome = ConsumerObservability.Outcome.SUCCESS;
            return response;
        } catch (ConsumerFailures.NotFound e) {
            outcome = ConsumerObservability.Outcome.NOT_FOUND;
            throw e;
        } catch (ConsumerFailures.RateLimited e) {
            outcome = ConsumerObservability.Outcome.RATE_LIMITED;
            throw e;
        } catch (ConsumerFailures.InvalidRequest e) {
            outcome = ConsumerObservability.Outcome.INVALID_REQUEST;
            throw e;
        } catch (ConsumerFailures.InvalidCursor e) {
            outcome = ConsumerObservability.Outcome.INVALID_CURSOR;
            throw e;
        } finally {
            observe.request(route, outcome, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    /** Identity is resolved ONCE per request, inside the measured boundary, and never re-derived. */
    private ConsumerIdentity identity(HttpServletRequest request) {
        return new ConsumerIdentity(clientIp(request),
                InstallationIdResolver.resolve(request.getHeader(InstallationIdResolver.HEADER)));
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
