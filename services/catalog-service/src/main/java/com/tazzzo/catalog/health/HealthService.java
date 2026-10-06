package com.tazzzo.catalog.health;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.datastore.DatastoreReadiness;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimitProperties;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Liveness and readiness for the container runtime and the load balancer.
 *
 * <p><b>Liveness</b> ({@code /health/live}) is "the process serves HTTP": it never consults a dependency, so a
 * dependency outage can never make the orchestrator kill and restart every instance (a restart loop fixes nothing
 * that is outside the process).
 *
 * <p><b>Readiness</b> ({@code /health/ready}) is "route traffic here":
 * <ol>
 *   <li>the serving gate must be OPEN ({@link DatastoreReadiness}: the datastore verifier passed and startup completed;
 *       a refused process or a migration job is never ready);</li>
 *   <li>MongoDB must answer a bounded {@code ping} (required by default; {@code tazzzo.health.readiness.require-mongo});</li>
 *   <li>the rate-limiter store (Valkey/Redis) is probed and reported; it fails readiness only when
 *       {@code tazzzo.health.readiness.require-rate-limiter=true} (default false: a shared-store outage already yields
 *       503s from the fail-closed limiter, and failing every target's readiness at once would also trigger task
 *       replacement). {@code DISABLED} mode is reported, never a failure: it is a ratified configuration state.</li>
 * </ol>
 * Probe results are cached for {@value #CACHE_MILLIS} ms so several probers cannot turn into a ping storm, and each
 * probe is bounded by {@value #PROBE_TIMEOUT_MILLIS} ms on a dedicated thread, so a hung dependency cannot hang the probe.
 */
@Component
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    static final long CACHE_MILLIS = 1_000;
    static final long PROBE_TIMEOUT_MILLIS = 2_000;

    private final DatastoreReadiness readiness;
    private final BooleanSupplier mongoPing;
    private final BooleanSupplier rateLimiterPing; // null when the limiter is DISABLED
    private final boolean requireMongo;
    private final boolean requireRateLimiter;
    private final Clock clock;
    // two bounded daemon platform threads (not a scheduler: nothing runs unless a probe asks)
    private final ExecutorService prober = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon(true).name("health-probe-", 0).factory());

    private volatile HealthReport cached;
    private volatile long cachedAt = Long.MIN_VALUE;

    @Autowired
    public HealthService(DatastoreReadiness readiness, MongoDatabase db, ConsumerRateLimitProperties.Mode rateLimitMode,
                         ObjectProvider<StringRedisTemplate> consumerRateLimitRedisTemplate,
                         @Value("${tazzzo.health.readiness.require-mongo:true}") boolean requireMongo,
                         @Value("${tazzzo.health.readiness.require-rate-limiter:false}") boolean requireRateLimiter) {
        this(readiness,
                () -> db.runCommand(new Document("ping", 1)).get("ok") != null,
                rateLimitMode == ConsumerRateLimitProperties.Mode.DISABLED ? null : () -> {
                    StringRedisTemplate template = consumerRateLimitRedisTemplate.getIfAvailable();
                    if (template == null) {
                        return false;
                    }
                    String pong = template.execute((RedisConnection c) -> c.ping());
                    return "PONG".equalsIgnoreCase(pong);
                },
                requireMongo, requireRateLimiter, Clock.systemUTC());
    }

    /** Test seam: the probes are plain suppliers. */
    HealthService(DatastoreReadiness readiness, BooleanSupplier mongoPing, BooleanSupplier rateLimiterPing,
                  boolean requireMongo, boolean requireRateLimiter, Clock clock) {
        this.readiness = readiness;
        this.mongoPing = mongoPing;
        this.rateLimiterPing = rateLimiterPing;
        this.requireMongo = requireMongo;
        this.requireRateLimiter = requireRateLimiter;
        this.clock = clock;
    }

    /** Always UP: the request reached the servlet container, so the process is alive. */
    public HealthReport live() {
        return new HealthReport(true, datastoreWord(), HealthReport.SKIPPED, HealthReport.SKIPPED);
    }

    public HealthReport ready() {
        long now = clock.millis();
        HealthReport c = cached;
        if (c != null && now - cachedAt < CACHE_MILLIS) {
            return c;
        }
        HealthReport fresh = computeReady();
        cached = fresh;
        cachedAt = now;
        return fresh;
    }

    private HealthReport computeReady() {
        String datastore = datastoreWord();
        if (!"OPEN".equals(datastore)) {
            return new HealthReport(false, datastore, HealthReport.SKIPPED, HealthReport.SKIPPED);
        }
        boolean mongoUp = bounded(mongoPing, "mongo");
        String rateLimiter;
        boolean rateLimiterOk;
        if (rateLimiterPing == null) {
            rateLimiter = HealthReport.DISABLED;
            rateLimiterOk = true;
        } else {
            boolean up = bounded(rateLimiterPing, "rate_limiter");
            rateLimiter = up ? HealthReport.UP : HealthReport.DOWN;
            rateLimiterOk = up || !requireRateLimiter;
        }
        boolean up = (mongoUp || !requireMongo) && rateLimiterOk;
        return new HealthReport(up, datastore, mongoUp ? HealthReport.UP : HealthReport.DOWN, rateLimiter);
    }

    private String datastoreWord() {
        return switch (readiness.state()) {
            case OPEN -> "OPEN";
            case PENDING, VERIFIED -> "STARTING";
            case REFUSED -> "REFUSED";
            case JOB -> "JOB";
        };
    }

    /** Runs a probe with a hard time limit; any exception or timeout is DOWN, logged by component name only. */
    private boolean bounded(BooleanSupplier probe, String component) {
        CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(probe::getAsBoolean, prober);
        try {
            return Boolean.TRUE.equals(f.get(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            f.cancel(true);
            log.warn("health_probe_down component={} reason={}", component, e.getClass().getSimpleName());
            return false;
        }
    }

    @PreDestroy
    void shutdownProber() {
        prober.shutdownNow();
    }

    static Duration cacheWindow() {
        return Duration.ofMillis(CACHE_MILLIS);
    }
}
