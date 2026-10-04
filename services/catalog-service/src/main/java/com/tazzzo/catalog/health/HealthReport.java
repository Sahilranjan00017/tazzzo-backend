package com.tazzzo.catalog.health;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a probe sees. Every value is a bounded word: no host, port, URI, user, version, error text or stack trace ever
 * appears here (the probe path is unauthenticated).
 *
 * @param up whether the probe should answer 200
 * @param datastore the serving gate: {@code OPEN} (verified and startup complete), {@code STARTING}, {@code REFUSED}
 *                  (the datastore verifier refused this process) or {@code JOB} (a migration job, never serves)
 * @param mongo {@code UP}, {@code DOWN} or {@code SKIPPED} (not probed because the gate is not open)
 * @param rateLimiter {@code UP}, {@code DOWN}, {@code DISABLED} (fail-closed mode: the consumer surface is not exposed)
 */
public record HealthReport(boolean up, String datastore, String mongo, String rateLimiter) {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    public static final String SKIPPED = "SKIPPED";
    public static final String DISABLED = "DISABLED";

    public Map<String, Object> toBody() {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("datastore", datastore);
        components.put("mongo", mongo);
        components.put("rate_limiter", rateLimiter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? UP : DOWN);
        body.put("components", components);
        return body;
    }
}
