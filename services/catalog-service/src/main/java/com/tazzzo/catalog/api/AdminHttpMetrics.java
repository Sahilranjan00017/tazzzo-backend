package com.tazzzo.catalog.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HTTP measurement for the surfaces the consumer meters ({@code tazzzo.catalog.consumer.*}) do not cover: the internal
 * ({@code /api/**}, i.e. the admin API) surface and unclassified paths. Spring's own {@code http.server.requests} stays
 * disabled (its URI-template-and-exception vocabulary is the one Q5-OBS-1 forbids), so this is the admin error-rate signal.
 *
 * <p>One timer, {@value #REQUESTS}, tagged only with:
 * <ul>
 *   <li>{@code surface}: {@code internal} or {@code unknown} ({@link SurfaceClassifier}); consumer, customer and health
 *       requests are not recorded here;</li>
 *   <li>{@code route}: the Spring pattern that matched ({@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}, for example
 *       {@code /api/v1/products/{id}}), which is declared in code and therefore a fixed, small set. A request no handler
 *       matched (an auth refusal, a body-limit refusal, a 404, a scan) is {@code unmatched}. The raw URI is never read into a
 *       tag, and a hard cap of {@value #MAX_ROUTES} distinct routes folds any surprise into {@code other};</li>
 *   <li>{@code status_class}: {@code 2xx}, {@code 3xx}, {@code 4xx}, {@code 5xx} or {@code other}.</li>
 * </ul>
 * The timer's count is the request count, so the error rate of a route is its 5xx (or 4xx) count over its total.
 */
@Component
public class AdminHttpMetrics {

    private static final Logger log = LoggerFactory.getLogger(AdminHttpMetrics.class);

    public static final String REQUESTS = "admin_http_requests";
    public static final String UNMATCHED = "unmatched";
    public static final String OTHER = "other";
    public static final Set<String> ALLOWED_TAG_KEYS = Set.of("surface", "route", "status_class");
    static final int MAX_ROUTES = 300;
    static final int MAX_ROUTE_LENGTH = 120;

    private final MeterRegistry registry;
    private final Set<String> routes = ConcurrentHashMap.newKeySet();

    public AdminHttpMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** True for the surfaces this meter covers. */
    static boolean covers(SurfaceClassifier.Surface surface) {
        return surface == SurfaceClassifier.Surface.INTERNAL || surface == SurfaceClassifier.Surface.UNKNOWN;
    }

    public void record(HttpServletRequest request, SurfaceClassifier.Surface surface, int status, long nanos) {
        try {
            Timer.builder(REQUESTS).description("admin (internal) and unclassified HTTP requests by matched route and status class")
                    .tag("surface", surface.name().toLowerCase(java.util.Locale.ROOT))
                    .tag("route", routeLabel(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)))
                    .tag("status_class", statusClass(status))
                    .register(registry).record(Duration.ofNanos(Math.max(0, nanos)));
        } catch (RuntimeException e) {
            log.warn("admin http metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }

    /** The matched pattern if it is a plain, short, already-seen-or-room-for-it pattern; never anything derived from the URI. */
    String routeLabel(Object matchedPattern) {
        if (!(matchedPattern instanceof String p) || p.isEmpty() || p.equals("/**") || p.equals("/*")) {
            return UNMATCHED;
        }
        if (p.length() > MAX_ROUTE_LENGTH || !p.startsWith("/") || p.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            return OTHER;
        }
        if (routes.contains(p)) {
            return p;
        }
        synchronized (routes) {   // slow path, only for a route not seen before: the size check and the add are one step
            if (routes.contains(p)) {
                return p;
            }
            if (routes.size() >= MAX_ROUTES) {
                return OTHER;
            }
            routes.add(p);
            return p;
        }
    }

    static String statusClass(int status) {
        return status >= 200 && status < 600 ? (status / 100) + "xx" : OTHER;
    }
}
