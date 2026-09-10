package com.tazzzo.catalog.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumer rate-limit configuration (CAT-SEC-1 Q5-c).
 *
 * <p><b>The mode has NO production default, by design.</b> A permissive default is worse than none,
 * because it looks like a decision: a deployment that forgot to configure the limiter would start
 * happily and serve unlimited traffic. Missing or invalid configuration is a startup failure, so the
 * absence of evidence is visible rather than silently guessed.
 *
 * <pre>
 *   DISABLED  an INTENTIONAL fail-closed state — no limiter is constructed, and the consumer
 *             surface must not be exposed. It is NOT an in-memory limiter.
 *   REDIS     requires complete Redis, bucket and trusted-proxy configuration.
 * </pre>
 *
 * <p>Capacities and refill rates are deliberately not defaulted either. They are derived from load
 * measurement against a representative corpus with the limiter enabled; fixture values in test
 * profiles are not production recommendations.
 */
@ConfigurationProperties(prefix = "tazzzo.consumer-rate-limit")
public class ConsumerRateLimitProperties {

    public enum Mode { DISABLED, REDIS }

    /** No default. Unset or unrecognised is a configuration failure, never a guess. */
    private String mode;

    /**
     * The limiter's OWN Redis/Valkey endpoint, e.g. {@code redis://host:6379} or {@code rediss://…}
     * for an in-transit-encrypted ElastiCache. No default, and deliberately NOT
     * {@code spring.data.redis.*}: production admission control must not depend on whatever
     * connection defaults an autoconfiguration happens to supply, and must not be silently
     * repointed by an unrelated future Redis feature.
     */
    private String redisUrl;

    /** CIDRs whose X-Forwarded-For may be trusted (Q5-IP-1). Empty means "trust no proxy". */
    private List<String> trustedProxyCidrs = new ArrayList<>();

    private Bucket ip = new Bucket();
    private Bucket installation = new Bucket();

    public static class Bucket {
        /** Burst allowance. 0 means unconfigured. */
        private long capacity;
        /** Sustained allowance. 0 means unconfigured. */
        private double refillPerSecond;

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }

        boolean isConfigured() {
            return capacity > 0 && refillPerSecond > 0;
        }
    }

    /**
     * @throws IllegalStateException when the configuration is absent or incomplete — the startup
     *     validation failure Q5-c requires
     */
    public Mode resolvedMode() {
        if (mode == null || mode.isBlank()) {
            throw new IllegalStateException(
                    "tazzzo.consumer-rate-limit.mode is required and has NO default. "
                            + "Set DISABLED (fail-closed, consumer surface not exposed) or REDIS.");
        }
        try {
            return Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "tazzzo.consumer-rate-limit.mode must be DISABLED or REDIS, was: " + mode);
        }
    }

    /** Validates everything REDIS mode needs, so a half-configured limiter cannot start. */
    public void requireCompleteForRedis() {
        List<String> missing = new ArrayList<>();
        if (redisUrl == null || redisUrl.isBlank()) {
            missing.add("redis-url (the limiter owns its endpoint; it never inherits a default)");
        }
        if (!ip.isConfigured()) {
            missing.add("ip.capacity and ip.refill-per-second");
        }
        if (!installation.isConfigured()) {
            missing.add("installation.capacity and installation.refill-per-second");
        }
        if (trustedProxyCidrs == null || trustedProxyCidrs.isEmpty()) {
            // Not a warning: with no trusted proxy the resolver would treat the ALB itself as the
            // client, putting every consumer in one bucket. Better to refuse to start.
            missing.add("trusted-proxy-cidrs (behind a proxy this must list it; direct-exposure "
                    + "deployments must say so explicitly)");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "tazzzo.consumer-rate-limit.mode=REDIS requires: " + String.join(", ", missing));
        }
    }

    public String getRedisUrl() {
        return redisUrl;
    }

    public void setRedisUrl(String redisUrl) {
        this.redisUrl = redisUrl;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public List<String> getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs;
    }

    public Bucket getIp() {
        return ip;
    }

    public void setIp(Bucket ip) {
        this.ip = ip;
    }

    public Bucket getInstallation() {
        return installation;
    }

    public void setInstallation(Bucket installation) {
        this.installation = installation;
    }
}
