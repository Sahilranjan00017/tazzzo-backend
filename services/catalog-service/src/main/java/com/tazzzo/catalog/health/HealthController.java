package com.tazzzo.catalog.health;

import com.tazzzo.catalog.api.SurfaceClassifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The two probe endpoints (surface {@code HEALTH}: unauthenticated, exact paths, never cached). Wire the container
 * runtime's health check to {@code /health/live} and the load balancer's target health check to {@code /health/ready}.
 * Bodies contain bounded words only; see {@link HealthReport}.
 */
@RestController
public class HealthController {

    private final HealthService health;

    public HealthController(HealthService health) {
        this.health = health;
    }

    @GetMapping(SurfaceClassifier.HEALTH_LIVE)
    public ResponseEntity<Map<String, Object>> live() {
        return respond(health.live());
    }

    @GetMapping(SurfaceClassifier.HEALTH_READY)
    public ResponseEntity<Map<String, Object>> ready() {
        return respond(health.ready());
    }

    private static ResponseEntity<Map<String, Object>> respond(HealthReport report) {
        return ResponseEntity.status(report.up() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(report.toBody());
    }
}
